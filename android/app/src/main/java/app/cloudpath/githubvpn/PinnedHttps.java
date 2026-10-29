package app.cloudpath.githubvpn;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * One plain HTTP/1.1 request carried by TLS to {@code host:443} while the TCP connection goes to
 * {@code ip} — the address {@link DohResolver} learned when the device resolver had nothing.
 *
 * The name is never taken from the network: the caller passes one of the app's two fixed GitHub
 * names, and the same name is used for SNI, the {@code Host} header and certificate verification
 * (plus Android's default hostname verifier as a second check). So a wrong answer can only make
 * the connection fail; it cannot hand the OAuth token to a different server.
 *
 * Deliberately minimal: no redirects, no cookies, no connection reuse, a size cap and timeouts.
 */
final class PinnedHttps {
    private static final int MAX_RESPONSE = 1_500_000;
    private static final int MAX_LINE = 8 * 1024;

    private PinnedHttps() { }

    static final class Response {
        final int status;
        final String body;
        Response(int status, String body) { this.status = status; this.body = body; }
    }

    static Response request(String host, String ip, String method, String path,
                            Map<String, String> headers, byte[] body,
                            int connectTimeoutMs, int readTimeoutMs) throws IOException {
        if (!DohResolver.ipv4(ip)) throw new IOException("pinned address is not an IPv4 literal");
        if (host == null || host.isEmpty() || path == null || !path.startsWith("/")) throw new IOException("invalid request target");
        Socket raw = new Socket();
        SSLSocket socket = null;
        try {
            raw.connect(new InetSocketAddress(ip, 443), connectTimeoutMs);
            raw.setSoTimeout(readTimeoutMs);
            socket = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, host, 443, true);
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            parameters.setServerNames(Collections.<SNIServerName>singletonList(new SNIHostName(host)));
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, socket.getSession()))
                throw new SSLPeerUnverifiedException("certificate does not match " + host);

            StringBuilder head = new StringBuilder();
            head.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            head.append("Host: ").append(host).append("\r\n");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (!safe(header.getKey()) || !safe(header.getValue())) throw new IOException("unsafe header");
                head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
            }
            if (body != null) head.append("Content-Length: ").append(body.length).append("\r\n");
            head.append("Connection: close\r\n\r\n");

            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) out.write(body);
            out.flush();
            return read(socket.getInputStream());
        } finally {
            if (socket != null) { try { socket.close(); } catch (IOException ignored) { } }
            try { raw.close(); } catch (IOException ignored) { }
        }
    }

    private static boolean safe(String value) {
        if (value == null || value.length() > 1024) return false;
        return value.indexOf('\r') < 0 && value.indexOf('\n') < 0;
    }

    private static Response read(InputStream stream) throws IOException {
        BufferedInputStream in = new BufferedInputStream(stream, 8192);
        String status = line(in);
        if (status == null || !status.startsWith("HTTP/1.")) throw new IOException("unexpected TLS peer answer");
        String[] parts = status.split(" ", 3);
        int code;
        try { code = Integer.parseInt(parts.length > 1 ? parts[1] : ""); }
        catch (RuntimeException e) { throw new IOException("unexpected TLS peer status", e); }
        long length = -1;
        boolean chunked = false;
        for (int guard = 0; guard < 100; guard++) {
            String header = line(in);
            if (header == null) throw new IOException("truncated TLS peer answer");
            if (header.isEmpty()) break;
            int colon = header.indexOf(':');
            if (colon <= 0) continue;
            String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = header.substring(colon + 1).trim();
            if ("content-length".equals(name)) {
                try { length = Long.parseLong(value); } catch (NumberFormatException e) { throw new IOException("bad content length", e); }
            } else if ("transfer-encoding".equals(name) && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            }
        }
        byte[] payload = chunked ? chunkedBody(in) : (length >= 0 ? exact(in, length) : untilEnd(in));
        return new Response(code, new String(payload, StandardCharsets.UTF_8));
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            int value = in.read();
            if (value < 0) return out.size() == 0 ? null : new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
            if (value == '\n') break;
            if (out.size() >= MAX_LINE) throw new IOException("header line too long");
            out.write(value);
        }
        String text = new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }

    private static byte[] exact(InputStream in, long length) throws IOException {
        if (length < 0 || length > MAX_RESPONSE) throw new IOException("TLS peer answer too large");
        byte[] data = new byte[(int) length];
        int offset = 0;
        while (offset < data.length) {
            int n = in.read(data, offset, data.length - offset);
            if (n < 0) throw new IOException("truncated TLS peer answer");
            offset += n;
        }
        return data;
    }

    private static byte[] untilEnd(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > MAX_RESPONSE) throw new IOException("TLS peer answer too large");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] chunkedBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String size = line(in);
            if (size == null) throw new IOException("truncated chunked answer");
            int semicolon = size.indexOf(';');
            String digits = (semicolon >= 0 ? size.substring(0, semicolon) : size).trim();
            int length;
            try { length = Integer.parseInt(digits, 16); }
            catch (NumberFormatException e) { throw new IOException("bad chunk size", e); }
            if (length < 0) throw new IOException("bad chunk size");
            if (length == 0) {
                // Trailer section: read until the blank line, then stop.
                for (int guard = 0; guard < 100; guard++) {
                    String trailer = line(in);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                return out.toByteArray();
            }
            if (out.size() + (long) length > MAX_RESPONSE) throw new IOException("TLS peer answer too large");
            byte[] data = exact(in, length);
            out.write(data, 0, data.length);
            line(in); // CRLF that ends the chunk
        }
    }
}
