package com.pmcl.core.gamecontent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void zipNamedDotZipDoesNotReplaceTheSavesFolder() throws IOException {
        WorldManager worlds = new WorldManager(tempDir);
        Path keep = worlds.getSavesDir().resolve("Keep");
        Files.createDirectories(keep);
        Files.writeString(keep.resolve("level.dat"), "ok");
        Path zip = tempDir.resolve(".zip");
        try (ZipOutputStream ignored = new ZipOutputStream(Files.newOutputStream(zip))) {
            // 空压缩包。名字去掉 .zip 后是空字符串。
        }
        assertThrows(IOException.class, () -> worlds.importWorld(zip));
        assertTrue(Files.isRegularFile(keep.resolve("level.dat")));
    }
}
