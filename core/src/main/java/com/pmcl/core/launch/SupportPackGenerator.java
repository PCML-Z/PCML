package com.pmcl.core.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把崩溃现场打包成 zip：崩溃报告、latest.log、模组列表、系统信息。
 * 不含账号令牌或完整偏好文件。
 */
public final class SupportPackGenerator {

    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final int MAX_CRASH_REPORTS = 5;
    private static final int MAX_LOG_CHARS = 400_000;

    private SupportPackGenerator() {}

    public static void write(
            Path zipPath,
            String versionId,
            Path workDir,
            Path gameDir,
            List<String> recentLogs,
            String launcherLog,
            String extraInfo
    ) throws IOException {
        if (zipPath == null) throw new IllegalArgumentException("zipPath");
        Path parent = zipPath.getParent();
        if (parent != null) Files.createDirectories(parent);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            putText(zos, "info.txt", buildInfo(versionId, workDir, gameDir, extraInfo));
            putText(zos, "logs/game-recent.log", joinTruncated(recentLogs));
            if (launcherLog != null && !launcherLog.isEmpty()) {
                putText(zos, "logs/launcher.log", truncate(launcherLog, MAX_LOG_CHARS));
            }
            copyIfExists(zos, "logs/latest.log", resolve(gameDir, "logs", "latest.log"));
            copyIfExists(zos, "logs/work-latest.log", resolve(workDir, "logs", "latest.log"));
            putText(zos, "mods.txt", listMods(gameDir));
            copyCrashReports(zos, gameDir);
            if (gameDir == null || workDir == null || !gameDir.equals(workDir)) {
                copyCrashReports(zos, workDir);
            }
            Path versionJson = findVersionJson(workDir, versionId);
            copyIfExists(zos, "version.json", versionJson);
        }
    }

    private static String buildInfo(String versionId, Path workDir, Path gameDir, String extraInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("PMCL support pack\n");
        sb.append("time=").append(Instant.now()).append('\n');
        sb.append("versionId=").append(nullToEmpty(versionId)).append('\n');
        sb.append("os.name=").append(sys("os.name")).append('\n');
        sb.append("os.arch=").append(sys("os.arch")).append('\n');
        sb.append("os.version=").append(sys("os.version")).append('\n');
        sb.append("java.version=").append(sys("java.version")).append('\n');
        sb.append("java.vendor=").append(sys("java.vendor")).append('\n');
        sb.append("workDir=").append(workDir != null ? workDir : "").append('\n');
        sb.append("gameDir=").append(gameDir != null ? gameDir : "").append('\n');
        if (extraInfo != null && !extraInfo.isBlank()) {
            sb.append('\n').append(extraInfo).append('\n');
        }
        return sb.toString();
    }

    private static void copyCrashReports(ZipOutputStream zos, Path root) throws IOException {
        if (root == null) return;
        Path dir = root.resolve("crash-reports");
        if (!Files.isDirectory(dir)) return;
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path p : stream) files.add(p);
        }
        files.sort(Comparator.comparingLong((Path p) -> {
            try { return Files.getLastModifiedTime(p).toMillis(); }
            catch (IOException e) { return 0L; }
        }).reversed());
        int n = Math.min(MAX_CRASH_REPORTS, files.size());
        for (int i = 0; i < n; i++) {
            Path p = files.get(i);
            String name = "crash-reports/" + sanitizeName(p.getFileName().toString());
            copyIfExists(zos, name, p);
        }
    }

    private static String listMods(Path gameDir) {
        if (gameDir == null) return "(no gameDir)\n";
        Path mods = gameDir.resolve("mods");
        if (!Files.isDirectory(mods)) return "(no mods dir)\n";
        StringBuilder sb = new StringBuilder();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods)) {
            List<String> names = new ArrayList<>();
            for (Path p : stream) {
                String n = p.getFileName().toString();
                if (n.endsWith(".jar") || n.endsWith(".jar.disabled")) names.add(n);
            }
            names.sort(String::compareToIgnoreCase);
            for (String n : names) sb.append(n).append('\n');
        } catch (IOException e) {
            return "(failed to list mods: " + e.getMessage() + ")\n";
        }
        if (sb.length() == 0) return "(empty)\n";
        return sb.toString();
    }

    private static Path findVersionJson(Path workDir, String versionId) {
        if (workDir == null || versionId == null || versionId.isEmpty()) return null;
        if (versionId.contains("..") || versionId.indexOf('/') >= 0 || versionId.indexOf('\\') >= 0) {
            return null;
        }
        Path p = workDir.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        return Files.isRegularFile(p) ? p : null;
    }

    private static Path resolve(Path root, String... parts) {
        if (root == null) return null;
        Path p = root;
        for (String part : parts) p = p.resolve(part);
        return p;
    }

    private static void copyIfExists(ZipOutputStream zos, String entryName, Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return;
        zos.putNextEntry(new ZipEntry(entryName));
        long size = Files.size(file);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            long remaining = Math.min(size, MAX_FILE_BYTES);
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                zos.write(buf, 0, n);
                remaining -= n;
            }
        }
        zos.closeEntry();
    }

    private static void putText(ZipOutputStream zos, String entryName, String text) throws IOException {
        if (text == null) text = "";
        zos.putNextEntry(new ZipEntry(entryName));
        zos.write(text.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static String joinTruncated(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (sb.length() > MAX_LOG_CHARS) break;
            sb.append(line).append('\n');
        }
        return truncate(sb.toString(), MAX_LOG_CHARS);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(s.length() - max);
    }

    private static String sanitizeName(String name) {
        if (name == null) return "file.txt";
        return name.replace("..", "_").replace('/', '_').replace('\\', '_');
    }

    private static String sys(String key) {
        String v = System.getProperty(key);
        return v != null ? v : "";
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
