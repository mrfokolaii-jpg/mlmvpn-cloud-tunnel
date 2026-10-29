package app.cloudpath.githubvpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Each GitHub identity has an independent encrypted token record in Android Keystore-backed storage. */
final class TokenVault implements OAuthTokenManager.Store {
    private static final String ALIAS = "cloudpath.github.oauth.v1";
    private static final Object STORE_LOCK = new Object();
    private final SharedPreferences prefs;
    private final String accountLogin;
    private final String credentialsKey;
    private final String tokenKey;

    /** Uses the currently selected identity; retained for existing call sites. */
    TokenVault(Context c) { this(c, new Settings(c).login()); }

    TokenVault(Context c, String login) {
        prefs = c.getSharedPreferences("cloudpath-vault", Context.MODE_PRIVATE);
        accountLogin = login != null && login.matches("[A-Za-z0-9-]{1,39}") ? login : "";
        credentialsKey = accountLogin.isEmpty() ? "credentials" : AccountMapping.key("credentials.", accountLogin);
        tokenKey = accountLogin.isEmpty() ? "token" : AccountMapping.key("token.", accountLogin);
        migrateLegacyForActiveAccount(c);
    }

    private void migrateLegacyForActiveAccount(Context context) {
        if (accountLogin.isEmpty() || !accountLogin.equalsIgnoreCase(new Settings(context).login())) return;
        synchronized (STORE_LOCK) {
            SharedPreferences.Editor edit = prefs.edit();
            String legacyCredentials = prefs.getString("credentials", "");
            String legacyToken = prefs.getString("token", "");
            boolean hasScopedCredentials = !prefs.getString(credentialsKey, "").isEmpty() || !prefs.getString(tokenKey, "").isEmpty();
            boolean hasLegacyCredentials = !legacyCredentials.isEmpty() || !legacyToken.isEmpty();
            if (hasLegacyCredentials && !hasScopedCredentials) {
                if (!legacyCredentials.isEmpty()) edit.putString(credentialsKey, legacyCredentials);
                if (!legacyToken.isEmpty()) edit.putString(tokenKey, legacyToken);
            }
            if (hasLegacyCredentials) edit.remove("credentials").remove("token").commit();
        }
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    private String encrypt(String text) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[c.getIV().length + encrypted.length];
        System.arraycopy(c.getIV(), 0, packed, 0, c.getIV().length);
        System.arraycopy(encrypted, 0, packed, c.getIV().length, encrypted.length);
        return Base64.getEncoder().encodeToString(packed);
    }

    private String decrypt(String value) throws Exception {
        byte[] packed = Base64.getDecoder().decode(value);
        if (packed.length < 29) throw new IllegalStateException("invalid vault");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(packed, 0, 12)));
        return new String(c.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), StandardCharsets.UTF_8);
    }

    private OAuthTokenManager.Grant readLocked() throws Exception {
        String value = prefs.getString(credentialsKey, "");
        if (value.isEmpty()) {
            // Existing installs stored only an access token; it remains usable until revoked.
            String legacy = prefs.getString(tokenKey, "");
            return legacy.isEmpty() ? null : new OAuthTokenManager.Grant("legacy", "", decrypt(legacy), "", 0);
        }
        JSONObject json = new JSONObject(decrypt(value));
        String session = json.optString("session", ""), client = json.optString("client_id", "");
        String access = json.optString("access", ""), refresh = json.optString("refresh", "");
        long expiresAt = json.optLong("expires_at", 0);
        if (session.isEmpty() || access.isEmpty() || expiresAt < 0 || (expiresAt > 0 && (client.isEmpty() || refresh.isEmpty())))
            throw new IllegalStateException("invalid credentials");
        return new OAuthTokenManager.Grant(session, client, access, refresh, expiresAt);
    }

    @Override public OAuthTokenManager.Grant read() throws Exception {
        synchronized (STORE_LOCK) { return readLocked(); }
    }

    private void saveLocked(OAuthTokenManager.Grant grant) throws Exception {
        JSONObject json = new JSONObject().put("session", grant.sessionId).put("client_id", grant.clientId)
                .put("access", grant.accessToken).put("refresh", grant.refreshToken).put("expires_at", grant.expiresAt);
        if (!prefs.edit().putString(credentialsKey, encrypt(json.toString())).remove(tokenKey).commit())
            throw new IllegalStateException("cannot save credentials");
    }

    void save(OAuthTokenManager.Grant grant) throws Exception {
        synchronized (STORE_LOCK) { saveLocked(grant); }
    }

    @Override public boolean compareAndSet(OAuthTokenManager.Grant expected, OAuthTokenManager.Grant updated) throws Exception {
        synchronized (STORE_LOCK) {
            if (!expected.equals(readLocked())) return false;
            saveLocked(updated); return true;
        }
    }

    String load() throws Exception {
        OAuthTokenManager.Grant grant = read();
        return grant == null ? "" : grant.accessToken;
    }

    GithubApi api() throws IOException { return new GithubApi(new OAuthTokenManager(this)); }

    /** Forget this account only; credentials of other GitHub accounts remain untouched. */
    void clear() {
        synchronized (STORE_LOCK) {
            prefs.edit().remove(credentialsKey).remove(tokenKey).apply();
        }
    }
}
