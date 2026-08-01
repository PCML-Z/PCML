package com.lash.pmcl.core.modloader

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.install.InstallInterruptedException
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionStaging
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.Exceptions
import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer

/**
 * LiteLoader 安装器。
 *
 * LiteLoader 是旧版本（1.7.10 / 1.10.2 / 1.12.2 等）的轻量级模组加载器。
 * 官方元数据：https://dl.liteloader.com/versions/versions.json
 *
 * 与 Forge/Fabric 不同，LiteLoader **没有预构建的版本 JSON 文件**。
 * dl.liteloader.com 上的版本 JSON 路径全部返回 404。
 * 因此安装流程为：
 *   1) 拉取 versions.json 清单，提取目标游戏版本下可用的 LiteLoader 版本
 *   2) 从清单元数据（tweakClass / libraries / file / version）**本地构造**版本 JSON
 *   3) 写入 versions/{id}/{id}.json，库文件由下载器统一拉取
 *
 * LiteLoader 版本 JSON 继承自原版版本（inheritsFrom），使用 --tweakClass 注入
 * LiteLoaderTweaker。不需要执行 installer.jar，直接写入 JSON 即可运行。
 *
 * 库下载 URL：
 *   - ivy 类型（RELEASE，1.5.2-1.8）：https://dl.liteloader.com/versions/ + maven path
 *   - m2 类型（SNAPSHOT，1.8.9-1.12.2）：https://bmclapi2.bangbang93.com/maven/ + maven path
 *     （repo.mumfrey.com 已下线，BMCLAPI maven 提供 302 重定向到教育网镜像）
 */
class LiteLoaderInstaller(
    private val paths: PmclPaths,
    private val downloads: DownloadManager
) : ModLoaderInstaller {

    override fun listVersions(gameVersion: String): CompletableFuture<List<ModLoaderVersion>> {
        return CompletableFuture.supplyAsync {
            try {
                val json = downloads.downloadString(MANIFEST_URL)
                val root = parseJsonObject(json, "LiteLoader manifest")
                // versions.json 顶层可能直接以 MC 版本号为 key，也可能嵌套在 "versions" 字段下
                var byMc = root
                if (root.has("versions") && root.get("versions").isJsonObject) {
                    byMc = root.getAsJsonObject("versions")
                }
                val result = ArrayList<ModLoaderVersion>()
                if (!byMc.has(gameVersion)) return@supplyAsync result
                val versionNode = byMc.getAsJsonObject(gameVersion)

                // 优先从 artefacts（RELEASE）提取，再从 snapshots（SNAPSHOT）提取
                result.addAll(extractVersions(versionNode, "artefacts", gameVersion, true))
                result.addAll(extractVersions(versionNode, "snapshots", gameVersion, false))

                result
            } catch (ex: Throwable) {
                throw RuntimeException("拉取 LiteLoader 版本失败", ex)
            }
        }
    }

    /** 从 manifest 的 artefacts 或 snapshots 节点提取版本列表 */
    private fun extractVersions(
        versionNode: JsonObject,
        section: String,
        gameVersion: String,
        stable: Boolean
    ): List<ModLoaderVersion> {
        val result = ArrayList<ModLoaderVersion>()
        if (!versionNode.has(section) || !versionNode.get(section).isJsonObject) return result
        val sectionNode = versionNode.getAsJsonObject(section)
        if (!sectionNode.has("com.mumfrey:liteloader")) return result
        val loaderNode = sectionNode.getAsJsonObject("com.mumfrey:liteloader")
        for ((key, value) in loaderNode.entrySet()) {
            if (key == "latest") continue // 跳过 latest 别名
            if (!value.isJsonObject) continue
            val v = value.asJsonObject
            val version = if (v.has("version") && !v.get("version").isJsonNull)
                v.get("version").asString else key
            if (version.isEmpty()) continue
            result.add(ModLoaderVersion(ModLoader.LITELOADER, gameVersion, version, stable))
        }
        return result
    }

    override fun install(
        gameVersion: String,
        loaderVersion: String,
        onProgress: Consumer<InstallProgress>?
    ): CompletableFuture<Void> {
        return CompletableFuture.runAsync {
            try {
                // 1. 重新拉取 manifest，找到对应版本的元数据
                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "拉取 LiteLoader 清单"
                    )
                )
                val json = downloads.downloadString(MANIFEST_URL)
                val root = parseJsonObject(json, "LiteLoader manifest")
                val byMc = if (root.has("versions") && root.get("versions").isJsonObject)
                    root.getAsJsonObject("versions") else root
                if (!byMc.has(gameVersion)) {
                    throw IOException("LiteLoader 清单中找不到游戏版本: $gameVersion")
                }
                val versionNode = byMc.getAsJsonObject(gameVersion)

                // 在 artefacts 和 snapshots 中查找匹配的版本
                var artefact = findVersionEntry(versionNode, "artefacts", loaderVersion)
                var isSnapshot = false
                if (artefact == null) {
                    artefact = findVersionEntry(versionNode, "snapshots", loaderVersion)
                    isSnapshot = true
                }
                if (artefact == null) {
                    throw IOException("LiteLoader 清单中找不到版本: $loaderVersion")
                }

                // 2. 构造版本 JSON
                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 1,
                        "构造 LiteLoader 版本 JSON"
                    )
                )
                val versionId = "LiteLoader-$loaderVersion"
                val versionJson = buildVersionJson(
                    gameVersion, loaderVersion, versionId, artefact, versionNode, isSnapshot
                )

                // 3. 写入 staging 再原子提升
                val staging = VersionStaging.writeVersionJson(
                    paths.versions, versionId, versionJson.toString()
                )
                VersionStaging.promote(paths.versions, versionId, staging)

                onProgress?.accept(
                    InstallProgress(
                        InstallProgress.Stage.DONE, 1, 1,
                        "LiteLoader 安装完成: $versionId"
                    )
                )
            } catch (e: Exception) {
                val id = "LiteLoader-$loaderVersion"
                if (!InstallInterruptedException.isInterrupted(e)) {
                    VersionStaging.discard(paths.versions, id)
                }
                val detail = Exceptions.rootMessage(e)
                onProgress?.accept(
                    InstallProgress(InstallProgress.Stage.FAILED, 0, 0, detail)
                )
                if (InstallInterruptedException.isInterrupted(e)) {
                    throw if (e is RuntimeException) e
                    else InstallInterruptedException("LiteLoader 安装已中断", e)
                }
                throw RuntimeException("LiteLoader 安装失败: $detail", e)
            }
        }
    }

    /** 在 manifest 的 artefacts/snapshots 节点中查找指定版本 */
    private fun findVersionEntry(
        versionNode: JsonObject,
        section: String,
        loaderVersion: String
    ): JsonObject? {
        if (!versionNode.has(section) || !versionNode.get(section).isJsonObject) return null
        val sectionNode = versionNode.getAsJsonObject(section)
        if (!sectionNode.has("com.mumfrey:liteloader")) return null
        val loaderNode = sectionNode.getAsJsonObject("com.mumfrey:liteloader")
        // 先精确匹配 version 字段
        for ((key, value) in loaderNode.entrySet()) {
            if (key == "latest") continue
            if (!value.isJsonObject) continue
            val v = value.asJsonObject
            val ver = if (v.has("version") && !v.get("version").isJsonNull)
                v.get("version").asString else ""
            if (loaderVersion == ver) return v
        }
        // 再匹配 key（md5 hash）
        if (loaderNode.has(loaderVersion) && loaderNode.get(loaderVersion).isJsonObject) {
            return loaderNode.getAsJsonObject(loaderVersion)
        }
        return null
    }

    /**
     * 本地构造 LiteLoader 版本 JSON。
     *
     * 结构：
     * ```
     * {
     *   "id": "LiteLoader-{version}",
     *   "inheritsFrom": "{gameVersion}",
     *   "mainClass": "net.minecraft.launchwrapper.Launcher",
     *   "minecraftArguments": "{标准参数} --tweakClass {tweakClass}",
     *   "libraries": [
     *     { "name": "com.mumfrey:liteloader:{version}", "url": "{repoUrl}" },
     *     ...依赖库
     *   ],
     *   "type": "release" or "snapshot"
     * }
     * ```
     */
    @Throws(IOException::class)
    private fun buildVersionJson(
        gameVersion: String,
        loaderVersion: String,
        versionId: String,
        artefact: JsonObject,
        versionNode: JsonObject,
        isSnapshot: Boolean
    ): JsonObject {
        val versionJson = JsonObject()
        versionJson.addProperty("id", versionId)
        versionJson.addProperty("inheritsFrom", gameVersion)
        versionJson.addProperty("mainClass", "net.minecraft.launchwrapper.Launcher")
        versionJson.addProperty("type", if (isSnapshot) "snapshot" else "release")

        // tweakClass
        var tweakClass = "com.mumfrey.liteloader.launch.LiteLoaderTweaker"
        if (artefact.has("tweakClass") && !artefact.get("tweakClass").isJsonNull) {
            tweakClass = artefact.get("tweakClass").asString
        }

        // minecraftArguments：尝试从父版本继承，追加 --tweakClass
        var mcArgs = resolveMinecraftArguments(gameVersion)
        mcArgs = "$mcArgs --tweakClass $tweakClass"
        versionJson.addProperty("minecraftArguments", mcArgs)

        // libraries
        val libraries = com.google.gson.JsonArray()

        // 1. LiteLoader 自身库
        val liteloaderLib = JsonObject()
        liteloaderLib.addProperty("name", "com.mumfrey:liteloader:$loaderVersion")
        // 确定下载 URL：ivy 用 dl.liteloader.com，m2 用 BMCLAPI maven
        val repoUrl = resolveRepoUrl(versionNode, isSnapshot)
        liteloaderLib.addProperty("url", repoUrl)
        libraries.add(liteloaderLib)

        // 2. 依赖库（launchwrapper, asm 等）
        if (artefact.has("libraries") && artefact.get("libraries").isJsonArray) {
            for (e in artefact.getAsJsonArray("libraries")) {
                if (e.isJsonObject) {
                    // 保留原有的 url 字段（如 asm-all 的 url）
                    libraries.add(e.asJsonObject)
                }
            }
        }

        // snapshots 节点可能有额外的公共 libraries
        if (isSnapshot && versionNode.has("snapshots")
            && versionNode.getAsJsonObject("snapshots").has("libraries")
            && versionNode.getAsJsonObject("snapshots").get("libraries").isJsonArray
        ) {
            for (e in versionNode.getAsJsonObject("snapshots").getAsJsonArray("libraries")) {
                if (e.isJsonObject) libraries.add(e.asJsonObject)
            }
        }

        versionJson.add("libraries", libraries)
        return versionJson
    }

    /**
     * 解析库下载 URL。
     * - ivy 类型（RELEASE）：http://dl.liteloader.com/versions/（Cloudflare CDN，可用）
     * - m2 类型（SNAPSHOT）：https://bmclapi2.bangbang93.com/maven/（repo.mumfrey.com 已下线）
     */
    private fun resolveRepoUrl(versionNode: JsonObject, isSnapshot: Boolean): String {
        val repo = if (versionNode.has("repo") && versionNode.get("repo").isJsonObject)
            versionNode.getAsJsonObject("repo") else null
        val type = if (repo != null && repo.has("type") && !repo.get("type").isJsonNull)
            repo.get("type").asString else "ivy"
        if ("m2" == type || isSnapshot) {
            // repo.mumfrey.com 已下线，用 BMCLAPI maven 镜像
            return BMCLAPI_MAVEN
        }
        // ivy: dl.liteloader.com/versions/（Cloudflare CDN，强制 HTTPS 防中间人篡改）
        return "https://dl.liteloader.com/versions/"
    }

    /**
     * 获取 minecraftArguments。
     * 优先从已安装的父版本 JSON 读取，找不到则用 1.7.x-1.12.x 标准格式。
     */
    private fun resolveMinecraftArguments(gameVersion: String): String {
        // 尝试读取已安装的父版本 JSON
        val parentJson = paths.versions.resolve(gameVersion).resolve("$gameVersion.json")
        if (Files.exists(parentJson)) {
            try {
                val content = FileUtils.readString(parentJson, StandardCharsets.UTF_8)
                val parent = parseJsonObject(content, "父版本 $gameVersion")
                if (parent.has("minecraftArguments") && !parent.get("minecraftArguments").isJsonNull) {
                    return parent.get("minecraftArguments").asString
                }
            } catch (_: IOException) {
                // 读取失败，用默认值
            }
        }
        // 1.7.x-1.12.x 标准参数
        return ("--username \${auth_name} --version \${version_name} --gameDir \${game_directory} "
                + "--assetsDir \${assets_root} --assetIndex \${assets_index_name} "
                + "--uuid \${auth_uuid} --accessToken \${auth_access_token} "
                + "--userProperties \${user_properties} --userType \${user_type}")
    }

    /** 解析 JSON 对象，非 JSON 响应给出有意义的错误信息 */
    @Throws(IOException::class)
    private fun parseJsonObject(json: String?, context: String): JsonObject {
        val trimmed = json?.trim() ?: ""
        if (trimmed.isEmpty()) {
            throw IOException("服务器返回空响应: $context")
        }
        val first = trimmed[0]
        if (first != '{' && first != '[') {
            val preview = if (trimmed.length > 200) trimmed.substring(0, 200) + "..." else trimmed
            throw IOException("服务器返回非 JSON 内容（可能为错误页面）: $context\n响应内容: $preview")
        }
        try {
            return JsonParser.parseString(trimmed).asJsonObject
        } catch (e: Exception) {
            val preview = if (trimmed.length > 200) trimmed.substring(0, 200) + "..." else trimmed
            throw IOException("JSON 解析失败: $context\n错误: ${e.message}\n响应内容: $preview")
        }
    }

    companion object {
        private const val MANIFEST_URL = "https://dl.liteloader.com/versions/versions.json"
        private const val BMCLAPI_MAVEN = "https://bmclapi2.bangbang93.com/maven/"
    }
}
