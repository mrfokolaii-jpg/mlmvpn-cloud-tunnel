package app.cloudpath.githubvpn;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Active identity, account pool, and per-account repository/health are kept separately. */
final class Settings implements ManagedProvisioner.State {
    private static final String NAME = "cloudpath-settings";
    private static final String ACCOUNTS = "github_accounts";
    private static final String HEALTH_PREFIX = "account.health.";
    private final SharedPreferences p;
    Settings(Context context) { p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE); }
    String clientId() { return p.getString("client_id", ""); }
    String login() { return p.getString("login", ""); }
    String repo() { return p.getString("repo", ""); }
    String country() { return p.getString("country", SessionRules.DIRECT); }
    boolean desired() { return p.getBoolean("desired", false); }
    String status() { return p.getString("status", "آمادهٔ اتصال"); }
    String phase() { return p.getString("phase", "stopped"); }

    /** Existing single-account installs are included automatically during migration. */
    synchronized List<String> accountLogins() {
        List<String> result = new ArrayList<>();
        try {
            JSONArray stored = new JSONArray(p.getString(ACCOUNTS, "[]"));
            for (int i = 0; i < stored.length(); i++) addUnique(result, stored.optString(i, ""));
        } catch (Exception ignored) { }
        String active = login();
        if (validLogin(active)) {
            result.removeIf(value -> value.equalsIgnoreCase(active));
            result.add(0, active);
        }
        String encoded = encodeAccounts(result);
        if (!encoded.equals(p.getString(ACCOUNTS, "[]"))) p.edit().putString(ACCOUNTS, encoded).apply();
        return result;
    }

    String repoFor(String accountLogin) {
        if (!validLogin(accountLogin)) return "";
        String stored = p.getString(AccountMapping.key("account.repo.", accountLogin), "");
        String activeLogin = login();
        String activeRepo = accountLogin.equalsIgnoreCase(activeLogin) ? repo() : "";
        return AccountMapping.restore(activeLogin, accountLogin, stored, activeRepo);
    }

    String accountHealth(String accountLogin) {
        if (!validLogin(accountLogin)) return "UNKNOWN";
        return p.getString(AccountMapping.key(HEALTH_PREFIX, accountLogin), "OK");
    }

    void setAccountHealth(String accountLogin, String health) {
        if (!validLogin(accountLogin) || !("OK".equals(health) || "EXHAUSTED".equals(health) || "AUTH_REQUIRED".equals(health))) return;
        p.edit().putString(AccountMapping.key(HEALTH_PREFIX, accountLogin), health).apply();
    }

    void clearAccountHealth(String accountLogin) {
        if (validLogin(accountLogin)) p.edit().remove(AccountMapping.key(HEALTH_PREFIX, accountLogin)).apply();
    }

    private static boolean validLogin(String value) {
        return value != null && value.matches("[A-Za-z0-9-]{1,39}");
    }

    private static void addUnique(List<String> into, String login) {
        if (!validLogin(login)) return;
        for (String item : into) if (item.equalsIgnoreCase(login)) return;
        into.add(login);
    }

    private static String encodeAccounts(List<String> values) {
        JSONArray json = new JSONArray();
        for (String value : values) json.put(value);
        return json.toString();
    }

    void activateAccount(String accountLogin) throws IOException {
        if (!validLogin(accountLogin)) throw new IOException("حساب GitHub معتبر نیست");
        List<String> accounts = accountLogins();
        accounts.removeIf(value -> value.equalsIgnoreCase(accountLogin));
        accounts.add(0, accountLogin);
        String accountKey = AccountMapping.key("account.repo.", accountLogin);
        String saved = AccountMapping.restore(login(), accountLogin, p.getString(accountKey, ""), repo());
        // Migration from older versions is allowed only for the very same active account.
        SharedPreferences.Editor edit = p.edit().putString("login", accountLogin).putString("repo", saved)
                .putString(ACCOUNTS, encodeAccounts(accounts));
        if (!saved.isEmpty()) edit.putString(accountKey, saved);
        if (!edit.commit()) throw new IOException("ذخیرهٔ حساب ناموفق بود");
    }

    void selectAccount(String accountLogin) throws IOException {
        boolean found = false;
        for (String saved : accountLogins()) if (saved.equalsIgnoreCase(accountLogin)) { found = true; accountLogin = saved; break; }
        if (!found) throw new IOException("این حساب روی دستگاه ثبت نشده است");
        activateAccount(accountLogin);
    }

    void removeAccount(String accountLogin) throws IOException {
        if (!validLogin(accountLogin)) return;
        String current = login();
        List<String> accounts = accountLogins();
        accounts.removeIf(value -> value.equalsIgnoreCase(accountLogin));
        String next = current.equalsIgnoreCase(accountLogin) ? (accounts.isEmpty() ? "" : accounts.get(0)) : current;
        String nextRepo = next.isEmpty() ? "" : p.getString(AccountMapping.key("account.repo.", next), "");
        if (next.equalsIgnoreCase(current) && !next.isEmpty()) nextRepo = repoFor(next);
        SharedPreferences.Editor edit = p.edit()
                .remove(AccountMapping.key("account.repo.", accountLogin))
                .remove(AccountMapping.key("account.pending.", accountLogin))
                .remove(AccountMapping.key(HEALTH_PREFIX, accountLogin))
                .putString(ACCOUNTS, encodeAccounts(accounts));
        if (current.equalsIgnoreCase(accountLogin)) edit.putString("login", next).putString("repo", nextRepo);
        if (!edit.commit()) throw new IOException("حذف حساب از دستگاه ناموفق بود");
    }

    void forgetCurrentAccount() {
        try { removeAccount(login()); } catch (IOException ignored) { }
    }

    void setRepo(String value) throws IOException {
        String accountLogin = login(), repository = value.trim();
        if (!SessionRules.validRepo(repository)) throw new IOException("نام مخزن معتبر نیست");
        if (!p.edit().putString("repo", repository)
                .putString(AccountMapping.key("account.repo.", accountLogin), repository).commit())
            throw new IOException("ذخیرهٔ مخزن ناموفق بود");
    }

    void clearRepo() throws IOException {
        if (!p.edit().putString("repo", "").remove(AccountMapping.key("account.repo.", login())).commit())
            throw new IOException("پاک‌کردن مخزن ناموفق بود");
    }

    @Override public String pendingRepository(String accountLogin) {
        return p.getString(AccountMapping.key("account.pending.", accountLogin), "");
    }

    void forgetPendingRepository(String accountLogin) throws IOException {
        if (!p.edit().remove(AccountMapping.key("account.pending.", accountLogin)).commit())
            throw new IOException("ذخیرهٔ وضعیت نصب ناموفق بود");
    }

    @Override public void savePendingRepository(String accountLogin, String name) throws IOException {
        if (!ManagedProvisioner.validName(name)) throw new IOException("نام مخزن معتبر نیست");
        if (!p.edit().putString(AccountMapping.key("account.pending.", accountLogin), name).commit())
            throw new IOException("ذخیرهٔ وضعیت نصب ناموفق بود");
    }

    @Override public void finishRepository(String accountLogin, String fullName) throws IOException {
        if (!SessionRules.validRepo(fullName) || !fullName.regionMatches(true, 0, accountLogin + "/", 0, accountLogin.length() + 1))
            throw new IOException("حساب مخزن با ورود فعلی مطابقت ندارد");
        SharedPreferences.Editor edit = p.edit().putString(AccountMapping.key("account.repo.", accountLogin), fullName)
                .remove(AccountMapping.key("account.pending.", accountLogin));
        if (accountLogin.equalsIgnoreCase(login())) edit.putString("repo", fullName);
        if (!edit.commit()) throw new IOException("ذخیرهٔ مخزن ناموفق بود");
    }

    void setCountry(String value) { p.edit().putString("country", value).apply(); }
    void setDesired(boolean value) { p.edit().putBoolean("desired", value).commit(); }
    void setStatus(String phase, String status) { p.edit().putString("phase", phase).putString("status", status).apply(); }
}
