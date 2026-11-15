package com.pmcl.core.friend;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FriendChatConnectTest {

    @Test
    void parseAndRejectAddressesThatAreNotAChatServer(@TempDir Path dir) throws Exception {
        assertNull(FriendManager.parseChatAddress(""));
        assertNull(FriendManager.parseChatAddress("no-port"));
        assertNull(FriendManager.parseChatAddress("host:0"));
        assertNull(FriendManager.parseChatAddress("host:65536"));
        assertNull(FriendManager.parseChatAddress("bad host:80"));
        FriendManager.ChatServerTarget v4 = FriendManager.parseChatAddress("127.0.0.1:80");
        assertNotNull(v4);
        assertEquals("127.0.0.1", v4.host);
        assertEquals(80, v4.port);
        FriendManager.ChatServerTarget v6 = FriendManager.parseChatAddress("[::1]:81");
        assertNotNull(v6);
        assertEquals("::1", v6.host);
        assertEquals(81, v6.port);

        FriendManager alice = started(dir.resolve("alice"),
                "11111111-1111-1111-1111-111111111111", "Alice");
        try {
            assertEquals("invalid", alice.connectChatServer("not-an-address").code);
            assertEquals("self", alice.connectChatServer("127.0.0.1:" + alice.getChatPort()).code);
            assertFalse(alice.localChatAddress().isBlank());
        } finally {
            alice.close();
        }
    }

    @Test
    void connectingByAddressDeliversChatAfterThePeerAccepts(@TempDir Path dir) throws Exception {
        FriendManager alice = started(dir.resolve("alice"),
                "11111111-1111-1111-1111-111111111111", "Alice");
        FriendManager bob = started(dir.resolve("bob"),
                "22222222-2222-2222-2222-222222222222", "Bob");
        try {
            CopyOnWriteArrayList<FriendManager.PendingRequest> pending = new CopyOnWriteArrayList<>();
            bob.addListener(event -> {
                if (event.type == FriendManager.FriendEvent.Type.FRIEND_REQUEST_RECEIVED
                        && event.data instanceof FriendManager.PendingRequest request) {
                    pending.add(request);
                }
            });

            String bobId = bob.getIdentityManager().getIdentity().toString();
            FriendManager.ChatConnectResult connected =
                    alice.connectChatServer("127.0.0.1:" + bob.getChatPort());
            assertEquals("request_sent", connected.code);
            assertEquals(bobId, connected.identity);

            waitFor(8_000, () -> !pending.isEmpty());
            assertFalse(pending.isEmpty(), "对方没有收到好友请求");
            FriendManager.PendingRequest request = pending.get(0);
            assertEquals(alice.getChatPort(), request.port);
            assertEquals("127.0.0.1", request.ip);
            bob.acceptFriendRequest(request);

            String aliceId = alice.getIdentityManager().getIdentity().toString();
            waitFor(8_000, () -> {
                FriendStore.FriendEntry entry = alice.getStore().getFriend(bobId);
                return entry != null && "Bob".equals(entry.displayName);
            });
            FriendStore.FriendEntry named = alice.getStore().getFriend(bobId);
            assertNotNull(named);
            assertEquals("Bob", named.displayName);

            alice.sendMessage(bobId, "ping");
            waitFor(8_000, () -> hasText(bob, aliceId, "ping", false));
            assertTrue(hasText(bob, aliceId, "ping", false), "Bob 没有收到 Alice 的消息");

            bob.sendMessage(aliceId, "pong");
            waitFor(8_000, () -> hasText(alice, bobId, "pong", false));
            assertTrue(hasText(alice, bobId, "pong", false), "Alice 没有收到 Bob 的回复");
        } finally {
            alice.close();
            bob.close();
        }
    }

    private static FriendManager started(Path dir, String accountUuid, String name) throws Exception {
        FriendManager manager = new FriendManager(dir);
        manager.initialize();
        manager.switchAccount(accountUuid, name);
        if (manager.getState() != FriendManager.State.RUNNING) manager.start();
        return manager;
    }

    private static boolean hasText(FriendManager manager, String identity, String text, boolean fromMe) {
        for (FriendStore.StoredMessage message : manager.getMessages(identity)) {
            if (text.equals(message.text) && message.fromMe == fromMe) return true;
        }
        return false;
    }

    private static void waitFor(long timeoutMs, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(40);
        }
    }
}
