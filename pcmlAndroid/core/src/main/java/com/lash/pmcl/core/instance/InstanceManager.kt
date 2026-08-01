package com.lash.pmcl.core.instance

import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.comparisons.compareByDescending

/**
 * 独立实例管理器。
 *
 * 统一管理 `~/.pmcl/instances/<instanceId>/` 目录下的所有实例，包括：
 *   - 用户手动创建的自定义实例（Type.CUSTOM）
 *   - 从整合包导入的实例（Type.MODPACK，向后兼容 modpack.json）
 *
 * 每个实例目录包含：
 *   - instance.json — 实例元数据标记文件（新格式）
 *   - modpack.json — 旧格式整合包标记（向后兼容读取）
 *   - mods/ saves/ config/ resourcepacks/ shaderpacks/ screenshots/ logs/
 *
 * 桌面版依赖 LauncherConfig，Android 版改为依赖 PmclPaths。
 */
class InstanceManager(private val paths: PmclPaths) {

    /** 实例根目录 `~/.pmcl/instances/` */
    fun getInstancesDir(): Path = paths.instances

    /** 获取指定实例的目录路径（校验 instanceId，防路径穿越） */
    fun getInstanceDir(instanceId: String): Path = resolveSafeInstanceDir(instanceId)

    /**
     * 校验并解析实例目录：仅允许单层安全 ID，拒绝 `..` / 分隔符 / 越界。
     */
    fun resolveSafeInstanceDir(instanceId: String): Path {
        requireSafeInstanceId(instanceId)
        val base = paths.instances.toAbsolutePath().normalize()
        val dir = base.resolve(instanceId).normalize()
        if (!dir.startsWith(base)) {
            throw IllegalArgumentException("instance path escapes instances dir: $instanceId")
        }
        return dir
    }

    /**
     * 列出所有实例（扫描 instances/ 目录，读取标记文件）。
     * 同时支持新格式 instance.json 和旧格式 modpack.json。
     */
    fun listInstances(): List<InstanceInfo> {
        val dir = paths.instances
        if (!Files.isDirectory(dir)) return emptyList()
        val result = ArrayList<InstanceInfo>()
        try {
            Files.list(dir).use { stream ->
                stream.filter { Files.isDirectory(it) }
                    .sorted(compareByDescending { p: Path ->
                        try {
                            Files.getLastModifiedTime(p).toMillis()
                        } catch (e: IOException) {
                            0L
                        }
                    })
                    .forEach { instanceDir ->
                        loadInstanceInfo(instanceDir)?.let { result.add(it) }
                    }
            }
        } catch (e: IOException) {
            return emptyList()
        }
        return result
    }

    /** 读取实例元数据（优先 instance.json，回退 modpack.json） */
    private fun loadInstanceInfo(instanceDir: Path): InstanceInfo? {
        try {
            val newMarker = instanceDir.resolve(INSTANCE_MARKER)
            val legacyMarker = instanceDir.resolve(LEGACY_MODPACK_MARKER)

            if (Files.exists(newMarker)) {
                val json = FileUtils.readString(newMarker)
                return InstanceInfo.fromJson(json, instanceDir)
            }
            if (Files.exists(legacyMarker)) {
                val json = FileUtils.readString(legacyMarker)
                return InstanceInfo.fromModpackJson(json, instanceDir)
            }
            // 无标记文件但存在 mods/ 子目录（versionIsolation 创建的目录）
            if (Files.isDirectory(instanceDir.resolve("mods"))) {
                val dirName = instanceDir.fileName?.toString() ?: "Unknown"
                // M64: 基于目录绝对路径生成稳定 UUID（nameUUIDFromBytes），
                // 避免每次扫描生成新 UUID 导致 UI 认为新实例出现
                val stableId = UUID.nameUUIDFromBytes(
                    instanceDir.toAbsolutePath().toString().toByteArray(StandardCharsets.UTF_8)
                ).toString()
                val info = InstanceInfo(stableId, dirName, dirName, InstanceInfo.Type.CUSTOM)
                info.instanceDir = instanceDir
                // 持久化 instance.json，下次扫描直接读取，无需重新生成
                try {
                    saveInstanceInfo(info)
                } catch (saveErr: IOException) {
                    System.err.println("[InstanceManager] 持久化 instance.json 失败 $instanceDir: ${saveErr.message}")
                }
                return info
            }
        } catch (e: Exception) {
            System.err.println("[InstanceManager] 读取实例元数据失败 $instanceDir: ${e.message}")
        }
        return null
    }

    /**
     * 创建新实例。
     *
     * @param name           实例显示名称
     * @param baseVersionId  基础 Minecraft 版本 ID
     * @param loader         模组加载器（可为 null）
     * @param loaderVersion  加载器版本（可为 null）
     * @return 创建的实例信息
     */
    @Throws(IOException::class)
    fun createInstance(
        name: String, baseVersionId: String,
        loader: String?, loaderVersion: String?
    ): InstanceInfo {
        val instanceId = UUID.randomUUID().toString()
        val instanceDir = getInstanceDir(instanceId)
        Files.createDirectories(instanceDir)
        for (sub in SUBDIRS) {
            Files.createDirectories(instanceDir.resolve(sub))
        }

        val info = InstanceInfo(instanceId, name, baseVersionId, InstanceInfo.Type.CUSTOM)
        info.loader = loader
        info.loaderVersion = loaderVersion
        info.instanceDir = instanceDir
        saveInstanceInfo(info)
        return info
    }

    /**
     * 复制现有实例（克隆 mods/configs/resourcepacks，不复制 saves/logs）。
     *
     * @param sourceId 源实例 ID
     * @param newName  新实例名称
     * @return 新实例信息
     */
    @Throws(IOException::class)
    fun copyInstance(sourceId: String, newName: String): InstanceInfo {
        val sourceDir = getInstanceDir(sourceId)
        if (!Files.isDirectory(sourceDir)) throw IOException("源实例不存在: $sourceId")

        val source = loadInstanceInfo(sourceDir) ?: throw IOException("无法读取源实例元数据")

        val newId = UUID.randomUUID().toString()
        val newDir = getInstanceDir(newId)
        Files.createDirectories(newDir)

        // 复制 mods / config / resourcepacks / shaderpacks（不复制 saves/screenshots/logs 避免占空间）
        for (sub in listOf("mods", "config", "resourcepacks", "shaderpacks")) {
            val srcSub = sourceDir.resolve(sub)
            if (Files.isDirectory(srcSub)) {
                copyDirectory(srcSub, newDir.resolve(sub))
            } else {
                Files.createDirectories(newDir.resolve(sub))
            }
        }
        // 创建空的 saves/screenshots/logs
        for (sub in listOf("saves", "screenshots", "logs")) {
            Files.createDirectories(newDir.resolve(sub))
        }

        val newInfo = InstanceInfo(newId, newName, source.baseVersionId, InstanceInfo.Type.CUSTOM)
        newInfo.loader = source.loader
        newInfo.loaderVersion = source.loaderVersion
        newInfo.description = source.description
        newInfo.instanceDir = newDir
        saveInstanceInfo(newInfo)
        return newInfo
    }

    /** 重命名实例（仅修改 name 字段，目录名不变） */
    @Throws(IOException::class)
    fun renameInstance(instanceId: String, newName: String) {
        val dir = getInstanceDir(instanceId)
        val info = loadInstanceInfo(dir) ?: throw IOException("实例不存在")
        info.name = newName
        saveInstanceInfo(info)
    }

    /** 删除实例（递归删除整个目录） */
    @Throws(IOException::class)
    fun deleteInstance(instanceId: String) {
        val dir = getInstanceDir(instanceId)
        if (Files.isDirectory(dir)) {
            deleteDirectory(dir)
        }
    }

    /** 保存实例元数据到 instance.json */
    @Throws(IOException::class)
    fun saveInstanceInfo(info: InstanceInfo) {
        val instanceDir = info.instanceDir ?: throw IOException("实例目录未初始化")
        val file = instanceDir.resolve(INSTANCE_MARKER)
        val parent = file.parent
        if (parent != null) Files.createDirectories(parent)
        val tmp = file.resolveSibling("$INSTANCE_MARKER.tmp.${UUID.randomUUID()}")
        FileUtils.writeString(tmp, info.toJson())
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 确保实例子目录存在 */
    @Throws(IOException::class)
    fun ensureSubdirs(instanceDir: Path) {
        for (sub in SUBDIRS) {
            Files.createDirectories(instanceDir.resolve(sub))
        }
    }

    companion object {
        private const val INSTANCE_MARKER = "instance.json"
        private const val LEGACY_MODPACK_MARKER = "modpack.json"
        private val SUBDIRS =
            arrayOf("mods", "saves", "config", "resourcepacks", "shaderpacks", "screenshots", "logs")

        /** @throws IllegalArgumentException 若 instanceId 非法 */
        @JvmStatic
        fun requireSafeInstanceId(instanceId: String?) {
            if (instanceId == null || instanceId.isBlank()) {
                throw IllegalArgumentException("instanceId is blank")
            }
            if (instanceId.contains("..") || instanceId.contains("/") || instanceId.contains("\\")
                || instanceId.indexOf('\u0000') >= 0
                || !instanceId.matches(Regex("[A-Za-z0-9._\\-]{1,128}"))
            ) {
                throw IllegalArgumentException("illegal instanceId: $instanceId")
            }
        }

        /**
         * 解析实例图标路径：仅允许实例目录内的简单文件名（如 icon.png），拒绝穿越。
         * @return 安全路径；非法则 null
         */
        @JvmStatic
        fun resolveSafeIconPath(instanceDir: Path, iconPath: String?): Path? {
            if (iconPath == null || iconPath.isBlank()) return null
            if (iconPath.contains("..") || iconPath.contains("/") || iconPath.contains("\\")
                || iconPath.indexOf('\u0000') >= 0
            ) {
                System.err.println("[InstanceManager] 拒绝非法 iconPath: $iconPath")
                return null
            }
            val base = instanceDir.toAbsolutePath().normalize()
            val iconFile = base.resolve(iconPath).normalize()
            if (!iconFile.startsWith(base)) {
                System.err.println("[InstanceManager] iconPath 越界: $iconPath")
                return null
            }
            return iconFile
        }

        @Throws(IOException::class)
        private fun copyDirectory(source: Path, target: Path) {
            Files.createDirectories(target)
            try {
                Files.walk(source).use { stream ->
                    stream.forEach { src ->
                        try {
                            val dst = target.resolve(source.relativize(src))
                            if (Files.isDirectory(src)) {
                                Files.createDirectories(dst)
                            } else {
                                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
                            }
                        } catch (e: IOException) {
                            // M65: 不静默吞异常，包装为 UncheckedIOException 抛出
                            throw UncheckedIOException("复制失败: $src", e)
                        }
                    }
                }
            } catch (e: UncheckedIOException) {
                throw e.cause ?: IOException(e)
            }
        }

        @Throws(IOException::class)
        private fun deleteDirectory(dir: Path) {
            Files.walk(dir).use { stream ->
                stream.sorted(compareByDescending { p: Path -> p })
                    .forEach { p ->
                        try { Files.deleteIfExists(p) } catch (_: IOException) {}
                    }
            }
        }
    }
}
