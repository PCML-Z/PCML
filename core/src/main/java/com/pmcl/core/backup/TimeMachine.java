package com.pmcl.core.backup;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 启动器自己的时间机器。
 * <p>
 * 每一份快照只记录文件清单。文件内容按 SHA-256 放进仓库，没改过的文件不会再复制一份。
 * 账号、令牌和密钥文件不会进入快照。
 */
public final class TimeMachine {

    private static final DateTimeFormatter ID_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());
    private static final int BUFFER = 1024 * 1024;
    private static final Set<String> SKIP_NAMES = Set.of(
            "session.lock", ".ds_store", "accounts.json", "auth-servers.json", ".keyfile",
            "opanel.json", "tokens.json", "credentials.json", "secrets.json", "auth.json",
            "friends.json", "identity.json", "plugins.json", "trusted-signers.txt"
    );
    private static final Set<String> SKIP_DIRS = Set.of(
            "timemachine", "libraries", "assets", "runtimes", "logs", "crash-reports",
            "natives", ".cache", "backups", "updates", "downloads", "native-clients",
            "plugins", "blobs", "snapshots"
    );

    private final Path workDir;
    private final Path store;
    private final Object lock = new Object();

    public TimeMachine(Path workDir) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.store = this.workDir.resolve("timemachine");
    }

    public Path storeDir() {
        return store;
    }

    public static final class Settings {
        public String schedule = "manual";
        public int keep = 30;
        public boolean worlds = true;
        public boolean mods = true;
        public boolean configs = true;
        public boolean resourcepacks = true;
        public boolean shaders = true;
        /** 启动器设置可能含代理凭据，默认不备份。 */
        public boolean launcher = false;

        public boolean allows(String scope) {
            return switch (scope) {
                case "worlds" -> worlds;
                case "mods" -> mods;
                case "configs" -> configs;
                case "resourcepacks" -> resourcepacks;
                case "shaders" -> shaders;
                case "launcher" -> launcher;
                default -> false;
            };
        }
    }

    public static final class Snapshot {
        private final String id;
        private final long createdAt;
        private final String label;
        private final int files;
        private final long logicalBytes;
        private final long storedBytes;
        private final int skipped;

        public Snapshot(String id, long createdAt, String label, int files,
                        long logicalBytes, long storedBytes, int skipped) {
            this.id = id;
            this.createdAt = createdAt;
            this.label = label;
            this.files = files;
            this.logicalBytes = logicalBytes;
            this.storedBytes = storedBytes;
            this.skipped = skipped;
        }

        public String id() { return id; }
        public long createdAt() { return createdAt; }
        public String label() { return label; }
        public int files() { return files; }
        public long logicalBytes() { return logicalBytes; }
        public long storedBytes() { return storedBytes; }
        public int skipped() { return skipped; }
    }

    public static final class Entry {
        private final String scope;
        private final String root;
        private final String path;
        private final long size;
        private final String sha256;
        private final boolean tree;
        private final String change;

        public Entry(String scope, String root, String path, long size, String sha256,
                     boolean tree, String change) {
            this.scope = scope;
            this.root = root;
            this.path = path;
            this.size = size;
            this.sha256 = sha256;
            this.tree = tree;
            this.change = change == null ? "" : change;
        }

        public String scope() { return scope; }
        public String root() { return root; }
        public String path() { return path; }
        public long size() { return size; }
        public String sha256() { return sha256; }
        public boolean tree() { return tree; }
        public String change() { return change; }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void update(int done, int total, String current);
    }

    public Settings loadSettings() {
        Path file = store.resolve("settings.json");
        Settings settings = new Settings();
        if (!Files.isRegularFile(file)) return settings;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            settings.schedule = text(obj, "schedule", "manual");
            settings.keep = obj.has("keep") ? obj.get("keep").getAsInt() : 30;
            settings.worlds = bool(obj, "worlds", true);
            settings.mods = bool(obj, "mods", true);
            settings.configs = bool(obj, "configs", true);
            settings.resourcepacks = bool(obj, "resourcepacks", true);
            settings.shaders = bool(obj, "shaders", true);
            settings.launcher = bool(obj, "launcher", false);
        } catch (Exception ignored) {
            return new Settings();
        }
        return sanitize(settings);
    }

    public void saveSettings(Settings settings) throws IOException {
        Settings clean = sanitize(settings);
        JsonObject obj = new JsonObject();
        obj.addProperty("schedule", clean.schedule);
        obj.addProperty("keep", clean.keep);
        obj.addProperty("worlds", clean.worlds);
        obj.addProperty("mods", clean.mods);
        obj.addProperty("configs", clean.configs);
        obj.addProperty("resourcepacks", clean.resourcepacks);
        obj.addProperty("shaders", clean.shaders);
        obj.addProperty("launcher", clean.launcher);
        writeJson(store.resolve("settings.json"), obj);
    }

    public List<Snapshot> listSnapshots() {
        Path dir = store.resolve("snapshots");
        List<Snapshot> list = new ArrayList<>();
        if (!Files.isDirectory(dir)) return list;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path child : ds) {
                if (!Files.isDirectory(child) || !safeId(fileName(child))) continue;
                Snapshot snap = readMeta(child);
                if (snap != null) list.add(snap);
            }
        } catch (IOException ignored) {
            return list;
        }
        list.sort(Comparator.comparingLong(Snapshot::createdAt).reversed());
        return list;
    }

    public List<Entry> entries(String snapshotId) throws IOException {
        requireId(snapshotId);
        return readEntries(snapshotDir(snapshotId));
    }

    /** 和上一份快照比：新增、修改、未改、已删除。没有更早的快照时，全部算新增。 */
    public List<Entry> changes(String snapshotId) throws IOException {
        requireId(snapshotId);
        List<Snapshot> all = listSnapshots();
        Snapshot current = null;
        Snapshot older = null;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(snapshotId)) {
                current = all.get(i);
                if (i + 1 < all.size()) older = all.get(i + 1);
                break;
            }
        }
        if (current == null) throw new IOException("snapshot not found");
        Map<String, Entry> now = index(readEntries(snapshotDir(current.id())));
        Map<String, Entry> prev = older == null ? Map.of() : index(readEntries(snapshotDir(older.id())));
        List<Entry> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : now.entrySet()) {
            Entry cur = e.getValue();
            Entry old = prev.get(e.getKey());
            String change = old == null ? "added" : (old.sha256().equals(cur.sha256()) ? "unchanged" : "modified");
            out.add(copy(cur, change));
        }
        for (Map.Entry<String, Entry> e : prev.entrySet()) {
            if (!now.containsKey(e.getKey())) out.add(copy(e.getValue(), "removed"));
        }
        out.sort(Comparator.comparing(Entry::path));
        return out;
    }

    public boolean due(Settings settings, long nowMillis) {
        Settings clean = sanitize(settings);
        long interval = intervalMillis(clean.schedule);
        if (interval <= 0) return false;
        long latest = 0L;
        boolean any = false;
        for (Snapshot snap : listSnapshots()) {
            if ("before-restore".equals(snap.label())) continue;
            any = true;
            if (snap.createdAt() > latest) latest = snap.createdAt();
        }
        return any && nowMillis - latest >= interval;
    }

    public long storedSize() {
        Path blobs = store.resolve("blobs");
        if (!Files.isDirectory(blobs)) return 0L;
        long[] total = new long[1];
        try {
            Files.walkFileTree(blobs, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!Files.isSymbolicLink(file) && attrs.isRegularFile()) total[0] += attrs.size();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            return total[0];
        }
        return total[0];
    }

    public Snapshot backup(Settings settings, List<Path> extraRoots, String label, ProgressListener progress)
            throws IOException {
        synchronized (lock) {
            Settings clean = sanitize(settings);
            String useLabel = knownLabel(label, "now");
            List<Source> sources = collect(clean, extraRoots);
            List<Pending> pending = new ArrayList<>();
            for (Source source : sources) walk(source, pending);
            long now = System.currentTimeMillis();
            String id = newId(now, "");
            Path dir = snapshotDir(id);
            Files.createDirectories(dir);
            JsonArray files = new JsonArray();
            long logical = 0L;
            long stored = 0L;
            int skipped = 0;
            int done = 0;
            for (Pending item : pending) {
                done++;
                if (progress != null) progress.update(done, pending.size(), item.relative);
                try {
                    Stored storedBlob = storeBlob(item.file);
                    logical += item.size;
                    if (storedBlob.created) stored += item.size;
                    JsonObject row = new JsonObject();
                    row.addProperty("scope", item.scope);
                    row.addProperty("root", item.root.toString());
                    row.addProperty("path", item.relative);
                    row.addProperty("size", item.size);
                    row.addProperty("sha256", storedBlob.sha256);
                    row.addProperty("tree", item.tree);
                    files.add(row);
                } catch (IOException e) {
                    skipped++;
                }
            }
            JsonObject manifest = new JsonObject();
            manifest.addProperty("id", id);
            manifest.addProperty("createdAt", now);
            manifest.addProperty("label", useLabel);
            manifest.add("files", files);
            writeJson(dir.resolve("manifest.json"), manifest);
            Snapshot snap = new Snapshot(id, now, useLabel, files.size(), logical, stored, skipped);
            writeJson(dir.resolve("meta.json"), metaJson(snap));
            pruneUnlocked(clean.keep);
            gcUnlocked();
            return snap;
        }
    }

    /**
     * @param selected 要恢复的条目。null 表示整份快照。已删除的条目可以单独写回。
     * @param deleteExtras 仅在恢复整份快照时生效，删掉这份快照之后新出现、且属于同一目录树的文件。
     */
    public int restore(String snapshotId, List<Entry> selected, boolean deleteExtras, ProgressListener progress)
            throws IOException {
        synchronized (lock) {
            requireId(snapshotId);
            List<Entry> all = readEntries(snapshotDir(snapshotId));
            List<Entry> chosen = pick(all, selected);
            if (chosen.isEmpty()) return 0;
            List<Entry> pre = new ArrayList<>();
            int done = 0;
            int written = 0;
            for (Entry entry : chosen) {
                done++;
                if (progress != null) progress.update(done, chosen.size(), entry.path());
                if (writeBack(entry, pre)) written++;
            }
            if (deleteExtras && selected == null) {
                written += deleteExtras(all, pre, progress);
            }
            if (!pre.isEmpty()) writeSnapshot(newId(System.currentTimeMillis(), "pre-"), "before-restore", pre, 0);
            return written;
        }
    }

    public void deleteSnapshot(String snapshotId) throws IOException {
        synchronized (lock) {
            requireId(snapshotId);
            Path dir = snapshotDir(snapshotId);
            if (!Files.isDirectory(dir)) return;
            deleteTree(dir);
            gcUnlocked();
        }
    }

    private List<Entry> pick(List<Entry> all, List<Entry> selected) {
        if (selected == null) return all;
        Map<String, Entry> have = index(all);
        Set<String> roots = new HashSet<>();
        for (Entry entry : all) roots.add(normalizeRoot(entry.root()));
        List<Entry> chosen = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Entry entry : selected) {
            String key = key(entry.root(), entry.path());
            if (!seen.add(key)) continue;
            Entry known = have.get(key);
            if (known != null) {
                chosen.add(known);
                continue;
            }
            if (!"removed".equals(entry.change())) continue;
            if (!roots.contains(normalizeRoot(entry.root()))) continue;
            if (!isSha256(entry.sha256()) || !Files.isRegularFile(blobPath(entry.sha256()))) continue;
            chosen.add(entry);
        }
        return chosen;
    }

    private boolean writeBack(Entry entry, List<Entry> pre) throws IOException {
        String relative = safeRelative(entry.path());
        if (!isSha256(entry.sha256())) throw new IOException("bad hash");
        Path root = absoluteRoot(entry.root());
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new IOException("path escapes root");
        if (target.startsWith(store)) throw new IOException("refuse store target");
        String name = target.getFileName() == null ? "" : target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (SKIP_NAMES.contains(name)) throw new IOException("refuse sensitive file");
        Path blob = blobPath(entry.sha256());
        if (!Files.isRegularFile(blob)) throw new IOException("missing blob");
        if (Files.isSymbolicLink(target)) throw new IOException("refuse symlink target");
        Files.createDirectories(target.getParent() == null ? root : target.getParent());
        if (Files.isRegularFile(target)) {
            String have = sha256(target);
            if (entry.sha256().equals(have)) return false;
            pre.add(new Entry(entry.scope(), root.toString(), relative, Files.size(target), have, entry.tree(), ""));
        }
        Path tmp = target.resolveSibling(target.getFileName().toString() + ".pmcl-restore");
        try {
            String got = copyHashed(blob, tmp);
            if (!entry.sha256().equals(got)) throw new IOException("blob hash mismatch");
            moveReplace(tmp, target);
            return true;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private int deleteExtras(List<Entry> all, List<Entry> pre, ProgressListener progress) throws IOException {
        Map<String, Set<String>> keep = new HashMap<>();
        Map<String, String> scopeOf = new HashMap<>();
        for (Entry entry : all) {
            if (!entry.tree()) continue;
            String root = normalizeRoot(entry.root());
            keep.computeIfAbsent(root, k -> new HashSet<>()).add(safeRelative(entry.path()));
            scopeOf.put(root, entry.scope());
        }
        int removed = 0;
        for (Map.Entry<String, Set<String>> rootEntry : keep.entrySet()) {
            Path root = Path.of(rootEntry.getKey());
            if (!extraRootAllowed(root) || !Files.isDirectory(root) || Files.isSymbolicLink(root)) continue;
            if (root.startsWith(store)) continue;
            Set<String> present = rootEntry.getValue();
            List<Path> doomed = new ArrayList<>();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                    if (!dir.equals(root) && skipDir(dir)) return FileVisitResult.SKIP_SUBTREE;
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(file) || !attrs.isRegularFile()) return FileVisitResult.CONTINUE;
                    if (skipFile(file)) return FileVisitResult.CONTINUE;
                    String rel = root.relativize(file).toString().replace('\\', '/');
                    if (!present.contains(rel)) doomed.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
            for (Path file : doomed) {
                String rel = safeRelative(root.relativize(file).toString().replace('\\', '/'));
                String hash = sha256(file);
                pre.add(new Entry(scopeOf.getOrDefault(root.toString(), ""), root.toString(), rel,
                        Files.size(file), hash, true, ""));
                storeBlob(file);
                Files.deleteIfExists(file);
                removed++;
                if (progress != null) progress.update(removed, doomed.size(), rel);
            }
        }
        return removed;
    }

    private void writeSnapshot(String id, String label, List<Entry> entries, int skipped) throws IOException {
        Path dir = snapshotDir(id);
        Files.createDirectories(dir);
        JsonArray files = new JsonArray();
        long logical = 0L;
        for (Entry entry : entries) {
            logical += Math.max(0L, entry.size());
            JsonObject row = new JsonObject();
            row.addProperty("scope", entry.scope());
            row.addProperty("root", entry.root());
            row.addProperty("path", entry.path());
            row.addProperty("size", entry.size());
            row.addProperty("sha256", entry.sha256());
            row.addProperty("tree", entry.tree());
            files.add(row);
        }
        JsonObject manifest = new JsonObject();
        manifest.addProperty("id", id);
        manifest.addProperty("createdAt", System.currentTimeMillis());
        manifest.addProperty("label", label);
        manifest.add("files", files);
        writeJson(dir.resolve("manifest.json"), manifest);
        Snapshot snap = new Snapshot(id, manifest.get("createdAt").getAsLong(), label, files.size(), logical, 0L, skipped);
        writeJson(dir.resolve("meta.json"), metaJson(snap));
    }

    private List<Source> collect(Settings settings, List<Path> extraRoots) throws IOException {
        Map<String, Source> map = new LinkedHashMap<>();
        addGame(map, settings, workDir, true);
        addChildren(map, settings, workDir.resolve("instances"));
        addVersionChildren(map, settings, workDir.resolve("versions"));
        if (extraRoots != null) {
            for (Path extra : extraRoots) {
                if (extra == null) continue;
                Path root = extra.toAbsolutePath().normalize();
                if (root.startsWith(store) || root.equals(workDir)) continue;
                addGame(map, settings, root, false);
                addVersionChildren(map, settings, root.resolve("versions"));
            }
        }
        return new ArrayList<>(map.values());
    }

    private void addChildren(Map<String, Source> map, Settings settings, Path parent) throws IOException {
        if (!isRealDir(parent)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(parent)) {
            for (Path child : ds) {
                if (!isRealDir(child) || skipChild(child)) continue;
                addGame(map, settings, child, false);
            }
        }
    }

    private void addVersionChildren(Map<String, Source> map, Settings settings, Path versions) throws IOException {
        addChildren(map, settings, versions);
    }

    private void addGame(Map<String, Source> map, Settings settings, Path root, boolean launcherHome) {
        if (!isRealDir(root) || root.startsWith(store)) return;
        if (settings.worlds) putTree(map, "worlds", root.resolve("saves"));
        if (settings.mods) putTree(map, "mods", root.resolve("mods"));
        if (settings.configs) {
            putTree(map, "configs", root.resolve("config"));
            putFiles(map, "configs", root, "options.txt", "optionsof.txt", "optionsshaders.txt", "servers.dat");
        }
        if (settings.resourcepacks) putTree(map, "resourcepacks", root.resolve("resourcepacks"));
        if (settings.shaders) putTree(map, "shaders", root.resolve("shaderpacks"));
        if (launcherHome && settings.launcher) putFiles(map, "launcher", root, "preferences.json");
    }

    private void putTree(Map<String, Source> map, String scope, Path dir) {
        if (!isRealDir(dir) || dir.startsWith(store)) return;
        Path norm = dir.toAbsolutePath().normalize();
        map.putIfAbsent(scope + "|" + norm + "|tree", new Source(scope, norm, true, null));
    }

    private void putFiles(Map<String, Source> map, String scope, Path dir, String... names) {
        if (!isRealDir(dir) || dir.startsWith(store)) return;
        Path norm = dir.toAbsolutePath().normalize();
        String key = scope + "|" + norm + "|files";
        Source existing = map.get(key);
        Set<String> keep = existing == null ? new HashSet<>() : existing.onlyNames;
        for (String name : names) {
            if (SKIP_NAMES.contains(name.toLowerCase(Locale.ROOT))) continue;
            Path file = norm.resolve(name);
            if (Files.isRegularFile(file) && !Files.isSymbolicLink(file)) keep.add(name);
        }
        if (keep.isEmpty()) return;
        if (existing == null) map.put(key, new Source(scope, norm, false, keep));
    }

    private void walk(Source source, List<Pending> out) throws IOException {
        if (!source.tree) {
            for (String name : source.onlyNames) {
                Path file = source.dir.resolve(name);
                if (Files.isRegularFile(file) && !Files.isSymbolicLink(file) && !skipFile(file)) {
                    out.add(new Pending(source.scope, source.dir, name, Files.size(file), false, file));
                }
            }
            return;
        }
        Files.walkFileTree(source.dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                if (dir.startsWith(store)) return FileVisitResult.SKIP_SUBTREE;
                if (!dir.equals(source.dir) && skipDir(dir)) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile() || skipFile(file)) {
                    return FileVisitResult.CONTINUE;
                }
                String rel = source.dir.relativize(file).toString().replace('\\', '/');
                try {
                    rel = safeRelative(rel);
                } catch (IllegalArgumentException e) {
                    return FileVisitResult.CONTINUE;
                }
                out.add(new Pending(source.scope, source.dir, rel, attrs.size(), true, file));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Stored storeBlob(Path file) throws IOException {
        String hash = sha256(file);
        Path blob = blobPath(hash);
        if (Files.isRegularFile(blob)) return new Stored(hash, false);
        Files.createDirectories(blob.getParent());
        Path tmp = blob.resolveSibling(hash + ".tmp");
        try {
            String got = copyHashed(file, tmp);
            if (!hash.equals(got)) throw new IOException("hash changed while reading");
            if (Files.isRegularFile(blob)) {
                Files.deleteIfExists(tmp);
                return new Stored(hash, false);
            }
            moveReplace(tmp, blob);
            return new Stored(hash, true);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private String copyHashed(Path from, Path to) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(from); OutputStream out = Files.newOutputStream(to)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n == 0) continue;
                digest.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        return hex(digest.digest());
    }

    private String sha256(Path file) throws IOException {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (n > 0) digest.update(buf, 0, n);
            }
        }
        return hex(digest.digest());
    }

    private void pruneUnlocked(int keep) throws IOException {
        List<Snapshot> snaps = listSnapshots();
        if (snaps.size() <= keep) return;
        List<Snapshot> oldest = new ArrayList<>(snaps);
        oldest.sort(Comparator.comparingLong(Snapshot::createdAt));
        int extra = oldest.size() - keep;
        for (int i = 0; i < extra; i++) deleteTree(snapshotDir(oldest.get(i).id()));
    }

    private void gcUnlocked() throws IOException {
        Set<String> used = new HashSet<>();
        Path snaps = store.resolve("snapshots");
        if (Files.isDirectory(snaps)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(snaps)) {
                for (Path child : ds) {
                    try {
                        for (Entry entry : readEntries(child)) used.add(entry.sha256());
                    } catch (Exception e) {
                        // 清单读不出来就先不回收，避免把还要用的内容删掉
                        return;
                    }
                }
            }
        }
        Path blobs = store.resolve("blobs");
        if (!Files.isDirectory(blobs)) return;
        List<Path> drop = new ArrayList<>();
        Files.walkFileTree(blobs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!Files.isSymbolicLink(file) && !used.contains(fileName(file))) drop.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        for (Path file : drop) Files.deleteIfExists(file);
    }

    private Snapshot readMeta(Path dir) {
        Path meta = dir.resolve("meta.json");
        if (!Files.isRegularFile(meta)) return null;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
            String id = text(obj, "id", fileName(dir));
            if (!id.equals(fileName(dir)) || !safeId(id)) return null;
            return new Snapshot(id, obj.get("createdAt").getAsLong(), text(obj, "label", "now"),
                    obj.get("files").getAsInt(), obj.get("logicalBytes").getAsLong(),
                    obj.get("storedBytes").getAsLong(),
                    obj.has("skipped") ? obj.get("skipped").getAsInt() : 0);
        } catch (Exception e) {
            return null;
        }
    }

    private List<Entry> readEntries(Path dir) throws IOException {
        Path manifest = dir.resolve("manifest.json");
        if (!Files.isRegularFile(manifest)) return List.of();
        JsonObject obj = JsonParser.parseString(Files.readString(manifest)).getAsJsonObject();
        JsonArray files = obj.getAsJsonArray("files");
        List<Entry> list = new ArrayList<>();
        if (files == null) return list;
        for (JsonElement element : files) {
            JsonObject row = element.getAsJsonObject();
            list.add(new Entry(
                    text(row, "scope", ""),
                    text(row, "root", ""),
                    text(row, "path", ""),
                    row.has("size") ? row.get("size").getAsLong() : 0L,
                    text(row, "sha256", ""),
                    !row.has("tree") || row.get("tree").getAsBoolean(),
                    ""
            ));
        }
        return list;
    }

    private static JsonObject metaJson(Snapshot snap) {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", snap.id());
        obj.addProperty("createdAt", snap.createdAt());
        obj.addProperty("label", snap.label());
        obj.addProperty("files", snap.files());
        obj.addProperty("logicalBytes", snap.logicalBytes());
        obj.addProperty("storedBytes", snap.storedBytes());
        obj.addProperty("skipped", snap.skipped());
        return obj;
    }

    private Path snapshotDir(String id) {
        requireId(id);
        Path dir = store.resolve("snapshots").resolve(id).normalize();
        if (!dir.startsWith(store.resolve("snapshots"))) throw new IllegalArgumentException("snapshot");
        return dir;
    }

    private Path blobPath(String sha256) {
        if (!isSha256(sha256)) throw new IllegalArgumentException("hash");
        return store.resolve("blobs").resolve(sha256.substring(0, 2)).resolve(sha256);
    }

    private String newId(long millis, String prefix) throws IOException {
        String base = prefix + ID_TIME.format(Instant.ofEpochMilli(millis));
        String id = base;
        int n = 2;
        while (Files.exists(store.resolve("snapshots").resolve(id))) {
            id = base + "-" + n;
            n++;
        }
        requireId(id);
        return id;
    }

    private static void writeJson(Path file, JsonObject obj) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(tmp, obj.toString(), StandardCharsets.UTF_8);
        try {
            moveReplace(tmp, file);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveReplace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!Files.isSymbolicLink(file)) Files.deleteIfExists(file);
                else Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static Settings sanitize(Settings in) {
        Settings out = in == null ? new Settings() : in;
        if (intervalMillis(out.schedule) < 0) out.schedule = "manual";
        if (!out.schedule.equals("manual") && !out.schedule.equals("hourly")
                && !out.schedule.equals("daily") && !out.schedule.equals("weekly")) {
            out.schedule = "manual";
        }
        if (out.keep < 1) out.keep = 1;
        if (out.keep > 200) out.keep = 200;
        return out;
    }

    private static long intervalMillis(String schedule) {
        if (schedule == null) return -1L;
        return switch (schedule) {
            case "manual" -> 0L;
            case "hourly" -> 3_600_000L;
            case "daily" -> 86_400_000L;
            case "weekly" -> 7L * 86_400_000L;
            default -> -1L;
        };
    }

    private static String knownLabel(String label, String fallback) {
        if ("now".equals(label) || "auto".equals(label) || "before-restore".equals(label)) return label;
        return fallback;
    }

    private static boolean safeId(String id) {
        if (id == null || id.isEmpty() || id.length() > 80) return false;
        if (id.contains("..") || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (!ok) return false;
        }
        return true;
    }

    private static void requireId(String id) {
        if (!safeId(id)) throw new IllegalArgumentException("snapshot");
    }

    static String safeRelative(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("path");
        }
        String slash = path.replace('\\', '/');
        if (slash.startsWith("/") || slash.contains("//")) throw new IllegalArgumentException("path");
        Path norm = Path.of(slash).normalize();
        if (norm.isAbsolute() || norm.getNameCount() == 0) throw new IllegalArgumentException("path");
        for (Path part : norm) {
            String text = part.toString();
            if (text.equals("..") || text.equals(".") || text.isEmpty()) {
                throw new IllegalArgumentException("path");
            }
        }
        return norm.toString().replace('\\', '/');
    }

    private static Path absoluteRoot(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("root");
        Path root = Path.of(text);
        if (!root.isAbsolute()) throw new IllegalArgumentException("root");
        root = root.normalize();
        if (root.getNameCount() < 1) throw new IllegalArgumentException("root");
        return root;
    }

    private static String normalizeRoot(String text) {
        return absoluteRoot(text).toString();
    }

    private static boolean isSha256(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!ok) return false;
        }
        return true;
    }

    private static boolean skipChild(Path child) {
        String lower = fileName(child).toLowerCase(Locale.ROOT);
        if (lower.startsWith(".")) return true;
        if (lower.endsWith(".staging") || lower.endsWith(".bak") || lower.endsWith(".tmp")) return true;
        return SKIP_DIRS.contains(lower);
    }

    private static boolean skipDir(Path dir) {
        return SKIP_DIRS.contains(fileName(dir).toLowerCase(Locale.ROOT));
    }

    private static boolean skipFile(Path file) {
        return SKIP_NAMES.contains(fileName(file).toLowerCase(Locale.ROOT));
    }

    /** 只允许在这些目录里清理“备份之后新增的文件”，避免误删整个启动器目录。 */
    private static boolean extraRootAllowed(Path root) {
        String name = fileName(root).toLowerCase(Locale.ROOT);
        return name.equals("saves") || name.equals("mods") || name.equals("config")
                || name.equals("resourcepacks") || name.equals("shaderpacks");
    }

    private static boolean isRealDir(Path dir) {
        return Files.isDirectory(dir) && !Files.isSymbolicLink(dir);
    }

    private static String fileName(Path path) {
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static String text(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        return obj.get(key).getAsString();
    }

    private static boolean bool(JsonObject obj, String key, boolean fallback) {
        if (obj == null || !obj.has(key)) return fallback;
        return obj.get(key).getAsBoolean();
    }

    private static Map<String, Entry> index(List<Entry> entries) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry entry : entries) map.put(key(entry.root(), entry.path()), entry);
        return map;
    }

    private static String key(String root, String path) {
        return root + "\n" + path;
    }

    private static Entry copy(Entry entry, String change) {
        return new Entry(entry.scope(), entry.root(), entry.path(), entry.size(), entry.sha256(), entry.tree(), change);
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] raw) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[raw.length * 2];
        for (int i = 0; i < raw.length; i++) {
            int v = raw[i] & 0xff;
            out[i * 2] = digits[v >>> 4];
            out[i * 2 + 1] = digits[v & 0x0f];
        }
        return new String(out);
    }

    private static final class Source {
        final String scope;
        final Path dir;
        final boolean tree;
        final Set<String> onlyNames;

        Source(String scope, Path dir, boolean tree, Set<String> onlyNames) {
            this.scope = scope;
            this.dir = dir;
            this.tree = tree;
            this.onlyNames = onlyNames;
        }
    }

    private static final class Pending {
        final String scope;
        final Path root;
        final String relative;
        final long size;
        final boolean tree;
        final Path file;

        Pending(String scope, Path root, String relative, long size, boolean tree, Path file) {
            this.scope = scope;
            this.root = root;
            this.relative = relative;
            this.size = size;
            this.tree = tree;
            this.file = file;
        }
    }

    private static final class Stored {
        final String sha256;
        final boolean created;

        Stored(String sha256, boolean created) {
            this.sha256 = sha256;
            this.created = created;
        }
    }
}
