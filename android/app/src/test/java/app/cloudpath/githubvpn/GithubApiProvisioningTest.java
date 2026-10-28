package app.cloudpath.githubvpn;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public class GithubApiProvisioningTest {
    private static final String NAME = "cloudpath-vpn-0123456789abcdef01234567";
    private static final class FakeTransport implements GithubApi.Transport {
        String base, method, path; JSONObject data; boolean auth; int calls;
        GithubApi.Response response = new GithubApi.Response(200, "{}");
        @Override public GithubApi.Response request(String base, String method, String path, JSONObject data, boolean auth) {
            this.base = base; this.method = method; this.path = path; this.data = data; this.auth = auth; calls++;
            return response;
        }
    }
    @Test public void deviceFlowUsesOfficialEndpointAndRequestsBothScopesWithoutAuthentication() throws Exception {
        FakeTransport t = new FakeTransport(); GithubApi.beginDevice("public-client-id", new GithubApi("", t));
        assertEquals("https://github.com", t.base); assertEquals("POST", t.method); assertEquals("/login/device/code", t.path);
        assertFalse(t.auth); assertEquals("public-client-id", t.data.getString("client_id")); assertEquals("repo workflow", t.data.getString("scope"));
    }
    @Test public void refreshUsesTheOfficialDeviceFlowEndpointWithoutAClientSecret() throws Exception {
        FakeTransport t = new FakeTransport();
        GithubApi.refreshDevice("public-client-id", "refresh-token", new GithubApi("", t));
        assertEquals("https://github.com", t.base); assertEquals("POST", t.method); assertEquals("/login/oauth/access_token", t.path);
        assertFalse(t.auth); assertEquals("public-client-id", t.data.getString("client_id"));
        assertEquals("refresh_token", t.data.getString("grant_type")); assertEquals("refresh-token", t.data.getString("refresh_token"));
        assertFalse(t.data.has("client_secret"));
    }
    @Test public void unauthorizedApiRequestIsRetriedOnlyOnceWithRefreshedToken() throws Exception {
        final int[] requests = {0}, refreshes = {0};
        GithubApi.Transport transport = (base, method, path, data, auth) -> {
            requests[0]++; return new GithubApi.Response(requests[0] == 1 ? 401 : 200, "{\"login\":\"alice\"}");
        };
        GithubApi.TokenSource source = new GithubApi.TokenSource() {
            @Override public String accessToken() { return "old"; }
            @Override public String refreshAfterUnauthorized(String rejected) {
                assertEquals("old", rejected); refreshes[0]++; return "new";
            }
        };
        assertEquals("alice", new GithubApi(source, transport).user().getString("login"));
        assertEquals(2, requests[0]); assertEquals(1, refreshes[0]);
    }
    @Test public void createsOnlyAPrivateInitializedRepositoryOnTheAuthenticatedAccount() throws Exception {
        FakeTransport t = new FakeTransport(); t.response = new GithubApi.Response(201, "{}");
        GithubApi api = new GithubApi("not-a-real-token", t); api.createPrivateRepository(NAME, ManagedProvisioner.description(NAME));
        assertEquals("https://api.github.com", t.base); assertEquals("POST", t.method); assertEquals("/user/repos", t.path); assertTrue(t.auth);
        assertEquals(NAME, t.data.getString("name")); assertTrue(t.data.getBoolean("private")); assertTrue(t.data.getBoolean("auto_init"));
        try { api.createPrivateRepository("some-existing-repo", "description"); fail("invalid managed name"); } catch (IOException expected) { }
        assertEquals(1, t.calls);
    }
    @Test public void repositoryFileRequestsUseKnownPathsAndEncodeContentWithoutOverwriteSha() throws Exception {
        FakeTransport t = new FakeTransport(); GithubApi api = new GithubApi("not-a-real-token", t);
        byte[] bytes = "example\n".getBytes(StandardCharsets.UTF_8);
        JSONObject body = new JSONObject().put("type", "file").put("encoding", "base64")
                .put("content", Base64.getEncoder().encodeToString(bytes));
        t.response = new GithubApi.Response(200, body.toString());
        assertArrayEquals(bytes, api.readRepositoryFile("alice/" + NAME, "agent/gt-agent.mjs"));
        assertEquals("GET", t.method); assertEquals("/repos/alice/" + NAME + "/contents/agent/gt-agent.mjs", t.path); assertTrue(t.auth);
        t.response = new GithubApi.Response(201, "{}");
        api.createRepositoryFile("alice/" + NAME, "main", ".github/workflows/github-vpn-session.yml", bytes);
        assertEquals("PUT", t.method); assertEquals("/repos/alice/" + NAME + "/contents/.github/workflows/github-vpn-session.yml", t.path);
        assertEquals("main", t.data.getString("branch")); assertEquals(Base64.getEncoder().encodeToString(bytes), t.data.getString("content"));
        assertFalse(t.data.has("sha")); // No update/overwrite request can be made by this method.
        try { api.createRepositoryFile("alice/" + NAME, "main", "../../other", bytes); fail("invalid path"); } catch (IOException expected) { }
        assertEquals(2, t.calls);
    }
    @Test public void distinguishesMissingFilesFromDeniedAccess() throws Exception {
        FakeTransport t = new FakeTransport(); GithubApi api = new GithubApi("not-a-real-token", t);
        t.response = new GithubApi.Response(404, "{}");
        assertNull(api.repositoryOrNull("alice/" + NAME));
        assertNull(api.readRepositoryFile("alice/" + NAME, "agent/gt-agent.mjs"));
        t.response = new GithubApi.Response(403, "{}");
        try { api.readRepositoryFile("alice/" + NAME, "agent/gt-agent.mjs"); fail("permission error ignored"); }
        catch (GithubApi.ApiException e) { assertEquals(403, e.status); }
    }
}
