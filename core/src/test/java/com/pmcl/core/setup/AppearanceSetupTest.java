package com.pmcl.core.setup;

import com.pmcl.core.preferences.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppearanceSetupTest {

    @TempDir
    Path tmp;

    @Test
    void applyChangesOnlyAppearance() {
        Preferences prefs = new Preferences(tmp.resolve("preferences.json"));
        prefs.setMinMemoryMb(2048);
        prefs.setCustomAccentColor(0xFFFF0000);
        prefs.setDynamicColor(true);
        prefs.setCustomThemePackId("pack");
        prefs.setThemePreset("ocean");

        new AppearanceSetup.Selection()
                .theme("sakura")
                .dark(true)
                .followSystem(false)
                .colorMode("soft")
                .scale(1.25)
                .font("PingFang SC")
                .glass(false)
                .material(true)
                .language("en_US")
                .apply(prefs);
        prefs.save();

        assertEquals("sakura", prefs.getThemePreset());
        assertTrue(prefs.isUseDarkTheme());
        assertFalse(prefs.isFollowSystemTheme());
        assertEquals("soft", prefs.getColorMode());
        assertEquals(1.25f, prefs.getUiScale(), 0.001f);
        assertEquals("PingFang SC", prefs.getLauncherFont());
        assertFalse(prefs.isGlassTheme());
        assertTrue(prefs.isMaterialTheme());
        assertEquals("en_US", prefs.getLanguage());
        assertEquals(-1, prefs.getCustomAccentColor());
        assertFalse(prefs.isDynamicColor());
        assertEquals("", prefs.getCustomThemePackId());
        assertEquals(2048, prefs.getMinMemoryMb());
    }

    @Test
    void unknownThemeDoesNotChangePreset() {
        Preferences prefs = new Preferences(tmp.resolve("preferences.json"));
        prefs.setThemePreset("ocean");
        AppearanceSetup.Selection bad = new AppearanceSetup.Selection().theme("foo");
        assertThrows(IllegalArgumentException.class, () -> bad.apply(prefs));
        assertEquals("ocean", prefs.getThemePreset());
    }

    @Test
    void partialFontKeepsTheme() {
        Preferences prefs = new Preferences(tmp.resolve("preferences.json"));
        prefs.setThemePreset("ocean");
        prefs.setCustomAccentColor(0xFF112233);
        prefs.setDynamicColor(true);
        AppearanceSetup.parse("{\"font\":\"B\"}").apply(prefs);
        assertEquals("ocean", prefs.getThemePreset());
        assertEquals("B", prefs.getLauncherFont());
        assertEquals(0xFF112233, prefs.getCustomAccentColor());
        assertTrue(prefs.isDynamicColor());
    }

    @Test
    void consumeDeletesFileAndKeepsOtherSettings() throws Exception {
        Preferences prefs = new Preferences(tmp.resolve("preferences.json"));
        prefs.setMinMemoryMb(2048);
        prefs.setThemePreset("ocean");
        Path file = tmp.resolve(AppearanceSetup.FILE_NAME);
        Files.writeString(file, new AppearanceSetup.Selection().theme("midnight").font("").toJson(), StandardCharsets.UTF_8);

        assertTrue(AppearanceSetup.consume(prefs, file));
        assertFalse(Files.exists(file));
        assertEquals("midnight", prefs.getThemePreset());
        assertEquals("", prefs.getLauncherFont());
        assertEquals(2048, prefs.getMinMemoryMb());
    }

    @Test
    void badFileIsRenamed() throws Exception {
        Preferences prefs = new Preferences(tmp.resolve("preferences.json"));
        prefs.setThemePreset("ocean");
        Path file = tmp.resolve(AppearanceSetup.FILE_NAME);
        Files.writeString(file, "{not json", StandardCharsets.UTF_8);
        assertFalse(AppearanceSetup.consume(prefs, file));
        assertFalse(Files.exists(file));
        assertTrue(Files.exists(tmp.resolve(AppearanceSetup.FILE_NAME + ".bad")));
        assertEquals("ocean", prefs.getThemePreset());
    }
}
