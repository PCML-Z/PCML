package com.lash.pmcl.core.mods

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.lash.pmcl.core.market.ModrinthClient
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.preferences.Preferences
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.ArrayList

/**
 * 拖放安装器：解析拖入的 mod jar + SHA1 反查 Modrinth + 拷贝到目标 mods 目录。
 *
 * 流程：
 * 1. [analyze]：对每个 jar 调用 [ModScanner.parseJar] 拿元数据，
 *    计算 SHA1，批量调用 [ModrinthClient.batchCheckBySha1] 反查
 *    game_versions / loaders，返回 [ModDropInfo] 列表
 * 2. [installTo]：拷贝 jar 到目标 mods 目录
 *    （版本隔离 → instances/<versionId>/mods/，否则 mods/<gameVersion>/）
 *
 * 设计：所有 IO 在调用方线程执行（同步方法），让 UI 决定是否切到 IO 协程。
 * 网络失败时不抛异常，降级为 modrinthFound=false，UI 仍允许用户手动选择版本。
 */
class ModDropInstaller(
    private val paths: PmclPaths,
    @Suppress("unused") private val preferences: Preferences,
    private val modrinth: ModrinthClient
) {

    /**
     * 批量解析拖入的 jar 文件。
     *
     * 对每个 jar：
     * 1. ModScanner.parseJar 拿 modId/name/version/loader
     * 2. 计算文件 SHA1
     *
     * 然后一次性批量调用 Modrinth batchCheckBySha1 反查所有 jar 的兼容版本信息。
     *
     * @param jarPaths 拖入的 jar 文件路径列表
     * @return 解析结果列表（顺序与输入一致），解析失败的 jar 也会返回带 parseError 的项
     */
    fun analyze(jarPaths: List<Path>?): List<ModDropInfo> {
        if (jarPaths.isNullOrEmpty()) return emptyList()

        // 第一阶段：解析 jar 元数据 + 计算 SHA1
        val metas = ArrayList<ModMeta?>(jarPaths.size)
        val sha1s = ArrayList<String?>(jarPaths.size)
        val parseErrors = ArrayList<String?>(jarPaths.size)
        for (p in jarPaths) {
            var meta: ModMeta? = null
            var err: String? = null
            try {
                meta = ModScanner.parseJar(p)
            } catch (t: Throwable) {
                err = t.message ?: t.javaClass.simpleName
            }
            metas.add(meta)
            parseErrors.add(err)
            var sha1: String? = null
            try {
                sha1 = computeSha1(p)
            } catch (_: IOException) {
                // SHA1 计算失败：仍允许安装，只是无法反查 Modrinth
            }
            sha1s.add(sha1)
        }

        // 第二阶段：批量反查 Modrinth（仅对有 SHA1 的 jar）
        var modrinthMap: Map<String, JsonObject> = emptyMap()
        val nonNullSha1s = ArrayList<String>()
        for (s in sha1s) {
            if (!s.isNullOrEmpty() && !nonNullSha1s.contains(s)) {
                nonNullSha1s.add(s)
            }
        }
        if (nonNullSha1s.isNotEmpty()) {
            try {
                modrinthMap = modrinth.batchCheckBySha1(nonNullSha1s)
            } catch (_: Throwable) {
                // 网络失败：降级为 modrinthFound=false
                modrinthMap = emptyMap()
            }
        }

        // 第三阶段：组装 ModDropInfo
        val result = ArrayList<ModDropInfo>(jarPaths.size)
        for (i in jarPaths.indices) {
            val jarPath = jarPaths[i]
            val meta = metas[i]
            val sha1 = sha1s[i]
            val err = parseErrors[i]

            val modId: String
            val name: String
            val version: String
            val loader: String
            val authors: String
            val description: String
            if (meta != null) {
                modId = meta.modId
                name = meta.name
                version = meta.version
                loader = meta.loader
                authors = meta.authors
                description = meta.description
            } else {
                modId = ""
                name = jarPath.fileName.toString()
                version = ""
                loader = "unknown"
                authors = ""
                description = ""
            }

            var gameVersions: List<String> = emptyList()
            var loaders: List<String> = emptyList()
            var found = false
            if (sha1 != null && modrinthMap.containsKey(sha1)) {
                val v = modrinthMap[sha1]
                if (v != null) {
                    gameVersions = jsonArrToStrings(v, "game_versions")
                    loaders = jsonArrToStrings(v, "loaders")
                    found = gameVersions.isNotEmpty() || loaders.isNotEmpty()
                }
            }
            result.add(
                ModDropInfo(
                    modId, name, version, loader, authors, description,
                    jarPath, sha1, gameVersions, loaders, found, err
                )
            )
        }
        return result
    }

    /**
     * 把已解析的 mod jar 拷贝到目标 mods 目录。
     *
     * 路径推导：
     * - versionId 非空（版本隔离）：instances/<versionId>/mods/
     * - 否则：mods/<gameVersion>/（gameVersion 为空则直接 mods/）
     *
     * 同名文件存在时覆盖（让用户能拖入新版 jar 更新）。
     *
     * @param info        已解析的 mod 信息
     * @param versionId   目标版本 ID（用于版本隔离），可为 null
     * @param gameVersion 目标 MC 版本号（如 "1.20.1"），可为 null
     * @return 拷贝目标路径
     * @throws IOException 拷贝失败
     */
    @Throws(IOException::class)
    fun installTo(info: ModDropInfo, versionId: String?, gameVersion: String?): Path {
        val modsDir: Path
        if (!versionId.isNullOrEmpty()) {
            // 版本隔离模式：instances/<versionId>/mods
            requireSafeName(versionId)
            val instancesRoot = paths.instances.toAbsolutePath().normalize()
            val instanceDir = instancesRoot.resolve(versionId).normalize()
            if (!instanceDir.startsWith(instancesRoot)) {
                throw IOException("versionId path escapes instances dir: $versionId")
            }
            modsDir = instanceDir.resolve("mods")
        } else {
            val baseModsDir = paths.minecraftWorkDir.resolve("mods")
            modsDir = if (!gameVersion.isNullOrEmpty()) {
                requireSafeName(gameVersion)
                val modsRoot = baseModsDir.toAbsolutePath().normalize()
                val resolved = modsRoot.resolve(gameVersion).normalize()
                if (!resolved.startsWith(modsRoot)) {
                    throw IOException("gameVersion path escapes mods dir: $gameVersion")
                }
                resolved
            } else {
                baseModsDir
            }
        }
        val modsAbs = modsDir.toAbsolutePath().normalize()
        Files.createDirectories(modsAbs)
        val jarName = info.jarPath.fileName
            ?: throw IOException("非法模组文件名")
        val fileName = jarName.toString()
        if (fileName.isBlank() || fileName.contains("..") || fileName.contains("/") ||
            fileName.contains("\\") || fileName.indexOf('\u0000') >= 0
        ) {
            throw IOException("非法模组文件名: $fileName")
        }
        val target = modsAbs.resolve(fileName).normalize()
        if (!target.startsWith(modsAbs)) {
            throw IOException("模组路径越界: $fileName")
        }
        Files.copy(info.jarPath, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    // ==================== 辅助 ====================

    /** 校验名称不含路径穿越字符（替代桌面版 InstanceManager.requireSafeInstanceId） */
    private fun requireSafeName(name: String) {
        if (name.contains("..") || name.contains("/") ||
            name.contains("\\") || name.indexOf('\u0000') >= 0
        ) {
            throw IOException("非法名称: $name")
        }
    }

    /** 计算文件 SHA1（hex 小写） */
    @Throws(IOException::class)
    private fun computeSha1(path: Path): String? {
        val md: MessageDigest = try {
            MessageDigest.getInstance("SHA-1")
        } catch (e: Exception) {
            return null
        }
        Files.newInputStream(path).use { inp ->
            val buf = ByteArray(8192)
            var n: Int
            while (inp.read(buf).also { n = it } > 0) {
                md.update(buf, 0, n)
            }
        }
        val digest = md.digest()
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(Character.forDigit((b.toInt() shr 4) and 0xF, 16))
            sb.append(Character.forDigit(b.toInt() and 0xF, 16))
        }
        return sb.toString()
    }

    private fun jsonArrToStrings(o: JsonObject?, key: String): List<String> {
        if (o == null || !o.has(key) || !o.get(key).isJsonArray) return emptyList()
        val arr: JsonArray = o.getAsJsonArray(key)
        val list = ArrayList<String>(arr.size())
        for (e: JsonElement in arr) {
            if (e.isJsonPrimitive) list.add(e.asString)
        }
        return list
    }
}
