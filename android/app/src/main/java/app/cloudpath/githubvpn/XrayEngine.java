package app.cloudpath.githubvpn;

import android.net.VpnService;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import libXray.DialerController;
import libXray.LibXray;

/** One managed libXray instance, an authenticated loopback SOCKS port, and a real tunnel probe. */
final class XrayEngine {
    final int socksPort;
    final String socksUser, socksPass;
    private final DialerController dialer;
    private boolean initialized;

    XrayEngine(VpnService service) throws IOException {
        try (ServerSocket free = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) { socksPort = free.getLocalPort(); }
        byte[] u = new byte[18], p = new byte[24];
        SecureRandom random = new SecureRandom(); random.nextBytes(u); random.nextBytes(p);
        socksUser = SessionCrypto.b64(u); socksPass = SessionCrypto.b64(p);
        dialer = fd -> fd >= 0 && fd <= Integer.MAX_VALUE && service.protect((int) fd);
    }
    void initialize(String dnsServer) throws Exception {
        if (initialized) return;
        LibXray.registerDialerController(dialer);
        LibXray.setDNS(dialer, dnsServer);
        initialized = true;
    }
    private void invoke(String method, JSONObject payload) throws Exception {
        JSONObject request = new JSONObject().put("apiVersion", 3).put("method", method).put("payload", payload);
        JSONObject response = new JSONObject(LibXray.invoke(request.toString()));
        // Native error text can contain the config/host; do not log it or display it.
        if (!response.optBoolean("success", false)) throw new IOException("راه‌اندازی موتور VPN ناموفق بود");
    }
    private JSONObject config(SessionRules.Endpoint endpoint, String uuid, String path) throws Exception {
        JSONObject inbound = new JSONObject().put("tag", "socks").put("listen", "127.0.0.1")
                .put("port", socksPort).put("protocol", "socks")
                .put("settings", new JSONObject().put("auth", "password").put("udp", true).put("ip", "127.0.0.1")
                        .put("accounts", new JSONArray().put(new JSONObject().put("user", socksUser).put("pass", socksPass))));
        JSONObject headers = new JSONObject().put("Host", endpoint.host);
        if (endpoint.localTunnel) headers.put("bypass-tunnel-reminder", "1");
        // VMess AEAD adds encryption between this app and the runner; the HTTPS relay terminates
        // the outer TLS connection and is not trusted with the plaintext stream.
        JSONObject server = new JSONObject().put("address", endpoint.host).put("port", 443)
                .put("users", new JSONArray().put(new JSONObject().put("id", uuid).put("security", "auto")));
        JSONObject outbound = new JSONObject().put("tag", "vpn").put("protocol", "vmess")
                .put("settings", new JSONObject().put("vnext", new JSONArray().put(server)))
                .put("streamSettings", new JSONObject().put("network", "ws").put("security", "tls")
                        .put("tlsSettings", new JSONObject().put("serverName", endpoint.host))
                        .put("wsSettings", new JSONObject().put("path", path).put("headers", headers)));
        return new JSONObject().put("log", new JSONObject().put("loglevel", "none"))
                .put("inbounds", new JSONArray().put(inbound)).put("outbounds", new JSONArray().put(outbound));
    }
    /** Stops the old core while testing; the established TUN stays up and does not bypass it. */
    void activate(JSONObject payload, String country, String sid, SessionCrypto crypto) throws Exception {
        if (!"vmess".equals(payload.optString("protocol")))
            throw new IOException("نسخهٔ عامل سرور از پروتکل این اپ پشتیبانی نمی‌کند");
        String uuid = SessionRules.selectedUuid(payload, country);
        String path = SessionRules.websocketPath(payload);
        List<SessionRules.Endpoint> endpoints = SessionRules.endpoints(payload);
        if (uuid == null || path == null || endpoints.isEmpty()) throw new IOException("نشست یا کشور انتخاب‌شده آماده نیست");
        Exception last = null;
        for (SessionRules.Endpoint endpoint : endpoints) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                stopCore();
                invoke("runXray", new JSONObject().put("xrayJson", config(endpoint, uuid, path).toString()));
                // A successful HTTP response, decrypted with this session's key, proves the VLESS path.
                // The native listener can take a moment to bind after runXray returns.
                JSONObject status = null;
                Exception probeFailure = null;
                for (int attempt = 0; attempt < 2 && status == null; attempt++) {
                    try { status = probe(sid, crypto); }
                    catch (Exception e) { probeFailure = e; if (attempt == 0) Thread.sleep(500); }
                }
                if (status == null) throw new IOException("tunnel probe failed", probeFailure);
                if (!"ready".equals(status.optString("phase"))) throw new IOException("server not ready");
                if (!SessionRules.DIRECT.equals(country) && SessionRules.selectedUuid(status, country) == null)
                    throw new IOException("خروجی کشور هنوز آماده نیست");
                return;
            } catch (InterruptedException e) { throw e; }
            catch (Exception e) { last = e; }
        }
        stopCore();
        throw new IOException("هیچ مسیر ارتباطی پاسخ نداد", last);
    }
    void stopCore() {
        try { invoke("stopXray", new JSONObject()); } catch (Exception ignored) { }
    }
    void close() {
        stopCore();
        if (initialized) {
            try { LibXray.resetDNS(); } catch (Exception ignored) { }
            // Do not leave the previous Android Service captured by a process-wide Go callback.
            try { LibXray.registerDialerController(fd -> true); } catch (Exception ignored) { }
        }
        initialized = false;
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read(); if (value < 0) throw new IOException("unexpected end of SOCKS reply"); return value;
    }
    private static void readFully(InputStream in, int count) throws IOException { for (int i = 0; i < count; i++) readByte(in); }
    /** HTTP over SOCKS5, using a domain-name request (no local lookup of status.gt.internal). */
    JSONObject probe(String sid, SessionCrypto crypto) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", socksPort), 5000);
            socket.setSoTimeout(12_000);
            InputStream in = socket.getInputStream(); OutputStream out = socket.getOutputStream();
            out.write(new byte[]{5, 1, 2}); out.flush();
            if (readByte(in) != 5 || readByte(in) != 2) throw new IOException("SOCKS auth unavailable");
            byte[] user = socksUser.getBytes(StandardCharsets.US_ASCII), pass = socksPass.getBytes(StandardCharsets.US_ASCII);
            out.write(1); out.write(user.length); out.write(user); out.write(pass.length); out.write(pass); out.flush();
            if (readByte(in) != 1 || readByte(in) != 0) throw new IOException("SOCKS authentication failed");
            byte[] host = "status.gt.internal".getBytes(StandardCharsets.US_ASCII);
            out.write(new byte[]{5, 1, 0, 3, (byte) host.length}); out.write(host); out.write(new byte[]{0, 80}); out.flush();
            int ver = readByte(in), code = readByte(in); readByte(in); int type = readByte(in);
            if (ver != 5 || code != 0) throw new IOException("SOCKS connect failed");
            if (type == 1) readFully(in, 4); else if (type == 4) readFully(in, 16);
            else if (type == 3) readFully(in, readByte(in)); else throw new IOException("bad SOCKS address");
            readFully(in, 2);
            out.write("GET /s HTTP/1.1\r\nHost: status.gt.internal\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n; if (total > 1_500_000) throw new IOException("status response too large");
                response.write(buf, 0, n);
            }
            String text = response.toString(StandardCharsets.UTF_8.name());
            int split = text.indexOf("\r\n\r\n");
            if (split < 0 || !(text.startsWith("HTTP/1.1 200 ") || text.startsWith("HTTP/1.0 200 ")))
                throw new IOException("tunnel status failed");
            return GithubApi.open(new JSONObject(text.substring(split + 4)), sid, crypto);
        }
    }
}
