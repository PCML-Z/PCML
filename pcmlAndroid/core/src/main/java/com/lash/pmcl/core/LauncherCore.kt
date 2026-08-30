package com.lash.pmcl.core

import com.lash.pmcl.core.auth.AuthService
import com.lash.pmcl.core.auth.TokenEncryptor
import com.lash.pmcl.core.cache.DataCache
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.download.MirrorManager
import com.lash.pmcl.core.gamecontent.ConfigFileManager
import com.lash.pmcl.core.gamecontent.ResourcePackManager
import com.lash.pmcl.core.gamecontent.ScreenshotManager
import com.lash.pmcl.core.gamecontent.ShaderPackManager
import com.lash.pmcl.core.gamecontent.WorldManager
import com.lash.pmcl.core.install.IntegrityChecker
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.instance.InstanceManager
import com.lash.pmcl.core.launch.LaunchManager
import com.lash.pmcl.core.market.CurseForgeClient
import com.lash.pmcl.core.market.ModMarketManager
import com.lash.pmcl.core.market.ModrinthClient
import com.lash.pmcl.core.modpack.ModpackManager
import com.lash.pmcl.core.mods.ModDependencyResolver
import com.lash.pmcl.core.mods.ModDropInstaller
import com.lash.pmcl.core.mods.ModManager
import com.lash.pmcl.core.mods.ModTagStore
import com.lash.pmcl.core.mods.ModUpdateChecker
import com.lash.pmcl.core.modloader.ModLoaderManager
import com.lash.pmcl.core.multiplayer.ServerPinger
import com.lash.pmcl.core.news.NewsClient
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.preferences.Preferences
import com.lash.pmcl.core.stats.PlayTimeTracker
import com.lash.pmcl.core.update.SelfUpdater
import com.lash.pmcl.core.update.UpdateSignatureVerifier
import com.lash.pmcl.core.version.VersionManager
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/**
 * 启动器内核入口 — Android 版。
 *
 * 与桌面版差异：
 * - 不再硬编码 ~/.pmcl/，所有路径通过 [PmclPaths] 注入
 * - 移除桌面专属模块：friend / plugin / metal / migration / runtime / web / translate / theme / boot
 * - 移除 JVM 代理系统属性设置（Android 不使用 JVM system properties 做代理）
 * - 移除可选子系统容错包装（Android 版所有模块均为必需或直接为 null）
 * - 移除插件注入机制
 *
 * 初始化顺序严格按依赖关系排列，靠前的模块先构建。
 */
class LauncherCore(
    val paths: PmclPaths,
    val androidId: String,
    val appDataDir: Path,
    appVersion: String = "0.0.0",
) {

    // ===== 基础设施 =====
    val preferences: Preferences = Preferences(paths)

    // ===== 共享镜像管理器（避免 VersionManager 和 DownloadManager 熔断状态不一致） =====
    private val sharedMirror = MirrorManager().also {
        it.type = MirrorManager.MirrorType.valueOf(
            preferences.getMirrorType().takeIf { v -> v in listOf("OFFICIAL", "BMCLAPI", "CUSTOM") } ?: "BMCLAPI"
        )
        it.customBase = preferences.getCustomMirrorBase()
    }

    // ===== 版本管理 =====
    val versionManager: VersionManager = VersionManager(
        paths = paths,
        mirrorManager = sharedMirror
    )

    // ===== 下载 =====
    val downloadManager: DownloadManager = DownloadManager(workDir = paths.minecraftWorkDir).also { dm ->
        applyNetworkTo(dm)
    }

    // ===== 鉴权 =====
    val tokenEncryptor: TokenEncryptor = TokenEncryptor(paths, androidId, appDataDir)
    val authService: AuthService = AuthService(paths, tokenEncryptor)

    // ===== 启动 =====
    val launchManager: LaunchManager = LaunchManager(paths, preferences)

    // ===== 安装 =====
    val versionInstaller: VersionInstaller = VersionInstaller(paths, versionManager, downloadManager)
    val integrityChecker: IntegrityChecker = IntegrityChecker(paths)

    // ===== 市场 =====
    val modrinthClient: ModrinthClient = ModrinthClient(downloadManager)
    val curseForgeClient: CurseForgeClient = CurseForgeClient("", downloadManager)
    val modMarketManager: ModMarketManager = ModMarketManager(paths, downloadManager, "", preferences)

    // ===== 模组加载器 =====
    val modLoaderManager: ModLoaderManager = ModLoaderManager(paths, downloadManager, versionInstaller)

    // ===== 实例管理 =====
    val instanceManager: InstanceManager = InstanceManager(paths)

    // ===== 整合包管理 =====
    val modpackManager: ModpackManager = ModpackManager(
        paths, downloadManager, versionInstaller, modLoaderManager, preferences
    )

    // ===== 模组管理 =====
    val modManager: ModManager = ModManager(paths.minecraftWorkDir.resolve("mods"))
    val modTagStore: ModTagStore = ModTagStore(paths.modTags)
    val modDropInstaller: ModDropInstaller = ModDropInstaller(paths, preferences, modrinthClient)

    private val modsExecutor: ExecutorService = Executors.newFixedThreadPool(5) { r ->
        Thread(r, "pmcl-mods").apply { isDaemon = true }
    }

    val modUpdateChecker: ModUpdateChecker = ModUpdateChecker(
        paths.minecraftWorkDir.resolve("mods"),
        modrinthClient, curseForgeClient, downloadManager, modsExecutor
    )

    val modDependencyResolver: ModDependencyResolver = ModDependencyResolver(
        paths.minecraftWorkDir.resolve("mods"), modrinthClient, modsExecutor
    )

    // ===== 游戏内容管理 =====
    val worldManager: WorldManager = WorldManager(paths.minecraftWorkDir)
    val screenshotManager: ScreenshotManager = ScreenshotManager(paths.minecraftWorkDir)
    val resourcePackManager: ResourcePackManager = ResourcePackManager(paths.minecraftWorkDir)
    val shaderPackManager: ShaderPackManager = ShaderPackManager(paths.minecraftWorkDir)
    val configFileManager: ConfigFileManager = ConfigFileManager(paths.minecraftWorkDir.resolve("config"))

    // ===== 缓存 =====
    val dataCache: DataCache = DataCache(paths.cache)

    // ===== 统计 =====
    val playTimeTracker: PlayTimeTracker = PlayTimeTracker(paths.playtime)

    // ===== 新闻 =====
    val newsClient: NewsClient = NewsClient()

    // ===== 联机（仅 ServerPinger，无 EasyTier/Terracotta/ConnectX 后端） =====
    val serverPinger: ServerPinger = ServerPinger

    // ===== 自更新 =====
    val signatureVerifier: UpdateSignatureVerifier = UpdateSignatureVerifier(null)
    val selfUpdater: SelfUpdater = SelfUpdater(
        downloadManager, paths, signatureVerifier,
        "https://pmcl.lash.com/update/manifest.json", appVersion
    )

    /** 用户修改网络配置后立即应用到下载客户端。 */
    fun applyNetworkPreferences() {
        applyNetworkTo(downloadManager)
    }

    /**
     * 用当前代理/镜像探测连通性。
     */
    fun testProxyConnection(): String {
        val url = when (preferences.getMirrorType()) {
            "BMCLAPI" -> "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json"
            "CUSTOM" -> {
                val base = preferences.getCustomMirrorBase().trim().trimEnd('/')
                if (base.isNotEmpty()) "$base/mc/game/version_manifest_v2.json"
                else "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
            }
            else -> "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        }
        return downloadManager.testConnection(url)
    }

    private fun applyNetworkTo(dm: DownloadManager) {
        val pref = preferences
        val mt = when (pref.getMirrorType()) {
            "OFFICIAL" -> MirrorManager.MirrorType.OFFICIAL
            "CUSTOM" -> MirrorManager.MirrorType.CUSTOM
            else -> MirrorManager.MirrorType.BMCLAPI
        }
        sharedMirror.type = mt
        sharedMirror.customBase = pref.getCustomMirrorBase()
        dm.reconfigure(
            mirrorType = mt,
            customMirrorBase = pref.getCustomMirrorBase(),
            speedLimitKb = pref.getDownloadSpeedLimitKb(),
            downloadRetryCount = pref.getDownloadRetryCount(),
            enableResume = pref.isEnableResume(),
            chunkedDownloadThreads = pref.getChunkedDownloadThreads(),
            proxy = pref.toJavaProxy(),
            useProxyAuth = pref.useHttpProxyAuth(),
            proxyUser = pref.getProxyUsername(),
            proxyPass = pref.getProxyPassword(),
        )
    }

    /**
     * 应用语言偏好。
     */
    fun applyLanguage(lang: String) {
        try {
            val locale = when (lang) {
                "zh_CN" -> java.util.Locale.SIMPLIFIED_CHINESE
                "zh_TW" -> java.util.Locale.TRADITIONAL_CHINESE
                "en_US" -> java.util.Locale.US
                "ja_JP" -> java.util.Locale.JAPANESE
                else -> java.util.Locale.SIMPLIFIED_CHINESE
            }
            com.lash.pmcl.core.i18n.I18n.setLocale(locale)
        } catch (_: Exception) {
            // 降级到默认语言，不中断启动
        }
    }

    /**
     * 关闭内核子系统（幂等），按顺序释放资源。
     */
    fun shutdown() {
        safeShutdown("launchManager") { launchManager.shutdownAll() }
        safeShutdown("downloadManager") { downloadManager.shutdown() }
        safeShutdown("modsExecutor") { modsExecutor.shutdownNow() }
        safeShutdown("authService") { authService.shutdown() }
        safeShutdown("preferences") { preferences.shutdown() }
    }

    private inline fun safeShutdown(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            System.err.println("[LauncherCore] shutdown $name failed: ${e.message}")
        }
    }
}
