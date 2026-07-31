package com.pmcl.video;

import com.pmcl.core.friend.FriendCrypto;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight SRTP-like frame protection for video UDP:
 * {@code version(1) || seq(8) || nonce(12) || AES-GCM(ciphertext||tag)}.
 * <p>
 * Key is derived out-of-band from friend static X25519 ECDH + callId
 * ({@link com.pmcl.core.friend.FriendManager#deriveMediaKey}).
 */
final class VideoMediaCipher {

    private static final byte VERSION = 1;
    private final byte[] key;
    private final AtomicLong sendSeq = new AtomicLong();

    VideoMediaCipher(byte[] key) {
        if (key == null || key.length != FriendCrypto.KEY_LEN) {
            throw new IllegalArgumentException("media key must be 32 bytes");
        }
        this.key = Arrays.copyOf(key, key.length);
    }

    byte[] seal(byte[] plaintext) {
        byte[] nonce = FriendCrypto.randomNonce();
        byte[] aad = FriendCrypto.longToBe(sendSeq.getAndIncrement());
        byte[] ct = FriendCrypto.aesGcmEncrypt(key, nonce, plaintext, aad);
        byte[] framed = new byte[1 + 8 + nonce.length + ct.length];
        framed[0] = VERSION;
        System.arraycopy(aad, 0, framed, 1, 8);
        System.arraycopy(nonce, 0, framed, 9, nonce.length);
        System.arraycopy(ct, 0, framed, 9 + nonce.length, ct.length);
        return framed;
    }

    byte[] open(byte[] packet) {
        if (packet == null || packet.length < 1 + 8 + FriendCrypto.NONCE_LEN + 16) {
            throw new IllegalArgumentException("truncated media packet");
        }
        if (packet[0] != VERSION) {
            throw new IllegalArgumentException("unsupported media packet version");
        }
        byte[] aad = Arrays.copyOfRange(packet, 1, 9);
        byte[] nonce = Arrays.copyOfRange(packet, 9, 9 + FriendCrypto.NONCE_LEN);
        byte[] ct = Arrays.copyOfRange(packet, 9 + FriendCrypto.NONCE_LEN, packet.length);
        return FriendCrypto.aesGcmDecrypt(key, nonce, ct, aad);
    }

    void destroy() {
        Arrays.fill(key, (byte) 0);
    }
}
