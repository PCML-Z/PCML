package com.pmcl.core.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 每次打开时核对安装目录里的 app 文件。
 * 有已验签的热更新清单时按清单修；否则用第一次打开时记下的本地基线发现损坏。
 */
public final class AppResourceCheck {
    private AppResourceCheck() {}

    public enum Status { OK, SKIPPED, BROKEN, REPAIRABLE, RESTORE }

    public static final class Report {
        private final Status status;
        private final String versionKey;
        private final String detail;
        private final HotUpdateManifest manifest;
        private final List<String> paths;

        private Report(Status status, String versionKey, String detail,
                       HotUpdateManifest manifest, List<String> paths) {
            this.status = status;
            this.versionKey = versionKey == null ? "" : versionKey;
            this.detail = detail == null ? "" : detail;
            this.manifest = manifest;
            this.paths = paths == null ? List.of() : List.copyOf(paths);
        }

        public Status status() { return status; }
        public String versionKey() { return versionKey; }
        public String detail() { return detail; }
        public HotUpdateManifest manifest() { return manifest; }
        public List<String> paths() { return paths; }

        static Report ok() { return new Report(Status.OK, "", "", null, List.of()); }
        static Report skipped() { return new Report(Status.SKIPPED, "", "", null, List.of()); }
    }

    public static Report check(Path home, Path appDir) throws IOException {
        if (appDir == null || !Files.isDirectory(appDir)) return Report.skipped();
        Path installed = installedManifest(home);
        if (Files.isRegularFile(installed)) {
            HotUpdateManifest manifest;
            try {
                manifest = HotUpdateManifest.parse(Files.readString(installed));
            } catch (IOException e) {
                return new Report(Status.BROKEN, "", e.getMessage(), null, List.of());
            }
            List<String> broken = mismatches(appDir, manifest.filesFor(HotUpdateManifest.Host.current()));
            if (broken.isEmpty()) return Report.ok();
            List<String> restorable = backupMatches(home, appDir, manifest, broken);
            if (restorable.size() == broken.size()) {
                return new Report(Status.RESTORE, manifest.version(), summarize(broken), manifest, broken);
            }
            return new Report(Status.REPAIRABLE, manifest.version(), summarize(broken), manifest, broken);
        }
        Path baselineFile = baselinePath(home);
        if (!Files.isRegularFile(baselineFile)) {
            List<HotUpdateManifest.FileEntry> captured = capture(appDir);
            if (captured.isEmpty()) {
                return new Report(Status.BROKEN, "baseline", "app 目录是空的", null, List.of());
            }
            writeBaseline(baselineFile, captured);
            return Report.ok();
        }
        List<HotUpdateManifest.FileEntry> expected = readBaseline(baselineFile);
        List<String> broken = mismatches(appDir, expected);
        if (broken.isEmpty()) return Report.ok();
        if (backupMatches(home, appDir, expected, broken).size() == broken.size()) {
            return new Report(Status.RESTORE, "baseline", summarize(broken), null, broken);
        }
        return new Report(Status.BROKEN, "baseline", summarize(broken), null, broken);
    }

    public static HotUpdateManifest loadInstalled(Path home) {
        try {
            Path file = installedManifest(home);
            if (!Files.isRegularFile(file)) return null;
            return HotUpdateManifest.parse(Files.readString(file));
        } catch (IOException e) {
            return null;
        }
    }

    public static Path installedManifest(Path home) {
        return home.resolve("updates").resolve("installed-hotupdate.json");
    }

    public static Path baselinePath(Path home) {
        return home.resolve("updates").resolve("app-baseline.json");
    }

    public static Path backupFile(Path home, String relative) {
        return home.resolve("updates").resolve("backup").resolve(relative);
    }

    static Path resolve(Path appDir, String relative) throws IOException {
        HotUpdateManifest.FileEntry.validatePath(relative);
        String rest = relative.substring("app/".length());
        Path dest = appDir.normalize();
        for (String seg : rest.split("/")) {
            dest = dest.resolve(seg).normalize();
            if (!dest.startsWith(appDir.normalize())) {
                throw new IOException("热更新路径越界: " + relative);
            }
            if (Files.isSymbolicLink(dest)) {
                throw new IOException("拒绝符号链接: " + relative);
            }
        }
        return dest;
    }

    static List<String> mismatches(Path appDir, List<HotUpdateManifest.FileEntry> expected) throws IOException {
        List<String> broken = new ArrayList<>();
        for (HotUpdateManifest.FileEntry file : expected) {
            Path dest = resolve(appDir, file.path());
            if (!Files.isRegularFile(dest) || Files.size(dest) != file.size()
                    || !FileDigest.sha256(dest).equals(file.sha256())
                    || !FileDigest.contentLooksValid(dest)) {
                broken.add(file.path());
            }
        }
        return broken;
    }

    private static List<String> backupMatches(Path home, Path appDir, HotUpdateManifest manifest,
                                              List<String> broken) throws IOException {
        return backupMatches(home, appDir, manifest.files(), broken);
    }

    private static List<String> backupMatches(Path home, Path appDir,
                                              List<HotUpdateManifest.FileEntry> expected,
                                              List<String> broken) throws IOException {
        List<String> ok = new ArrayList<>();
        for (String path : broken) {
            HotUpdateManifest.FileEntry file = find(expected, path);
            if (file == null) continue;
            Path backup = backupFile(home, path);
            if (Files.isRegularFile(backup) && !Files.isSymbolicLink(backup)
                    && Files.size(backup) == file.size()
                    && FileDigest.sha256(backup).equals(file.sha256())
                    && FileDigest.contentLooksValid(backup)) {
                ok.add(path);
            }
        }
        return ok;
    }

    private static HotUpdateManifest.FileEntry find(List<HotUpdateManifest.FileEntry> files, String path) {
        for (HotUpdateManifest.FileEntry file : files) {
            if (file.path().equals(path)) return file;
        }
        return null;
    }

    private static List<HotUpdateManifest.FileEntry> capture(Path appDir) throws IOException {
        List<HotUpdateManifest.FileEntry> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(appDir)) {
            List<Path> regulars = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : regulars) {
                if (Files.isSymbolicLink(file)) {
                    throw new IOException("安装目录含符号链接: " + appDir.relativize(file));
                }
                String name = file.getFileName().toString();
                if (name.equals(".DS_Store") || name.startsWith(".")) continue;
                if (!FileDigest.contentLooksValid(file)) {
                    throw new IOException("安装文件无法打开: " + appDir.relativize(file));
                }
                String relative = "app/" + appDir.relativize(file).toString().replace('\\', '/');
                HotUpdateManifest.FileEntry.validatePath(relative);
                files.add(new HotUpdateManifest.FileEntry(
                        relative, FileDigest.sha256(file), Files.size(file), "", "", ""));
            }
        }
        files.sort(Comparator.comparing(HotUpdateManifest.FileEntry::path));
        return files;
    }

    private static void writeBaseline(Path file, List<HotUpdateManifest.FileEntry> files) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("source", "baseline");
        JsonArray array = new JsonArray();
        for (HotUpdateManifest.FileEntry entry : files) {
            JsonObject item = new JsonObject();
            item.addProperty("path", entry.path());
            item.addProperty("sha256", entry.sha256());
            item.addProperty("size", entry.size());
            array.add(item);
        }
        o.add("files", array);
        Files.createDirectories(file.getParent());
        Files.writeString(file, o.toString(), StandardCharsets.UTF_8);
    }

    private static List<HotUpdateManifest.FileEntry> readBaseline(Path file) throws IOException {
        JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        if (!o.has("files") || !o.get("files").isJsonArray()) {
            throw new IOException("本地基线无效");
        }
        List<HotUpdateManifest.FileEntry> files = new ArrayList<>();
        for (var el : o.getAsJsonArray("files")) {
            JsonObject item = el.getAsJsonObject();
            String path = item.get("path").getAsString();
            HotUpdateManifest.FileEntry.validatePath(path);
            files.add(new HotUpdateManifest.FileEntry(
                    path,
                    item.get("sha256").getAsString(),
                    item.get("size").getAsLong(),
                    "", "", ""));
        }
        return files;
    }

    private static String summarize(List<String> paths) {
        int n = Math.min(paths.size(), 8);
        return String.join(", ", paths.subList(0, n));
    }

    /** 同一版本只自动修复一次，避免坏文件导致每次打开都重启。 */
    public static final class RepairAttempts {
        private RepairAttempts() {}

        public static int next(Path home, String version) throws IOException {
            Path file = home.resolve("updates").resolve("repair-attempt.txt");
            String key = version == null ? "" : version;
            int count = 0;
            if (Files.isRegularFile(file)) {
                String[] lines = Files.readString(file).split("\n", 2);
                if (lines.length == 2 && lines[0].equals(key)) {
                    try { count = Integer.parseInt(lines[1].trim()); } catch (NumberFormatException ignored) {}
                }
            }
            int next = count + 1;
            Files.createDirectories(file.getParent());
            Files.writeString(file, key + "\n" + next + "\n", StandardCharsets.UTF_8);
            return next;
        }

        public static void clear(Path home) throws IOException {
            Files.deleteIfExists(home.resolve("updates").resolve("repair-attempt.txt"));
        }
    }
}
