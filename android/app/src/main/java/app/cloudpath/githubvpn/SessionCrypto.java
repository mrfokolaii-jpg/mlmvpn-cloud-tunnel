package app.cloudpath.githubvpn;

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Opening half of agent/seal.mjs. Keys are deliberately kept only in memory. */
final class SessionCrypto {
    static final String INFO = "mlmvpn-gt-session-v2";
    static final String ALG = "x25519-hkdf-sha256-aes256gcm";
    private final X25519PrivateKeyParameters privateKey;
    private final byte[] publicKey;

    SessionCrypto() {
        privateKey = new X25519PrivateKeyParameters(new SecureRandom());
        publicKey = privateKey.generatePublicKey().getEncoded();
    }

    // Used only by the interoperability test with agent/seal.mjs.
    SessionCrypto(byte[] privateBytes) {
        if (privateBytes.length != 32) throw new IllegalArgumentException("bad X25519 key");
        privateKey = new X25519PrivateKeyParameters(privateBytes, 0);
        publicKey = privateKey.generatePublicKey().getEncoded();
    }

    String publicKey() { return b64(publicKey); }
    static String b64(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }

    byte[] open(String sid, String epkText, String ivText, String ctText) throws GeneralSecurityException {
        if (sid == null || !sid.matches("[A-Za-z0-9_-]{6,64}")) throw new GeneralSecurityException("bad session id");
        final byte[] epk, iv, ct;
        try { epk = decode(epkText); iv = decode(ivText); ct = decode(ctText); }
        catch (IllegalArgumentException e) { throw new GeneralSecurityException("bad envelope encoding", e); }
        if (epk.length != 32 || iv.length != 12 || ct.length < 16 || ct.length > 1_048_576)
            throw new GeneralSecurityException("bad envelope lengths");
        byte[] shared = new byte[32];
        byte[] salt = new byte[64];
        byte[] key = null;
        try {
            privateKey.generateSecret(new X25519PublicKeyParameters(epk, 0), shared, 0);
            System.arraycopy(epk, 0, salt, 0, 32);
            System.arraycopy(publicKey, 0, salt, 32, 32);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(salt, "HmacSHA256"));
            byte[] prk = mac.doFinal(shared);
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            mac.update(INFO.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 1);
            key = mac.doFinal();
            Arrays.fill(prk, (byte) 0);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD((INFO + "|" + sid).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ct);
        } catch (RuntimeException e) {
            throw new GeneralSecurityException("invalid key or envelope", e);
        } finally {
            Arrays.fill(shared, (byte) 0);
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }
}
