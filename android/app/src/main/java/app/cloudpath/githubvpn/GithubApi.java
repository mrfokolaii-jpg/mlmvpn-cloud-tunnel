package app.cloudpath.githubvpn;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Fixed GitHub endpoints only; no credential is sent to a redirect or arbitrary host. */
final class GithubApi implements ManagedProvisioner.Api {
    private static final String API = "https://api.github.com";
    private static final String WEB = "https://github.com";
    private static final String WORKFLOW = "github-vpn-session.yml";
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 18_000;
    // The only names the DNS-over-HTTPS fallback may ever be pointed at.
    private static final Set<String> PINNABLE = new HashSet<>(Arrays.asList("github.com", "api.github.com"));
    private final String token;
    private final TokenSource tokenSource;
    private final Transport transport;
    interface TokenSource {
        String accessToken() throws IOException;
        String refreshAfterUnauthorized(String rejectedToken) throws IOException;
    }
    interface Transport { Response request(String base, String method, String path, JSONObject data, boolean auth) throws IOException; }
    GithubApi(String token) { this(token, null); }
    GithubApi(String token, Transport transport) { this.token = token == null ? "" : token; this.tokenSource = null; this.transport = transport; }
    GithubApi(TokenSource tokenSource) { this(tokenSource, null); }
    GithubApi(TokenSource tokenSource, Transport transport) { this.token = ""; this.tokenSource = tokenSource; this.transport = transport; }

    static final class ApiException extends IOException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
    static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }
    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n, size = 0;
            while ((n = src.read(buf)) != -1) {
                size += n;
                if (size > 1_500_000) throw new IOException("GitHub response is too large");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    private Response request(String base, String method, String path, JSONObject data, boolean auth) throws IOException {
        String access = auth && tokenSource != null ? tokenSource.accessToken() : token;
        Response response = requestOnce(base, method, path, data, auth, access);
        if (auth && response.status == 401 && tokenSource != null) {
            // 401 is a definite rejection, so retrying once with a rotated token cannot duplicate a write.
            String refreshed = tokenSource.refreshAfterUnauthorized(access);
            if (!refreshed.isEmpty() && !refreshed.equals(access)) response = requestOnce(base, method, path, data, true, refreshed);
        }
        return response;
    }
    private Response requestOnce(String base, String method, String path, JSONObject data, boolean auth, String access) throws IOException {
        if (transport != null) return transport.request(base, method, path, data, auth);
        String host = fixedHost(base);
        byte[] body = data == null ? null : data.toString().getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = headersFor(base, auth, access, body != null);
        IOException noAnswer = null;
        try {
            return viaPlatformResolver(base + path, method, body, headers);
        } catch (IOException failure) {
            // A platform stack may wrap the lookup failure; only a name-resolution failure takes
            // this detour, everything else is reported as before.
            if (!NetErrors.isDns(failure)) throw failure;
            noAnswer = failure;
        }
        // Nothing left this device, so repeating the same request on a pinned address cannot
        // duplicate a write. The name — not the address — is what TLS is checked against.
        List<String> addresses = DohResolver.resolve(host);
        IOException last = noAnswer;
        for (String address : addresses) {
            try {
                PinnedHttps.Response pinned = PinnedHttps.request(
                        host, address, method, path, headers, body, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
                return new Response(pinned.status, pinned.body);
            } catch (IOException failure) {
                last = failure;
            }
        }
        if (addresses.isEmpty()) throw new IOException(NetErrors.DNS, noAnswer);
        throw new IOException(NetErrors.describe(last, "اتصال به GitHub برقرار نشد"), last);
    }
    /** The URL's host, and only if it is one of the two fixed GitHub names. */
    private static String fixedHost(String base) throws IOException {
        String host = new URL(base).getHost();
        if (host == null) throw new IOException("invalid GitHub endpoint");
        host = host.toLowerCase(Locale.ROOT);
        if (!PINNABLE.contains(host)) throw new IOException("unexpected GitHub host");
        return host;
    }
    private static Map<String, String> headersFor(String base, boolean auth, String access, boolean hasBody) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (base.equals(API)) {
            headers.put("Accept", "application/vnd.github+json");
            headers.put("X-GitHub-Api-Version", "2022-11-28");
        } else {
            headers.put("Accept", "application/json");
        }
        headers.put("User-Agent", "cloudpath-vpn-android");
        headers.put("Cache-Control", "no-store");
        if (hasBody) headers.put("Content-Type", "application/json; charset=utf-8");
        if (auth && access != null && !access.isEmpty()) headers.put("Authorization", "Bearer " + access);
        return headers;
    }
    private static Response viaPlatformResolver(String url, String method, byte[] body, Map<String, String> headers) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestMethod(method);
            for (Map.Entry<String, String> header : headers.entrySet())
                c.setRequestProperty(header.getKey(), header.getValue());
            if (body != null) {
                c.setDoOutput(true);
                try (OutputStream out = c.getOutputStream()) { out.write(body); }
            }
            int status = c.getResponseCode();
            String text = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            return new Response(status, text);
        } finally { c.disconnect(); }
    }
    private static JSONObject json(String text) throws IOException {
        try { return new JSONObject(text == null || text.isEmpty() ? "{}" : text); }
        catch (Exception e) { throw new IOException("پاسخ گیت‌هاب قابل خواندن نیست", e); }
    }
    private static void requireOk(Response response) throws IOException {
        if (response.status >= 200 && response.status < 300) return;
        String message = "خطای GitHub (" + response.status + ")";
        if (response.status == 401) message = "ورود GitHub منقضی شده؛ دوباره وارد شو";
        else if (response.status == 403) message = "GitHub دسترسی یا سهمیه را محدود کرده است";
        else if (response.status == 404) message = "مخزن یا فایل workflow پیدا نشد";
        else if (response.status == 422) message = "GitHub درخواست را نپذیرفت";
        throw new ApiException(response.status, message);
    }
    private static String repoPath(String repo) throws IOException {
        if (!SessionRules.validRepo(repo)) throw new IOException("نام مخزن نامعتبر است؛ owner/repository وارد کن");
        return "/repos/" + repo;
    }

    static JSONObject beginDevice(String clientId) throws IOException { return beginDevice(clientId, new GithubApi("")); }
    static JSONObject beginDevice(String clientId, GithubApi api) throws IOException {
        if (clientId == null || !clientId.matches("[A-Za-z0-9_.-]{8,100}")) throw new IOException("Client ID معتبر نیست");
        try {
            Response r = api.request(WEB, "POST", "/login/device/code",
                    new JSONObject().put("client_id", clientId).put("scope", "repo workflow"), false);
            requireOk(r);
            return json(r.body);
        } catch (org.json.JSONException e) { throw new IOException("invalid request", e); }
    }
    static JSONObject pollDevice(String clientId, String deviceCode) throws IOException {
        try {
            Response r = new GithubApi("").request(WEB, "POST", "/login/oauth/access_token",
                    new JSONObject().put("client_id", clientId).put("device_code", deviceCode)
                            .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code"), false);
            requireOk(r);
            return json(r.body);
        } catch (org.json.JSONException e) { throw new IOException("invalid request", e); }
    }
    static JSONObject refreshDevice(String clientId, String refreshToken) throws IOException {
        return refreshDevice(clientId, refreshToken, new GithubApi(""));
    }
    static JSONObject refreshDevice(String clientId, String refreshToken, GithubApi api) throws IOException {
        if (clientId == null || !clientId.matches("[A-Za-z0-9_.-]{8,100}") || refreshToken == null || refreshToken.isEmpty() || refreshToken.length() > 10_000)
            throw new IOException("اطلاعات تمدید ورود GitHub معتبر نیست");
        try {
            Response r = api.request(WEB, "POST", "/login/oauth/access_token", new JSONObject().put("client_id", clientId)
                    .put("grant_type", "refresh_token").put("refresh_token", refreshToken), false);
            requireOk(r); return json(r.body);
        } catch (org.json.JSONException e) { throw new IOException("invalid refresh request", e); }
    }
    JSONObject user() throws IOException {
        Response r = request(API, "GET", "/user", null, true); requireOk(r); return json(r.body);
    }
    @Override public String authenticatedLogin() throws IOException {
        String login = user().optString("login", "");
        if (!login.matches("[A-Za-z0-9-]{1,39}")) throw new IOException("حساب GitHub شناسایی نشد");
        return login;
    }
    @Override public JSONObject repositoryOrNull(String fullName) throws IOException {
        Response r = request(API, "GET", repoPath(fullName), null, true);
        if (r.status == 404) return null;
        requireOk(r); return json(r.body);
    }
    @Override public JSONObject createPrivateRepository(String name, String description) throws IOException {
        if (!ManagedProvisioner.validName(name) || !ManagedProvisioner.description(name).equals(description))
            throw new IOException("نام مخزن معتبر نیست");
        try {
            JSONObject data = new JSONObject().put("name", name).put("description", description)
                    .put("private", true).put("auto_init", true).put("has_issues", false)
                    .put("has_projects", false).put("has_wiki", false);
            Response r = request(API, "POST", "/user/repos", data, true);
            requireOk(r); return json(r.body);
        } catch (org.json.JSONException e) { throw new IOException("invalid repository request", e); }
    }
    @Override public byte[] readRepositoryFile(String repo, String path) throws IOException {
        if (!ManagedProvisioner.allowedPath(path)) throw new IOException("invalid managed file path");
        Response r = request(API, "GET", repoPath(repo) + "/contents/" + path, null, true);
        if (r.status == 404) return null;
        requireOk(r);
        JSONObject item = json(r.body);
        if (!"file".equals(item.optString("type")) || !"base64".equals(item.optString("encoding")))
            throw new IOException("فایل موجود در مخزن قابل بررسی نیست");
        try {
            byte[] contents = Base64.getMimeDecoder().decode(item.getString("content"));
            if (contents.length > 512_000) throw new IOException("فایل مخزن بیش از اندازه بزرگ است");
            return contents;
        } catch (IllegalArgumentException | org.json.JSONException e) { throw new IOException("فایل مخزن قابل خواندن نیست", e); }
    }
    @Override public void createRepositoryFile(String repo, String branch, String path, byte[] contents) throws IOException {
        if (!ManagedProvisioner.allowedPath(path) || branch == null || branch.isEmpty() || branch.length() > 255 ||
                contents == null || contents.length == 0 || contents.length > 512_000)
            throw new IOException("فایل نصب نامعتبر است");
        try {
            JSONObject data = new JSONObject().put("message", "CloudPath: install tunnel agent")
                    .put("content", Base64.getEncoder().encodeToString(contents)).put("branch", branch);
            Response r = request(API, "PUT", repoPath(repo) + "/contents/" + path, data, true);
            requireOk(r);
        } catch (org.json.JSONException e) { throw new IOException("invalid file request", e); }
    }
    String defaultBranch(String repo) throws IOException {
        Response r = request(API, "GET", repoPath(repo), null, true); requireOk(r);
        JSONObject j = json(r.body);
        String branch = j.optString("default_branch", "");
        if (branch.isEmpty() || branch.length() > 255) throw new IOException("شاخهٔ پیش‌فرض مخزن پیدا نشد");
        return branch;
    }
    void ensureWorkflow(String repo) throws IOException {
        Response r = request(API, "GET", repoPath(repo) + "/actions/workflows/" + WORKFLOW, null, true);
        if (r.status == 404) throw new IOException("فایل github-vpn-session.yml باید روی شاخهٔ پیش‌فرض مخزن باشد");
        requireOk(r);
    }
    void dispatch(String repo, String branch, String sid, String pub, String country) throws IOException {
        if (!SessionRules.validSession(sid) || !SessionRules.validCountry(country)) throw new IOException("ورودی نشست نامعتبر است");
        try {
            JSONObject inputs = new JSONObject().put("session_id", sid).put("client_pub", pub)
                    .put("exits", SessionRules.DIRECT.equals(country) ? "" : country + ":vpngate");
            Response r = request(API, "POST", repoPath(repo) + "/actions/workflows/" + WORKFLOW + "/dispatches",
                    new JSONObject().put("ref", branch).put("inputs", inputs), true);
            requireOk(r);
        } catch (org.json.JSONException e) { throw new IOException("invalid dispatch", e); }
    }
    JSONObject session(String repo, String sid, SessionCrypto crypto) throws IOException {
        if (!SessionRules.validSession(sid)) throw new IOException("bad session id");
        Response r = request(API, "GET", repoPath(repo) + "/contents/sessions/" + sid + ".json", null, true);
        if (r.status == 404) return null;
        requireOk(r);
        JSONObject file = json(r.body);
        try {
            byte[] bytes = Base64.getMimeDecoder().decode(file.getString("content"));
            JSONObject sealed = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            return open(sealed, sid, crypto);
        } catch (Exception e) { throw new IOException("رمزگشایی نشست ناموفق بود", e); }
    }
    static JSONObject open(JSONObject sealed, String sid, SessionCrypto crypto) throws Exception {
        if (sealed.optInt("v") != 2 || !SessionCrypto.ALG.equals(sealed.optString("alg")) ||
                !sid.equals(sealed.optString("sid"))) throw new IOException("invalid session envelope");
        byte[] plain = crypto.open(sid, sealed.getString("epk"), sealed.getString("iv"), sealed.getString("ct"));
        return new JSONObject(new String(plain, StandardCharsets.UTF_8));
    }

    /** The exact unique run-name is essential: never cancel an unrelated workflow. */
    JSONObject findRun(String repo, String sid) throws IOException {
        if (!SessionRules.validSession(sid)) throw new IOException("bad session id");
        Response r = request(API, "GET", repoPath(repo) + "/actions/workflows/" + WORKFLOW + "/runs?event=workflow_dispatch&per_page=100", null, true);
        requireOk(r);
        return matchingRun(json(r.body).optJSONArray("workflow_runs"), sid);
    }
    static JSONObject matchingRun(JSONArray runs, String sid) {
        if (!SessionRules.validSession(sid) || runs == null) return null;
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null && ("vpn-" + sid).equals(run.optString("display_title"))) return run;
        }
        return null;
    }
    /** True only once GitHub reports completed; accepting cancellation is not completion. */
    boolean cancelIfPresent(String repo, String sid) throws IOException {
        JSONObject run = findRun(repo, sid);
        if (run == null) return false;
        if ("completed".equals(run.optString("status"))) return true;
        long id = run.optLong("id", 0);
        if (id <= 0) return false;
        Response r = request(API, "POST", repoPath(repo) + "/actions/runs/" + id + "/cancel", new JSONObject(), true);
        if (r.status != 409) requireOk(r);
        return false;
    }
}
