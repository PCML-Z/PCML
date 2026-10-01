package com.pmcl.core.gamecontent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void scansOnlyVideoFilesAndDeletesScannedRecording() throws Exception {
        Path dir = tempDir.resolve("recordings");
        Files.createDirectories(dir);
        Files.write(dir.resolve("clip.mp4"), new byte[]{1, 2, 3});
        Files.writeString(dir.resolve("notes.txt"), "not video");

        RecordingManager manager = new RecordingManager(tempDir);
        List<RecordingManager.Recording> recordings = manager.list(dir, "Test");

        assertEquals(1, recordings.size());
        assertEquals("clip.mp4", recordings.getFirst().getName());
        assertEquals("Test", recordings.getFirst().getSource());

        manager.delete(recordings.getFirst());
        assertFalse(Files.exists(dir.resolve("clip.mp4")));
        assertTrue(Files.exists(dir.resolve("notes.txt")));
    }

    @Test
    void importsWithUniqueNameAndRejectsUnsupportedFiles() throws Exception {
        Path source = tempDir.resolve("capture.webm");
        Files.write(source, new byte[]{4, 5, 6});
        RecordingManager manager = new RecordingManager(tempDir.resolve("pmcl"));

        RecordingManager.Recording first = manager.importRecording(source);
        RecordingManager.Recording second = manager.importRecording(source);

        assertEquals("capture.webm", first.getName());
        assertEquals("capture_1.webm", second.getName());
        assertTrue(Files.isRegularFile(first.getPath()));
        assertTrue(Files.isRegularFile(second.getPath()));

        Path text = tempDir.resolve("bad.txt");
        Files.writeString(text, "bad");
        assertThrows(Exception.class, () -> manager.importRecording(text));
    }
}
