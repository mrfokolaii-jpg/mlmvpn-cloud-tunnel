package app.cloudpath.githubvpn;

import android.content.Context;
import android.content.SharedPreferences;

final class Settings {
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
    void setClientId(String v) { p.edit().putString("client_id", v.trim()).apply(); }
    void setLogin(String v) { p.edit().putString("login", v).apply(); }
    void setRepo(String v) { p.edit().putString("repo", v.trim()).apply(); }
    void setCountry(String v) { p.edit().putString("country", v).apply(); }
    void setDesired(boolean v) { p.edit().putBoolean("desired", v).commit(); }
    void setStatus(String phase, String status) { p.edit().putString("phase", phase).putString("status", status).apply(); }
}
