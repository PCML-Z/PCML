package com.pmcl.core.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacGameModeTest {

    @Test
    void plistDeclaresGameMode() {
        String plist = MacGameMode.infoPlist();
        assertTrue(plist.contains("<key>LSSupportsGameMode</key>"));
        assertTrue(plist.contains("<key>LSApplicationCategoryType</key>"));
        assertTrue(plist.contains("public.app-category.games"));
        assertTrue(plist.contains("<key>GCSupportsControllerUserInteraction</key>"));
        assertTrue(plist.contains("<string>" + MacGameMode.EXECUTABLE + "</string>"));
        assertTrue(plist.contains("com.pmcl.game"));
    }

    @Test
    void wrapPrependsTheBundledExecutable(@TempDir Path home) throws Exception {
        List<String> wrapped = MacGameMode.wrap(List.of("/usr/bin/java", "-jar", "client.jar"), home);
        assertEquals(4, wrapped.size());
        assertTrue(wrapped.get(0).endsWith("PMCLGame.app/Contents/MacOS/" + MacGameMode.EXECUTABLE));
        assertEquals("/usr/bin/java", wrapped.get(1));
        assertEquals("-jar", wrapped.get(2));
        Path exe = Path.of(wrapped.get(0));
        assertTrue(Files.isRegularFile(exe));
        assertTrue(Files.isExecutable(exe));
        assertTrue(Files.readString(exe.getParent().getParent().resolve("Info.plist"))
                .contains("LSSupportsGameMode"));
        List<String> again = MacGameMode.wrap(List.of("/usr/bin/java"), home);
        assertEquals(wrapped.get(0), again.get(0));
    }
}
