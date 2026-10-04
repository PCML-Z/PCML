package com.pmcl.core.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotUpdateTest {
    private static final HotUpdateManifest.Host MAC = new HotUpdateManifest.Host("macos", "aarch64");

    @Test
    void canonicalPayloadListsFilesInPathOrder() {
        HotUpdateManifest manifest = HotUpdateManifest.verified("1.2.3", "", "sig", List.of(
                entry("app/b.dat", "bb", 2),
                entry("app/a.dat", "aa", 2)
        ));
        String canonical = manifest.canonical();
        assertTrue(canonical.startsWith("PMCL-HOTUPDATE-V1\n1.2.3\n"));
        assertTrue(canonical.indexOf("app/a.dat") < canonical.indexOf("app/b.dat"));
        assertTrue(canonical.contains("app/a.dat\taa\t2\thttps://example.com/a.dat\t\t\n"));
    }

    @Test
    void unsignedOrUnsafeManifestIsRejected() {
        assertThrows(Exception.class, () -> HotUpdateManifest.parse("""
                {"version":"1.0.0","signature":"AAAA","files":[
                  {"path":"app/a.dat","sha256":"%s","size":1,"url":"https://example.com/a.dat"}
                ]}
                """.formatted("ab".repeat(32))));
        assertThrows(Exception.class, () -> HotUpdateManifest.parse("""
                {"version":"1.0.0","signature":"AAAA","files":[
                  {"path":"app/../../etc/passwd","sha256":"%s","size":1,"url":"https://example.com/a.dat"}
                ]}
                """.formatted("ab".repeat(32))));
        assertThrows(Exception.class, () -> HotUpdateManifest.parse("""
                {"version":"1.0.0","signature":"AAAA","files":[
                  {"path":"app/a.dat","sha256":"%s","size":1,"url":"http://example.com/a.dat"}
                ]}
                """.formatted("ab".repeat(32))));
    }

    @Test
    void planDownloadsOnlyChangedFiles(@TempDir Path root) throws Exception {
        Path app = root.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("keep.dat"), "same");
        Files.writeString(app.resolve("old.dat"), "old");
        HotUpdateManifest.FileEntry keep = file("app/keep.dat", Files.readAllBytes(app.resolve("keep.dat")));
        HotUpdateManifest.FileEntry changed = file("app/changed.dat", "new".getBytes(StandardCharsets.UTF_8));
        changed = new HotUpdateManifest.FileEntry(
                changed.path(), changed.sha256(), changed.size(), changed.url(), "windows", "x64");
        HotUpdateManifest.FileEntry missing = file("app/missing.dat", "miss".getBytes(StandardCharsets.UTF_8));
        HotUpdateManifest next = HotUpdateManifest.verified("2", "", "sig", List.of(keep, changed, missing));
        HotUpdateManifest previous = HotUpdateManifest.verified("1", "", "sig", List.of(
                file("app/old.dat", Files.readAllBytes(app.resolve("old.dat"))),
                keep
        ));
        HotUpdatePlanner.Plan plan = HotUpdatePlanner.plan(app, next, previous, MAC);
        assertEquals(List.of("app/missing.dat"), plan.downloads().stream().map(HotUpdateManifest.FileEntry::path).toList());
        assertEquals(List.of("app/old.dat"), plan.deletes());
        assertEquals(missing.size(), plan.downloadBytes());
    }

    @Test
    void startupCheckCatchesDamageAndCanRestoreBackup(@TempDir Path home) throws Exception {
        Path app = home.resolve("install").resolve("app");
        Files.createDirectories(app);
        Path file = app.resolve("ok.dat");
        Files.writeString(file, "good");
        assertEquals(AppResourceCheck.Status.OK, AppResourceCheck.check(home, app).status());
        assertEquals(AppResourceCheck.Status.OK, AppResourceCheck.check(home, app).status());

        Files.writeString(file, "bad!");
        AppResourceCheck.Report broken = AppResourceCheck.check(home, app);
        assertEquals(AppResourceCheck.Status.BROKEN, broken.status());
        assertTrue(broken.detail().contains("app/ok.dat"));

        Path backup = AppResourceCheck.backupFile(home, "app/ok.dat");
        Files.createDirectories(backup.getParent());
        Files.writeString(backup, "good");
        assertEquals(AppResourceCheck.Status.RESTORE, AppResourceCheck.check(home, app).status());
    }

    @Test
    void tamperedInstalledManifestIsNotUsedForRepair(@TempDir Path home) throws Exception {
        Path app = home.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("ok.dat"), "good");
        Files.createDirectories(AppResourceCheck.installedManifest(home).getParent());
        Files.writeString(AppResourceCheck.installedManifest(home), "{");
        AppResourceCheck.Report report = AppResourceCheck.check(home, app);
        assertEquals(AppResourceCheck.Status.BROKEN, report.status());
        assertFalse(report.detail().isBlank());
    }

    @Test
    void corruptJarFailsEvenWhenTheDeclaredHashMatches(@TempDir Path app) throws Exception {
        Path jar = app.resolve("bad.jar");
        Files.writeString(jar, "not-a-jar");
        byte[] bytes = Files.readAllBytes(jar);
        HotUpdateManifest.FileEntry entry = file("app/bad.jar", bytes);
        List<String> broken = AppResourceCheck.mismatches(app, List.of(entry));
        assertEquals(List.of("app/bad.jar"), broken);

        Path good = app.resolve("good.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(good))) {
            zip.putNextEntry(new ZipEntry("a.txt"));
            zip.write("hi".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        HotUpdateManifest.FileEntry ok = file("app/good.jar", Files.readAllBytes(good));
        assertTrue(AppResourceCheck.mismatches(app, List.of(ok)).isEmpty());
    }

    @Test
    void repairStopsAfterOneAutomaticAttempt(@TempDir Path home) throws Exception {
        assertEquals(1, AppResourceCheck.RepairAttempts.next(home, "1.0"));
        assertEquals(2, AppResourceCheck.RepairAttempts.next(home, "1.0"));
        AppResourceCheck.RepairAttempts.clear(home);
        assertEquals(1, AppResourceCheck.RepairAttempts.next(home, "1.0"));
    }

    @Test
    void installScriptWaitsAndBacksUpBeforeCopy() throws Exception {
        String[] copy = {"/tmp/new.dat", "/Applications/PMCL.app/Contents/app/a.dat", "/tmp/backup/a.dat"};
        List<String[]> copies = java.util.Collections.singletonList(copy);
        String script = HotUpdateInstaller.renderUnix(42, true, copies, List.of(), "", "", "/Applications/PMCL.app/Contents/MacOS/pmcl", true);
        assertTrue(script.contains("kill -0 42"));
        assertTrue(script.contains("/Applications/PMCL.app/Contents/app/a.dat"));
        assertTrue(script.contains("/tmp/backup/a.dat"));
        assertTrue(script.contains("osascript"));
        String direct = HotUpdateInstaller.renderUnix(7, false, copies, List.of(), "", "", "", false);
        assertFalse(direct.contains("/tmp/backup/a.dat"));
        String windows = HotUpdateInstaller.renderWindows(9, copies, List.of("/Applications/PMCL.app/Contents/app/old.dat"),
                "", "", "C:\\PMCL\\pmcl.exe", true);
        assertTrue(windows.contains("PID eq 9"));
        assertTrue(windows.contains("old.dat"));
    }

    @Test
    void missingInstallDirectorySkipsTheCheck(@TempDir Path home) throws Exception {
        assertEquals(AppResourceCheck.Status.SKIPPED, AppResourceCheck.check(home, null).status());
    }

    private static HotUpdateManifest.FileEntry entry(String path, String sha, long size) {
        return new HotUpdateManifest.FileEntry(path, sha, size, "https://example.com/" + path.substring(4), "", "");
    }

    private static HotUpdateManifest.FileEntry file(String path, byte[] bytes) throws Exception {
        return new HotUpdateManifest.FileEntry(path, sha256(bytes), bytes.length,
                "https://example.com/" + path.substring("app/".length()), "", "");
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }
}
