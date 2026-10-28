package app.cloudpath.githubvpn;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Non-secret run identifiers survive a crash so a later launch can retry cancellation. */
final class RunStore {
    private final SharedPreferences prefs;
    RunStore(Context c) { prefs = c.getSharedPreferences("cloudpath-runs", Context.MODE_PRIVATE); }
    static final class Record {
        final String repo, sid;
        final long created;
        Record(String repo, String sid, long created) { this.repo = repo; this.sid = sid; this.created = created; }
    }
    synchronized List<Record> all() {
        List<Record> result = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("runs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String repo = o.optString("repo"), sid = o.optString("sid");
                if (SessionRules.validRepo(repo) && SessionRules.validSession(sid)) result.add(new Record(repo, sid, o.optLong("created")));
            }
        } catch (Exception ignored) { }
        return result;
    }
    synchronized void add(String repo, String sid) {
        List<Record> all = all();
        all.add(new Record(repo, sid, System.currentTimeMillis()));
        write(all);
    }
    synchronized void remove(String sid) {
        List<Record> all = all();
        all.removeIf(r -> r.sid.equals(sid));
        write(all);
    }
    private void write(List<Record> records) {
        JSONArray a = new JSONArray();
        try {
            for (Record r : records) a.put(new JSONObject().put("repo", r.repo).put("sid", r.sid).put("created", r.created));
            if (!prefs.edit().putString("runs", a.toString()).commit()) throw new IllegalStateException("cannot persist run tracking");
        } catch (Exception e) { throw new IllegalStateException("cannot track GitHub runs", e); }
    }
}
