package com.pmcl.core.preferences;

import com.pmcl.core.gamecontent.VersionGameFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionSettingsTest {

    @TempDir
    Path tmp;

    @Test
    void blankFieldsKeepGlobalValues() {
        VersionSettings settings = new VersionSettings(
                "  创造 ", "", "", 0, 2048, "", "", 1280, 0, VersionSettings.FULLSCREEN_INHERIT);
        assertEquals("创造", settings.getDisplayName());
        assertEquals(2048, settings.memoryMax(4096));
        assertEquals(512, settings.memoryMin(512));
        assertEquals(1280, settings.width(854));
        assertEquals(480, settings.height(480));
        assertFalse(settings.fullscreen(false));
        assertTrue(settings.fullscreen(true));
        assertEquals("-Xmx2G", settings.jvmArgs("-Xmx2G"));
        assertNull(VersionSettings.parseGameDir("../outside"));
        assertNull(VersionSettings.parseGameDir(""));
    }

    @Test
    void emptyVersionGetsItsOwnCopyOfTheDefaults() {
        VersionSettings first = VersionSettings.empty().fillDefaults(
                "1.21.8", tmp.resolve("one").toString(),
                512, 4096, "/usr/bin/java", "-Xmx2G",
                854, 480, false, tmp.resolve("icon.png").toString());
        VersionSettings second = VersionSettings.empty().fillDefaults(
                "1.20.1", tmp.resolve("two").toString(),
                512, 4096, "/usr/bin/java", "-Xmx2G",
                854, 480, true, tmp.resolve("icon.png").toString());
        assertEquals("1.21.8", first.getDisplayName());
        assertEquals(tmp.resolve("one").toAbsolutePath().normalize(), VersionSettings.parseGameDir(first.getGameDir()));
        assertEquals(512, first.getMinMemoryMb());
        assertEquals(4096, first.getMaxMemoryMb());
        assertEquals(854, first.getWindowWidth());
        assertEquals(VersionSettings.FULLSCREEN_WINDOW, first.getFullscreenMode());
        assertEquals("1.20.1", second.getDisplayName());
        assertEquals(VersionSettings.FULLSCREEN_ON, second.getFullscreenMode());
        assertFalse(first.getGameDir().equals(second.getGameDir()));

        VersionSettings custom = new VersionSettings(
                "创造", tmp.resolve("custom").toString(), "",
                1024, 2048, "", "", 1280, 720, VersionSettings.FULLSCREEN_ON);
        VersionSettings kept = custom.fillDefaults(
                "1.21.8", tmp.resolve("one").toString(),
                512, 4096, "/usr/bin/java", "-Xmx2G",
                854, 480, false, tmp.resolve("icon.png").toString());
        assertEquals("创造", kept.getDisplayName());
        assertEquals(1024, kept.getMinMemoryMb());
        assertEquals(1280, kept.getWindowWidth());
        assertEquals(VersionSettings.FULLSCREEN_ON, kept.getFullscreenMode());
        assertEquals("/usr/bin/java", kept.getJavaPath());
    }

    @Test
    void versionOverridesReplaceGlobalLaunchValues() {
        VersionSettings settings = new VersionSettings(
                "", tmp.toString(), tmp.resolve("icon.png").toString(),
                1024, 8192, "/usr/bin/java", "-XX:+UseG1GC",
                1920, 1080, VersionSettings.FULLSCREEN_ON);
        assertEquals(1024, settings.memoryMin(512));
        assertEquals(8192, settings.memoryMax(4096));
        assertEquals("-XX:+UseG1GC", settings.jvmArgs(""));
        assertEquals("-Xmx2G -XX:+UseG1GC", settings.jvmArgs("-Xmx2G"));
        assertEquals(1920, settings.width(854));
        assertTrue(settings.fullscreen(false));
        assertEquals(tmp.toAbsolutePath().normalize(), VersionSettings.parseGameDir(tmp.toString()));
        VersionSettings restored = VersionSettings.fromJson(settings.toJson());
        assertEquals(settings.getJavaPath(), restored.getJavaPath());
        assertEquals(VersionSettings.FULLSCREEN_ON, restored.getFullscreenMode());
        assertTrue(settings.getDebug().isDefault());
    }

    @Test
    void debugOptionsRoundTripAndRejectUnsafePaths() {
        VersionSettings settings = new VersionSettings(
                "创造", tmp.toString(), "",
                0, 0, "", "", 0, 0, VersionSettings.FULLSCREEN_INHERIT,
                new VersionSettings.DebugOptions(
                        tmp.toString(),
                        VersionSettings.DebugOptions.API_VULKAN,
                        VersionSettings.DebugOptions.DRIVER_LAVAPIPE,
                        true, false, true, true, true, false, false));
        assertFalse(settings.isBlank());
        VersionSettings restored = VersionSettings.fromJson(settings.toJson());
        assertEquals(tmp.toAbsolutePath().normalize().toString(), restored.getDebug().getNativesDir());
        assertEquals(VersionSettings.DebugOptions.API_VULKAN, restored.getDebug().getGraphicsApi());
        assertEquals(VersionSettings.DebugOptions.DRIVER_LAVAPIPE, restored.getDebug().getDriver());
        assertTrue(restored.getDebug().isSkipGameCheck());
        assertTrue(restored.getDebug().isSkipJvmCheck());
        VersionSettings dropped = settings.withDebug(new VersionSettings.DebugOptions(
                "relative/natives",
                VersionSettings.DebugOptions.API_OPENGL,
                VersionSettings.DebugOptions.DRIVER_LAVAPIPE,
                false, false, false, false, false, false, false));
        assertEquals("", dropped.getDebug().getNativesDir());
        assertEquals(VersionSettings.DebugOptions.DRIVER_DEFAULT, dropped.getDebug().getDriver());
    }

    @Test
    void filesStayInsideTheVersionFolder() throws Exception {
        Path game = tmp.resolve("game");
        Path source = tmp.resolve("sodium.jar");
        Files.writeString(source, "jar");
        VersionGameFiles.importFile(game, VersionGameFiles.MODS, source);
        assertEquals(java.util.List.of("sodium.jar"), VersionGameFiles.list(game, VersionGameFiles.MODS));
        assertThrows(Exception.class, () -> VersionGameFiles.delete(game, VersionGameFiles.MODS, "../sodium.jar"));
        assertTrue(Files.isRegularFile(game.resolve("mods").resolve("sodium.jar")));
        VersionGameFiles.delete(game, VersionGameFiles.MODS, "sodium.jar");
        assertTrue(VersionGameFiles.list(game, VersionGameFiles.MODS).isEmpty());

        Path shader = tmp.resolve("complementary.zip");
        Files.writeString(shader, "zip");
        VersionGameFiles.importFile(game, VersionGameFiles.SHADER_PACKS, shader);
        assertTrue(Files.isRegularFile(game.resolve("shaderpacks").resolve("complementary.zip")));
        Path projection = tmp.resolve("house.litematic");
        Files.writeString(projection, "lit");
        VersionGameFiles.importFile(game, VersionGameFiles.SCHEMATICS, projection);
        assertTrue(Files.isRegularFile(game.resolve("schematics").resolve("house.litematic")));
        assertThrows(Exception.class, () -> VersionGameFiles.delete(game, VersionGameFiles.SCHEMATICS, "../house.litematic"));
        assertThrows(Exception.class, () -> VersionGameFiles.importFile(game, VersionGameFiles.SCHEMATICS, source));

        Path nested = game.resolve("schematics").resolve("farms");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("barn.litematic"), "lit");
        java.util.List<String> nestedNames = VersionGameFiles.schematicFiles(game.resolve("schematics")).stream()
                .map(path -> VersionGameFiles.schematicRelative(game.resolve("schematics"), path))
                .sorted()
                .toList();
        assertEquals(java.util.List.of("farms/barn.litematic", "house.litematic"), nestedNames);
        VersionGameFiles.deleteSchematic(game, "farms/barn.litematic");
        assertFalse(Files.exists(nested.resolve("barn.litematic")));
        assertThrows(Exception.class, () -> VersionGameFiles.deleteSchematic(game, "farms/../../outside.litematic"));
        assertTrue(Files.isRegularFile(game.resolve("schematics").resolve("house.litematic")));
    }
}
