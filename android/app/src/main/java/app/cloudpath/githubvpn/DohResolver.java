package app.cloudpath.githubvpn;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An IPv4 address for one of the app's own fixed hosts, learned WITHOUT the device resolver.
 *
 * The reported failure was «Unable to resolve host "github.com": No address associated with
 * hostname» arriving after GitHub had already accepted the device code: on a filtered or flaky
 * network the system resolver can answer nothing while the same network still carries TLS to
 * GitHub — which is why the login page itself had loaded in the browser.
 *
 * DNS-over-HTTPS to a resolver addressed by its own IP needs no name resolved first, so it works
 * exactly where the device resolver does not. Every connection built from an answer here is still
 * TLS-verified against the real host name (see {@link PinnedHttps}), so a lying answer cannot
 * redirect the token anywhere: it can only fail to connect.
 *
 * Nothing here throws. An empty list simply means "keep the platform behaviour".
 */
final class DohResolver {
    private static final long TTL_MS = 10 * 60_000L;
    private static final int MAX_ANSWERS = 4;
    private static final int MAX_BODY = 32 * 1024;
    // Resolvers named by IP: no name lookup is needed to reach them, and their certificates
    // include the address itself, so the plain platform TLS stack can verify them.
    private static final String[] ENDPOINTS = {
        "https://1.1.1.1/dns-query?ct=application/dns-json&type=A&name=",
        "https://1.0.0.1/dns-query?ct=application/dns-json&type=A&name=",
        "https://8.8.8.8/resolve?type=A&name=",
        "https://8.8.4.4/resolve?type=A&name=",
    };

    private static final class Entry {
        final List<String> addresses;
        final long at;
        Entry(List<String> addresses, long at) { this.addresses = addresses; this.at = at; }
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> LAST_GOOD = new ConcurrentHashMap<>();

    private DohResolver() { }

    /** Fresh addresses for {@code host}; the last known good list if every resolver is silent. */
    static List<String> resolve(String host) {
        String name = normalise(host);
        if (name.isEmpty()) return Collections.emptyList();
        long now = System.currentTimeMillis();
        Entry cached = CACHE.get(name);
        if (cached != null && now - cached.at < TTL_MS) return cached.addresses;
        List<String> fresh = query(name);
        if (!fresh.isEmpty()) {
            CACHE.put(name, new Entry(fresh, now));
            LAST_GOOD.put(name, fresh);
            return fresh;
        }
        List<String> previous = LAST_GOOD.get(name);
        return previous == null ? Collections.<String>emptyList() : previous;
    }

    private static String normalise(String host) {
        if (host == null) return "";
        String name = host.trim().toLowerCase(Locale.ROOT);
        if (name.length() < 3 || name.length() > 253 || name.indexOf('.') < 0) return "";
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c == '.' || c == '-' || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) return "";
        }
        return name;
    }

    private static List<String> query(String host) {
        for (String endpoint : ENDPOINTS) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(endpoint + host).openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(4_000);
                connection.setReadTimeout(4_000);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/dns-json");
                connection.setRequestProperty("User-Agent", "cloudpath-vpn-android");
                connection.setRequestProperty("Cache-Control", "no-store");
                if (connection.getResponseCode() != 200) continue;
                List<String> addresses = parse(read(connection.getInputStream()));
                if (!addresses.isEmpty()) return addresses;
            } catch (Exception ignored) {
                // Blocked, filtered or offline: try the next resolver, then fall back quietly.
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        return Collections.emptyList();
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n, total = 0;
            while ((n = src.read(buffer)) != -1) {
                total += n;
                if (total > MAX_BODY) return "";
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** The A records of a JSON DNS answer; Cloudflare and dns.google use the same shape. */
    static List<String> parse(String body) {
        List<String> addresses = new ArrayList<>();
        if (body == null || body.isEmpty()) return addresses;
        JSONObject answer;
        try { answer = new JSONObject(body); } catch (Exception e) { return addresses; }
        if (answer.optInt("Status", -1) != 0) return addresses;
        JSONArray answers = answer.optJSONArray("Answer");
        if (answers == null) return addresses;
        for (int i = 0; i < answers.length() && addresses.size() < MAX_ANSWERS; i++) {
            JSONObject row = answers.optJSONObject(i);
            if (row == null || row.optInt("type", 0) != 1) continue;
            String data = row.optString("data", "").trim();
            if (!ipv4(data) || addresses.contains(data)) continue;
            addresses.add(data);
        }
        return addresses;
    }

    /** A strict dotted-quad check: this string becomes a socket address, never a name. */
    static boolean ipv4(String value) {
        if (value == null) return false;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                char c = part.charAt(i);
                if (c < '0' || c > '9') return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') return false;
            if (Integer.parseInt(part) > 255) return false;
        }
        return true;
    }

    /** Tests only: forget everything learned so far. */
    static void clear() {
        CACHE.clear();
        LAST_GOOD.clear();
    }
}
