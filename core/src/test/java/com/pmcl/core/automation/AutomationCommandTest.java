package com.pmcl.core.automation;

import com.pmcl.core.i18n.I18n;
import com.pmcl.core.preferences.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomationCommandTest {

    @TempDir
    Path tmp;

    @Test
    void javaSourceRunsInASeparateProcess() throws Exception {
        I18n.setLocale(I18n.ZH_CN);
        AutomationCommand command = new AutomationCommand(
                "java1", "java", AutomationCommand.JAVA, AutomationCommand.TRIGGER_MANUAL, true,
                "public class Main { public static void main(String[] args) { System.out.print(\"ok\"); } }",
                "", List.of());
        AutomationRunner.Result result = AutomationRunner.run(
                command, tmp, AutomationCommand.TRIGGER_MANUAL, "1.21", "", null);
        assertEquals(0, result.exitCode, result.summary());
        assertTrue(result.output.contains("ok"), result.summary());
    }

    @Test
    void kotlinSourceRunsInASeparateProcess() throws Exception {
        I18n.setLocale(I18n.ZH_CN);
        AutomationCommand command = new AutomationCommand(
                "kt1", "kotlin", AutomationCommand.KOTLIN, AutomationCommand.TRIGGER_MANUAL, true,
                "fun main() { println(\"kt-ok\") }",
                "", List.of());
        AutomationRunner.Result result = AutomationRunner.run(
                command, tmp, AutomationCommand.TRIGGER_MANUAL, "", "", null);
        assertEquals(0, result.exitCode, result.summary());
        assertTrue(result.output.contains("kt-ok"), result.summary());
    }

    @Test
    void compiledNativeExecutableRunsWithoutAShell() throws Exception {
        Path script = tmp.resolve("echo-ok");
        Files.writeString(script, "#!/bin/sh\necho native-ok\n");
        try {
            Files.setPosixFilePermissions(script, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException e) {
            return;
        }
        AutomationCommand command = new AutomationCommand(
                "c1", "native", AutomationCommand.C, AutomationCommand.TRIGGER_MANUAL, true,
                "not compiled by the launcher", script.toAbsolutePath().toString(), List.of());
        AutomationRunner.Result result = AutomationRunner.run(
                command, tmp, AutomationCommand.TRIGGER_MANUAL, "", "", null);
        assertEquals(0, result.exitCode, result.summary());
        assertTrue(result.output.contains("native-ok"), result.summary());
    }

    @Test
    void sanitizeDropsBadEntriesAndPackageIsRejectedAtRun() throws Exception {
        I18n.setLocale(I18n.ZH_CN);
        assertNull(AutomationCommand.sanitize("bad id", "n", "C", "MANUAL", true, "", "relative-bin", List.of()));
        assertNull(AutomationCommand.sanitize("ok", "n", "NOPE", "MANUAL", true, "", "", List.of()));
        AutomationCommand stored = AutomationCommand.sanitize(
                "pkg", "packaged", "JAVA", "MANUAL", true,
                "package evil;\npublic class Main { public static void main(String[] args) {} }",
                "", List.of("keep", ""));
        assertNotNull(stored);
        assertTrue(stored.getSource().contains("package evil"));
        assertEquals(List.of("keep"), stored.getArgs());
        AutomationRunner.Result result = AutomationRunner.run(stored, tmp, "MANUAL", "", "", null);
        assertEquals(-1, result.exitCode);
        assertTrue(result.output.contains("package"), result.output);
    }

    @Test
    void preferencesRoundTripAndOnlyTheMatchingTriggerRuns() throws Exception {
        I18n.setLocale(I18n.ZH_CN);
        Preferences pref = new Preferences(tmp.resolve("p.json"));
        Preferences reloaded = null;
        try {
            pref.setAutomationCommands(List.of(
                    new AutomationCommand(
                            "round1", "round", "java", "before_game", true,
                            "public class Main { public static void main(String[] args) { System.out.print(System.getenv(\"PMCL_EVENT\")); } }",
                            "/should/be/cleared", List.of()),
                    new AutomationCommand(
                            "round2", "off", "JAVA", "BEFORE_GAME", false,
                            "public class Main { public static void main(String[] args) { System.out.print(\"nope\"); } }",
                            "", List.of())
            ));
            pref.save();
            reloaded = new Preferences(tmp.resolve("p.json"));
            List<AutomationCommand> stored = reloaded.getAutomationCommands();
            assertEquals(2, stored.size());
            assertEquals(AutomationCommand.JAVA, stored.get(0).getLanguage());
            assertEquals(AutomationCommand.TRIGGER_BEFORE_GAME, stored.get(0).getTrigger());
            assertEquals("", stored.get(0).getExecutable());
            StringBuilder manual = new StringBuilder();
            AutomationRunner.runTrigger(reloaded, tmp, AutomationCommand.TRIGGER_MANUAL, "", "", null, manual::append);
            assertEquals("", manual.toString());
            StringBuilder before = new StringBuilder();
            AutomationRunner.runTrigger(
                    reloaded, tmp, AutomationCommand.TRIGGER_BEFORE_GAME, "ver", tmp.toString(), null, before::append);
            assertTrue(before.toString().contains("BEFORE_GAME"), before.toString());
            assertTrue(!before.toString().contains("nope"), before.toString());
        } finally {
            pref.shutdown();
            if (reloaded != null) reloaded.shutdown();
        }
    }
}
