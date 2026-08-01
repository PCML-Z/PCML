package com.lash.pmcl.core.market

import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.paths.PmclPaths
import okhttp3.OkHttpClient
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Consumer

/**
 * 模组市场聚合管理：同时支持 CurseForge 与 Modrinth。
 *
 * 通过 [search] 聚合两个平台结果，通过 [installMod] 下载到 mods 目录。
 *
 * Android 版本：从 Java 移植，移除 LauncherConfig/环境变量依赖，
 * 路径通过 [PmclPaths] 获取，CurseForge API Key 通过构造函数传入。
 */
class ModMarketManager(
    private val paths: PmclPaths,
    private val downloads: DownloadManager,
    curseForgeApiKey: String
) {

    private val clients: MutableList<ModMarketClient> = CopyOnWriteArrayList()

    init {
        // Modrinth 不需要 key，直接接入
        clients.add(ModrinthClient(downloads))
        // CurseForge 仅在 API Key 非空时注册
        if (curseForgeApiKey.isNotEmpty()) {
            clients.add(CurseForgeClient(curseForgeApiKey, downloads))
        }
    }

    /** 是否启用了 CurseForge（取决于是否配置 API Key） */
    fun hasCurseForge(): Boolean =
        clients.any { "curseforge" == it.source() }

    /** 获取 Modrinth 客户端实例（用于整合包更新检查等高级 API） */
    fun getModrinthClient(): ModrinthClient? =
        clients.filterIsInstance<ModrinthClient>().firstOrNull()

    /** 获取所有已注册的市场客户端列表（不可变视图） */
    fun getClients(): List<ModMarketClient> = Collections.unmodifiableList(clients)

    /**
     * 更新所有客户端的 OkHttpClient 引用（用户在设置中修改代理后调用）。
     * 让 mod 市场请求也能立即走代理。
     */
    fun updateHttpClients(http: OkHttpClient) {
        for (c in clients) {
            c.updateHttpClient(http)
        }
    }

    /**
     * 跨平台聚合搜索：并发查询所有客户端，合并结果。
     * 单源失败降级为空列表，不影响其他源。
     */
    fun search(query: String, gameVersion: String, loader: String, limit: Int):
        CompletableFuture<List<ModProject>> {
        val futures = ArrayList<CompletableFuture<List<ModProject>>>()
        for (c in clients) {
            futures.add(c.search(query, gameVersion, loader, limit)
                .exceptionally { ex ->
                    logMarketFailure(c, "search", ex)
                    emptyList()
                })
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply {
                val merged = ArrayList<ModProject>()
                for (f in futures) {
                    merged.addAll(f.join())
                }
                merged
            }
    }

    /**
     * 跨平台聚合搜索（带分类过滤）：关键字 + 分类 AND 关系。
     * 不支持分类的平台会忽略 category（仅按关键字搜索）。
     */
    fun search(query: String, gameVersion: String, loader: String,
               category: String, limit: Int): CompletableFuture<List<ModProject>> {
        if (category.isEmpty()) {
            return search(query, gameVersion, loader, limit)
        }
        val futures = ArrayList<CompletableFuture<List<ModProject>>>()
        for (c in clients) {
            futures.add(c.search(query, gameVersion, loader, category, limit)
                .exceptionally { ex ->
                    logMarketFailure(c, "search+category", ex)
                    emptyList()
                })
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply {
                val merged = ArrayList<ModProject>()
                for (f in futures) {
                    merged.addAll(f.join())
                }
                merged
            }
    }

    /**
     * 跨平台聚合获取热门项目：并发查询所有客户端，合并结果。
     * 用于「热门推荐」卡片网格展示。
     */
    fun popular(gameVersion: String, loader: String, limit: Int):
        CompletableFuture<List<ModProject>> {
        val futures = ArrayList<CompletableFuture<List<ModProject>>>()
        for (c in clients) {
            futures.add(c.popular(gameVersion, loader, limit)
                .exceptionally { ex ->
                    logMarketFailure(c, "popular", ex)
                    emptyList()
                })
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply {
                val merged = ArrayList<ModProject>()
                for (f in futures) {
                    merged.addAll(f.join())
                }
                merged
            }
    }

    /**
     * 跨平台聚合按分类浏览：并发查询所有客户端，合并结果。
     * 用于「分类推荐」功能：用户点击分类标签后加载该分类下的热门项目。
     * 不支持分类浏览的平台会返回空列表，不影响其他源。
     */
    fun searchByCategory(category: String, gameVersion: String,
                         loader: String, limit: Int): CompletableFuture<List<ModProject>> {
        if (category.isEmpty()) {
            return popular(gameVersion, loader, limit)
        }
        val futures = ArrayList<CompletableFuture<List<ModProject>>>()
        for (c in clients) {
            futures.add(c.searchByCategory(category, gameVersion, loader, limit)
                .exceptionally { ex ->
                    logMarketFailure(c, "searchByCategory", ex)
                    emptyList()
                })
        }
        return CompletableFuture.allOf(*futures.toTypedArray())
            .thenApply {
                val merged = ArrayList<ModProject>()
                for (f in futures) {
                    merged.addAll(f.join())
                }
                merged
            }
    }

    /**
     * 列出某项目所有文件（按来源分发到对应客户端）。
     */
    fun listFiles(project: ModProject): CompletableFuture<List<ModFile>> {
        for (c in clients) {
            if (c.source() == project.source) {
                return c.listFiles(project.id)
            }
        }
        return CompletableFuture.completedFuture(emptyList())
    }

    /**
     * 安装模组：下载到 mods 目录。
     *
     * @param file         模组文件
     * @param gameVersion  目标 MC 版本（决定 mods 子目录，如 mods/1.20.4）
     */
    fun installMod(file: ModFile, gameVersion: String): CompletableFuture<Void> =
        installMod(file, gameVersion, null, null, null)

    /**
     * 安装模组：下载到 mods 目录，带进度回调。
     *
     * @param file         模组文件
     * @param gameVersion  目标 MC 版本（决定 mods 子目录，如 mods/1.20.4）
     * @param onStatus     状态回调（如 "正在下载 xxx.jar..."），可为 null
     */
    fun installMod(file: ModFile, gameVersion: String,
                   onStatus: Consumer<String>?): CompletableFuture<Void> =
        installMod(file, gameVersion, null, null, onStatus)

    /**
     * 安装模组到 mods 目录。
     *
     * 版本隔离开启时，模组安装到 `instances/<versionId>/mods/`；
     * 否则安装到 `mods/<gameVersion>/`。
     *
     * @param file             模组文件
     * @param gameVersion      目标 MC 版本（非隔离模式下决定 mods 子目录）
     * @param versionId        版本 ID（隔离模式下决定 instance 目录），可为 null
     * @param versionIsolation 是否开启版本隔离，可为 null
     * @param onStatus         状态回调
     */
    fun installMod(file: ModFile, gameVersion: String,
                   versionId: String?, versionIsolation: Boolean?,
                   onStatus: Consumer<String>?): CompletableFuture<Void> =
        CompletableFuture.runAsync {
            try {
                var modsDir: Path
                if (versionIsolation == true && !versionId.isNullOrEmpty()) {
                    // H20: versionId path traversal 防护
                    requireSafeInstanceId(versionId)
                    val instancesRoot = paths.instances.toAbsolutePath().normalize()
                    val instanceDir = instancesRoot.resolve(versionId).normalize()
                    if (!instanceDir.startsWith(instancesRoot)) {
                        throw IOException("versionId path escapes instances dir: $versionId")
                    }
                    // 版本隔离：安装到 instances/<versionId>/mods/
                    modsDir = instanceDir.resolve("mods")
                } else {
                    modsDir = paths.minecraftWorkDir.resolve("mods")
                    if (gameVersion.isNotEmpty()) {
                        requireSafeInstanceId(gameVersion)
                        val modsRoot = modsDir.toAbsolutePath().normalize()
                        modsDir = modsRoot.resolve(gameVersion).normalize()
                        if (!modsDir.startsWith(modsRoot)) {
                            throw IOException("gameVersion path escapes mods dir: $gameVersion")
                        }
                    }
                }
                val fileName = file.fileName
                if (fileName.isBlank() || fileName.contains("..") ||
                    fileName.contains("/") || fileName.contains("\\") ||
                    fileName.indexOf('\u0000') >= 0
                ) {
                    throw IOException("非法模组文件名: $fileName")
                }
                val modsAbs = modsDir.toAbsolutePath().normalize()
                val target = modsAbs.resolve(fileName).normalize()
                if (!target.startsWith(modsAbs)) {
                    throw IOException("模组路径越界: $fileName")
                }
                // 重复安装检测：覆盖下载
                if (Files.exists(target)) {
                    onStatus?.accept("覆盖已存在: $fileName")
                }
                Files.createDirectories(modsAbs)
                onStatus?.accept("正在下载: ${file.fileName} (${file.fileSize / 1024} KB)")
                val sha1 = file.getSha1()
                val sha512 = file.getSha512()
                if (sha1.isNullOrBlank() && sha512.isNullOrBlank()) {
                    throw IOException("模组缺少 SHA-1/SHA-512，拒绝安装未校验文件: ${file.fileName}")
                }
                downloads.downloadToVerified(file.downloadUrl, target, sha1, sha512)
                onStatus?.accept("完成: ${file.fileName}")
            } catch (e: Exception) {
                throw RuntimeException("模组下载失败: ${file.fileName}", e)
            }
        }

    /** 校验实例 ID 不含路径穿越字符（替代桌面版 InstanceManager.requireSafeInstanceId） */
    @Throws(IOException::class)
    private fun requireSafeInstanceId(id: String) {
        if (id.contains("..") || id.contains("/") ||
            id.contains("\\") || id.indexOf('\u0000') >= 0
        ) {
            throw IOException("非法实例 ID: $id")
        }
    }

    /** 单源失败时记录日志，避免 UI 把"源故障"误当成"无结果"却无从排查 */
    private fun logMarketFailure(client: ModMarketClient?, op: String, ex: Throwable) {
        var root: Throwable = ex
        while (root.cause != null && root.cause !== root) {
            root = root.cause!!
        }
        val src = client?.source() ?: "?"
        val msg = root.message ?: root.toString()
        System.err.println("[ModMarket] $src $op 失败: $msg")
    }
}
