package app.cloudpath.githubvpn;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** The OAuth token never enters intents, logs, repository files, or Android backup. */
final class TokenVault {
    private static final String ALIAS = "cloudpath.github.oauth.v1";
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
    void save(String token) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = c.doFinal(token.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[c.getIV().length + encrypted.length];
        System.arraycopy(c.getIV(), 0, packed, 0, c.getIV().length);
        System.arraycopy(encrypted, 0, packed, c.getIV().length, encrypted.length);
        if (!prefs.edit().putString("token", Base64.getEncoder().encodeToString(packed)).commit())
            throw new IllegalStateException("cannot save credentials");
    }
    String load() throws Exception {
        String value = prefs.getString("token", "");
        if (value.isEmpty()) return "";
        byte[] packed = Base64.getDecoder().decode(value);
        if (packed.length < 29) throw new IllegalStateException("invalid vault");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(packed, 0, 12)));
        return new String(c.doFinal(Arrays.copyOfRange(packed, 12, packed.length)), StandardCharsets.UTF_8);
    }
    void clear() { prefs.edit().clear().apply(); }
}
