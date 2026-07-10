package com.pmcl.ui.viewmodel

import com.google.gson.reflect.TypeToken
import com.pmcl.core.LauncherConfig
import com.pmcl.core.LauncherCore
import com.pmcl.core.auth.Account
import com.pmcl.core.auth.DeviceCode
import com.pmcl.core.cache.DataCache
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
import com.pmcl.core.preferences.Preferences
import com.pmcl.core.version.McVersion
import com.pmcl.core.gamecontent.WorldManager
import com.pmcl.core.gamecontent.ScreenshotManager
import com.pmcl.core.gamecontent.ResourcePackManager
import com.pmcl.core.gamecontent.ShaderPackManager
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

    // ===== 微软登录 =====
    private val _deviceCode = MutableStateFlow<DeviceCode?>(null)
    val deviceCode: StateFlow<DeviceCode?> = _deviceCode.asStateFlow()

    private val _loggingIn = MutableStateFlow(false)
    val loggingIn: StateFlow<Boolean> = _loggingIn.asStateFlow()

    // ===== 启动日志 =====
    private val _gameLogs = MutableStateFlow<List<String>>(emptyList())
    val gameLogs: StateFlow<List<String>> = _gameLogs.asStateFlow()

    private val _gameRunning = MutableStateFlow(false)
    val gameRunning: StateFlow<Boolean> = _gameRunning.asStateFlow()

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

    // ===== 游戏安装完成事件（用于弹窗询问是否安装模组加载器）=====
    /**
     * Vanilla 游戏版本安装成功后触发的事件。
     * UI 监听此流弹出模组加载器安装询问对话框。
     * null 表示无事件（已清除或未触发）。
     */
    data class InstallCompleteEvent(
        val versionId: String
    )
    private val _installCompleteEvent = MutableStateFlow<InstallCompleteEvent?>(null)
    val installCompleteEvent: StateFlow<InstallCompleteEvent?> = _installCompleteEvent.asStateFlow()

    /** 清除安装完成事件（UI 关闭弹窗时调用） */
    fun clearInstallCompleteEvent() { _installCompleteEvent.value = null }

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
        if (_gameRunning.value) {
            _status.value = "游戏已在运行中，请先等待退出"
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
                val data = cached[0] as List<McVersion>
                val savedAt = cached[1] as Long
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
        val ts = targetThemeState ?: themeState
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
                val palette = withContext(Dispatchers.IO) {
                    com.pmcl.core.theme.WallpaperColorProvider.generatePalette(seedColor, dark)
                }
                val scheme = if (dark) {
                    androidx.compose.material3.darkColorScheme(
                        primary = androidx.compose.ui.graphics.Color(palette[0] or 0xFF000000.toInt()),
                        secondary = androidx.compose.ui.graphics.Color(palette[1] or 0xFF000000.toInt()),
                        tertiary = androidx.compose.ui.graphics.Color(palette[2] or 0xFF000000.toInt()),
                        background = androidx.compose.ui.graphics.Color(palette[3] or 0xFF000000.toInt()),
                        surface = androidx.compose.ui.graphics.Color(palette[4] or 0xFF000000.toInt())
                    )
                } else {
                    androidx.compose.material3.lightColorScheme(
                        primary = androidx.compose.ui.graphics.Color(palette[0] or 0xFF000000.toInt()),
                        secondary = androidx.compose.ui.graphics.Color(palette[1] or 0xFF000000.toInt()),
                        tertiary = androidx.compose.ui.graphics.Color(palette[2] or 0xFF000000.toInt()),
                        background = androidx.compose.ui.graphics.Color(palette[3] or 0xFF000000.toInt()),
                        surface = androidx.compose.ui.graphics.Color(palette[4] or 0xFF000000.toInt())
                    )
                }
                // Compose Desktop 的 mutableStateOf 可以在任意线程更新（快照系统自动同步）
                com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] calling updateDynamicColorScheme, ts=${ts != null}, primary=${scheme.primary}")
                ts?.updateDynamicColorScheme(scheme)
                com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] updateDynamicColorScheme done, ts.dynamicColorScheme=${ts?.dynamicColorScheme}")
                _status.value = "莫奈取色已应用（种子色: #${Integer.toHexString(seedColor).padStart(6, '0')}）"
            } catch (e: Throwable) {
                com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] EXCEPTION: ${e.javaClass.name}: ${e.message}")
                _status.value = "壁纸取色失败：${e.message}"
            }
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

    fun installVersion(versionId: String) {
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
                // 触发安装完成事件，UI 监听后弹窗询问是否安装模组加载器
                _installCompleteEvent.value = InstallCompleteEvent(versionId)
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
                val data = cached[0] as List<ModLoaderVersion>
                val savedAt = cached[1] as Long
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
                val data = cached[0] as List<ModProject>
                val savedAt = cached[1] as Long
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
                    core.modMarket().installMod(file, gameVersion) { msg ->
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
        if (_gameRunning.value) {
            _status.value = "游戏已在运行中"
            return
        }
        // mod 冲突检测：仅警告，不阻断启动
        // （NeoForge 支持 jar-in-jar 内嵌依赖，Sinytra Connector 提供 fabric 兼容层，
        //   静态扫描无法检测这些，误报率高；真正的冲突游戏自己会崩并生成崩溃报告）
        val conflicts = _modConflicts.value
        if (conflicts != null && conflicts.hasIssues()) {
            _gameLogs.value = _gameLogs.value + "[警告] mod 冲突检测（仅供参考，不阻断启动）："
            conflicts.getErrors().take(5).forEach {
                _gameLogs.value = _gameLogs.value + "  - $it"
            }
            if (conflicts.getErrors().size > 5) {
                _gameLogs.value = _gameLogs.value + "  …还有 ${conflicts.getErrors().size - 5} 条，见模组页"
            }
        }

        scope.launch {
            _status.value = "正在构建启动配置…"
            try {
                // 先读取版本要求的 Java 版本，用于选择合适的 Java 运行时
                // alpha/beta/1.7- 无 javaVersion 字段返回 0，按旧版本处理（需 Java 8）
                val requiredJavaVer = withContext(Dispatchers.IO) {
                    try { core.profileBuilder().getRequiredJavaVersion(versionId) } catch (e: Exception) { 0 }
                }
                val javaExe = withContext(Dispatchers.IO) {
                    val customPath = preferences.getJavaPath()
                    if (customPath.isNotEmpty()) customPath
                    else JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir(), requiredJavaVer)
                        ?: ""
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
                            } catch (e: Exception) {
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

                // 创建/复用 GameLogger 持久化日志
                val logFile = config.getWorkDir().resolve("logs").resolve("latest.log")
                gameLogger = withContext(Dispatchers.IO) {
                    try { GameLogger(logFile) } catch (e: Exception) { null }
                }

                _gameLogs.value = if (usingCompatLayer) {
                    listOf(
                        "[PMCL 兼容层] 检测到旧版本使用 Java $javaMajorVer 启动（推荐 Java ${requiredJavaVer}）",
                        "[PMCL 兼容层] 已通过 PmclBootstrap 入口类注入 URLClassLoader，解决 LaunchWrapper 兼容问题",
                        "[PMCL 兼容层] 已注入 --add-opens 参数，允许旧版本反射访问 Java 内部 API",
                        "[PMCL 兼容层] 如遇问题，请安装 Java 8 以获得最佳兼容性",
                        ""
                    )
                } else emptyList()
                _gameRunning.value = true
                _status.value = "启动中… java=$javaExe (Java $javaMajorVer $javaArch) version=$versionId" +
                        if (usingCompatLayer) " [兼容层]" else ""

                // 记录启动前的崩溃报告快照（用于退出后对比新增）
                val crashDirBefore = withContext(Dispatchers.IO) {
                    try { core.crashAnalyzer().scanReports(config.getWorkDir()).map { it.getFile().toString() }.toSet() }
                    catch (t: Throwable) { emptySet<String>() }
                }

                // 记录启动：最近使用列表 + 最后游玩时间戳
                val launchTime = System.currentTimeMillis()
                preferences.recordRecentVersion(versionId)
                preferences.setLastPlayedTime(versionId, launchTime)
                _recentVersions.value = preferences.getRecentVersions()
                _lastPlayedTimes.value = HashMap(preferences.getLastPlayedTimesRaw())

                // launchAsync 返回 CompletableFuture，需等待进程退出，否则 gameRunning 会立即被 finally 重置
                val future = core.launch().launchAsync(
                    profile, javaExe,
                    { line -> _gameLogs.value = _gameLogs.value + line },
                    gameLogger
                )
                val exitCode = withContext(Dispatchers.IO) { future.join() }
                _status.value = "游戏已退出（code=$exitCode）"

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
                _gameLogs.value = _gameLogs.value + "[错误] ${e.message}"
            } finally {
                _gameRunning.value = false
                gameLogger?.close()
                gameLogger = null
            }
        }
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
        } catch (e: Exception) {
            "未找到"
        }
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
                    try { GameLogger(logFile) } catch (e: Exception) { null }
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
                    { line -> _gameLogs.value = _gameLogs.value + line },
                    gameLogger
                )
                val exitCode = withContext(Dispatchers.IO) { future.join() }
                _status.value = "游戏已退出（code=$exitCode）"
                _gameRunning.value = false
            } catch (e: Exception) {
                _status.value = "启动失败: ${e.message}"
                _gameLogs.value = (_gameLogs.value + "启动失败: ${e.message}")
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
            } catch (e: Exception) {
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
                val data = cached[0] as List<com.pmcl.core.news.NewsItem>
                val savedAt = cached[1] as Long
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
     */
    fun translateText(text: String) {
        if (text.isBlank()) return
        // 已缓存：跳过
        if (_translationCache.value.containsKey(text)) return
        if (_translating.value) return

        scope.launch {
            _translating.value = true
            try {
                val result = withContext(Dispatchers.IO) {
                    core.translate().translate(text)
                }
                _translationCache.value = _translationCache.value + (text to result)
            } catch (_: Throwable) {
                // 翻译失败：不阻断 UI
            } finally {
                _translating.value = false
            }
        }
    }

    /**
     * 批量翻译（带缓存，跳过已翻译的）。
     * @param texts 待翻译文本列表
     */
    fun translateBatch(texts: List<String>) {
        val pending = texts.filter { it.isNotBlank() && !_translationCache.value.containsKey(it) }
        if (pending.isEmpty() || _translating.value) return

        scope.launch {
            _translating.value = true
            try {
                val results = withContext(Dispatchers.IO) {
                    core.translate().translateBatchAsync(pending).join()
                }
                val newMap = _translationCache.value.toMutableMap()
                for (i in pending.indices) {
                    newMap[pending[i]] = results[i]
                }
                _translationCache.value = newMap
            } catch (_: Throwable) {
            } finally {
                _translating.value = false
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
