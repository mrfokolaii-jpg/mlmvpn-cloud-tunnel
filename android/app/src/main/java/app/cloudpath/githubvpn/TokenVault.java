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

/** OAuth access/refresh tokens never enter intents, logs, repository files, or Android backup. */
final class TokenVault implements OAuthTokenManager.Store {
    private static final String ALIAS = "cloudpath.github.oauth.v1";
    private static final Object STORE_LOCK = new Object();
    private final SharedPreferences prefs;
    TokenVault(Context c) { prefs = c.getSharedPreferences("cloudpath-vault", Context.MODE_PRIVATE); }

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
        String value = prefs.getString("credentials", "");
        if (value.isEmpty()) {
            // Existing installs stored only an access token; it remains usable until revoked.
            String legacy = prefs.getString("token", "");
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
        if (!prefs.edit().putString("credentials", encrypt(json.toString())).remove("token").commit())
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
    void clear() { synchronized (STORE_LOCK) { prefs.edit().clear().apply(); } }
}
