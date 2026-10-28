package app.cloudpath.githubvpn;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validate authenticated server data before it is used in a native proxy configuration. */
final class SessionRules {
    static final String DIRECT = "DIRECT";
    private static final Pattern SESSION = Pattern.compile("[A-Za-z0-9_-]{6,64}");
    private static final Pattern REPO = Pattern.compile("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}");
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9_-]{1,64}");
    private static final Pattern RELAY = Pattern.compile("[a-z0-9-]{1,63}\\.(?:lhr\\.(?:life|pro)|srv\\.us|serveo\\.net|serveousercontent\\.com|loca\\.lt|trycloudflare\\.com)");

    static boolean validRepo(String value) { return value != null && REPO.matcher(value).matches() && !value.contains(".."); }
    static boolean validSession(String value) { return value != null && SESSION.matcher(value).matches(); }
    static boolean validCountry(String value) { return DIRECT.equals(value) || (value != null && value.matches("[A-Z]{2}")); }
    static boolean hasSetupScopes(String scopes) {
        boolean repo = false, workflow = false;
        if (scopes == null) return false;
        for (String s : scopes.split(",")) {
            if ("repo".equals(s.trim())) repo = true;
            if ("workflow".equals(s.trim())) workflow = true;
        }
        return repo && workflow;
    }
    static boolean validRelay(String value) { return value != null && RELAY.matcher(value).matches(); }

    static final class Endpoint {
        final String host;
        final boolean localTunnel;
        Endpoint(String host) { this.host = host; this.localTunnel = host.endsWith(".loca.lt"); }
    }

    static List<Endpoint> endpoints(JSONObject payload) {
        List<Endpoint> list = new ArrayList<>();
        appendEndpoints(list, payload.optJSONArray("relays"));
        appendEndpoints(list, payload.optJSONArray("hosts"));
        return list;
    }
    private static void appendEndpoints(List<Endpoint> list, JSONArray values) {
        if (values == null) return;
        for (int i = 0; i < values.length() && list.size() < 16; i++) {
            JSONObject row = values.optJSONObject(i);
            String host = row == null ? "" : row.optString("host", "").toLowerCase(Locale.ROOT);
            if (!validRelay(host)) continue;
            boolean seen = false;
            for (Endpoint e : list) if (e.host.equals(host)) { seen = true; break; }
            if (!seen) list.add(new Endpoint(host));
        }
    }

    static String websocketPath(JSONObject payload) {
        String path = payload.optString("wsPath", "");
        return PATH.matcher(path).matches() ? path : null;
    }

    /** No silent fallback to the runner or to Psiphon, whose UDP leaves from the runner. */
    static String selectedUuid(JSONObject payload, String country) {
        if (!validCountry(country)) return null;
        JSONObject uuids = payload.optJSONObject("uuids");
        if (uuids == null) return null;
        String id = "direct";
        if (!DIRECT.equals(country)) {
            JSONArray exits = payload.optJSONArray("exits");
            if (exits == null) return null;
            id = null;
            for (int i = 0; i < exits.length(); i++) {
                JSONObject x = exits.optJSONObject(i);
                if (x == null || !country.equals(x.optString("country"))) continue;
                String slot = x.optString("id", "");
                if (!slot.matches("x[1-6]") || !"ready".equals(x.optString("state")) ||
                        !"vpngate".equals(x.optString("provider")) || !x.optBoolean("udp") ||
                        !country.equals(x.optString("seenCountry"))) continue;
                JSONObject runner = payload.optJSONObject("runner");
                if (runner != null && !runner.optString("ip", "").isEmpty() &&
                        runner.optString("ip", "").equals(x.optString("ip", ""))) continue;
                id = slot;
                break;
            }
            if (id == null) return null;
        }
        String uuid = uuids.optString(id, "");
        try { return UUID.fromString(uuid).toString().equalsIgnoreCase(uuid) ? uuid : null; }
        catch (IllegalArgumentException e) { return null; }
    }

    static String countryError(JSONObject payload, String country) {
        if (DIRECT.equals(country)) return "";
        JSONArray exits = payload.optJSONArray("exits");
        if (exits == null) return "";
        for (int i = 0; i < exits.length(); i++) {
            JSONObject x = exits.optJSONObject(i);
            if (x != null && country.equals(x.optString("country")) && "failed".equals(x.optString("state")))
                return x.optString("error", "خروجی کشور آماده نشد");
        }
        return "";
    }
}
