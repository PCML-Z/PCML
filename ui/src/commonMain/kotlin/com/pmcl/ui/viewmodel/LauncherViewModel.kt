package com.pmcl.ui.viewmodel

import com.google.gson.reflect.TypeToken
import com.pmcl.core.LauncherConfig
import com.pmcl.core.LauncherCore
import com.pmcl.core.auth.Account
import com.pmcl.core.auth.DeviceCode
import com.pmcl.core.cache.DataCache
import com.pmcl.core.download.DownloadQueueManager
import com.pmcl.core.install.InstallProgress
import com.pmcl.core.launch.GameLogger
import com.pmcl.core.launch.JavaRuntimeFinder
import com.pmcl.core.launch.ExternalLauncherDetector
import com.pmcl.core.market.ModFile
import com.pmcl.core.market.ModProject
import com.pmcl.core.modloader.ModLoader
import com.pmcl.core.modloader.ModLoaderVersion
import com.pmcl.core.mods.ModConflictChecker
import com.pmcl.core.mods.ModMeta
import com.pmcl.core.mods.ModScanner
import com.pmcl.core.mods.ModUpdateChecker
import com.pmcl.core.mods.ModDependencyResolver
import com.pmcl.core.modpack.ModpackManager
import com.pmcl.core.preferences.Preferences
import com.pmcl.core.stats.PlayTimeTracker
import com.pmcl.core.version.McVersion
import com.pmcl.core.gamecontent.WorldManager
import com.pmcl.core.gamecontent.ScreenshotManager
import com.pmcl.core.gamecontent.ResourcePackManager
import com.pmcl.core.gamecontent.ShaderPackManager
import com.pmcl.core.gamecontent.ConfigFileManager
import com.pmcl.core.gamecontent.DatapackManager
import com.pmcl.core.install.IntegrityChecker
import com.pmcl.core.launch.CrashAnalyzer
import com.pmcl.core.web.WikiBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 启动器主 ViewModel：UI 与 Java 内核之间的桥接层。
 */
class LauncherViewModel {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val core = LauncherCore()

    /** 账号持久化文件 */
    private val accountFile = Paths.get(System.getProperty("user.home"), ".pmcl", "accounts.json")

    // ===== 版本列表 =====
    private val _versions = MutableStateFlow<List<McVersion>>(emptyList())
    val versions: StateFlow<List<McVersion>> = _versions.asStateFlow()

    private val _localVersions = MutableStateFlow<List<String>>(emptyList())
    val localVersions: StateFlow<List<String>> = _localVersions.asStateFlow()

    // 本地版本详细信息（含 jar/json/inheritsFrom）
    private val _localVersionInfos = MutableStateFlow<List<com.pmcl.core.version.VersionManager.LocalVersionInfo>>(emptyList())
    val localVersionInfos: StateFlow<List<com.pmcl.core.version.VersionManager.LocalVersionInfo>> = _localVersionInfos.asStateFlow()

    // 固定的版本磁贴
    private val _pinnedVersions = MutableStateFlow<List<String>>(emptyList())
    val pinnedVersions: StateFlow<List<String>> = _pinnedVersions.asStateFlow()

    // 磁贴自定义名称（versionId → 显示名）
    private val _pinnedTileLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val pinnedTileLabels: StateFlow<Map<String, String>> = _pinnedTileLabels.asStateFlow()

    // 最近使用（LRU，最多 5 个）
    private val _recentVersions = MutableStateFlow<List<String>>(emptyList())
    val recentVersions: StateFlow<List<String>> = _recentVersions.asStateFlow()

    // 最后游玩时间戳（versionId → millis），用于磁贴/列表显示
    private val _lastPlayedTimes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastPlayedTimes: StateFlow<Map<String, Long>> = _lastPlayedTimes.asStateFlow()

    // 扫描进度（null 表示未在扫描）
    private val _scanProgress = MutableStateFlow<com.pmcl.core.version.VersionManager.ScanProgress?>(null)
    val scanProgress: StateFlow<com.pmcl.core.version.VersionManager.ScanProgress?> = _scanProgress.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _selectedVersion = MutableStateFlow<String?>(null)
    val selectedVersion: StateFlow<String?> = _selectedVersion.asStateFlow()

    // ===== 状态/账号 =====
    private val _status = MutableStateFlow("就绪")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    // ===== 安装进度 =====
    private val _installProgress = MutableStateFlow<InstallProgress?>(null)
    val installProgress: StateFlow<InstallProgress?> = _installProgress.asStateFlow()

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    // ===== 模组加载器 =====
    private val _modLoaderVersions = MutableStateFlow<List<ModLoaderVersion>>(emptyList())
    val modLoaderVersions: StateFlow<List<ModLoaderVersion>> = _modLoaderVersions.asStateFlow()

    // ===== 模组市场 =====
    private val _marketResults = MutableStateFlow<List<ModProject>>(emptyList())
    val marketResults: StateFlow<List<ModProject>> = _marketResults.asStateFlow()

    private val _currentModFiles = MutableStateFlow<List<ModFile>>(emptyList())
    val currentModFiles: StateFlow<List<ModFile>> = _currentModFiles.asStateFlow()

    private val _marketLoading = MutableStateFlow(false)
    val marketLoading: StateFlow<Boolean> = _marketLoading.asStateFlow()

    // 热门推荐（按下载量排序，进入页面时自动加载）
    private val _popularMods = MutableStateFlow<List<ModProject>>(emptyList())
    val popularMods: StateFlow<List<ModProject>> = _popularMods.asStateFlow()

    private val _popularLoading = MutableStateFlow(false)
    val popularLoading: StateFlow<Boolean> = _popularLoading.asStateFlow()

    // 分类推荐（用户选择分类标签后加载该分类下的热门项目）
    private val _categoryResults = MutableStateFlow<List<ModProject>>(emptyList())
    val categoryResults: StateFlow<List<ModProject>> = _categoryResults.asStateFlow()

    private val _categoryLoading = MutableStateFlow(false)
    val categoryLoading: StateFlow<Boolean> = _categoryLoading.asStateFlow()

    // 当前选中的分类 slug（空字符串表示未选择，显示热门推荐）
    private val _selectedCategory = MutableStateFlow("")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    // 点击卡片进入的详情项目（null 表示未选中，显示热门网格）
    private val _detailProject = MutableStateFlow<ModProject?>(null)
    val detailProject: StateFlow<ModProject?> = _detailProject.asStateFlow()

    // ===== 已安装 mod 列表 + 冲突检测 =====
    private val _installedMods = MutableStateFlow<List<ModMeta>>(emptyList())
    val installedMods: StateFlow<List<ModMeta>> = _installedMods.asStateFlow()

    private val _modConflicts = MutableStateFlow<ModConflictChecker.Result?>(null)
    val modConflicts: StateFlow<ModConflictChecker.Result?> = _modConflicts.asStateFlow()

    // ===== 整合包管理 =====
    private val _modpacks = MutableStateFlow<List<ModpackManager.InstalledModpack>>(emptyList())
    val modpacks: StateFlow<List<ModpackManager.InstalledModpack>> = _modpacks.asStateFlow()

    private val _modpackProgress = MutableStateFlow<InstallProgress?>(null)
    val modpackProgress: StateFlow<InstallProgress?> = _modpackProgress.asStateFlow()

    private val _modpackBusy = MutableStateFlow(false)
    val modpackBusy: StateFlow<Boolean> = _modpackBusy.asStateFlow()

    // ===== 下载队列 =====
    private val _queueTasks = MutableStateFlow<List<DownloadQueueManager.QueueTask>>(emptyList())
    val queueTasks: StateFlow<List<DownloadQueueManager.QueueTask>> = _queueTasks.asStateFlow()

    private val _queueSummary = MutableStateFlow<DownloadQueueManager.QueueSummary>(
        DownloadQueueManager.QueueSummary(0, 0, 0, 0, 0, 0, 0L, 0L)
    )
    val queueSummary: StateFlow<DownloadQueueManager.QueueSummary> = _queueSummary.asStateFlow()

    /** 队列监听器初始化标志，避免重复注册 */
    private var queueListenerRegistered = false

    // ===== 配置文件编辑器 =====
    private val _configFiles = MutableStateFlow<List<ConfigFileManager.ConfigFileEntry>>(emptyList())
    val configFiles: StateFlow<List<ConfigFileManager.ConfigFileEntry>> = _configFiles.asStateFlow()

    private val _configFileContent = MutableStateFlow<String?>(null)
    val configFileContent: StateFlow<String?> = _configFileContent.asStateFlow()

    private val _configFileDirty = MutableStateFlow(false)
    val configFileDirty: StateFlow<Boolean> = _configFileDirty.asStateFlow()

    private val _currentConfigPath = MutableStateFlow<String?>(null)
    val currentConfigPath: StateFlow<String?> = _currentConfigPath.asStateFlow()

    private val _configCurrentDir = MutableStateFlow("")
    val configCurrentDir: StateFlow<String> = _configCurrentDir.asStateFlow()

    // ===== 模组更新检测 =====
    private val _modUpdates = MutableStateFlow<List<ModUpdateChecker.UpdateInfo>>(emptyList())
    val modUpdates: StateFlow<List<ModUpdateChecker.UpdateInfo>> = _modUpdates.asStateFlow()

    private val _checkingUpdates = MutableStateFlow(false)
    val checkingUpdates: StateFlow<Boolean> = _checkingUpdates.asStateFlow()

    private val _updateCheckProgress = MutableStateFlow<Pair<Int, Int>>(0 to 0)
    val updateCheckProgress: StateFlow<Pair<Int, Int>> = _updateCheckProgress.asStateFlow()

    private val _updatingMod = MutableStateFlow(false)
    val updatingMod: StateFlow<Boolean> = _updatingMod.asStateFlow()

    /** 更新检测用的 gameVersion（从当前选中版本推断） */
    private val _updateGameVersion = MutableStateFlow("")
    val updateGameVersion: StateFlow<String> = _updateGameVersion.asStateFlow()

    // ===== 模组依赖安装 =====
    private val _installingDeps = MutableStateFlow(false)
    val installingDeps: StateFlow<Boolean> = _installingDeps.asStateFlow()

    private val _depInstallResult = MutableStateFlow<ModDependencyResolver.DependencyResult?>(null)
    val depInstallResult: StateFlow<ModDependencyResolver.DependencyResult?> = _depInstallResult.asStateFlow()

    // ===== 游戏时长统计 =====
    private val _playTimeStats = MutableStateFlow<PlayTimeTracker.OverallStat?>(null)
    val playTimeStats: StateFlow<PlayTimeTracker.OverallStat?> = _playTimeStats.asStateFlow()

    private val _dailyStats = MutableStateFlow<List<PlayTimeTracker.DailyStat>>(emptyList())
    val dailyStats: StateFlow<List<PlayTimeTracker.DailyStat>> = _dailyStats.asStateFlow()

    /** 统计图表展示的天数范围 */
    private val _statsDays = MutableStateFlow(7)
    val statsDays: StateFlow<Int> = _statsDays.asStateFlow()

    // ===== 微软登录 =====
    private val _deviceCode = MutableStateFlow<DeviceCode?>(null)
    val deviceCode: StateFlow<DeviceCode?> = _deviceCode.asStateFlow()

    private val _loggingIn = MutableStateFlow(false)
    val loggingIn: StateFlow<Boolean> = _loggingIn.asStateFlow()

    // ===== 启动日志 =====
    private val _gameLogs = MutableStateFlow<List<String>>(emptyList())
    val gameLogs: StateFlow<List<String>> = _gameLogs.asStateFlow()

    // ===== 日志导出/分享 =====
    private val _logSharing = MutableStateFlow(false)
    val logSharing: StateFlow<Boolean> = _logSharing.asStateFlow()
    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    private val _gameRunning = MutableStateFlow(false)
    val gameRunning: StateFlow<Boolean> = _gameRunning.asStateFlow()

    // ===== 多实例启动 =====
    data class RunningInstance(
        val id: String,
        val versionId: String,
        val accountName: String,
        val startTime: Long,
        val active: Boolean = false
    )
    private val _runningInstances = MutableStateFlow<List<RunningInstance>>(emptyList())
    val runningInstances: StateFlow<List<RunningInstance>> = _runningInstances.asStateFlow()
    private val instanceLogs = mutableMapOf<String, MutableList<String>>()
    private val instanceLoggers = mutableMapOf<String, GameLogger?>()

    // ===== 兼容性选项（检测到外部启动器时弹出选择） =====
    data class CompatOption(
        val title: String,
        val description: String,
        val action: () -> Unit
    )
    private val _compatOptions = MutableStateFlow<List<CompatOption>>(emptyList())
    val compatOptions: StateFlow<List<CompatOption>> = _compatOptions.asStateFlow()
    private val _compatTitle = MutableStateFlow("")
    val compatTitle: StateFlow<String> = _compatTitle.asStateFlow()
    fun dismissCompatOptions() { _compatOptions.value = emptyList() }

    // ===== Java 运行时下载 =====
    private val _javaDownloading = MutableStateFlow(false)
    val javaDownloading: StateFlow<Boolean> = _javaDownloading.asStateFlow()

    private val _javaDownloadStatus = MutableStateFlow("")
    val javaDownloadStatus: StateFlow<String> = _javaDownloadStatus.asStateFlow()

    // ===== 世界 / 截图 / 资源包 =====
    private val _worlds = MutableStateFlow<List<WorldManager.WorldInfo>>(emptyList())
    val worlds: StateFlow<List<WorldManager.WorldInfo>> = _worlds.asStateFlow()

    private val _screenshots = MutableStateFlow<List<ScreenshotManager.Screenshot>>(emptyList())
    val screenshots: StateFlow<List<ScreenshotManager.Screenshot>> = _screenshots.asStateFlow()

    private val _resourcePacks = MutableStateFlow<List<ResourcePackManager.Pack>>(emptyList())
    val resourcePacks: StateFlow<List<ResourcePackManager.Pack>> = _resourcePacks.asStateFlow()

    private val _shaderPacks = MutableStateFlow<List<ShaderPackManager.ShaderPack>>(emptyList())
    val shaderPacks: StateFlow<List<ShaderPackManager.ShaderPack>> = _shaderPacks.asStateFlow()

    private val _datapacks = MutableStateFlow<List<DatapackManager.Datapack>>(emptyList())
    val datapacks: StateFlow<List<DatapackManager.Datapack>> = _datapacks.asStateFlow()

    // ===== 完整性校验 / 崩溃分析 =====
    private val _integrityResult = MutableStateFlow<IntegrityChecker.Result?>(null)
    val integrityResult: StateFlow<IntegrityChecker.Result?> = _integrityResult.asStateFlow()

    private val _crashReports = MutableStateFlow<List<CrashAnalyzer.CrashReport>>(emptyList())
    val crashReports: StateFlow<List<CrashAnalyzer.CrashReport>> = _crashReports.asStateFlow()

    /** 游戏异常退出事件（null 表示无崩溃，UI 监听此流弹出崩溃窗口） */
    data class CrashEvent(
        val exitCode: Int,
        val report: CrashAnalyzer.CrashReport?,   // 崩溃报告（可能为 null，如 crash-reports 无新增）
        val recentLogs: List<String>,              // 最近日志片段
        val versionId: String
    )
    private val _crashEvent = MutableStateFlow<CrashEvent?>(null)
    val crashEvent: StateFlow<CrashEvent?> = _crashEvent.asStateFlow()

    /** 清除崩溃事件（UI 关闭弹窗时调用） */
    fun clearCrashEvent() { _crashEvent.value = null }

    /** 恢复操作执行后的用户反馈消息（UI 可监听显示 snackbar） */
    private val _recoveryMessage = MutableStateFlow<String?>(null)
    val recoveryMessage: StateFlow<String?> = _recoveryMessage.asStateFlow()
    fun clearRecoveryMessage() { _recoveryMessage.value = null }

    /** 导航请求：恢复操作或搜索可请求跳转到指定页面 */
    private val _navigationRequest = MutableStateFlow<String?>(null)
    val navigationRequest: StateFlow<String?> = _navigationRequest.asStateFlow()
    fun requestNavigation(route: String) { _navigationRequest.value = route }
    fun clearNavigationRequest() { _navigationRequest.value = null }

    /** Hub 页面 Tab 跳转请求：命令面板可请求跳转到 Hub 页面的指定 Tab */
    private val _hubTabRequest = MutableStateFlow<Pair<String, Int>?>(null)
    val hubTabRequest: StateFlow<Pair<String, Int>?> = _hubTabRequest.asStateFlow()
    fun requestHubTab(route: String, tabIndex: Int) { _hubTabRequest.value = route to tabIndex }
    fun clearHubTabRequest() { _hubTabRequest.value = null }

    // ===== 游戏安装前询问事件（用于弹窗询问是否同时安装模组加载器）=====
    /**
     * 用户点击安装游戏时触发的事件（安装开始前）。
     * UI 监听此流弹出模组加载器选择对话框，用户确认后再执行实际安装。
     * null 表示无事件（已清除或未触发）。
     */
    data class PreInstallEvent(
        val versionId: String
    )
    private val _preInstallEvent = MutableStateFlow<PreInstallEvent?>(null)
    val preInstallEvent: StateFlow<PreInstallEvent?> = _preInstallEvent.asStateFlow()

    /** 清除安装前询问事件（UI 关闭弹窗时调用） */
    fun clearPreInstallEvent() { _preInstallEvent.value = null }

    // ===== 新闻 =====
    private val _newsItems = MutableStateFlow<List<com.pmcl.core.news.NewsItem>>(emptyList())
    val newsItems: StateFlow<List<com.pmcl.core.news.NewsItem>> = _newsItems.asStateFlow()

    private val _newsLoading = MutableStateFlow(false)
    val newsLoading: StateFlow<Boolean> = _newsLoading.asStateFlow()

    // 新闻文章详情
    private val _articleContent = MutableStateFlow<com.pmcl.core.news.ArticleContent?>(null)
    val articleContent: StateFlow<com.pmcl.core.news.ArticleContent?> = _articleContent.asStateFlow()

    private val _articleLoading = MutableStateFlow(false)
    val articleLoading: StateFlow<Boolean> = _articleLoading.asStateFlow()

    private val _articleError = MutableStateFlow("")
    val articleError: StateFlow<String> = _articleError.asStateFlow()

    // ===== 翻译缓存（key = 原文，value = 译文）=====
    private val _translationCache = MutableStateFlow<Map<String, String>>(emptyMap())
    val translationCache: StateFlow<Map<String, String>> = _translationCache.asStateFlow()

    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating.asStateFlow()
    /** 并发翻译计数器：>0 时 _translating 为 true，用于 UI 显示「翻译中…」 */
    private val translateCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // ===== 多人联机 =====
    private val _mpState = MutableStateFlow<com.pmcl.core.multiplayer.MultiplayerManager.State>(
        com.pmcl.core.multiplayer.MultiplayerManager.State.IDLE
    )
    val mpState: StateFlow<com.pmcl.core.multiplayer.MultiplayerManager.State> = _mpState.asStateFlow()

    private val _mpProgress = MutableStateFlow("")
    val mpProgress: StateFlow<String> = _mpProgress.asStateFlow()

    private val _mpVirtualIp = MutableStateFlow("")
    val mpVirtualIp: StateFlow<String> = _mpVirtualIp.asStateFlow()

    private val _mpInvitation = MutableStateFlow("")
    val mpInvitation: StateFlow<String> = _mpInvitation.asStateFlow()

    /** Terracotta 房客模式：本地 MC 连接地址（如 127.0.0.1:25565） */
    private val _mpLocalMcAddr = MutableStateFlow("")
    val mpLocalMcAddr: StateFlow<String> = _mpLocalMcAddr.asStateFlow()

    // 陶瓦联机错误信息（供 UI 在失败时展示）
    val mpLastError: String get() = core.multiplayer().lastError

    // ===== 首次启动 / 迁移 =====
    private val _firstLaunchCompleted = MutableStateFlow(preferences.isFirstLaunchCompleted())
    val firstLaunchCompleted: StateFlow<Boolean> = _firstLaunchCompleted.asStateFlow()

    private val _migrationSources = MutableStateFlow<List<com.pmcl.core.migration.MigrationManager.Source>>(emptyList())
    val migrationSources: StateFlow<List<com.pmcl.core.migration.MigrationManager.Source>> = _migrationSources.asStateFlow()

    private val _migrating = MutableStateFlow(false)
    val migrating: StateFlow<Boolean> = _migrating.asStateFlow()

    private val _migrationProgress = MutableStateFlow("")
    val migrationProgress: StateFlow<String> = _migrationProgress.asStateFlow()

    /** 当前会话的 GameLogger 实例 */
    @Volatile
    private var gameLogger: GameLogger? = null

    val systemInfo: String
        get() = with(core.runtime()) {
            "OS: ${getOsName()}  |  内存: ${getAvailableMemoryMb()}/${getTotalMemoryMb()} MB  |  推荐: ${getRecommendedMaxMemoryMb()} MB"
        }

    val config: LauncherConfig get() = core.getConfig()
    val preferences: Preferences get() = core.getPreferences()

    init {
        loadSavedAccount()
        refreshInstalledMods()
        // 加载已固定的版本磁贴 + 自定义名称 + 最近使用 + 最后游玩时间
        _pinnedVersions.value = preferences.getPinnedVersions()
        _pinnedTileLabels.value = HashMap(preferences.getPinnedTileLabelsRaw())
        _recentVersions.value = preferences.getRecentVersions()
        _lastPlayedTimes.value = HashMap(preferences.getLastPlayedTimesRaw())
        refreshLocalVersions()
        // 恢复上次选中的版本（待本地版本扫描完成后由 selectVersion 兜底校验存在性）
        val saved = preferences.getLastSelectedVersion()
        if (saved.isNotEmpty()) {
            _selectedVersion.value = saved
        }
        // 初始化联机后端
        core.multiplayer().setBackend(mpBackend)
        // 连接预热：提前对常见下载源建立 TCP+TLS 连接，减少首次下载延迟
        core.downloads().warmupConnections(java.util.List.of(
                "https://piston-meta.mojang.com/",
                "https://bmclapi2.bangbang93.com/"
        ))
    }

    /** 扫描本地已安装版本（详细信息），自动检测 .pmcl/versions + 系统默认 Minecraft 目录，带进度回调 */
    fun refreshLocalVersions() {
        // 先读缓存秒开（独立协程，不阻塞 UI，不影响扫描重入守卫）
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                DataCache.load("local_versions", object : TypeToken<List<com.pmcl.core.version.VersionManager.LocalVersionInfo>>() {})
            }
            if (cached != null && cached.isNotEmpty() && _localVersionInfos.value.isEmpty()) {
                _localVersionInfos.value = cached
                _localVersions.value = cached.map { it.getId() }
            }
        }
        // 用 atomic compareAndSet 防重入，避免 _scanning 卡死时按钮永远失效
        if (!_scanning.compareAndSet(expect = false, update = true)) return
        scope.launch {
            _scanProgress.value = null
            _status.value = "正在扫描本地版本…"
            val startTime = System.currentTimeMillis()
            try {
                val list = withContext(Dispatchers.IO) {
                    core.versions().scanAllLocalVersions { p ->
                        _scanProgress.value = p
                        _status.value = "扫描中 ${p.getScanned()}/${p.getTotal()}  ${p.getCurrentDir()}/${p.getCurrentVersion()}"
                    }
                }
                _localVersionInfos.value = list
                _localVersions.value = list.map { it.getId() }
                DataCache.save("local_versions", list)
                val pmclDir = config.getVersionsDir()
                val mcDir = com.pmcl.core.version.VersionManager.detectDefaultMinecraftVersionsDir()
                _status.value = if (list.isEmpty()) {
                    if (mcDir != null) "扫描完成：未找到版本（已扫描 $pmclDir 与 $mcDir）"
                    else "扫描完成：未找到版本（已扫描 $pmclDir；未检测到 Minecraft 目录）"
                } else {
                    "扫描完成：共 ${list.size} 个本地版本" +
                        if (mcDir != null) "（含 $mcDir）" else ""
                }
                // 扫描后校验：恢复的 lastSelectedVersion 若已不存在则清空，
                // 避免启动按钮对失效版本可点击
                val saved = _selectedVersion.value
                if (saved != null && saved.isNotEmpty() && list.none { it.getId() == saved }) {
                    _selectedVersion.value = null
                    preferences.setLastSelectedVersion("")
                }
                // 自动清理失效的固定磁贴（版本已删除）
                val invalidPinned = _pinnedVersions.value.filter { vid ->
                    list.none { it.getId() == vid }
                }
                if (invalidPinned.isNotEmpty()) {
                    invalidPinned.forEach { preferences.unpinVersion(it) }
                    _pinnedVersions.value = preferences.getPinnedVersions()
                }
            } catch (e: Throwable) {
                _status.value = "扫描本地版本失败：${e.message}"
            } finally {
                // 最小显示 600ms，避免扫描太快导致动画一闪而过
                val elapsed = System.currentTimeMillis() - startTime
                if (elapsed < 600) {
                    kotlinx.coroutines.delay(600 - elapsed)
                }
                _scanning.value = false
                _scanProgress.value = null
            }
        }
    }

    /** 固定版本到磁贴 */
    fun pinVersion(versionId: String) {
        preferences.pinVersion(versionId)
        _pinnedVersions.value = preferences.getPinnedVersions()
        _status.value = "已固定 $versionId"
    }

    /** 取消固定（删除磁贴）— 同时清理自定义名称 */
    fun unpinVersion(versionId: String) {
        preferences.unpinVersion(versionId)
        _pinnedVersions.value = preferences.getPinnedVersions()
        _pinnedTileLabels.value = HashMap(preferences.getPinnedTileLabelsRaw())
        _status.value = "已删除磁贴 $versionId"
    }

    /** 设置磁贴自定义名称（传空串则恢复为版本 ID） */
    fun renamePinnedTile(versionId: String, label: String) {
        val trimmed = label.trim()
        preferences.setPinnedTileLabel(versionId, trimmed)
        _pinnedTileLabels.value = HashMap(preferences.getPinnedTileLabelsRaw())
        _status.value = if (trimmed.isEmpty()) "已重置 $versionId 磁贴名称"
                        else "已重命名 $versionId → $trimmed"
    }

    /**
     * 一键磁贴启动：预校验 + 选择版本 + 启动。
     * 与 [launch] 不同的是，先做账号/版本存在性校验并通过 status 给出反馈，
     * 避免磁贴点击后没有任何响应。
     */
    fun quickLaunch(versionId: String) {
        // 校验本地版本仍存在（防止版本被删除后磁贴残留）
        if (_localVersionInfos.value.none { it.getId() == versionId }) {
            _status.value = "磁贴失效：本地未找到 $versionId，已自动清理"
            // 自动清理失效磁贴
            if (_pinnedVersions.value.contains(versionId)) {
                unpinVersion(versionId)
            }
            return
        }
        if (_account.value == null) {
            _status.value = "请先在右侧登录账号后再启动"
            return
        }
        selectVersion(versionId)
        launch()
    }

    /**
     * 清除指定版本的所有相关记录（fixed/recent/lastPlayed），
     * 在版本已被删除时由 UI 触发清理。
     */
    fun purgeVersionRecords(versionId: String) {
        if (_pinnedVersions.value.contains(versionId)) {
            preferences.unpinVersion(versionId)
            _pinnedVersions.value = preferences.getPinnedVersions()
        }
        if (_recentVersions.value.contains(versionId)) {
            preferences.removeRecentVersion(versionId)
            _recentVersions.value = preferences.getRecentVersions()
        }
        if (_lastPlayedTimes.value.containsKey(versionId)) {
            preferences.removeLastPlayedTime(versionId)
            _lastPlayedTimes.value = HashMap(preferences.getLastPlayedTimesRaw())
        }
        if (preferences.getLastSelectedVersion() == versionId) {
            preferences.setLastSelectedVersion("")
        }
        if (_selectedVersion.value == versionId) {
            _selectedVersion.value = null
        }
        _status.value = "已清理 $versionId 的所有记录"
    }

    /** 从磁盘加载已保存账号 */
    private fun loadSavedAccount() {
        scope.launch {
            try {
                val acc = withContext(Dispatchers.IO) {
                    core.auth().loadAccount(accountFile)
                }
                if (acc != null) {
                    _account.value = acc
                    _status.value = "已加载账号：${acc.getUsername()}（${acc.getType()}）"
                }
            } catch (e: Throwable) {
                _status.value = "加载账号失败：${e.message}"
            }
        }
    }

    private fun saveAccount(acc: Account) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.auth().saveAccount(acc, accountFile)
                }
            } catch (e: Throwable) {
                _status.value = "保存账号失败：${e.message}"
            }
        }
    }

    fun logout() {
        _account.value = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    java.nio.file.Files.deleteIfExists(accountFile)
                }
                _status.value = "已退出登录"
            } catch (e: Throwable) {
                _status.value = "清除账号文件失败：${e.message}"
            }
        }
    }

    fun refreshVersions() {
        scope.launch {
            _loading.value = true
            _status.value = "正在拉取版本清单…"
            // 先读缓存秒开
            val cached = withContext(Dispatchers.IO) {
                DataCache.loadWithTimestamp("versions_remote", object : TypeToken<List<McVersion>>() {})
            }
            if (cached != null) {
                @Suppress("UNCHECKED_CAST")
                val data = cached[0] as? List<McVersion> ?: return@launch
                val savedAt = cached[1] as? Long ?: return@launch
                if (data.isNotEmpty()) {
                    _versions.value = data
                    if (_selectedVersion.value == null) {
                        _selectedVersion.value = data.first().getId()
                    }
                }
                // 缓存未过期：后台静默刷新（stale-while-revalidate）
                if (!DataCache.isExpired(savedAt, 6 * 60 * 60 * 1000L)) {
                    _loading.value = false
                    _status.value = "已加载 ${data.size} 个版本"
                    scope.launch {
                        try {
                            val list = withContext(Dispatchers.IO) {
                                core.versions().fetchRemoteVersions().join()
                            }
                            _versions.value = list
                            DataCache.save("versions_remote", list)
                            _status.value = "已加载 ${list.size} 个版本"
                        } catch (_: Throwable) {
                            // 静默失败，保留缓存数据
                        }
                    }
                    // 无论远程拉取成败，都要刷新本地版本扫描
                    refreshLocalVersions()
                    return@launch
                }
                // 缓存已过期：继续走正常网络请求
            }
            // 缓存不存在/已过期：正常网络请求
            try {
                val list = withContext(Dispatchers.IO) {
                    core.versions().fetchRemoteVersions().join()
                }
                _versions.value = list
                _status.value = "已加载 ${list.size} 个版本"
                if (_selectedVersion.value == null && list.isNotEmpty()) {
                    _selectedVersion.value = list.first().getId()
                }
                DataCache.save("versions_remote", list)
            } catch (e: Throwable) {
                _status.value = "拉取失败：${e.message}"
            } finally {
                _loading.value = false
                // 无论远程拉取成败，都要刷新本地版本扫描
                refreshLocalVersions()
            }
        }
    }

    fun selectVersion(id: String) {
        _selectedVersion.value = id
        // 持久化上次选中，重启时自动恢复
        preferences.setLastSelectedVersion(id)
    }

    fun loginOffline(username: String) {
        if (username.isBlank()) {
            _status.value = "请输入用户名"
            return
        }
        val acc = core.auth().offline(username)
        _account.value = acc
        saveAccount(acc)
        _status.value = "已登录（离线）：$username"
    }

    /** 为当前离线账号设置自定义皮肤 URL（如 Crafatar 头像 URL 或其他皮肤图） */
    fun setOfflineSkin(skinUrl: String, skinModel: String = "classic") {
        val current = _account.value ?: run {
            _status.value = "请先登录账号"
            return
        }
        if (current.getType() != Account.AccountType.OFFLINE) {
            _status.value = "仅离线账号支持自定义皮肤（微软账号使用 Mojang 服务器皮肤）"
            return
        }
        val updated = Account(
            current.getUsername(), current.getUuid(), current.getAccessToken(),
            current.getType(), skinUrl, skinModel
        )
        _account.value = updated
        saveAccount(updated)
        _status.value = if (skinUrl.isEmpty()) "已清除自定义皮肤" else "已设置自定义皮肤"
    }

    /**
     * 刷新壁纸取色：从桌面壁纸提取种子色，生成动态 ColorScheme。
     * @param targetThemeState 可选的 ThemeState 引用，若提供则直接更新（避免字段赋值时序问题）
     */
    fun refreshWallpaperColor(targetThemeState: com.pmcl.ui.theme.ThemeState? = null) {
        val ts = targetThemeState ?: themeState ?: return
        com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] refreshWallpaperColor called, ts=${ts != null}")
        scope.launch {
            try {
                _status.value = "正在提取壁纸主色…"
                com.pmcl.core.theme.WallpaperColorProvider.clearCache()
                val seedColor = withContext(Dispatchers.IO) {
                    com.pmcl.core.theme.WallpaperColorProvider.fetchSeedColor()
                }
                com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] seedColor=$seedColor")
                if (seedColor == -1) {
                    _status.value = "壁纸取色失败，使用默认配色"
                    return@launch
                }
                val dark = preferences.isUseDarkTheme()
                ts.applySeedColor(seedColor, dark)
                com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] applySeedColor done, primary=${ts.dynamicColorScheme?.primary}")
                _status.value = "莫奈取色已应用（种子色: #${Integer.toHexString(seedColor).padStart(6, '0')}）"
            } catch (e: Throwable) {
                com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] EXCEPTION: ${e.javaClass.name}: ${e.message}")
                _status.value = "壁纸取色失败：${e.message}"
            }
        }
    }

    /**
     * 应用自定义强调色（手动色板选择）。
     * 非莫奈模式下使用用户选择的颜色作为种子色生成完整配色。
     */
    fun applyCustomAccentColor(argb: Int, targetThemeState: com.pmcl.ui.theme.ThemeState? = null) {
        val ts = targetThemeState ?: themeState ?: return
        // 提取 RGB（去掉 alpha）
        val rgb = argb and 0x00FFFFFF
        ts.applyCustomAccentColor(rgb)
        ts.enableDynamicColor(false)
        preferences.setDynamicColor(false)
        preferences.setCustomAccentColor(rgb)
        val dark = preferences.isUseDarkTheme()
        ts.applySeedColor(rgb, dark)
        _status.value = "已应用自定义强调色 (#${Integer.toHexString(rgb).padStart(6, '0')})"
    }

    /** 清除自定义强调色，恢复默认配色 */
    fun clearCustomAccentColor(targetThemeState: com.pmcl.ui.theme.ThemeState? = null) {
        val ts = targetThemeState ?: themeState ?: return
        ts.clearCustomAccentColor()
        ts.updateDynamicColorScheme(null)
        preferences.setCustomAccentColor(-1)
        _status.value = "已恢复默认配色"
    }

    /**
     * 切换深色/浅色模式时重新生成配色（修复莫奈/自定义色与深浅模式不同步的 bug）。
     * 在 SettingsPage 深色 Switch 的 onCheckedChange 中调用。
     */
    fun onThemeModeChanged(dark: Boolean, targetThemeState: com.pmcl.ui.theme.ThemeState? = null) {
        val ts = targetThemeState ?: themeState ?: return
        ts.set(dark)
        preferences.setUseDarkTheme(dark)
        // 如果莫奈取色或自定义强调色开启，需重新生成配色以适配深浅模式
        if (ts.dynamicColor && ts.seedColor != -1) {
            ts.applySeedColor(ts.seedColor, dark)
        } else if (ts.customAccentColor != -1) {
            ts.applySeedColor(ts.customAccentColor, dark)
        }
    }

    /** 由 App.kt 注入的 ThemeState 引用 */
    var themeState: com.pmcl.ui.theme.ThemeState? = null

    fun startMicrosoftLogin() {
        scope.launch {
            _loggingIn.value = true
            _status.value = "请求设备码…"
            try {
                val dc = withContext(Dispatchers.IO) { core.auth().requestDeviceCode() }
                _deviceCode.value = dc
                _status.value = "请打开 ${dc.getVerificationUri()} 输入 ${dc.getUserCode()}"

                val account = withContext(Dispatchers.IO) {
                    core.auth().loginMicrosoftAsync(dc) { msg ->
                        _status.value = msg
                    }.join()
                }
                _account.value = account
                saveAccount(account)
                _status.value = "已登录（微软）：${account.getUsername()}"
                _deviceCode.value = null
            } catch (e: Throwable) {
                _status.value = "微软登录失败：${e.message}"
            } finally {
                _loggingIn.value = false
            }
        }
    }

    /**
     * 触发游戏安装流程：先弹窗询问是否同时安装模组加载器，用户确认后再执行实际安装。
     * 此方法不立即开始下载，仅触发 [preInstallEvent] 事件。
     */
    fun installVersion(versionId: String) {
        _preInstallEvent.value = PreInstallEvent(versionId)
    }

    /**
     * 执行实际安装：先安装游戏版本，成功后若指定了加载器则继续安装模组加载器。
     * 由安装前弹窗确认后调用。
     *
     * @param versionId      游戏 versionId
     * @param loader         可选模组加载器类型，null 表示仅安装原版
     * @param loaderVersion  加载器版本号，loader 非 null 时必须提供
     */
    fun proceedInstall(versionId: String, loader: ModLoader? = null, loaderVersion: String? = null) {
        scope.launch {
            _installing.value = true
            _status.value = "开始安装 $versionId"
            try {
                withContext(Dispatchers.IO) {
                    core.install().install(versionId) { p ->
                        _installProgress.value = p
                        _status.value = "${p.getStage()} - ${p.getMessage()}"
                    }.join()
                }
                refreshLocalVersions()
                _status.value = "安装完成：$versionId"
                // 游戏安装成功后，若用户选择了加载器则继续安装
                if (loader != null && !loaderVersion.isNullOrEmpty()) {
                    _status.value = "安装 $loader $loaderVersion"
                    withContext(Dispatchers.IO) {
                        core.modLoaders().get(loader)
                            .install(versionId, loaderVersion) { p ->
                                _installProgress.value = p
                                _status.value = "${p.getStage()} - ${p.getMessage()}"
                            }.join()
                    }
                    refreshLocalVersions()
                    _status.value = "安装完成：$loader $loaderVersion"
                }
            } catch (e: Throwable) {
                _status.value = "安装失败：${e.message}"
            } finally {
                _installing.value = false
            }
        }
    }

    fun listModLoaderVersions(loader: ModLoader, gameVersion: String) {
        val cacheKey = "modloader_${loader}_${gameVersion}"
        scope.launch {
            // 先读缓存
            val cached = withContext(Dispatchers.IO) {
                DataCache.loadWithTimestamp(cacheKey, object : TypeToken<List<ModLoaderVersion>>() {})
            }
            if (cached != null) {
                @Suppress("UNCHECKED_CAST")
                val data = cached[0] as? List<ModLoaderVersion> ?: return@launch
                val savedAt = cached[1] as? Long ?: return@launch
                // 缓存存在且未过期（24h）：直接使用，不发起网络请求
                if (!DataCache.isExpired(savedAt, 24 * 60 * 60 * 1000L)) {
                    _modLoaderVersions.value = data
                    _status.value = "已加载 ${data.size} 个 $loader 版本（缓存）"
                    return@launch
                }
            }
            // 缓存不存在/已过期：网络请求
            _status.value = "拉取 $loader 版本列表…"
            try {
                val list = withContext(Dispatchers.IO) {
                    core.modLoaders().get(loader).listVersions(gameVersion).join()
                }
                _modLoaderVersions.value = list
                _status.value = "已加载 ${list.size} 个 $loader 版本"
                DataCache.save(cacheKey, list)
            } catch (e: Throwable) {
                _status.value = "拉取失败：${e.message}"
            }
        }
    }

    fun installModLoader(loader: ModLoader, gameVersion: String, loaderVersion: String) {
        scope.launch {
            _installing.value = true
            _status.value = "安装 $loader $loaderVersion"
            try {
                withContext(Dispatchers.IO) {
                    core.modLoaders().get(loader)
                        .install(gameVersion, loaderVersion) { p ->
                            _installProgress.value = p
                            _status.value = "${p.getStage()} - ${p.getMessage()}"
                        }.join()
                }
                refreshLocalVersions()
                _status.value = "安装完成：$loader $loaderVersion"
            } catch (e: Throwable) {
                _status.value = "安装失败：${e.message}"
            } finally {
                _installing.value = false
            }
        }
    }

    // ============ 模组市场 ============

    fun searchMods(query: String, gameVersion: String? = null, loader: String? = null,
                   category: String? = null) {
        scope.launch {
            _marketLoading.value = true
            _status.value = "搜索: $query"
            try {
                val list = withContext(Dispatchers.IO) {
                    if (category != null && category.isNotEmpty()) {
                        core.modMarket().search(query, gameVersion, loader, category, 30).join()
                    } else {
                        core.modMarket().search(query, gameVersion, loader, 30).join()
                    }
                }
                _marketResults.value = list
                _status.value = "找到 ${list.size} 个模组（CurseForge ${if (core.modMarket().hasCurseForge()) "已启用" else "未启用"}）"
            } catch (e: Throwable) {
                _status.value = "搜索失败：${e.message}"
            } finally {
                _marketLoading.value = false
            }
        }
    }

    /**
     * 加载 Modrinth + CurseForge 热门 mod（按下载量排序）。
     * 进入页面时自动调用一次，作为「热门推荐」展示。
     */
    fun loadPopularMods(gameVersion: String? = null, loader: String? = null) {
        scope.launch {
            // 先读缓存秒开
            val cached = withContext(Dispatchers.IO) {
                DataCache.loadWithTimestamp("popular_mods", object : TypeToken<List<ModProject>>() {})
            }
            if (cached != null) {
                @Suppress("UNCHECKED_CAST")
                val data = cached[0] as? List<ModProject> ?: return@launch
                val savedAt = cached[1] as? Long ?: return@launch
                if (data.isNotEmpty()) {
                    _popularMods.value = data
                    _popularLoading.value = false
                }
                // 缓存未过期：后台静默刷新（stale-while-revalidate）
                if (!DataCache.isExpired(savedAt, 12 * 60 * 60 * 1000L)) {
                    scope.launch {
                        try {
                            val list = withContext(Dispatchers.IO) {
                                core.modMarket().popular(gameVersion, loader, 24).join()
                            }
                            _popularMods.value = list
                            DataCache.save("popular_mods", list)
                            _status.value = "已加载 ${list.size} 个热门模组"
                        } catch (_: Throwable) {
                            // 静默失败，保留缓存数据
                        }
                    }
                    return@launch
                }
                // 缓存已过期：继续走正常网络请求
            }
            // 缓存不存在/已过期：正常网络请求
            _popularLoading.value = true
            _status.value = "加载热门模组…"
            try {
                val list = withContext(Dispatchers.IO) {
                    core.modMarket().popular(gameVersion, loader, 24).join()
                }
                _popularMods.value = list
                _status.value = "已加载 ${list.size} 个热门模组"
                DataCache.save("popular_mods", list)
            } catch (e: Throwable) {
                _status.value = "加载热门模组失败：${e.message}"
            } finally {
                _popularLoading.value = false
            }
        }
    }

    /**
     * 按分类加载推荐模组（用户点击分类标签后调用）。
     * 使用 Modrinth + CurseForge 聚合，按下载量排序。
     * category 为空字符串时等同于 loadPopularMods。
     */
    fun loadCategoryMods(category: String, gameVersion: String? = null, loader: String? = null) {
        _selectedCategory.value = category
        if (category.isEmpty()) {
            // 取消分类选择：清空分类结果，回到热门推荐
            _categoryResults.value = emptyList()
            return
        }
        // 切换到分类浏览模式：清除关键字搜索结果，使分类网格立即可见
        _marketResults.value = emptyList()
        scope.launch {
            _categoryLoading.value = true
            _status.value = "加载分类：$category"
            try {
                val list = withContext(Dispatchers.IO) {
                    core.modMarket().searchByCategory(category, gameVersion, loader, 24).join()
                }
                _categoryResults.value = list
                _status.value = "已加载 ${list.size} 个分类模组"
            } catch (e: Throwable) {
                _status.value = "加载分类失败：${e.message}"
            } finally {
                _categoryLoading.value = false
            }
        }
    }

    /** 清除分类选择，回到热门推荐视图 */
    fun clearCategory() {
        _selectedCategory.value = ""
        _categoryResults.value = emptyList()
    }

    /**
     * 点击热门卡片进入该 mod 的详情界面（展开版本文件列表）。
     * 在 UI 层会把 _detailProject 设置为该 project，并触发 listProjectFiles。
     */
    fun openModDetail(project: ModProject) {
        _detailProject.value = project
        listProjectFiles(project)
    }

    /** 返回热门推荐网格（关闭详情） */
    fun closeModDetail() {
        _detailProject.value = null
        _currentModFiles.value = emptyList()
    }

    fun listProjectFiles(project: ModProject) {
        scope.launch {
            // 立即清空旧的文件列表，避免切换 project 时残留
            _currentModFiles.value = emptyList()
            _status.value = "拉取 ${project.getName()} 文件…"
            try {
                val files = withContext(Dispatchers.IO) {
                    core.modMarket().listFiles(project).join()
                }
                _currentModFiles.value = files
                _status.value = "${project.getName()} 共 ${files.size} 个文件"
            } catch (e: Throwable) {
                _status.value = "拉取失败：${e.message}"
            }
        }
    }

    fun installMod(file: ModFile, gameVersion: String) {
        scope.launch {
            _status.value = "下载模组 ${file.getFileName()}"
            try {
                withContext(Dispatchers.IO) {
                    core.modMarket().installMod(file, gameVersion,
                        _selectedVersion.value, preferences) { msg ->
                        _status.value = msg
                    }.join()
                }
                _status.value = "模组已安装：${file.getFileName()}"
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = "模组安装失败：${e.message}"
            }
        }
    }

    /**
     * 安装模组并自动解析安装其依赖。
     * 下载主模组后解析 jar 内 depends 列表，自动搜索并安装未安装的依赖。
     */
    fun installModWithDeps(file: ModFile, gameVersion: String) {
        if (_installingDeps.value) return
        _installingDeps.value = true
        _depInstallResult.value = null
        scope.launch {
            _status.value = "安装模组（含依赖）：${file.getFileName()}"
            try {
                val result = core.modDependencyResolver().installWithDependencies(
                    file, gameVersion, _selectedVersion.value
                ) { msg -> _status.value = msg }.join()
                _depInstallResult.value = result
                _status.value = if (result.hasInstalled()) {
                    "安装完成：${file.getFileName()}（${result.summary()}）"
                } else {
                    "安装完成：${file.getFileName()}（无额外依赖）"
                }
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = "安装失败：${e.message}"
            } finally {
                _installingDeps.value = false
            }
        }
    }

    /** 清除依赖安装结果 */
    fun clearDepInstallResult() {
        _depInstallResult.value = null
    }

    // ============ 游戏时长统计 ============

    /** 刷新统计数据（进入统计页时调用） */
    fun refreshPlayTimeStats() {
        val days = _statsDays.value
        _playTimeStats.value = core.playTimeTracker().getOverallStats(days)
        _dailyStats.value = core.playTimeTracker().getDailyStatsWithZeros(days)
    }

    /** 设置统计展示天数（7/14/30）并刷新 */
    fun setStatsDays(days: Int) {
        _statsDays.value = days
        refreshPlayTimeStats()
    }

    // ============ 已安装 Mod 扫描 ============

    fun refreshInstalledMods() {
        // 先读缓存秒开
        scope.launch {
            val cached = withContext(Dispatchers.IO) {
                DataCache.load("installed_mods", object : TypeToken<List<ModMeta>>() {})
            }
            if (cached != null && cached.isNotEmpty() && _installedMods.value.isEmpty()) {
                _installedMods.value = cached
            }
        }
        scope.launch {
            try {
                val mods = withContext(Dispatchers.IO) {
                    val allMods = mutableListOf<ModMeta>()
                    val seenFiles = mutableSetOf<String>()
                    // 按目录分组的 mod 列表（用于冲突检查时按目录隔离）
                    val modsByDir = mutableMapOf<Path, MutableList<ModMeta>>()
                    val modsDirs = mutableListOf<Path>()
                    // 1. PMCL 工作目录的 mods
                    modsDirs.add(config.getWorkDir().resolve("mods"))
                    // 2. 系统所有 Minecraft 根目录的 mods
                    for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                        val mcRoot = mcDir.parent
                        if (mcRoot != null) modsDirs.add(mcRoot.resolve("mods"))
                    }
                    // 3. 每个版本目录下的 mods（整合包结构：versions/<id>/mods/）
                    val allVersionsDirs = mutableListOf<Path>()
                    allVersionsDirs.add(config.getVersionsDir())
                    allVersionsDirs.addAll(com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs())
                    for (versionsDir in allVersionsDirs) {
                        val versionsFile = versionsDir.toFile()
                        if (!versionsFile.isDirectory) continue
                        val subDirs = versionsFile.listFiles { f -> f.isDirectory } ?: continue
                        for (subDir in subDirs) {
                            val versionModsDir = subDir.toPath().resolve("mods")
                            if (versionModsDir !in modsDirs) modsDirs.add(versionModsDir)
                        }
                    }
                    // 4. 版本隔离目录下的 mods（instances/<id>/mods/）
                    val instancesDir = config.getWorkDir().resolve("instances")
                    val instancesFile = instancesDir.toFile()
                    if (instancesFile.isDirectory) {
                        val instDirs = instancesFile.listFiles { f -> f.isDirectory } ?: emptyArray()
                        for (instDir in instDirs) {
                            val instModsDir = instDir.toPath().resolve("mods")
                            if (instModsDir !in modsDirs) modsDirs.add(instModsDir)
                        }
                    }
                    // 扫描所有 mods 目录，按目录分组
                    for (modsDir in modsDirs) {
                        try {
                            val part = ModScanner.scanDirectory(modsDir)
                            // 为每个 mod 设置来源标签
                            val sourceLabel = sourceLabelFor(modsDir)
                            for (m in part) {
                                // 用「目录路径 + 文件名」去重，避免不同目录的同名文件误去重
                                val dedupKey = "$modsDir/${m.getJarFile()}"
                                if (seenFiles.add(dedupKey)) {
                                    m.setSource(sourceLabel)
                                    allMods.add(m)
                                    modsByDir.getOrPut(modsDir) { mutableListOf() }.add(m)
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                    // 按目录分组检查冲突，避免跨版本目录误报依赖缺失
                    val allErrors = mutableListOf<String>()
                    val allWarnings = mutableListOf<String>()
                    for ((_, dirMods) in modsByDir) {
                        if (dirMods.isEmpty()) continue
                        val r = ModConflictChecker.check(dirMods)
                        allErrors.addAll(r.getErrors())
                        allWarnings.addAll(r.getWarnings())
                    }
                    _modConflicts.value = ModConflictChecker.Result(allErrors, allWarnings)
                    allMods
                }
                _installedMods.value = mods
                DataCache.save("installed_mods", mods)
                _status.value = "已扫描 ${mods.size} 个 mod（${modsDirsCount(mods)}）[build 20260708.4]"
            } catch (e: Throwable) {
                _status.value = "扫描 mods 失败：${e.message} [build 20260708.4]"
                System.err.println("[refreshInstalledMods] 顶层异常: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun modsDirsCount(mods: List<ModMeta>): String {
        return "${mods.size} mods"
    }

    /**
     * 根据 mods 目录路径推断来源标签：
     * - PMCL 全局 mods → "全局"
     * - versions/<id>/mods → <id>（版本/整合包名）
     * - 系统 .minecraft/mods → "系统"
     */
    private fun sourceLabelFor(modsDir: java.nio.file.Path): String {
        // PMCL 全局 mods 目录
        if (modsDir == config.getWorkDir().resolve("mods")) return "全局"
        // 整合包结构：parent 是 versions/<id> 下的版本目录
        val parent = modsDir.parent
        if (parent != null) {
            val grandParentName = parent.parent?.fileName?.toString()?.lowercase()
            if (grandParentName == "versions") {
                return parent.fileName?.toString() ?: "版本"
            }
        }
        // 系统目录
        return "系统"
    }

    /** 删除指定 mod（按 jar 文件名） */
    fun deleteMod(jarFile: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.modManager().deleteMod(jarFile)
                }
                _status.value = "已删除 $jarFile"
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = "删除失败：${e.message}"
            }
        }
    }

    /** 禁用 mod（重命名为 .jar.disabled） */
    fun disableMod(jarFile: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.modManager().disableMod(jarFile)
                }
                _status.value = "已禁用 $jarFile"
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = "禁用失败：${e.message}"
            }
        }
    }

    /** 启用 mod（去掉 .disabled 后缀） */
    fun enableMod(jarFile: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.modManager().enableMod(jarFile)
                }
                _status.value = "已启用 $jarFile"
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = "启用失败：${e.message}"
            }
        }
    }

    /** 在系统文件管理中打开 mods 目录（优先打开第一个存在且有文件的目录） */
    fun openModsDir() {
        try {
            // 候选 mods 目录：PMCL 工作目录 + 系统所有 Minecraft 根目录
            val candidates = mutableListOf<java.io.File>()
            candidates.add(config.getWorkDir().resolve("mods").toFile())
            for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                val mcRoot = mcDir.parent
                if (mcRoot != null) candidates.add(mcRoot.resolve("mods").toFile())
            }
            // 优先选第一个存在且非空的目录，否则用 PMCL 默认目录
            val modsDir = candidates.firstOrNull { it.isDirectory && (it.list()?.isNotEmpty() == true) }
                ?: candidates.firstOrNull { it.isDirectory }
                ?: config.getWorkDir().resolve("mods").toFile().also { it.mkdirs() }
            openDir(modsDir)
        } catch (e: Throwable) {
            _status.value = "打开目录失败：${e.message}"
        }
    }

    /** 在系统文件管理中打开指定目录 */
    private fun openDir(dir: java.io.File) {
        try {
            if (!dir.isDirectory) dir.mkdirs()
            val os = System.getProperty("os.name").lowercase()
            val cmd = when {
                os.contains("mac") -> listOf("open", dir.absolutePath)
                os.contains("win") -> listOf("explorer", dir.absolutePath)
                else -> listOf("xdg-open", dir.absolutePath)
            }
            ProcessBuilder(cmd).start()
        } catch (e: Throwable) {
            _status.value = "打开目录失败：${e.message}"
        }
    }

    /**
     * 检查市场项目是否已安装（按 modId 匹配）。
     * 用于在市场列表中显示"已安装"标记。
     */
    fun isModInstalled(modId: String): Boolean {
        return _installedMods.value.any { it.getModId() == modId && !it.isDisabled() }
    }

    // ============ 整合包管理 ============

    /** 刷新已安装整合包列表 */
    fun refreshModpacks() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    core.modpacks().listInstalledModpacks()
                }
                _modpacks.value = list
            } catch (e: Throwable) {
                _status.value = "刷新整合包列表失败：${e.message}"
            }
        }
    }

    /** 导入整合包文件（.mrpack 或 .zip） */
    fun importModpack(filePath: String) {
        if (_modpackBusy.value) {
            _status.value = "整合包操作进行中，请等待"
            return
        }
        scope.launch {
            _modpackBusy.value = true
            _modpackProgress.value = InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 0, "开始导入整合包...")
            try {
                withContext(Dispatchers.IO) {
                    val path = java.nio.file.Paths.get(filePath)
                    core.modpacks().importModpack(path) { p ->
                        _modpackProgress.value = p
                    }.join()
                }
                _status.value = "整合包导入完成"
                refreshModpacks()
            } catch (e: Throwable) {
                _status.value = "整合包导入失败：${e.message}"
            } finally {
                _modpackBusy.value = false
                _modpackProgress.value = null
            }
        }
    }

    /** 导出当前选中版本为 Modrinth .mrpack 整合包 */
    fun exportModpack(targetPath: String) {
        val versionId = _selectedVersion.value ?: run {
            _status.value = "请先选择版本"
            return
        }
        if (_modpackBusy.value) {
            _status.value = "整合包操作进行中，请等待"
            return
        }
        scope.launch {
            _modpackBusy.value = true
            _modpackProgress.value = InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 0, "开始导出整合包...")
            try {
                withContext(Dispatchers.IO) {
                    val path = java.nio.file.Paths.get(targetPath)
                    core.modpacks().exportModpack(versionId, path) { p ->
                        _modpackProgress.value = p
                    }.join()
                }
                _status.value = "整合包已导出：$targetPath"
            } catch (e: Throwable) {
                _status.value = "整合包导出失败：${e.message}"
            } finally {
                _modpackBusy.value = false
                _modpackProgress.value = null
            }
        }
    }

    /** 删除整合包实例 */
    fun deleteModpack(name: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.modpacks().deleteModpack(name)
                }
                _status.value = "已删除整合包：$name"
                refreshModpacks()
            } catch (e: Throwable) {
                _status.value = "删除整合包失败：${e.message}"
            }
        }
    }

    // ============ 下载队列管理 ============

    /** 注册队列监听器并刷新任务列表（页面首次进入时调用） */
    fun initDownloadQueue() {
        if (queueListenerRegistered) {
            refreshQueue()
            return
        }
        queueListenerRegistered = true
        core.downloadQueue().addListener { tasks ->
            // 在 IO 线程回调，直接更新 StateFlow（Compose 快照系统线程安全）
            _queueTasks.value = tasks
            _queueSummary.value = core.downloadQueue().summary
        }
        refreshQueue()
    }

    /** 刷新队列状态 */
    fun refreshQueue() {
        _queueTasks.value = core.downloadQueue().tasks
        _queueSummary.value = core.downloadQueue().summary
    }

    /** 提交版本安装到队列 */
    fun enqueueVersionInstall(versionId: String) {
        core.downloadQueue().submitVersionInstall(versionId)
        _status.value = "已加入队列：Minecraft $versionId"
        refreshQueue()
    }

    /** 提交模组加载器安装到队列 */
    fun enqueueModLoaderInstall(loaderName: String, gameVersion: String, loaderVersion: String) {
        core.downloadQueue().submitModLoaderInstall(loaderName, gameVersion, loaderVersion)
        _status.value = "已加入队列：$loaderName $loaderVersion"
        refreshQueue()
    }

    /** 提交模组下载到队列 */
    fun enqueueModDownload(modFile: ModFile, gameVersion: String, versionId: String? = null) {
        val vid = versionId ?: _selectedVersion.value
        core.downloadQueue().submitModDownload(modFile, gameVersion, vid)
        _status.value = "已加入队列：${modFile.fileName}"
        refreshQueue()
    }

    /** 暂停任务 */
    fun pauseQueueTask(taskId: String) {
        core.downloadQueue().pause(taskId)
        refreshQueue()
    }

    /** 继续任务 */
    fun resumeQueueTask(taskId: String) {
        core.downloadQueue().resume(taskId)
        refreshQueue()
    }

    /** 取消任务 */
    fun cancelQueueTask(taskId: String) {
        core.downloadQueue().cancel(taskId)
        refreshQueue()
    }

    /** 暂停所有 */
    fun pauseAllQueue() {
        core.downloadQueue().pauseAll()
        refreshQueue()
    }

    /** 继续所有 */
    fun resumeAllQueue() {
        core.downloadQueue().resumeAll()
        refreshQueue()
    }

    /** 取消所有 */
    fun cancelAllQueue() {
        core.downloadQueue().cancelAll()
        refreshQueue()
    }

    /** 清除已完成/已取消/已失败的任务记录 */
    fun clearFinishedQueue() {
        core.downloadQueue().clearFinished()
        refreshQueue()
    }

    /** 移除任务记录 */
    fun removeQueueTask(taskId: String) {
        core.downloadQueue().remove(taskId)
        refreshQueue()
    }

    // ============ 配置文件编辑器 ============

    /** 获取当前选中版本的 config 目录 */
    fun getConfigDir(): java.nio.file.Path {
        val versionId = _selectedVersion.value
        val pref = preferences
        if (pref.isVersionIsolation() && versionId != null) {
            return config.getWorkDir().resolve("instances").resolve(versionId).resolve("config")
        }
        return config.getWorkDir().resolve("config")
    }

    /** 创建 ConfigFileManager 实例（基于当前选中版本的 config 目录） */
    fun createConfigFileManager(): ConfigFileManager {
        return ConfigFileManager(getConfigDir())
    }

    /** 刷新配置文件列表 */
    fun refreshConfigFiles(subDir: String = "") {
        scope.launch {
            try {
                val manager = createConfigFileManager()
                val files = withContext(Dispatchers.IO) {
                    manager.listFiles(subDir)
                }
                _configFiles.value = files
                _configCurrentDir.value = subDir
            } catch (e: Throwable) {
                _status.value = "读取配置文件失败：${e.message}"
            }
        }
    }

    /** 读取配置文件内容 */
    fun readConfigFile(relativePath: String) {
        scope.launch {
            try {
                val manager = createConfigFileManager()
                val content = withContext(Dispatchers.IO) {
                    manager.readFile(relativePath)
                }
                _configFileContent.value = content
                _currentConfigPath.value = relativePath
                _configFileDirty.value = false
            } catch (e: Throwable) {
                _status.value = "读取文件失败：${e.message}"
                _configFileContent.value = null
                _currentConfigPath.value = null
            }
        }
    }

    /** 保存配置文件内容 */
    fun saveConfigFile(content: String) {
        val path = _currentConfigPath.value ?: return
        scope.launch {
            try {
                val manager = createConfigFileManager()
                withContext(Dispatchers.IO) {
                    manager.writeFile(path, content)
                }
                _configFileDirty.value = false
                _status.value = "已保存：$path"
            } catch (e: Throwable) {
                _status.value = "保存失败：${e.message}"
            }
        }
    }

    /** 删除配置文件 */
    fun deleteConfigFile(relativePath: String) {
        scope.launch {
            try {
                val manager = createConfigFileManager()
                withContext(Dispatchers.IO) {
                    manager.deleteFile(relativePath)
                }
                if (_currentConfigPath.value == relativePath) {
                    _configFileContent.value = null
                    _currentConfigPath.value = null
                    _configFileDirty.value = false
                }
                _status.value = "已删除：$relativePath"
                refreshConfigFiles(_configCurrentDir.value)
            } catch (e: Throwable) {
                _status.value = "删除失败：${e.message}"
            }
        }
    }

    /** 创建新配置文件 */
    fun createConfigFile(fileName: String) {
        scope.launch {
            try {
                val manager = createConfigFileManager()
                val dir = _configCurrentDir.value
                val relativePath = if (dir.isEmpty()) fileName else "$dir/$fileName"
                withContext(Dispatchers.IO) {
                    manager.createFile(relativePath)
                }
                _status.value = "已创建：$fileName"
                refreshConfigFiles(dir)
            } catch (e: Throwable) {
                _status.value = "创建失败：${e.message}"
            }
        }
    }

    /** 标记当前文件已修改（未保存） */
    fun markConfigDirty() {
        _configFileDirty.value = true
    }

    /** 关闭当前编辑的文件 */
    fun closeConfigFile() {
        _configFileContent.value = null
        _currentConfigPath.value = null
        _configFileDirty.value = false
    }

    /** 进入子目录 */
    fun enterConfigDir(subDir: String) {
        val newDir = if (_configCurrentDir.value.isEmpty()) subDir
                     else "${_configCurrentDir.value}/$subDir"
        refreshConfigFiles(newDir)
    }

    /** 返回上级目录 */
    fun navigateConfigUp() {
        val current = _configCurrentDir.value
        if (current.isEmpty()) return
        val idx = current.lastIndexOf('/')
        val parent = if (idx < 0) "" else current.substring(0, idx)
        refreshConfigFiles(parent)
    }

    /** 在系统文件管理中打开 config 目录 */
    fun openConfigDir() {
        try {
            val dir = getConfigDir().toFile()
            if (!dir.isDirectory) dir.mkdirs()
            openDir(dir)
        } catch (e: Throwable) {
            _status.value = "打开目录失败：${e.message}"
        }
    }

    // ============ 模组更新检测 ============

    /**
     * 检测已安装模组的更新。
     * 自动从当前选中版本推断 gameVersion。
     */
    fun checkModUpdates() {
        val mods = _installedMods.value
        if (mods.isEmpty()) {
            _status.value = "没有已安装的模组"
            return
        }
        // 从选中版本推断 gameVersion
        val versionId = _selectedVersion.value
        val gameVersion = inferGameVersion(versionId)
        _updateGameVersion.value = gameVersion

        if (_checkingUpdates.value) return // 防止重复检测
        _checkingUpdates.value = true
        _updateCheckProgress.value = 0 to mods.size
        _status.value = "正在检测模组更新..."

        scope.launch {
            try {
                val results = core.modUpdateChecker().checkUpdates(
                    mods, gameVersion
                ) { progress ->
                    _updateCheckProgress.value = progress[0] to progress[1]
                }.join()
                _modUpdates.value = results
                val updateCount = results.count { it.hasUpdate() }
                _status.value = if (updateCount > 0) {
                    "检测完成：$updateCount 个模组有更新"
                } else {
                    "检测完成：所有模组均为最新"
                }
            } catch (e: Throwable) {
                _status.value = "检测更新失败：${e.message}"
            } finally {
                _checkingUpdates.value = false
            }
        }
    }

    /**
     * 从版本 ID 推断 gameVersion（如 "1.20.4-OptiFine_HD_U_I7" → "1.20.4"）。
     */
    private fun inferGameVersion(versionId: String?): String {
        if (versionId == null || versionId.isEmpty()) return ""
        // 取第一个分隔符前的部分（Forge/Fabric/OptiFine 版本通常用 - 分隔）
        val idx = versionId.indexOfAny(charArrayOf('-', '+'))
        return if (idx > 0) versionId.substring(0, idx) else versionId
    }

    /**
     * 更新单个模组。
     */
    fun updateMod(info: ModUpdateChecker.UpdateInfo) {
        if (_updatingMod.value) return
        _updatingMod.value = true
        val versionId = _selectedVersion.value
        val gameVersion = _updateGameVersion.value

        scope.launch {
            try {
                core.modUpdateChecker().updateMod(info, gameVersion, versionId) { status ->
                    _status.value = status
                }.join()
                _status.value = "更新完成：${info.displayName()}"
                // 刷新已安装模组列表
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = "更新失败：${e.message}"
            } finally {
                _updatingMod.value = false
            }
        }
    }

    /**
     * 一键更新所有有更新的模组。
     */
    fun updateAllMods() {
        val updates = _modUpdates.value.filter { it.hasUpdate() }
        if (updates.isEmpty()) {
            _status.value = "没有需要更新的模组"
            return
        }
        if (_updatingMod.value) return
        _updatingMod.value = true
        val versionId = _selectedVersion.value
        val gameVersion = _updateGameVersion.value
        _status.value = "正在批量更新 ${updates.size} 个模组..."

        scope.launch {
            try {
                core.modUpdateChecker().updateAll(updates, gameVersion, versionId) { progress ->
                    _updateCheckProgress.value = progress[0] to progress[1]
                    _status.value = "批量更新中：${progress[0]}/${progress[1]}"
                }.join()
                _status.value = "批量更新完成"
                refreshInstalledMods()
                // 重新检测一次
                checkModUpdates()
            } catch (e: Throwable) {
                _status.value = "批量更新失败：${e.message}"
            } finally {
                _updatingMod.value = false
            }
        }
    }

    /** 清空更新检测结果 */
    fun clearModUpdates() {
        _modUpdates.value = emptyList()
    }

    // ============ 启动游戏 ============

    fun launch() {
        val versionId = _selectedVersion.value ?: run {
            _status.value = "请先选择版本"
            return
        }
        val account = _account.value ?: run {
            _status.value = "请先登录账号"
            return
        }
        // mod 冲突检测：仅警告，不阻断启动
        // （NeoForge 支持 jar-in-jar 内嵌依赖，Sinytra Connector 提供 fabric 兼容层，
        //   静态扫描无法检测这些，误报率高；真正的冲突游戏自己会崩并生成崩溃报告）
        val conflicts = _modConflicts.value
        if (conflicts != null && conflicts.hasIssues()) {
            _gameLogs.update { old -> (old + "[警告] mod 冲突检测（仅供参考，不阻断启动）：").takeLast(2000) }
            conflicts.getErrors().take(5).forEach {
                _gameLogs.update { old -> (old + "  - $it").takeLast(2000) }
            }
            if (conflicts.getErrors().size > 5) {
                _gameLogs.update { old -> (old + "  …还有 ${conflicts.getErrors().size - 5} 条，见模组页").takeLast(2000) }
            }
        }

        scope.launch {
            _status.value = "正在构建启动配置…"
            var instanceId: String? = null
            try {
                // 先读取版本要求的 Java 版本，用于选择合适的 Java 运行时
                // alpha/beta/1.7- 无 javaVersion 字段返回 0，按旧版本处理（需 Java 8）
                val requiredJavaVer = withContext(Dispatchers.IO) {
                    try { core.profileBuilder().getRequiredJavaVersion(versionId) } catch (e: Throwable) { 0 }
                }
                val javaExe = withContext(Dispatchers.IO) {
                    // 优先级：版本独立 Java > 全局 Java > 自动检测
                    val versionPath = preferences.getVersionJavaPath(versionId)
                    if (versionPath.isNotEmpty()) versionPath
                    else {
                        val customPath = preferences.getJavaPath()
                        if (customPath.isNotEmpty()) customPath
                        else JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir(), requiredJavaVer)
                            ?: ""
                    }
                }
                if (javaExe.isEmpty()) {
                    _status.value = "启动失败：未找到 Java 运行时"
                    _gameLogs.value = listOf(
                        "启动失败：未找到任何 Java 运行时",
                        "请安装 Java（推荐 Java 8 用于旧版本，Java 21 用于新版本）",
                        "下载地址：https://adoptium.net/temurin/releases/"
                    )
                    return@launch
                }
                // 获取实际 Java 主版本号，用于条件注入 Java 16+ 专属参数（避免 Java 8 报错）
                val javaMajorVer = withContext(Dispatchers.IO) {
                    JavaRuntimeFinder.getMajorVersion(javaExe) ?: 0
                }
                // 旧版本用 Java 9+ 启动时，PMCL 兼容层会自动处理 LaunchWrapper 的 URLClassLoader 问题
                // 不再硬性拦截，而是显示警告并继续启动（兼容层通过 -Djava.system.class.loader 解决）
                val usingCompatLayer = requiredJavaVer in 1..10 && javaMajorVer > 0 && javaMajorVer >= 9
                // 检测游戏 Java 的架构，用于让 native 库选择匹配架构的版本
                // 在 ARM64 系统上用 x86_64 Java 启动老版本时，此参数确保选择 x86_64 natives
                val javaArch = withContext(Dispatchers.IO) {
                    JavaRuntimeFinder.getArchitecture(javaExe)
                }
                // Apple Silicon Mac 上旧版本 + arm64 Java 检测：native 库只有 x86_64，会加载失败
                val isArchMismatch = requiredJavaVer in 1..10
                        && (javaArch.contains("aarch64") || javaArch.contains("arm64"))
                        && System.getProperty("os.name", "").lowercase().contains("mac")
                        && (System.getProperty("os.arch", "").lowercase().contains("aarch64")
                            || System.getProperty("os.arch", "").lowercase().contains("arm64"))
                if (isArchMismatch) {
                    _status.value = "兼容性问题：架构不匹配"
                    // 检测外部启动器和它们管理的 Java 运行时
                    val externalLaunchers = withContext(Dispatchers.IO) {
                        ExternalLauncherDetector.detectLaunchers()
                    }
                    val externalJavas = withContext(Dispatchers.IO) {
                        ExternalLauncherDetector.detectExternalJavaRuntimes(true)
                    }
                    val x86Javas = externalJavas.filter {
                        it.majorVersion == 8 && (it.arch.contains("x86_64") || it.arch.contains("amd64"))
                    }

                    val options = mutableListOf<CompatOption>()

                    // 选项1：用外部启动器管理的 x86_64 Java 8 启动
                    if (x86Javas.isNotEmpty()) {
                        val java = x86Javas.first()
                        options.add(CompatOption(
                            title = "使用 ${java.source} 的 x86_64 Java 8 启动",
                            description = "路径: ${java.javaPath}\n通过 Rosetta 2 运行 x86_64 Java 8，PMCL 用自己的启动逻辑",
                            action = { launchWithSpecificJava(versionId, java.javaPath, javaMajorVer, "x86_64") }
                        ))
                    }

                    // 选项2：用外部启动器直接打开
                    for (launcher in externalLaunchers) {
                        options.add(CompatOption(
                            title = "用 ${launcher.name} 启动",
                            description = "路径: ${launcher.executablePath}\n打开 ${launcher.name} 启动器，从中启动此版本",
                            action = { launchWithExternalLauncher(launcher, versionId) }
                        ))
                    }

                    // 选项3：安装 x86_64 Java 8
                    options.add(CompatOption(
                        title = "安装 x86_64 Java 8",
                        description = "打开浏览器下载 x86_64 版本的 Java 8\n安装后 PMCL 会自动检测并使用",
                        action = {
                            try {
                                val url = "https://adoptium.net/temurin/releases/?version=8&arch=x64"
                                if (System.getProperty("os.name", "").lowercase().contains("mac")) {
                                    Runtime.getRuntime().exec(arrayOf("open", url))
                                } else {
                                    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                                }
                            } catch (e: Throwable) {
                                _status.value = "无法打开浏览器: ${e.message}"
                            }
                        }
                    ))

                    _compatTitle.value = "兼容性问题：旧版本需要 x86_64 Java"
                    _compatOptions.value = options
                    return@launch
                }
                val profile = withContext(Dispatchers.IO) {
                    core.profileBuilder().build(versionId, account, javaMajorVer, javaArch)
                }

                // 创建/复用 GameLogger 持久化日志（多实例：每个实例独立日志文件）
                instanceId = "${versionId}_${System.currentTimeMillis()}"
                val logFile = config.getWorkDir().resolve("logs").resolve("$instanceId.log")
                val instLogger = withContext(Dispatchers.IO) {
                    try { GameLogger(logFile) } catch (e: Throwable) { null }
                }
                instanceLoggers[instanceId] = instLogger
                gameLogger = instLogger

                // 初始化实例日志列表
                val initLogs = if (usingCompatLayer) {
                    mutableListOf(
                        "[PMCL 兼容层] 检测到旧版本使用 Java $javaMajorVer 启动（推荐 Java ${requiredJavaVer}）",
                        "[PMCL 兼容层] 已通过 PmclBootstrap 入口类注入 URLClassLoader，解决 LaunchWrapper 兼容问题",
                        "[PMCL 兼容层] 已注入 --add-opens 参数，允许旧版本反射访问 Java 内部 API",
                        "[PMCL 兼容层] 如遇问题，请安装 Java 8 以获得最佳兼容性",
                        ""
                    )
                } else mutableListOf()
                instanceLogs[instanceId] = initLogs
                _gameLogs.value = initLogs.toList()

                // 添加到运行中实例列表，设为活跃
                _runningInstances.update { list ->
                    list.map { it.copy(active = false) } + RunningInstance(
                        id = instanceId,
                        versionId = versionId,
                        accountName = account.username,
                        startTime = System.currentTimeMillis(),
                        active = true
                    )
                }
                _gameRunning.value = true
                _status.value = "启动中… java=$javaExe (Java $javaMajorVer $javaArch) version=$versionId" +
                        if (usingCompatLayer) " [兼容层]" else ""

                // 记录启动前的崩溃报告快照（用于退出后对比新增）
                val crashDirBefore = withContext(Dispatchers.IO) {
                    try { core.crashAnalyzer().scanReports(config.getWorkDir()).map { it.getFile().toString() }.toSet() }
                    catch (t: Throwable) { emptySet<String>() }
                }

                // 记录启动：最近使用列表 + 最后游玩时间戳 + 时长追踪
                val launchTime = System.currentTimeMillis()
                preferences.recordRecentVersion(versionId)
                preferences.setLastPlayedTime(versionId, launchTime)
                _recentVersions.value = preferences.getRecentVersions()
                _lastPlayedTimes.value = HashMap(preferences.getLastPlayedTimesRaw())
                core.playTimeTracker().recordStart(versionId)

                // launchAsync 返回 CompletableFuture，需等待进程退出，否则 gameRunning 会立即被 finally 重置
                val future = core.launch().launchAsync(
                    profile, javaExe,
                    { line ->
                        // 同时写入实例日志和全局日志（如果该实例是活跃的）
                        instanceLogs[instanceId]?.let { logs ->
                            synchronized(logs) {
                                logs.add(line)
                                if (logs.size > 2000) logs.subList(0, logs.size - 2000).clear()
                            }
                        }
                        // 仅当此实例为活跃时更新 UI
                        if (_runningInstances.value.any { it.id == instanceId && it.active }) {
                            _gameLogs.value = instanceLogs[instanceId]?.toList() ?: emptyList()
                        }
                    },
                    instLogger
                )
                val exitCode = withContext(Dispatchers.IO) { future.join() }
                _status.value = "游戏已退出（code=$exitCode） $versionId"

                // 记录游玩时长
                core.playTimeTracker().recordEnd(versionId)

                // 异常退出检测：非 0 退出码视为崩溃
                if (exitCode != 0) {
                    val recentLogs = _gameLogs.value.takeLast(80)
                    val report = withContext(Dispatchers.IO) {
                        try {
                            val after = core.crashAnalyzer().scanReports(config.getWorkDir())
                            // 找出本次启动后新增的崩溃报告（按文件路径对比）
                            val newReport = after.firstOrNull { it.getFile().toString() !in crashDirBefore }
                            // 若无新增 crash-reports 文件，对 latest.log 末尾做分析
                            if (newReport != null) {
                                newReport
                            } else {
                                val logText = recentLogs.joinToString("\n")
                                if (logText.isNotBlank()) core.crashAnalyzer().analyze(logText, null)
                                else null
                            }
                        } catch (t: Throwable) { null }
                    }
                    _crashEvent.value = CrashEvent(exitCode, report, recentLogs, versionId)
                    _crashReports.value = withContext(Dispatchers.IO) {
                        try { core.crashAnalyzer().scanReports(config.getWorkDir()) } catch (t: Throwable) { emptyList() }
                    }
                }
            } catch (e: Throwable) {
                _status.value = "启动失败：${e.message}"
                _gameLogs.update { old -> (old + "[错误] ${e.message}").takeLast(2000) }
                instanceId?.let { id ->
                    instanceLogs[id]?.let { logs ->
                        synchronized(logs) { logs.add("[错误] ${e.message}") }
                    }
                }
            } finally {
                instanceId?.let { id ->
                    // 从运行列表中移除此实例
                    _runningInstances.update { list ->
                        val remaining = list.filter { it.id != id }
                        // 如果活跃实例退出了，将最后一个实例设为活跃
                        if (remaining.isNotEmpty() && !remaining.any { it.active }) {
                            remaining.mapIndexed { idx, inst ->
                                if (idx == remaining.lastIndex) inst.copy(active = true)
                                else inst
                            }
                        } else remaining
                    }
                    // 更新 UI 日志为新的活跃实例
                    val activeInst = _runningInstances.value.firstOrNull { it.active }
                    if (activeInst != null) {
                        _gameLogs.value = instanceLogs[activeInst.id]?.toList() ?: emptyList()
                    }
                    _gameRunning.value = _runningInstances.value.isNotEmpty()
                    // 清理此实例的日志资源
                    instanceLoggers.remove(id)?.close()
                    instanceLogs.remove(id)
                    gameLogger = instanceLoggers.values.lastOrNull()
                }
            }
        }
    }

    /**
     * 切换活跃实例（UI 日志面板显示该实例的日志）。
     */
    fun selectInstance(instanceId: String) {
        _runningInstances.update { list ->
            list.map { it.copy(active = it.id == instanceId) }
        }
        _gameLogs.value = instanceLogs[instanceId]?.toList() ?: emptyList()
    }

    // ============ Java 运行时管理 ============

    /**
     * 检测当前可用于启动 MC 的 Java 路径。
     * 优先返回 preferences.javaPath，其次扫描 runtimes 目录与系统路径。
     */
    fun detectJavaPath(): String {
        return try {
            val custom = preferences.getJavaPath()
            if (custom.isNotEmpty()) custom
            else JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir())
        } catch (e: Throwable) {
            "未找到"
        }
    }

    /** 获取指定版本的独立 Java 路径，未配置返回空字符串 */
    fun getVersionJavaPath(versionId: String): String {
        return preferences.getVersionJavaPath(versionId)
    }

    /** 设置指定版本的独立 Java 路径，空字符串则清除 */
    fun setVersionJavaPath(versionId: String, javaPath: String) {
        preferences.setVersionJavaPath(versionId, javaPath)
    }

    /**
     * 使用指定的 Java 路径启动游戏（兼容性选项触发）。
     * 临时使用指定的 Java 路径，不修改用户偏好设置。
     */
    fun launchWithSpecificJava(versionId: String, javaPath: String, javaMajorVer: Int, javaArch: String) {
        dismissCompatOptions()
        scope.launch {
            _status.value = "使用指定 Java 启动…"
            try {
                val account = _account.value
                if (account == null) {
                    _status.value = "请先登录账户"
                    return@launch
                }
                val profile = withContext(Dispatchers.IO) {
                    core.profileBuilder().build(versionId, account, javaMajorVer, javaArch)
                }
                val logFile = config.getWorkDir().resolve("logs").resolve("latest.log")
                gameLogger = withContext(Dispatchers.IO) {
                    try { GameLogger(logFile) } catch (e: Throwable) { null }
                }
                _gameLogs.value = listOf(
                    "[PMCL] 使用外部 Java 启动: $javaPath",
                    "[PMCL] Java 版本: $javaMajorVer 架构: $javaArch",
                    ""
                )
                _gameRunning.value = true
                _status.value = "启动中… java=$javaPath (Java $javaMajorVer $javaArch) version=$versionId"
                val future = core.launch().launchAsync(
                    profile, javaPath,
                    { line -> _gameLogs.update { old -> (old + line).takeLast(2000) } },
                    gameLogger
                )
                val exitCode = withContext(Dispatchers.IO) { future.join() }
                _status.value = "游戏已退出（code=$exitCode）"
                _gameRunning.value = false
            } catch (e: Throwable) {
                _status.value = "启动失败: ${e.message}"
                _gameLogs.update { old -> (old + "启动失败: ${e.message}").takeLast(2000) }
            }
        }
    }

    /**
     * 用外部启动器（HMCL/LauncherX 等）启动指定版本。
     */
    fun launchWithExternalLauncher(
        launcher: ExternalLauncherDetector.ExternalLauncher,
        versionId: String
    ) {
        dismissCompatOptions()
        scope.launch {
            _status.value = "正在用 ${launcher.name} 启动…"
            try {
                val cmd = withContext(Dispatchers.IO) {
                    ExternalLauncherDetector.buildExternalLaunchCommand(launcher, versionId)
                }
                _gameLogs.value = listOf(
                    "[PMCL] 正在用 ${launcher.name} 启动版本 $versionId",
                    "[PMCL] 命令: ${cmd.joinToString(" ")}",
                    ""
                )
                withContext(Dispatchers.IO) {
                    val workDir = java.io.File(launcher.gameDir).let {
                        if (it.isDirectory) it else java.io.File(System.getProperty("user.home"))
                    }
                    ProcessBuilder(cmd).directory(workDir).start()
                }
                _status.value = "已打开 ${launcher.name}，请在 ${launcher.name} 中启动 $versionId"
            } catch (e: Throwable) {
                _status.value = "打开 ${launcher.name} 失败: ${e.message}"
                _gameLogs.value = listOf("打开 ${launcher.name} 失败: ${e.message}")
            }
        }
    }

    /**
     * 一键下载 Mojang 官方 Java 21 运行时（MC 1.20.5+ 必需）。
     * 下载完成后清空 preferences.javaPath，让启动时自动用新扫描到的 runtimes。
     */
    fun downloadJava21() {
        if (_javaDownloading.value) return
        scope.launch {
            _javaDownloading.value = true
            _javaDownloadStatus.value = "正在拉取 Java 21 清单…"
            try {
                val runtimeType = com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_21
                val entries = withContext(Dispatchers.IO) {
                    core.javaDownloader().listRuntimes(runtimeType).join()
                }
                if (entries.isNullOrEmpty()) {
                    _javaDownloadStatus.value = "未找到可用的 Java 21 运行时"
                    _status.value = "Java 21 下载失败：清单为空"
                    return@launch
                }
                // 选第一个（Mojang 通常每个类型只提供一个稳定版）
                val entry = entries[0]
                _javaDownloadStatus.value = "准备下载：${entry.version}（${entry.size / 1024 / 1024} MB）"
                // 清空 javaPath，确保启动时用新下载的 runtime
                preferences.setJavaPath("")
                withContext(Dispatchers.IO) {
                    core.javaDownloader().install(runtimeType, entry) { msg ->
                        _javaDownloadStatus.value = msg
                    }.join()
                }
                val detected = JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir())
                _javaDownloadStatus.value = "完成：$detected"
                _status.value = "Java 21 安装完成，可启动游戏"
            } catch (e: Throwable) {
                _javaDownloadStatus.value = "失败：${e.message}"
                _status.value = "Java 21 下载失败：${e.message}"
            } finally {
                _javaDownloading.value = false
            }
        }
    }

    /** 手动指定 Java 可执行文件路径（空字符串表示自动检测）。 */
    fun setJavaPath(path: String) {
        preferences.setJavaPath(path)
        _status.value = if (path.isEmpty()) "Java 路径已重置为自动检测" else "Java 路径已设置为 $path"
    }

    // ============ 世界管理 ============

    fun refreshWorlds() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val all = mutableListOf<WorldManager.WorldInfo>()
                    val seenPaths = mutableSetOf<String>()
                    val savesDirs = mutableListOf<Pair<Path, String>>()
                    // 1. PMCL 工作目录的 saves
                    savesDirs.add(config.getWorkDir().resolve("saves") to "PMCL")
                    // 2. 系统所有 Minecraft 根目录的 saves（HMCL / 官方启动器）
                    for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                        val mcRoot = mcDir.parent
                        if (mcRoot != null) savesDirs.add(mcRoot.resolve("saves") to "外部启动器")
                    }
                    // 3. 每个版本目录下的 saves（整合包结构：versions/<id>/saves/）
                    val allVersionsDirs = mutableListOf<Path>()
                    allVersionsDirs.add(config.getVersionsDir())
                    allVersionsDirs.addAll(com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs())
                    for (versionsDir in allVersionsDirs) {
                        val versionsFile = versionsDir.toFile()
                        if (!versionsFile.isDirectory) continue
                        val subDirs = versionsFile.listFiles { f -> f.isDirectory } ?: continue
                        for (subDir in subDirs) {
                            val versionSaves = subDir.toPath().resolve("saves")
                            savesDirs.add(versionSaves to subDir.name)
                        }
                    }
                    // 4. 版本隔离目录下的 saves（instances/<id>/saves/）
                    val instancesDir = config.getWorkDir().resolve("instances")
                    val instancesFile = instancesDir.toFile()
                    if (instancesFile.isDirectory) {
                        val instDirs = instancesFile.listFiles { f -> f.isDirectory } ?: emptyArray()
                        for (instDir in instDirs) {
                            val instSaves = instDir.toPath().resolve("saves")
                            savesDirs.add(instSaves to instDir.name)
                        }
                    }
                    // 扫描所有 saves 目录，按绝对路径去重
                    val wm = core.worlds()
                    val diag = StringBuilder()
                    diag.append("savesDirs.size = ${savesDirs.size}\n")
                    for ((savesDir, source) in savesDirs) {
                        try {
                            val part = wm.listWorlds(savesDir, source)
                            for (w in part) {
                                if (seenPaths.add(w.dir.toAbsolutePath().toString())) all.add(w)
                            }
                            diag.append("[$savesDir] → ${part.size} worlds (exists=${java.nio.file.Files.isDirectory(savesDir)})\n")
                        } catch (t: Throwable) {
                            diag.append("[$savesDir] → 异常: ${t.javaClass.simpleName}: ${t.message}\n")
                        }
                    }
                    diag.append("TOTAL = ${all.size} worlds\n")
                    System.err.println("[refreshWorlds] $diag")
                    all
                }
                _worlds.value = list
                _status.value = "扫描到 ${list.size} 个世界 [build 20260708.5]"
            } catch (e: Throwable) {
                _status.value = "扫描世界失败：${e.message} [build 20260708.5]"
            }
        }
    }

    fun backupWorld(world: WorldManager.WorldInfo) {
        scope.launch {
            try {
                val zip = withContext(Dispatchers.IO) { core.worlds().backup(world) }
                _status.value = "已备份到 ${zip.fileName}"
            } catch (e: Throwable) {
                _status.value = "备份失败：${e.message}"
            }
        }
    }

    fun deleteWorld(world: WorldManager.WorldInfo) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.worlds().delete(world) }
                _status.value = "已删除世界 ${world.name}"
                refreshWorlds()
            } catch (e: Throwable) {
                _status.value = "删除失败：${e.message}"
            }
        }
    }

    // ============ 截图 ============

    fun refreshScreenshots() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val all = mutableListOf<ScreenshotManager.Screenshot>()
                    val seenPaths = mutableSetOf<String>()
                    val shotDirs = mutableListOf<Pair<Path, String>>()
                    // 1. PMCL 工作目录的 screenshots
                    shotDirs.add(config.getWorkDir().resolve("screenshots") to "PMCL")
                    // 2. 系统所有 Minecraft 根目录的 screenshots（HMCL / 官方启动器）
                    for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                        val mcRoot = mcDir.parent
                        if (mcRoot != null) shotDirs.add(mcRoot.resolve("screenshots") to "外部启动器")
                    }
                    // 3. 每个版本目录下的 screenshots（整合包结构：versions/<id>/screenshots/）
                    val allVersionsDirs = mutableListOf<Path>()
                    allVersionsDirs.add(config.getVersionsDir())
                    allVersionsDirs.addAll(com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs())
                    for (versionsDir in allVersionsDirs) {
                        val versionsFile = versionsDir.toFile()
                        if (!versionsFile.isDirectory) continue
                        val subDirs = versionsFile.listFiles { f -> f.isDirectory } ?: continue
                        for (subDir in subDirs) {
                            val versionShots = subDir.toPath().resolve("screenshots")
                            shotDirs.add(versionShots to subDir.name)
                        }
                    }
                    // 4. 版本隔离目录下的 screenshots（instances/<id>/screenshots/）
                    val instancesDir = config.getWorkDir().resolve("instances")
                    val instancesFile = instancesDir.toFile()
                    if (instancesFile.isDirectory) {
                        val instDirs = instancesFile.listFiles { f -> f.isDirectory } ?: emptyArray()
                        for (instDir in instDirs) {
                            val instShots = instDir.toPath().resolve("screenshots")
                            shotDirs.add(instShots to instDir.name)
                        }
                    }
                    // 扫描所有 screenshots 目录，按绝对路径去重
                    val sm = core.screenshots()
                    val diag = StringBuilder()
                    diag.append("shotDirs.size = ${shotDirs.size}\n")
                    for ((shotDir, source) in shotDirs) {
                        try {
                            val part = sm.list(shotDir, source)
                            for (s in part) {
                                if (seenPaths.add(s.path.toAbsolutePath().toString())) all.add(s)
                            }
                            diag.append("[$shotDir] → ${part.size} shots (exists=${java.nio.file.Files.isDirectory(shotDir)})\n")
                        } catch (t: Throwable) {
                            diag.append("[$shotDir] → 异常: ${t.javaClass.simpleName}: ${t.message}\n")
                        }
                    }
                    // 合并后再次按修改时间倒序
                    all.sortByDescending { it.modified }
                    diag.append("TOTAL = ${all.size} shots\n")
                    System.err.println("[refreshScreenshots] $diag")
                    all
                }
                _screenshots.value = list
                _status.value = "扫描到 ${list.size} 张截图 [build 20260708.6]"
            } catch (e: Throwable) {
                _status.value = "扫描截图失败：${e.message} [build 20260708.6]"
            }
        }
    }

    fun deleteScreenshot(shot: ScreenshotManager.Screenshot) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.screenshots().delete(shot) }
                _status.value = "已删除 ${shot.name}"
                refreshScreenshots()
            } catch (e: Throwable) {
                _status.value = "删除失败：${e.message}"
            }
        }
    }

    // ============ 资源包 ============

    fun refreshResourcePacks() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { core.resourcePacks().list() }
                _resourcePacks.value = list
                _status.value = "扫描到 ${list.size} 个资源包"
            } catch (e: Throwable) {
                _status.value = "扫描资源包失败：${e.message}"
            }
        }
    }

    fun deleteResourcePack(pack: ResourcePackManager.Pack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.resourcePacks().delete(pack) }
                _status.value = "已删除 ${pack.name}"
                refreshResourcePacks()
            } catch (e: Throwable) {
                _status.value = "删除失败：${e.message}"
            }
        }
    }

    // ============ 完整性校验 ============

    fun checkIntegrity(versionId: String) {
        scope.launch {
            _status.value = "正在校验 $versionId 完整性…"
            try {
                val r = withContext(Dispatchers.IO) { core.integrity().check(versionId) }
                _integrityResult.value = r
                _status.value = if (r.isOk()) "完整性校验通过"
                    else "发现 ${r.issueCount} 个问题（缺失 ${r.missing.size} / 哈希不匹配 ${r.hashMismatch.size}）"
            } catch (e: Throwable) {
                _status.value = "校验失败：${e.message}"
            }
        }
    }

    // ============ 崩溃日志分析 ============

    fun refreshCrashReports() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    core.crashAnalyzer().scanReports(config.getWorkDir())
                }
                _crashReports.value = list
                _status.value = "扫描到 ${list.size} 份崩溃报告"
            } catch (e: Throwable) {
                _status.value = "扫描崩溃报告失败：${e.message}"
            }
        }
    }

    // ============ 崩溃恢复操作 ============

    /**
     * 执行崩溃恢复操作。
     * 根据 RecoveryType 调用对应的修复逻辑，执行后更新 _recoveryMessage 供 UI 显示反馈。
     */
    fun executeRecoveryAction(action: CrashAnalyzer.RecoveryAction, versionId: String) {
        when (action.getType()) {
            CrashAnalyzer.RecoveryType.INCREASE_MEMORY -> increaseMemory()
            CrashAnalyzer.RecoveryType.SWITCH_JAVA -> {
                _navigationRequest.value = "settings"
                _recoveryMessage.value = "已跳转到设置，请在「Java 路径」中为 $versionId 指定正确版本"
            }
            CrashAnalyzer.RecoveryType.CHECK_MOD_CONFLICTS -> {
                refreshInstalledMods()
                _recoveryMessage.value = "正在扫描模组冲突，请稍后查看模组页面"
            }
            CrashAnalyzer.RecoveryType.DISABLE_RECENT_MODS -> disableRecentMods(versionId)
            CrashAnalyzer.RecoveryType.CHECK_INTEGRITY -> {
                checkIntegrity(versionId)
                _recoveryMessage.value = "正在校验 $versionId 完整性，缺失文件将自动补全"
            }
            CrashAnalyzer.RecoveryType.REINSTALL_VERSION -> reinstallVersion(versionId)
            CrashAnalyzer.RecoveryType.CLEAR_GAME_CONFIG -> clearGameConfig(versionId)
            CrashAnalyzer.RecoveryType.SHARE_LOGS -> {
                shareLogs()
                _recoveryMessage.value = "正在上传日志到 paste.gg…"
            }
            CrashAnalyzer.RecoveryType.OPEN_MODS_PAGE -> {
                _navigationRequest.value = "content"
                _recoveryMessage.value = "已跳转到模组管理页面"
            }
            CrashAnalyzer.RecoveryType.OPEN_SETTINGS -> {
                _navigationRequest.value = "settings"
                _recoveryMessage.value = "已跳转到设置页面"
            }
        }
    }

    /** 增大最大内存 1024MB（上限为系统可用内存的 80%） */
    fun increaseMemory() {
        val current = preferences.getMaxMemoryMb()
        val sysMax = with(core.runtime()) { getTotalMemoryMb() }
        val ceiling = (sysMax * 0.8).toInt()
        val target = (current + 1024).coerceAtMost(ceiling)
        if (target <= current) {
            _recoveryMessage.value = "内存已达上限 ${ceiling}MB（系统可用 ${sysMax}MB）"
        } else {
            preferences.setMaxMemoryMb(target)
            _recoveryMessage.value = "最大内存已从 ${current}MB 调整为 ${target}MB，可重新启动游戏"
        }
    }

    /** 禁用最近添加的模组：将 mods 目录下最近修改的 5 个 .jar 移到 disabled 子目录 */
    fun disableRecentMods(versionId: String) {
        scope.launch {
            try {
                val moved = withContext(Dispatchers.IO) {
                    val modsDir = config.getWorkDir().resolve("mods")
                    if (!java.nio.file.Files.isDirectory(modsDir)) return@withContext 0
                    val disabledDir = modsDir.resolve("disabled")
                    java.nio.file.Files.createDirectories(disabledDir)
                    // 列出 .jar 并按 mtime 降序（最近添加的在前）
                    val jars = java.nio.file.Files.list(modsDir)
                        .filter { it.fileName.toString().endsWith(".jar") }
                        .toList()
                    val sorted = jars.sortedByDescending {
                        try { java.nio.file.Files.getLastModifiedTime(it).toMillis() }
                        catch (_: Throwable) { 0L }
                    }
                    var count = 0
                    for (jar in sorted.take(5)) {
                        try {
                            val dest = disabledDir.resolve(jar.fileName)
                            java.nio.file.Files.move(jar, dest,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            count++
                        } catch (_: Throwable) {}
                    }
                    count
                }
                _recoveryMessage.value = if (moved > 0)
                    "已禁用 $moved 个最近添加的模组（已移至 mods/disabled）"
                else "mods 目录下无可禁用的模组"
                if (moved > 0) refreshInstalledMods()
            } catch (e: Throwable) {
                _recoveryMessage.value = "禁用模组失败：${e.message}"
            }
        }
    }

    /** 重新安装版本：删除 versions/{id} 目录后触发安装 */
    fun reinstallVersion(versionId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val versionDir = config.getVersionsDir().resolve(versionId)
                    if (java.nio.file.Files.exists(versionDir)) {
                        java.nio.file.Files.walk(versionDir)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach { p -> try { java.nio.file.Files.deleteIfExists(p) } catch (_: Throwable) {} }
                    }
                }
                _recoveryMessage.value = "$versionId 旧文件已清除，正在重新安装…"
                installVersion(versionId)
            } catch (e: Throwable) {
                _recoveryMessage.value = "重新安装失败：${e.message}"
            }
        }
    }

    /** 清理游戏配置：备份并重置可能损坏的 options.txt / servers.dat */
    fun clearGameConfig(versionId: String) {
        scope.launch {
            try {
                val backedUp = withContext(Dispatchers.IO) {
                    val gameDir = config.getWorkDir()
                    val backupDir = gameDir.resolve("config-backup-${System.currentTimeMillis()}")
                    java.nio.file.Files.createDirectories(backupDir)
                    var count = 0
                    val targets = listOf("options.txt", "servers.dat",
                        "optionsof.txt", "servers.dat_old", "optionsSHA.txt")
                    for (name in targets) {
                        val f = gameDir.resolve(name)
                        if (java.nio.file.Files.exists(f)) {
                            java.nio.file.Files.move(f, backupDir.resolve(name),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                            count++
                        }
                    }
                    count
                }
                _recoveryMessage.value = if (backedUp > 0)
                    "已备份并清理 $backedUp 个配置文件（备份位于 config-backup-* 目录），可重新启动"
                else "未发现可清理的配置文件"
            } catch (e: Throwable) {
                _recoveryMessage.value = "清理配置失败：${e.message}"
            }
        }
    }

    // ============ 网络偏好（设置页用） ============

    /** 用户修改网络配置后调用，立即生效 */
    fun applyNetworkPreferences() {
        core.applyNetworkPreferences()
        _status.value = "已应用网络偏好（镜像/代理/限速）"
    }

    // ============ 光影包 ============

    fun refreshShaderPacks() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { core.shaderPacks().list() }
                _shaderPacks.value = list
                _status.value = "扫描到 ${list.size} 个光影包"
            } catch (e: Throwable) {
                _status.value = "扫描光影包失败：${e.message}"
            }
        }
    }

    fun deleteShaderPack(pack: ShaderPackManager.ShaderPack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.shaderPacks().delete(pack) }
                _status.value = "已删除光影包 ${pack.name}"
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = "删除失败：${e.message}"
            }
        }
    }

    /** 将指定光影包设为当前选中（写入 options.txt） */
    fun setActiveShaderPack(pack: ShaderPackManager.ShaderPack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.shaderPacks().setActive(pack) }
                _status.value = "已应用光影包：${pack.name}"
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = "应用失败：${e.message}"
            }
        }
    }

    /** 关闭光影（清空当前选中） */
    fun clearActiveShaderPack() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.shaderPacks().clearActive() }
                _status.value = "已关闭光影"
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = "关闭失败：${e.message}"
            }
        }
    }

    /** 在系统文件管理中打开 shaderpacks 目录 */
    fun openShaderPacksDir() {
        openDir(core.shaderPacks().shaderPacksDir.toFile())
    }

    /** 在系统文件管理中打开 resourcepacks 目录 */
    fun openResourcePacksDir() {
        openDir(core.resourcePacks().resourcePacksDir.toFile())
    }

    // ============ 数据包 ============

    fun refreshDatapacks(worldDir: java.nio.file.Path) {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { core.datapacks().list(worldDir) }
                _datapacks.value = list
                _status.value = "扫描到 ${list.size} 个数据包"
            } catch (e: Throwable) {
                _status.value = "扫描数据包失败：${e.message}"
            }
        }
    }

    fun deleteDatapack(pack: DatapackManager.Datapack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.datapacks().delete(pack) }
                _status.value = "已删除数据包 ${pack.name}"
                // 删除后刷新当前选中的世界
                _selectedDatapackWorld.value?.let { w ->
                    val list = withContext(Dispatchers.IO) { core.datapacks().list(w.dir) }
                    _datapacks.value = list
                }
            } catch (e: Throwable) {
                _status.value = "删除失败：${e.message}"
            }
        }
    }

    /** 当前选中的数据包世界（用于 DatapacksPage） */
    private val _selectedDatapackWorld = MutableStateFlow<WorldManager.WorldInfo?>(null)
    val selectedDatapackWorld: StateFlow<WorldManager.WorldInfo?> = _selectedDatapackWorld.asStateFlow()

    fun selectDatapackWorld(world: WorldManager.WorldInfo) {
        _selectedDatapackWorld.value = world
        refreshDatapacks(world.dir)
    }

    /** 清除选中的世界，返回世界列表视图 */
    fun clearDatapackWorld() {
        _selectedDatapackWorld.value = null
        _datapacks.value = emptyList()
    }

    /** 打开指定世界的 datapacks 目录 */
    fun openDatapacksDir(world: WorldManager.WorldInfo) {
        openDir(world.dir.resolve("datapacks").toFile())
    }

    // ============ Wiki 浏览 ============

    fun openWikiUrl(url: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WikiBrowser.open(url) }
                _status.value = "已在系统浏览器打开: $url"
            } catch (e: Throwable) {
                _status.value = "打开失败：${e.message}"
            }
        }
    }

    // ============ 语言切换 ============

    fun setLanguage(lang: String) {
        preferences.setLanguage(lang)
        core.applyLanguage(lang)
        _status.value = "已切换语言（重启 UI 后完全生效）"
    }

    // ============ 日志导出/分享 ============

    /** 清除已分享的 URL（关闭分享对话框时调用） */
    fun clearShareUrl() { _shareUrl.value = null }

    /**
     * 导出当前游戏日志到用户指定的文件路径。
     * @param targetPath 目标文件绝对路径
     * @return 是否成功
     */
    suspend fun exportLogs(targetPath: String): Boolean = withContext(Dispatchers.IO) {
        val logs = _gameLogs.value
        if (logs.isEmpty()) return@withContext false
        try {
            val path = java.nio.file.Paths.get(targetPath)
            java.nio.file.Files.createDirectories(path.parent)
            val content = buildString {
                append("PMCL 游戏日志\n")
                append("导出时间: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())).append("\n")
                append("=").append("=".repeat(60)).append("\n\n")
                append(logs.joinToString("\n"))
            }
            java.nio.file.Files.write(path, content.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            _status.value = "日志已导出到: $targetPath"
            true
        } catch (e: Throwable) {
            _status.value = "日志导出失败: ${e.message}"
            false
        }
    }

    /**
     * 上传当前游戏日志到 paste.gg，返回可分享的 URL。
     */
    fun shareLogs() {
        if (_logSharing.value) return
        val logs = _gameLogs.value
        if (logs.isEmpty()) {
            _status.value = "暂无日志可分享"
            return
        }
        _logSharing.value = true
        _shareUrl.value = null
        scope.launch {
            try {
                val content = logs.joinToString("\n")
                val name = "PMCL-Log-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())}"
                val url = core.pastebin().upload(content, name)
                _shareUrl.value = url
                _status.value = "日志已上传，分享链接已生成"
            } catch (e: Throwable) {
                _status.value = "日志上传失败: ${e.message}"
            } finally {
                _logSharing.value = false
            }
        }
    }

    // ============ 新闻 ============

    /**
     * 拉取 Minecraft.net 官方 RSS 新闻。
     * 进入新闻页时自动调用一次；网络失败会通过 status 反馈并保留旧数据。
     */
    fun refreshNews() {
        if (_newsLoading.value) return
        scope.launch {
            // 先读缓存秒开
            val cached = withContext(Dispatchers.IO) {
                DataCache.loadWithTimestamp("news_list", object : TypeToken<List<com.pmcl.core.news.NewsItem>>() {})
            }
            if (cached != null) {
                @Suppress("UNCHECKED_CAST")
                val data = cached[0] as? List<com.pmcl.core.news.NewsItem> ?: return@launch
                val savedAt = cached[1] as? Long ?: return@launch
                if (data.isNotEmpty()) {
                    _newsItems.value = data
                    _newsLoading.value = false
                }
                // 缓存未过期：后台静默刷新（stale-while-revalidate）
                if (!DataCache.isExpired(savedAt, 60 * 60 * 1000L)) {
                    scope.launch {
                        try {
                            val list = withContext(Dispatchers.IO) {
                                core.news().fetch(20).join()
                            }
                            _newsItems.value = list
                            DataCache.save("news_list", list)
                            _status.value = if (list.isEmpty()) "暂无新闻" else "已加载 ${list.size} 条新闻"
                        } catch (_: Throwable) {
                            // 静默失败，保留缓存数据
                        }
                    }
                    return@launch
                }
                // 缓存已过期：继续走正常网络请求
            }
            // 缓存不存在/已过期：正常网络请求
            _newsLoading.value = true
            _status.value = "正在加载新闻…"
            try {
                val list = withContext(Dispatchers.IO) {
                    core.news().fetch(20).join()
                }
                _newsItems.value = list
                _status.value = if (list.isEmpty()) "暂无新闻" else "已加载 ${list.size} 条新闻"
                DataCache.save("news_list", list)
            } catch (e: Throwable) {
                _status.value = "加载新闻失败：${e.message}"
            } finally {
                _newsLoading.value = false
            }
        }
    }

    /** 在系统浏览器打开新闻原文链接 */
    fun openNewsLink(url: String) {
        if (url.isBlank()) {
            _status.value = "该新闻没有可访问的链接"
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WikiBrowser.open(url) }
                _status.value = "已在系统浏览器打开新闻"
            } catch (e: Throwable) {
                _status.value = "打开失败：${e.message}"
            }
        }
    }

    /** 加载新闻文章正文（在 PMCL 内部展示） */
    fun loadArticle(url: String) {
        if (url.isBlank()) {
            _articleError.value = "该新闻没有可访问的链接"
            return
        }
        if (_articleLoading.value) return
        val cacheKey = "article_" + url.hashCode()
        scope.launch {
            // 先读缓存（永久缓存，命中即返回）
            val cached = withContext(Dispatchers.IO) {
                DataCache.load(cacheKey, object : TypeToken<com.pmcl.core.news.ArticleContent>() {})
            }
            if (cached != null) {
                _articleContent.value = cached
                _articleError.value = ""
                _articleLoading.value = false
                return@launch
            }
            // 缓存不存在：网络请求
            _articleLoading.value = true
            _articleError.value = ""
            _articleContent.value = null
            try {
                val content = withContext(Dispatchers.IO) {
                    core.news().fetchArticle(url).join()
                }
                _articleContent.value = content
                DataCache.save(cacheKey, content)
            } catch (e: Throwable) {
                _articleError.value = "加载文章失败：${e.message}"
            } finally {
                _articleLoading.value = false
            }
        }
    }

    /** 退出文章详情视图 */
    fun clearArticle() {
        _articleContent.value = null
        _articleError.value = ""
    }

    // ============ 翻译 ============

    /**
     * 翻译单段文本（带缓存）。
     * 如果已翻译过则直接返回缓存，否则调用 TranslateClient。
     * UI 层通过 [translationCache] 观察翻译结果。
     * 允许多条并发翻译，互不阻塞。
     */
    fun translateText(text: String) {
        if (text.isBlank()) return
        if (_translationCache.value.containsKey(text)) return

        translateCounter.incrementAndGet()
        _translating.value = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    core.translate().translate(text)
                }
                // 失败时 translate 返回原文：不缓存，以便后续重试
                if (result != text) {
                    _translationCache.update { old -> old + (text to result) }
                }
            } catch (_: Throwable) {
            } finally {
                if (translateCounter.decrementAndGet() <= 0) {
                    _translating.value = false
                }
            }
        }
    }

    /**
     * 批量翻译（带缓存，跳过已翻译的）。
     * 并行翻译，允许与 [translateText] 同时调用。
     * @param texts 待翻译文本列表
     */
    fun translateBatch(texts: List<String>) {
        val pending = texts.filter { it.isNotBlank() && !_translationCache.value.containsKey(it) }
        if (pending.isEmpty()) return

        translateCounter.incrementAndGet()
        _translating.value = true
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    core.translate().translateBatchAsync(pending).join()
                }
                val newEntries = mutableMapOf<String, String>()
                for (i in pending.indices) {
                    val original = pending[i]
                    val translated = results[i]
                    // 失败时 translate 返回原文：不缓存，以便后续重试
                    if (translated != original) {
                        newEntries[original] = translated
                    }
                }
                _translationCache.update { old -> old.toMutableMap().apply { putAll(newEntries) } }
            } catch (_: Throwable) {
            } finally {
                if (translateCounter.decrementAndGet() <= 0) {
                    _translating.value = false
                }
            }
        }
    }

    /** 获取翻译文本（无缓存则返回原文） */
    fun translated(original: String): String =
        _translationCache.value[original] ?: original

    /** 是否已翻译 */
    fun isTranslated(original: String): Boolean =
        _translationCache.value.containsKey(original)

    /** 清除翻译缓存 */
    fun clearTranslations() {
        _translationCache.value = emptyMap()
    }

    // ============ 多人联机（陶瓦联机） ============

    /** 创建新房间（房主） */
    /** 当前联机后端 */
    val mpBackend: com.pmcl.core.multiplayer.MultiplayerManager.Backend
        get() = when (preferences.getMpBackend()) {
            "CONNECTX" -> com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX
            "EASYTIER" -> com.pmcl.core.multiplayer.MultiplayerManager.Backend.EASYTIER
            else -> com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA
        }

    /** 切换联机后端 */
    fun setMpBackend(b: com.pmcl.core.multiplayer.MultiplayerManager.Backend) {
        if (_mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTING ||
            _mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
            _status.value = "请先离开当前房间再切换后端"
            return
        }
        val name = when (b) {
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> "CONNECTX"
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.EASYTIER -> "EASYTIER"
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA -> "TERRACOTTA"
        }
        preferences.setMpBackend(name)
        core.multiplayer().setBackend(b)
        val label = when (b) {
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> "ConnectX"
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.EASYTIER -> "EasyTier"
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA -> "Terracotta 陶瓦联机"
        }
        _status.value = "已切换到 $label 联机后端"
    }

    fun createRoom() {
        if (_mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTING ||
            _mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
            _status.value = "已在房间中，请先离开"
            return
        }
        val backend = mpBackend
        _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.DOWNLOADING
        _mpProgress.value = "准备中…"
        _status.value = when (backend) {
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> "正在创建 ConnectX 房间…"
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA -> "正在创建陶瓦联机房间（Terracotta）…"
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.EASYTIER -> "正在创建陶瓦联机房间…"
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    when (backend) {
                        com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> {
                            val binPath = preferences.getConnectxBinaryPath()
                            val serverAddr = preferences.getConnectxServerAddress()
                            val serverPort = preferences.getConnectxServerPort()
                            core.multiplayer().createRoomConnectX(
                                { msg -> _mpProgress.value = msg },
                                binPath, serverAddr, serverPort
                            ).join()
                        }
                        else -> {
                            // Terracotta / EasyTier 都走 createRoom
                            core.multiplayer().createRoom { msg ->
                                _mpProgress.value = msg
                            }.join()
                        }
                    }
                }
                refreshMpState()
                _status.value = if (core.multiplayer().state ==
                    com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
                    when (backend) {
                        com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA ->
                            "已创建房间，房间码：${core.multiplayer().currentRoomCode}"
                        com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX ->
                            "已创建 ConnectX 房间"
                        else ->
                            "已创建房间，虚拟 IP：${core.multiplayer().virtualIp}"
                    }
                } else {
                    "房间已启动，等待连接…"
                }
            } catch (e: Throwable) {
                _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.FAILED
                _status.value = "创建房间失败：${e.message}"
            } finally {
                _mpProgress.value = ""
            }
        }
    }

    /** 通过邀请码/房间码加入房间 */
    fun joinRoom(invitation: String) {
        if (invitation.isBlank()) {
            _status.value = "请输入房间码或邀请码"
            return
        }
        if (_mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTING ||
            _mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
            _status.value = "已在房间中，请先离开"
            return
        }
        val isConnectX = invitation.trim().startsWith("connectx-")
        val isTerracotta = invitation.trim().startsWith("U/") ||
            mpBackend == com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA
        _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.DOWNLOADING
        _mpProgress.value = "解析房间码…"
        _status.value = "正在加入房间…"
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (isConnectX) {
                        val binPath = preferences.getConnectxBinaryPath()
                        val serverAddr = preferences.getConnectxServerAddress()
                        val serverPort = preferences.getConnectxServerPort()
                        core.multiplayer().joinRoomConnectX(invitation, { msg ->
                            _mpProgress.value = msg
                        }, binPath, serverAddr, serverPort).join()
                    } else {
                        core.multiplayer().joinRoom(invitation) { msg ->
                            _mpProgress.value = msg
                        }.join()
                    }
                }
                refreshMpState()
                _status.value = if (core.multiplayer().state ==
                    com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
                    if (isTerracotta && core.multiplayer().localMcAddr.isNotEmpty()) {
                        "已加入房间，MC 地址：${core.multiplayer().localMcAddr}（直接连接用此地址）"
                    } else {
                        "已加入房间，虚拟 IP：${core.multiplayer().virtualIp}"
                    }
                } else {
                    "正在连接房间…"
                }
            } catch (e: Throwable) {
                _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.FAILED
                _status.value = "加入房间失败：${e.message}"
            } finally {
                _mpProgress.value = ""
            }
        }
    }

    /** 离开当前房间 */
    fun leaveRoom() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.multiplayer().leaveRoom() }
            } catch (e: Throwable) {
                _status.value = "离开房间失败：${e.message}"
            }
            refreshMpState()
            _mpInvitation.value = ""
            _mpVirtualIp.value = ""
            _mpLocalMcAddr.value = ""
            val backend = mpBackend
            _status.value = when (backend) {
                com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> "已离开 ConnectX 房间"
                com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA -> "已离开陶瓦联机房间"
                else -> "已离开陶瓦联机房间"
            }
        }
    }

    /** 刷新当前房间状态 / 邀请码 / 虚拟 IP 到 StateFlow */
    fun refreshMpState() {
        val mgr = core.multiplayer()
        _mpState.value = mgr.state
        _mpVirtualIp.value = mgr.virtualIp
        _mpInvitation.value = if (mgr.isInRoom) mgr.generateInvitation() else ""
        _mpLocalMcAddr.value = mgr.localMcAddr
    }

    /** 复制邀请码到系统剪贴板 */
    fun copyInvitation() {
        val code = core.multiplayer().generateInvitation()
        if (code.isEmpty()) {
            _status.value = "当前没有可分享的邀请码"
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    java.awt.Toolkit.getDefaultToolkit()
                        .systemClipboard.setContents(
                            java.awt.datatransfer.StringSelection(code), null
                        )
                }
                _status.value = "邀请码已复制到剪贴板"
            } catch (e: Throwable) {
                _status.value = "复制失败：${e.message}"
            }
        }
    }

    /** 复制任意文本到系统剪贴板 */
    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    java.awt.Toolkit.getDefaultToolkit()
                        .systemClipboard.setContents(
                            java.awt.datatransfer.StringSelection(text), null
                        )
                }
                _status.value = "已复制：$text"
            } catch (e: Throwable) {
                _status.value = "复制失败：${e.message}"
            }
        }
    }

    // ============ 收藏服务器列表 + ping 延迟 ============

    /** 服务器列表数据项 */
    data class FavoriteServer(val name: String, val host: String, val port: Int)

    /** ping 结果：key = "host:port"，value = 延迟毫秒（-1 不可达，-2 超时） */
    private val _serverPings = MutableStateFlow<Map<String, Long>>(emptyMap())
    val serverPings: StateFlow<Map<String, Long>> = _serverPings.asStateFlow()

    /** 服务器列表（可观察） */
    private val _favoriteServers = MutableStateFlow<List<FavoriteServer>>(emptyList())
    val favoriteServers: StateFlow<List<FavoriteServer>> = _favoriteServers.asStateFlow()

    /** 加载收藏服务器列表 */
    fun loadFavoriteServers() {
        _favoriteServers.value = preferences.getFavoriteServers().map {
            FavoriteServer(it[0], it[1], it[2].toIntOrNull() ?: 25565)
        }
    }

    /** 添加收藏服务器 */
    fun addFavoriteServer(name: String, host: String, port: Int) {
        preferences.addFavoriteServer(name, host, port)
        loadFavoriteServers()
    }

    /** 删除收藏服务器 */
    fun removeFavoriteServer(index: Int) {
        preferences.removeFavoriteServer(index)
        loadFavoriteServers()
    }

    /** 将服务器设为直连目标（写入 gameServerHost/Port） */
    fun setDirectConnectServer(host: String, port: Int) {
        preferences.setGameServerHost(host)
        preferences.setGameServerPort(port)
        _status.value = "已设置直连服务器：$host:$port"
    }

    /** ping 单个服务器 */
    fun pingServer(host: String, port: Int) {
        val key = "$host:$port"
        scope.launch {
            try {
                val latency = withContext(Dispatchers.IO) {
                    com.pmcl.core.multiplayer.ServerPinger.ping(host, port)
                }
                _serverPings.value = _serverPings.value + (key to latency)
            } catch (e: Throwable) {
                _serverPings.value = _serverPings.value + (key to com.pmcl.core.multiplayer.ServerPinger.UNREACHABLE)
            }
        }
    }

    /** 批量 ping 所有收藏服务器 */
    fun pingAllServers() {
        val servers = _favoriteServers.value
        servers.forEach { s -> pingServer(s.host, s.port) }
    }

    // ============ 首次启动 / 迁移 ============

    /** 扫描本机其他启动器的数据目录（HMCL / PCL / 系统 .minecraft） */
    fun detectMigrationSources() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { core.migration().detectSources() }
                _migrationSources.value = list
                _status.value = if (list.isEmpty()) "未检测到可迁移的启动器" else "检测到 ${list.size} 个可迁移来源"
            } catch (e: Throwable) {
                _status.value = "扫描失败：${e.message}"
            }
        }
    }

    /** 从指定来源迁移游戏数据到 PMCL 工作目录 */
    fun migrateFrom(source: com.pmcl.core.migration.MigrationManager.Source) {
        if (_migrating.value) return
        scope.launch {
            _migrating.value = true
            _migrationProgress.value = "开始从 ${source.getName()} 迁移…"
            _status.value = "正在从 ${source.getName()} 迁移游戏数据…"
            try {
                withContext(Dispatchers.IO) {
                    core.migration().migrate(source) { msg ->
                        _migrationProgress.value = msg
                    }
                }
                // 迁移完成后刷新本地版本
                refreshLocalVersions()
                _status.value = "迁移完成，已加载本地版本"
                _migrationProgress.value = "迁移完成"
            } catch (e: Throwable) {
                _status.value = "迁移失败：${e.message}"
                _migrationProgress.value = "迁移失败：${e.message}"
            } finally {
                _migrating.value = false
            }
        }
    }

    /** 完成首次启动欢迎流程，进入主界面 */
    fun completeFirstLaunch() {
        preferences.setFirstLaunchCompleted(true)
        _firstLaunchCompleted.value = true
    }
}
