package app.cloudpath.githubvpn;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class SessionRulesTest {
    private static final String DIRECT = "19b229ea-cbb2-4569-8150-995c23a3ac7f";
    private static final String EXIT = "2d09c610-cb28-4ebf-b3bf-32ef577b7792";
    private JSONObject payload() throws Exception {
        JSONObject exit = new JSONObject().put("id", "x1").put("state", "ready").put("country", "JP")
                .put("provider", "vpngate").put("udp", true).put("seenCountry", "JP").put("ip", "203.0.113.8");
        return new JSONObject().put("uuids", new JSONObject().put("direct", DIRECT).put("x1", EXIT))
                .put("exits", new JSONArray().put(exit)).put("runner", new JSONObject().put("ip", "192.0.2.7"));
    }
    @Test public void selectedCountryNeverFallsBackToDirect() throws Exception {
        JSONObject p = payload();
        assertEquals(DIRECT, SessionRules.selectedUuid(p, SessionRules.DIRECT));
        assertEquals(EXIT, SessionRules.selectedUuid(p, "JP"));
        assertNull(SessionRules.selectedUuid(p, "US"));
        JSONObject x = p.getJSONArray("exits").getJSONObject(0);
        x.put("seenCountry", "US"); assertNull(SessionRules.selectedUuid(p, "JP"));
        x.put("seenCountry", "JP").put("provider", "psiphon"); assertNull(SessionRules.selectedUuid(p, "JP"));
        x.put("provider", "vpngate").put("udp", false); assertNull(SessionRules.selectedUuid(p, "JP"));
        x.put("udp", true).put("state", "starting"); assertNull(SessionRules.selectedUuid(p, "JP"));
        x.put("state", "ready").put("ip", "192.0.2.7"); assertNull(SessionRules.selectedUuid(p, "JP"));
    }
    @Test public void cancellationMatchesOnlyTheExactUniqueRunName() throws Exception {
        JSONArray runs = new JSONArray().put(new JSONObject().put("display_title", "vpn-other-session").put("id", 11))
                .put(new JSONObject().put("display_title", "vpn-our_session_123-extra").put("id", 12))
                .put(new JSONObject().put("display_title", "vpn-our_session_123").put("id", 13));
        assertEquals(13, GithubApi.matchingRun(runs, "our_session_123").getInt("id"));
        assertNull(GithubApi.matchingRun(runs, "absent_session"));
        assertNull(GithubApi.matchingRun(runs, "../invalid"));
    }
    @Test public void acceptsOnlyExpectedRelayDomainsAndRepositoryNames() throws Exception {
        assertTrue(SessionRules.validRelay("one.lhr.life"));
        assertTrue(SessionRules.validRelay("two.loca.lt"));
        assertTrue(SessionRules.validRelay("abc.trycloudflare.com"));
        assertFalse(SessionRules.validRelay("localhost"));
        assertFalse(SessionRules.validRelay("x.loca.lt.attacker.com"));
        assertFalse(SessionRules.validRelay("https://one.lhr.life"));
        assertTrue(SessionRules.validRepo("owner/my-repo"));
        assertFalse(SessionRules.validRepo("owner/repo/other"));
        assertFalse(SessionRules.validRepo("../other"));
        JSONObject p = new JSONObject().put("relays", new JSONArray().put(new JSONObject().put("host", "one.lhr.life"))
                .put(new JSONObject().put("host", "attacker.invalid")));
        assertEquals(1, SessionRules.endpoints(p).size());
    }
}
