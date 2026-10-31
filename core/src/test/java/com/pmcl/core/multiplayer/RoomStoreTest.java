package com.pmcl.core.multiplayer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoomStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void rememberDedupesSameBackendAndInvitation() {
        RoomStore store = new RoomStore(tempDir.resolve("mp-rooms.json"));
        store.remember("EASYTIER", "easytier-invite-1", "10.126.126.1", 0, "和朋友挖矿");
        store.remember("EASYTIER", "easytier-invite-1", "10.126.126.1", 0, "和朋友挖矿");
        store.remember("TERRACOTTA", "U/ABCDEF", "", 0, "陶瓦房");
        List<RoomStore.Room> rooms = store.list();
        assertEquals(2, rooms.size(), "同后端同邀请码应合并为一条");
        assertEquals("和朋友挖矿", rooms.stream()
                .filter(r -> r.getBackend().equals("EASYTIER")).findFirst().orElseThrow().getName());
    }

    @Test
    void renameSurvivesReloadAndRejoinKeepsName() {
        Path file = tempDir.resolve("mp-rooms.json");
        RoomStore store = new RoomStore(file);
        RoomStore.Room room = store.remember("REDSTONE", "relay.example:7000", "relay.example:7000", 8, "红石中继");
        store.rename(room.getId(), "周末生存服");

        RoomStore reloaded = new RoomStore(file);
        assertEquals("周末生存服", reloaded.find(room.getId()).getName());

        // 重新加入同一房间不应覆盖用户改过的名字
        reloaded.remember("REDSTONE", "relay.example:7000", "relay.example:7000", 8, "红石中继");
        assertEquals("周末生存服", reloaded.find(room.getId()).getName());
        assertEquals(1, reloaded.list().size());
    }

    @Test
    void removeDeletesAndPersists() {
        Path file = tempDir.resolve("mp-rooms.json");
        RoomStore store = new RoomStore(file);
        RoomStore.Room room = store.remember("CONNECTX", "connectx-abc", "", 0, null);
        assertNotNull(room.getName(), "缺省名应自动生成");
        store.remove(room.getId());
        assertTrue(store.list().isEmpty());
        assertNull(new RoomStore(file).find(room.getId()));
    }

    @Test
    void sameEasyTierNetworkWithDifferentPeerStaysOneRoom() {
        RoomStore store = new RoomStore(tempDir.resolve("mp-rooms.json"));
        store.remember("EASYTIER", easyTierInvite("mine", "secret", "10.0.0.5"), "", 0, "挖矿网");
        store.remember("EASYTIER", easyTierInvite("mine", "secret", "10.0.9.9"), "", 0, "挖矿网");
        assertEquals(1, store.list().size(), "同一网络换了本机 peer 也不该堆出第二条");
    }

    private static String easyTierInvite(String name, String secret, String peer) {
        String raw = name + "|" + secret + "|" + peer;
        return "pmcl-" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void listOrdersByLastUsed() throws Exception {
        RoomStore store = new RoomStore(tempDir.resolve("mp-rooms.json"));
        RoomStore.Room first = store.remember("EASYTIER", "code-a", "", 0, "A");
        Thread.sleep(5);
        store.remember("EASYTIER", "code-b", "", 0, "B");
        Thread.sleep(5);
        store.remember("EASYTIER", "code-a", "", 0, "A");
        List<RoomStore.Room> rooms = store.list();
        assertEquals("A", rooms.get(0).getName(), "最近使用的房间排最前");
        assertEquals(first.getId(), rooms.get(0).getId());
    }
}
