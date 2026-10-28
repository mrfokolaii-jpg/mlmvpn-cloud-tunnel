package app.cloudpath.githubvpn;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class OAuthTokenManagerTest {
    private static final String CLIENT = "public-client-id";
    private static JSONObject answer(String access, String refresh, long lifetime) throws Exception {
        JSONObject json = new JSONObject().put("access_token", access).put("scope", "repo,workflow");
        if (!refresh.isEmpty()) json.put("refresh_token", refresh).put("expires_in", lifetime);
        return json;
    }
    private static final class FakeStore implements OAuthTokenManager.Store {
        OAuthTokenManager.Grant grant;
        FakeStore(OAuthTokenManager.Grant grant) { this.grant = grant; }
        @Override public synchronized OAuthTokenManager.Grant read() { return grant; }
        @Override public synchronized boolean compareAndSet(OAuthTokenManager.Grant expected, OAuthTokenManager.Grant update) {
            if (!expected.equals(grant)) return false;
            grant = update; return true;
        }
        synchronized void set(OAuthTokenManager.Grant next) { grant = next; }
    }
    @Test public void supportsLongLivedGrantsWithoutAttemptingRefresh() throws Exception {
        OAuthTokenManager.Grant grant = OAuthTokenManager.fromLogin(CLIENT, answer("access", "", 0), 1000);
        FakeStore store = new FakeStore(grant);
        OAuthTokenManager m = new OAuthTokenManager(store, (id, token) -> { fail("unexpected refresh"); return null; }, () -> 9_000_000);
        assertEquals(0, grant.expiresAt);
        assertEquals("access", m.accessToken());
        assertEquals("access", m.refreshAfterUnauthorized("access"));
    }
    @Test public void refreshesNearExpiryAndRotatesBothTokens() throws Exception {
        long now = 1_000_000L;
        FakeStore store = new FakeStore(OAuthTokenManager.fromLogin(CLIENT, answer("access-a", "refresh-a", 3600), now));
        String originalSession = store.grant.sessionId;
        final long[] clock = {now};
        AtomicInteger calls = new AtomicInteger();
        OAuthTokenManager m = new OAuthTokenManager(store, (id, token) -> {
            calls.incrementAndGet(); assertEquals(CLIENT, id); assertEquals("refresh-a", token);
            try { return answer("access-b", "refresh-b", 3600); } catch (Exception e) { throw new IOException(e); }
        }, () -> clock[0]);
        assertEquals("access-a", m.accessToken());
        clock[0] = now + 3_600_000 - 60_000;
        assertEquals("access-b", m.accessToken());
        assertEquals("refresh-b", store.grant.refreshToken);
        assertEquals(originalSession, store.grant.sessionId);
        assertEquals(1, calls.get());
        assertEquals("access-b", m.accessToken());
        assertEquals(1, calls.get());
    }
    @Test public void unauthorizedRequestForcesOneRefreshAndSharesItWithOtherRequests() throws Exception {
        FakeStore store = new FakeStore(OAuthTokenManager.fromLogin(CLIENT, answer("old", "refresh-old", 3600), 1000));
        AtomicInteger calls = new AtomicInteger();
        OAuthTokenManager m = new OAuthTokenManager(store, (id, token) -> {
            calls.incrementAndGet();
            try { return answer("new", "refresh-new", 3600); } catch (Exception e) { throw new IOException(e); }
        }, () -> 1000);
        assertEquals("new", m.refreshAfterUnauthorized("old"));
        assertEquals("new", m.refreshAfterUnauthorized("old"));
        assertEquals(1, calls.get());
    }
    @Test public void concurrentRequestsCannotSpendTheSameRotatingRefreshTokenTwice() throws Exception {
        FakeStore store = new FakeStore(OAuthTokenManager.fromLogin(CLIENT, answer("old", "refresh-old", 3600), 1000));
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            OAuthTokenManager manager = new OAuthTokenManager(store, (id, token) -> {
                calls.incrementAndGet();
                try { Thread.sleep(30); return answer("new", "refresh-new", 3600); }
                catch (Exception e) { throw new IOException(e); }
            }, () -> 3_600_000);
            Thread t = new Thread(() -> {
                try { start.await(); assertEquals("new", manager.accessToken()); }
                catch (Throwable failure) { failures.add(failure); }
            });
            threads.add(t); t.start();
        }
        start.countDown();
        for (Thread t : threads) t.join();
        assertTrue(failures.toString(), failures.isEmpty());
        assertEquals(1, calls.get());
    }
    @Test public void logoutDuringRefreshCannotRestoreCredentials() throws Exception {
        FakeStore store = new FakeStore(OAuthTokenManager.fromLogin(CLIENT, answer("old", "refresh-old", 3600), 1000));
        OAuthTokenManager m = new OAuthTokenManager(store, (id, token) -> {
            store.set(null);
            try { return answer("new", "refresh-new", 3600); } catch (Exception e) { throw new IOException(e); }
        }, () -> 3_600_000);
        try { m.accessToken(); fail("logout must win"); } catch (IOException expected) { }
        assertNull(store.grant);
    }
    @Test public void doesNotUseAnotherAccountOrSaveIncompleteRefreshResponses() throws Exception {
        OAuthTokenManager.Grant first = OAuthTokenManager.fromLogin(CLIENT, answer("old", "refresh-old", 3600), 1000);
        FakeStore store = new FakeStore(first);
        OAuthTokenManager m = new OAuthTokenManager(store, (id, token) -> {
            try { return answer("new", "", 0); } catch (Exception e) { throw new IOException(e); }
        }, () -> 3_600_000);
        try { m.accessToken(); fail("missing rotated refresh token"); } catch (IOException expected) { }
        assertEquals(first, store.grant);
        store.set(OAuthTokenManager.fromLogin(CLIENT, answer("other", "", 0), 1000));
        try { m.accessToken(); fail("account changed"); } catch (IOException expected) { }
    }
    @Test public void rejectsInsufficientScopesOrIncompleteExpiringToken() throws Exception {
        try { OAuthTokenManager.fromLogin(CLIENT, new JSONObject().put("access_token", "x").put("scope", "repo"), 1000); fail(); }
        catch (IOException expected) { }
        try { OAuthTokenManager.fromLogin(CLIENT, new JSONObject().put("access_token", "x").put("scope", "repo,workflow").put("expires_in", 3600), 1000); fail(); }
        catch (IOException expected) { }
    }
}
