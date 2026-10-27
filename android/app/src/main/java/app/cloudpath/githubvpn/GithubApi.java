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
import java.util.Base64;

/** Fixed GitHub endpoints only; no credential is sent to a redirect or arbitrary host. */
final class GithubApi {
    private static final String API = "https://api.github.com";
    private static final String WEB = "https://github.com";
    private static final String WORKFLOW = "github-vpn-session.yml";
    private final String token;
    GithubApi(String token) { this.token = token == null ? "" : token; }

    static final class ApiException extends IOException {
        final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
    private static final class Response {
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
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(12_000);
        c.setReadTimeout(18_000);
        c.setRequestMethod(method);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "cloudpath-vpn-android");
        c.setRequestProperty("Cache-Control", "no-store");
        if (base.equals(API)) {
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        }
        if (auth && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        if (data != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        }
        try {
            int status = c.getResponseCode();
            String body = read(status >= 400 ? c.getErrorStream() : c.getInputStream());
            return new Response(status, body);
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
        else if (response.status == 422) message = "GitHub درخواست نشست را نپذیرفت";
        throw new ApiException(response.status, message);
    }
    private static String repoPath(String repo) throws IOException {
        if (!SessionRules.validRepo(repo)) throw new IOException("نام مخزن نامعتبر است؛ owner/repository وارد کن");
        return "/repos/" + repo;
    }

    static JSONObject beginDevice(String clientId) throws IOException {
        if (clientId == null || !clientId.matches("[A-Za-z0-9_.-]{8,100}")) throw new IOException("Client ID معتبر نیست");
        try {
            Response r = new GithubApi("").request(WEB, "POST", "/login/device/code",
                    new JSONObject().put("client_id", clientId).put("scope", "repo"), false);
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
    JSONObject user() throws IOException {
        Response r = request(API, "GET", "/user", null, true); requireOk(r); return json(r.body);
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
