package com.pmcl.core.launch;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.preferences.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameDirResolverTest {

    @TempDir
    Path tmp;

    @Test
    void isolationCopiesSharedModsAndConfig() throws Exception {
        Path work = tmp.resolve("pmcl");
        Files.createDirectories(work.resolve("versions").resolve("1.20.1"));
        Files.writeString(work.resolve("versions").resolve("1.20.1").resolve("1.20.1.json"),
                "{\"id\":\"1.20.1\"}", StandardCharsets.UTF_8);
        Files.createDirectories(work.resolve("mods"));
        Files.writeString(work.resolve("mods").resolve("fabric-api.jar"), "jar", StandardCharsets.UTF_8);
        Files.createDirectories(work.resolve("mods").resolve("1.20.1"));
        Files.writeString(work.resolve("mods").resolve("1.20.1").resolve("sodium.jar"), "jar", StandardCharsets.UTF_8);
        Files.createDirectories(work.resolve("config"));
        Files.writeString(work.resolve("config").resolve("sodium-options.json"), "{}", StandardCharsets.UTF_8);

        Preferences pref = new Preferences(work.resolve("preferences.json"));
        pref.setVersionIsolation(true);
        GameDirResolver resolver = new GameDirResolver(new LauncherConfig(work), pref);

        Path gameDir = resolver.resolveGameDir("1.20.1", work);
        Path isolated = work.resolve("instances").resolve("1.20.1");
        assertEquals(isolated, gameDir);
        assertTrue(Files.isRegularFile(isolated.resolve("mods").resolve("fabric-api.jar")));
        assertTrue(Files.isRegularFile(isolated.resolve("mods").resolve("sodium.jar")));
        assertTrue(Files.isRegularFile(isolated.resolve("config").resolve("sodium-options.json")));
        // 原文件还在，是拷贝不是搬家
        assertTrue(Files.isRegularFile(work.resolve("mods").resolve("fabric-api.jar")));
    }

    @Test
    void isolationLeavesModpackVersionDirAlone() throws Exception {
        Path work = tmp.resolve("pmcl");
        Path versionDir = work.resolve("versions").resolve("CreatePack");
        Files.createDirectories(versionDir.resolve("mods"));
        Files.writeString(versionDir.resolve("CreatePack.json"), "{\"id\":\"CreatePack\"}", StandardCharsets.UTF_8);
        Files.writeString(versionDir.resolve("mods").resolve("create.jar"), "jar", StandardCharsets.UTF_8);

        Preferences pref = new Preferences(work.resolve("preferences.json"));
        pref.setVersionIsolation(true);
        GameDirResolver resolver = new GameDirResolver(new LauncherConfig(work), pref);

        Path gameDir = resolver.resolveGameDir("CreatePack", work);
        assertEquals(versionDir, gameDir);
        assertFalse(Files.isDirectory(work.resolve("instances").resolve("CreatePack")));
        assertTrue(Files.isRegularFile(versionDir.resolve("mods").resolve("create.jar")));
    }

    @Test
    void seedRunsOnlyOnce() throws Exception {
        Path work = tmp.resolve("pmcl");
        Files.createDirectories(work.resolve("versions").resolve("1.21"));
        Files.writeString(work.resolve("versions").resolve("1.21").resolve("1.21.json"),
                "{\"id\":\"1.21\"}", StandardCharsets.UTF_8);
        Files.createDirectories(work.resolve("mods"));
        Files.writeString(work.resolve("mods").resolve("a.jar"), "a", StandardCharsets.UTF_8);

        Preferences pref = new Preferences(work.resolve("preferences.json"));
        pref.setVersionIsolation(true);
        GameDirResolver resolver = new GameDirResolver(new LauncherConfig(work), pref);
        Path isolatedMods = resolver.resolveGameDir("1.21", work).resolve("mods");
        Files.deleteIfExists(isolatedMods.resolve("a.jar"));

        resolver.resolveGameDir("1.21", work);
        assertFalse(Files.exists(isolatedMods.resolve("a.jar")),
                "用户删掉的模组不应在再次解析时从全局目录冒回来");
    }

    @Test
    void resolveModsDirPrefersExistingUuidInstance() throws Exception {
        Path work = tmp.resolve("pmcl");
        String instanceId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        Path instanceDir = work.resolve("instances").resolve(instanceId);
        Files.createDirectories(instanceDir.resolve("mods"));
        Files.writeString(instanceDir.resolve("mods").resolve("from-instance.jar"), "x", StandardCharsets.UTF_8);

        Preferences pref = new Preferences(work.resolve("preferences.json"));
        GameDirResolver resolver = new GameDirResolver(new LauncherConfig(work), pref);

        Path mods = resolver.resolveModsDir("1.20.1", instanceId);
        assertEquals(instanceDir.resolve("mods"), mods);
    }

    @Test
    void resolveModsDirFindsLegacyNamedInstanceByJsonId() throws Exception {
        Path work = tmp.resolve("pmcl");
        Path named = work.resolve("instances").resolve("MyPack");
        Files.createDirectories(named.resolve("mods"));
        String id = "11111111-2222-3333-4444-555555555555";
        Files.writeString(named.resolve("instance.json"),
                "{\"instanceId\":\"" + id + "\",\"name\":\"MyPack\",\"baseVersionId\":\"1.20.1\"}",
                StandardCharsets.UTF_8);

        Preferences pref = new Preferences(work.resolve("preferences.json"));
        GameDirResolver resolver = new GameDirResolver(new LauncherConfig(work), pref);

        Path mods = resolver.resolveModsDir("1.20.1", id);
        assertEquals(named.resolve("mods"), mods);
    }

    @Test
    void resolveModsDirFindsLegacyModpackJsonOnlyDir() throws Exception {
        Path work = tmp.resolve("pmcl");
        Path named = work.resolve("instances").resolve("OldPack");
        Files.createDirectories(named.resolve("mods"));
        Files.writeString(named.resolve("modpack.json"),
                "{\"name\":\"OldPack\",\"gameVersion\":\"1.20.1\",\"loader\":\"fabric\"}",
                StandardCharsets.UTF_8);
        String id = java.util.UUID.nameUUIDFromBytes(
                named.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)
        ).toString();

        Preferences pref = new Preferences(work.resolve("preferences.json"));
        GameDirResolver resolver = new GameDirResolver(new LauncherConfig(work), pref);

        Path mods = resolver.resolveModsDir("1.20.1", id);
        assertEquals(named.resolve("mods"), mods);
    }

    @Test
    void resolveModsDirRejectsPathTraversalInstanceId() throws Exception {
        Path work = tmp.resolve("pmcl");
        Files.createDirectories(work.resolve("mods"));
        Preferences pref = new Preferences(work.resolve("preferences.json"));
        GameDirResolver resolver = new GameDirResolver(new LauncherConfig(work), pref);

        Path mods = resolver.resolveModsDir(null, "../etc");
        assertEquals(work.resolve("mods"), mods);
    }
}
