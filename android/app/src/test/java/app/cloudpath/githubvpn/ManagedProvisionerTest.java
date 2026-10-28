package app.cloudpath.githubvpn;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static org.junit.Assert.*;

public class ManagedProvisionerTest {
    private static final String NAME = "cloudpath-vpn-0123456789abcdef01234567";
    private static final String NAME2 = "cloudpath-vpn-fedcba9876543210fedcba98";
    private static final ManagedProvisioner.Assets ASSETS = name -> ("packaged:" + name).getBytes(StandardCharsets.UTF_8);

    private static JSONObject repository(String login, String name, boolean privateRepo, String description) {
        try {
            return new JSONObject().put("private", privateRepo).put("full_name", login + "/" + name)
                    .put("owner", new JSONObject().put("login", login)).put("description", description).put("default_branch", "main");
        } catch (JSONException e) { throw new AssertionError(e); }
    }
    private static final class MemoryState implements ManagedProvisioner.State {
        final Map<String, String> pending = new HashMap<>(), ready = new HashMap<>();
        private String key(String login) { return login.toLowerCase(Locale.ROOT); }
        @Override public String pendingRepository(String login) { return pending.getOrDefault(key(login), ""); }
        @Override public void savePendingRepository(String login, String name) { pending.put(key(login), name); }
        @Override public void finishRepository(String login, String fullName) { pending.remove(key(login)); ready.put(key(login), fullName); }
    }
    private static final class FakeApi implements ManagedProvisioner.Api {
        final String login;
        final MemoryState state;
        JSONObject repo;
        int creates;
        boolean privateRequested, raceOnCreate, raceOnFile;
        String failOnceOn;
        final Map<String, byte[]> files = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        FakeApi(String login, MemoryState state) { this.login = login; this.state = state; }
        @Override public String authenticatedLogin() { return login; }
        @Override public JSONObject repositoryOrNull(String fullName) { return repo; }
        @Override public JSONObject createPrivateRepository(String name, String description) throws IOException {
            // A crash after POST must still be resumable; candidate is persisted first.
            assertEquals(name, state.pendingRepository(login));
            creates++; privateRequested = true; repo = repository(login, name, true, description);
            if (raceOnCreate) throw new GithubApi.ApiException(422, "already created");
            return repo;
        }
        @Override public byte[] readRepositoryFile(String fullName, String path) { return files.get(path); }
        @Override public void createRepositoryFile(String fullName, String branch, String path, byte[] contents) throws IOException {
            assertEquals("main", branch);
            if (path.equals(failOnceOn)) { failOnceOn = null; throw new IOException("simulated offline"); }
            if (files.containsKey(path)) throw new AssertionError("must not overwrite");
            files.put(path, Arrays.copyOf(contents, contents.length)); writes.add(path);
            if (raceOnFile) { raceOnFile = false; throw new GithubApi.ApiException(409, "concurrent writer"); }
        }
    }
    private static ManagedProvisioner installer(FakeApi api, MemoryState state, String name) {
        return new ManagedProvisioner(api, state, ASSETS, () -> name);
    }
    @Test public void createsPrivateRepositoryForTheAuthenticatedUserAndInstallsWorkflowLast() throws Exception {
        MemoryState state = new MemoryState(); FakeApi api = new FakeApi("Alice", state);
        assertEquals("Alice/" + NAME, installer(api, state, NAME).ensureReady("Alice"));
        assertTrue(api.privateRequested); assertEquals(1, api.creates);
        assertEquals("Alice/" + NAME, state.ready.get("alice")); assertEquals("", state.pendingRepository("Alice"));
        assertEquals(7, api.writes.size()); assertEquals("cloudpath-managed.txt", api.writes.get(0));
        assertEquals(".github/workflows/github-vpn-session.yml", api.writes.get(6));
        for (ManagedProvisioner.FileSpec file : ManagedProvisioner.FILES)
            assertArrayEquals(ASSETS.read(file.asset), api.files.get(file.path));
    }
    @Test public void interruptedUploadResumesSameRepositoryWithoutOverwriting() throws Exception {
        MemoryState state = new MemoryState(); FakeApi api = new FakeApi("alice", state); api.failOnceOn = "agent/relays.mjs";
        try { installer(api, state, NAME).ensureReady("alice"); fail("expected offline failure"); } catch (IOException expected) { }
        assertEquals(NAME, state.pendingRepository("alice")); assertTrue(state.ready.isEmpty());
        int writtenBeforeRetry = api.writes.size();
        installer(api, state, NAME2).ensureReady("alice");
        assertEquals(1, api.creates); assertEquals(7, api.writes.size()); assertTrue(writtenBeforeRetry > 0);
        assertEquals("alice/" + NAME, state.ready.get("alice"));
    }
    @Test public void neverTouchesUnrelatedOrPublicRepository() throws Exception {
        for (boolean isPrivate : new boolean[]{true, false}) {
            MemoryState state = new MemoryState(); state.savePendingRepository("alice", NAME);
            FakeApi api = new FakeApi("alice", state);
            api.repo = repository("alice", NAME, isPrivate, isPrivate ? "someone else's repository" : ManagedProvisioner.description(NAME));
            try { installer(api, state, NAME).ensureReady("alice"); fail("unrelated repository accepted"); } catch (IOException expected) { }
            assertEquals(0, api.creates); assertTrue(api.writes.isEmpty()); assertTrue(state.ready.isEmpty());
        }
    }
    @Test public void changedExistingFilesAreNeverOverwritten() throws Exception {
        MemoryState state = new MemoryState(); FakeApi api = new FakeApi("alice", state); api.failOnceOn = "agent/relays.mjs";
        try { installer(api, state, NAME).ensureReady("alice"); fail(); } catch (IOException expected) { }
        byte[] edited = "user edit".getBytes(StandardCharsets.UTF_8); api.files.put("agent/gt-agent.mjs", edited);
        int before = api.writes.size();
        try { installer(api, state, NAME).ensureReady("alice"); fail("overwrote user data"); } catch (IOException expected) { }
        assertArrayEquals(edited, api.files.get("agent/gt-agent.mjs")); assertEquals(before, api.writes.size());
    }
    @Test public void refusesToCreateForAnAccountDifferentFromTheToken() throws Exception {
        MemoryState state = new MemoryState(); FakeApi api = new FakeApi("bob", state);
        try { installer(api, state, NAME).ensureReady("alice"); fail("accepted wrong account"); } catch (IOException expected) { }
        assertEquals(0, api.creates); assertTrue(state.pending.isEmpty());
    }
    @Test public void differentAccountsKeepDifferentRepositoryRecords() throws Exception {
        MemoryState state = new MemoryState();
        installer(new FakeApi("alice", state), state, NAME).ensureReady("alice");
        installer(new FakeApi("bob", state), state, NAME2).ensureReady("bob");
        assertEquals("alice/" + NAME, state.ready.get("alice")); assertEquals("bob/" + NAME2, state.ready.get("bob"));
    }
    @Test public void recoversSafeRacesWhenRepoOrIdenticalFileWasCreatedConcurrently() throws Exception {
        MemoryState state = new MemoryState(); FakeApi api = new FakeApi("alice", state); api.raceOnCreate = true; api.raceOnFile = true;
        assertEquals("alice/" + NAME, installer(api, state, NAME).ensureReady("alice"));
        assertEquals(1, api.creates); assertEquals(7, api.writes.size());
    }
}
