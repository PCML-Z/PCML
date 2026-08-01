package com.lash.pmcl.core.gamecontent

import com.lash.pmcl.core.nbt.NbtReader
import com.lash.pmcl.core.nbt.NbtTag
import com.lash.pmcl.core.util.SafeZipExtractor
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Stream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 世界 / 存档管理：扫描、备份（zip）、恢复、导入。
 *
 * Minecraft 的 saves 目录下每个子目录即一个世界，含 level.dat。
 * 备份格式：将整个世界目录压缩为 zip 到 backups/ 目录。
 *
 * Android 版本：从 Java 移植，保留 level.dat 解析（依赖 [NbtReader]）、
 * 世界备份/恢复（三阶段原子替换 + 失败回滚）、session.lock 跳过、
 * ConcurrentHashMap 缓存世界大小、worldLocks 串行化锁。
 */
class WorldManager(workDir: Path) {

    val savesDir: Path = workDir.resolve("saves")
    val backupsDir: Path = workDir.resolve("backups").resolve("worlds")

    /** 世界大小缓存：key=世界目录路径, value=[mtime, size] */
    private val sizeCache: MutableMap<Path, LongArray> = ConcurrentHashMap()
    /** 按世界名串行化备份/恢复操作，防止并发导致数据损坏 */
    private val worldLocks: MutableMap<String, Any> = ConcurrentHashMap()

    /** 获取（或创建）指定世界的操作锁 */
    private fun lockFor(worldName: String): Any =
        worldLocks.computeIfAbsent(worldName) { Any() }

    /** 单个世界信息 */
    class WorldInfo(
        val name: String,
        val dir: Path,
        val lastModified: Long,
        val sizeBytes: Long,
        val source: String = "PMCL"
    ) {
        /** level.dat 中的显示名（LevelName），空则 UI 回退用文件夹名 */
        var displayName: String = ""
            internal set
        /** 游戏模式：0=生存 1=创造 2=冒险 3=旁观，-1=未知 */
        var gameType: Int = -1
            internal set
        /** 难度：0=和平 1=简单 2=普通 3=困难，-1=未知 */
        var difficulty: Int = -1
            internal set
        var hardcore: Boolean = false
            internal set
        /** 世界种子；Long.MIN_VALUE 表示未知 */
        var seed: Long = Long.MIN_VALUE
            internal set
        var hasIcon: Boolean = false
            internal set

        internal fun applyMeta(
            displayName: String?, gameType: Int, difficulty: Int,
            hardcore: Boolean, seed: Long, hasIcon: Boolean
        ) {
            this.displayName = displayName ?: ""
            this.gameType = gameType
            this.difficulty = difficulty
            this.hardcore = hardcore
            this.seed = seed
            this.hasIcon = hasIcon
        }
    }

    /** 扫描默认 saves 目录 */
    @Throws(IOException::class)
    fun listWorlds(): List<WorldInfo> = listWorlds(savesDir, "PMCL")

    /**
     * 扫描指定 saves 目录下的所有世界。
     * @param savesDir 某个 saves 目录
     * @param source   来源标签（用于 UI 区分世界归属）
     */
    @Throws(IOException::class)
    fun listWorlds(savesDir: Path, source: String): List<WorldInfo> {
        val result = ArrayList<WorldInfo>()
        if (!Files.isDirectory(savesDir)) return result
        Files.list(savesDir).use { stream ->
            stream.filter { Files.isDirectory(it) }.forEach { dir ->
                val levelDat = dir.resolve("level.dat")
                if (!Files.exists(levelDat)) return@forEach
                try {
                    val mtime = Files.getLastModifiedTime(levelDat).toMillis()
                    // 缓存命中：level.dat mtime 未变则复用上次计算的大小
                    val cached = sizeCache[dir]
                    val size: Long
                    if (cached != null && cached[0] == mtime) {
                        size = cached[1]
                    } else {
                        size = dirSize(dir)
                        sizeCache[dir] = longArrayOf(mtime, size)
                    }
                    val info = WorldInfo(dir.fileName.toString(), dir, mtime, size, source)
                    fillLevelMeta(info, levelDat)
                    result.add(info)
                } catch (e: Throwable) {
                    // 单个世界扫描失败（权限/符号链接/损坏）不应中断其他世界的加载
                }
            }
        }
        return result
    }

    /**
     * 从 level.dat 读取显示名 / 模式 / 难度 / 硬核 / 种子，并检测 icon.png。
     * 解析失败时保留默认未知值，不抛出。
     */
    private fun fillLevelMeta(info: WorldInfo, levelDat: Path) {
        val hasIcon = Files.isRegularFile(info.dir.resolve("icon.png"))
        var displayName = ""
        var gameType = -1
        var difficulty = -1
        var hardcore = false
        var seed = Long.MIN_VALUE
        try {
            val root = NbtReader.read(levelDat)
            val data = findDataCompound(root)
            if (data != null) {
                displayName = readString(data, "LevelName")
                gameType = readInt(data, "GameType", -1)
                difficulty = readInt(data, "Difficulty", -1)
                hardcore = readByte(data, "hardcore") != 0.toByte()
                    || readByte(data, "Hardcore") != 0.toByte()
                seed = readLong(data, "RandomSeed", Long.MIN_VALUE)
                if (seed == Long.MIN_VALUE) {
                    val wgs = data.get("WorldGenSettings")
                    if (wgs is NbtTag.CompoundTag) {
                        seed = readLong(wgs, "seed", Long.MIN_VALUE)
                    }
                }
            }
        } catch (e: Throwable) {
            // level.dat 损坏/版本过新：仍展示基础信息
        }
        info.applyMeta(displayName, gameType, difficulty, hardcore, seed, hasIcon)
    }

    private fun findDataCompound(root: NbtTag?): NbtTag.CompoundTag? {
        if (root !is NbtTag.CompoundTag) return null
        val data = root.get("Data")
        if (data is NbtTag.CompoundTag) return data
        // 少数工具可能直接以 Data 为根
        if (root.contains("LevelName") || root.contains("GameType")) return root
        return null
    }

    private fun readString(c: NbtTag.CompoundTag, key: String): String {
        val t = c.get(key)
        return if (t is NbtTag.StringTag) t.value else ""
    }

    private fun readInt(c: NbtTag.CompoundTag, key: String, def: Int): Int {
        return when (val t = c.get(key)) {
            is NbtTag.IntTag -> t.value
            is NbtTag.ByteTag -> t.value.toInt()
            is NbtTag.ShortTag -> t.value.toInt()
            is NbtTag.LongTag -> t.value.toInt()
            else -> def
        }
    }

    private fun readByte(c: NbtTag.CompoundTag, key: String): Byte {
        return when (val t = c.get(key)) {
            is NbtTag.ByteTag -> t.value
            is NbtTag.IntTag -> t.value.toByte()
            else -> 0.toByte()
        }
    }

    private fun readLong(c: NbtTag.CompoundTag, key: String, def: Long): Long {
        return when (val t = c.get(key)) {
            is NbtTag.LongTag -> t.value
            is NbtTag.IntTag -> t.value.toLong()
            else -> def
        }
    }

    /** 备份世界为 zip（按世界名串行化，防止并发备份产生损坏的 zip） */
    @Throws(IOException::class)
    fun backup(world: WorldInfo): Path {
        synchronized(lockFor(world.name)) {
            Files.createDirectories(backupsDir)
            val stamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val zip = backupsDir.resolve("${world.name}-$stamp.zip")
            ZipOutputStream(Files.newOutputStream(zip)).use { zos ->
                Files.walkFileTree(world.dir, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        // 跳过 Minecraft 运行时锁文件，避免读取正在运行的世界时失败
                        val fileName = file.fileName.toString()
                        if (fileName == "session.lock") return FileVisitResult.CONTINUE
                        val rel = world.dir.relativize(file).toString().replace(File.separatorChar, '/')
                        zos.putNextEntry(ZipEntry(rel))
                        Files.copy(file, zos)
                        zos.closeEntry()
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                        // 单个文件访问失败（权限/符号链接/占用）不应中断整个备份
                        System.err.println("[WorldManager] 备份时跳过无法访问的文件: $file - ${exc.message}")
                        return FileVisitResult.CONTINUE
                    }
                })
            }
            return zip
        }
    }

    /** 从 zip 恢复世界（覆盖现有世界，按世界名串行化，防止与并发备份/恢复竞争导致数据丢失） */
    @Throws(IOException::class)
    fun restore(zipFile: Path, worldName: String) {
        synchronized(lockFor(worldName)) {
            val target = savesDir.resolve(worldName).normalize()
            if (!target.startsWith(savesDir)) throw IOException("非法世界名: $worldName")
            Files.createDirectories(savesDir)
            // 先解压到临时暂存目录，成功后再替换原世界，避免解压中途失败导致原存档丢失
            val staging = target.resolveSibling("$worldName.restoring")
            // 清理上次失败残留的暂存目录
            if (Files.exists(staging)) deleteRecursive(staging)
            try {
                Files.createDirectories(staging)
                ZipInputStream(Files.newInputStream(zipFile)).use { zis ->
                    SafeZipExtractor.extractStreamSafely(zis, staging, null)
                }
                // 解压成功，原子替换原世界（先备份 target→bak，move staging→target，成功后删 bak，失败恢复）
                val bak = target.resolveSibling("$worldName.bak")
                if (Files.exists(target)) {
                    try {
                        Files.move(target, bak, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: AtomicMoveNotSupportedException) {
                        Files.move(target, bak, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                try {
                    try {
                        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: AtomicMoveNotSupportedException) {
                        Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
                    }
                    // 成功后清理备份
                    if (Files.exists(bak)) deleteRecursive(bak)
                } catch (e: IOException) {
                    // move 失败：恢复备份
                    try {
                        if (Files.exists(bak)) {
                            try {
                                Files.move(bak, target, StandardCopyOption.ATOMIC_MOVE)
                            } catch (ex: AtomicMoveNotSupportedException) {
                                Files.move(bak, target, StandardCopyOption.REPLACE_EXISTING)
                            }
                        }
                    } catch (ignored: IOException) {
                    }
                    throw e
                }
            } catch (e: IOException) {
                // 解压失败：清理暂存目录，保留原存档不受影响
                try { deleteRecursive(staging) } catch (ignored: IOException) {}
                throw e
            }
        }
    }

    /** 导入世界 zip（与 restore 相同，但目标名取 zip 文件名） */
    @Throws(IOException::class)
    fun importWorld(zipFile: Path) {
        var name = zipFile.fileName.toString()
        if (name.lowercase(Locale.ROOT).endsWith(".zip")) name = name.substring(0, name.length - 4)
        restore(zipFile, name)
    }

    /** 删除世界（仅允许删除位于某个 saves/ 下且含 level.dat 的目录） */
    @Throws(IOException::class)
    fun delete(world: WorldInfo) {
        val dir = assertDeletableWorldDir(world.dir)
        deleteRecursive(dir)
        sizeCache.remove(world.dir)
        sizeCache.remove(dir)
    }

    @Throws(IOException::class)
    private fun dirSize(dir: Path): Long {
        val size = LongArray(1)
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                size[0] += attrs.size()
                return FileVisitResult.CONTINUE
            }
        })
        return size[0]
    }

    @Throws(IOException::class)
    private fun deleteRecursive(p: Path) {
        if (!Files.exists(p)) return
        Files.walkFileTree(p, object : SimpleFileVisitor<Path>() {
            override fun visitFile(f: Path, a: BasicFileAttributes): FileVisitResult {
                Files.delete(f)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(d: Path, exc: IOException?): FileVisitResult {
                Files.delete(d)
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** 列出已备份的世界 zip */
    @Throws(IOException::class)
    fun listBackups(worldName: String): List<Path> {
        val result = ArrayList<Path>()
        if (!Files.isDirectory(backupsDir)) return result
        Files.list(backupsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().startsWith("$worldName-") }
                .forEach { result.add(it) }
        }
        return result
    }

    companion object {
        /**
         * 防止任意路径递归删除：目录必须在名为 `saves` 的父目录下，
         * 且包含 `level.dat`（Minecraft 世界特征文件）。
         */
        @Throws(IOException::class)
        @JvmStatic
        internal fun assertDeletableWorldDir(worldDir: Path?): Path {
            if (worldDir == null) throw IOException("世界目录为空")
            val dir = worldDir.toAbsolutePath().normalize()
            if (!Files.isDirectory(dir)) throw IOException("不是目录: $dir")
            var underSaves = false
            var p: Path? = dir.parent
            while (p != null) {
                val name = p.fileName
                if (name != null && "saves".equals(name.toString(), ignoreCase = true)) {
                    underSaves = true
                    break
                }
                p = p.parent
            }
            if (!underSaves) {
                throw IOException("拒绝删除：路径不在 saves 目录下: $dir")
            }
            if (!Files.isRegularFile(dir.resolve("level.dat"))) {
                throw IOException("拒绝删除：缺少 level.dat，不像 Minecraft 世界: $dir")
            }
            return dir
        }
    }
}
