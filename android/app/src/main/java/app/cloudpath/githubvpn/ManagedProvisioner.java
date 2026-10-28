package app.cloudpath.githubvpn;

import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Installs a private, per-account tunnel repository after the user has consented.
 * A random name and a persistent pending record make retries safe after an interrupted request.
 * Existing files are compared byte-for-byte and are never overwritten.
 */
final class ManagedProvisioner {
    private static final String PREFIX = "cloudpath-vpn-";
    private static final String MARKER = "cloudpath-managed.txt";
    static final class FileSpec {
        final String path, asset;
        FileSpec(String path, String asset) { this.path = path; this.asset = asset; }
    }
    static final FileSpec[] FILES = {
        new FileSpec("agent/gt-agent.mjs", "tunnel/agent/gt-agent.mjs"),
        new FileSpec("agent/exits.mjs", "tunnel/agent/exits.mjs"),
        new FileSpec("agent/relays.mjs", "tunnel/agent/relays.mjs"),
        new FileSpec("agent/seal.mjs", "tunnel/agent/seal.mjs"),
        new FileSpec("agent/xray-config.mjs", "tunnel/agent/xray-config.mjs"),
        // The workflow is deliberately last: an incomplete installation cannot be dispatched.
        new FileSpec(".github/workflows/github-vpn-session.yml", "tunnel/github-vpn-session.yml")
    };
    interface Api {
        String authenticatedLogin() throws IOException;
        JSONObject repositoryOrNull(String fullName) throws IOException;
        JSONObject createPrivateRepository(String name, String description) throws IOException;
        byte[] readRepositoryFile(String repo, String path) throws IOException;
        void createRepositoryFile(String repo, String branch, String path, byte[] contents) throws IOException;
    }
    interface State {
        String pendingRepository(String login);
        void savePendingRepository(String login, String name) throws IOException;
        void finishRepository(String login, String fullName) throws IOException;
    }
    interface Assets { byte[] read(String name) throws IOException; }
    interface NameSource { String next(); }

    private final Api api;
    private final State state;
    private final Assets assets;
    private final NameSource names;
    ManagedProvisioner(Api api, State state, Assets assets) { this(api, state, assets, ManagedProvisioner::randomName); }
    ManagedProvisioner(Api api, State state, Assets assets, NameSource names) {
        this.api = api; this.state = state; this.assets = assets; this.names = names;
    }
    static boolean validName(String name) { return name != null && name.matches("cloudpath-vpn-[a-f0-9]{24}"); }
    private static String randomName() {
        byte[] bytes = new byte[12]; new SecureRandom().nextBytes(bytes);
        char[] hex = "0123456789abcdef".toCharArray(); StringBuilder out = new StringBuilder(PREFIX);
        for (byte b : bytes) { out.append(hex[(b & 0xff) >>> 4]); out.append(hex[b & 15]); }
        return out.toString();
    }
    static String description(String name) { return "Private tunnel managed by CloudPath VPN (" + name + ")"; }
    static boolean allowedPath(String path) {
        if (MARKER.equals(path)) return true;
        for (FileSpec file : FILES) if (file.path.equals(path)) return true;
        return false;
    }
    String ensureReady(String login) throws IOException {
        if (login == null || !login.matches("[A-Za-z0-9-]{1,39}")) throw new IOException("حساب GitHub معتبر نیست");
        if (!login.equalsIgnoreCase(api.authenticatedLogin())) throw new IOException("حساب واردشده تغییر کرده است؛ دوباره وارد شو");
        String name = state.pendingRepository(login);
        if (name == null || name.isEmpty()) {
            name = names.next();
            if (!validName(name)) throw new IOException("نام مخزن آزمایشی معتبر نیست");
            state.savePendingRepository(login, name); // before POST; an interrupted request can be retried
        }
        if (!validName(name)) throw new IOException("نام مخزن در حال آماده‌سازی معتبر نیست");
        String fullName = login + "/" + name;
        JSONObject repo = api.repositoryOrNull(fullName);
        if (repo == null) {
            try { repo = api.createPrivateRepository(name, description(name)); }
            catch (GithubApi.ApiException e) {
                // The first POST may have succeeded before a timeout, or another attempt raced it.
                if (e.status != 422) throw e;
                repo = api.repositoryOrNull(fullName);
                if (repo == null) throw e;
            }
        }
        JSONObject owner = repo.optJSONObject("owner");
        String branch = repo.optString("default_branch", "");
        if (!repo.optBoolean("private", false) || owner == null ||
                !login.equalsIgnoreCase(owner.optString("login", "")) ||
                !fullName.equalsIgnoreCase(repo.optString("full_name", "")) ||
                !description(name).equals(repo.optString("description", "")) ||
                branch.isEmpty() || branch.length() > 255) {
            throw new IOException("مخزن موجود تأیید نشد؛ هیچ فایلی در آن تغییر نکرد");
        }
        install(fullName, branch, MARKER, ("CloudPath managed repository v1\n" + name + "\n").getBytes(StandardCharsets.UTF_8));
        for (FileSpec file : FILES) {
            byte[] contents = assets.read(file.asset);
            if (contents == null || contents.length == 0 || contents.length > 512_000) throw new IOException("فایل نصب تونل در اپ پیدا نشد");
            install(fullName, branch, file.path, contents);
        }
        state.finishRepository(login, fullName);
        return fullName;
    }
    private void install(String repo, String branch, String path, byte[] expected) throws IOException {
        byte[] existing = api.readRepositoryFile(repo, path);
        if (existing != null) {
            if (!Arrays.equals(existing, expected)) throw new IOException("فایل موجود متفاوت است؛ برای محافظت از مخزن بازنویسی نشد: " + path);
            return;
        }
        try { api.createRepositoryFile(repo, branch, path, expected); }
        catch (GithubApi.ApiException e) {
            // Concurrent retries may have created the exact same file. Never replace different data.
            if (e.status != 409 && e.status != 422) throw e;
            existing = api.readRepositoryFile(repo, path);
            if (existing == null || !Arrays.equals(existing, expected)) throw e;
        }
    }
}
