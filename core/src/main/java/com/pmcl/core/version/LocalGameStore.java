package com.pmcl.core.version;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.install.VersionStaging;
import com.pmcl.core.launch.GameDirResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机已安装游戏的占用和卸载。
 * 只统计、只删除 versions 根目录下的那一个版本，以及 instances 根目录下属于它的实例。
 * 不碰共享 libraries、assets，也不跟符号链接走出去。
 */
public final class LocalGameStore {

    public enum Kind {
        VERSION,
        INSTANCE
    }

    public static final class GameSpace {
        private final Kind kind;
        private final String id;
        private final String title;
        private final boolean external;
        private final long versionBytes;
        private final long dataBytes;
        private final List<String> dataDirNames;

        GameSpace(Kind kind, String id, String title, boolean external,
                  long versionBytes, long dataBytes, List<String> dataDirNames) {
            this.kind = kind;
            this.id = id;
            this.title = title;
            this.external = external;
            this.versionBytes = versionBytes;
            this.dataBytes = dataBytes;
            this.dataDirNames = dataDirNames;
        }

        public Kind getKind() { return kind; }
        public String getId() { return id; }
        public String getTitle() { return title; }
        public boolean isExternal() { return external; }
        public boolean isInstanceOnly() { return kind == Kind.INSTANCE; }
        public long getVersionBytes() { return versionBytes; }
        public long getDataBytes() { return dataBytes; }
        public int getDataDirCount() { return dataDirNames.size(); }
        public List<String> getDataDirNames() { return dataDirNames; }
        public long totalBytes() { return versionBytes + dataBytes; }
    }

    private LocalGameStore() {}

    public static List<GameSpace> measure(Path workDir, List<Path> versionRoots) {
        Path versionsHome = versionsHome(workDir);
        Path instancesRoot = instancesRoot(workDir);
        Map<String, Path> versions = new LinkedHashMap<>();
        if (versionRoots != null) {
            for (Path root : versionRoots) {
                collectVersions(root, versions);
            }
        }
        Map<String, List<Path>> owned = new LinkedHashMap<>();
        List<Path> orphans = new ArrayList<>();
        collectInstances(instancesRoot, versions, owned, orphans);

        List<GameSpace> rows = new ArrayList<>();
        for (Map.Entry<String, Path> entry : versions.entrySet()) {
            Path versionDir = entry.getValue();
            List<Path> dataDirs = owned.getOrDefault(entry.getKey(), List.of());
            long versionBytes = directoryBytes(versionDir);
            long dataBytes = 0L;
            List<String> names = new ArrayList<>();
            for (Path data : dataDirs) {
                if (data.startsWith(versionDir)) continue;
                dataBytes += directoryBytes(data);
                names.add(data.getFileName().toString());
            }
            boolean external = versionDir.getParent() == null || !versionDir.getParent().equals(versionsHome);
            rows.add(new GameSpace(Kind.VERSION, entry.getKey(), entry.getKey(), external,
                    versionBytes, dataBytes, List.copyOf(names)));
        }
        for (Path orphan : orphans) {
            String folder = orphan.getFileName().toString();
            rows.add(new GameSpace(Kind.INSTANCE, folder, instanceTitle(orphan, folder), false,
                    0L, directoryBytes(orphan), List.of(folder)));
        }
        rows.sort((a, b) -> {
            int bySize = Long.compare(b.totalBytes(), a.totalBytes());
            if (bySize != 0) return bySize;
            return a.title.compareToIgnoreCase(b.title);
        });
        return rows;
    }

    /** 删除这个版本目录，以及 instances 里属于它的实例目录。 */
    public static void uninstallVersion(Path workDir, List<Path> versionRoots, String versionId) throws IOException {
        GameDirResolver.requireSafeVersionId(versionId);
        Path versionDir = findVersionDir(versionRoots, versionId);
        if (versionDir == null) {
            throw new IOException("找不到版本: " + versionId);
        }
        Map<String, Path> versions = new LinkedHashMap<>();
        versions.put(versionId, versionDir);
        Map<String, List<Path>> owned = new LinkedHashMap<>();
        collectInstances(instancesRoot(workDir), versions, owned, new ArrayList<>());
        for (Path data : owned.getOrDefault(versionId, List.of())) {
            assertDirectChild(instancesRoot(workDir), data);
            if (!data.equals(versionDir)) deleteTree(data);
        }
        assertDirectChild(versionDir.getParent(), versionDir);
        deleteTree(versionDir);
    }

    /** 只删除一个没有对应版本的实例目录。 */
    public static void uninstallInstance(Path workDir, String folderName) throws IOException {
        GameDirResolver.requireSafeVersionId(folderName);
        Path dir = instancesRoot(workDir).resolve(folderName).normalize();
        assertDirectChild(instancesRoot(workDir), dir);
        if (!Files.isDirectory(dir) || Files.isSymbolicLink(dir)) {
            throw new IOException("找不到实例: " + folderName);
        }
        deleteTree(dir);
    }

    private static void collectVersions(Path root, Map<String, Path> versions) {
        if (root == null) return;
        Path normalized;
        try {
            normalized = root.toAbsolutePath().normalize();
        } catch (Exception e) {
            return;
        }
        if (!isVersionsRoot(normalized) || !Files.isDirectory(normalized)) return;
        try (var stream = Files.list(normalized)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (Files.isSymbolicLink(child) || !Files.isDirectory(child)) continue;
                String name = child.getFileName().toString();
                if (!isSafeId(name) || VersionStaging.isTransientDirName(name)) continue;
                Path expected = normalized.resolve(name).normalize();
                if (!expected.equals(child.toAbsolutePath().normalize())) continue;
                if (!expected.startsWith(normalized)) continue;
                versions.putIfAbsent(name, expected);
            }
        } catch (IOException ignored) {
        }
    }

    private static Path findVersionDir(List<Path> versionRoots, String versionId) {
        if (versionRoots == null) return null;
        for (Path root : versionRoots) {
            if (root == null) continue;
            Path normalized = root.toAbsolutePath().normalize();
            if (!isVersionsRoot(normalized)) continue;
            Path child = normalized.resolve(versionId).normalize();
            if (!child.startsWith(normalized) || child.getParent() == null || !child.getParent().equals(normalized)) {
                continue;
            }
            if (Files.isDirectory(child) && !Files.isSymbolicLink(child)) return child;
        }
        return null;
    }

    private static void collectInstances(Path instancesRoot, Map<String, Path> versions,
                                         Map<String, List<Path>> owned, List<Path> orphans) {
        if (!Files.isDirectory(instancesRoot)) return;
        try (var stream = Files.list(instancesRoot)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (Files.isSymbolicLink(child) || !Files.isDirectory(child)) continue;
                String folder = child.getFileName().toString();
                if (!isSafeId(folder)) continue;
                Path expected = instancesRoot.resolve(folder).normalize();
                if (!expected.equals(child.toAbsolutePath().normalize()) || !expected.startsWith(instancesRoot)) {
                    continue;
                }
                String base = instanceBaseVersion(expected);
                if (base != null && versions.containsKey(base)) {
                    owned.computeIfAbsent(base, key -> new ArrayList<>()).add(expected);
                } else if (base == null && versions.containsKey(folder)) {
                    owned.computeIfAbsent(folder, key -> new ArrayList<>()).add(expected);
                } else {
                    orphans.add(expected);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String instanceBaseVersion(Path dir) {
        String fromMarker = readBaseVersion(dir.resolve("instance.json"));
        if (fromMarker != null) return fromMarker;
        return readBaseVersion(dir.resolve("modpack.json"));
    }

    private static String readBaseVersion(Path file) {
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) return null;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!obj.has("baseVersionId") || obj.get("baseVersionId").isJsonNull()) return null;
            String id = obj.get("baseVersionId").getAsString();
            return isSafeId(id) ? id : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String instanceTitle(Path dir, String folder) {
        Path marker = dir.resolve("instance.json");
        if (!Files.isRegularFile(marker)) marker = dir.resolve("modpack.json");
        if (!Files.isRegularFile(marker) || Files.isSymbolicLink(marker)) return folder;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(marker, StandardCharsets.UTF_8)).getAsJsonObject();
            if (obj.has("name") && !obj.get("name").isJsonNull()) {
                String name = obj.get("name").getAsString().trim();
                if (!name.isEmpty()) return name;
            }
        } catch (Exception ignored) {
        }
        return folder;
    }

    private static boolean isVersionsRoot(Path root) {
        Path name = root.getFileName();
        return name != null && "versions".equals(name.toString());
    }

    private static boolean isSafeId(String id) {
        if (id == null || id.isBlank()) return false;
        return id.indexOf('\0') < 0 && !id.contains("..") && !id.contains("/") && !id.contains("\\");
    }

    private static Path versionsHome(Path workDir) {
        return workDir.toAbsolutePath().normalize().resolve("versions");
    }

    private static Path instancesRoot(Path workDir) {
        return workDir.toAbsolutePath().normalize().resolve("instances");
    }

    private static void assertDirectChild(Path root, Path child) throws IOException {
        Path parent = root.toAbsolutePath().normalize();
        Path target = child.toAbsolutePath().normalize();
        if (target.equals(parent) || target.getParent() == null || !target.getParent().equals(parent)
                || !target.startsWith(parent)) {
            throw new IOException("拒绝删除: " + child);
        }
    }

    static long directoryBytes(Path root) {
        if (root == null || !Files.exists(root) || Files.isSymbolicLink(root)) return 0L;
        long[] total = {0L};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isSymbolicLink() && attrs.isRegularFile()) total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
        return total[0];
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.isSymbolicLink(root)) {
            Files.deleteIfExists(root);
            return;
        }
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(root) && Files.isSymbolicLink(dir)) {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
        if (Files.exists(root)) {
            throw new IOException("未能删除 " + root.getFileName());
        }
    }
}
