package com.pmcl.core.preferences;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PreferencesShutdownTest {

    @Test
    void secondShutdownDoesNotRewrite() throws Exception {
        Path dir = Files.createTempDirectory("pmcl-prefs-shutdown");
        Path file = dir.resolve("preferences.json");
        Preferences prefs = new Preferences(file);
        prefs.setUseDarkTheme(true);
        prefs.shutdown();
        prefs.shutdown();
        String text = Files.readString(file);
        assertTrue(text.contains("useDarkTheme"));
        long first = Files.size(file);
        prefs.shutdown();
        assertTrue(Files.size(file) == first);
    }
}
