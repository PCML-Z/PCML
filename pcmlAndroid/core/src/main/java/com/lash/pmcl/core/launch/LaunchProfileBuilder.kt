package com.lash.pmcl.core.launch

import com.google.gson.JsonParser
import com.lash.pmcl.core.auth.Account
import com.lash.pmcl.core.install.VersionJson
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.preferences.Preferences
import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * 从已安装的版本 JSON 构造 [LaunchProfile] — Android 版。
 *
 * 流程：读取 versions/{id}/{id}.json → 解析 → 处理 inheritsFrom
 *      → 收集 classpath（client.jar + libraries 主 artifact）
 *      → 注入 JVM/游戏参数 → 叠加用户偏好（GC/Aikar/自定义参数）。
 *
 * 与桌面版的差异：
 * - 路径由 [PmclPaths] 提供，移除 LauncherConfig 依赖
 * - 移除 MenuBackgroundProvider（主菜单背景视频，桌面专属）
 * - 移除 AuthlibInjectorManager（Android 上由 PojavLauncher 内置处理）
 * - 移除 RetroWrapperSupport（旧版本 Java 翻译，桌面专属）
 * - 移除 PluginManager LaunchHook 贡献
 * - 移除多 Minecraft 根目录扫描（Android 上只扫描 paths.versions）
 * - 保留 inheritsFrom 递归合并、占位符替换、Aikar Flags、内存参数
 */
class LaunchProfileBuilder(
    private val paths: PmclPaths,
    private val preferences: Preferences?
) {

    companion object {
        /** 匹配 ${...} 占位符，用于单次扫描替换防止注入 */
        private val PLACEHOLDER_PATTERN = Regex("\\$\\{[^}]+\\}")
    }

    /**
     * 构造启动配置。
     *
     * @param versionId 版本 ID（如 "1.20.4"）
     * @param account   账号（可为 null，仅离线启动时）
     * @return 完整的 [LaunchProfile]
     */
    @Throws(IOException::class)
    fun build(versionId: String, account: Account?): LaunchProfile {
        val profile = LaunchProfile(paths, account, versionId)

        // 1. 查找并解析版本 JSON
        val jsonPath = findVersionJson(versionId)
            ?: throw IOException("未找到版本 JSON: $versionId")
        val jsonStr = FileUtils.readString(jsonPath)
        var vj = VersionJson.parse(jsonStr)

        // 处理继承：合并父版本 JSON
        if (!vj.inheritsFrom.isNullOrEmpty() && vj.inheritsFrom != versionId) {
            vj = mergeInherited(vj, vj.inheritsFrom!!, mutableSetOf(), 0)
        }

        // 2. 主类
        if (vj.mainClass.isNotEmpty()) {
            profile.setMainClass(vj.mainClass)
        }

        // 3. classpath：client.jar + libraries 主 artifact
        val versionDir = paths.versions.resolve(versionId)
        val clientJar = versionDir.resolve("$versionId.jar")
        if (Files.exists(clientJar)) {
            profile.addClasspath(clientJar)
        }

        for (lib in vj.libraries) {
            if (!lib.appliesToCurrentOs()) continue
            // 只收集主 artifact（非 native），native 由启动器运行时提取
            lib.artifact?.let { a ->
                val libPath = paths.libraries.resolve(lib.getPath())
                if (Files.exists(libPath)) {
                    profile.addClasspath(libPath)
                }
            }
        }

        // 4. JVM 参数（来自版本 JSON arguments.jvm）
        for (jvmArg in vj.getJvmArgs()) {
            profile.addJvmArg(jvmArg)
        }

        // 5. 游戏参数（来自版本 JSON arguments.game 或旧格式 minecraftArguments）
        val gameArgs = vj.getGameArgs()
        for (arg in gameArgs) {
            profile.addGameArg(arg)
        }

        // 6. 叠加用户偏好：内存 / GC / Aikar Flags
        applyPreferences(profile)

        // 7. 占位符替换（${auth_player_name} → 实际用户名等）
        replacePlaceholders(profile, account, versionId, vj.assets)

        return profile
    }

    /**
     * 查找版本 JSON 文件。
     * Android 上只扫描 paths.versions（不扫描系统默认 Minecraft 目录）。
     */
    private fun findVersionJson(versionId: String): Path? {
        if (versionId.contains("..") || versionId.contains("/") ||
            versionId.contains("\\") || versionId.indexOf('\u0000') >= 0) {
            throw IllegalArgumentException("非法版本 ID: $versionId")
        }
        val jsonPath = paths.versions.resolve(versionId).resolve("$versionId.json")
        return if (Files.exists(jsonPath)) jsonPath else null
    }

    /**
     * 递归合并继承版本（inheritsFrom）。
     * 将父版本的 mainClass、libraries、arguments、assets、javaVersion 等合并到子版本。
     * 与 VersionInstaller.mergeInheritedRecursive 逻辑一致。
     */
    @Throws(IOException::class)
    private fun mergeInherited(
        child: VersionJson,
        parentVersionId: String,
        visited: MutableSet<String>,
        depth: Int
    ): VersionJson {
        if (depth > 16) throw IOException("版本继承深度超过 16 层，可能存在循环")
        if (!visited.add(parentVersionId)) {
            throw IOException("版本继承出现循环: ${visited.joinToString(" → ")} → $parentVersionId")
        }
        try {
            val parentJsonPath = findVersionJson(parentVersionId)
                ?: throw IOException("未找到父版本 JSON: $parentVersionId")
            val parentJson = FileUtils.readString(parentJsonPath)
            var parentObj = JsonParser.parseString(parentJson).asJsonObject
            var parentVj = VersionJson.parse(parentJson)

            // 递归处理父版本的 inheritsFrom
            if (!parentVj.inheritsFrom.isNullOrEmpty() && parentVj.inheritsFrom != parentVersionId) {
                val merged = mergeInherited(parentVj, parentVj.inheritsFrom, visited, depth + 1)
                parentObj = merged.rawJson
            }

            val childObj = child.rawJson

            // mainClass: 子没有则用父
            if (!childObj.has("mainClass") && parentObj.has("mainClass")) {
                childObj.add("mainClass", parentObj.get("mainClass"))
            }
            // type: 继承父版本的类型标识
            if (!childObj.has("type") && parentObj.has("type")) {
                childObj.add("type", parentObj.get("type"))
            }
            // assets: 子没有则用父
            if (!childObj.has("assets") && parentObj.has("assets")) {
                childObj.add("assets", parentObj.get("assets"))
            }
            // assetIndex
            if (!childObj.has("assetIndex") && parentObj.has("assetIndex")) {
                childObj.add("assetIndex", parentObj.get("assetIndex"))
            }
            // downloads
            if (!childObj.has("downloads") && parentObj.has("downloads")) {
                childObj.add("downloads", parentObj.get("downloads"))
            }
            // javaVersion
            if (!childObj.has("javaVersion") && parentObj.has("javaVersion")) {
                childObj.add("javaVersion", parentObj.get("javaVersion"))
            }
            // minecraftArguments
            if (!childObj.has("minecraftArguments") && parentObj.has("minecraftArguments")) {
                childObj.add("minecraftArguments", parentObj.get("minecraftArguments"))
            }

            // 合并 arguments
            if (parentObj.has("arguments")) {
                val parentArgs = parentObj.getAsJsonObject("arguments")
                if (!childObj.has("arguments")) {
                    childObj.add("arguments", parentArgs)
                } else {
                    val childArgs = childObj.getAsJsonObject("arguments")
                    if (parentArgs.has("game")) {
                        val merged = com.google.gson.JsonArray()
                        if (childArgs.has("game"))
                            childArgs.getAsJsonArray("game").forEach { merged.add(it) }
                        parentArgs.getAsJsonArray("game").forEach { merged.add(it) }
                        childArgs.add("game", merged)
                    }
                    if (parentArgs.has("jvm")) {
                        val merged = com.google.gson.JsonArray()
                        if (childArgs.has("jvm"))
                            childArgs.getAsJsonArray("jvm").forEach { merged.add(it) }
                        parentArgs.getAsJsonArray("jvm").forEach { merged.add(it) }
                        childArgs.add("jvm", merged)
                    }
                }
            }

            // 合并 libraries：子的覆盖父的同名库
            if (parentObj.has("libraries")) {
                val merged = com.google.gson.JsonArray()
                val childNames = HashSet<String>()
                if (childObj.has("libraries")) {
                    childObj.getAsJsonArray("libraries").forEach { e ->
                        merged.add(e)
                        val libObj = e.asJsonObject
                        if (libObj.has("name") && !libObj.get("name").isJsonNull)
                            childNames.add(libObj.get("name").asString)
                    }
                }
                parentObj.getAsJsonArray("libraries").forEach { e ->
                    val libObj = e.asJsonObject
                    if (!libObj.has("name") || libObj.get("name").isJsonNull) return@forEach
                    if (libObj.get("name").asString !in childNames) merged.add(e)
                }
                childObj.add("libraries", merged)
            }

            return VersionJson.parse(childObj.toString())
        } finally {
            visited.remove(parentVersionId)
        }
    }

    /**
     * 应用用户偏好：内存、GC、Aikar Flags。
     */
    private fun applyPreferences(profile: LaunchProfile) {
        val prefs = preferences ?: return

        // 内存
        profile.addJvmArg("-Xms${prefs.getMinMemoryMb()}m")
        profile.addJvmArg("-Xmx${prefs.getMaxMemoryMb()}m")

        // GC
        profile.addJvmArg("-XX:+Use${prefs.getGcType()}")

        // Aikar Flags（仅当用户启用且 GC 为 G1GC）
        if (prefs.isUseAikarFlags() && prefs.getGcType() == "G1GC") {
            for (flag in AikarFlags.FLAGS) {
                profile.addJvmArg(flag)
            }
        }

        // 自定义 JVM 参数
        val customArgs = prefs.getCustomJvmArgs()
        if (customArgs.isNotEmpty()) {
            // 按空白分割（支持多个参数）
            for (arg in customArgs.trim().split("\\s+".toRegex())) {
                if (arg.isNotEmpty()) profile.addJvmArg(arg)
            }
        }
    }

    /**
     * 替换 ${...} 占位符为实际值。
     * 单次扫描替换，防止用户名等字段注入新的占位符。
     */
    private fun replacePlaceholders(
        profile: LaunchProfile,
        account: Account?,
        versionId: String,
        assets: String
    ) {
        val playerName = account?.username ?: "Player"
        val playerUuid = account?.uuid ?: ""
        val accessToken = account?.accessToken ?: ""
        val xuid = account?.xuid ?: ""
        val userType = if (account?.type == Account.AccountType.MICROSOFT) "msa" else "legacy"
        val assetsDir = paths.assets.toString()
        val assetsRoot = paths.assets.resolve("objects").toString()
        val gameDir = profile.gameDir.toString()
        val versionDir = paths.versions.resolve(versionId).toString()

        val replacements = mapOf(
            "auth_player_name" to playerName,
            "auth_uuid" to playerUuid,
            "auth_access_token" to accessToken,
            "auth_xuid" to xuid,
            "user_type" to userType,
            "version_name" to versionId,
            "game_directory" to gameDir,
            "assets_root" to assetsRoot,
            "assets_index_name" to assets,
            "version_type" to "release",
            "natives_directory" to "$versionDir/natives",
            "launcher_name" to "PMCL",
            "launcher_version" to "1.0",
            "classpath" to "",  // 由 buildCommand 单独处理
            "user_properties" to "{}"
        )

        // 替换 JVM 参数
        val jvmArgs = profile.jvmArgsMutable()
        for (i in jvmArgs.indices) {
            jvmArgs[i] = replaceInString(jvmArgs[i], replacements)
        }

        // 替换游戏参数
        val gameArgs = profile.gameArgsMutable()
        for (i in gameArgs.indices) {
            gameArgs[i] = replaceInString(gameArgs[i], replacements)
        }
    }

    private fun replaceInString(s: String, replacements: Map<String, String>): String {
        return PLACEHOLDER_PATTERN.replace(s) { match ->
            val key = match.value.removePrefix("\${").removeSuffix("}")
            replacements[key] ?: match.value
        }
    }
}
