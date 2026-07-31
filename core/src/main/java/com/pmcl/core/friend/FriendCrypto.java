package com.pmcl.core.friend;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Friend P2P crypto: Ed25519 (identity/auth) + X25519 (ECDH) + AES-256-GCM + HKDF-SHA256.
 * <p>
 * Long-term keys are stored with the local identity; peer public keys travel in QR/invite
 * text (out-of-band), never as a shared {@code authSecret} on the wire.
 */
public final class FriendCrypto {

    public static final int GCM_TAG_BITS = 128;
    public static final int NONCE_LEN = 12;
    public static final int KEY_LEN = 32;

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private FriendCrypto() {}

    public static final class KeyBundle {
        public final byte[] ed25519PrivatePkcs8;
        public final byte[] ed25519PublicSpki;
        public final byte[] x25519PrivatePkcs8;
        public final byte[] x25519PublicSpki;

        public KeyBundle(byte[] edPriv, byte[] edPub, byte[] xPriv, byte[] xPub) {
            this.ed25519PrivatePkcs8 = edPriv;
            this.ed25519PublicSpki = edPub;
            this.x25519PrivatePkcs8 = xPriv;
            this.x25519PublicSpki = xPub;
        }
    }

    public static KeyBundle generateKeyBundle() {
        try {
            KeyPairGenerator edGen = KeyPairGenerator.getInstance("Ed25519");
            KeyPair ed = edGen.generateKeyPair();
            KeyPairGenerator xGen = KeyPairGenerator.getInstance("X25519");
            KeyPair x = xGen.generateKeyPair();
            return new KeyBundle(
                    ed.getPrivate().getEncoded(),
                    ed.getPublic().getEncoded(),
                    x.getPrivate().getEncoded(),
                    x.getPublic().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate friend keypairs", e);
        }
    }

    public static String b64(byte[] raw) {
        return B64.encodeToString(raw);
    }

    public static byte[] b64d(String s) {
        return B64D.decode(s);
    }

    public static byte[] signEd25519(byte[] privatePkcs8, byte[] message) {
        try {
            PrivateKey sk = KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(privatePkcs8));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(sk);
            sig.update(message);
            return sig.sign();
        } catch (Exception e) {
            throw new IllegalStateException("Ed25519 sign failed", e);
        }
    }

    public static boolean verifyEd25519(byte[] publicSpki, byte[] message, byte[] signature) {
        try {
            PublicKey pk = KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(publicSpki));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pk);
            sig.update(message);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] x25519(byte[] privatePkcs8, byte[] peerPublicSpki) {
        try {
            PrivateKey sk = KeyFactory.getInstance("X25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(privatePkcs8));
            PublicKey pk = KeyFactory.getInstance("X25519")
                    .generatePublic(new X509EncodedKeySpec(peerPublicSpki));
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(sk);
            ka.doPhase(pk, true);
            return ka.generateSecret();
        } catch (Exception e) {
            throw new IllegalStateException("X25519 ECDH failed", e);
        }
    }

    /** Ephemeral X25519 keypair (SPKI / PKCS8). */
    public static KeyPair generateEphemeralX25519() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("X25519");
            g.initialize(new NamedParameterSpec("X25519"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("ephemeral X25519 failed", e);
        }
    }

    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int len) {
        try {
            byte[] prk = hmacSha256(salt == null || salt.length == 0 ? new byte[32] : salt, ikm);
            byte[] result = new byte[len];
            byte[] t = new byte[0];
            int pos = 0;
            byte counter = 1;
            while (pos < len) {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(prk, "HmacSHA256"));
                mac.update(t);
                mac.update(info);
                mac.update(counter);
                t = mac.doFinal();
                int copy = Math.min(t.length, len - pos);
                System.arraycopy(t, 0, result, pos, copy);
                pos += copy;
                counter++;
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("HKDF failed", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    /**
     * Derive directional traffic keys after mutual handshake.
     * {@code ikm} = eph_shared || static_shared
     */
    public static SessionKeys deriveSessionKeys(byte[] ikm, boolean iAmClient) {
        byte[] okm = hkdf(ikm, "pmcl-friend-hs-v1".getBytes(StandardCharsets.UTF_8),
                "session".getBytes(StandardCharsets.UTF_8), KEY_LEN * 2);
        byte[] c2s = Arrays.copyOfRange(okm, 0, KEY_LEN);
        byte[] s2c = Arrays.copyOfRange(okm, KEY_LEN, KEY_LEN * 2);
        return iAmClient ? new SessionKeys(c2s, s2c) : new SessionKeys(s2c, c2s);
    }

    public static final class SessionKeys {
        public final byte[] sendKey;
        public final byte[] recvKey;
        public SessionKeys(byte[] sendKey, byte[] recvKey) {
            this.sendKey = sendKey;
            this.recvKey = recvKey;
        }
    }

    public static byte[] aesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(plaintext);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    public static byte[] aesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, nonce));
            if (aad != null) c.updateAAD(aad);
            return c.doFinal(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("AES-GCM decrypt failed", e);
        }
    }

    public static byte[] randomNonce() {
        byte[] n = new byte[NONCE_LEN];
        RNG.nextBytes(n);
        return n;
    }

    /** Media key for a call: HKDF(static_ecdh, callId). */
    public static byte[] deriveMediaKey(byte[] myXPriv, byte[] peerXPub, String callId) {
        byte[] shared = x25519(myXPriv, peerXPub);
        return hkdf(shared, callId.getBytes(StandardCharsets.UTF_8),
                "pmcl-video-srtp-v1".getBytes(StandardCharsets.UTF_8), KEY_LEN);
    }

    /** Legacy-compatible hex secret derived from static ECDH (local only, never wire). */
    public static String deriveSharedSecretHex(byte[] myXPriv, byte[] peerXPub) {
        byte[] shared = x25519(myXPriv, peerXPub);
        byte[] out = hkdf(shared, "pmcl-friend-auth".getBytes(StandardCharsets.UTF_8),
                "secret".getBytes(StandardCharsets.UTF_8), 32);
        StringBuilder sb = new StringBuilder(out.length * 2);
        for (byte b : out) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static byte[] longToBe(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }
}
