package com.pmcl.core.launch;

import com.pmcl.core.LauncherConfig;
import com.pmcl.core.auth.Account;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandaloneMacAppExporterTest {

    @TempDir
    Path tmp;

    @AfterEach
    void clearBindOverrides() {
        System.clearProperty("pmcl.bind.launcher");
        System.clearProperty("pmcl.bind.device");
    }

    @Test
    void sanitizeAndSuffix() {
        assertEquals("Fabric-1.20.1", StandaloneMacAppExporter.sanitizeAppName("Fabric-1.20.1.app"));
        assertEquals("Minecraft", StandaloneMacAppExporter.sanitizeAppName("../"));
        Path p = StandaloneMacAppExporter.withAppSuffix(tmp.resolve("Pack"));
        assertTrue(p.getFileName().toString().endsWith(".app"));
    }

    @Test
    void stripTokenAndRewritePaths() {
        List<String> cmd = new ArrayList<>(List.of(
                "/jre/bin/java",
                "--accessToken", "SECRET-TOKEN",
                "--userType", "msa",
                "-Djava.library.path=/abs/natives",
                "--gameDir", "/abs/game"
        ));
        StandaloneMacAppExporter.stripOnlineAuth(cmd);
        assertEquals("0", cmd.get(2));
        assertEquals("legacy", cmd.get(4));

        Map<String, String> map = new LinkedHashMap<>();
        map.put("/abs/natives", StandaloneMacAppExporter.CONTENTS_TOKEN + "/Game/natives");
        map.put("/abs/game", StandaloneMacAppExporter.CONTENTS_TOKEN + "/Game/gamedata");
        List<String> out = StandaloneMacAppExporter.rewriteCommand(cmd, map);
        assertTrue(out.get(5).contains(StandaloneMacAppExporter.CONTENTS_TOKEN));
        assertFalse(out.get(5).contains("/abs/natives"));
        assertTrue(StandaloneMacAppExporter.quoteArg("a b").startsWith("\""));
    }

    @Test
    void exportWritesMacBundleLayout() throws Exception {
        Path work = tmp.resolve("pmcl");
        Path jreBin = work.resolve("jre").resolve("bin");
        Files.createDirectories(jreBin);
        Path java = jreBin.resolve("java");
        Files.writeString(java, "#!/bin/sh\n", StandardCharsets.UTF_8);

        Path gameDir = work.resolve("gamedir");
        Files.createDirectories(gameDir.resolve("mods").resolve("1.20.1"));
        Files.writeString(gameDir.resolve("mods").resolve("sodium.jar"), "mod", StandardCharsets.UTF_8);
        Files.writeString(gameDir.resolve("mods").resolve("1.20.1").resolve("lithium.jar"), "nested", StandardCharsets.UTF_8);
        Files.writeString(gameDir.resolve("options.txt"), "lang:zh_cn", StandardCharsets.UTF_8);
        Files.createDirectories(work.resolve("mods"));
        Files.writeString(work.resolve("mods").resolve("fabric-api.jar"), "api", StandardCharsets.UTF_8);
        Files.createDirectories(gameDir.resolve("resourcepacks").resolve("1.20.1"));
        Files.writeString(gameDir.resolve("resourcepacks").resolve("local-rp.zip"), "rp", StandardCharsets.UTF_8);
        Files.writeString(gameDir.resolve("resourcepacks").resolve("1.20.1").resolve("faithful.zip"), "nested-rp", StandardCharsets.UTF_8);
        Files.createDirectories(gameDir.resolve("resourcepacks").resolve("FolderPack"));
        Files.writeString(gameDir.resolve("resourcepacks").resolve("FolderPack").resolve("pack.mcmeta"), "{}", StandardCharsets.UTF_8);
        Files.createDirectories(work.resolve("resourcepacks"));
        Files.writeString(work.resolve("resourcepacks").resolve("global-rp.zip"), "grp", StandardCharsets.UTF_8);
        Files.createDirectories(gameDir.resolve("shaderpacks").resolve("1.20.1"));
        Files.writeString(gameDir.resolve("shaderpacks").resolve("1.20.1").resolve("bsl.zip"), "nested-sh", StandardCharsets.UTF_8);
        Files.createDirectories(work.resolve("shaderpacks"));
        Files.writeString(work.resolve("shaderpacks").resolve("complementary.zip"), "sh", StandardCharsets.UTF_8);
        Files.writeString(work.resolve("shaderpacks").resolve("complementary.zip.txt"), "cfg", StandardCharsets.UTF_8);
        Files.writeString(work.resolve("optionsshaders.txt"), "shaderPack=complementary.zip", StandardCharsets.UTF_8);

        Path natives = work.resolve("natives");
        Files.createDirectories(natives);
        Files.writeString(natives.resolve("libglfw.dylib"), "dylib", StandardCharsets.UTF_8);

        Path assets = work.resolve("assets");
        Path idx = assets.resolve("indexes");
        Files.createDirectories(idx);
        String hash = "aa".repeat(20);
        Files.writeString(idx.resolve("5.json"),
                "{\"objects\":{\"icons/icon.png\":{\"hash\":\"" + hash + "\",\"size\":3}}}",
                StandardCharsets.UTF_8);
        Path obj = assets.resolve("objects").resolve(hash.substring(0, 2));
        Files.createDirectories(obj);
        Files.writeString(obj.resolve(hash), "png", StandardCharsets.UTF_8);

        Path lib = work.resolve("libraries").resolve("com").resolve("mojang").resolve("a.jar");
        Files.createDirectories(lib.getParent());
        Files.writeString(lib, "jar", StandardCharsets.UTF_8);

        Path client = work.resolve("versions").resolve("1.20.1").resolve("1.20.1.jar");
        Files.createDirectories(client.getParent());
        Files.writeString(client, "client", StandardCharsets.UTF_8);
        Files.writeString(client.getParent().resolve("1.20.1.json"),
                "{\"id\":\"1.20.1\",\"mainClass\":\"net.minecraft.client.main.Main\"}",
                StandardCharsets.UTF_8);

        Path fabricDir = work.resolve("versions").resolve("fabric-loader-0.15.11-1.20.1");
        Files.createDirectories(fabricDir);
        Files.writeString(fabricDir.resolve("fabric-loader-0.15.11-1.20.1.json"),
                "{\"id\":\"fabric-loader-0.15.11-1.20.1\",\"inheritsFrom\":\"1.20.1\","
                        + "\"mainClass\":\"net.fabricmc.loader.impl.launch.knot.KnotClient\"}",
                StandardCharsets.UTF_8);
        Path fabricJar = fabricDir.resolve("fabric-loader-0.15.11-1.20.1.jar");
        Files.writeString(fabricJar, "fabric-profile", StandardCharsets.UTF_8);
        Path fabricLib = work.resolve("libraries").resolve("net").resolve("fabricmc")
                .resolve("fabric-loader").resolve("0.15.11").resolve("fabric-loader-0.15.11.jar");
        Files.createDirectories(fabricLib.getParent());
        Files.writeString(fabricLib, "loader", StandardCharsets.UTF_8);

        LaunchProfile profile = new LaunchProfile(new LauncherConfig(work),
                new Account("Steve", "abc", "SECRET", Account.AccountType.MICROSOFT),
                "fabric-loader-0.15.11-1.20.1");
        profile.setGameDir(gameDir);
        profile.setMainClass("net.fabricmc.loader.impl.launch.knot.KnotClient");
        profile.addClasspath(lib);
        profile.addClasspath(client);
        profile.addClasspath(fabricLib);
        profile.addClasspath(fabricJar);
        profile.addJvmArg("-Djava.library.path=" + natives.toAbsolutePath());
        profile.addJvmArg("-Xmx2G");
        profile.addGameArg("--username");
        profile.addGameArg("Steve");
        profile.addGameArg("--gameDir");
        profile.addGameArg(gameDir.toAbsolutePath().toString());
        profile.addGameArg("--assetsDir");
        profile.addGameArg(assets.toAbsolutePath().toString());
        profile.addGameArg("--assetIndex");
        profile.addGameArg("5");
        profile.addGameArg("--accessToken");
        profile.addGameArg("SECRET");
        profile.addGameArg("--userType");
        profile.addGameArg("msa");

        Path launcher = tmp.resolve("PMCL.app");
        Files.createDirectories(launcher);
        System.setProperty("pmcl.bind.launcher", launcher.toString());
        System.setProperty("pmcl.bind.device", "test-machine");
        Path out = tmp.resolve("StevePack.app");
        new StandaloneMacAppExporter().export(profile, java.toString(), out, false, true, work, null);

        assertTrue(Files.isRegularFile(out.resolve("Contents/Info.plist")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/MacOS/launch")));
        String launch = Files.readString(out.resolve("Contents/MacOS/launch"), StandardCharsets.UTF_8);
        assertTrue(launch.contains("bind-gate.jar"));
        assertTrue(launch.contains("bind-last-error.txt"));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Resources/jvm.args")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Resources/bind.ticket")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Resources/bind-gate.jar")));
        assertTrue(Files.isRegularFile(work.resolve(StandaloneBindGate.HOST_FILE_NAME)));
        StandaloneBindGate.verify(out.resolve("Contents"));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Runtime/bin/java")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/mods/sodium.jar")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/mods/lithium.jar")),
                "versioned mods/<id>/*.jar must be flattened");
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/mods/fabric-api.jar")),
                "shared mods from workDir must be copied");
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/resourcepacks/local-rp.zip")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/resourcepacks/faithful.zip")),
                "versioned resourcepacks/<id>/*.zip must be flattened");
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/resourcepacks/global-rp.zip")),
                "shared resource packs from workDir must be copied");
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/resourcepacks/FolderPack/pack.mcmeta")),
                "folder resource packs must be copied");
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/shaderpacks/bsl.zip")),
                "versioned shaderpacks/<id>/*.zip must be flattened");
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/shaderpacks/complementary.zip")),
                "shared shader packs from workDir must be copied");
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/shaderpacks/complementary.zip.txt")),
                "Iris/OptiFine shader config sidecar must be copied");
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/gamedata/optionsshaders.txt")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/natives/libglfw.dylib")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/assets/indexes/5.json")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/libraries/com/mojang/a.jar")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/libraries/net/fabricmc/fabric-loader/0.15.11/fabric-loader-0.15.11.jar")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/versions/1.20.1/1.20.1.jar")));
        assertTrue(Files.isRegularFile(out.resolve("Contents/Game/versions/1.20.1/1.20.1.json")));
        assertTrue(Files.isRegularFile(out.resolve(
                "Contents/Game/versions/fabric-loader-0.15.11-1.20.1/fabric-loader-0.15.11-1.20.1.json")));
        String args = Files.readString(out.resolve("Contents/Resources/jvm.args"), StandardCharsets.UTF_8);
        assertFalse(args.contains("SECRET"));
        assertTrue(args.contains(StandaloneMacAppExporter.CONTENTS_TOKEN));
        assertTrue(args.contains("legacy"));
        String plist = Files.readString(out.resolve("Contents/Info.plist"), StandardCharsets.UTF_8);
        assertTrue(plist.contains("CFBundleExecutable"));
        assertTrue(plist.contains("launch"));
    }
}
