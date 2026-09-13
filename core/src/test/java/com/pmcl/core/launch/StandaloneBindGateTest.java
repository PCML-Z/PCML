package com.pmcl.core.launch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StandaloneBindGateTest {

    @TempDir
    Path tmp;

    @AfterEach
    void clearOverrides() {
        System.clearProperty("pmcl.bind.launcher");
        System.clearProperty("pmcl.bind.device");
        System.clearProperty("pmcl.bind.known");
    }

    @Test
    void verifyFailsWhenHostOrLauncherMissingOrDeviceChanges() throws Exception {
        System.setProperty("pmcl.bind.known", "false");
        Path work = tmp.resolve("pmcl");
        Path launcher = tmp.resolve("PMCL.app");
        Files.createDirectories(work);
        Files.createDirectories(launcher);
        System.setProperty("pmcl.bind.launcher", launcher.toString());
        System.setProperty("pmcl.bind.device", "machine-a");

        StandaloneBindGate.HostSecret host = StandaloneBindGate.ensureHostSecret(work);
        Path contents = tmp.resolve("Game.app").resolve("Contents");
        Path resources = contents.resolve("Resources");
        Files.createDirectories(resources);
        StandaloneBindGate.writeTicket(
                resources.resolve(StandaloneBindGate.TICKET_FILE_NAME),
                StandaloneBindGate.hostFile(work),
                launcher,
                host,
                StandaloneBindGate.deviceId());
        StandaloneBindGate.verify(contents);

        Path hostFile = StandaloneBindGate.hostFile(work);
        Path bak = tmp.resolve("host.bak");
        Files.move(hostFile, bak);
        assertThrows(Exception.class, () -> StandaloneBindGate.verify(contents));
        Files.move(bak, hostFile);
        StandaloneBindGate.verify(contents);

        Files.deleteIfExists(launcher);
        assertFalse(Files.exists(launcher));
        assertThrows(Exception.class, () -> StandaloneBindGate.verify(contents));
        Files.createDirectories(launcher);
        StandaloneBindGate.verify(contents);

        System.setProperty("pmcl.bind.device", "machine-b");
        assertThrows(Exception.class, () -> StandaloneBindGate.verify(contents));
        System.setProperty("pmcl.bind.device", "machine-a");
        StandaloneBindGate.verify(contents);
        assertTrue(Files.isRegularFile(hostFile));
    }

    @Test
    void verifyAcceptsKnownPmclWhenRecordedLauncherIsGone() throws Exception {
        Path work = tmp.resolve("pmcl");
        Path launcher = tmp.resolve("stale-PMCL.app");
        Files.createDirectories(work);
        Files.createDirectories(launcher);
        System.setProperty("pmcl.bind.launcher", launcher.toString());
        System.setProperty("pmcl.bind.device", "machine-a");

        StandaloneBindGate.HostSecret host = StandaloneBindGate.ensureHostSecret(work);
        Path contents = tmp.resolve("Game.app").resolve("Contents");
        Path resources = contents.resolve("Resources");
        Files.createDirectories(resources);
        StandaloneBindGate.writeTicket(
                resources.resolve(StandaloneBindGate.TICKET_FILE_NAME),
                StandaloneBindGate.hostFile(work),
                launcher,
                host,
                StandaloneBindGate.deviceId());

        Files.deleteIfExists(launcher);
        Path known = Path.of(System.getProperty("user.home"), "Applications", "pmcl.app");
        Files.createDirectories(known);
        StandaloneBindGate.verify(contents);
    }

    @Test
    void writeTicketAcceptsWorkDirWhenNoAppBundle() throws Exception {
        System.setProperty("pmcl.bind.known", "false");
        System.setProperty("pmcl.bind.device", "machine-a");
        Path work = tmp.resolve("pmcl-home");
        Files.createDirectories(work);
        StandaloneBindGate.HostSecret host = StandaloneBindGate.ensureHostSecret(work);
        Path contents = tmp.resolve("Game.app").resolve("Contents");
        Path resources = contents.resolve("Resources");
        Files.createDirectories(resources);
        StandaloneBindGate.writeTicket(
                resources.resolve(StandaloneBindGate.TICKET_FILE_NAME),
                StandaloneBindGate.hostFile(work),
                work,
                host,
                StandaloneBindGate.deviceId());
        StandaloneBindGate.verify(contents);
    }

    @Test
    void gateJarTargetsJava8() throws Exception {
        Path jar = tmp.resolve("bind-gate.jar");
        StandaloneBindGate.writeGateJar(jar);
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry entry = jf.getJarEntry("com/pmcl/core/launch/StandaloneBindGate.class");
            assertTrue(entry != null);
            assertTrue(jf.getJarEntry("com/pmcl/core/launch/StandaloneBindGate$HostSecret.class") != null,
                    "inner class must be packed or game JRE cannot load the gate");
            try (InputStream in = jf.getInputStream(entry)) {
                byte[] hdr = in.readNBytes(8);
                int major = ((hdr[6] & 0xff) << 8) | (hdr[7] & 0xff);
                assertEquals(52, major, "bind gate must run on the game JRE (Java 8+)");
            }
        }
    }
}
