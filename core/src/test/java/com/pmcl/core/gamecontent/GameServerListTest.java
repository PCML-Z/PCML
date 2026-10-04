package com.pmcl.core.gamecontent;

import com.pmcl.core.nbt.NbtReader;
import com.pmcl.core.nbt.NbtTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GameServerListTest {

    @TempDir
    Path dir;

    @Test
    void parsesHostAndPort() {
        GameServerList.Address split = GameServerList.parse("play.example.com:25566", 25565);
        assertEquals("play.example.com", split.getHost());
        assertEquals(25566, split.getPort());
        GameServerList.Address plain = GameServerList.parse("play.example.com", 25565);
        assertEquals(25565, plain.getPort());
        assertNull(GameServerList.parse("../evil", 25565));
        assertNull(GameServerList.parse("play.example.com/path", 25565));
        GameServerList.Address hidden = GameServerList.parse("room@play.example.com:25566", 25565);
        assertEquals("play.example.com", hidden.getHost());
        assertEquals(25566, hidden.getPort());
    }

    @Test
    void writesServerAndKeepsExistingEntries() throws Exception {
        GameServerList.add(dir, "Hypixel", "mc.hypixel.net", 25565);
        GameServerList.add(dir, "本地", "127.0.0.1", 25566);
        NbtTag.CompoundTag root = (NbtTag.CompoundTag) NbtReader.read(dir.resolve("servers.dat"));
        NbtTag.ListTag servers = (NbtTag.ListTag) root.get("servers");
        assertEquals(2, servers.size());
        NbtTag.CompoundTag first = (NbtTag.CompoundTag) servers.getItems().get(0);
        assertEquals("本地", ((NbtTag.StringTag) first.get("name")).getValue());
        assertEquals("127.0.0.1:25566", ((NbtTag.StringTag) first.get("ip")).getValue());
        GameServerList.add(dir, "本地房间", "127.0.0.1", 25566);
        root = (NbtTag.CompoundTag) NbtReader.read(dir.resolve("servers.dat"));
        servers = (NbtTag.ListTag) root.get("servers");
        assertEquals(2, servers.size());
        first = (NbtTag.CompoundTag) servers.getItems().get(0);
        assertEquals("本地房间", ((NbtTag.StringTag) first.get("name")).getValue());
        GameServerList.add(dir, "隐身", "play.example.com", 25565, "room");
        root = (NbtTag.CompoundTag) NbtReader.read(dir.resolve("servers.dat"));
        servers = (NbtTag.ListTag) root.get("servers");
        assertEquals(3, servers.size());
        NbtTag.CompoundTag hidden = (NbtTag.CompoundTag) servers.getItems().get(0);
        assertEquals("room@play.example.com", ((NbtTag.StringTag) hidden.get("ip")).getValue());
    }

    @Test
    void refusesToReplaceBrokenServerList() {
        assertThrows(Exception.class, () -> GameServerList.add(null, "a", "localhost", 25565));
    }
}
