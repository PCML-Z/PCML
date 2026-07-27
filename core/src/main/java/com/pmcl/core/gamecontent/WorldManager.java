package com.pmcl.core.gamecontent;

import com.pmcl.core.nbt.NbtReader;
import com.pmcl.core.nbt.NbtTag;

import java.io.IOException;
import java.io.File;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 世界 / 存档管理：扫描、备份（zip）、恢复、导入。
 * <p>
 * Minecraft 的 saves 目录下每个子目录即一个世界，含 level.dat。
 * 备份格式：将整个世界目录压缩为 zip 到 backups/ 目录。
 */
public final class WorldManager {

    private final Path savesDir;
    private final Path backupsDir;
    /** 世界大小缓存：key=世界目录路径, value=[mtime, size] */
    private final Map<Path, long[]> sizeCache = new ConcurrentHashMap<>();
    /** 按世界名串行化备份/恢复操作，防止并发导致数据损坏 */
    private final Map<String, Object> worldLocks = new ConcurrentHashMap<>();

    public WorldManager(Path workDir) {
        this.savesDir = workDir.resolve("saves");
        this.backupsDir = workDir.resolve("backups").resolve("worlds");
    }

    /** 获取（或创建）指定世界的操作锁 */
    private Object lockFor(String worldName) {
        return worldLocks.computeIfAbsent(worldName, k -> new Object());
    }

    public Path getSavesDir() { return savesDir; }
    public Path getBackupsDir() { return backupsDir; }

    /** 单个世界信息 */
    public static final class WorldInfo {
        private String name;
        private Path dir;
        private long lastModified;
        private long sizeBytes;
        private String source;
        /** level.dat 中的显示名（LevelName），空则 UI 回退用文件夹名 */
        private String displayName = "";
        /** 游戏模式：0=生存 1=创造 2=冒险 3=旁观，-1=未知 */
        private int gameType = -1;
        /** 难度：0=和平 1=简单 2=普通 3=困难，-1=未知 */
        private int difficulty = -1;
        private boolean hardcoreFlag;
        /** 世界种子；Long.MIN_VALUE 表示未知 */
        private long seed = Long.MIN_VALUE;
        private boolean iconPresent;

        public WorldInfo(String name, Path dir, long lastModified, long sizeBytes) {
            this(name, dir, lastModified, sizeBytes, "PMCL");
        }
        public WorldInfo(String name, Path dir, long lastModified, long sizeBytes, String source) {
            this.name = name; this.dir = dir;
            this.lastModified = lastModified; this.sizeBytes = sizeBytes;
            this.source = source;
        }
        public String getName() { return name; }
        public Path getDir() { return dir; }
        public long getLastModified() { return lastModified; }
        public long getSizeBytes() { return sizeBytes; }
        public String getSource() { return source; }
        public String getDisplayName() { return displayName; }
        public int getGameType() { return gameType; }
        public int getDifficulty() { return difficulty; }
        public boolean isHardcore() { return hardcoreFlag; }
        public long getSeed() { return seed; }
        public boolean hasIcon() { return iconPresent; }

        void applyMeta(String displayName, int gameType, int difficulty, boolean hardcore, long seed, boolean hasIcon) {
            this.displayName = displayName != null ? displayName : "";
            this.gameType = gameType;
            this.difficulty = difficulty;
            this.hardcoreFlag = hardcore;
            this.seed = seed;
            this.iconPresent = hasIcon;
        }
    }

    /** 扫描默认 saves 目录（~/.pmcl/saves） */
    public List<WorldInfo> listWorlds() throws IOException {
        return listWorlds(savesDir, "PMCL");
    }

    /**
     * 扫描指定 saves 目录下的所有世界。
     * @param savesDir 某个 saves 目录（如 ~/.pmcl/saves、整合包 versions/&lt;id&gt;/saves、外部启动器 saves）
     * @param source   来源标签（用于 UI 区分世界归属）
     */
    public List<WorldInfo> listWorlds(Path savesDir, String source) throws IOException {
        List<WorldInfo> result = new ArrayList<>();
        if (!Files.isDirectory(savesDir)) return result;
        try (Stream<Path> stream = Files.list(savesDir)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                Path levelDat = dir.resolve("level.dat");
                if (!Files.exists(levelDat)) return;
                try {
                    long mtime = Files.getLastModifiedTime(levelDat).toMillis();
                    // 缓存命中：level.dat mtime 未变则复用上次计算的大小
                    long[] cached = sizeCache.get(dir);
                    long size;
                    if (cached != null && cached[0] == mtime) {
                        size = cached[1];
                    } else {
                        size = dirSize(dir);
                        sizeCache.put(dir, new long[]{mtime, size});
                    }
                    WorldInfo info = new WorldInfo(dir.getFileName().toString(), dir, mtime, size, source);
                    fillLevelMeta(info, levelDat);
                    result.add(info);
                } catch (Throwable ignored) {
                    // 单个世界扫描失败（权限/符号链接/损坏）不应中断其他世界的加载
                }
            });
        }
        return result;
    }

    /**
     * 从 level.dat 读取显示名 / 模式 / 难度 / 硬核 / 种子，并检测 icon.png。
     * 解析失败时保留默认未知值，不抛出。
     */
    private static void fillLevelMeta(WorldInfo info, Path levelDat) {
        boolean hasIcon = Files.isRegularFile(info.getDir().resolve("icon.png"));
        String displayName = "";
        int gameType = -1;
        int difficulty = -1;
        boolean hardcore = false;
        long seed = Long.MIN_VALUE;
        try {
            NbtTag root = NbtReader.read(levelDat);
            NbtTag.CompoundTag data = findDataCompound(root);
            if (data != null) {
                displayName = readString(data, "LevelName");
                gameType = readInt(data, "GameType", -1);
                difficulty = readInt(data, "Difficulty", -1);
                hardcore = readByte(data, "hardcore") != 0
                        || readByte(data, "Hardcore") != 0;
                seed = readLong(data, "RandomSeed", Long.MIN_VALUE);
                if (seed == Long.MIN_VALUE) {
                    NbtTag wgs = data.get("WorldGenSettings");
                    if (wgs instanceof NbtTag.CompoundTag) {
                        seed = readLong((NbtTag.CompoundTag) wgs, "seed", Long.MIN_VALUE);
                    }
                }
            }
        } catch (Throwable ignored) {
            // level.dat 损坏/版本过新：仍展示基础信息
        }
        info.applyMeta(displayName, gameType, difficulty, hardcore, seed, hasIcon);
    }

    private static NbtTag.CompoundTag findDataCompound(NbtTag root) {
        if (!(root instanceof NbtTag.CompoundTag)) return null;
        NbtTag.CompoundTag compound = (NbtTag.CompoundTag) root;
        NbtTag data = compound.get("Data");
        if (data instanceof NbtTag.CompoundTag) return (NbtTag.CompoundTag) data;
        // 少数工具可能直接以 Data 为根
        if (compound.contains("LevelName") || compound.contains("GameType")) return compound;
        return null;
    }

    private static String readString(NbtTag.CompoundTag c, String key) {
        NbtTag t = c.get(key);
        return t instanceof NbtTag.StringTag ? ((NbtTag.StringTag) t).getValue() : "";
    }

    private static int readInt(NbtTag.CompoundTag c, String key, int def) {
        NbtTag t = c.get(key);
        if (t instanceof NbtTag.IntTag) return ((NbtTag.IntTag) t).getValue();
        if (t instanceof NbtTag.ByteTag) return ((NbtTag.ByteTag) t).getValue();
        if (t instanceof NbtTag.ShortTag) return ((NbtTag.ShortTag) t).getValue();
        if (t instanceof NbtTag.LongTag) return (int) ((NbtTag.LongTag) t).getValue();
        return def;
    }

    private static byte readByte(NbtTag.CompoundTag c, String key) {
        NbtTag t = c.get(key);
        if (t instanceof NbtTag.ByteTag) return ((NbtTag.ByteTag) t).getValue();
        if (t instanceof NbtTag.IntTag) return (byte) ((NbtTag.IntTag) t).getValue();
        return 0;
    }

    private static long readLong(NbtTag.CompoundTag c, String key, long def) {
        NbtTag t = c.get(key);
        if (t instanceof NbtTag.LongTag) return ((NbtTag.LongTag) t).getValue();
        if (t instanceof NbtTag.IntTag) return ((NbtTag.IntTag) t).getValue();
        return def;
    }

    /** 备份世界为 zip（按世界名串行化，防止并发备份产生损坏的 zip） */
    public Path backup(WorldInfo world) throws IOException {
        synchronized (lockFor(world.getName())) {
            Files.createDirectories(backupsDir);
            String stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path zip = backupsDir.resolve(world.getName() + "-" + stamp + ".zip");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
                Files.walkFileTree(world.getDir(), new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        // 跳过 Minecraft 运行时锁文件，避免读取正在运行的世界时失败
                        String fileName = file.getFileName().toString();
                        if ("session.lock".equals(fileName)) return FileVisitResult.CONTINUE;
                        String rel = world.getDir().relativize(file).toString().replace(File.separatorChar, '/');
                        zos.putNextEntry(new ZipEntry(rel));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        // 单个文件访问失败（权限/符号链接/占用）不应中断整个备份
                        System.err.println("[WorldManager] 备份时跳过无法访问的文件: " + file + " - " + exc.getMessage());
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
            return zip;
        }
    }

    /** 从 zip 恢复世界（覆盖现有世界，按世界名串行化，防止与并发备份/恢复竞争导致数据丢失） */
    public void restore(Path zipFile, String worldName) throws IOException {
        synchronized (lockFor(worldName)) {
            Path target = savesDir.resolve(worldName).normalize();
            if (!target.startsWith(savesDir)) throw new IOException("非法世界名: " + worldName);
            Files.createDirectories(savesDir);
            // 先解压到临时暂存目录，成功后再替换原世界，避免解压中途失败导致原存档丢失
            Path staging = target.resolveSibling(worldName + ".restoring");
            // 清理上次失败残留的暂存目录
            if (Files.exists(staging)) deleteRecursive(staging);
            try {
                Files.createDirectories(staging);
                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
                    com.pmcl.core.util.SafeZipExtractor.extractStreamSafely(zis, staging, null);
                }
                // 解压成功，原子替换原世界（先备份 target→bak，move staging→target，成功后删 bak，失败恢复）
                Path bak = target.resolveSibling(worldName + ".bak");
                if (Files.exists(target)) {
                    try {
                        Files.move(target, bak, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(target, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                try {
                    try {
                        Files.move(staging, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(staging, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    // 成功后清理备份
                    if (Files.exists(bak)) deleteRecursive(bak);
                } catch (IOException e) {
                    // move 失败：恢复备份
                    try {
                        if (Files.exists(bak)) {
                            try {
                                Files.move(bak, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                                Files.move(bak, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                    } catch (IOException ignored) {}
                    throw e;
                }
            } catch (IOException e) {
                // 解压失败：清理暂存目录，保留原存档不受影响
                try { deleteRecursive(staging); } catch (IOException ignored) {}
                throw e;
            }
        }
    }

    /** 导入世界 zip（与 restore 相同，但目标名取 zip 文件名） */
    public void importWorld(Path zipFile) throws IOException {
        String name = zipFile.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) name = name.substring(0, name.length() - 4);
        restore(zipFile, name);
    }

    /** 删除世界（仅允许删除位于某个 saves/ 下且含 level.dat 的目录） */
    public void delete(WorldInfo world) throws IOException {
        Path dir = assertDeletableWorldDir(world.getDir());
        deleteRecursive(dir);
        sizeCache.remove(world.getDir());
        sizeCache.remove(dir);
    }

    /**
     * 防止任意路径递归删除：目录必须在名为 {@code saves} 的父目录下，
     * 且包含 {@code level.dat}（Minecraft 世界特征文件）。
     */
    static Path assertDeletableWorldDir(Path worldDir) throws IOException {
        if (worldDir == null) throw new IOException("世界目录为空");
        Path dir = worldDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) throw new IOException("不是目录: " + dir);
        boolean underSaves = false;
        for (Path p = dir.getParent(); p != null; p = p.getParent()) {
            Path name = p.getFileName();
            if (name != null && "saves".equalsIgnoreCase(name.toString())) {
                underSaves = true;
                break;
            }
        }
        if (!underSaves) {
            throw new IOException("拒绝删除：路径不在 saves 目录下: " + dir);
        }
        if (!Files.isRegularFile(dir.resolve("level.dat"))) {
            throw new IOException("拒绝删除：缺少 level.dat，不像 Minecraft 世界: " + dir);
        }
        return dir;
    }

    private static long dirSize(Path dir) throws IOException {
        long[] size = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                size[0] += attrs.size();
                return FileVisitResult.CONTINUE;
            }
        });
        return size[0];
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f); return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d); return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 列出已备份的世界 zip */
    public List<Path> listBackups(String worldName) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!Files.isDirectory(backupsDir)) return result;
        try (Stream<Path> stream = Files.list(backupsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(worldName + "-"))
                    .forEach(result::add);
        }
        return result;
    }
}
