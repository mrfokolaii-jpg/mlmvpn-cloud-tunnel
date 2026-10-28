package app.cloudpath.githubvpn;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;

/** Active identity and repository are associated per GitHub account, not globally. */
final class Settings implements ManagedProvisioner.State {
    private static final String NAME = "cloudpath-settings";
    private final SharedPreferences p;
    Settings(Context context) { p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE); }
    String clientId() { return p.getString("client_id", ""); }
    String login() { return p.getString("login", ""); }
    String repo() { return p.getString("repo", ""); }
    String country() { return p.getString("country", SessionRules.DIRECT); }
    boolean desired() { return p.getBoolean("desired", false); }
    String status() { return p.getString("status", "آمادهٔ اتصال"); }
    String phase() { return p.getString("phase", "stopped"); }
    void activateAccount(String login) throws IOException {
        String accountKey = AccountMapping.key("account.repo.", login);
        String saved = AccountMapping.restore(login(), login, p.getString(accountKey, ""), repo());
        // Migration from older versions is allowed only for the very same active account.
        SharedPreferences.Editor edit = p.edit().putString("login", login).putString("repo", saved);
        if (!saved.isEmpty()) edit.putString(accountKey, saved);
        if (!edit.commit()) throw new IOException("ذخیرهٔ حساب ناموفق بود");
    }
    void forgetCurrentAccount() { p.edit().putString("login", "").putString("repo", "").apply(); }
    void setRepo(String v) throws IOException {
        String login = login(), repo = v.trim();
        if (!SessionRules.validRepo(repo)) throw new IOException("نام مخزن معتبر نیست");
        if (!p.edit().putString("repo", repo).putString(AccountMapping.key("account.repo.", login), repo).commit())
            throw new IOException("ذخیرهٔ مخزن ناموفق بود");
    }
    void clearRepo() throws IOException {
        if (!p.edit().putString("repo", "").remove(AccountMapping.key("account.repo.", login())).commit())
            throw new IOException("ذخیرهٔ مخزن ناموفق بود");
    }
    @Override public String pendingRepository(String login) { return p.getString(AccountMapping.key("account.pending.", login), ""); }
    void forgetPendingRepository(String login) throws IOException {
        if (!p.edit().remove(AccountMapping.key("account.pending.", login)).commit()) throw new IOException("ذخیرهٔ وضعیت نصب ناموفق بود");
    }
    @Override public void savePendingRepository(String login, String name) throws IOException {
        if (!ManagedProvisioner.validName(name)) throw new IOException("نام مخزن معتبر نیست");
        if (!p.edit().putString(AccountMapping.key("account.pending.", login), name).commit()) throw new IOException("ذخیرهٔ وضعیت نصب ناموفق بود");
    }
    @Override public void finishRepository(String login, String fullName) throws IOException {
        if (!SessionRules.validRepo(fullName) || !fullName.regionMatches(true, 0, login + "/", 0, login.length() + 1))
            throw new IOException("حساب مخزن با ورود فعلی مطابقت ندارد");
        SharedPreferences.Editor edit = p.edit().putString(AccountMapping.key("account.repo.", login), fullName).remove(AccountMapping.key("account.pending.", login));
        if (login.equalsIgnoreCase(login())) edit.putString("repo", fullName);
        if (!edit.commit()) throw new IOException("ذخیرهٔ مخزن ناموفق بود");
    }
    void setCountry(String v) { p.edit().putString("country", v).apply(); }
    void setDesired(boolean v) { p.edit().putBoolean("desired", v).commit(); }
    void setStatus(String phase, String status) { p.edit().putString("phase", phase).putString("status", status).apply(); }
}
