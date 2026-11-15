package com.pmcl.core.agent;

import com.google.gson.JsonArray;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.LauncherCore;
import com.pmcl.plugin.PluginContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentApiTest {

    @TempDir
    Path tempDir;

    private LauncherCore core;

    @AfterEach
    void tearDown() {
        if (core != null) core.shutdown();
    }

    @Test
    void hidesDestructiveAndFileMethods() {
        assertTrue(AgentApi.denied("gameContent.deleteWorld"));
        assertTrue(AgentApi.denied("versions.deleteVersion"));
        assertTrue(AgentApi.denied("filesystem.readText"));
        assertTrue(AgentApi.denied("http.request"));
        assertTrue(AgentApi.denied("nbt.readSnbt"));
        assertFalse(AgentApi.denied("versions.listLocalVersions"));
        assertFalse(AgentApi.permissions().contains("FILESYSTEM"));
        assertFalse(AgentApi.permissions().contains("KILL_PROCESS"));
        assertTrue(AgentApi.leaves("ui.navigate"));
        assertFalse(AgentApi.leaves("mods.scanMods"));
        assertTrue(AgentApi.denied("recordings.delete"));
        assertTrue(AgentApi.denied("presets.delete"));
        assertTrue(AgentApi.denied("gameContent.importWorld"));
        assertTrue(AgentApi.denied("modpacks.importModpack"));
        assertTrue(AgentApi.denied("plugins.enablePlugin"));
        assertTrue(AgentApi.denied("plugins.disablePlugin"));
        assertTrue(AgentApi.denied("rooms.invitation"));
        assertFalse(AgentApi.catalog("content").contains("importWorld"));
        assertFalse(AgentApi.catalog("rooms").contains("rooms.invitation"));
        String room = AgentApi.redactRoomState(
                "{\"invitation\":\"secret\",\"roomCode\":\"U/1\",\"inRoom\":true}");
        assertFalse(room.contains("secret"));
        assertFalse(room.contains("U/1"));
        assertTrue(room.contains("true"));
        assertFalse(AgentApi.denied("loaders.install"));
        assertTrue(AgentApi.leaves("loaders.install"));
        assertTrue(AgentApi.catalog("pins").contains("pin"));
        assertTrue(AgentApi.catalog("content").contains("listWorlds"));
        assertFalse(AgentApi.catalog("content").contains("deleteWorld"));
        assertFalse(AgentApi.catalog("backups").contains("deleteSnapshot"));
        assertTrue(AgentApi.catalog("nope").contains("versionjava"));
        assertEquals("不能执行 gameContent.deleteWorld",
                AgentApi.invoke(null, "gameContent.deleteWorld", new JsonArray()));
    }

    @Test
    void callsTheSamePluginApiPythonUses() {
        core = new LauncherCore(new LauncherConfig(tempDir));
        PluginContext ctx = core.plugins().appContext("pmcl.agent", tempDir.resolve("agent-data"), AgentApi.permissions());
        String instances = AgentApi.invoke(ctx, "instances.listInstances", new JsonArray());
        assertTrue(instances.startsWith("["), instances);

        JsonArray query = new JsonArray();
        query.add("creeper");
        String wiki = AgentApi.invoke(ctx, "wiki.minecraft", query);
        assertTrue(wiki.contains("minecraft.wiki"), wiki);
        assertEquals("[]", AgentApi.invoke(ctx, "pins.pinned", new JsonArray()));
        assertTrue(AgentApi.invoke(ctx, "loaders.names", new JsonArray()).contains("FABRIC"), "loaders");
        assertTrue(AgentApi.invoke(ctx, "play.records", new JsonArray()).contains("longestStreakDays"), "play");
        assertTrue(AgentApi.invoke(ctx, "backups.listSnapshots", new JsonArray()).startsWith("["), "backups");
        assertTrue(AgentApi.invoke(ctx, "kernels.list", new JsonArray()).startsWith("["), "kernels");
        assertTrue(AgentApi.invoke(ctx, "recordings.list", new JsonArray()).startsWith("["), "recordings");
        assertTrue(AgentApi.invoke(ctx, "skins.list", new JsonArray()).startsWith("["), "skins");
        JsonArray missing = new JsonArray();
        missing.add("missing-version");
        assertTrue(AgentApi.invoke(ctx, "integrity.check", missing).contains("\"ok\":false"), "integrity");
        assertEquals("不能执行 backups.deleteSnapshot",
                AgentApi.invoke(ctx, "backups.deleteSnapshot", new JsonArray()));

        PluginContext bare = core.plugins().appContext("pmcl.agent", tempDir.resolve("agent-data"), Set.of());
        String denied = AgentApi.invoke(bare, "accounts.listAccounts", new JsonArray());
        assertTrue(denied.startsWith("permission_denied"), denied);
    }
}
