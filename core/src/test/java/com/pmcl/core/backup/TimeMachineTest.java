package com.pmcl.core.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimeMachineTest {

    @TempDir
    Path dir;

    @Test
    void secondBackupReusesUnchangedBytesAndSkipsSecrets() throws Exception {
        Path saves = dir.resolve("saves").resolve("World");
        Files.createDirectories(saves);
        Files.writeString(saves.resolve("level.dat"), "alpha");
        Files.writeString(saves.resolve("same-a.txt"), "same");
        Files.writeString(saves.resolve("same-b.txt"), "same");
        Files.writeString(saves.resolve("session.lock"), "locked");
        Path config = dir.resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("accounts.json"), "token");
        Files.writeString(config.resolve("paper.toml"), "view=2");
        Path outside = dir.resolve("outside-secret.txt");
        Files.writeString(outside, "secret");
        Files.createSymbolicLink(config.resolve("linked.txt"), outside);
        Files.createDirectories(dir.resolve("libraries"));
        Files.writeString(dir.resolve("libraries").resolve("lib.jar"), "jar");

        TimeMachine machine = new TimeMachine(dir);
        TimeMachine.Settings settings = new TimeMachine.Settings();
        TimeMachine.Snapshot first = machine.backup(settings, List.of(), "now", null);
        assertEquals(4, first.files());
        assertEquals(Files.size(saves.resolve("level.dat"))
                + Files.size(saves.resolve("same-a.txt"))
                + Files.size(saves.resolve("same-b.txt"))
                + Files.size(config.resolve("paper.toml")), first.logicalBytes());
        assertTrue(first.storedBytes() < first.logicalBytes());
        assertTrue(machine.entries(first.id()).stream().noneMatch(e -> e.path().contains("accounts.json")));
        assertTrue(machine.entries(first.id()).stream().noneMatch(e -> e.path().contains("linked")));
        assertTrue(machine.entries(first.id()).stream().noneMatch(e -> e.path().contains("session.lock")));
        assertTrue(machine.entries(first.id()).stream().noneMatch(e -> e.path().contains("lib.jar")));

        TimeMachine.Snapshot second = machine.backup(settings, List.of(), "auto", null);
        assertEquals(0L, second.storedBytes());
        assertEquals(first.files(), second.files());
        assertEquals(machine.storedSize(), first.storedBytes());
    }

    @Test
    void changesAndRestorePutTheOldFileBack() throws Exception {
        Path world = dir.resolve("saves").resolve("World");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "v1");
        TimeMachine machine = new TimeMachine(dir);
        TimeMachine.Settings settings = new TimeMachine.Settings();
        TimeMachine.Snapshot first = machine.backup(settings, List.of(), "now", null);

        Files.writeString(world.resolve("level.dat"), "v2");
        Files.writeString(world.resolve("extra.txt"), "new");
        TimeMachine.Snapshot second = machine.backup(settings, List.of(), "now", null);
        Files.delete(world.resolve("extra.txt"));

        List<TimeMachine.Entry> changes = machine.changes(second.id());
        assertTrue(changes.stream().anyMatch(e -> e.path().endsWith("level.dat") && "modified".equals(e.change())));
        assertTrue(changes.stream().anyMatch(e -> e.path().endsWith("extra.txt") && "added".equals(e.change())));

        TimeMachine.Entry level = machine.entries(first.id()).stream()
                .filter(e -> e.path().endsWith("level.dat")).findFirst().orElseThrow();
        int written = machine.restore(first.id(), List.of(level), false, null);
        assertEquals(1, written);
        assertEquals("v1", Files.readString(world.resolve("level.dat")));
        assertTrue(machine.listSnapshots().stream().anyMatch(s -> "before-restore".equals(s.label())));
        TimeMachine.Snapshot safety = machine.listSnapshots().stream()
                .filter(s -> "before-restore".equals(s.label())).findFirst().orElseThrow();
        assertEquals("v2", blobText(machine, safety));
    }

    @Test
    void restoreCanRemoveFilesAddedAfterTheSnapshot() throws Exception {
        Path world = dir.resolve("saves").resolve("World");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "keep");
        TimeMachine machine = new TimeMachine(dir);
        TimeMachine.Snapshot snap = machine.backup(new TimeMachine.Settings(), List.of(), "now", null);
        Files.writeString(world.resolve("later.txt"), "later");
        machine.restore(snap.id(), null, true, null);
        assertFalse(Files.exists(world.resolve("later.txt")));
        assertEquals("keep", Files.readString(world.resolve("level.dat")));
        assertTrue(machine.listSnapshots().stream().anyMatch(s -> "before-restore".equals(s.label())));
    }

    @Test
    void restoreRejectsPathEscape(@TempDir Path elsewhere) throws Exception {
        TimeMachine machine = new TimeMachine(dir);
        Path snap = machine.storeDir().resolve("snapshots").resolve("evil");
        Files.createDirectories(snap);
        String manifest = """
                {"id":"evil","createdAt":1,"label":"now","files":[
                  {"scope":"worlds","root":"%s","path":"../../escape.txt","size":1,"sha256":"%s","tree":true}
                ]}
                """.formatted(dir.toAbsolutePath(), "ab".repeat(32));
        Files.writeString(snap.resolve("manifest.json"), manifest);
        Files.writeString(snap.resolve("meta.json"),
                "{\"id\":\"evil\",\"createdAt\":1,\"label\":\"now\",\"files\":1,\"logicalBytes\":1,\"storedBytes\":1,\"skipped\":0}");
        assertThrows(IllegalArgumentException.class, () -> machine.restore("evil", null, false, null));
        assertFalse(Files.exists(elsewhere.resolve("escape.txt")));
        assertFalse(Files.exists(dir.getParent().resolve("escape.txt")));
    }

    @Test
    void deleteDropsUnusedBytesAndKeepLimitDropsOldest() throws Exception {
        Path world = dir.resolve("saves").resolve("World");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "one");
        TimeMachine machine = new TimeMachine(dir);
        TimeMachine.Settings settings = new TimeMachine.Settings();
        settings.keep = 1;
        TimeMachine.Snapshot first = machine.backup(settings, List.of(), "now", null);
        long used = machine.storedSize();
        assertTrue(used > 0L);
        Files.writeString(world.resolve("level.dat"), "two");
        machine.backup(settings, List.of(), "now", null);
        assertEquals(1, machine.listSnapshots().size());
        assertTrue(machine.entries(first.id()).isEmpty());
        assertTrue(machine.storedSize() > 0L);
        machine.deleteSnapshot(machine.listSnapshots().get(0).id());
        assertEquals(0L, machine.storedSize());
        assertTrue(machine.listSnapshots().isEmpty());
    }

    @Test
    void manualScheduleIsNotDueAndDailyIs() throws Exception {
        Path world = dir.resolve("saves").resolve("World");
        Files.createDirectories(world);
        Files.writeString(world.resolve("level.dat"), "one");
        TimeMachine machine = new TimeMachine(dir);
        TimeMachine.Settings settings = new TimeMachine.Settings();
        assertFalse(machine.due(settings, System.currentTimeMillis()));
        machine.backup(settings, List.of(), "now", null);
        settings.schedule = "daily";
        assertFalse(machine.due(settings, System.currentTimeMillis()));
        assertTrue(machine.due(settings, System.currentTimeMillis() + 86_400_000L));
        settings.schedule = "manual";
        machine.saveSettings(settings);
        TimeMachine.Settings loaded = machine.loadSettings();
        assertEquals("manual", loaded.schedule);
        assertFalse(loaded.launcher);
    }

    private static String blobText(TimeMachine machine, TimeMachine.Snapshot snapshot) throws Exception {
        TimeMachine.Entry entry = machine.entries(snapshot.id()).get(0);
        Path blob = machine.storeDir().resolve("blobs").resolve(entry.sha256().substring(0, 2)).resolve(entry.sha256());
        return Files.readString(blob);
    }
}
