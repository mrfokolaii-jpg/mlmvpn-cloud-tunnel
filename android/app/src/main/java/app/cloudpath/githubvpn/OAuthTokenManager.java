package app.cloudpath.githubvpn;

import org.json.JSONObject;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Refreshes GitHub device-flow tokens without an OAuth client secret. */
final class OAuthTokenManager implements GithubApi.TokenSource {
    private static final Object REFRESH_LOCK = new Object();
    private static final long EARLY_REFRESH_MS = 60_000L;
    private static final long MAX_LIFETIME_SECONDS = 10L * 366 * 24 * 60 * 60;
    static final class Grant {
        final String sessionId, clientId, accessToken, refreshToken;
        final long expiresAt;
        Grant(String sessionId, String clientId, String accessToken, String refreshToken, long expiresAt) {
            this.sessionId = sessionId; this.clientId = clientId; this.accessToken = accessToken;
            this.refreshToken = refreshToken; this.expiresAt = expiresAt;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Grant)) return false;
            Grant g = (Grant) other;
            return expiresAt == g.expiresAt && Objects.equals(sessionId, g.sessionId) && Objects.equals(clientId, g.clientId) &&
                    Objects.equals(accessToken, g.accessToken) && Objects.equals(refreshToken, g.refreshToken);
        }
        @Override public int hashCode() { return Objects.hash(sessionId, clientId, accessToken, refreshToken, expiresAt); }
    }
    interface Store {
        Grant read() throws Exception;
        boolean compareAndSet(Grant expected, Grant updated) throws Exception;
    }
    interface Refresher { JSONObject refresh(String clientId, String refreshToken) throws IOException; }
    interface Clock { long now(); }
    private final Store store;
    private final Refresher refresher;
    private final Clock clock;
    private final String sessionId;

    OAuthTokenManager(Store store) throws IOException { this(store, GithubApi::refreshDevice, System::currentTimeMillis); }
    OAuthTokenManager(Store store, Refresher refresher, Clock clock) throws IOException {
        this.store = store; this.refresher = refresher; this.clock = clock;
        Grant current = read();
        if (current == null || current.accessToken.isEmpty()) throw new IOException("ابتدا با GitHub وارد شو");
        sessionId = current.sessionId;
    }
    static Grant fromLogin(String clientId, JSONObject answer, long now) throws IOException {
        if (!SessionRules.hasSetupScopes(answer.optString("scope", ""))) throw new IOException("مجوزهای repo و workflow داده نشده‌اند");
        return fromAnswer(UUID.randomUUID().toString(), clientId, answer, now);
    }
    private static Grant fromAnswer(String sessionId, String clientId, JSONObject answer, long now) throws IOException {
        if (!answer.optString("error", "").isEmpty()) throw new IOException("ورود GitHub منقضی شده؛ دوباره وارد شو");
        String access = answer.optString("access_token", ""), refresh = answer.optString("refresh_token", "");
        long seconds = answer.optLong("expires_in", 0);
        if (!clientId.matches("[A-Za-z0-9_.-]{8,100}") || access.isEmpty() || access.length() > 10_000 ||
                refresh.length() > 10_000 || seconds < 0 || seconds > MAX_LIFETIME_SECONDS ||
                (seconds > 0 && refresh.isEmpty()) || (seconds == 0 && !refresh.isEmpty()))
            throw new IOException("پاسخ ورود GitHub ناقص است؛ دوباره وارد شو");
        long expiresAt = 0;
        if (seconds > 0) {
            try { expiresAt = Math.addExact(now, Math.multiplyExact(seconds, 1000L)); }
            catch (ArithmeticException e) { throw new IOException("زمان ورود GitHub معتبر نیست", e); }
        }
        return new Grant(sessionId, clientId, access, refresh, expiresAt);
    }
    private Grant read() throws IOException {
        try { return store.read(); } catch (Exception e) { throw new IOException("خواندن ورود ذخیره‌شده ناموفق بود", e); }
    }
    private Grant boundGrant() throws IOException {
        Grant current = read();
        if (current == null || current.accessToken.isEmpty() || !sessionId.equals(current.sessionId))
            throw new IOException("حساب GitHub تغییر کرده است؛ دوباره وارد شو");
        return current;
    }
    private Grant refresh(Grant current) throws IOException {
        if (current.refreshToken.isEmpty()) throw new IOException("ورود GitHub منقضی شده؛ دوباره وارد شو");
        JSONObject answer = refresher.refresh(current.clientId, current.refreshToken);
        String scope = answer.optString("scope", "");
        if (!scope.isEmpty() && !SessionRules.hasSetupScopes(scope)) throw new IOException("مجوزهای GitHub تغییر کرده‌اند؛ دوباره وارد شو");
        Grant updated = fromAnswer(current.sessionId, current.clientId, answer, clock.now());
        if (updated.refreshToken.isEmpty()) throw new IOException("تمدید ورود GitHub ناقص بود؛ دوباره وارد شو");
        try {
            // Logout or switching accounts while a refresh is in flight must never restore old tokens.
            if (!store.compareAndSet(current, updated)) throw new IOException("حساب GitHub هنگام تمدید تغییر کرد");
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("ذخیرهٔ ورود تمدیدشده ناموفق بود", e); }
        return updated;
    }
    @Override public String accessToken() throws IOException {
        synchronized (REFRESH_LOCK) {
            Grant current = boundGrant();
            if (current.expiresAt > 0 && clock.now() >= current.expiresAt - EARLY_REFRESH_MS) current = refresh(current);
            return current.accessToken;
        }
    }
    @Override public String refreshAfterUnauthorized(String rejectedToken) throws IOException {
        synchronized (REFRESH_LOCK) {
            Grant current = boundGrant();
            if (!current.accessToken.equals(rejectedToken)) return current.accessToken; // another request refreshed it
            if (current.refreshToken.isEmpty()) return rejectedToken;
            return refresh(current).accessToken;
        }
    }
}
