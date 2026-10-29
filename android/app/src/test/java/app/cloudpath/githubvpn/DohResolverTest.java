package app.cloudpath.githubvpn;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** The pure half of the DNS-over-HTTPS fallback: what an answer is allowed to contribute. */
public class DohResolverTest {
    @Test public void takesOnlyARecordsFromAResolvedAnswer() {
        String body = "{\"Status\":0,\"Answer\":["
                + "{\"name\":\"github.com.\",\"type\":5,\"data\":\"github.com.cdn.cloudflare.net.\"},"
                + "{\"name\":\"github.com.\",\"type\":1,\"data\":\"140.82.121.4\"},"
                + "{\"name\":\"github.com.\",\"type\":28,\"data\":\"2606:50c0:8000::153\"},"
                + "{\"name\":\"github.com.\",\"type\":1,\"data\":\"140.82.121.4\"},"
                + "{\"name\":\"github.com.\",\"type\":1,\"data\":\"not-an-address\"}]}";
        List<String> addresses = DohResolver.parse(body);
        assertEquals(1, addresses.size());
        assertEquals("140.82.121.4", addresses.get(0));
    }
    @Test public void refusesAnswersThatAreNotSuccessful() {
        assertTrue(DohResolver.parse("{\"Status\":3,\"Answer\":[{\"type\":1,\"data\":\"1.2.3.4\"}]}").isEmpty());
        assertTrue(DohResolver.parse("{\"Status\":0}").isEmpty());
        assertTrue(DohResolver.parse("{\"Status\":0,\"Answer\":[]}").isEmpty());
        assertTrue(DohResolver.parse("not json").isEmpty());
        assertTrue(DohResolver.parse("").isEmpty());
        assertTrue(DohResolver.parse(null).isEmpty());
    }
    @Test public void acceptsOnlyStrictIpv4Literals() {
        assertTrue(DohResolver.ipv4("140.82.121.4"));
        assertTrue(DohResolver.ipv4("8.8.8.8"));
        assertFalse(DohResolver.ipv4("140.82.121"));
        assertFalse(DohResolver.ipv4("140.82.121.256"));
        assertFalse(DohResolver.ipv4("140.82.121.4.5"));
        assertFalse(DohResolver.ipv4("140.82.121."));
        assertFalse(DohResolver.ipv4("140.82.121.04"));
        assertFalse(DohResolver.ipv4("evil.example"));
        assertFalse(DohResolver.ipv4("1.2.3.4\nHost: evil"));
        assertFalse(DohResolver.ipv4(null));
    }
    @Test public void resolutionOfAnInvalidNameIsEmptyRatherThanANetworkCall() {
        assertTrue(DohResolver.resolve("").isEmpty());
        assertTrue(DohResolver.resolve("no-dot").isEmpty());
        assertTrue(DohResolver.resolve("bad host name").isEmpty());
        assertTrue(DohResolver.resolve("1.2.3.4/../../x").isEmpty());
        assertTrue(DohResolver.resolve(null).isEmpty());
    }
}
