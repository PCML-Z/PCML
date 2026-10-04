package com.pmcl.core.modloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForbricInstallerTest {

    @Test
    void picksTheInstallerJarAndIgnoresScripts() {
        List<ForbricInstaller.InstallerAsset> assets = ForbricInstaller.selectInstallers("""
                [
                  {
                    "tag_name": "v0.3.0",
                    "draft": false,
                    "prerelease": false,
                    "assets": [
                      {"name": "Forbric-Installer.bat", "browser_download_url": "https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/download/v0.3.0/Forbric-Installer.bat", "size": 10},
                      {"name": "Forbric-Installer.command", "browser_download_url": "https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/download/v0.3.0/Forbric-Installer.command", "size": 10},
                      {"name": "forbric-kernel-installer-0.3.0.jar.sha256", "browser_download_url": "https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/download/v0.3.0/forbric-kernel-installer-0.3.0.jar.sha256", "size": 64},
                      {"name": "forbric-kernel-installer-0.3.0.jar", "browser_download_url": "https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/download/v0.3.0/forbric-kernel-installer-0.3.0.jar", "size": 100, "digest": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                    ]
                  },
                  {
                    "tag_name": "v0.3.1-beta.1",
                    "draft": false,
                    "prerelease": true,
                    "assets": [
                      {"name": "forbric-kernel-installer-0.3.1-beta.1.jar", "browser_download_url": "https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/download/v0.3.1-beta.1/forbric-kernel-installer-0.3.1-beta.1.jar", "size": 80}
                    ]
                  },
                  {
                    "tag_name": "v9",
                    "draft": true,
                    "assets": [
                      {"name": "forbric-kernel-installer-9.jar", "browser_download_url": "https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader/releases/download/v9/forbric-kernel-installer-9.jar", "size": 10}
                    ]
                  },
                  {
                    "tag_name": "vbad",
                    "draft": false,
                    "assets": [
                      {"name": "forbric-kernel-installer-bad.jar", "browser_download_url": "http://127.0.0.1/installer.jar", "size": 10}
                    ]
                  }
                ]
                """);
        assertEquals(2, assets.size());
        ForbricInstaller.InstallerAsset release = assets.get(0);
        assertEquals("0.3.0", release.version);
        assertEquals("v0.3.0", release.tag);
        assertTrue(release.stable);
        assertTrue(release.url.endsWith("forbric-kernel-installer-0.3.0.jar"));
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", release.sha256);
        assertFalse(assets.get(1).stable);
        assertEquals("0.3.1-beta.1", assets.get(1).version);
        assertNull(assets.get(1).sha256);
    }

    @Test
    void commandIsJavaJarWithoutAShell() {
        Path jar = Path.of("/tmp/forbric-kernel-installer-0.3.0.jar");
        Path dir = Path.of("/tmp/pmcl");
        List<String> command = ForbricInstaller.command(
                "/usr/bin/java", jar, dir, "26.2", "v0.3.0");
        assertEquals("/usr/bin/java", command.get(0));
        assertEquals("-jar", command.get(1));
        assertEquals(jar.toAbsolutePath().toString(), command.get(2));
        assertEquals("--dir", command.get(3));
        assertEquals(dir.toAbsolutePath().toString(), command.get(4));
        assertEquals("--mc", command.get(5));
        assertEquals("26.2", command.get(6));
        assertEquals("--release", command.get(7));
        assertEquals("v0.3.0", command.get(8));
        assertFalse(command.contains("sh"));
        assertFalse(command.contains("cmd"));
        assertFalse(command.contains("--artifacts"));
        assertEquals("26.2-forbric", ForbricInstaller.versionId("26.2"));
    }

    @Test
    void onlyMinecraft262IsListed() {
        assertTrue(ForbricInstaller.supportsGame("26.2"));
        assertTrue(ForbricInstaller.supportsGame(" 26.2 "));
        assertTrue(ForbricInstaller.supportsGame(null));
        assertFalse(ForbricInstaller.supportsGame("1.20.4"));
        assertFalse(ForbricInstaller.isInstallerJar("Forbric-Installer.bat"));
        assertFalse(ForbricInstaller.isInstallerJar("Forbric-Installer.command"));
        assertTrue(ForbricInstaller.isInstallerJar("forbric-kernel-installer-0.3.0.jar"));
    }

    @Test
    void installedVersionReadsKernelAndSkipsMissingProfile(@TempDir Path dir) throws Exception {
        assertNull(ForbricInstaller.installedVersion(dir));
        Path profile = dir.resolve("26.2-forbric");
        Files.createDirectories(profile);
        Files.writeString(profile.resolve("26.2-forbric.json"), """
                {"libraries":[{"name":"net.forbric:forbric-kernel:0.3.1-beta2"}]}
                """);
        assertEquals("0.3.1-beta2", ForbricInstaller.installedVersion(dir));
    }

    @Test
    void buildProgressKeepsGrowingAcrossFiles() {
        ForbricInstaller.BuildProgress progress = new ForbricInstaller.BuildProgress(8L * 1024 * 1024);
        progress.observe("  forge.jar    0%  16 KB / 3.4 MB");
        long afterFirst = progress.completed();
        progress.observe("  forge.jar  100%  3.4 MB / 3.4 MB");
        progress.observe("  server.jar    7%  4.3 MB / 58.1 MB");
        assertTrue(progress.completed() > afterFirst);
        assertTrue(progress.total() >= progress.completed());
        assertTrue(progress.total() > 8L * 1024 * 1024);
        long mid = progress.completed();
        progress.observe("  server.jar   70%  40 MB / 58.1 MB");
        assertTrue(progress.completed() > mid);
        assertTrue(progress.total() >= progress.completed());
    }
}
