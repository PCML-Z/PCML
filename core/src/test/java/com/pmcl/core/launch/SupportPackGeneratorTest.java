package com.pmcl.core.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportPackGeneratorTest {

    @TempDir
    Path tmp;

    @Test
    void looksLikeCrashDetectsVanillaAndForgeMarkers() {
        assertTrue(CrashAnalyzer.looksLikeCrash("---- Minecraft Crash Report ----"));
        assertTrue(CrashAnalyzer.looksLikeCrash("#@!@# Game crashed! Crash report saved to:"));
        assertTrue(CrashAnalyzer.looksLikeCrash("Minecraft has crashed!"));
        assertFalse(CrashAnalyzer.looksLikeCrash("if Minecraft has crashed, send logs"));
        assertFalse(CrashAnalyzer.looksLikeCrash("[Render thread/INFO]: Opened"));
        assertFalse(CrashAnalyzer.looksLikeCrash(""));
        assertFalse(CrashAnalyzer.looksLikeCrash(null));
    }

    @Test
    void writePacksCrashReportAndModsList() throws Exception {
        Path work = tmp.resolve("work");
        Path game = tmp.resolve("game");
        Files.createDirectories(game.resolve("crash-reports"));
        Files.createDirectories(game.resolve("mods"));
        Files.writeString(game.resolve("crash-reports").resolve("crash-2026.txt"),
                "---- Minecraft Crash Report ----\nboom", StandardCharsets.UTF_8);
        Files.writeString(game.resolve("mods").resolve("sodium.jar"), "jar", StandardCharsets.UTF_8);

        Path zip = tmp.resolve("pack.zip");
        SupportPackGenerator.write(
                zip, "1.20.1", work, game,
                List.of("line1", "---- Minecraft Crash Report ----"),
                "launcher log line",
                "extra=ok"
        );

        assertTrue(Files.isRegularFile(zip));
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertTrue(zf.getEntry("info.txt") != null);
            assertTrue(zf.getEntry("mods.txt") != null);
            assertTrue(zf.getEntry("crash-reports/crash-2026.txt") != null);
            assertTrue(zf.getEntry("logs/game-recent.log") != null);
            String mods = new String(zf.getInputStream(zf.getEntry("mods.txt")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(mods.contains("sodium.jar"));
            String info = new String(zf.getInputStream(zf.getEntry("info.txt")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(info.contains("versionId=1.20.1"));
            assertTrue(info.contains("extra=ok"));
        }
    }
}
