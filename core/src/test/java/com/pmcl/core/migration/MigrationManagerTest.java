package com.pmcl.core.migration;

import com.pmcl.core.i18n.I18n;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationManagerTest {

    @TempDir
    Path tmp;

    @Test
    void macSharedFolderFollowsLauncherXNotHmcl() throws Exception {
        I18n.setLocale(I18n.ZH_CN);
        Path home = tmp.resolve("home");
        Path launcherXGame = home.resolve("Library/Application Support/.minecraft");
        Path hmclGame = home.resolve("Library/Application Support/minecraft");
        Path decoy = home.resolve("decoy");
        Files.createDirectories(launcherXGame.resolve("versions/1.21.8-fabric-0.16.14"));
        Files.createDirectories(decoy.resolve("versions/wrong"));
        Files.createDirectories(hmclGame.resolve("cache"));
        Files.createDirectories(home.resolve(".minecraft/assets"));

        Files.createDirectories(home.resolve(".hmcl"));
        Files.writeString(home.resolve(".hmcl/hmcl.json"), """
                {"configurations":{
                  "Default":{"gameDir":".minecraft","useRelativePath":true},
                  "Home":{"gameDir":"%s","useRelativePath":false}
                }}
                """.formatted(hmclGame));
        Files.createDirectories(home.resolve("Library/Application Support/hmcl/config"));
        Files.writeString(home.resolve("Library/Application Support/hmcl/config/user-game-directories.json"), """
                {"directories":[{"path":"%s"}]}
                """.formatted(hmclGame));
        Files.createDirectories(home.resolve("Library/Application Support/LauncherX"));
        Files.writeString(home.resolve("Library/Application Support/LauncherX/launcherx.json"), """
                {"Path":"%s","VariableConfigurationDic":{"GamePathList":{"Value":[{"Path":"%s"}]}}}
                """.formatted(decoy, launcherXGame));
        Files.writeString(launcherXGame.resolve("lx_profiles.json"),
                "{\"launcherVersion\":{\"name\":\"LauncherX\"}}");

        List<MigrationManager.Source> sources = new MigrationManager(tmp.resolve("pmcl"))
                .detectSources(home, "Mac OS X", Map.of());
        assertEquals(1, sources.size(), sources.stream().map(MigrationManager.Source::getName).toList().toString());
        assertEquals("LauncherX", sources.get(0).getName());
        assertEquals(launcherXGame.toAbsolutePath().normalize(), sources.get(0).getGameRoot().toAbsolutePath().normalize());
    }

    @Test
    void hmclExplicitDirectoryAndOfficialDirectoryStaySeparate() throws Exception {
        I18n.setLocale(I18n.ZH_CN);
        Path home = tmp.resolve("home");
        Path hmclGame = home.resolve("Games/hmcl-mc");
        Path official = home.resolve("Library/Application Support/minecraft");
        Files.createDirectories(hmclGame.resolve("versions/1.20.1"));
        Files.createDirectories(official.resolve("versions/1.21.1"));
        Files.createDirectories(home.resolve("Library/Application Support/hmcl/config"));
        Files.writeString(home.resolve("Library/Application Support/hmcl/config/user-game-directories.json"),
                "{\"directories\":[{\"path\":\"" + hmclGame + "\"}]}");

        List<MigrationManager.Source> sources = new MigrationManager(tmp.resolve("pmcl"))
                .detectSources(home, "Mac OS X", Map.of());
        assertEquals(2, sources.size());
        assertTrue(sources.stream().anyMatch(s -> s.getName().equals("HMCL")
                && s.getGameRoot().toAbsolutePath().normalize().equals(hmclGame.toAbsolutePath().normalize())));
        assertTrue(sources.stream().anyMatch(s -> s.getName().equals("系统 Minecraft")
                && s.getGameRoot().toAbsolutePath().normalize().equals(official.toAbsolutePath().normalize())));
    }

    @Test
    void pclLaunchFolderIsNotLabeledOfficial() throws Exception {
        I18n.setLocale(I18n.ZH_CN);
        Path home = tmp.resolve("home");
        Path game = home.resolve("Games/pcl-mc");
        Files.createDirectories(game.resolve("versions/1.16.5"));
        Files.createDirectories(home.resolve("PCL"));
        Files.writeString(home.resolve("PCL/Setup.ini"), "LaunchFolder=" + game + "\n");

        List<MigrationManager.Source> sources = new MigrationManager(tmp.resolve("pmcl"))
                .detectSources(home, "Mac OS X", Map.of());
        assertEquals(1, sources.size());
        assertEquals("PCL", sources.get(0).getName());
        assertEquals(game.toAbsolutePath().normalize(), sources.get(0).getGameRoot().toAbsolutePath().normalize());
    }
}
