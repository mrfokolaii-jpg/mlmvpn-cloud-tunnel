package app.cloudpath.githubvpn;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Non-secret run identifiers and their owning account survive crashes for safe cancellation. */
final class RunStore {
    private final SharedPreferences prefs;
    private final String legacyLogin;

    RunStore(Context c) {
        prefs = c.getSharedPreferences("cloudpath-runs", Context.MODE_PRIVATE);
        legacyLogin = new Settings(c).login();
        all(); // persist ownership of pre-multi-account runs before the active account can change
    }

    static final class Record {
        final String login, repo, sid;
        final long created;
        Record(String login, String repo, String sid, long created) {
            this.login = login; this.repo = repo; this.sid = sid; this.created = created;
        }
    }

    synchronized List<Record> all() {
        List<Record> result = new ArrayList<>();
        boolean migrated = false;
        try {
            JSONArray a = new JSONArray(prefs.getString("runs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String repo = o.optString("repo"), sid = o.optString("sid");
                String login = o.optString("login", "");
                if (!validLogin(login)) { login = legacyLogin; migrated = true; }
                if (validLogin(login) && SessionRules.validRepo(repo) && SessionRules.validSession(sid))
                    result.add(new Record(login, repo, sid, o.optLong("created")));
            }
        } catch (Exception ignored) { }
        if (migrated && !result.isEmpty()) write(result);
        return result;
    }

    synchronized void add(String login, String repo, String sid) {
        if (!validLogin(login) || !SessionRules.validRepo(repo) || !SessionRules.validSession(sid))
            throw new IllegalArgumentException("invalid run record");
        List<Record> all = all();
        all.add(new Record(login, repo, sid, System.currentTimeMillis()));
        write(all);
    }

    /** Legacy overload for tests or callers not yet carrying the owner explicitly. */
    synchronized void add(String repo, String sid) { add(legacyLogin, repo, sid); }

    synchronized void remove(String sid) {
        List<Record> all = all();
        all.removeIf(r -> r.sid.equals(sid));
        write(all);
    }

    private static boolean validLogin(String login) {
        return login != null && login.matches("[A-Za-z0-9-]{1,39}");
    }

    private void write(List<Record> records) {
        JSONArray a = new JSONArray();
        try {
            for (Record r : records) a.put(new JSONObject().put("login", r.login).put("repo", r.repo)
                    .put("sid", r.sid).put("created", r.created));
            if (!prefs.edit().putString("runs", a.toString()).commit()) throw new IllegalStateException("cannot persist run tracking");
        } catch (Exception e) { throw new IllegalStateException("cannot track GitHub runs", e); }
    }
}
