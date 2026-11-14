package com.pmcl.core.friend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendLanDiscoveryTest {

    @Test
    void discoverSignatureKeepsTheOldPayloadUntilXIsPresent() {
        FriendCrypto.KeyBundle keys = FriendCrypto.generateKeyBundle();
        String id = "AAAAA-BBBBB-CCCCC-DDDDD-EEEEE";
        byte[] oldPayload = FriendProtocol.DiscoverMessage.signingPayload(id, "Alice", 25565, 1000L);
        assertArrayEquals(
                "PMCL-DISCOVER\nAAAAA-BBBBB-CCCCC-DDDDD-EEEEE\nAlice\n25565\n1000"
                        .getBytes(StandardCharsets.UTF_8),
                oldPayload);
        assertArrayEquals(oldPayload,
                FriendProtocol.DiscoverMessage.signingPayload(id, "Alice", 25565, 1000L, "  "));

        String x = FriendCrypto.b64(keys.x25519PublicSpki);
        byte[] withX = FriendProtocol.DiscoverMessage.signingPayload(id, "Alice", 25565, 1000L, x);
        assertFalse(java.util.Arrays.equals(oldPayload, withX));
        byte[] sig = FriendCrypto.signEd25519(keys.ed25519PrivatePkcs8, withX);
        assertTrue(FriendCrypto.verifyEd25519(keys.ed25519PublicSpki, withX, sig));
        assertFalse(FriendCrypto.verifyEd25519(keys.ed25519PublicSpki, oldPayload, sig));
    }

    @Test
    void twoLaunchersSeeEachOtherOnTheLan(@TempDir Path dir) throws Exception {
        FriendManager alice = new FriendManager(dir.resolve("alice"));
        FriendManager bob = new FriendManager(dir.resolve("bob"));
        try {
            alice.initialize();
            bob.initialize();
            alice.switchAccount("11111111-1111-1111-1111-111111111111", "Alice");
            bob.switchAccount("22222222-2222-2222-2222-222222222222", "Bob");

            FriendManager.NearbyPeer found = null;
            long deadline = System.currentTimeMillis() + 8_000L;
            while (System.currentTimeMillis() < deadline) {
                for (FriendManager.NearbyPeer peer : alice.nearbyPeers()) {
                    if ("Bob".equals(peer.displayName)) found = peer;
                }
                if (found != null && found.x25519 != null && found.ed25519 != null) break;
                Thread.sleep(200);
            }

            assertNotNull(found, "同一台机器上的另一个 PMCL 没有出现在附近列表里");
            assertTrue(found.port > 0);
            assertNotNull(found.ip);
            String self = alice.getIdentityManager().getIdentity().toString();
            for (FriendManager.NearbyPeer peer : alice.nearbyPeers()) {
                assertNotEquals(self, peer.identity);
            }

            alice.sendFriendRequest(found.identity, found.displayName, "", 0,
                    found.ed25519, found.x25519);
            String added = found.identity;
            assertTrue(alice.nearbyPeers().stream().noneMatch(peer -> added.equals(peer.identity)));
        } finally {
            alice.close();
            bob.close();
        }
    }
}
