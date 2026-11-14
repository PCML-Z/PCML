package com.pmcl.core.migration;

import com.pmcl.core.i18n.I18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 把游戏目录搬到另一块磁盘，并在原来的位置留下指向新位置的链接。
 * <p>
 * 只搬版本、依赖库、资源、实例、运行时和存档这类游戏数据。设置、账号和插件留在本机。
 * 复制核对完成之前不改原目录。只删除带有本次迁移标记的副本。
 */
public final class GameDriveRelocation {

    public static final List<String> DIRS = List.of(
            "versions", "libraries", "assets", "instances", "runtimes",
            "mods", "resourcepacks", "shaderpacks", "texturepacks",
            "saves", "screenshots", "resources", "downloads", "crash-reports"
    );
    private static final Set<String> DIR_SET = Set.copyOf(DIRS);
    private static final String FOLDER = "PMCL";
    private static final String MARKER = ".pmcl-drive";
    private static final String MAGIC = "pmcl-game-drive";
    private static final String ASIDE = ".pmcl-aside";
    private static final String INCOMING = ".pmcl-incoming";
    private static final long MARGIN = 256L * 1024 * 1024;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    private final Path workDir;
    private final Usable usable;
    /** 测试在建立链接前抛出，用来确认原目录会被放回。 */
    Step beforeLink;

    public GameDriveRelocation(Path workDir) {
        this(workDir, path -> Files.getFileStore(path).getUsableSpace());
    }

    GameDriveRelocation(Path workDir, Usable usable) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.usable = usable;
    }

    public static final class Cancelled extends IOException {
        public Cancelled() {
            super(I18n.t("drive.cancelled"));
        }
    }

    public static final class Volume {
        private final String name;
        private final Path root;
        private final long usableBytes;

        public Volume(String name, Path root, long usableBytes) {
            this.name = name;
            this.root = root;
            this.usableBytes = usableBytes;
        }

        public String getName() { return name; }
        public Path getRoot() { return root; }
        public long getUsableBytes() { return usableBytes; }
    }

    public static final class Placement {
        private final boolean onExternal;
        private final boolean available;
        private final String volumeName;
        private final Path volumeRoot;
        private final long bytes;
        private final int localDirs;
        private final int linkedDirs;

        Placement(boolean onExternal, boolean available, String volumeName, Path volumeRoot,
                  long bytes, int localDirs, int linkedDirs) {
            this.onExternal = onExternal;
            this.available = available;
            this.volumeName = volumeName;
            this.volumeRoot = volumeRoot;
            this.bytes = bytes;
            this.localDirs = localDirs;
            this.linkedDirs = linkedDirs;
        }

        public boolean isOnExternal() { return onExternal; }
        public boolean isAvailable() { return available; }
        public String getVolumeName() { return volumeName; }
        public Path getVolumeRoot() { return volumeRoot; }
        public long getBytes() { return bytes; }
        public int getLocalDirs() { return localDirs; }
        public int getLinkedDirs() { return linkedDirs; }
    }

    public static final class Progress {
        private final long copied;
        private final long total;
        private final String current;

        public Progress(long copied, long total, String current) {
            this.copied = copied;
            this.total = total;
            this.current = current;
        }

        public long getCopied() { return copied; }
        public long getTotal() { return total; }
        public String getCurrent() { return current; }
    }

    @FunctionalInterface
    interface Usable {
        long of(Path path) throws IOException;
    }

    @FunctionalInterface
    interface Step {
        void run() throws IOException;
    }

    public Placement inspect() {
        long bytes = 0;
        int local = 0;
        int linked = 0;
        boolean available = true;
        Path volume = null;
        String volumeName = null;
        for (String name : DIRS) {
            Path path = workDir.resolve(name);
            try {
                if (isOwnedGameLink(path, workDir)) {
                    linked++;
                    Path target = linkTarget(path);
                    bytes += census(path.toRealPath()).bytes;
                    Path root = volumeRoot(target);
                    if (volume == null) {
                        volume = root;
                        volumeName = volumeLabel(root);
                    }
                    if (!Files.isDirectory(target)) available = false;
                } else if (Files.isDirectory(path) && !Files.isSymbolicLink(path)) {
                    local++;
                    bytes += census(path).bytes;
                } else if (Files.isSymbolicLink(path)) {
                    available = false;
                    linked++;
                    Path target = linkTarget(path);
                    if (volume == null) {
                        volume = volumeRoot(target);
                        volumeName = volumeLabel(volume);
                    }
                }
            } catch (IOException e) {
                available = false;
            }
        }
        boolean external = linked > 0 && local == 0 && available && volume != null;
        return new Placement(external, available || linked == 0, volumeName, volume, bytes, local, linked);
    }

    public List<Volume> externalVolumes() {
        List<Volume> out = new ArrayList<>();
        try {
            Path home = Path.of(System.getProperty("user.home", ""));
            FileStore homeStore = Files.getFileStore(home);
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            FileStore systemStore = null;
            if (!os.contains("win")) {
                try {
                    systemStore = Files.getFileStore(Path.of("/"));
                } catch (IOException ignored) {
                    systemStore = null;
                }
            }
            for (Path candidate : volumeCandidates()) {
                try {
                    if (!Files.isDirectory(candidate)) continue;
                    FileStore store = Files.getFileStore(candidate);
                    String name = candidate.getFileName() == null
                            ? candidate.toString() : candidate.getFileName().toString();
                    boolean writable = Files.isWritable(candidate) && !store.isReadOnly();
                    if (!includeVolume(name, store.equals(homeStore),
                            systemStore != null && store.equals(systemStore),
                            writable, store.isReadOnly(), store.getUsableSpace())) {
                        continue;
                    }
                    out.add(new Volume(name, candidate.toAbsolutePath().normalize(), store.getUsableSpace()));
                } catch (IOException ignored) {
                    // 拔盘的瞬间跳过这一项
                }
            }
        } catch (IOException ignored) {
            return List.of();
        }
        out.sort(Comparator.comparing(Volume::getName));
        return out;
    }

    public void moveTo(Path volumeRoot, Consumer<Progress> progress) throws IOException {
        if (!RUNNING.compareAndSet(false, true)) throw new IOException(I18n.t("drive.busy"));
        try {
            recoverAll();
            Path volume = volumeRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(volume)) throw new IOException(I18n.t("drive.not_a_directory"));
            Path realVolume = volume.toRealPath();
            if (!Files.isWritable(realVolume)) throw new IOException(I18n.t("drive.read_only"));
            Path pmcl = realVolume.resolve(FOLDER);
            Path workReal = Files.exists(workDir) ? workDir.toRealPath() : workDir;
            if (pmcl.startsWith(workReal) || workReal.startsWith(pmcl)) {
                throw new IOException(I18n.t("drive.target_inside"));
            }
            List<String> copy = new ArrayList<>();
            List<String> linkOnly = new ArrayList<>();
            long total = 0;
            boolean any = false;
            for (String name : DIRS) {
                Path source = workDir.resolve(name);
                if (!Files.exists(source) && !Files.isSymbolicLink(source)) continue;
                any = true;
                Path finalDest = pmcl.resolve(name);
                if (isOwnedGameLink(source, workDir) && linkTarget(source).equals(finalDest.normalize())) continue;
                Path real = realDir(source);
                if (!Files.isDirectory(real)) throw new IOException(I18n.t("drive.not_a_directory"));
                if (Files.isDirectory(finalDest) && !Files.isSymbolicLink(finalDest) && mirrorEquals(real, finalDest)) {
                    linkOnly.add(name);
                } else {
                    copy.add(name);
                    total += census(real).bytes;
                }
            }
            if (!any) throw new IOException(I18n.t("drive.no_data"));
            if (copy.isEmpty() && linkOnly.isEmpty()) return;
            long free = usable.of(realVolume);
            if (free < total + MARGIN) {
                throw new IOException(I18n.t("drive.no_space", formatBytes(total + MARGIN), formatBytes(free)));
            }
            ensureMarker(pmcl);
            Progressor reporter = new Progressor(total, progress);
            for (String name : copy) {
                checkCancel();
                relocateOne(name, pmcl, reporter);
            }
            for (String name : linkOnly) {
                checkCancel();
                swapToLink(name, workDir.resolve(name), pmcl.resolve(name).toAbsolutePath().normalize());
            }
            reporter.finish();
        } finally {
            RUNNING.set(false);
        }
    }

    public void moveHome(Consumer<Progress> progress) throws IOException {
        if (!RUNNING.compareAndSet(false, true)) throw new IOException(I18n.t("drive.busy"));
        try {
            recoverAll();
            List<String> todo = new ArrayList<>();
            long total = 0;
            for (String name : DIRS) {
                Path source = workDir.resolve(name);
                if (!Files.isSymbolicLink(source)) continue;
                if (!Files.isDirectory(source)) throw new IOException(I18n.t("drive.offline"));
                if (!isOwnedGameLink(source, workDir)) continue;
                todo.add(name);
                total += census(source.toRealPath()).bytes;
            }
            if (todo.isEmpty()) throw new IOException(I18n.t("drive.not_external"));
            long free = usable.of(workDir);
            if (free < total + MARGIN) {
                throw new IOException(I18n.t("drive.no_space", formatBytes(total + MARGIN), formatBytes(free)));
            }
            Progressor reporter = new Progressor(total, progress);
            for (String name : todo) {
                checkCancel();
                bringHome(name, reporter);
            }
            reporter.finish();
        } finally {
            RUNNING.set(false);
        }
    }

    /** 原位置上的游戏目录链接，且目标落在带本数据目录标记的 PMCL 文件夹里。 */
    public static boolean isOwnedGameLink(Path path, Path workDir) {
        if (path == null || workDir == null || !Files.isSymbolicLink(path)) return false;
        Path parent = path.getParent();
        if (parent == null) return false;
        if (!parent.toAbsolutePath().normalize().equals(workDir.toAbsolutePath().normalize())) return false;
        String name = path.getFileName().toString();
        if (!DIR_SET.contains(name)) return false;
        try {
            Path target = linkTarget(path);
            if (target.getFileName() == null || !target.getFileName().toString().equals(name)) return false;
            Path folder = target.getParent();
            return folder != null && owns(folder, workDir.toAbsolutePath().normalize());
        } catch (IOException e) {
            return false;
        }
    }

    /** 解析后的路径是否落在本次迁移的 PMCL 文件夹里。 */
    public static boolean containsOwnedTarget(Path real, Path workDir) {
        if (real == null || workDir == null) return false;
        Path norm = real.toAbsolutePath().normalize();
        Path home = workDir.toAbsolutePath().normalize();
        for (String name : DIRS) {
            Path link = home.resolve(name);
            if (!isOwnedGameLink(link, home)) continue;
            try {
                Path folder = linkTarget(link).getParent();
                if (folder != null && Files.isDirectory(folder)
                        && norm.startsWith(folder.toRealPath())) return true;
            } catch (IOException ignored) {
                // 盘没接上就不当作已迁移
            }
        }
        return false;
    }

    /** 不是本次迁移留下的链接时，返回原路径。 */
    public static Path followOwnedLink(Path path, Path workDir) {
        if (!isOwnedGameLink(path, workDir)) return path;
        try {
            return path.toRealPath();
        } catch (IOException e) {
            return path;
        }
    }

    static boolean includeVolume(String name, boolean sameAsHome, boolean sameAsSystem,
                                 boolean writable, boolean readOnly, long usableBytes) {
        if (name == null || name.isBlank() || name.startsWith(".")) return false;
        if (systemVolumeName(name)) return false;
        if (sameAsHome || sameAsSystem || !writable || readOnly || usableBytes <= 0) return false;
        return true;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, unit == 0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private void relocateOne(String name, Path pmcl, Progressor progress) throws IOException {
        Path source = workDir.resolve(name);
        Path finalDest = pmcl.resolve(name).toAbsolutePath().normalize();
        if (isOwnedGameLink(source, workDir) && linkTarget(source).equals(finalDest)) return;
        Path real = realDir(source);
        progress.name(name);
        Path incoming = pmcl.resolve(name + INCOMING);
        if (Files.exists(incoming) || Files.isSymbolicLink(incoming)) deleteTree(incoming);
        try {
            copyTree(real, incoming, progress);
            assertMirror(real, incoming);
            if (Files.exists(finalDest) || Files.isSymbolicLink(finalDest)) deleteTree(finalDest);
            Files.move(incoming, finalDest);
        } catch (Exception e) {
            if (Files.exists(incoming) || Files.isSymbolicLink(incoming)) {
                try {
                    deleteTree(incoming);
                } catch (IOException ignored) {
                    // 保留最初的失败原因
                }
            }
            throw asIO(e);
        }
        swapToLink(name, source, finalDest);
    }

    private void swapToLink(String name, Path source, Path finalDest) throws IOException {
        if (isOwnedGameLink(source, workDir) && linkTarget(source).equals(finalDest)) return;
        Path aside = workDir.resolve(name + ASIDE);
        Path oldTarget = Files.isSymbolicLink(source) ? linkTarget(source) : null;
        if (Files.exists(aside) || Files.isSymbolicLink(aside)) deleteTree(aside);
        try {
            if (Files.isSymbolicLink(source)) Files.delete(source);
            else Files.move(source, aside);
            fireBeforeLink();
            createLink(source, finalDest);
            if (!linkPointsAt(source, finalDest)) throw new IOException(I18n.t("drive.link_failed"));
            if (Files.exists(aside) || Files.isSymbolicLink(aside)) deleteTree(aside);
            if (oldTarget != null && !oldTarget.equals(finalDest)) deleteOwnedCopy(oldTarget);
        } catch (Exception e) {
            restoreSource(source, aside, oldTarget);
            throw asIO(e);
        }
    }

    private void bringHome(String name, Progressor progress) throws IOException {
        Path source = workDir.resolve(name);
        if (!isOwnedGameLink(source, workDir)) return;
        Path real = source.toRealPath();
        Path oldTarget = linkTarget(source);
        progress.name(name);
        Path incoming = workDir.resolve(name + INCOMING);
        if (Files.exists(incoming) || Files.isSymbolicLink(incoming)) deleteTree(incoming);
        try {
            copyTree(real, incoming, progress);
            assertMirror(real, incoming);
        } catch (Exception e) {
            if (Files.exists(incoming) || Files.isSymbolicLink(incoming)) {
                try {
                    deleteTree(incoming);
                } catch (IOException ignored) {
                    // 保留最初的失败原因
                }
            }
            throw asIO(e);
        }
        Path aside = workDir.resolve(name + ASIDE);
        try {
            Files.move(source, aside);
            fireBeforeLink();
            Files.move(incoming, source);
            deleteTree(aside);
            deleteOwnedCopy(oldTarget);
            if (oldTarget.getParent() != null) deleteFolderIfEmpty(oldTarget.getParent());
        } catch (Exception e) {
            if (!Files.exists(source) && !Files.isSymbolicLink(source) && Files.exists(aside)) {
                Files.move(aside, source);
            }
            if ((Files.exists(incoming) || Files.isSymbolicLink(incoming))
                    && (Files.exists(source) || Files.isSymbolicLink(source))) {
                try {
                    deleteTree(incoming);
                } catch (IOException ignored) {
                    // 链接已经放回，副本留在旁边也比丢掉原文件好
                }
            }
            throw asIO(e);
        }
    }

    private void restoreSource(Path source, Path aside, Path oldTarget) {
        try {
            if (Files.isSymbolicLink(source)) Files.delete(source);
            if (!Files.exists(source) && !Files.isSymbolicLink(source)) {
                if (Files.exists(aside) || Files.isSymbolicLink(aside)) Files.move(aside, source);
                else if (oldTarget != null) createLink(source, oldTarget);
            }
        } catch (IOException ignored) {
            // 恢复失败时留下 aside，下次启动再接回
        }
    }

    private void recoverAll() throws IOException {
        for (String name : DIRS) {
            Path source = workDir.resolve(name);
            Path aside = workDir.resolve(name + ASIDE);
            if ((Files.exists(aside) || Files.isSymbolicLink(aside))
                    && !Files.exists(source) && !Files.isSymbolicLink(source)) {
                Files.move(aside, source);
            }
        }
    }

    private void copyTree(Path source, Path dest, Progressor progress) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                checkCancel();
                Files.createDirectories(dest.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                checkCancel();
                Path target = dest.resolve(source.relativize(file));
                if (attrs.isSymbolicLink()) copyLink(file, target, progress);
                else copyFile(file, target, attrs.size(), progress);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw exc;
            }
        });
    }

    private void copyFile(Path file, Path target, long size, Progressor progress) throws IOException {
        Files.createDirectories(target.getParent());
        if (size < 1024 * 1024) {
            Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            progress.add(size);
            return;
        }
        try (InputStream in = Files.newInputStream(file);
             OutputStream out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buf = new byte[1024 * 1024];
            int n;
            while ((n = in.read(buf)) >= 0) {
                checkCancel();
                out.write(buf, 0, n);
                progress.add(n);
            }
        }
    }

    private void copyLink(Path link, Path target, Progressor progress) throws IOException {
        Path raw = Files.readSymbolicLink(link);
        try {
            if (Files.exists(target) || Files.isSymbolicLink(target)) Files.delete(target);
            Files.createSymbolicLink(target, raw);
            return;
        } catch (IOException ignored) {
            // 目标盘不支持符号链接时，改存链接指向的内容
        }
        Path resolved = raw.isAbsolute() ? raw : link.getParent().resolve(raw).normalize();
        if (Files.isDirectory(resolved)) copyTree(resolved, target, progress);
        else if (Files.isRegularFile(resolved)) copyFile(resolved, target, Files.size(resolved), progress);
    }

    private void assertMirror(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                if (!Files.isDirectory(dest.resolve(rel))) throw new IOException(I18n.t("drive.mismatch", dest));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path target = dest.resolve(source.relativize(file));
                if (attrs.isSymbolicLink()) {
                    if (!Files.exists(target) && !Files.isSymbolicLink(target)) {
                        throw new IOException(I18n.t("drive.mismatch", dest));
                    }
                    return FileVisitResult.CONTINUE;
                }
                if (!Files.isRegularFile(target) || Files.size(target) != attrs.size()) {
                    throw new IOException(I18n.t("drive.mismatch", dest));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                throw exc;
            }
        });
    }

    private boolean mirrorEquals(Path source, Path dest) {
        try {
            assertMirror(source, dest);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void createLink(Path link, Path target) throws IOException {
        Path abs = target.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(link)) Files.delete(link);
        try {
            Files.createSymbolicLink(link, abs);
        } catch (IOException first) {
            if (!isWindows()) throw new IOException(I18n.t("drive.link_failed"), first);
            Process process = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), abs.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Cancelled();
            }
            if (code != 0 || !Files.exists(link)) {
                throw new IOException(I18n.t("drive.link_failed") + " " + output.trim(), first);
            }
        }
        if (!linkPointsAt(link, abs)) throw new IOException(I18n.t("drive.link_failed"));
    }

    private static boolean linkPointsAt(Path link, Path target) throws IOException {
        if (!Files.exists(link) && !Files.isSymbolicLink(link)) return false;
        Path got = Files.isSymbolicLink(link) ? linkTarget(link) : link.toRealPath();
        return got.equals(target.toAbsolutePath().normalize());
    }

    private static Path linkTarget(Path link) throws IOException {
        Path target = Files.readSymbolicLink(link);
        if (!target.isAbsolute()) target = link.getParent().resolve(target);
        return target.toAbsolutePath().normalize();
    }

    private void ensureMarker(Path pmcl) throws IOException {
        if (Files.isSymbolicLink(pmcl)) throw new IOException(I18n.t("drive.occupied"));
        Path marker = pmcl.resolve(MARKER);
        if (Files.isSymbolicLink(marker)) throw new IOException(I18n.t("drive.occupied"));
        if (Files.isRegularFile(marker)) {
            if (owns(pmcl, workDir)) return;
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (!lines.isEmpty() && MAGIC.equals(lines.get(0))) throw new IOException(I18n.t("drive.marker_conflict"));
            throw new IOException(I18n.t("drive.occupied"));
        }
        if (Files.isDirectory(pmcl)) {
            try (var stream = Files.list(pmcl)) {
                if (stream.findAny().isPresent()) throw new IOException(I18n.t("drive.occupied"));
            }
        }
        Files.createDirectories(pmcl);
        Files.writeString(marker, MAGIC + "\n" + workDir + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW);
    }

    private static boolean owns(Path pmcl, Path workDir) {
        Path marker = pmcl.resolve(MARKER);
        if (!Files.isRegularFile(marker) || Files.isSymbolicLink(marker)) return false;
        try {
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            if (lines.size() < 2 || !MAGIC.equals(lines.get(0))) return false;
            return Path.of(lines.get(1).trim()).toAbsolutePath().normalize().equals(workDir.toAbsolutePath().normalize());
        } catch (IOException e) {
            return false;
        }
    }

    private void deleteOwnedCopy(Path path) throws IOException {
        if (path == null) return;
        Path abs = path.toAbsolutePath().normalize();
        if (!deletable(abs)) return;
        deleteTree(abs);
    }

    private static Path realDir(Path source) throws IOException {
        try {
            return source.toRealPath();
        } catch (IOException e) {
            if (Files.isSymbolicLink(source)) throw new IOException(I18n.t("drive.offline"), e);
            throw e;
        }
    }

    private void deleteFolderIfEmpty(Path pmcl) throws IOException {
        if (!owns(pmcl, workDir) || Files.isSymbolicLink(pmcl)) return;
        try (var stream = Files.list(pmcl)) {
            if (stream.anyMatch(path -> !MARKER.equals(path.getFileName().toString()))) return;
        }
        Path marker = pmcl.resolve(MARKER);
        if (Files.isSymbolicLink(marker)) return;
        Files.deleteIfExists(marker);
        try (var stream = Files.list(pmcl)) {
            if (stream.findAny().isEmpty()) Files.delete(pmcl);
        }
    }

    /** 只删除迁移自己的副本：工作目录旁的临时目录，或带标记的 PMCL 文件夹里的游戏目录。 */
    private void deleteTree(Path root) throws IOException {
        Path abs = root.toAbsolutePath().normalize();
        if (!deletable(abs)) throw new IOException(I18n.t("drive.link_failed"));
        if (Files.isSymbolicLink(abs)) {
            Files.delete(abs);
            return;
        }
        if (!Files.exists(abs)) return;
        Files.walkFileTree(abs, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean deletable(Path abs) {
        if (abs.equals(workDir) || abs.getParent() == null || abs.getFileName() == null) return false;
        String name = abs.getFileName().toString();
        Path parent = abs.getParent().toAbsolutePath().normalize();
        if (parent.equals(workDir)) return name.endsWith(ASIDE) || name.endsWith(INCOMING);
        if (parent.getFileName() != null && FOLDER.equals(parent.getFileName().toString()) && owns(parent, workDir)) {
            String base = name.endsWith(INCOMING) ? name.substring(0, name.length() - INCOMING.length()) : name;
            return DIR_SET.contains(base);
        }
        return false;
    }

    private static Census census(Path root) throws IOException {
        Census census = new Census();
        if (!Files.exists(root) && !Files.isSymbolicLink(root)) return census;
        Path start = Files.isSymbolicLink(root) ? root.toRealPath() : root;
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isSymbolicLink()) census.bytes += attrs.size();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        return census;
    }

    private void fireBeforeLink() throws IOException {
        if (beforeLink != null) beforeLink.run();
    }

    private static void checkCancel() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new Cancelled();
    }

    private static IOException asIO(Exception e) {
        if (e instanceof IOException io) return io;
        return new IOException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean systemVolumeName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("macintosh hd")
                || n.equals("macintosh hd - data")
                || n.equals("recovery")
                || n.equals("preboot")
                || n.equals("vm")
                || n.equals("update")
                || n.equals("mobilebackups")
                || n.startsWith("com.apple.timemachine");
    }

    private static Path volumeRoot(Path target) {
        Path abs = target.toAbsolutePath().normalize();
        Path root = abs.getRoot();
        if (root == null) return abs.getParent();
        for (int i = 0; i < abs.getNameCount(); i++) {
            if (FOLDER.equals(abs.getName(i).toString())) {
                if (i == 0) return root;
                return root.resolve(abs.subpath(0, i));
            }
        }
        return root;
    }

    private static String volumeLabel(Path root) {
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }

    private static List<Path> volumeCandidates() throws IOException {
        List<Path> out = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            java.io.File[] roots = java.io.File.listRoots();
            if (roots != null) {
                for (java.io.File root : roots) out.add(root.toPath());
            }
            return out;
        }
        if (os.contains("mac")) {
            addChildren(Path.of("/Volumes"), out);
            return out;
        }
        String user = System.getProperty("user.name", "");
        addChildren(Path.of("/media").resolve(user), out);
        addChildren(Path.of("/run/media").resolve(user), out);
        addChildren(Path.of("/mnt"), out);
        addChildren(Path.of("/Volumes"), out);
        return out;
    }

    private static void addChildren(Path parent, List<Path> out) throws IOException {
        if (!Files.isDirectory(parent)) return;
        try (var stream = Files.list(parent)) {
            stream.forEach(out::add);
        }
    }

    private static final class Census {
        long bytes;
    }

    private static final class Progressor {
        private final long total;
        private final Consumer<Progress> callback;
        private long copied;
        private String name = "";
        private long lastEmit;

        Progressor(long total, Consumer<Progress> callback) {
            this.total = total;
            this.callback = callback;
        }

        void name(String name) {
            this.name = name;
            emit(true);
        }

        void add(long bytes) {
            copied += Math.max(0, bytes);
            emit(false);
        }

        void finish() {
            if (copied < total) copied = total;
            emit(true);
        }

        private void emit(boolean force) {
            if (callback == null) return;
            long now = System.nanoTime();
            if (!force && copied < total && now - lastEmit < 200_000_000L) return;
            lastEmit = now;
            callback.accept(new Progress(copied, total, name));
        }
    }
}
