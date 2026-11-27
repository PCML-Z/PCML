package com.pmcl.core.version;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalGameStoreTest {

    @TempDir
    Path temp;

    @Test
    void measuresVersionAndItsInstancesWithoutSharedFiles() throws Exception {
        Path work = temp.resolve("pmcl");
        write(work.resolve("versions/1.20.4/client.dat"), 100);
        write(work.resolve("versions/1.21/keep.dat"), 30);
        write(work.resolve("versions/1.20.4.staging/partial.dat"), 80);
        write(work.resolve("libraries/shared.dat"), 200);
        write(work.resolve("saves/world.dat"), 40);
        write(work.resolve("instances/1.20.4/saves/a.dat"), 50);
        write(work.resolve("instances/custom-uuid/mods/b.dat"), 20);
        String customJson = "{\"instanceId\":\"custom-uuid\",\"name\":\"生存\",\"baseVersionId\":\"1.20.4\"}";
        Files.writeString(work.resolve("instances/custom-uuid/instance.json"), customJson);
        write(work.resolve("instances/orphan/saves/c.dat"), 10);
        String orphanJson = "{\"instanceId\":\"orphan\",\"name\":\"旧存档\",\"baseVersionId\":\"1.16.5\"}";
        Files.writeString(work.resolve("instances/orphan/instance.json"), orphanJson);
        Path official = temp.resolve("official");
        write(official.resolve("versions/1.8.9/old.dat"), 40);

        List<LocalGameStore.GameSpace> rows = LocalGameStore.measure(work, List.of(
                work.resolve("versions"), official.resolve("versions"), work));
        LocalGameStore.GameSpace modern = find(rows, "1.20.4");
        assertEquals(100L, modern.getVersionBytes());
        assertEquals(70L + customJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, modern.getDataBytes());
        assertEquals(2, modern.getDataDirCount());
        assertFalse(modern.isExternal());
        assertEquals(30L, find(rows, "1.21").totalBytes());
        LocalGameStore.GameSpace old = find(rows, "1.8.9");
        assertTrue(old.isExternal());
        assertEquals(40L, old.totalBytes());
        LocalGameStore.GameSpace orphan = rows.stream()
                .filter(LocalGameStore.GameSpace::isInstanceOnly)
                .findFirst()
                .orElseThrow();
        assertEquals("旧存档", orphan.getTitle());
        assertEquals(10L + orphanJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, orphan.totalBytes());
        assertTrue(rows.stream().noneMatch(row -> row.getId().contains("staging")));
        assertTrue(rows.stream().noneMatch(row -> "libraries".equals(row.getId()) || "saves".equals(row.getId())));
    }

    @Test
    void uninstallRemovesOnlyThatGame() throws Exception {
        Path work = temp.resolve("pmcl");
        write(work.resolve("versions/1.20.4/client.dat"), 100);
        write(work.resolve("versions/1.21/keep.dat"), 30);
        write(work.resolve("libraries/shared.dat"), 200);
        write(work.resolve("saves/world.dat"), 40);
        write(work.resolve("instances/1.20.4/saves/a.dat"), 50);
        write(work.resolve("instances/custom-uuid/mods/b.dat"), 20);
        Files.writeString(work.resolve("instances/custom-uuid/instance.json"),
                "{\"name\":\"生存\",\"baseVersionId\":\"1.20.4\"}");
        write(work.resolve("instances/orphan/saves/c.dat"), 10);
        Files.writeString(work.resolve("instances/orphan/instance.json"),
                "{\"name\":\"旧存档\",\"baseVersionId\":\"1.16.5\"}");
        Path official = temp.resolve("official");
        write(official.resolve("versions/1.8.9/old.dat"), 40);
        List<Path> roots = List.of(work.resolve("versions"), official.resolve("versions"));

        LocalGameStore.uninstallVersion(work, roots, "1.20.4");

        assertFalse(Files.exists(work.resolve("versions/1.20.4")));
        assertFalse(Files.exists(work.resolve("instances/1.20.4")));
        assertFalse(Files.exists(work.resolve("instances/custom-uuid")));
        assertTrue(Files.exists(work.resolve("versions/1.21/keep.dat")));
        assertTrue(Files.exists(work.resolve("libraries/shared.dat")));
        assertTrue(Files.exists(work.resolve("saves/world.dat")));
        assertTrue(Files.exists(work.resolve("instances/orphan/saves/c.dat")));
        assertTrue(Files.exists(official.resolve("versions/1.8.9/old.dat")));

        LocalGameStore.uninstallInstance(work, "orphan");
        assertFalse(Files.exists(work.resolve("instances/orphan")));
        assertTrue(Files.exists(work.resolve("versions/1.21/keep.dat")));

        assertThrows(IllegalArgumentException.class,
                () -> LocalGameStore.uninstallVersion(work, roots, ".."));
        assertThrows(java.io.IOException.class,
                () -> LocalGameStore.uninstallVersion(work, roots, "missing"));
    }

    private static LocalGameStore.GameSpace find(List<LocalGameStore.GameSpace> rows, String id) {
        return rows.stream().filter(row -> id.equals(row.getId())).findFirst().orElseThrow();
    }

    private static void write(Path file, int size) throws Exception {
        Files.createDirectories(file.getParent());
        byte[] bytes = new byte[size];
        Files.write(file, bytes);
    }
}
