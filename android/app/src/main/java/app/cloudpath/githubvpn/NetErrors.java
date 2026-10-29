package app.cloudpath.githubvpn;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

/**
 * One Persian sentence for a failed network step, in place of the English socket text that used to
 * reach the dialog («Unable to resolve host "github.com": No address associated with hostname»).
 * Messages the app already wrote in Persian are passed through unchanged.
 */
final class NetErrors {
    static final String DNS = "آدرس GitHub با DNS این شبکه پیدا نشد. اگر VPN یا DNS خصوصی روشن است آن را بررسی کن، "
            + "شبکهٔ Wi‑Fi و داده را عوض کن و دوباره تلاش کن.";
    private static final Pattern PERSIAN = Pattern.compile(".*[\\u0600-\\u06FF].*", Pattern.DOTALL);

    private NetErrors() { }

    /** True when any cause is a name-resolution failure. */
    static boolean isDns(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) if (t instanceof UnknownHostException) return true;
        return false;
    }

    static String describe(Throwable error, String fallback) {
        if (error == null) return fallback;
        if (isDns(error)) return DNS;
        // A sentence the app itself wrote (an existing Persian message) is already the answer.
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && PERSIAN.matcher(message).matches()) return message;
        }
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SocketTimeoutException)
                return "پاسخی از GitHub نرسید (وقفهٔ شبکه). اتصال اینترنت را بررسی و دوباره تلاش کن.";
            if (t instanceof ConnectException)
                return "اتصال به GitHub برقرار نشد؛ ممکن است این شبکه GitHub را مسدود کرده باشد. VPN یا DNS را بررسی کن.";
            if (t instanceof SSLException)
                return "ارتباط امن با GitHub برقرار نشد؛ ساعت دستگاه و شبکه را بررسی کن.";
        }
        return fallback;
    }
}
