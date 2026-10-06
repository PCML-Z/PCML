package com.pmcl.core.modloader;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcxpForbricInstallerTest {

    @Test
    void release010JarIsSelectable() {
        List<ForbricInstaller.InstallerAsset> assets = ForbricInstaller.selectInstallers("""
                [{
                  "tag_name": "v0.10",
                  "draft": false,
                  "prerelease": false,
                  "assets": [
                    {"name": "forbric-kernel-installer-0.10.zip", "browser_download_url": "https://github.com/PCML-Z/ECXP-Forbric-/releases/download/v0.10/forbric-kernel-installer-0.10.zip", "size": 6898308},
                    {"name": "forbric-kernel-installer-0.10.jar", "browser_download_url": "https://github.com/PCML-Z/ECXP-Forbric-/releases/download/v0.10/forbric-kernel-installer-0.10.jar", "size": 6903883, "digest": "sha256:5a0a976067143910d355c5493a74d3405a79a646204f19ca57d6f91e8d930fc2"}
                  ]
                }]
                """);
        assertEquals(1, assets.size());
        assertEquals("0.10", assets.get(0).version);
        assertEquals("v0.10", assets.get(0).tag);
        assertTrue(assets.get(0).stable);
        assertTrue(assets.get(0).url.endsWith("forbric-kernel-installer-0.10.jar"));
        assertEquals("5a0a976067143910d355c5493a74d3405a79a646204f19ca57d6f91e8d930fc2", assets.get(0).sha256);
    }

    @Test
    void supportsThePinnedMinecraftVersions() {
        assertTrue(EcxpForbricInstaller.supportsGame("26.2"));
        assertFalse(EcxpForbricInstaller.supportsGame("1.21.8"));
        assertFalse(EcxpForbricInstaller.supportsGame("1.21.1"));
        assertTrue(EcxpForbricInstaller.supportsGame(null));
        assertTrue(EcxpForbricInstaller.supportsGame("  "));
        assertFalse(EcxpForbricInstaller.supportsGame("1.20.4"));
        assertEquals("26.2", EcxpForbricInstaller.canonicalGame(null));
        assertEquals("26.2-ecxp-forbric", EcxpForbricInstaller.versionId("26.2"));
        assertEquals(List.of("26.2"), EcxpForbricInstaller.MINECRAFT_VERSIONS);
    }

    @Test
    void commandTargetsTheRequestedMinecraftVersion() {
        Path jar = Path.of("/tmp/forbric-kernel-installer-0.4.0.jar");
        Path dir = Path.of("/tmp/pmcl");
        List<String> command = ForbricInstaller.command(
                "/usr/bin/java", jar, dir, "1.21.8", "v0.4.0");
        assertEquals("--mc", command.get(5));
        assertEquals("1.21.8", command.get(6));
        assertEquals("--release", command.get(7));
        assertFalse(command.contains("sh"));
    }

    @Test
    void installResultKeepsTheExistingForbricProfile(@TempDir Path root) throws Exception {
        Path libraries = root.resolve("libraries");
        Path versions = root.resolve("versions");
        String mc = "1.21.1";
        String stockId = mc + "-forbric";
        String id = mc + "-ecxp-forbric";
        String gamePath = "net/forbric/patched-mc-merged/" + mc + "/patched-mc-merged-" + mc + ".jar";
        String kernelPath = "net/forbric/forbric-kernel/0.4.0/forbric-kernel-0.4.0.jar";
        Path gameJar = libraries.resolve(gamePath);
        Path kernelJar = libraries.resolve(kernelPath);
        Files.createDirectories(gameJar.getParent());
        Files.createDirectories(kernelJar.getParent());
        Files.writeString(gameJar, "OLD-GAME");
        Files.writeString(kernelJar, "OLD-KERNEL");

        Path stock = versions.resolve(stockId);
        Files.createDirectories(stock);
        Files.writeString(stock.resolve("keep.txt"), "forbric-profile");
        Files.writeString(stock.resolve(stockId + ".json"), profile(stockId, mc, gamePath, kernelPath, "OLD"));

        Path snapshot = root.resolve("snapshot");
        EcxpForbricInstaller.snapshotForbricLibraries(libraries.resolve("net").resolve("forbric"), snapshot);

        Files.writeString(gameJar, "NEW-GAME");
        Files.writeString(kernelJar, "NEW-KERNEL");
        Path ecxpDir = versions.resolve(id);
        Files.createDirectories(ecxpDir);
        Path written = ecxpDir.resolve(id + ".json");
        Files.writeString(written, profile(id, mc, gamePath, kernelPath, "NEW"));

        EcxpForbricInstaller.isolateForbricLibraries(libraries, written);
        EcxpForbricInstaller.restoreSnapshot(snapshot, libraries.resolve("net").resolve("forbric"));

        assertEquals("OLD-GAME", Files.readString(gameJar));
        assertEquals("OLD-KERNEL", Files.readString(kernelJar));
        assertEquals("forbric-profile", Files.readString(versions.resolve(stockId).resolve("keep.txt")));

        Path ecxpJar = libraries.resolve(
                "net/forbric/patched-mc-merged/" + mc + "-ecxp/patched-mc-merged-" + mc + "-ecxp.jar");
        Path ecxpKernel = libraries.resolve(
                "net/forbric/forbric-kernel/0.4.0-ecxp/forbric-kernel-0.4.0-ecxp.jar");
        assertEquals("NEW-GAME", Files.readString(ecxpJar));
        assertEquals("NEW-KERNEL", Files.readString(ecxpKernel));

        var json = JsonParser.parseString(Files.readString(
                versions.resolve(id).resolve(id + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(id, json.get("id").getAsString());
        assertEquals("net.forbric:patched-mc-merged:" + mc + "-ecxp",
                json.getAsJsonArray("libraries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("net.fabricmc:fabric-loader:0.19.3",
                json.getAsJsonArray("libraries").get(2).getAsJsonObject().get("name").getAsString());
        String gameArg = json.getAsJsonObject("arguments").getAsJsonArray("game").get(1).getAsString();
        assertTrue(gameArg.contains(mc + "-ecxp/patched-mc-merged-" + mc + "-ecxp.jar"));
        assertFalse(Files.exists(versions.resolve(stockId).resolve(id + ".json")));
    }

    private static String profile(String id, String mc, String gamePath, String kernelPath, String marker) {
        return """
                {
                  "id": "%s",
                  "inheritsFrom": "%s",
                  "marker": "%s",
                  "libraries": [
                    {"name": "net.forbric:patched-mc-merged:%s", "downloads": {"artifact": {"path": "%s"}}},
                    {"name": "net.forbric:forbric-kernel:0.4.0", "downloads": {"artifact": {"path": "%s"}}},
                    {"name": "net.fabricmc:fabric-loader:0.19.3", "downloads": {"artifact": {"path": "net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"}}}
                  ],
                  "arguments": {"game": ["--gameJar", "${library_directory}/%s"]}
                }
                """.formatted(id, mc, marker, mc, gamePath, kernelPath, gamePath);
    }
}
