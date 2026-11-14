package com.pmcl.core.migration;

import com.pmcl.core.backup.TimeMachine;
import com.pmcl.core.i18n.I18n;
import com.pmcl.core.mods.ModScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameDriveRelocationTest {

    @TempDir
    Path tmp;

    @Test
    void moveLeavesALinkAndKeepsLauncherFiles() throws Exception {
        I18n.setLocale(I18n.ZH_CN);
        Path work = tmp.resolve("work");
        Path volume = tmp.resolve("disk");
        Files.createDirectories(work.resolve("versions").resolve("1.20.1"));
        Files.writeString(work.resolve("versions").resolve("1.20.1").resolve("1.20.1.json"), "{\"id\":\"1.20.1\"}");
        Files.writeString(work.resolve("preferences.json"), "{\"keep\":true}");
        Files.createDirectories(work.resolve("plugins"));
        Files.writeString(work.resolve("plugins").resolve("keep.txt"), "plugin");
        fabricJar(work.resolve("mods").resolve("foo.jar"));
        Files.createDirectories(work.resolve("saves").resolve("World"));
        Files.writeString(work.resolve("saves").resolve("World").resolve("level.dat"), "world");
        Files.createDirectories(volume);

        GameDriveRelocation relocation = relocation(work);
        relocation.moveTo(volume, progress -> { });

        Path versions = work.resolve("versions");
        assertTrue(Files.isSymbolicLink(versions));
        assertEquals("{\"id\":\"1.20.1\"}", Files.readString(versions.resolve("1.20.1").resolve("1.20.1.json")));
        assertTrue(Files.isSymbolicLink(work.resolve("mods")));
        assertTrue(Files.isSymbolicLink(work.resolve("saves")));
        assertFalse(Files.isSymbolicLink(work.resolve("plugins")));
        assertEquals("{\"keep\":true}", Files.readString(work.resolve("preferences.json")));
        assertEquals("foo", ModScanner.scanDirectory(work.resolve("mods")).get(0).getModId());

        relocation.moveTo(volume, progress -> { });
        assertEquals("{\"id\":\"1.20.1\"}", Files.readString(versions.resolve("1.20.1").resolve("1.20.1.json")));

        TimeMachine.Snapshot snapshot = new TimeMachine(work).backup(new TimeMachine.Settings(), List.of(), "now", null);
        assertTrue(snapshot.files() >= 1);
        assertTrue(new TimeMachine(work).entries(snapshot.id()).stream().anyMatch(entry -> entry.path().endsWith("level.dat")));
        assertTrue(new TimeMachine(work).entries(snapshot.id()).stream().anyMatch(entry -> entry.root().startsWith(work.toString())));

        relocation.moveHome(progress -> { });
        assertFalse(Files.isSymbolicLink(versions));
        assertEquals("{\"id\":\"1.20.1\"}", Files.readString(versions.resolve("1.20.1").resolve("1.20.1.json")));
        assertEquals("world", Files.readString(work.resolve("saves").resolve("World").resolve("level.dat")));
        assertFalse(Files.exists(volume.resolve("PMCL").resolve("versions")));
    }

    @Test
    void failedLinkPutsTheOriginalBack() throws Exception {
        Path work = tmp.resolve("work");
        Path volume = tmp.resolve("disk");
        Files.createDirectories(work.resolve("versions"));
        Files.writeString(work.resolve("versions").resolve("a.txt"), "hi");
        Files.createDirectories(volume);
        GameDriveRelocation relocation = relocation(work);
        relocation.beforeLink = () -> {
            throw new IOException("boom");
        };
        assertThrows(IOException.class, () -> relocation.moveTo(volume, null));
        assertFalse(Files.isSymbolicLink(work.resolve("versions")));
        assertEquals("hi", Files.readString(work.resolve("versions").resolve("a.txt")));

        relocation.beforeLink = null;
        relocation.moveTo(volume, null);
        assertTrue(Files.isSymbolicLink(work.resolve("versions")));
        assertEquals("hi", Files.readString(work.resolve("versions").resolve("a.txt")));
    }

    @Test
    void refusesToMoveInsideItselfOrWithoutSpace() throws Exception {
        Path work = tmp.resolve("work");
        Files.createDirectories(work.resolve("versions"));
        Files.writeString(work.resolve("versions").resolve("a.txt"), "hi");
        Files.createDirectories(work.resolve("nested"));
        GameDriveRelocation relocation = relocation(work);
        assertThrows(IOException.class, () -> relocation.moveTo(work.resolve("nested"), null));
        assertFalse(Files.isSymbolicLink(work.resolve("versions")));

        Files.createDirectories(tmp.resolve("other"));
        GameDriveRelocation cramped = new GameDriveRelocation(work, path -> 10);
        assertThrows(IOException.class, () -> cramped.moveTo(tmp.resolve("other"), null));
        assertEquals("hi", Files.readString(work.resolve("versions").resolve("a.txt")));
        assertFalse(Files.isSymbolicLink(work.resolve("versions")));
    }

    @Test
    void includeVolumeSkipsTheSystemDisk() {
        assertFalse(GameDriveRelocation.includeVolume("Macintosh HD", false, false, true, false, 100));
        assertFalse(GameDriveRelocation.includeVolume(".hidden", false, false, true, false, 100));
        assertFalse(GameDriveRelocation.includeVolume("Backup", true, false, true, false, 100));
        assertFalse(GameDriveRelocation.includeVolume("Backup", false, false, true, true, 100));
        assertFalse(GameDriveRelocation.includeVolume("Backup", false, false, true, false, 0));
        assertTrue(GameDriveRelocation.includeVolume("Backup", false, false, true, false, 100));
    }

    private static GameDriveRelocation relocation(Path work) {
        return new GameDriveRelocation(work, path -> Long.MAX_VALUE);
    }

    private static void fabricJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));
            zip.write("{\"id\":\"foo\",\"version\":\"1\",\"name\":\"Foo\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
