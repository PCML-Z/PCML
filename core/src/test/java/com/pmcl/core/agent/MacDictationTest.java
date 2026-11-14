package com.pmcl.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MacDictationTest {

    @Test
    void joinKeepsASpaceBetweenTheCursorAndThePhrase() {
        assertEquals("打开 存档", MacDictation.join("打开", "存档", ""));
        assertEquals("打开 存档", MacDictation.join("打开 ", "存档", ""));
        assertEquals("请 打开 存档", MacDictation.join("请 ", "打开", " 存档"));
        assertEquals("存档", MacDictation.join("", "", "存档"));
        assertEquals(5, MacDictation.caret("打开", "存档"));
        assertEquals(2, MacDictation.caret("打开", ""));
    }

    @Test
    void parseReadsOneEventPerLine() {
        assertEquals(new MacDictation.Event("partial", "你好"), MacDictation.parse("{\"partial\":\"你好\"}"));
        assertEquals(new MacDictation.Event("final", "你好"), MacDictation.parse("{\"final\":\"你好\"}"));
        assertEquals(new MacDictation.Event("error", "speech_denied"), MacDictation.parse("{\"error\":\"speech_denied\"}"));
        assertEquals(new MacDictation.Event("status", "listening"), MacDictation.parse("{\"status\":\"listening\"}"));
        assertEquals(new MacDictation.Event("", ""), MacDictation.parse("not json"));
        assertEquals(new MacDictation.Event("", ""), MacDictation.parse(""));
    }

    @Test
    void plistAsksForTheMicrophoneAndSpeech() {
        String plist = MacDictation.infoPlist();
        assertTrue(plist.contains("NSMicrophoneUsageDescription"));
        assertTrue(plist.contains("NSSpeechRecognitionUsageDescription"));
        assertTrue(plist.contains("com.pmcl.dictate"));
        assertTrue(plist.contains("LSUIElement"));
    }

    @Test
    void helperSourceStaysOnDevice() throws Exception {
        try (var in = MacDictation.class.getResourceAsStream("/native/macos/PMCLDictate.swift")) {
            assertNotNull(in);
            String source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(source.contains("requiresOnDeviceRecognition"));
            assertTrue(source.contains("\"check\""));
            assertTrue(source.contains("127.0.0.1"));
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void helperExitsWithoutOpeningTheMicrophone(@TempDir Path home) throws Exception {
        Process finder = new ProcessBuilder("xcrun", "--find", "swiftc").redirectErrorStream(true).start();
        finder.getInputStream().readAllBytes();
        assumeTrue(finder.waitFor() == 0, "swiftc 不在这台电脑上");
        Path app = MacDictation.install(home);
        Path exe = app.resolve("Contents/MacOS/PMCLDictate");
        assertTrue(Files.isExecutable(exe));
        String plist = Files.readString(app.resolve("Contents/Info.plist"));
        assertTrue(plist.contains("NSSpeechRecognitionUsageDescription"));
        Process check = new ProcessBuilder(exe.toString(), "check").redirectErrorStream(true).start();
        String out = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, check.waitFor());
        assertTrue(out.contains("\"ok\":true"));
    }
}
