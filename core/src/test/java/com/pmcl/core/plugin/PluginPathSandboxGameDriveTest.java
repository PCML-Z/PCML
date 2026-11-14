package com.pmcl.core.plugin;

import com.pmcl.core.migration.GameDriveRelocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginPathSandboxGameDriveTest {

    @TempDir
    Path tmp;

    @Test
    void ownedGameLinkStaysReachableAndOtherLinksDoNot() throws Exception {
        Path work = tmp.resolve("work");
        Path volume = tmp.resolve("disk");
        Files.createDirectories(work.resolve("versions"));
        Files.writeString(work.resolve("versions").resolve("a.txt"), "hi");
        Files.createDirectories(volume);
        new GameDriveRelocation(work).moveTo(volume, null);

        Path file = work.resolve("versions").resolve("a.txt");
        assertEquals(file.toAbsolutePath().normalize(), PluginPathSandbox.requireUnderWorkDir(file, work));

        Path evil = work.resolve("mods");
        Files.createSymbolicLink(evil, Path.of("/etc"));
        assertThrows(SecurityException.class, () -> PluginPathSandbox.requireUnderWorkDir(evil, work));
    }
}
