package app.cloudpath.githubvpn;

import org.junit.Test;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import static org.junit.Assert.*;

/** What the user is told when a GitHub step fails: never the raw English socket text. */
public class NetErrorsTest {
    private static final String FALLBACK = "ورود ناموفق بود";

    @Test public void dnsFailureHasItsOwnPersianSentence() {
        UnknownHostException dns = new UnknownHostException("github.com: No address associated with hostname");
        assertTrue(NetErrors.isDns(dns));
        String message = NetErrors.describe(dns, FALLBACK);
        assertEquals(NetErrors.DNS, message);
        assertFalse(message.contains("No address associated"));
        assertFalse(message.contains("github.com\""));
    }
    @Test public void dnsFailureIsFoundUnderWrapping() {
        IOException wrapped = new IOException("درخواست ناموفق", new IOException("connect", new UnknownHostException("api.github.com")));
        assertEquals(NetErrors.DNS, NetErrors.describe(wrapped, FALLBACK));
    }
    @Test public void socketFailuresGetPersianTextToo() {
        String timeout = NetErrors.describe(new SocketTimeoutException("Read timed out"), FALLBACK);
        String refused = NetErrors.describe(new ConnectException("Failed to connect to github.com"), FALLBACK);
        assertTrue(timeout.contains("وقفهٔ شبکه"));
        assertTrue(refused.contains("مسدود"));
        assertNotEquals(FALLBACK, timeout);
        assertFalse(timeout.contains("Read timed out"));
    }
    @Test public void aPersianMessageTheAppWroteIsKept() {
        String own = "مخزن موجود تأیید نشد؛ هیچ فایلی در آن تغییر نکرد";
        assertEquals(own, NetErrors.describe(new IOException(own), FALLBACK));
        assertEquals(own, NetErrors.describe(new IOException("wrap", new IOException(own)), FALLBACK));
    }
    @Test public void unknownFailuresUseTheCallerFallback() {
        assertEquals(FALLBACK, NetErrors.describe(new IllegalStateException("boom"), FALLBACK));
        assertEquals(FALLBACK, NetErrors.describe(null, FALLBACK));
        assertFalse(NetErrors.isDns(new IOException("plain")));
    }
}
