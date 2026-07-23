package com.pmcl.ui.viewmodel

import com.google.gson.reflect.TypeToken
import com.pmcl.core.LauncherConfig
import com.pmcl.core.LauncherCore
import com.pmcl.core.auth.Account
import com.pmcl.core.auth.AccountStore
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
import com.pmcl.core.modpack.ModpackManager.ModpackUpdateResult
import com.pmcl.core.modpack.ModpackManager.ModUpdate
import com.pmcl.core.nbt.NbtReader
import com.pmcl.core.nbt.NbtTag
import com.pmcl.core.nbt.NbtWriter
import com.pmcl.core.preferences.Preferences
import com.pmcl.core.stats.PlayTimeTracker
import com.pmcl.core.update.GitHubReleaseSyncChecker
import com.pmcl.core.update.SelfUpdater
import com.pmcl.core.version.McVersion
import com.pmcl.core.gamecontent.WorldManager
import com.pmcl.core.gamecontent.ScreenshotManager
import com.pmcl.core.gamecontent.ResourcePackManager
import com.pmcl.core.gamecontent.ShaderPackManager
import com.pmcl.core.gamecontent.ConfigFileManager
import com.pmcl.core.gamecontent.DatapackManager
import com.pmcl.core.install.IntegrityChecker
import com.pmcl.core.launch.CrashAnalyzer
import com.pmcl.core.instance.InstanceInfo
import com.pmcl.core.instance.InstanceManager
import com.pmcl.core.web.WikiBrowser
import com.pmcl.core.i18n.I18n
import com.pmcl.music.source.AudioSourceResolver
import com.pmcl.music.player.MusicPlayer
import com.pmcl.music.player.PlaybackState
import com.pmcl.music.player.MusicPlayerListener
import com.pmcl.ui.page.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 启动器主 ViewModel：UI 与 Java 内核之间的桥接层。
 */
class LauncherViewModel {

    // M29 拆分：scope 标 @PublishedApi internal 以便同模块扩展函数（Music/NBT 等）访问
    @PublishedApi internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default +
        CoroutineExceptionHandler { _, throwable ->
            System.err.println("[LauncherViewModel] 未捕获的协程异常: ${throwable.message}")
            throwable.printStackTrace()
            // 更新 UI 状态让用户感知到错误，而非完全静默
            _status.value = I18n.t("status.internal_error", throwable.message ?: I18n.t("common.unknown"))
        })

    val core = LauncherCore()

    // ===== GitHub Release 同步更新 =====
    /** 同步是否处于活动状态（已启用且调度器已启动） */
    private val _syncActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val syncActive: kotlinx.coroutines.flow.StateFlow<Boolean> = _syncActive

    /** 发现的新版本（null = 无；非 null = 待用户处理的更新通知） */
    private val _pushedUpdate = kotlinx.coroutines.flow.MutableStateFlow<SelfUpdater.UpdateInfo?>(null)
    val pushedUpdate: kotlinx.coroutines.flow.StateFlow<SelfUpdater.UpdateInfo?> = _pushedUpdate

    /** 同步状态描述（检查中 / 已是最新 / 错误 / 速率限制等） */
    private val _pushStatusText = kotlinx.coroutines.flow.MutableStateFlow("")
    val pushStatusText: kotlinx.coroutines.flow.StateFlow<String> = _pushStatusText

    /** 同步监听器引用（用于 start/stop 时 add/remove） */
    private var syncListener: GitHubReleaseSyncChecker.Listener? = null

    init {
        // 注入 video 模块的主菜单背景视频处理器（JavaCV 实现）
        // core 模块不依赖 video，通过接口注入避免循环依赖；video 模块未就绪时该功能降级不可用
        try {
            core.profileBuilder().setMenuBackgroundProvider(com.pmcl.video.MenuBackgroundManager())
        } catch (e: Throwable) {
            System.err.println("[LauncherViewModel] MenuBackgroundProvider 注入失败: ${e.message}")
        }
        // 注册 GitHub Release 同步监听器
        setupGithubSyncListener()
    }

    /**
     * 注册 GitHub Release 同步监听器，将同步事件映射到 StateFlow 供 UI 观察。
     * 仅注册一次；启用/禁用通过 start() 控制，不重复 add/remove。
     */
    private fun setupGithubSyncListener() {
        val client = core.githubSync() ?: return
        val listener = object : GitHubReleaseSyncChecker.Listener {
            override fun onUpdateAvailable(info: SelfUpdater.UpdateInfo) {
                // 仅当尚未有待处理更新时才覆盖，避免覆盖用户尚未响应的更新
                if (_pushedUpdate.value == null) {
                    _pushedUpdate.value = info
                }
                _pushStatusText.value = "发现新版本 v${info.version}"
            }
            override fun onUpToDate() {
                _pushStatusText.value = "已是最新版本"
            }
            override fun onError(message: String, cause: Throwable?) {
                _pushStatusText.value = "错误: $message"
            }
            override fun onRateLimited(retryAfterMinutes: Long) {
                _pushStatusText.value = "GitHub API 速率限制，${retryAfterMinutes}分钟后重试"
            }
        }
        client.addListener(listener)
        syncListener = listener
        // 若 core 初始化时已启动同步，则反映活动状态
        if (preferences.isGithubSyncEnabled() && preferences.getGithubRepo().isNotEmpty()) {
            _syncActive.value = true
        }
    }

    /** 用户在设置页开启/关闭 GitHub Release 同步 */
    fun setGithubSyncEnabled(enabled: Boolean) {
        val client = core.githubSync() ?: return
        preferences.setGithubSyncEnabled(enabled)
        if (enabled) {
            val repo = preferences.getGithubRepo()
            if (repo.isNotEmpty()) {
                client.setGithubRepo(repo)
                client.start()
                _syncActive.value = true
                _pushStatusText.value = "已启用，正在检查更新..."
            } else {
                _pushStatusText.value = "已启用，请填写 GitHub 仓库地址"
            }
        } else {
            // 关闭：不调用 client.close()（会销毁调度器，后续无法重启）
            // 用户重启启动器后才会真正停止调度器
            _syncActive.value = false
            _pushStatusText.value = "已禁用（重启后生效）"
        }
    }

    /** 用户在设置页修改 GitHub 仓库（格式 "owner/repo"） */
    fun setGithubRepo(repo: String) {
        val client = core.githubSync() ?: return
        preferences.setGithubRepo(repo)
        client.setGithubRepo(repo)
        // 仓库变更后立即触发一次检查（若已启用）
        if (preferences.isGithubSyncEnabled() && repo.isNotEmpty()) {
            client.checkNow()
            _pushStatusText.value = "仓库已更新，正在检查..."
        } else {
            _pushStatusText.value = "仓库已保存（启用后生效）"
        }
    }

    /** 用户响应了更新弹窗（无论下载/取消），清除待处理状态 */
    fun clearPushedUpdate() {
        _pushedUpdate.value = null
    }

    /** 用户确认下载发现的更新 */
    fun downloadPushedUpdate(onProgress: (Long) -> Unit) {
        val info = _pushedUpdate.value ?: return
        val updater = core.selfUpdater() ?: return
        scope.launch {
            try {
                _pushStatusText.value = "正在下载更新 v${info.version}..."
                updater.downloadUpdate(info, onProgress).join()
                _pushStatusText.value = "更新已下载，下次启动时生效"
                _pushedUpdate.value = null
            } catch (e: Throwable) {
                _pushStatusText.value = "下载更新失败: ${e.message}"
            }
        }
    }

    /**
     * 优雅关闭：取消所有后台协程，释放资源。
     * 应在应用退出前调用，避免 JVM 强杀导致正在进行的文件写入损坏。
     */
    fun shutdown() {
        scope.cancel()
    }

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
    @PublishedApi internal val _status = MutableStateFlow(I18n.t("status.ready"))
    val status: StateFlow<String> = _status.asStateFlow()

    /** UI 层更新状态栏文本（如浏览器打开失败等错误提示） */
    fun updateStatus(msg: String) {
        _status.value = msg
    }

    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

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

    // ===== 整合包更新检查 =====
    private val _modpackUpdateResult = MutableStateFlow<ModpackUpdateResult?>(null)
    val modpackUpdateResult: StateFlow<ModpackUpdateResult?> = _modpackUpdateResult.asStateFlow()
    private val _modpackUpdateChecking = MutableStateFlow(false)
    val modpackUpdateChecking: StateFlow<Boolean> = _modpackUpdateChecking.asStateFlow()

    // ===== NBT 编辑器 =====
    private val _nbtRoot = MutableStateFlow<NbtTag?>(null)
    val nbtRoot: StateFlow<NbtTag?> = _nbtRoot.asStateFlow()
    private val _nbtFilePath = MutableStateFlow<String?>(null)
    val nbtFilePath: StateFlow<String?> = _nbtFilePath.asStateFlow()
    private val _nbtDirty = MutableStateFlow(false)
    val nbtDirty: StateFlow<Boolean> = _nbtDirty.asStateFlow()
    private val _nbtError = MutableStateFlow<String?>(null)
    val nbtError: StateFlow<String?> = _nbtError.asStateFlow()
    /** 修订计数器：每次树结构修改时递增，强制 Compose 重组（解决同引用 StateFlow 不刷新问题） */
    private val _nbtRevision = MutableStateFlow(0)
    val nbtRevision: StateFlow<Int> = _nbtRevision.asStateFlow()

    // ===== 下载队列 =====
    private val _queueTasks = MutableStateFlow<List<DownloadQueueManager.QueueTask>>(emptyList())
    val queueTasks: StateFlow<List<DownloadQueueManager.QueueTask>> = _queueTasks.asStateFlow()

    private val _queueSummary = MutableStateFlow<DownloadQueueManager.QueueSummary>(
        DownloadQueueManager.QueueSummary(0, 0, 0, 0, 0, 0, 0L, 0L)
    )
    val queueSummary: StateFlow<DownloadQueueManager.QueueSummary> = _queueSummary.asStateFlow()

    /** 队列监听器初始化标志，避免重复注册 */
    @Volatile private var queueListenerRegistered = false

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

    // ===== 性能 HUD 浮窗 =====
    private val _perfHudVisible = MutableStateFlow(preferences.isShowPerfHud())
    val perfHudVisible: StateFlow<Boolean> = _perfHudVisible.asStateFlow()
    private val _perfHudMetrics = MutableStateFlow(preferences.getPerfHudMetrics())
    val perfHudMetrics: StateFlow<String> = _perfHudMetrics.asStateFlow()

    // ===== 音乐播放器 =====
    // M29 拆分：音乐域状态/函数已移至 LauncherViewModelMusic.kt（扩展函数）。
    // 此处状态标 @PublishedApi internal 以便同模块扩展函数访问，公共只读视图保持不变。
    @PublishedApi internal val gson = com.google.gson.Gson()
    @PublishedApi internal val musicPlayer = MusicPlayer()
    @PublishedApi internal val audioResolver = AudioSourceResolver()

    @PublishedApi internal val _musicPlaylist = MutableStateFlow<List<MusicTrack>>(emptyList())
    val musicPlaylist: StateFlow<List<MusicTrack>> = _musicPlaylist.asStateFlow()

    @PublishedApi internal val _musicCurrentIndex = MutableStateFlow(-1)
    val musicCurrentIndex: StateFlow<Int> = _musicCurrentIndex.asStateFlow()

    @PublishedApi internal val _musicPlaybackState = MutableStateFlow(PlaybackState.IDLE)
    val musicPlaybackState: StateFlow<PlaybackState> = _musicPlaybackState.asStateFlow()

    @PublishedApi internal val _musicCurrentMs = MutableStateFlow(0L)
    val musicCurrentMs: StateFlow<Long> = _musicCurrentMs.asStateFlow()
    // 音乐进度节流：记录上次发射的整秒值，仅在整秒变化时才更新 _musicCurrentMs
    @Volatile private var lastMusicProgressSec = -1L

    @PublishedApi internal val _musicDurationMs = MutableStateFlow(0L)
    val musicDurationMs: StateFlow<Long> = _musicDurationMs.asStateFlow()

    @PublishedApi internal val _musicVolume = MutableStateFlow(80)
    val musicVolume: StateFlow<Int> = _musicVolume.asStateFlow()

    @PublishedApi internal val _musicMuted = MutableStateFlow(false)
    val musicMuted: StateFlow<Boolean> = _musicMuted.asStateFlow()

    @PublishedApi internal val _musicLoadingUrl = MutableStateFlow<String?>(null)
    val musicLoadingUrl: StateFlow<String?> = _musicLoadingUrl.asStateFlow()

    @PublishedApi internal val _musicRepeatMode = MutableStateFlow(0)  // 0=顺序, 1=列表循环, 2=单曲循环
    val musicRepeatMode: StateFlow<Int> = _musicRepeatMode.asStateFlow()

    @PublishedApi internal val _musicShuffle = MutableStateFlow(false)
    val musicShuffle: StateFlow<Boolean> = _musicShuffle.asStateFlow()

    fun setPerfHudVisible(v: Boolean) {
        preferences.setShowPerfHud(v)
        _perfHudVisible.value = v
    }
    fun setPerfHudMetrics(v: String) {
        preferences.setPerfHudMetrics(v)
        _perfHudMetrics.value = v
    }

    // ===== 视差背景 + 玻璃主题（响应式，可在设置中实时切换） =====
    private val _parallaxBackground = MutableStateFlow(preferences.isParallaxBackground())
    val parallaxBackground: StateFlow<Boolean> = _parallaxBackground.asStateFlow()
    private val _glassTheme = MutableStateFlow(preferences.isGlassTheme())
    val glassTheme: StateFlow<Boolean> = _glassTheme.asStateFlow()
    private val _lockscreenLaunchTheme = MutableStateFlow(preferences.isLockscreenLaunchTheme())
    val lockscreenLaunchTheme: StateFlow<Boolean> = _lockscreenLaunchTheme.asStateFlow()

    fun setParallaxBackground(v: Boolean) {
        preferences.setParallaxBackground(v)
        _parallaxBackground.value = v
        themeState?.applyParallaxBackground(v)
    }
    fun setGlassTheme(v: Boolean) {
        preferences.setGlassTheme(v)
        _glassTheme.value = v
        themeState?.applyGlassTheme(v)
    }
    fun setLockscreenLaunchTheme(v: Boolean) {
        preferences.setLockscreenLaunchTheme(v)
        _lockscreenLaunchTheme.value = v
        themeState?.applyLockscreenLaunchTheme(v)
    }

    // ===== 模组依赖安装 =====
    private val _installingDeps = MutableStateFlow(false)
    val installingDeps: StateFlow<Boolean> = _installingDeps.asStateFlow()

    private val _depInstallResult = MutableStateFlow<ModDependencyResolver.DependencyResult?>(null)
    val depInstallResult: StateFlow<ModDependencyResolver.DependencyResult?> = _depInstallResult.asStateFlow()

    // ===== 拖放安装 =====
    /**
     * 拖放安装状态：null 表示无拖放对话框打开。
     * 拖入 .jar 文件后自动 analyze 并填充 [items]；用户在 UI 多选目标版本后调用
     * [confirmDropInstall] 执行拷贝。
     */
    data class DropInstallState(
        val items: List<com.pmcl.core.mods.ModDropInfo> = emptyList(),
        val scanning: Boolean = false,
        val installing: Boolean = false,
        /** 每个 mod 的已选目标版本 ID 集合（key = jarPath.toString） */
        val selectedVersions: Map<String, Set<String>> = emptyMap(),
        val message: String? = null
    )

    private val _dropInstallState = MutableStateFlow<DropInstallState?>(null)
    val dropInstallState: StateFlow<DropInstallState?> = _dropInstallState.asStateFlow()

    private val dropInstaller: com.pmcl.core.mods.ModDropInstaller? by lazy {
        try {
            com.pmcl.core.mods.ModDropInstaller(core.getConfig(),
                core.modMarket().getModrinthClient()).also {
                it.setPreferences(preferences)
            }
        } catch (e: Throwable) {
            System.err.println("[LauncherViewModel] ModDropInstaller 初始化失败: ${e.message}")
            null
        }
    }

    /**
     * 处理拖入的 jar 文件列表：解析 + SHA1 反查 Modrinth → 填充 [dropInstallState]。
     * UI 在 AWT DropTarget 回调中调用，传入过滤后的 .jar 路径。
     */
    fun dropInstallMod(jarPaths: List<java.nio.file.Path>) {
        if (jarPaths.isEmpty()) return
        val installer = dropInstaller ?: run {
            _status.value = I18n.t("status.drop_install_unavailable")
            return
        }
        // 打开对话框（scanning 状态），让 UI 立即显示进度
        _dropInstallState.value = DropInstallState(scanning = true)
        scope.launch {
            try {
                val infos = withContext(Dispatchers.IO) {
                    installer.analyze(jarPaths)
                }
                // 默认勾选所有兼容版本（每个 mod 至少预选一个，方便用户）
                val defaultSel = HashMap<String, Set<String>>()
                for (info in infos) {
                    val compat = findCompatibleVersions(info)
                    if (compat.isNotEmpty()) {
                        defaultSel[info.getJarPath().toString()] =
                            setOf(compat.first().getId())
                    }
                }
                _dropInstallState.value = DropInstallState(
                    items = infos,
                    selectedVersions = defaultSel
                )
            } catch (e: Throwable) {
                _dropInstallState.value = DropInstallState(
                    message = "解析失败：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * 切换某 mod 某版本的勾选状态。
     */
    fun toggleDropInstallSelection(jarPath: String, versionId: String) {
        val cur = _dropInstallState.value ?: return
        val curSel = cur.selectedVersions[jarPath] ?: emptySet()
        val newSel = if (curSel.contains(versionId)) curSel - versionId
                     else curSel + versionId
        val newMap = cur.selectedVersions.toMutableMap().apply {
            put(jarPath, newSel)
        }
        _dropInstallState.value = cur.copy(selectedVersions = newMap)
    }

    /**
     * 确认安装：把每个 mod 拷贝到用户勾选的目标版本 mods 目录。
     */
    fun confirmDropInstall() {
        val cur = _dropInstallState.value ?: return
        val installer = dropInstaller ?: return
        val allSelections = cur.items.mapNotNull { info ->
            val sel = cur.selectedVersions[info.getJarPath().toString()] ?: emptySet()
            if (sel.isEmpty()) null else info to sel
        }
        if (allSelections.isEmpty()) {
            _dropInstallState.value = cur.copy(message = "请至少选择一个目标版本")
            return
        }
        _dropInstallState.value = cur.copy(installing = true, message = null)
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    val localInfos = _localVersionInfos.value.associateBy { it.getId() }
                    val summary = StringBuilder()
                    var ok = 0
                    var fail = 0
                    for ((info, versionIds) in allSelections) {
                        for (versionId in versionIds) {
                            val lvi = localInfos[versionId]
                            val gameVersion = deriveGameVersion(lvi)
                            try {
                                installer.installTo(info, versionId, gameVersion)
                                ok++
                            } catch (e: Throwable) {
                                fail++
                                summary.append("  ${info.getName()} → $versionId: ${e.message}\n")
                            }
                        }
                    }
                    "成功 $ok 项" + if (fail > 0) "，失败 $fail 项\n$summary" else ""
                }
                _dropInstallState.value = null
                _status.value = I18n.t("status.drop_install_complete", results)
                refreshInstalledMods()
            } catch (e: Throwable) {
                _dropInstallState.value = cur.copy(
                    installing = false,
                    message = "安装失败：${e.message ?: e.javaClass.simpleName}"
                )
            }
        }
    }

    /** 关闭拖放对话框 */
    fun cancelDropInstall() {
        _dropInstallState.value = null
    }

    /**
     * 推导本地版本对应的 Minecraft 游戏版本号。
     * - 模组加载器版本（含 inheritsFrom）：用 inheritsFrom（即原版 MC 版本号）
     * - 原版版本：直接用 id
     */
    private fun deriveGameVersion(lvi: com.pmcl.core.version.VersionManager.LocalVersionInfo?): String {
        if (lvi == null) return ""
        val inherits = lvi.getInheritsFrom()
        return if (!inherits.isNullOrEmpty()) inherits else lvi.getId()
    }

    /**
     * 推导本地版本对应的 mod 加载器（基于 mainClass 关键字匹配）。
     */
    private fun deriveLoader(lvi: com.pmcl.core.version.VersionManager.LocalVersionInfo?): String {
        val mc = lvi?.getMainClass() ?: return ""
        return when {
            mc.contains("fabric", ignoreCase = true) -> "fabric"
            mc.contains("quilt", ignoreCase = true) -> "quilt"
            mc.contains("neoforge", ignoreCase = true) -> "neoforge"
            mc.contains("forge", ignoreCase = true) -> "forge"
            else -> ""
        }
    }

    /**
     * 找出与拖入 mod 兼容的本地版本列表。
     * <p>
     * 匹配规则（AND）：
     * <ol>
     *   <li>modrinthFound=true 时：本地版本的 gameVersion ∈ mod 的 gameVersions</li>
     *   <li>mod 的 loader 非空且非 unknown 时：本地版本 mainClass 含相同 loader 关键字</li>
     * </ol>
     * modrinthFound=false 时（无 Modrinth 数据），跳过 gameVersion 过滤，仅按 loader 匹配；
     * loader 也是 unknown 时返回所有本地版本（让用户手动选）。
     */
    fun findCompatibleVersions(info: com.pmcl.core.mods.ModDropInfo): List<com.pmcl.core.version.VersionManager.LocalVersionInfo> {
        val all = _localVersionInfos.value
        if (all.isEmpty()) return emptyList()
        val modLoader = info.getLoader() ?: ""
        val modGameVersions = info.getGameVersions()
        val hasLoaderFilter = modLoader.isNotEmpty() && modLoader != "unknown"
        val hasGameVersionFilter = info.isModrinthFound() && modGameVersions.isNotEmpty()
        return all.filter { lvi ->
            val gameVersion = deriveGameVersion(lvi)
            val passVersion = !hasGameVersionFilter || modGameVersions.contains(gameVersion)
            val passLoader = !hasLoaderFilter || deriveLoader(lvi).equals(modLoader, ignoreCase = true)
            passVersion && passLoader
        }
    }

    // ===== 游戏时长统计 =====
    private val _playTimeStats = MutableStateFlow<PlayTimeTracker.OverallStat?>(null)
    val playTimeStats: StateFlow<PlayTimeTracker.OverallStat?> = _playTimeStats.asStateFlow()

    private val _dailyStats = MutableStateFlow<List<PlayTimeTracker.DailyStat>>(emptyList())
    val dailyStats: StateFlow<List<PlayTimeTracker.DailyStat>> = _dailyStats.asStateFlow()

    /** 统计图表展示的天数范围 */
    private val _statsDays = MutableStateFlow(7)
    val statsDays: StateFlow<Int> = _statsDays.asStateFlow()

    /** 时段热力图数据 */
    private val _heatmap = MutableStateFlow<PlayTimeTracker.HeatmapStat?>(null)
    val heatmap: StateFlow<PlayTimeTracker.HeatmapStat?> = _heatmap.asStateFlow()

    /** 周几分布数据 */
    private val _weekdayDist = MutableStateFlow<List<PlayTimeTracker.WeekdayStat>>(emptyList())
    val weekdayDist: StateFlow<List<PlayTimeTracker.WeekdayStat>> = _weekdayDist.asStateFlow()

    /** 游玩记录（极值） */
    private val _records = MutableStateFlow<PlayTimeTracker.RecordsStat?>(null)
    val records: StateFlow<PlayTimeTracker.RecordsStat?> = _records.asStateFlow()

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
    // 使用 ConcurrentHashMap 避免多实例并发启动/退出时 put/remove/迭代 导致 ConcurrentModificationException
    // 内层 MutableList 仍用 synchronized(logs) 保护（见日志回调处）
    private val instanceLogs = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    private val instanceLoggers = java.util.concurrent.ConcurrentHashMap<String, GameLogger?>()

    // ===== 预判启动 =====
    // 预热策略：不启动 MC 进程（会弹窗口），而是预构建 LaunchProfile + 预热 JVM 页缓存
    // 用户点击启动时，若版本匹配则复用预存的 profile 跳过 build() 阶段
    @Volatile private var preheatedProfile: com.pmcl.core.launch.LaunchProfile? = null
    @Volatile private var preheatedJavaExe: String = ""
    @Volatile private var preheatedVersionId: String = ""
    private val _predictiveState = MutableStateFlow<PredictiveState>(PredictiveState.Idle)
    val predictiveState: StateFlow<PredictiveState> = _predictiveState.asStateFlow()

    /** 预判启动 UI 状态 */
    sealed class PredictiveState {
        /** 空闲：无预热 */
        object Idle : PredictiveState()
        /** 正在预判 + 预热中 */
        data class Preheating(val versionId: String, val confidence: Double) : PredictiveState()
        /** 资源预热就绪：LaunchProfile 已构建，JVM 页缓存已预热，等待用户点击启动 */
        data class Ready(val versionId: String, val confidence: Double) : PredictiveState()
        /** 预热失败 */
        data class Failed(val reason: String) : PredictiveState()
        /** 已被采用（用户启动了该版本） */
        object Adopted : PredictiveState()
        /** 已被中止（用户启动了其他版本） */
        object Aborted : PredictiveState()
    }

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

    // ===== 启动预设 =====
    private val _launchPresets = MutableStateFlow<List<Preferences.LaunchPreset>>(emptyList())
    val launchPresets: StateFlow<List<Preferences.LaunchPreset>> = _launchPresets.asStateFlow()

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

    // ===== 协议同意门控 =====
    private val _agreementAccepted = MutableStateFlow(preferences.isAgreementAccepted())
    val agreementAccepted: StateFlow<Boolean> = _agreementAccepted.asStateFlow()

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

    /** mods 目录扫描缓存：key=目录路径, value=[mtime, 扫描结果] */
    // M30 修复：用类型安全的 data class 替代 Array<Any>，避免 unchecked cast 与运行时类型错误
    private data class ModScanCacheEntry(val dirMtime: Long, val mods: List<ModMeta>)
    private val modScanCache = java.util.concurrent.ConcurrentHashMap<Path, ModScanCacheEntry>()

    init {
        loadSavedAccount()
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
        // 启动时应用网络偏好（含 Java 全局代理系统属性，让头像/皮肤图片下载能走代理）
        core.applyNetworkPreferences()
        // 注：refreshInstalledMods 和 warmupConnections 已延迟到首次需要时执行，
        // 避免冷启动时阻塞首屏渲染（ModsPage LaunchedEffect 会触发 mod 扫描，
        // warmupConnections 延迟到首次下载时由 DownloadManager 内部触发）

        // ===== 音乐播放器监听器 =====
        musicPlayer.addListener(object : MusicPlayerListener {
            override fun onStateChanged(state: PlaybackState) {
                _musicPlaybackState.value = state
                if (state == PlaybackState.ENDED) {
                    // 自动播放下一曲
                    playNextMusic()
                }
            }
            override fun onProgress(currentMs: Long, durationMs: Long) {
                // 节流：仅当整秒变化时才发射，避免每秒 4-10 次高频更新导致全局重组
                val sec = currentMs / 1000
                if (sec != lastMusicProgressSec) {
                    lastMusicProgressSec = sec
                    _musicCurrentMs.value = currentMs
                }
                if (durationMs > 0) _musicDurationMs.value = durationMs
            }
            override fun onError(message: String) {
                _status.value = I18n.t("music.error_play", message)
            }
            override fun onTrackEnded() {}
        })

        // 加载持久化播放列表
        scope.launch {
            try {
                val file = java.io.File(System.getProperty("user.home"), ".pmcl/music/playlist.json")
                if (file.exists()) {
                    val type = object : TypeToken<List<MusicTrack>>() {}.type
                    val list: List<MusicTrack> = withContext(Dispatchers.IO) {
                        gson.fromJson(file.readText(), type) ?: emptyList()
                    }
                    _musicPlaylist.value = list
                }
            } catch (_: Throwable) {}
        }
    }

    /** 扫描本地已安装版本（详细信息），自动检测 .pmcl/versions + 系统默认 Minecraft 目录，带进度回调 */
    fun refreshLocalVersions() {
        // M34 修复：缓存协程与扫描协程竞态——原代码用 check-then-act（isEmpty() 后赋值），
        // 若扫描协程在 isEmpty() 检查后、赋值前完成，缓存会覆盖新鲜扫描结果。
        // 改用 update {} 原子检查：仅当当前值仍为空时才赋缓存值。
        scope.launch {
            try {
                val cached = withContext(Dispatchers.IO) {
                    DataCache.load("local_versions", object : TypeToken<List<com.pmcl.core.version.VersionManager.LocalVersionInfo>>() {})
                }
                if (cached != null && cached.isNotEmpty()) {
                    _localVersionInfos.update { current -> if (current.isEmpty()) cached else current }
                    _localVersions.update { current -> if (current.isEmpty()) cached.map { it.getId() } else current }
                }
            } catch (e: Throwable) {
                // 缓存读取失败不影响后续正常扫描，静默处理
            }
        }
        // 用 atomic compareAndSet 防重入，避免 _scanning 卡死时按钮永远失效
        if (!_scanning.compareAndSet(expect = false, update = true)) return
        scope.launch {
            _scanProgress.value = null
            _status.value = I18n.t("status.scanning_local_versions")
            val startTime = System.currentTimeMillis()
            try {
                val list = withContext(Dispatchers.IO) {
                    core.versions().scanAllLocalVersions { p ->
                        _scanProgress.value = p
                        _status.value = I18n.t("status.scan_progress", p.getScanned(), p.getTotal(), p.getCurrentDir(), p.getCurrentVersion())
                    }
                }
                _localVersionInfos.value = list
                _localVersions.value = list.map { it.getId() }
                DataCache.save("local_versions", list)
                val pmclDir = config.getVersionsDir()
                val mcDir = com.pmcl.core.version.VersionManager.detectDefaultMinecraftVersionsDir()
                _status.value = if (list.isEmpty()) {
                    if (mcDir != null) I18n.t("status.scan_complete_no_versions_with_mc", pmclDir, mcDir)
                    else I18n.t("status.scan_complete_no_versions_no_mc", pmclDir)
                } else {
                    if (mcDir != null) I18n.t("status.scan_complete_with_mc", list.size, mcDir)
                    else I18n.t("status.scan_complete", list.size)
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
                _status.value = I18n.t("status.scan_local_failed", e.message ?: "")
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
        _status.value = I18n.t("status.pinned", versionId)
    }

    /** 取消固定（删除磁贴）— 同时清理自定义名称 */
    fun unpinVersion(versionId: String) {
        preferences.unpinVersion(versionId)
        _pinnedVersions.value = preferences.getPinnedVersions()
        _pinnedTileLabels.value = HashMap(preferences.getPinnedTileLabelsRaw())
        _status.value = I18n.t("status.tile_deleted", versionId)
    }

    /** 设置磁贴自定义名称（传空串则恢复为版本 ID） */
    fun renamePinnedTile(versionId: String, label: String) {
        val trimmed = label.trim()
        preferences.setPinnedTileLabel(versionId, trimmed)
        _pinnedTileLabels.value = HashMap(preferences.getPinnedTileLabelsRaw())
        _status.value = if (trimmed.isEmpty()) I18n.t("status.tile_name_reset", versionId)
                        else I18n.t("status.tile_renamed", versionId, trimmed)
    }

    /**
     * 一键磁贴启动：预校验 + 选择版本 + 启动。
     * 与 [launch] 不同的是，先做账号/版本存在性校验并通过 status 给出反馈，
     * 避免磁贴点击后没有任何响应。
     */
    fun quickLaunch(versionId: String) {
        // 校验本地版本仍存在（防止版本被删除后磁贴残留）
        if (_localVersionInfos.value.none { it.getId() == versionId }) {
            _status.value = I18n.t("status.tile_invalid", versionId)
            // 自动清理失效磁贴
            if (_pinnedVersions.value.contains(versionId)) {
                unpinVersion(versionId)
            }
            return
        }
        if (_account.value == null) {
            _status.value = I18n.t("status.login_first_to_launch")
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
        _status.value = I18n.t("status.records_purged", versionId)
    }

    /** 从磁盘加载已保存账号集合（多账号） */
    private fun loadSavedAccount() {
        scope.launch {
            try {
                val store = withContext(Dispatchers.IO) {
                    core.auth().loadStore(accountFile)
                }
                _accounts.value = store.getAccounts()
                val sel = store.getSelected().orElse(null)
                _account.value = sel
                if (sel != null) {
                    // 基于账户 UUID 派生好友身份
                    withContext(Dispatchers.IO) {
                        core.friend()?.switchAccount(sel.getUuid(), sel.getUsername())
                    }
                    _status.value = I18n.t("status.account_loaded", sel.getUsername(), sel.getType())
                }
            } catch (e: Throwable) {
                _status.value = I18n.t("status.account_load_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 持久化整个 AccountStore 到磁盘 */
    private fun saveStore(store: AccountStore) {
        _accounts.value = store.getAccounts()
        _account.value = store.getSelected().orElse(null)
        // 同步当前账户到好友身份系统（基于 UUID 派生身份，切换数据集）
        store.getSelected().ifPresent { acc ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    core.friend()?.switchAccount(acc.getUuid(), acc.getUsername())
                }
            }
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.auth().saveStore(store, accountFile)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _status.value = I18n.t("status.account_save_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 账号操作互斥锁：保护读-改-写操作的原子性，避免并发覆盖 */
    private val accountLock = Any()

    /** 向账号集合添加新账号（或更新已有），并设为选中 */
    private fun upsertAccount(acc: Account) = synchronized(accountLock) {
        val current = AccountStore(_accounts.value, _account.value?.getUuid())
        saveStore(current.upsert(acc))
    }

    /** 切换当前选中账号 */
    fun switchAccount(uuid: String) = synchronized(accountLock) {
        val current = AccountStore(_accounts.value, _account.value?.getUuid())
        saveStore(current.select(uuid))
        _status.value = I18n.t("status.account_switched", _account.value?.getUsername() ?: "")
    }

    /** 删除指定账号 */
    fun removeAccount(uuid: String) = synchronized(accountLock) {
        val current = AccountStore(_accounts.value, _account.value?.getUuid())
        saveStore(current.remove(uuid))
        _status.value = I18n.t("status.account_removed")
    }

    /** 退出当前账号（等同于删除当前选中账号） */
    fun logout() {
        val cur = _account.value ?: return
        removeAccount(cur.getUuid())
    }

    fun refreshVersions() {
        scope.launch {
            _loading.value = true
            _status.value = I18n.t("status.fetching_version_manifest")
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
                    _status.value = I18n.t("status.versions_loaded", data.size)
                    scope.launch {
                        try {
                            val list = withContext(Dispatchers.IO) {
                                core.versions().fetchRemoteVersions().join()
                            }
                            _versions.value = list
                            DataCache.save("versions_remote", list)
                            _status.value = I18n.t("status.versions_loaded", list.size)
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
                _status.value = I18n.t("status.versions_loaded", list.size)
                if (_selectedVersion.value == null && list.isNotEmpty()) {
                    _selectedVersion.value = list.first().getId()
                }
                DataCache.save("versions_remote", list)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.fetch_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.username_required")
            return
        }
        val acc = core.auth().offline(username)
        upsertAccount(acc)
        // 持久化用户名，下次启动时恢复，避免每次重置为 Steve
        preferences.setLastOfflineUsername(username)
        _status.value = I18n.t("status.logged_in_offline", username)
    }

    /** 上次离线登录用户名（启动时恢复） */
    fun lastOfflineUsername(): String = preferences.getLastOfflineUsername()

    /** 为当前离线账号设置自定义皮肤 URL（如 Crafatar 头像 URL 或其他皮肤图） */
    fun setOfflineSkin(skinUrl: String, skinModel: String = "classic") {
        val current = _account.value ?: run {
            _status.value = I18n.t("status.login_first")
            return
        }
        if (current.getType() != Account.AccountType.OFFLINE) {
            _status.value = I18n.t("status.offline_skin_microsoft_unsupported")
            return
        }
        val updated = Account(
            current.getUsername(), current.getUuid(), current.getAccessToken(),
            current.getType(), skinUrl, skinModel
        )
        upsertAccount(updated)
        _status.value = if (skinUrl.isEmpty()) I18n.t("status.skin_cleared") else I18n.t("status.skin_set")
    }

    /** 皮肤管理器实例（懒加载） */
    private val skinManager: com.pmcl.core.auth.SkinManager by lazy { com.pmcl.core.auth.SkinManager() }

    /** 上传皮肤到微软账号 */
    fun uploadMicrosoftSkin(skinFile: java.nio.file.Path, model: String) {
        val current = _account.value ?: run {
            _status.value = I18n.t("status.login_first")
            return
        }
        if (current.getType() != Account.AccountType.MICROSOFT) {
            _status.value = I18n.t("status.skin_upload_microsoft_only")
            return
        }
        scope.launch {
            _status.value = I18n.t("status.skin_uploading")
            try {
                withContext(Dispatchers.IO) {
                    skinManager.uploadMicrosoftSkin(current.getAccessToken(), skinFile, model)
                }
                _status.value = I18n.t("status.skin_uploaded")
            } catch (e: Throwable) {
                _status.value = I18n.t("status.skin_upload_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 重置微软账号皮肤 */
    fun resetMicrosoftSkin() {
        val current = _account.value ?: run {
            _status.value = I18n.t("status.login_first")
            return
        }
        if (current.getType() != Account.AccountType.MICROSOFT) {
            _status.value = I18n.t("status.skin_upload_microsoft_only")
            return
        }
        scope.launch {
            _status.value = I18n.t("status.skin_resetting")
            try {
                withContext(Dispatchers.IO) {
                    skinManager.resetMicrosoftSkin(current.getAccessToken())
                }
                _status.value = I18n.t("status.skin_reset")
            } catch (e: Throwable) {
                _status.value = I18n.t("status.skin_reset_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 上传皮肤到皮肤站账号 */
    fun uploadYggdrasilSkin(skinFile: java.nio.file.Path, model: String, password: String) {
        val current = _account.value ?: run {
            _status.value = I18n.t("status.login_first")
            return
        }
        if (current.getType() != Account.AccountType.YGGDRASIL) {
            _status.value = I18n.t("status.skin_upload_yggdrasil_only")
            return
        }
        val apiUrl = current.getAuthServerUrl()
        if (apiUrl.isEmpty()) {
            _status.value = I18n.t("status.skin_upload_no_api_url")
            return
        }
        scope.launch {
            _status.value = I18n.t("status.skin_uploading")
            try {
                val playerId = current.getUuid().replace("-", "")
                withContext(Dispatchers.IO) {
                    skinManager.uploadYggdrasilSkin(
                        apiUrl, current.getUsername(), password, playerId, skinFile, model
                    )
                }
                _status.value = I18n.t("status.skin_uploaded")
            } catch (e: Throwable) {
                _status.value = I18n.t("status.skin_upload_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 重置皮肤站账号皮肤 */
    fun resetYggdrasilSkin(password: String) {
        val current = _account.value ?: run {
            _status.value = I18n.t("status.login_first")
            return
        }
        if (current.getType() != Account.AccountType.YGGDRASIL) {
            _status.value = I18n.t("status.skin_upload_yggdrasil_only")
            return
        }
        val apiUrl = current.getAuthServerUrl()
        if (apiUrl.isEmpty()) {
            _status.value = I18n.t("status.skin_upload_no_api_url")
            return
        }
        scope.launch {
            _status.value = I18n.t("status.skin_resetting")
            try {
                val playerId = current.getUuid().replace("-", "")
                withContext(Dispatchers.IO) {
                    skinManager.resetYggdrasilSkin(apiUrl, current.getUsername(), password, playerId)
                }
                _status.value = I18n.t("status.skin_reset")
            } catch (e: Throwable) {
                _status.value = I18n.t("status.skin_reset_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
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
                _status.value = I18n.t("status.extracting_wallpaper_color")
                // 不再无条件清缓存：5 分钟缓存避免窗口渲染后采样被污染
                val seedColor = withContext(Dispatchers.IO) {
                    com.pmcl.core.theme.WallpaperColorProvider.fetchSeedColor()
                }
                com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] seedColor=$seedColor")
                if (seedColor == -1) {
                    _status.value = I18n.t("status.wallpaper_color_failed_default")
                    return@launch
                }
                val dark = preferences.isUseDarkTheme()
                ts.applySeedColor(seedColor, dark)
                // 持久化种子色：下次启动时立即应用，避免启动期截图污染
                preferences.setMonetSeedColor(seedColor)
                com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] applySeedColor done, primary=${ts.dynamicColorScheme?.primary}")
                _status.value = I18n.t("status.monet_applied", Integer.toHexString(seedColor).padStart(6, '0'))
            } catch (e: Throwable) {
                com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] EXCEPTION: ${e.javaClass.name}: ${e.message}")
                _status.value = I18n.t("status.wallpaper_color_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /**
     * 强制重新采样壁纸主色（用户手动刷新用，如切换壁纸后）。
     * 绕过缓存直接截图采样。
     */
    fun forceRefreshWallpaperColor(targetThemeState: com.pmcl.ui.theme.ThemeState? = null) {
        val ts = targetThemeState ?: themeState ?: return
        com.pmcl.core.theme.WallpaperColorProvider.diagLog("[VM] forceRefreshWallpaperColor called")
        scope.launch {
            try {
                _status.value = I18n.t("status.re_extracting_wallpaper_color")
                val seedColor = withContext(Dispatchers.IO) {
                    com.pmcl.core.theme.WallpaperColorProvider.fetchSeedColorForce()
                }
                if (seedColor == -1) {
                    _status.value = I18n.t("status.wallpaper_color_failed_default")
                    return@launch
                }
                val dark = preferences.isUseDarkTheme()
                ts.applySeedColor(seedColor, dark)
                preferences.setMonetSeedColor(seedColor)
                _status.value = I18n.t("status.monet_refreshed", Integer.toHexString(seedColor).padStart(6, '0'))
            } catch (e: Throwable) {
                _status.value = I18n.t("status.wallpaper_color_failed", e.message ?: I18n.t("common.unknown"))
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
        _status.value = I18n.t("status.custom_accent_applied", Integer.toHexString(rgb).padStart(6, '0'))
    }

    /** 清除自定义强调色，恢复默认配色 */
    fun clearCustomAccentColor(targetThemeState: com.pmcl.ui.theme.ThemeState? = null) {
        val ts = targetThemeState ?: themeState ?: return
        ts.clearCustomAccentColor()
        ts.updateDynamicColorScheme(null)
        preferences.setCustomAccentColor(-1)
        _status.value = I18n.t("status.default_color_restored")
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
            try {
                // 统一使用 device code flow：
                // - 无需用户注册 Azure 应用 / 配置 redirect_uri
                // - LEGACY_CLIENT_ID 即可工作
                // - 返回的 MBI_SSL compact token 能被 Xbox Live 正确认证
                //   （login.live.com 旧端点的授权码流程返回的 token 缺少 audience claim，
                //    v2.0 端点返回的 JWT 需要 Azure 应用显式添加 XboxLive.signin API 权限，
                //    对普通用户门槛过高且易出错，故统一用 device code flow）
                _status.value = I18n.t("status.requesting_device_code")
                val dc = withContext(Dispatchers.IO) { core.auth().requestDeviceCode() }
                _deviceCode.value = dc
                _status.value = I18n.t("status.open_verification_url", dc.getVerificationUri(), dc.getUserCode())
                val account = withContext(Dispatchers.IO) {
                    core.auth().loginMicrosoftAsync(dc) { msg -> _status.value = msg }.join()
                }
                _account.value = account
                upsertAccount(account)
                _status.value = I18n.t("status.logged_in_microsoft", account.getUsername())
                _deviceCode.value = null
            } catch (e: Throwable) {
                _deviceCode.value = null
                val msg = e.message ?: e.toString()
                _status.value = if (msg.contains("SSL", ignoreCase = true) ||
                    msg.contains("TLS", ignoreCase = true) ||
                    msg.contains("handshake", ignoreCase = true) ||
                    msg.contains("SYSCALL", ignoreCase = true) ||
                    msg.contains("reset", ignoreCase = true) ||
                    msg.contains("网络错误", ignoreCase = true)) {
                    I18n.t("status.microsoft_login_failed_network", msg)
                } else {
                    I18n.t("status.microsoft_login_failed", msg)
                }
            } finally {
                _loggingIn.value = false
            }
        }
    }

    /** GitHub 设备码登录 */
    fun startGitHubLogin() {
        scope.launch {
            _loggingIn.value = true
            _status.value = I18n.t("status.requesting_github_device_code")
            try {
                val dc = withContext(Dispatchers.IO) { core.auth().requestGitHubDeviceCode() }
                _deviceCode.value = dc
                _status.value = I18n.t("status.open_verification_url", dc.getVerificationUri(), dc.getUserCode())

                val account = withContext(Dispatchers.IO) {
                    core.auth().loginGitHubAsync(dc) { msg ->
                        _status.value = msg
                    }.join()
                }
                _account.value = account
                upsertAccount(account)
                _status.value = I18n.t("status.logged_in_github", account.getUsername())
                _deviceCode.value = null
            } catch (e: Throwable) {
                _status.value = I18n.t("status.github_login_failed", e.message ?: I18n.t("common.unknown"))
            } finally {
                _loggingIn.value = false
            }
        }
    }

    /** 皮肤站（Yggdrasil / authlib-injector）登录 */
    fun startYggdrasilLogin(apiUrl: String, username: String, password: String) {
        if (apiUrl.isBlank() || username.isBlank() || password.isBlank()) {
            _status.value = I18n.t("status.yggdrasil_fields_required")
            return
        }
        scope.launch {
            _loggingIn.value = true
            _status.value = I18n.t("status.yggdrasil_logging_in")
            try {
                val account = withContext(Dispatchers.IO) {
                    core.auth().yggdrasilLogin(apiUrl, username, password)
                }
                _account.value = account
                upsertAccount(account)
                _status.value = I18n.t("status.logged_in_yggdrasil", account.getUsername())
            } catch (e: Throwable) {
                _status.value = I18n.t("status.yggdrasil_login_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.install_starting", versionId)
            try {
                withContext(Dispatchers.IO) {
                    core.install().install(versionId) { p ->
                        _installProgress.value = p
                        _status.value = I18n.t("status.install_progress", p.getStage(), p.getMessage())
                    }.join()
                }
                refreshLocalVersions()
                _status.value = I18n.t("status.install_complete", versionId)
                // 游戏安装成功后，若用户选择了加载器则继续安装
                if (loader != null && !loaderVersion.isNullOrEmpty()) {
                    _status.value = I18n.t("status.installing_loader", loader, loaderVersion)
                    withContext(Dispatchers.IO) {
                        core.modLoaders().get(loader)
                            .install(versionId, loaderVersion) { p ->
                                _installProgress.value = p
                                _status.value = I18n.t("status.install_progress", p.getStage(), p.getMessage())
                            }.join()
                    }
                    refreshLocalVersions()
                    _status.value = I18n.t("status.loader_install_complete", loader, loaderVersion)
                }
            } catch (e: Throwable) {
                _status.value = I18n.t("status.install_failed", e.message ?: I18n.t("common.unknown"))
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
                    _status.value = I18n.t("status.loader_versions_loaded_cache", data.size, loader)
                    return@launch
                }
            }
            // 缓存不存在/已过期：网络请求
            _status.value = I18n.t("status.fetching_loader_versions", loader)
            try {
                val list = withContext(Dispatchers.IO) {
                    core.modLoaders().get(loader).listVersions(gameVersion).join()
                }
                _modLoaderVersions.value = list
                _status.value = I18n.t("status.loader_versions_loaded", list.size, loader)
                DataCache.save(cacheKey, list)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.fetch_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun installModLoader(loader: ModLoader, gameVersion: String, loaderVersion: String) {
        scope.launch {
            _installing.value = true
            _status.value = I18n.t("status.installing_loader", loader, loaderVersion)
            try {
                withContext(Dispatchers.IO) {
                    core.modLoaders().get(loader)
                        .install(gameVersion, loaderVersion) { p ->
                            _installProgress.value = p
                            _status.value = I18n.t("status.install_progress", p.getStage(), p.getMessage())
                        }.join()
                }
                refreshLocalVersions()
                _status.value = I18n.t("status.loader_install_complete", loader, loaderVersion)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.install_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.searching", query)
            try {
                val list = withContext(Dispatchers.IO) {
                    if (category != null && category.isNotEmpty()) {
                        core.modMarket().search(query, gameVersion, loader, category, 30).join()
                    } else {
                        core.modMarket().search(query, gameVersion, loader, 30).join()
                    }
                }
                _marketResults.value = list
                _status.value = I18n.t("status.mods_found", list.size, if (core.modMarket().hasCurseForge()) I18n.t("common.enabled") else I18n.t("common.disabled"))
            } catch (e: Throwable) {
                _status.value = I18n.t("status.search_failed", e.message ?: I18n.t("common.unknown"))
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
                            _status.value = I18n.t("status.popular_mods_loaded", list.size)
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
            _status.value = I18n.t("status.loading_popular_mods")
            try {
                val list = withContext(Dispatchers.IO) {
                    core.modMarket().popular(gameVersion, loader, 24).join()
                }
                _popularMods.value = list
                _status.value = I18n.t("status.popular_mods_loaded", list.size)
                DataCache.save("popular_mods", list)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.popular_mods_load_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.loading_category", category)
            try {
                val list = withContext(Dispatchers.IO) {
                    core.modMarket().searchByCategory(category, gameVersion, loader, 24).join()
                }
                _categoryResults.value = list
                _status.value = I18n.t("status.category_mods_loaded", list.size)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.category_load_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.fetching_project_files", project.getName())
            try {
                val files = withContext(Dispatchers.IO) {
                    core.modMarket().listFiles(project).join()
                }
                _currentModFiles.value = files
                _status.value = I18n.t("status.project_files_loaded", project.getName(), files.size)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.fetch_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun installMod(file: ModFile, gameVersion: String) {
        scope.launch {
            _status.value = I18n.t("status.downloading_mod", file.getFileName())
            try {
                withContext(Dispatchers.IO) {
                    core.modMarket().installMod(file, gameVersion,
                        _selectedVersion.value, preferences) { msg ->
                        _status.value = msg
                    }.join()
                }
                _status.value = I18n.t("status.mod_installed", file.getFileName())
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.mod_install_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.installing_mod_with_deps", file.getFileName())
            try {
                val result = core.modDependencyResolver().installWithDependencies(
                    file, gameVersion, _selectedVersion.value
                ) { msg -> _status.value = msg }.join()
                _depInstallResult.value = result
                _status.value = if (result.hasInstalled()) {
                    I18n.t("status.mod_install_complete_with_deps", file.getFileName(), result.summary())
                } else {
                    I18n.t("status.mod_install_complete_no_deps", file.getFileName())
                }
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.install_failed", e.message ?: I18n.t("common.unknown"))
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
        val tracker = core.playTimeTracker()
        _playTimeStats.value = tracker.getOverallStats(days)
        _dailyStats.value = tracker.getDailyStatsWithZeros(days)
        _heatmap.value = tracker.getHeatmap(days)
        _weekdayDist.value = tracker.getWeekdayDistribution(days)
        _records.value = tracker.getRecords()
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
            try {
                val cached = withContext(Dispatchers.IO) {
                    DataCache.load("installed_mods", object : TypeToken<List<ModMeta>>() {})
                }
                if (cached != null && cached.isNotEmpty() && _installedMods.value.isEmpty()) {
                    _installedMods.value = cached
                }
            } catch (e: Throwable) {
                // 缓存读取失败不影响后续扫描，静默处理
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
                            // 基于目录 mtime 的缓存：未变化则复用上次扫描结果
                            val dirMtime = try { java.nio.file.Files.getLastModifiedTime(modsDir).toMillis() } catch (_: Throwable) { 0L }
                            val cached = modScanCache[modsDir]
                            val part = if (cached != null && cached.dirMtime == dirMtime && dirMtime > 0L) {
                                cached.mods
                            } else {
                                val scanned = ModScanner.scanDirectory(modsDir)
                                modScanCache[modsDir] = ModScanCacheEntry(dirMtime, scanned)
                                scanned
                            }
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
                    // 应用用户自定义标签
                    try { core.modTagStore().applyTags(allMods) } catch (_: Throwable) {}
                    allMods
                }
                _installedMods.value = mods
                DataCache.save("installed_mods", mods)
                _status.value = I18n.t("status.mods_scanned", mods.size, modsDirsCount(mods))
            } catch (e: Throwable) {
                _status.value = I18n.t("status.scan_mods_failed", e.message ?: I18n.t("common.unknown"))
                System.err.println("[refreshInstalledMods] 顶层异常: ${e.javaClass.name}: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /** 所有已使用的模组标签（可观察） */
    private val _allModTags = MutableStateFlow<List<String>>(emptyList())
    val allModTags: StateFlow<List<String>> = _allModTags.asStateFlow()

    /** 刷新标签列表（从 ModTagStore 加载） */
    fun refreshModTags() {
        _allModTags.value = core.modTagStore().getAllTags()
    }

    /** 设置模组标签（jarFile → tags），并刷新 UI */
    fun setModTags(jarFile: String, tags: List<String>) {
        scope.launch {
            withContext(Dispatchers.IO) {
                core.modTagStore().setTags(jarFile, tags)
            }
            // 更新内存中的 ModMeta
            _installedMods.value = _installedMods.value.map { mod ->
                if (mod.getJarFile() == jarFile) {
                    mod.setTags(tags)
                    mod
                } else {
                    mod
                }
            }
            // 刷新标签列表
            _allModTags.value = core.modTagStore().getAllTags()
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

    /**
     * 根据内容目录路径推断来源标签（光影包/资源包共用）。
     * - PMCL 全局 → "全局"
     * - versions/<id>/ 下 → <id>
     * - instances/<id>/ 下 → <id>
     * - 系统 .minecraft → "系统"
     */
    private fun contentSourceLabelFor(dir: java.nio.file.Path, subDirName: String): String {
        if (dir == config.getWorkDir().resolve(subDirName)) return "全局"
        val parent = dir.parent
        if (parent != null) {
            val grandName = parent.parent?.fileName?.toString()?.lowercase()
            if (grandName == "versions" || grandName == "instances") {
                return parent.fileName?.toString() ?: "版本"
            }
        }
        return "系统"
    }

    /** 删除指定 mod（按 jar 文件名） */
    fun deleteMod(jarFile: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.modManager().deleteMod(jarFile)
                }
                _status.value = I18n.t("status.mod_deleted", jarFile)
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.mod_disabled", jarFile)
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.disable_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.mod_enabled", jarFile)
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.enable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 导入模组文件到 mods 目录 */
    fun importMod(filePath: String) {
        scope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) {
                    val src = java.nio.file.Paths.get(filePath)
                    val targetDir = config.getWorkDir().resolve("mods")
                    java.nio.file.Files.createDirectories(targetDir)
                    val target = targetDir.resolve(src.fileName)
                    java.nio.file.Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    src.fileName.toString()
                }
                _status.value = I18n.t("status.mod_imported", fileName)
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.import_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量启用模组 */
    fun batchEnableMods(jarFiles: List<String>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (jarFile in jarFiles) {
                        try {
                            core.modManager().enableMod(jarFile)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_enabled_mods", jarFiles.size)
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_enable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量禁用模组 */
    fun batchDisableMods(jarFiles: List<String>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (jarFile in jarFiles) {
                        try {
                            core.modManager().disableMod(jarFile)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_disabled_mods", jarFiles.size)
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_disable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量删除模组 */
    fun batchDeleteMods(jarFiles: List<String>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (jarFile in jarFiles) {
                        try {
                            core.modManager().deleteMod(jarFile)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_deleted_mods", jarFiles.size)
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_delete_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.open_dir_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.open_dir_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.refresh_modpacks_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 导入整合包文件（.mrpack 或 .zip） */
    fun importModpack(filePath: String) {
        if (_modpackBusy.value) {
            _status.value = I18n.t("status.modpack_busy")
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
                _status.value = I18n.t("status.modpack_import_complete")
                refreshModpacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.modpack_import_failed", e.message ?: I18n.t("common.unknown"))
            } finally {
                _modpackBusy.value = false
                _modpackProgress.value = null
            }
        }
    }

    /** 导出当前选中版本为 Modrinth .mrpack 整合包 */
    fun exportModpack(targetPath: String) {
        exportModpack(targetPath, "modrinth")
    }

    /**
     * 导出当前选中版本为整合包。
     * @param format "modrinth" 导出 .mrpack；"curseforge" 导出 CF manifest.json 格式 .zip
     */
    fun exportModpack(targetPath: String, format: String) {
        val versionId = _selectedVersion.value ?: run {
            _status.value = I18n.t("status.version_select_first")
            return
        }
        if (_modpackBusy.value) {
            _status.value = I18n.t("status.modpack_busy")
            return
        }
        scope.launch {
            _modpackBusy.value = true
            _modpackProgress.value = InstallProgress(
                InstallProgress.Stage.DOWNLOAD_VERSION_JSON, 0, 0, "开始导出整合包...")
            try {
                withContext(Dispatchers.IO) {
                    val path = java.nio.file.Paths.get(targetPath)
                    val future = if (format == "curseforge") {
                        core.modpacks().exportCurseForge(versionId, path) { p ->
                            _modpackProgress.value = p
                        }
                    } else {
                        core.modpacks().exportModpack(versionId, path) { p ->
                            _modpackProgress.value = p
                        }
                    }
                    future.join()
                }
                _status.value = I18n.t("status.modpack_exported", targetPath)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.modpack_export_failed", e.message ?: I18n.t("common.unknown"))
            } finally {
                _modpackBusy.value = false
                _modpackProgress.value = null
            }
        }
    }

    /** 检查已安装整合包的 mod 更新 */
    fun checkModpackUpdates(instanceName: String) {
        if (_modpackUpdateChecking.value) return
        scope.launch {
            _modpackUpdateChecking.value = true
            _modpackUpdateResult.value = null
            _status.value = I18n.t("status.checking_modpack_updates", instanceName)
            try {
                val result = withContext(Dispatchers.IO) {
                    core.modpacks().checkForUpdates(instanceName).join()
                }
                _modpackUpdateResult.value = result
                if (result.isSuccess()) {
                    if (result.hasUpdates()) {
                        _status.value = I18n.t("status.modpack_has_updates", instanceName, result.updates.size)
                    } else {
                        _status.value = I18n.t("status.modpack_up_to_date", instanceName, result.totalChecked)
                    }
                } else {
                    _status.value = result.error ?: I18n.t("status.check_updates_failed_default")
                }
            } catch (e: Throwable) {
                _status.value = I18n.t("status.check_updates_failed", e.message ?: I18n.t("common.unknown"))
            } finally {
                _modpackUpdateChecking.value = false
            }
        }
    }

    /** 清除更新检查结果 */
    fun clearModpackUpdateResult() {
        _modpackUpdateResult.value = null
    }

    // ===== NBT 编辑器方法 =====

    /** 打开 NBT 文件（自动检测 gzip 压缩，如 level.dat） */
    fun openNbtFile(path: String) {
        scope.launch {
            _nbtError.value = null
            try {
                val tag = withContext(Dispatchers.IO) {
                    NbtReader.read(java.nio.file.Paths.get(path))
                }
                _nbtRoot.value = tag
                _nbtFilePath.value = path
                _nbtDirty.value = false
                _nbtRevision.value++
                _status.value = I18n.t("status.nbt_loaded", path)
            } catch (e: Throwable) {
                _nbtError.value = "读取 NBT 失败: ${e.message}"
                _status.value = I18n.t("status.nbt_read_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 保存 NBT 到当前文件（保存前自动创建 .bak 备份） */
    fun saveNbtFile() {
        val root = _nbtRoot.value ?: return
        val path = _nbtFilePath.value ?: return
        scope.launch {
            _nbtError.value = null
            try {
                withContext(Dispatchers.IO) {
                    val file = java.nio.file.Paths.get(path)
                    // 保存前备份
                    if (java.nio.file.Files.exists(file)) {
                        val bak = file.resolveSibling(file.fileName.toString() + ".bak")
                        java.nio.file.Files.copy(file, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                    NbtWriter.write(root, file)
                }
                _nbtDirty.value = false
                _status.value = I18n.t("status.nbt_saved", path)
            } catch (e: Throwable) {
                _nbtError.value = "保存 NBT 失败: ${e.message}"
                _status.value = I18n.t("status.nbt_save_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 另存为指定路径 */
    fun saveNbtFileAs(targetPath: String) {
        val root = _nbtRoot.value ?: return
        scope.launch {
            _nbtError.value = null
            try {
                withContext(Dispatchers.IO) {
                    NbtWriter.write(root, java.nio.file.Paths.get(targetPath))
                }
                _nbtFilePath.value = targetPath
                _nbtDirty.value = false
                _status.value = I18n.t("status.nbt_saved", targetPath)
            } catch (e: Throwable) {
                _nbtError.value = "保存 NBT 失败: ${e.message}"
                _status.value = I18n.t("status.nbt_save_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 标记 NBT 树已修改，触发 UI 重组 */
    fun updateNbtValue() {
        _nbtDirty.value = true
        _nbtRevision.value++
    }

    /** 关闭当前 NBT 文件 */
    fun closeNbtFile() {
        _nbtRoot.value = null
        _nbtFilePath.value = null
        _nbtDirty.value = false
        _nbtError.value = null
        _nbtRevision.value++
    }

    // ===== 树结构编辑 =====

    /** 向 Compound 添加子标签 */
    fun addNbtChild(parent: NbtTag.CompoundTag, name: String, type: Int) {
        if (parent.contains(name)) return
        parent.put(name, NbtTag.createDefault(type))
        updateNbtValue()
    }

    /** 从 Compound 删除子标签 */
    fun removeNbtChild(parent: NbtTag.CompoundTag, name: String) {
        parent.remove(name)
        updateNbtValue()
    }

    /** 重命名 Compound 子标签 */
    fun renameNbtChild(parent: NbtTag.CompoundTag, oldName: String, newName: String) {
        if (oldName == newName || parent.contains(newName)) return
        val tag = parent.get(oldName) ?: return
        parent.remove(oldName)
        parent.put(newName, tag)
        updateNbtValue()
    }

    /** 向 List 添加元素（使用 listType 创建默认值） */
    fun addNbtListItem(list: NbtTag.ListTag) {
        val type = if (list.getListType() == NbtTag.TYPE_END) NbtTag.TYPE_COMPOUND else list.getListType()
        list.add(NbtTag.createDefault(type))
        updateNbtValue()
    }

    /** 删除 List 元素 */
    fun removeNbtListItem(list: NbtTag.ListTag, index: Int) {
        list.remove(index)
        updateNbtValue()
    }

    /** 移动 List 元素（up=true 上移，up=false 下移） */
    fun moveNbtListItem(list: NbtTag.ListTag, index: Int, up: Boolean) {
        val target = if (up) index - 1 else index + 1
        if (target < 0 || target >= list.size()) return
        val item = list.getItems()[index]
        list.remove(index)
        list.add(target, item)
        updateNbtValue()
    }

    // ===== 数组编辑 =====

    /** 设置数组元素值 */
    fun setNbtArrayElement(array: NbtTag, index: Int, value: String): Boolean {
        try {
            when (array) {
                is NbtTag.ByteArrayTag -> {
                    val arr = array.getValue()
                    if (index < 0 || index >= arr.size) return false
                    arr[index] = value.toByte()
                }
                is NbtTag.IntArrayTag -> {
                    val arr = array.getValue()
                    if (index < 0 || index >= arr.size) return false
                    arr[index] = value.toInt()
                }
                is NbtTag.LongArrayTag -> {
                    val arr = array.getValue()
                    if (index < 0 || index >= arr.size) return false
                    arr[index] = value.toLong()
                }
                else -> return false
            }
            updateNbtValue()
            return true
        } catch (_: NumberFormatException) {
            return false
        }
    }

    /** 添加数组元素 */
    fun addNbtArrayElement(array: NbtTag, value: String): Boolean {
        try {
            when (array) {
                is NbtTag.ByteArrayTag -> {
                    val old = array.getValue()
                    val newArr = java.util.Arrays.copyOf(old, old.size + 1)
                    newArr[old.size] = value.toByte()
                    array.setValue(newArr)
                }
                is NbtTag.IntArrayTag -> {
                    val old = array.getValue()
                    val newArr = java.util.Arrays.copyOf(old, old.size + 1)
                    newArr[old.size] = value.toInt()
                    array.setValue(newArr)
                }
                is NbtTag.LongArrayTag -> {
                    val old = array.getValue()
                    val newArr = java.util.Arrays.copyOf(old, old.size + 1)
                    newArr[old.size] = value.toLong()
                    array.setValue(newArr)
                }
                else -> return false
            }
            updateNbtValue()
            return true
        } catch (_: NumberFormatException) {
            return false
        }
    }

    /** 删除数组元素 */
    fun removeNbtArrayElement(array: NbtTag, index: Int) {
        when (array) {
            is NbtTag.ByteArrayTag -> {
                val old = array.getValue()
                if (index < 0 || index >= old.size) return
                val newArr = java.util.Arrays.copyOf(old, old.size - 1)
                var j = 0
                for (i in old.indices) { if (i != index) newArr[j++] = old[i] }
                array.setValue(newArr)
            }
            is NbtTag.IntArrayTag -> {
                val old = array.getValue()
                if (index < 0 || index >= old.size) return
                val newArr = java.util.Arrays.copyOf(old, old.size - 1)
                var j = 0
                for (i in old.indices) { if (i != index) newArr[j++] = old[i] }
                array.setValue(newArr)
            }
            is NbtTag.LongArrayTag -> {
                val old = array.getValue()
                if (index < 0 || index >= old.size) return
                val newArr = java.util.Arrays.copyOf(old, old.size - 1)
                var j = 0
                for (i in old.indices) { if (i != index) newArr[j++] = old[i] }
                array.setValue(newArr)
            }
            else -> return
        }
        updateNbtValue()
    }

    /** 导出 NBT 为 SNBT 字符串 */
    fun exportNbtSnbt(): String {
        return _nbtRoot.value?.toSnbt() ?: ""
    }

    /** 删除整合包实例 */
    fun deleteModpack(name: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.modpacks().deleteModpack(name)
                }
                _status.value = I18n.t("status.modpack_deleted", name)
                refreshModpacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.modpack_delete_failed", e.message ?: I18n.t("common.unknown"))
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
        _status.value = I18n.t("status.queued_minecraft_version", versionId)
        refreshQueue()
    }

    /** 提交模组加载器安装到队列 */
    fun enqueueModLoaderInstall(loaderName: String, gameVersion: String, loaderVersion: String) {
        core.downloadQueue().submitModLoaderInstall(loaderName, gameVersion, loaderVersion)
        _status.value = I18n.t("status.queued_loader", loaderName, loaderVersion)
        refreshQueue()
    }

    /** 提交模组下载到队列 */
    fun enqueueModDownload(modFile: ModFile, gameVersion: String, versionId: String? = null) {
        val vid = versionId ?: _selectedVersion.value
        core.downloadQueue().submitModDownload(modFile, gameVersion, vid)
        _status.value = I18n.t("status.queued_mod", modFile.fileName)
        // 若该模组有 API 声明的依赖，提醒用户可使用"带依赖下载"
        val deps = modFile.getDependencies()
        if (deps != null && deps.isNotEmpty()) {
            _status.value = I18n.t("status.mod_has_deps", modFile.fileName, deps.size)
        }
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
                _status.value = I18n.t("status.config_files_load_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.config_file_read_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.config_file_saved", path)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.config_file_save_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.config_file_deleted", relativePath)
                refreshConfigFiles(_configCurrentDir.value)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.config_file_created", fileName)
                refreshConfigFiles(dir)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.config_file_create_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.open_dir_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.no_installed_mods")
            return
        }
        // 从选中版本推断 gameVersion
        val versionId = _selectedVersion.value
        val gameVersion = inferGameVersion(versionId)
        _updateGameVersion.value = gameVersion

        if (_checkingUpdates.value) return // 防止重复检测
        _checkingUpdates.value = true
        _updateCheckProgress.value = 0 to mods.size
        _status.value = I18n.t("status.checking_mod_updates")

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
                    I18n.t("status.mod_updates_found", updateCount)
                } else {
                    I18n.t("status.mod_updates_all_latest")
                }
            } catch (e: Throwable) {
                _status.value = I18n.t("status.check_mod_updates_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.mod_update_complete", info.displayName())
                // 刷新已安装模组列表
                refreshInstalledMods()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.mod_update_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.no_mods_to_update")
            return
        }
        if (_updatingMod.value) return
        _updatingMod.value = true
        val versionId = _selectedVersion.value
        val gameVersion = _updateGameVersion.value
        _status.value = I18n.t("status.batch_updating_mods", updates.size)

        scope.launch {
            try {
                core.modUpdateChecker().updateAll(updates, gameVersion, versionId) { progress ->
                    _updateCheckProgress.value = progress[0] to progress[1]
                    _status.value = I18n.t("status.batch_updating_progress", progress[0], progress[1])
                }.join()
                _status.value = I18n.t("status.batch_update_complete")
                refreshInstalledMods()
                // 重新检测一次
                checkModUpdates()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_update_failed", e.message ?: I18n.t("common.unknown"))
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

    // ===== 预判启动：进入启动页时预测最可能的版本并后台预热资源 =====

    /**
     * 触发预判启动：用 LaunchPredictor 预测最可能的版本，若置信度达标则后台预热资源。
     *
     * 预热内容（不启动 MC 进程，避免窗口提前弹出）：
     * 1. 构建完整 LaunchProfile（含 verifyLibraries 的全量文件校验，这是启动时最耗时的 IO）
     * 2. 解析 Java 可执行文件路径
     * 3. 启动 `java -version` 子进程预热 JVM 页缓存（OS 会缓存 java 可执行文件和依赖库）
     *
     * 调用时机：用户切换到 LaunchPage 时（见 LaunchPage.LaunchedEffect）。
     *
     * 安全保证：
     * - 未开启 predictiveLaunch 偏好时不执行
     * - 已有运行中实例时不执行
     * - 已有预热 profile 时不重复预热
     * - 预热失败不影响正常启动（用户点启动时走原 launch 流程）
     */
    fun predictAndPreheat() {
        if (!preferences.isPredictiveLaunch()) return
        if (_gameRunning.value || _runningInstances.value.isNotEmpty()) return
        if (preheatedProfile != null) return  // 已有预热
        if (_account.value == null) return     // 无账号无法构建 profile

        scope.launch {
            try {
                // 1. 收集本地已安装版本 ID 作为候选集
                val installedIds = _localVersionInfos.value.mapNotNull { it.getId() }.toSet()
                if (installedIds.isEmpty()) return@launch

                // 2. 预测
                val predictor = com.pmcl.core.launch.LaunchPredictor(
                    core.playTimeTracker(), preferences
                )
                val result = withContext(Dispatchers.IO) { predictor.predict(installedIds) }
                if (!result.shouldPreheat()) {
                    _predictiveState.value = PredictiveState.Idle
                    return@launch
                }
                val versionId = result.topVersionId ?: return@launch
                val account = _account.value ?: return@launch

                _predictiveState.value = PredictiveState.Preheating(versionId, result.confidence)
                // 预热全程静默：不更新 _status，避免在 UI 暴露预加载信息

                // 3. 构造 LaunchProfile（这是启动时最耗时的阶段，含 verifyLibraries 全量文件校验）
                val requiredJavaVer = withContext(Dispatchers.IO) {
                    try { core.profileBuilder().getRequiredJavaVersion(versionId) } catch (e: Throwable) { 0 }
                }
                val javaExe = resolveJavaExe(versionId, requiredJavaVer)
                if (javaExe.isEmpty()) {
                    _predictiveState.value = PredictiveState.Failed("无法解析 Java 路径")
                    return@launch
                }
                val launchProfile = try {
                    withContext(Dispatchers.IO) {
                        core.profileBuilder().build(versionId, account)
                    }
                } catch (e: Throwable) {
                    _predictiveState.value = PredictiveState.Failed("构建启动配置失败：${e.message}")
                    return@launch
                }

                // 4. JVM 页缓存预热（启动 `java -version` 子进程，立即退出但触发 OS 缓存）
                withContext(Dispatchers.IO) {
                    core.launch().prewarmJvm(javaExe)
                }

                // 5. 缓存预热结果
                preheatedProfile = launchProfile
                preheatedJavaExe = javaExe
                preheatedVersionId = versionId
                _predictiveState.value = PredictiveState.Ready(versionId, result.confidence)

            } catch (e: Throwable) {
                _predictiveState.value = PredictiveState.Failed(e.message ?: "未知错误")
            }
        }
    }

    /**
     * Java 路径解析辅助（与 launch() 内的逻辑保持一致）。
     */
    private suspend fun resolveJavaExe(versionId: String, requiredJavaVer: Int): String {
        return withContext(Dispatchers.IO) {
            val versionPath = preferences.getVersionJavaPath(versionId)
            if (versionPath.isNotEmpty()) return@withContext versionPath
            val globalPath = preferences.getJavaPath()
            if (globalPath.isNotEmpty()) return@withContext globalPath
            try {
                JavaRuntimeFinder.findJavaExecutable(
                    config.getRuntimesDir(), requiredJavaVer
                ) ?: ""
            } catch (e: Throwable) { "" }
        }
    }

    /**
     * 取消预判启动：清空预存的 profile（用户离开启动页时调用）。
     * 不需要杀进程，因为预热阶段没有启动 MC 进程。
     */
    fun cancelPreheat() {
        if (preheatedProfile != null) {
            preheatedProfile = null
            preheatedJavaExe = ""
            preheatedVersionId = ""
            _predictiveState.value = PredictiveState.Aborted
            // 静默取消：不更新 _status，避免在 UI 暴露预加载信息
        }
        // 不立刻重置 Idle，让 UI 有机会显示 Aborted；下次 predictAndPreheat 会重置
    }

    /**
     * 尝试采用预热的 LaunchProfile：若用户启动的版本与预热版本一致，
     * 返回预存的 (profile, javaExe) 跳过 build() 阶段；否则清空预热并返回 null。
     *
     * @param versionId 用户实际启动的版本 ID
     * @return 采用成功时返回 Pair(profile, javaExe)，不匹配或无预热时返回 null
     */
    private fun tryAdoptPreheated(versionId: String): Pair<com.pmcl.core.launch.LaunchProfile, String>? {
        val profile = preheatedProfile ?: return null
        val preheatedVer = preheatedVersionId
        if (preheatedVer != versionId) {
            // 版本不匹配：清空预热，返回 null 让 launch() 走正常 build 路径
            preheatedProfile = null
            preheatedJavaExe = ""
            preheatedVersionId = ""
            _predictiveState.value = PredictiveState.Aborted
            // 静默：不更新 _status，避免在 UI 暴露预加载信息
            return null
        }
        // 版本匹配：复用预热的 profile
        val javaExe = preheatedJavaExe
        preheatedProfile = null
        preheatedJavaExe = ""
        preheatedVersionId = ""
        _predictiveState.value = PredictiveState.Adopted
        // 静默：launch() 后续会设置 _status 为"启动中…"，不暴露预热信息
        return Pair(profile, javaExe)
    }

    fun launch() {
        val versionId = _selectedVersion.value ?: run {
            _status.value = I18n.t("status.version_select_first")
            return
        }
        val account = _account.value ?: run {
            _status.value = I18n.t("status.login_first")
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
            _status.value = I18n.t("status.building_launch_profile")
            var instanceId: String? = null
            var timeTracked = false
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
                    _status.value = I18n.t("status.launch_failed_no_java")
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
                // 龙芯平台兼容性检测：native 库可能不完整，提示用户
                if (JavaRuntimeFinder.isLoongson()) {
                    val isLoongArch = JavaRuntimeFinder.isLoongArch64()
                    val archName = if (isLoongArch) "LoongArch64" else "MIPS64el"
                    val isOldVersion = requiredJavaVer in 1..10

                    if (isOldVersion || !isLoongArch) {
                        // 旧版本（LWJGL 2.x）或 MIPS64el：无原生 native，需 x86_64 + 二进制翻译
                        _status.value = I18n.t("status.compat_hint_loongson", archName)
                        val options = mutableListOf<CompatOption>()

                        // 选项1：仍尝试启动（可能因 native 库缺失而崩溃）
                        options.add(CompatOption(
                            title = "仍尝试启动",
                            description = "龙芯 $archName 上旧版本 Minecraft 的 LWJGL 原生库可能缺失。\n" +
                                    if (isLoongArch)
                                        "若已安装 LATX 二进制翻译 + x86_64 Java，native 库可通过翻译层运行。\n游戏可能崩溃，请知悉风险。"
                                    else
                                        "MIPS64el 龙芯无 x86 二进制翻译能力，旧版本大概率无法运行。\n游戏可能崩溃，请知悉风险。",
                            action = { launchWithSpecificJava(versionId, javaExe, javaMajorVer, javaArch) }
                        ))

                        // 选项2：安装龙芯版 JDK（打开龙芯开源社区）
                        options.add(CompatOption(
                            title = "前往龙芯开源社区下载 JDK",
                            description = "打开浏览器访问龙芯开源社区，下载 LoongArch64 版 JDK\n" +
                                    "安装后 PMCL 会自动检测并使用",
                            action = {
                                try {
                                    val url = "https://www.loongnix.cn/zh/api/java/"
                                    if (System.getProperty("os.name", "").lowercase().contains("linux")) {
                                        Runtime.getRuntime().exec(arrayOf("xdg-open", url))
                                    } else {
                                        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                                    }
                                } catch (e: Throwable) {
                                    _status.value = I18n.t("status.cannot_open_browser", e.message ?: I18n.t("common.unknown"))
                                }
                            }
                        ))

                        _compatTitle.value = "龙芯 $archName 兼容性提示"
                        _compatOptions.value = options
                        return@launch
                    }
                }

                // RISC-V 平台兼容性检测：native 库可能不完整，提示用户
                if (JavaRuntimeFinder.isRiscV()) {
                    val isOldVersion = requiredJavaVer in 1..10

                    if (isOldVersion) {
                        // 旧版本（LWJGL 2.x）：无原生 native，需 x86_64 + QEMU 用户态翻译
                        _status.value = I18n.t("status.compat_hint_riscv")
                        val options = mutableListOf<CompatOption>()

                        // 选项1：仍尝试启动（可能因 native 库缺失而崩溃）
                        options.add(CompatOption(
                            title = "仍尝试启动",
                            description = "RISC-V 64 上旧版本 Minecraft 的 LWJGL 2.x 原生库无 RISC-V 版本。\n" +
                                    "若已安装 QEMU 用户态翻译 + x86_64 Java，native 库可通过翻译层运行。\n" +
                                    "游戏可能崩溃或性能较差，请知悉风险。",
                            action = { launchWithSpecificJava(versionId, javaExe, javaMajorVer, javaArch) }
                        ))

                        // 选项2：安装 RISC-V 版 JDK（打开 Adoptium）
                        options.add(CompatOption(
                            title = "前往 Adoptium 下载 RISC-V JDK",
                            description = "打开浏览器访问 Adoptium，下载 RISC-V 64 版 JDK\n" +
                                    "安装后 PMCL 会自动检测并使用",
                            action = {
                                try {
                                    val url = "https://adoptium.net/temurin/releases/?version=17&arch=riscv64"
                                    if (System.getProperty("os.name", "").lowercase().contains("linux")) {
                                        Runtime.getRuntime().exec(arrayOf("xdg-open", url))
                                    } else {
                                        java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                                    }
                                } catch (e: Throwable) {
                                    _status.value = I18n.t("status.cannot_open_browser", e.message ?: I18n.t("common.unknown"))
                                }
                            }
                        ))

                        _compatTitle.value = "RISC-V 64 兼容性提示"
                        _compatOptions.value = options
                        return@launch
                    }
                }

                // Apple Silicon Mac 上旧版本 + arm64 Java 检测：native 库只有 x86_64，会加载失败
                val isArchMismatch = requiredJavaVer in 1..10
                        && (javaArch.contains("aarch64") || javaArch.contains("arm64"))
                        && System.getProperty("os.name", "").lowercase().contains("mac")
                        && (System.getProperty("os.arch", "").lowercase().contains("aarch64")
                            || System.getProperty("os.arch", "").lowercase().contains("arm64"))
                if (isArchMismatch) {
                    _status.value = I18n.t("status.compat_issue_arch_mismatch")
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
                                _status.value = I18n.t("status.cannot_open_browser", e.message ?: I18n.t("common.unknown"))
                            }
                        }
                    ))

                    _compatTitle.value = "兼容性问题：旧版本需要 x86_64 Java"
                    _compatOptions.value = options
                    return@launch
                }
                // 尝试采用预判启动的预热 profile：版本一致则复用预热的 profile，跳过 build 阶段
                // （build 内部含 verifyLibraries 全量文件校验，是最耗时的 IO 步骤）
                // 实例启动（_pendingInstanceDir != null）不采用预热，因为实例有独立的 gameDir/libraries
                val adopted = if (_pendingInstanceDir == null) tryAdoptPreheated(versionId) else null
                val profile = adopted?.first ?: withContext(Dispatchers.IO) {
                    val instDir = _pendingInstanceDir
                    val instInfo = _pendingInstanceInfo
                    if (instDir != null && instInfo != null) {
                        // 实例启动：用基础版本的 JSON/jar/库，但 gameDir 指向实例目录
                        core.profileBuilder().buildInstance(
                            versionId, instDir, account, javaMajorVer, javaArch
                        )
                    } else {
                        core.profileBuilder().build(versionId, account, javaMajorVer, javaArch)
                    }
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
                _status.value = I18n.t("status.launching", javaExe, javaMajorVer, javaArch, versionId) +
                        if (usingCompatLayer) I18n.t("status.compat_layer_suffix") else ""

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
                // 携带实例 ID 和已安装模组列表，用于细分统计（按模组/按实例）
                val sessionModIds = _installedMods.value.mapNotNull { it.getModId().takeIf(String::isNotEmpty) }
                core.playTimeTracker().recordStart(versionId, instanceId ?: "", sessionModIds)
                timeTracked = true

                // launchAsync 返回 CompletableFuture，需等待进程退出，否则 gameRunning 会立即被 finally 重置
                // 预热仅预存 LaunchProfile（不启动 MC 进程），此处始终调用 launchAsync 启动真正的 MC 进程
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
                            _gameLogs.value = instanceLogs[instanceId]?.let { logs ->
                                synchronized(logs) { logs.toList() }
                            } ?: emptyList()
                        }
                        // 解析游戏日志，更新会话上下文（服务器地址 / 世界名）用于细分统计
                        try {
                            if (line.contains("Connecting to")) {
                                // 例：[Render thread/INFO]: Connecting to mc.example.com, 25565
                                val m = Regex("""Connecting to\s+([^,\s]+)(?:[,\s]+(\d+))?""").find(line)
                                if (m != null) {
                                    val host = m.groupValues[1]
                                    val port = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                                    val server = if (port != null) "$host:$port" else host
                                    core.playTimeTracker().updateSessionServer(versionId, server)
                                }
                            } else if (line.contains("Saving chunks for level")) {
                                // 例：Saving chunks for level 'worldName'/minecraft:overworld
                                val m = Regex("""Saving chunks for level '([^']+)'""").find(line)
                                if (m != null) {
                                    core.playTimeTracker().updateSessionWorld(versionId, m.groupValues[1])
                                }
                            } else if (line.contains("Preparing spawn area") && !line.contains("Connecting to")) {
                                // 单人世界加载阶段，若尚未记录世界名，用 "单人世界" 占位
                                core.playTimeTracker().updateSessionWorld(versionId, "Singleplayer")
                            }
                        } catch (_: Throwable) { }
                    },
                    instLogger
                )
                val exitCode = withContext(Dispatchers.IO) { future.join() }
                _status.value = I18n.t("status.game_exited_with_version", exitCode, versionId)

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
                _status.value = I18n.t("status.launch_failed", e.message ?: I18n.t("common.unknown"))
                _gameLogs.update { old -> (old + "[错误] ${e.message}").takeLast(2000) }
                instanceId?.let { id ->
                    instanceLogs[id]?.let { logs ->
                        synchronized(logs) { logs.add("[错误] ${e.message}") }
                    }
                }
            } finally {
                // 确保 recordEnd 被调用：即使 launchAsync 抛异常也要记录时长
                if (timeTracked) {
                    core.playTimeTracker().recordEnd(versionId)
                }
                // 清除实例启动上下文
                _pendingInstanceDir = null
                _pendingInstanceInfo = null
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
                        _gameLogs.value = instanceLogs[activeInst.id]?.let { logs ->
                            synchronized(logs) { logs.toList() }
                        } ?: emptyList()
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
        _gameLogs.value = instanceLogs[instanceId]?.let { logs ->
            synchronized(logs) { logs.toList() }
        } ?: emptyList()
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
            else JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir()) ?: "未找到"
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
            _status.value = I18n.t("status.launching_with_specific_java")
            var timeTracked = false
            try {
                val account = _account.value
                if (account == null) {
                    _status.value = I18n.t("status.login_first")
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
                _status.value = I18n.t("status.launching", javaPath, javaMajorVer, javaArch, versionId)
                // 记录游玩时长（携带已安装模组列表用于细分统计）
                val sessionModIds = _installedMods.value.mapNotNull { it.getModId().takeIf(String::isNotEmpty) }
                core.playTimeTracker().recordStart(versionId, "", sessionModIds)
                timeTracked = true
                preferences.recordRecentVersion(versionId)
                preferences.setLastPlayedTime(versionId, System.currentTimeMillis())
                _recentVersions.value = preferences.getRecentVersions()
                _lastPlayedTimes.value = HashMap(preferences.getLastPlayedTimesRaw())
                val future = core.launch().launchAsync(
                    profile, javaPath,
                    { line ->
                        _gameLogs.update { old -> (old + line).takeLast(2000) }
                        // 解析游戏日志，更新会话上下文（服务器地址 / 世界名）用于细分统计
                        try {
                            if (line.contains("Connecting to")) {
                                val m = Regex("""Connecting to\s+([^,\s]+)(?:[,\s]+(\d+))?""").find(line)
                                if (m != null) {
                                    val host = m.groupValues[1]
                                    val port = m.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }
                                    val server = if (port != null) "$host:$port" else host
                                    core.playTimeTracker().updateSessionServer(versionId, server)
                                }
                            } else if (line.contains("Saving chunks for level")) {
                                val m = Regex("""Saving chunks for level '([^']+)'""").find(line)
                                if (m != null) {
                                    core.playTimeTracker().updateSessionWorld(versionId, m.groupValues[1])
                                }
                            } else if (line.contains("Preparing spawn area")) {
                                core.playTimeTracker().updateSessionWorld(versionId, "Singleplayer")
                            }
                        } catch (_: Throwable) { }
                    },
                    gameLogger
                )
                val exitCode = withContext(Dispatchers.IO) { future.join() }
                _status.value = I18n.t("status.game_exited", exitCode)
                _gameRunning.value = false
            } catch (e: Throwable) {
                _status.value = I18n.t("status.launch_failed", e.message ?: I18n.t("common.unknown"))
                _gameLogs.update { old -> (old + "启动失败: ${e.message}").takeLast(2000) }
            } finally {
                if (timeTracked) {
                    core.playTimeTracker().recordEnd(versionId)
                }
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
            _status.value = I18n.t("status.launching_with_external_launcher", launcher.name)
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
                    val proc = ProcessBuilder(cmd).directory(workDir)
                        .redirectErrorStream(true).start()
                    // 消费合并输出流，防止管道缓冲区满导致进程挂起
                    Thread({
                        try { proc.inputStream.use { it.readBytes() } } catch (_: Throwable) {}
                    }, "ext-launcher-drain").apply { isDaemon = true }.start()
                }
                _status.value = I18n.t("status.external_launcher_opened", launcher.name, versionId)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.external_launcher_failed", launcher.name, e.message ?: I18n.t("common.unknown"))
                _gameLogs.value = listOf("打开 ${launcher.name} 失败: ${e.message}")
            }
        }
    }

    // ===== 启动预设 =====

    /** 刷新预设列表（从 Preferences 读取） */
    fun refreshLaunchPresets() {
        _launchPresets.value = preferences.getLaunchPresets()
    }

    /** 保存当前启动参数为预设 */
    fun saveLaunchPreset(name: String) {
        if (name.isBlank()) {
            _status.value = I18n.t("status.preset_name_empty")
            return
        }
        preferences.saveLaunchPreset(name.trim())
        refreshLaunchPresets()
        _status.value = I18n.t("status.preset_saved", name.trim())
    }

    /** 加载预设到当前启动参数 */
    fun applyLaunchPreset(name: String) {
        preferences.applyLaunchPreset(name)
        _status.value = I18n.t("status.preset_applied", name)
    }

    /** 删除预设 */
    fun deleteLaunchPreset(name: String) {
        preferences.deleteLaunchPreset(name)
        refreshLaunchPresets()
        _status.value = I18n.t("status.preset_deleted", name)
    }

    /**
     * 一键下载 Mojang 官方 Java 运行时。
     * 支持 Java 8（MC 1.12.2 及更早）/ Java 17（MC 1.17–1.20.4）/ Java 21（MC 1.20.5+）。
     * <p>
     * Apple Silicon Mac 上下载 Java 8 时，核心层自动选择 x86_64 版本（Rosetta 2），
     * 因为老版本 Minecraft 的 LWJGL 2.x 原生库只有 x86_64 版本。
     *
     * @param version Java 主版本号（8 / 17 / 21）
     */
    fun downloadJava(version: Int) {
        if (_javaDownloading.value) return
        val runtimeType = when (version) {
            8 -> com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_8
            17 -> com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_17
            21 -> com.pmcl.core.runtime.JavaRuntimeDownloader.RuntimeType.JAVA_21
            else -> {
                _status.value = I18n.t("status.unsupported_java_version", version)
                return
            }
        }
        scope.launch {
            _javaDownloading.value = true
            _javaDownloadStatus.value = "正在拉取 Java $version 清单…"
            try {
                val entries = withContext(Dispatchers.IO) {
                    core.javaDownloader().listRuntimes(runtimeType).join()
                }
                if (entries.isNullOrEmpty()) {
                    _javaDownloadStatus.value = "未找到可用的 Java $version 运行时"
                    _status.value = I18n.t("status.java_download_failed_empty_manifest", version)
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
                val detected = JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir()) ?: "未找到"
                _javaDownloadStatus.value = "完成：$detected"
                _status.value = I18n.t("status.java_install_complete", version)
            } catch (e: Throwable) {
                _javaDownloadStatus.value = "失败：${e.message}"
                _status.value = I18n.t("status.java_download_failed", version, e.message ?: I18n.t("common.unknown"))
            } finally {
                _javaDownloading.value = false
            }
        }
    }

    /** 向后兼容：下载 Java 21。 */
    fun downloadJava21() = downloadJava(21)

    /** 手动指定 Java 可执行文件路径（空字符串表示自动检测）。 */
    fun setJavaPath(path: String) {
        preferences.setJavaPath(path)
        _status.value = if (path.isEmpty()) I18n.t("status.java_path_reset") else I18n.t("status.java_path_set", path)
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
                _status.value = I18n.t("status.worlds_scanned", list.size)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.scan_worlds_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun backupWorld(world: WorldManager.WorldInfo) {
        scope.launch {
            try {
                val zip = withContext(Dispatchers.IO) { core.worlds().backup(world) }
                _status.value = I18n.t("status.world_backed_up", zip.fileName.toString())
            } catch (e: Throwable) {
                _status.value = I18n.t("status.backup_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun deleteWorld(world: WorldManager.WorldInfo) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.worlds().delete(world) }
                _status.value = I18n.t("status.world_deleted", world.name)
                refreshWorlds()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 列出指定世界的所有备份文件 */
    suspend fun listBackups(worldName: String): List<java.nio.file.Path> {
        return withContext(Dispatchers.IO) {
            try {
                core.worlds().listBackups(worldName)
            } catch (e: Throwable) {
                emptyList()
            }
        }
    }

    /** 从备份 zip 恢复世界（覆盖现有同名世界） */
    fun restoreWorld(zipFile: java.nio.file.Path, worldName: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.worlds().restore(zipFile, worldName) }
                _status.value = I18n.t("status.world_restored", worldName)
                refreshWorlds()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.restore_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 从 zip 导入世界（世界名取自 zip 文件名） */
    fun importWorld(zipFile: java.nio.file.Path) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.worlds().importWorld(zipFile) }
                _status.value = I18n.t("status.world_imported", zipFile.fileName.toString())
                refreshWorlds()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.import_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.screenshots_scanned", list.size)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.scan_screenshots_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun deleteScreenshot(shot: ScreenshotManager.Screenshot) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.screenshots().delete(shot) }
                _status.value = I18n.t("status.screenshot_deleted", shot.name)
                refreshScreenshots()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 复制截图到系统剪贴板（作为图片） */
    fun copyScreenshotToClipboard(shot: ScreenshotManager.Screenshot) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val img = javax.imageio.ImageIO.read(shot.getPath().toFile())
                    if (img != null) {
                        val selection = object : java.awt.datatransfer.Transferable {
                            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
                            override fun isDataFlavorSupported(f: java.awt.datatransfer.DataFlavor) =
                                f == java.awt.datatransfer.DataFlavor.imageFlavor
                            override fun getTransferData(f: java.awt.datatransfer.DataFlavor): Any {
                                if (f != java.awt.datatransfer.DataFlavor.imageFlavor)
                                    throw java.awt.datatransfer.UnsupportedFlavorException(f)
                                return img
                            }
                        }
                        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
                    }
                }
                _status.value = I18n.t("status.screenshot_copied", shot.name)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.copy_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 导出多张截图为 ZIP 文件 */
    fun exportScreenshotsZip(shots: List<ScreenshotManager.Screenshot>, targetPath: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    java.nio.file.Files.newOutputStream(java.nio.file.Paths.get(targetPath)).use { fos ->
                        java.util.zip.ZipOutputStream(fos).use { zos ->
                            val usedNames = mutableSetOf<String>()
                            for (shot in shots) {
                                var name = shot.getName()
                                while (!usedNames.add(name)) {
                                    val dot = name.lastIndexOf('.')
                                    name = if (dot > 0) name.substring(0, dot) + "_1" + name.substring(dot)
                                           else name + "_1"
                                }
                                zos.putNextEntry(java.util.zip.ZipEntry(name))
                                java.nio.file.Files.copy(shot.getPath(), zos)
                                zos.closeEntry()
                            }
                        }
                    }
                }
                _status.value = I18n.t("status.screenshots_exported", shots.size, targetPath)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.export_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    // ============ 资源包 ============

    fun refreshResourcePacks() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val all = mutableListOf<ResourcePackManager.Pack>()
                    val seen = mutableSetOf<String>()
                    val dirs = mutableListOf<java.nio.file.Path>()
                    // 1. PMCL 全局 resourcepacks
                    dirs.add(config.getWorkDir().resolve("resourcepacks"))
                    // 2. 系统 .minecraft/resourcepacks
                    for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                        val mcRoot = mcDir.parent
                        if (mcRoot != null) dirs.add(mcRoot.resolve("resourcepacks"))
                    }
                    // 3. 版本隔离 versions/<id>/resourcepacks
                    val versionsDirs = mutableListOf<java.nio.file.Path>()
                    versionsDirs.add(config.getVersionsDir())
                    versionsDirs.addAll(com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs())
                    for (vd in versionsDirs) {
                        val vf = vd.toFile()
                        if (!vf.isDirectory) continue
                        val subs = vf.listFiles { f -> f.isDirectory } ?: continue
                        for (sub in subs) dirs.add(sub.toPath().resolve("resourcepacks"))
                    }
                    // 4. 实例 instances/<id>/resourcepacks
                    val instDir = config.getWorkDir().resolve("instances")
                    if (instDir.toFile().isDirectory) {
                        val insts = instDir.toFile().listFiles { f -> f.isDirectory } ?: emptyArray()
                        for (inst in insts) dirs.add(inst.toPath().resolve("resourcepacks"))
                    }
                    // 扫描所有目录
                    for (dir in dirs) {
                        try {
                            val sourceLabel = contentSourceLabelFor(dir, "resourcepacks")
                            val part = core.resourcePacks().list(dir, sourceLabel)
                            for (p in part) {
                                val key = "$dir/${p.name}"
                                if (seen.add(key)) all.add(p)
                            }
                        } catch (_: Throwable) {}
                    }
                    all
                }
                _resourcePacks.value = list
                _status.value = I18n.t("status.resource_packs_scanned", list.size)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.scan_resource_packs_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun enableResourcePack(pack: ResourcePackManager.Pack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.resourcePacks().enable(pack.name) }
                _status.value = I18n.t("status.resource_pack_enabled", pack.name)
                refreshResourcePacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.enable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun disableResourcePack(pack: ResourcePackManager.Pack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.resourcePacks().disable(pack.name) }
                _status.value = I18n.t("status.resource_pack_disabled", pack.name)
                refreshResourcePacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.disable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun deleteResourcePack(pack: ResourcePackManager.Pack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.resourcePacks().delete(pack) }
                _status.value = I18n.t("status.resource_pack_deleted", pack.name)
                refreshResourcePacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 导入资源包文件到 resourcepacks 目录 */
    fun importResourcePack(filePath: String) {
        scope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) {
                    val src = java.nio.file.Paths.get(filePath)
                    val targetDir = config.getWorkDir().resolve("resourcepacks")
                    java.nio.file.Files.createDirectories(targetDir)
                    val target = targetDir.resolve(src.fileName)
                    java.nio.file.Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    src.fileName.toString()
                }
                _status.value = I18n.t("status.resource_pack_imported", fileName)
                refreshResourcePacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.import_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量启用资源包 */
    fun batchEnableResourcePacks(packs: List<ResourcePackManager.Pack>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (pack in packs) {
                        try {
                            core.resourcePacks().enable(pack.name)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_enabled_resource_packs", packs.size)
                refreshResourcePacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_enable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量禁用资源包 */
    fun batchDisableResourcePacks(packs: List<ResourcePackManager.Pack>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (pack in packs) {
                        try {
                            core.resourcePacks().disable(pack.name)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_disabled_resource_packs", packs.size)
                refreshResourcePacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_disable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量删除资源包 */
    fun batchDeleteResourcePacks(packs: List<ResourcePackManager.Pack>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (pack in packs) {
                        try {
                            core.resourcePacks().delete(pack)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_deleted_resource_packs", packs.size)
                refreshResourcePacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_delete_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    // ============ 完整性校验 ============

    fun checkIntegrity(versionId: String) {
        scope.launch {
            _status.value = I18n.t("status.checking_integrity", versionId)
            try {
                val r = withContext(Dispatchers.IO) { core.integrity().check(versionId) }
                _integrityResult.value = r
                _status.value = if (r.isOk()) I18n.t("status.integrity_check_passed")
                    else I18n.t("status.integrity_issues_found", r.issueCount, r.missing.size, r.hashMismatch.size)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.integrity_check_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.crash_reports_scanned", list.size)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.scan_crash_reports_failed", e.message ?: I18n.t("common.unknown"))
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
                _recoveryMessage.value = I18n.t("recovery.jump_to_settings_java", versionId)
            }
            CrashAnalyzer.RecoveryType.CHECK_MOD_CONFLICTS -> {
                refreshInstalledMods()
                _recoveryMessage.value = I18n.t("recovery.scanning_mod_conflicts")
            }
            CrashAnalyzer.RecoveryType.DISABLE_RECENT_MODS -> disableRecentMods(versionId)
            CrashAnalyzer.RecoveryType.CHECK_INTEGRITY -> {
                checkIntegrity(versionId)
                _recoveryMessage.value = I18n.t("recovery.checking_integrity", versionId)
            }
            CrashAnalyzer.RecoveryType.REINSTALL_VERSION -> reinstallVersion(versionId)
            CrashAnalyzer.RecoveryType.CLEAR_GAME_CONFIG -> clearGameConfig(versionId)
            CrashAnalyzer.RecoveryType.SHARE_LOGS -> {
                shareLogs()
                _recoveryMessage.value = I18n.t("recovery.uploading_logs")
            }
            CrashAnalyzer.RecoveryType.OPEN_MODS_PAGE -> {
                _navigationRequest.value = "content"
                _recoveryMessage.value = I18n.t("recovery.jumped_to_mods")
            }
            CrashAnalyzer.RecoveryType.OPEN_SETTINGS -> {
                _navigationRequest.value = "settings"
                _recoveryMessage.value = I18n.t("recovery.jumped_to_settings")
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
            _recoveryMessage.value = I18n.t("recovery.memory_at_limit", ceiling, sysMax)
        } else {
            preferences.setMaxMemoryMb(target)
            _recoveryMessage.value = I18n.t("recovery.memory_adjusted", current, target)
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
                    val jars = java.nio.file.Files.list(modsDir).use { stream ->
                        stream.filter { it.fileName.toString().endsWith(".jar") }.toList()
                    }
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
                    I18n.t("recovery.disabled_mods", moved)
                else I18n.t("recovery.no_mods_to_disable")
                if (moved > 0) refreshInstalledMods()
            } catch (e: Throwable) {
                _recoveryMessage.value = I18n.t("recovery.disable_mods_failed", e.message ?: I18n.t("common.unknown"))
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
                        java.nio.file.Files.walk(versionDir).use { stream ->
                            stream.sorted(java.util.Comparator.reverseOrder())
                                .forEach { p -> try { java.nio.file.Files.deleteIfExists(p) } catch (_: Throwable) {} }
                        }
                    }
                }
                _recoveryMessage.value = I18n.t("recovery.reinstalling", versionId)
                installVersion(versionId)
            } catch (e: Throwable) {
                _recoveryMessage.value = I18n.t("recovery.reinstall_failed", e.message ?: I18n.t("common.unknown"))
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
                    I18n.t("recovery.config_cleaned", backedUp)
                else I18n.t("recovery.no_config_to_clean")
            } catch (e: Throwable) {
                _recoveryMessage.value = I18n.t("recovery.clear_config_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    // ============ 网络偏好（设置页用） ============

    /** 用户修改网络配置后调用，立即生效 */
    fun applyNetworkPreferences() {
        core.applyNetworkPreferences()
        _status.value = I18n.t("status.network_prefs_applied")
    }

    // ============ 光影包 ============

    fun refreshShaderPacks() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) {
                    val all = mutableListOf<ShaderPackManager.ShaderPack>()
                    val seen = mutableSetOf<String>()
                    val dirs = mutableListOf<java.nio.file.Path>()
                    // 1. PMCL 全局 shaderpacks
                    dirs.add(config.getWorkDir().resolve("shaderpacks"))
                    // 2. 系统 .minecraft/shaderpacks
                    for (mcDir in com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs()) {
                        val mcRoot = mcDir.parent
                        if (mcRoot != null) dirs.add(mcRoot.resolve("shaderpacks"))
                    }
                    // 3. 版本隔离 versions/<id>/shaderpacks
                    val versionsDirs = mutableListOf<java.nio.file.Path>()
                    versionsDirs.add(config.getVersionsDir())
                    versionsDirs.addAll(com.pmcl.core.version.VersionManager.detectAllMinecraftVersionsDirs())
                    for (vd in versionsDirs) {
                        val vf = vd.toFile()
                        if (!vf.isDirectory) continue
                        val subs = vf.listFiles { f -> f.isDirectory } ?: continue
                        for (sub in subs) dirs.add(sub.toPath().resolve("shaderpacks"))
                    }
                    // 4. 实例 instances/<id>/shaderpacks
                    val instDir = config.getWorkDir().resolve("instances")
                    if (instDir.toFile().isDirectory) {
                        val insts = instDir.toFile().listFiles { f -> f.isDirectory } ?: emptyArray()
                        for (inst in insts) dirs.add(inst.toPath().resolve("shaderpacks"))
                    }
                    // 扫描所有目录
                    for (dir in dirs) {
                        try {
                            val sourceLabel = contentSourceLabelFor(dir, "shaderpacks")
                            val part = core.shaderPacks().list(dir, sourceLabel)
                            for (p in part) {
                                val key = "$dir/${p.name}"
                                if (seen.add(key)) all.add(p)
                            }
                        } catch (_: Throwable) {}
                    }
                    all
                }
                _shaderPacks.value = list
                _status.value = I18n.t("status.shader_packs_scanned", list.size)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.scan_shader_packs_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun enableShaderPack(pack: ShaderPackManager.ShaderPack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.shaderPacks().enable(pack.name) }
                _status.value = I18n.t("status.shader_pack_enabled", pack.name)
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.enable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun disableShaderPack(pack: ShaderPackManager.ShaderPack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.shaderPacks().disable(pack.name) }
                _status.value = I18n.t("status.shader_pack_disabled", pack.name)
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.disable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun deleteShaderPack(pack: ShaderPackManager.ShaderPack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.shaderPacks().delete(pack) }
                _status.value = I18n.t("status.shader_pack_deleted", pack.name)
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 导入光影包文件到 shaderpacks 目录 */
    fun importShaderPack(filePath: String) {
        scope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) {
                    val src = java.nio.file.Paths.get(filePath)
                    val targetDir = config.getWorkDir().resolve("shaderpacks")
                    java.nio.file.Files.createDirectories(targetDir)
                    val target = targetDir.resolve(src.fileName)
                    java.nio.file.Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    src.fileName.toString()
                }
                _status.value = I18n.t("status.shader_pack_imported", fileName)
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.import_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量启用光影包 */
    fun batchEnableShaderPacks(packs: List<ShaderPackManager.ShaderPack>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (pack in packs) {
                        try {
                            core.shaderPacks().enable(pack.name)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_enabled_shader_packs", packs.size)
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_enable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量禁用光影包 */
    fun batchDisableShaderPacks(packs: List<ShaderPackManager.ShaderPack>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (pack in packs) {
                        try {
                            core.shaderPacks().disable(pack.name)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_disabled_shader_packs", packs.size)
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_disable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量删除光影包 */
    fun batchDeleteShaderPacks(packs: List<ShaderPackManager.ShaderPack>) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (pack in packs) {
                        try {
                            core.shaderPacks().delete(pack)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_deleted_shader_packs", packs.size)
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_delete_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 将指定光影包设为当前选中（写入 options.txt） */
    fun setActiveShaderPack(pack: ShaderPackManager.ShaderPack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.shaderPacks().setActive(pack) }
                _status.value = I18n.t("status.shader_pack_applied", pack.name)
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.apply_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 关闭光影（清空当前选中） */
    fun clearActiveShaderPack() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.shaderPacks().clearActive() }
                _status.value = I18n.t("status.shader_pack_cleared")
                refreshShaderPacks()
            } catch (e: Throwable) {
                _status.value = I18n.t("status.clear_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.datapacks_scanned", list.size)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.scan_datapacks_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun deleteDatapack(pack: DatapackManager.Datapack) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { core.datapacks().delete(pack) }
                _status.value = I18n.t("status.datapack_deleted", pack.name)
                // 删除后刷新当前选中的世界
                _selectedDatapackWorld.value?.let { w ->
                    val list = withContext(Dispatchers.IO) { core.datapacks().list(w.dir) }
                    _datapacks.value = list
                }
            } catch (e: Throwable) {
                _status.value = I18n.t("status.delete_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun enableDatapack(pack: DatapackManager.Datapack) {
        scope.launch {
            try {
                _selectedDatapackWorld.value?.let { w ->
                    withContext(Dispatchers.IO) { core.datapacks().enable(w.dir, pack.name) }
                    _status.value = I18n.t("status.datapack_enabled", pack.name)
                    val list = withContext(Dispatchers.IO) { core.datapacks().list(w.dir) }
                    _datapacks.value = list
                }
            } catch (e: Throwable) {
                _status.value = I18n.t("status.enable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    fun disableDatapack(pack: DatapackManager.Datapack) {
        scope.launch {
            try {
                _selectedDatapackWorld.value?.let { w ->
                    withContext(Dispatchers.IO) { core.datapacks().disable(w.dir, pack.name) }
                    _status.value = I18n.t("status.datapack_disabled", pack.name)
                    val list = withContext(Dispatchers.IO) { core.datapacks().list(w.dir) }
                    _datapacks.value = list
                }
            } catch (e: Throwable) {
                _status.value = I18n.t("status.disable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 导入数据包文件到选中世界的 datapacks 目录 */
    fun importDatapack(filePath: String) {
        val world = _selectedDatapackWorld.value
        if (world == null) {
            _status.value = I18n.t("status.world_select_first")
            return
        }
        scope.launch {
            try {
                val fileName = withContext(Dispatchers.IO) {
                    val src = java.nio.file.Paths.get(filePath)
                    val targetDir = world.dir.resolve("datapacks")
                    java.nio.file.Files.createDirectories(targetDir)
                    val target = targetDir.resolve(src.fileName)
                    java.nio.file.Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    src.fileName.toString()
                }
                _status.value = I18n.t("status.datapack_imported", fileName)
                val list = withContext(Dispatchers.IO) { core.datapacks().list(world.dir) }
                _datapacks.value = list
            } catch (e: Throwable) {
                _status.value = I18n.t("status.import_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量启用数据包 */
    fun batchEnableDatapacks(packs: List<DatapackManager.Datapack>) {
        val world = _selectedDatapackWorld.value
        if (world == null) {
            _status.value = I18n.t("status.world_select_first")
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (pack in packs) {
                        try {
                            core.datapacks().enable(world.dir, pack.name)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_enabled_datapacks", packs.size)
                val list = withContext(Dispatchers.IO) { core.datapacks().list(world.dir) }
                _datapacks.value = list
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_enable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量禁用数据包 */
    fun batchDisableDatapacks(packs: List<DatapackManager.Datapack>) {
        val world = _selectedDatapackWorld.value
        if (world == null) {
            _status.value = I18n.t("status.world_select_first")
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (pack in packs) {
                        try {
                            core.datapacks().disable(world.dir, pack.name)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_disabled_datapacks", packs.size)
                val list = withContext(Dispatchers.IO) { core.datapacks().list(world.dir) }
                _datapacks.value = list
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_disable_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 批量删除数据包 */
    fun batchDeleteDatapacks(packs: List<DatapackManager.Datapack>) {
        val world = _selectedDatapackWorld.value
        if (world == null) {
            _status.value = I18n.t("status.world_select_first")
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (pack in packs) {
                        try {
                            core.datapacks().delete(pack)
                        } catch (_: Throwable) {}
                    }
                }
                _status.value = I18n.t("status.batch_deleted_datapacks", packs.size)
                val list = withContext(Dispatchers.IO) { core.datapacks().list(world.dir) }
                _datapacks.value = list
            } catch (e: Throwable) {
                _status.value = I18n.t("status.batch_delete_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.wiki_opened", url)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.open_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    // ============ 语言切换 ============

    fun setLanguage(lang: String) {
        preferences.setLanguage(lang)
        core.applyLanguage(lang)
        _status.value = I18n.t("status.language_switched")
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
            _status.value = I18n.t("status.logs_exported", targetPath)
            true
        } catch (e: Throwable) {
            _status.value = I18n.t("status.logs_export_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.no_logs_to_share")
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
                _status.value = I18n.t("status.logs_uploaded")
            } catch (e: Throwable) {
                _status.value = I18n.t("status.logs_upload_failed", e.message ?: I18n.t("common.unknown"))
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
                    fetchNewsCoverImages(data)
                }
                // 缓存未过期：后台静默刷新（stale-while-revalidate）
                if (!DataCache.isExpired(savedAt, 60 * 60 * 1000L)) {
                    scope.launch {
                        try {
                            val list = withContext(Dispatchers.IO) {
                                core.news().fetch(20).join()
                            }
                            transferNewsImageUrls(list)
                            _newsItems.value = list
                            fetchNewsCoverImages(list)
                            DataCache.save("news_list", list)
                            _status.value = if (list.isEmpty()) I18n.t("status.no_news") else I18n.t("status.news_loaded", list.size)
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
            _status.value = I18n.t("status.loading_news")
            try {
                val list = withContext(Dispatchers.IO) {
                    core.news().fetch(20).join()
                }
                transferNewsImageUrls(list)
                _newsItems.value = list
                fetchNewsCoverImages(list)
                _status.value = if (list.isEmpty()) I18n.t("status.no_news") else I18n.t("status.news_loaded", list.size)
                DataCache.save("news_list", list)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.news_load_failed", e.message ?: I18n.t("common.unknown"))
            } finally {
                _newsLoading.value = false
            }
        }
    }

    /** 封面图后台抓取 Job，可取消 */
    @Volatile
    private var newsImageJob: kotlinx.coroutines.Job? = null

    /** 将旧列表中已抓取的 imageUrl 按 link 迁移到新列表，避免后台刷新时重复抓取 */
    private fun transferNewsImageUrls(newItems: List<com.pmcl.core.news.NewsItem>) {
        val oldMap = _newsItems.value.associateBy { it.getLink() }
        newItems.forEach { newItem ->
            val old = oldMap[newItem.getLink()]
            if (old != null && old.getImageUrl().isNotEmpty()) {
                newItem.setImageUrl(old.getImageUrl())
            }
        }
    }

    /**
     * RSS 不含图片 URL，异步抓取每篇文章页提取封面图并回填到 NewsItem。
     * 并发限制 5，完成后更新缓存。已有 imageUrl 的条目跳过。
     */
    private fun fetchNewsCoverImages(items: List<com.pmcl.core.news.NewsItem>) {
        newsImageJob?.cancel()
        val toFetch = items.filter { it.getImageUrl().isEmpty() && it.getLink().isNotEmpty() }
        if (toFetch.isEmpty()) return
        newsImageJob = scope.launch {
            val semaphore = Semaphore(5)
            coroutineScope {
                toFetch.forEach { item ->
                    launch {
                        semaphore.withPermit {
                            try {
                                val url = withContext(Dispatchers.IO) {
                                    core.news().fetchCoverImage(item.getLink()).join()
                                }
                                if (url.isNotEmpty()) {
                                    item.setImageUrl(url)
                                    _newsItems.value = _newsItems.value.toList()
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
            DataCache.save("news_list", _newsItems.value)
        }
    }

    /** 在系统浏览器打开新闻原文链接 */
    fun openNewsLink(url: String) {
        if (url.isBlank()) {
            _status.value = I18n.t("status.news_no_link")
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { WikiBrowser.open(url) }
                _status.value = I18n.t("status.news_opened_in_browser")
            } catch (e: Throwable) {
                _status.value = I18n.t("status.open_failed", e.message ?: I18n.t("common.unknown"))
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
            _status.value = I18n.t("status.leave_room_before_switch_backend")
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
        _status.value = I18n.t("status.mp_backend_switched", label)
    }

    fun createRoom() {
        if (_mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTING ||
            _mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
            _status.value = I18n.t("status.already_in_room")
            return
        }
        val backend = mpBackend
        _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.DOWNLOADING
        _mpProgress.value = I18n.t("mp.progress.preparing")
        _status.value = when (backend) {
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> I18n.t("status.creating_connectx_room")
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA -> I18n.t("status.creating_terracotta_room")
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.EASYTIER -> I18n.t("status.creating_mp_room")
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
                            I18n.t("status.room_created_with_code", core.multiplayer().currentRoomCode)
                        com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX ->
                            I18n.t("status.connectx_room_created")
                        else ->
                            I18n.t("status.room_created_with_vip", core.multiplayer().virtualIp)
                    }
                } else {
                    I18n.t("status.room_started_waiting")
                }
            } catch (e: Throwable) {
                _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.FAILED
                _status.value = I18n.t("status.create_room_failed", e.message ?: I18n.t("common.unknown"))
            } finally {
                _mpProgress.value = ""
            }
        }
    }

    /** 通过邀请码/房间码加入房间 */
    fun joinRoom(invitation: String) {
        if (invitation.isBlank()) {
            _status.value = I18n.t("status.enter_room_code_or_invitation")
            return
        }
        if (_mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTING ||
            _mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
            _status.value = I18n.t("status.already_in_room")
            return
        }
        val isConnectX = invitation.trim().startsWith("connectx-")
        val isTerracotta = invitation.trim().startsWith("U/") ||
            mpBackend == com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA
        _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.DOWNLOADING
        _mpProgress.value = I18n.t("mp.progress.parsing_code")
        _status.value = I18n.t("status.joining_room")
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
                    // 启动好友系统网络服务
                    withContext(Dispatchers.IO) {
                        try { core.friend()?.start() } catch (e: Exception) {
                            System.err.println("[LauncherVM] 启动好友系统失败: ${e.message}")
                        }
                    }
                    if (isTerracotta && core.multiplayer().localMcAddr.isNotEmpty()) {
                        I18n.t("status.joined_room_mc_addr", core.multiplayer().localMcAddr)
                    } else {
                        I18n.t("status.joined_room_vip", core.multiplayer().virtualIp)
                    }
                } else {
                    I18n.t("status.connecting_room")
                }
            } catch (e: Throwable) {
                _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.FAILED
                _status.value = I18n.t("status.join_room_failed", e.message ?: I18n.t("common.unknown"))
            } finally {
                _mpProgress.value = ""
            }
        }
    }

    /** 离开当前房间 */
    fun leaveRoom() {
        scope.launch {
            try {
                // 停止好友系统网络服务
                withContext(Dispatchers.IO) {
                    try { core.friend()?.stop() } catch (e: Exception) {
                        System.err.println("[LauncherVM] 停止好友系统失败: ${e.message}")
                    }
                }
                withContext(Dispatchers.IO) { core.multiplayer().leaveRoom() }
            } catch (e: Throwable) {
                _status.value = I18n.t("status.leave_room_failed", e.message ?: I18n.t("common.unknown"))
            }
            refreshMpState()
            _mpInvitation.value = ""
            _mpVirtualIp.value = ""
            _mpLocalMcAddr.value = ""
            val backend = mpBackend
            _status.value = when (backend) {
                com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> I18n.t("status.left_connectx_room")
                com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA -> I18n.t("status.left_terracotta_room")
                else -> I18n.t("status.left_terracotta_room")
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
            _status.value = I18n.t("status.no_invitation_to_share")
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
                _status.value = I18n.t("status.invitation_copied")
            } catch (e: Throwable) {
                _status.value = I18n.t("status.copy_failed", e.message ?: I18n.t("common.unknown"))
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
                _status.value = I18n.t("status.copied", text)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.copy_failed", e.message ?: I18n.t("common.unknown"))
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
        _status.value = I18n.t("status.direct_connect_server_set", "$host:$port")
    }

    /** ping 单个服务器 */
    fun pingServer(host: String, port: Int) {
        val key = "$host:$port"
        scope.launch {
            try {
                val latency = withContext(Dispatchers.IO) {
                    com.pmcl.core.multiplayer.ServerPinger.ping(host, port)
                }
                // 使用 update 原子更新，避免并发 ping 完成时读-改-写丢失更新
                _serverPings.update { it + (key to latency) }
            } catch (e: Throwable) {
                _serverPings.update { it + (key to com.pmcl.core.multiplayer.ServerPinger.UNREACHABLE) }
            }
        }
    }

    /** 批量 ping 所有收藏服务器 */
    fun pingAllServers() {
        val servers = _favoriteServers.value
        servers.forEach { s -> pingServer(s.host, s.port) }
    }

    // ===== 服务器完整状态 ping（MOTD/在线人数/版本） =====

    /** 服务器完整状态（可观察），key = "host:port" */
    private val _serverStatuses = MutableStateFlow<Map<String, com.pmcl.core.multiplayer.ServerPinger.ServerStatus>>(emptyMap())
    val serverStatuses: StateFlow<Map<String, com.pmcl.core.multiplayer.ServerPinger.ServerStatus>> = _serverStatuses.asStateFlow()

    /** 正在 ping 中的服务器集合（key = "host:port"） */
    private val _pingingServers = MutableStateFlow<Set<String>>(emptySet())
    val pingingServers: StateFlow<Set<String>> = _pingingServers.asStateFlow()

    /** 完整 ping 单个服务器，返回 MOTD/在线人数/版本等完整信息 */
    fun pingServerFull(host: String, port: Int) {
        val key = "$host:$port"
        _pingingServers.update { it + key }
        scope.launch {
            try {
                val status = withContext(Dispatchers.IO) {
                    com.pmcl.core.multiplayer.ServerPinger.pingFull(host, port)
                }
                _serverStatuses.update { it + (key to status) }
                // 同步更新延迟 Map，保持与旧 API 兼容
                _serverPings.update { it + (key to status.latency) }
            } catch (e: Throwable) {
                val err = com.pmcl.core.multiplayer.ServerPinger.ServerStatus(
                    com.pmcl.core.multiplayer.ServerPinger.UNREACHABLE, "", 0, 0, "", 0, null, e.message)
                _serverStatuses.update { it + (key to err) }
            } finally {
                _pingingServers.update { it - key }
            }
        }
    }

    /** 批量完整 ping 所有收藏服务器 */
    fun pingAllServersFull() {
        val servers = _favoriteServers.value
        servers.forEach { s -> pingServerFull(s.host, s.port) }
    }

    /** 更新收藏服务器（名称/地址/端口） */
    fun updateFavoriteServer(index: Int, name: String, host: String, port: Int) {
        preferences.updateFavoriteServer(index, name, host, port)
        loadFavoriteServers()
    }

    // ============ 独立实例管理 ============

    private val _instances = MutableStateFlow<List<InstanceInfo>>(emptyList())
    val instances: StateFlow<List<InstanceInfo>> = _instances.asStateFlow()

    private val _instanceLaunching = MutableStateFlow<String?>(null)
    val instanceLaunching: StateFlow<String?> = _instanceLaunching.asStateFlow()

    // 实例启动上下文：launch() 读取此字段决定是否按实例模式启动
    @Volatile private var _pendingInstanceDir: java.nio.file.Path? = null
    @Volatile private var _pendingInstanceInfo: InstanceInfo? = null

    /** 加载实例列表 */
    fun loadInstances() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { core.instances().listInstances() }
                _instances.value = list
            } catch (e: Throwable) {
                _status.value = I18n.t("status.load_instances_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 创建新实例 */
    fun createInstance(name: String, baseVersionId: String, loader: String?, loaderVersion: String?) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.instances().createInstance(name, baseVersionId, loader, loaderVersion)
                }
                loadInstances()
                _status.value = I18n.t("status.instance_created", name)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.instance_create_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 复制实例（克隆 mods/configs/resourcepacks） */
    fun copyInstance(instanceId: String, newName: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.instances().copyInstance(instanceId, newName)
                }
                loadInstances()
                _status.value = I18n.t("status.instance_copied", newName)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.instance_copy_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 重命名实例 */
    fun renameInstance(instanceId: String, newName: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.instances().renameInstance(instanceId, newName)
                }
                loadInstances()
                _status.value = I18n.t("status.instance_renamed", newName)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.rename_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 删除实例 */
    fun deleteInstance(instanceId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.instances().deleteInstance(instanceId)
                }
                loadInstances()
                _status.value = I18n.t("status.instance_deleted")
            } catch (e: Throwable) {
                _status.value = I18n.t("status.instance_delete_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 启动实例 */
    fun launchInstance(instanceId: String) {
        val info = _instances.value.find { it.getInstanceId() == instanceId } ?: return
        if (!info.isLaunchable()) {
            _status.value = I18n.t("status.instance_missing_base_version", info.getName())
            return
        }
        // 实例绑定账户：启动前切换到绑定账户（不改变全局选中，仅影响本次启动）
        val boundUuid = info.getBoundAccountUuid()
        if (boundUuid.isNotEmpty()) {
            val boundAcc = _accounts.value.find { it.getUuid() == boundUuid }
            if (boundAcc != null && boundAcc.getUuid() != _account.value?.getUuid()) {
                _account.value = boundAcc
            }
        }
        // 设置实例上下文，launch() 会读取此字段用 buildInstance 代替 build
        _pendingInstanceDir = info.getInstanceDir()
        _pendingInstanceInfo = info
        // 选中基础版本并调用现有 launch 流程
        selectVersion(info.getBaseVersionId())
        launch()
    }

    /** 为实例绑定账户（uuid 为空则清除绑定） */
    fun bindAccountToInstance(instanceId: String, uuid: String) {
        val info = _instances.value.find { it.getInstanceId() == instanceId } ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    info.setBoundAccountUuid(uuid)
                    core.instances().updateInstance(info)
                }
                loadInstances()
                _status.value = if (uuid.isEmpty()) I18n.t("status.instance_account_unbound", info.getName())
                                else I18n.t("status.instance_account_bound", info.getName())
            } catch (e: Throwable) {
                _status.value = I18n.t("status.instance_account_bind_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 返回实例绑定的账户（未绑定返回 null） */
    fun getBoundAccount(info: InstanceInfo): Account? {
        val uuid = info.getBoundAccountUuid()
        if (uuid.isEmpty()) return null
        return _accounts.value.find { it.getUuid() == uuid }
    }

    /** 设置实例图标（复制图片到实例目录） */
    fun setInstanceIcon(instanceId: String, imagePath: java.nio.file.Path) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.instances().setInstanceIcon(instanceId, imagePath)
                }
                loadInstances()
                _status.value = I18n.t("status.instance_icon_set")
            } catch (e: Throwable) {
                _status.value = I18n.t("status.instance_icon_set_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 清除实例图标 */
    fun clearInstanceIcon(instanceId: String) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    core.instances().clearInstanceIcon(instanceId)
                }
                loadInstances()
                _status.value = I18n.t("status.instance_icon_cleared")
            } catch (e: Throwable) {
                _status.value = I18n.t("status.instance_icon_set_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 返回实例图标文件路径（不存在返回 null） */
    fun getInstanceIconFile(info: InstanceInfo): java.nio.file.Path? {
        val iconPath = info.getIconPath() ?: return null
        if (iconPath.isEmpty()) return null
        val dir = info.getInstanceDir() ?: return null
        val iconFile = dir.resolve(iconPath)
        return if (java.nio.file.Files.exists(iconFile)) iconFile else null
    }

    /** 导出实例为 .pmcl-instance 文件 */
    fun exportInstance(instanceId: String, outputPath: java.nio.file.Path) {
        scope.launch {
            _status.value = I18n.t("status.exporting_instance")
            try {
                val modCount = withContext(Dispatchers.IO) {
                    core.instances().exportInstance(instanceId, outputPath)
                }
                _status.value = I18n.t("status.instance_exported", modCount)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.instance_export_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 从 .pmcl-instance 文件导入实例，返回导入结果（null 表示失败） */
    suspend fun importInstance(zipPath: java.nio.file.Path): com.pmcl.core.instance.InstanceImporter.ImportResult? {
        return try {
            val result = withContext(Dispatchers.IO) {
                core.instances().importInstance(zipPath)
            }
            loadInstances()
            _status.value = if (result.mods.isEmpty()) {
                I18n.t("status.instance_imported_no_mods", result.info.getName())
            } else {
                I18n.t("status.instance_imported", result.info.getName(), result.mods.size)
            }
            result
        } catch (e: Throwable) {
            _status.value = I18n.t("status.instance_import_failed", e.message ?: I18n.t("common.unknown"))
            null
        }
    }

    /** 添加额外 Minecraft 根目录，添加后自动刷新版本列表 */
    fun addMinecraftRoot(rootPath: String) {
        if (rootPath.isBlank()) {
            _status.value = I18n.t("status.minecraft_root_empty")
            return
        }
        val path = java.nio.file.Paths.get(rootPath).toAbsolutePath().toString()
        // 校验：目录存在且含 versions 子目录
        val versionsDir = java.nio.file.Paths.get(path, "versions")
        if (!java.nio.file.Files.isDirectory(versionsDir)) {
            _status.value = I18n.t("status.minecraft_root_invalid", path)
            return
        }
        preferences.addExtraMinecraftRoot(path)
        // 清除版本目录检测缓存，让下次扫描重新检测
        com.pmcl.core.version.VersionManager.clearCache()
        refreshLocalVersions()
        _status.value = I18n.t("status.minecraft_root_added", path)
    }

    /** 移除额外 Minecraft 根目录，移除后自动刷新版本列表 */
    fun removeMinecraftRoot(rootPath: String) {
        preferences.removeExtraMinecraftRoot(rootPath)
        com.pmcl.core.version.VersionManager.clearCache()
        refreshLocalVersions()
        _status.value = I18n.t("status.minecraft_root_removed", rootPath)
    }

    /** 返回用户自定义的额外 Minecraft 根目录列表 */
    fun getExtraMinecraftRoots(): List<String> = preferences.getExtraMinecraftRoots()

    // ============ 首次启动 / 迁移 ============

    /** 扫描本机其他启动器的数据目录（HMCL / PCL / 系统 .minecraft） */
    fun detectMigrationSources() {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { core.migration().detectSources() }
                _migrationSources.value = list
                _status.value = if (list.isEmpty()) I18n.t("status.no_migration_sources") else I18n.t("status.migration_sources_detected", list.size)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.scan_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }

    /** 从指定来源迁移游戏数据到 PMCL 工作目录 */
    fun migrateFrom(source: com.pmcl.core.migration.MigrationManager.Source) {
        if (_migrating.value) return
        scope.launch {
            _migrating.value = true
            _migrationProgress.value = "开始从 ${source.getName()} 迁移…"
            _status.value = I18n.t("status.migrating_from", source.getName())
            try {
                withContext(Dispatchers.IO) {
                    core.migration().migrate(source) { msg ->
                        _migrationProgress.value = msg
                    }
                }
                // 迁移完成后刷新本地版本
                refreshLocalVersions()
                _status.value = I18n.t("status.migration_complete")
                _migrationProgress.value = "迁移完成"
            } catch (e: Throwable) {
                _status.value = I18n.t("status.migration_failed", e.message ?: I18n.t("common.unknown"))
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

    /** 用户同意用户协议、免责协议与许可证 */
    fun acceptAgreements() {
        preferences.setAgreementAccepted(true)
        _agreementAccepted.value = true
    }

    // ===== 音乐播放器方法 =====
    // M29 拆分：音乐域方法已移至 LauncherViewModelMusic.kt（同包扩展函数）。
    // UI 调用方签名不变（vm.playMusicAt / vm.musicPlaylist 等）。
    // 入口：resolveAndAddMusicTrack / playMusicAt / toggleMusicPlayPause / pauseMusic / resumeMusic /
    //       stopMusic / playNextMusic / playPreviousMusic / seekMusicTo / setMusicVolume /
    //       toggleMusicMute / cycleMusicRepeatMode / toggleMusicShuffle / removeMusicTrack /
    //       clearMusicPlaylist / currentMusicTrack / persistMusicPlaylist
}
