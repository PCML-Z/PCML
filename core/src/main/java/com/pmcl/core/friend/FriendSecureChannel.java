package com.pmcl.core.friend;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;

/**
 * Post-TCP mutual handshake + AES-GCM line framing for friend chat.
 * <p>
 * Bootstrap ({@code hs1}/{@code hs2}) is authenticated with Ed25519 and establishes
 * forward-secret session keys via ephemeral + static X25519. Application JSON lines
 * are then encrypted; {@code authSecret} is never sent on the wire.
 */
public final class FriendSecureChannel implements AutoCloseable {

    private static final long HS_WINDOW_MS = 60_000L;
    private static final String HS_PREFIX = "PMCL-HS1\n";

    private final InputStream rawIn;
    private final OutputStream rawOut;
    private final byte[] sendKey;
    private final byte[] recvKey;
    private final String peerIdentity;
    private final byte[] peerEdPub;
    private final byte[] peerXPub;
    private long sendCounter;
    private long recvCounter;
    private final byte[] staticShared; // for media key derivation

    private FriendSecureChannel(InputStream in, OutputStream out,
                                byte[] sendKey, byte[] recvKey,
                                String peerIdentity, byte[] peerEdPub, byte[] peerXPub,
                                byte[] staticShared) {
        this.rawIn = in;
        this.rawOut = out;
        this.sendKey = sendKey;
        this.recvKey = recvKey;
        this.peerIdentity = peerIdentity;
        this.peerEdPub = peerEdPub;
        this.peerXPub = peerXPub;
        this.staticShared = staticShared;
    }

    public String getPeerIdentity() { return peerIdentity; }
    public byte[] getPeerEdPub() { return peerEdPub.clone(); }
    public byte[] getPeerXPub() { return peerXPub.clone(); }

    /** SRTP-like media key bound to callId. */
    public byte[] deriveMediaKey(String callId) {
        return FriendCrypto.hkdf(staticShared, callId.getBytes(StandardCharsets.UTF_8),
                "pmcl-video-srtp-v1".getBytes(StandardCharsets.UTF_8), FriendCrypto.KEY_LEN);
    }

    public interface LocalIdentity {
        String identity();
        byte[] edPrivate();
        byte[] edPublic();
        byte[] xPrivate();
        byte[] xPublic();
    }

    public static final class PeerStaticKeys {
        public final byte[] edPublic;
        public final byte[] xPublic;
        public PeerStaticKeys(byte[] edPublic, byte[] xPublic) {
            this.edPublic = edPublic;
            this.xPublic = xPublic;
        }
    }

    /**
     * Client-side handshake. {@code expectedPeer} may be null for introduce (friend request)
     * when peer keys are not yet pinned; if non-null, peer long-term keys must match.
     */
    public static FriendSecureChannel clientHandshake(
            Socket socket, LocalIdentity me, PeerStaticKeys expectedPeer) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        KeyPair eph = FriendCrypto.generateEphemeralX25519();
        long ts = System.currentTimeMillis();
        String ephPubB64 = FriendCrypto.b64(eph.getPublic().getEncoded());
        String edB64 = FriendCrypto.b64(me.edPublic());
        String xB64 = FriendCrypto.b64(me.xPublic());
        byte[] toSign = (HS_PREFIX + me.identity() + "\n" + edB64 + "\n" + xB64 + "\n"
                + ephPubB64 + "\n" + ts).getBytes(StandardCharsets.UTF_8);
        String sig = FriendCrypto.b64(FriendCrypto.signEd25519(me.edPrivate(), toSign));

        JsonObject hs1 = new JsonObject();
        hs1.addProperty("type", "hs1");
        hs1.addProperty("v", 1);
        hs1.addProperty("id", me.identity());
        hs1.addProperty("ed", edB64);
        hs1.addProperty("x", xB64);
        hs1.addProperty("eph", ephPubB64);
        hs1.addProperty("ts", ts);
        hs1.addProperty("sig", sig);
        writeRawLine(out, hs1.toString());

        String line = FriendProtocol.readLineBounded(in, FriendProtocol.MAX_MESSAGE_LENGTH);
        JsonObject hs2 = parseHs(line, "hs2");
        verifyHs(hs2, expectedPeer);

        byte[] peerEd = FriendCrypto.b64d(hs2.get("ed").getAsString());
        byte[] peerX = FriendCrypto.b64d(hs2.get("x").getAsString());
        byte[] peerEph = FriendCrypto.b64d(hs2.get("eph").getAsString());
        byte[] ephShared = FriendCrypto.x25519(eph.getPrivate().getEncoded(), peerEph);
        byte[] staticShared = FriendCrypto.x25519(me.xPrivate(), peerX);
        byte[] ikm = FriendCrypto.concat(ephShared, staticShared);
        FriendCrypto.SessionKeys keys = FriendCrypto.deriveSessionKeys(ikm, true);
        return new FriendSecureChannel(in, out, keys.sendKey, keys.recvKey,
                hs2.get("id").getAsString(), peerEd, peerX, staticShared);
    }

    /**
     * Server-side handshake. {@code peerKeyLookup} returns pinned keys for known friends;
     * null allows introduce (unknown peer) if keys verify self-consistently.
     */
    public static FriendSecureChannel serverHandshake(
            Socket socket, LocalIdentity me,
            Function<String, PeerStaticKeys> peerKeyLookup) throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        String line = FriendProtocol.readLineBounded(in, FriendProtocol.MAX_MESSAGE_LENGTH);
        JsonObject hs1 = parseHs(line, "hs1");
        String peerId = hs1.get("id").getAsString();
        PeerStaticKeys expected = peerKeyLookup != null ? peerKeyLookup.apply(peerId) : null;
        verifyHs(hs1, expected);

        byte[] peerEd = FriendCrypto.b64d(hs1.get("ed").getAsString());
        byte[] peerX = FriendCrypto.b64d(hs1.get("x").getAsString());
        byte[] peerEph = FriendCrypto.b64d(hs1.get("eph").getAsString());

        KeyPair eph = FriendCrypto.generateEphemeralX25519();
        long ts = System.currentTimeMillis();
        String ephPubB64 = FriendCrypto.b64(eph.getPublic().getEncoded());
        String edB64 = FriendCrypto.b64(me.edPublic());
        String xB64 = FriendCrypto.b64(me.xPublic());
        byte[] toSign = (HS_PREFIX + me.identity() + "\n" + edB64 + "\n" + xB64 + "\n"
                + ephPubB64 + "\n" + ts).getBytes(StandardCharsets.UTF_8);
        String sig = FriendCrypto.b64(FriendCrypto.signEd25519(me.edPrivate(), toSign));

        JsonObject hs2 = new JsonObject();
        hs2.addProperty("type", "hs2");
        hs2.addProperty("v", 1);
        hs2.addProperty("id", me.identity());
        hs2.addProperty("ed", edB64);
        hs2.addProperty("x", xB64);
        hs2.addProperty("eph", ephPubB64);
        hs2.addProperty("ts", ts);
        hs2.addProperty("sig", sig);
        writeRawLine(out, hs2.toString());

        byte[] ephShared = FriendCrypto.x25519(eph.getPrivate().getEncoded(), peerEph);
        byte[] staticShared = FriendCrypto.x25519(me.xPrivate(), peerX);
        byte[] ikm = FriendCrypto.concat(ephShared, staticShared);
        FriendCrypto.SessionKeys keys = FriendCrypto.deriveSessionKeys(ikm, false);
        return new FriendSecureChannel(in, out, keys.sendKey, keys.recvKey,
                peerId, peerEd, peerX, staticShared);
    }

    public synchronized void writeLine(String jsonLine) throws IOException {
        byte[] plain = jsonLine.getBytes(StandardCharsets.UTF_8);
        long seq = sendCounter++;
        byte[] nonce = FriendCrypto.randomNonce();
        byte[] aad = FriendCrypto.longToBe(seq);
        byte[] ct = FriendCrypto.aesGcmEncrypt(sendKey, nonce, plain, aad);
        JsonObject frame = new JsonObject();
        frame.addProperty("type", "sec");
        frame.addProperty("seq", seq);
        frame.addProperty("n", FriendCrypto.b64(nonce));
        frame.addProperty("c", FriendCrypto.b64(ct));
        writeRawLine(rawOut, frame.toString());
    }

    public synchronized String readLine(int maxPlainBytes) throws IOException {
        String line = FriendProtocol.readLineBounded(rawIn, FriendProtocol.MAX_MESSAGE_LENGTH);
        if (line == null) return null;
        JsonObject frame = JsonParser.parseString(line).getAsJsonObject();
        if (!"sec".equals(text(frame, "type"))) {
            throw new IOException("expected encrypted frame, got: " + text(frame, "type"));
        }
        long seq = frame.get("seq").getAsLong();
        if (seq != recvCounter) {
            throw new IOException("secure channel sequence mismatch: got " + seq + " expect " + recvCounter);
        }
        recvCounter++;
        byte[] nonce = FriendCrypto.b64d(frame.get("n").getAsString());
        byte[] ct = FriendCrypto.b64d(frame.get("c").getAsString());
        byte[] plain = FriendCrypto.aesGcmDecrypt(recvKey, nonce, ct, FriendCrypto.longToBe(seq));
        if (plain.length > maxPlainBytes) {
            throw new IOException("decrypted message too large");
        }
        return new String(plain, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        Arrays.fill(sendKey, (byte) 0);
        Arrays.fill(recvKey, (byte) 0);
        Arrays.fill(staticShared, (byte) 0);
    }

    private static void writeRawLine(OutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static JsonObject parseHs(String line, String expectedType) throws IOException {
        if (line == null) throw new IOException("handshake EOF");
        try {
            JsonObject o = JsonParser.parseString(line).getAsJsonObject();
            if (!expectedType.equals(text(o, "type"))) {
                throw new IOException("bad handshake type: " + text(o, "type"));
            }
            if (o.get("v").getAsInt() != 1) {
                throw new IOException("unsupported handshake version");
            }
            return o;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("invalid handshake JSON", e);
        }
    }

    private static void verifyHs(JsonObject hs, PeerStaticKeys expected) throws IOException {
        long ts = hs.get("ts").getAsLong();
        if (Math.abs(System.currentTimeMillis() - ts) > HS_WINDOW_MS) {
            throw new IOException("handshake timestamp outside window");
        }
        String id = text(hs, "id");
        String edB64 = text(hs, "ed");
        String xB64 = text(hs, "x");
        String ephB64 = text(hs, "eph");
        String sigB64 = text(hs, "sig");
        if (id.isEmpty() || edB64.isEmpty() || xB64.isEmpty() || ephB64.isEmpty() || sigB64.isEmpty()) {
            throw new IOException("handshake missing fields");
        }
        byte[] ed = FriendCrypto.b64d(edB64);
        byte[] msg = (HS_PREFIX + id + "\n" + edB64 + "\n" + xB64 + "\n" + ephB64 + "\n" + ts)
                .getBytes(StandardCharsets.UTF_8);
        if (!FriendCrypto.verifyEd25519(ed, msg, FriendCrypto.b64d(sigB64))) {
            throw new IOException("handshake Ed25519 signature invalid");
        }
        if (expected != null) {
            if (!Arrays.equals(expected.edPublic, ed)
                    || !Arrays.equals(expected.xPublic, FriendCrypto.b64d(xB64))) {
                throw new IOException("peer long-term keys do not match pinned friend keys");
            }
        }
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
