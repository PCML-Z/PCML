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
import com.pmcl.core.nbt.NbtTag
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
import kotlinx.coroutines.Job
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
            // stop() 取消定时任务但保留调度器，可再次 start()
            client.stop()
            _syncActive.value = false
            _pushStatusText.value = "已禁用"
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

    @Volatile private var shutDown = false

    /**
     * 优雅关闭：清理联机/游戏进程、落盘偏好、关闭下载与日志，再取消协程。
     * 幂等；应在应用退出前调用，避免孤儿进程与未落盘配置。
     */
    fun shutdown() {
        if (shutDown) return
        shutDown = true
        try {
            preheatGeneration.incrementAndGet()
            preheatJob?.cancel()
            preheatJob = null
            preheatedProfile = null
        } catch (_: Throwable) {}
        try { core.multiplayer().leaveRoom() } catch (_: Throwable) {}
        try { core.launch().shutdown() } catch (_: Throwable) {}
        try {
            instanceLoggers.values.forEach { logger ->
                try { logger?.close() } catch (_: Throwable) {}
            }
            instanceLoggers.clear()
        } catch (_: Throwable) {}
        try { core.downloadQueue().shutdown() } catch (_: Throwable) {}
        try { core.downloads().shutdown() } catch (_: Throwable) {}
        try { core.preferences.shutdown() } catch (_: Throwable) {}
        try { stopMusic() } catch (_: Throwable) {}
        scope.cancel()
    }

    /** 账号持久化文件 */
    @PublishedApi internal val accountFile = Paths.get(System.getProperty("user.home"), ".pmcl", "accounts.json")
    @PublishedApi internal val accountLock = Any()
    /** 串行化账号落盘，避免快速切换/删除时乱序覆盖 */
    @PublishedApi internal val accountSaveMutex = kotlinx.coroutines.sync.Mutex()

    // ===== 版本列表 =====
    private val _versions = MutableStateFlow<List<McVersion>>(emptyList())
    val versions: StateFlow<List<McVersion>> = _versions.asStateFlow()

    private val _localVersions = MutableStateFlow<List<String>>(emptyList())
    val localVersions: StateFlow<List<String>> = _localVersions.asStateFlow()

    // 本地版本详细信息（含 jar/json/inheritsFrom）
    @PublishedApi internal val _localVersionInfos = MutableStateFlow<List<com.pmcl.core.version.VersionManager.LocalVersionInfo>>(emptyList())
    val localVersionInfos: StateFlow<List<com.pmcl.core.version.VersionManager.LocalVersionInfo>> = _localVersionInfos.asStateFlow()

    // 固定的版本磁贴
    private val _pinnedVersions = MutableStateFlow<List<String>>(emptyList())
    val pinnedVersions: StateFlow<List<String>> = _pinnedVersions.asStateFlow()

    // 磁贴自定义名称（versionId → 显示名）
    private val _pinnedTileLabels = MutableStateFlow<Map<String, String>>(emptyMap())
    val pinnedTileLabels: StateFlow<Map<String, String>> = _pinnedTileLabels.asStateFlow()

    // 最近使用（LRU，最多 5 个）
    @PublishedApi internal val _recentVersions = MutableStateFlow<List<String>>(emptyList())
    val recentVersions: StateFlow<List<String>> = _recentVersions.asStateFlow()

    // 最后游玩时间戳（versionId → millis），用于磁贴/列表显示
    @PublishedApi internal val _lastPlayedTimes = MutableStateFlow<Map<String, Long>>(emptyMap())
    val lastPlayedTimes: StateFlow<Map<String, Long>> = _lastPlayedTimes.asStateFlow()

    // 扫描进度（null 表示未在扫描）
    private val _scanProgress = MutableStateFlow<com.pmcl.core.version.VersionManager.ScanProgress?>(null)
    val scanProgress: StateFlow<com.pmcl.core.version.VersionManager.ScanProgress?> = _scanProgress.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    @PublishedApi internal val _selectedVersion = MutableStateFlow<String?>(null)
    val selectedVersion: StateFlow<String?> = _selectedVersion.asStateFlow()

    // ===== 状态/账号 =====
    @PublishedApi internal val _status = MutableStateFlow(I18n.t("status.ready"))
    val status: StateFlow<String> = _status.asStateFlow()

    /** UI 层更新状态栏文本（如浏览器打开失败等错误提示） */
    fun updateStatus(msg: String) {
        _status.value = msg
    }

    /** Companion 宿主未捕获异常时回写桌面状态栏，避免「假死」无反馈 */
    fun setCompanionHostError(detail: String) {
        _status.value = I18n.t("status.companion_host_error", detail)
    }

    @PublishedApi internal val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account.asStateFlow()

    @PublishedApi internal val _accounts = MutableStateFlow<List<Account>>(emptyList())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    /**
     * 单次启动账户覆盖（实例绑定账户）：不改动全局选中账号，
     * 在 launch() finally 中清除。
     */
    @PublishedApi @Volatile internal var _launchAccountOverride: Account? = null

    // ===== 安装进度 =====
    private val _installProgress = MutableStateFlow<InstallProgress?>(null)
    val installProgress: StateFlow<InstallProgress?> = _installProgress.asStateFlow()

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    // ===== 模组加载器 =====
    private val _modLoaderVersions = MutableStateFlow<List<ModLoaderVersion>>(emptyList())
    val modLoaderVersions: StateFlow<List<ModLoaderVersion>> = _modLoaderVersions.asStateFlow()

    // ===== 模组市场 / 已安装 =====
    // M29：方法见 LauncherViewModelMods.kt
    @PublishedApi internal val _marketResults = MutableStateFlow<List<ModProject>>(emptyList())
    val marketResults: StateFlow<List<ModProject>> = _marketResults.asStateFlow()

    @PublishedApi internal val _currentModFiles = MutableStateFlow<List<ModFile>>(emptyList())
    val currentModFiles: StateFlow<List<ModFile>> = _currentModFiles.asStateFlow()

    @PublishedApi internal val _marketLoading = MutableStateFlow(false)
    val marketLoading: StateFlow<Boolean> = _marketLoading.asStateFlow()

    @PublishedApi internal val _popularMods = MutableStateFlow<List<ModProject>>(emptyList())
    val popularMods: StateFlow<List<ModProject>> = _popularMods.asStateFlow()

    @PublishedApi internal val _popularLoading = MutableStateFlow(false)
    val popularLoading: StateFlow<Boolean> = _popularLoading.asStateFlow()

    @PublishedApi internal val _categoryResults = MutableStateFlow<List<ModProject>>(emptyList())
    val categoryResults: StateFlow<List<ModProject>> = _categoryResults.asStateFlow()

    @PublishedApi internal val _categoryLoading = MutableStateFlow(false)
    val categoryLoading: StateFlow<Boolean> = _categoryLoading.asStateFlow()

    @PublishedApi internal val _selectedCategory = MutableStateFlow("")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    @PublishedApi internal val _detailProject = MutableStateFlow<ModProject?>(null)
    val detailProject: StateFlow<ModProject?> = _detailProject.asStateFlow()

    @PublishedApi internal val _installedMods = MutableStateFlow<List<ModMeta>>(emptyList())
    val installedMods: StateFlow<List<ModMeta>> = _installedMods.asStateFlow()

    @PublishedApi internal val _modConflicts = MutableStateFlow<ModConflictChecker.Result?>(null)
    val modConflicts: StateFlow<ModConflictChecker.Result?> = _modConflicts.asStateFlow()

    @PublishedApi internal val _allModTags = MutableStateFlow<List<String>>(emptyList())
    val allModTags: StateFlow<List<String>> = _allModTags.asStateFlow()

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
    // M29 拆分：NBT 域方法已移至 LauncherViewModelNbt.kt（扩展函数）。
    // 此处状态标 @PublishedApi internal 以便同模块扩展函数访问，公共只读视图保持不变。
    @PublishedApi internal val _nbtRoot = MutableStateFlow<NbtTag?>(null)
    val nbtRoot: StateFlow<NbtTag?> = _nbtRoot.asStateFlow()
    @PublishedApi internal val _nbtFilePath = MutableStateFlow<String?>(null)
    val nbtFilePath: StateFlow<String?> = _nbtFilePath.asStateFlow()
    @PublishedApi internal val _nbtDirty = MutableStateFlow(false)
    val nbtDirty: StateFlow<Boolean> = _nbtDirty.asStateFlow()
    @PublishedApi internal val _nbtError = MutableStateFlow<String?>(null)
    val nbtError: StateFlow<String?> = _nbtError.asStateFlow()
    /** 修订计数器：每次树结构修改时递增，强制 Compose 重组（解决同引用 StateFlow 不刷新问题） */
    @PublishedApi internal val _nbtRevision = MutableStateFlow(0)
    val nbtRevision: StateFlow<Int> = _nbtRevision.asStateFlow()
    /** 打开时检测到的压缩方式；保存时保持一致（默认 gzip，与 level.dat 一致） */
    @PublishedApi internal val _nbtGzipped = MutableStateFlow(true)
    val nbtGzipped: StateFlow<Boolean> = _nbtGzipped.asStateFlow()
    @PublishedApi internal val nbtUndoStack = ArrayDeque<NbtTag>()
    @PublishedApi internal val nbtRedoStack = ArrayDeque<NbtTag>()
    @PublishedApi internal val _nbtCanUndo = MutableStateFlow(false)
    val nbtCanUndo: StateFlow<Boolean> = _nbtCanUndo.asStateFlow()
    @PublishedApi internal val _nbtCanRedo = MutableStateFlow(false)
    val nbtCanRedo: StateFlow<Boolean> = _nbtCanRedo.asStateFlow()
    @PublishedApi internal val _nbtHasClipboard = MutableStateFlow(false)
    val nbtHasClipboard: StateFlow<Boolean> = _nbtHasClipboard.asStateFlow()
    @PublishedApi internal var nbtClipboard: Pair<String, NbtTag>? = null
    @PublishedApi internal val _recentNbtFiles = MutableStateFlow(core.preferences.recentNbtFiles)
    val recentNbtFiles: StateFlow<List<String>> = _recentNbtFiles.asStateFlow()
    @PublishedApi internal val nbtMaxUndo = 40

    // ===== 下载队列 =====
    private val _queueTasks = MutableStateFlow<List<DownloadQueueManager.QueueTask>>(emptyList())
    val queueTasks: StateFlow<List<DownloadQueueManager.QueueTask>> = _queueTasks.asStateFlow()

    private val _queueSummary = MutableStateFlow<DownloadQueueManager.QueueSummary>(
        DownloadQueueManager.QueueSummary(0, 0, 0, 0, 0, 0, 0L, 0L)
    )
    val queueSummary: StateFlow<DownloadQueueManager.QueueSummary> = _queueSummary.asStateFlow()

    /** 队列监听器初始化标志，避免重复注册 */
    @Volatile private var queueListenerRegistered = false

    // ===== 下载飞入动画 =====
    private val _flyAnimations = MutableStateFlow<List<com.pmcl.ui.animation.DownloadFlyState>>(emptyList())
    val flyAnimations: StateFlow<List<com.pmcl.ui.animation.DownloadFlyState>> = _flyAnimations.asStateFlow()

    /** 悬浮下载队列卡片在窗口中的位置（由 FloatingDownloadQueue 上报） */
    @Volatile private var downloadQueueRect: com.pmcl.ui.animation.Rect? = null

    /** 脉冲触发计数（每次飞入完成 +1，驱动目标卡片缩放反馈） */
    private val _pulseTrigger = MutableStateFlow(0)
    val pulseTrigger: StateFlow<Int> = _pulseTrigger.asStateFlow()

    private val flyIdCounter = java.util.concurrent.atomic.AtomicLong(0)

    // ===== 配置文件编辑器 =====
    // M29：方法见 LauncherViewModelConfig.kt
    @PublishedApi internal val _configFiles = MutableStateFlow<List<ConfigFileManager.ConfigFileEntry>>(emptyList())
    val configFiles: StateFlow<List<ConfigFileManager.ConfigFileEntry>> = _configFiles.asStateFlow()

    @PublishedApi internal val _configFileContent = MutableStateFlow<String?>(null)
    val configFileContent: StateFlow<String?> = _configFileContent.asStateFlow()

    @PublishedApi internal val _configFileDirty = MutableStateFlow(false)
    val configFileDirty: StateFlow<Boolean> = _configFileDirty.asStateFlow()

    @PublishedApi internal val _currentConfigPath = MutableStateFlow<String?>(null)
    val currentConfigPath: StateFlow<String?> = _currentConfigPath.asStateFlow()

    @PublishedApi internal val _configCurrentDir = MutableStateFlow("")
    val configCurrentDir: StateFlow<String> = _configCurrentDir.asStateFlow()

    // ===== 模组更新检测 =====
    // M29：方法见 LauncherViewModelModUpdates.kt
    @PublishedApi internal val _modUpdates = MutableStateFlow<List<ModUpdateChecker.UpdateInfo>>(emptyList())
    val modUpdates: StateFlow<List<ModUpdateChecker.UpdateInfo>> = _modUpdates.asStateFlow()

    @PublishedApi internal val _checkingUpdates = MutableStateFlow(false)
    val checkingUpdates: StateFlow<Boolean> = _checkingUpdates.asStateFlow()

    @PublishedApi internal val _updateCheckProgress = MutableStateFlow<Pair<Int, Int>>(0 to 0)
    val updateCheckProgress: StateFlow<Pair<Int, Int>> = _updateCheckProgress.asStateFlow()

    @PublishedApi internal val _updatingMod = MutableStateFlow(false)
    val updatingMod: StateFlow<Boolean> = _updatingMod.asStateFlow()

    /** 更新检测用的 gameVersion（从当前选中版本推断） */
    @PublishedApi internal val _updateGameVersion = MutableStateFlow("")
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

    // ===== 自定义背景（图片/视频，优先级高于视差背景） =====
    private val _launcherBgType = MutableStateFlow(preferences.getLauncherBgType())
    /** 自定义背景类型：none / image / video */
    val launcherBgType: StateFlow<String> = _launcherBgType.asStateFlow()
    private val _launcherBgImagePath = MutableStateFlow(preferences.getLauncherBgImagePath())
    val launcherBgImagePath: StateFlow<String> = _launcherBgImagePath.asStateFlow()
    private val _launcherBgVideoPath = MutableStateFlow(preferences.getLauncherBgVideoPath())
    val launcherBgVideoPath: StateFlow<String> = _launcherBgVideoPath.asStateFlow()

    /** 自定义背景是否实际激活（类型已选且对应路径非空） */
    fun isCustomBackgroundActive(): Boolean = when (_launcherBgType.value) {
        "image" -> _launcherBgImagePath.value.isNotBlank()
        "video" -> _launcherBgVideoPath.value.isNotBlank()
        else -> false
    }

    private fun syncCustomBackgroundToTheme() {
        themeState?.applyCustomBackground(isCustomBackgroundActive())
    }

    fun setLauncherBgType(v: String) {
        preferences.setLauncherBgType(v)
        _launcherBgType.value = v
        syncCustomBackgroundToTheme()
    }
    fun setLauncherBgImagePath(p: String) {
        preferences.setLauncherBgImagePath(p)
        _launcherBgImagePath.value = p
        syncCustomBackgroundToTheme()
    }
    fun setLauncherBgVideoPath(p: String) {
        preferences.setLauncherBgVideoPath(p)
        _launcherBgVideoPath.value = p
        syncCustomBackgroundToTheme()
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
    @PublishedApi internal val _installingDeps = MutableStateFlow(false)
    val installingDeps: StateFlow<Boolean> = _installingDeps.asStateFlow()

    @PublishedApi internal val _depInstallResult = MutableStateFlow<ModDependencyResolver.DependencyResult?>(null)
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
    fun deriveGameVersion(lvi: com.pmcl.core.version.VersionManager.LocalVersionInfo?): String {
        if (lvi == null) return ""
        val inherits = lvi.getInheritsFrom()
        return if (!inherits.isNullOrEmpty()) inherits else lvi.getId()
    }

    /**
     * 推导本地版本对应的 mod 加载器（inheritsFrom + mainClass 关键字）。
     */
    fun deriveLoader(lvi: com.pmcl.core.version.VersionManager.LocalVersionInfo?): String {
        if (lvi == null) return ""
        val inherits = lvi.getInheritsFrom() ?: ""
        val mc = lvi.getMainClass() ?: ""
        return when {
            inherits.contains("neoforge", ignoreCase = true) ||
                mc.contains("neoforge", ignoreCase = true) -> "neoforge"
            inherits.contains("forge", ignoreCase = true) ||
                mc.contains("launchwrapper", ignoreCase = true) ||
                mc.contains("minecraftforge", ignoreCase = true) -> "forge"
            inherits.contains("quilt", ignoreCase = true) ||
                mc.contains("quilt", ignoreCase = true) -> "quilt"
            inherits.contains("fabric", ignoreCase = true) ||
                mc.contains("fabric", ignoreCase = true) -> "fabric"
            else -> ""
        }
    }

    /** 模组市场筛选条件：游戏版本 + 加载器 */
    data class MarketFilters(val gameVersion: String, val loader: String)

    /**
     * 根据当前选中的本地实例推导市场筛选条件。
     * 无选中或原版时 loader 为空（不限加载器）。
     */
    fun resolveMarketFilters(): MarketFilters {
        val selected = _selectedVersion.value ?: return MarketFilters("", "")
        val lvi = _localVersionInfos.value.firstOrNull { it.getId() == selected }
        return MarketFilters(
            gameVersion = deriveGameVersion(lvi),
            loader = deriveLoader(lvi)
        )
    }

    /** 本地已安装实例推导出的可用 MC 版本列表（降序） */
    fun knownMarketGameVersions(): List<String> =
        _localVersionInfos.value
            .map { deriveGameVersion(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareByDescending { it })

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


    // ============ 游戏时长统计 ============

    /** 刷新统计数据（进入统计页时调用） */
    fun refreshPlayTimeStats() {
        try {
            val days = _statsDays.value
            val tracker = core.playTimeTracker()
            _playTimeStats.value = tracker.getOverallStats(days)
            _dailyStats.value = tracker.getDailyStatsWithZeros(days)
            _heatmap.value = tracker.getHeatmap(days)
            _weekdayDist.value = tracker.getWeekdayDistribution(days)
            _records.value = tracker.getRecords()
        } catch (e: Throwable) {
            // 避免 NoClassDefFoundError / 数据损坏时弹出 Compose 致命 Error 对话框
            System.err.println("[PlayTime] 刷新统计失败: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            _status.value = "统计数据加载失败: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 设置统计展示天数（7/14/30）并刷新 */
    fun setStatsDays(days: Int) {
        _statsDays.value = days
        refreshPlayTimeStats()
    }



    // ===== 微软登录（M29 Accounts 扩展访问） =====
    @PublishedApi internal val _deviceCode = MutableStateFlow<DeviceCode?>(null)
    val deviceCode: StateFlow<DeviceCode?> = _deviceCode.asStateFlow()

    @PublishedApi internal val _loggingIn = MutableStateFlow(false)
    val loggingIn: StateFlow<Boolean> = _loggingIn.asStateFlow()

    /** 皮肤管理器实例（懒加载，Accounts 扩展使用） */
    @PublishedApi internal val skinManager: com.pmcl.core.auth.SkinManager by lazy { com.pmcl.core.auth.SkinManager() }


    // ===== 启动日志（M29 Launch 扩展访问） =====
    /** 游戏日志条目（携带单调递增 seq 作为 Compose 列表稳定 key） */
    data class GameLogEntry(val seq: Long, val text: String)
    @PublishedApi internal val gameLogSeq = java.util.concurrent.atomic.AtomicLong(0)
    @PublishedApi internal val _gameLogs = MutableStateFlow<List<GameLogEntry>>(emptyList())
    val gameLogs: StateFlow<List<GameLogEntry>> = _gameLogs.asStateFlow()
    /** 追加一条游戏日志（线程安全，自动裁剪到 2000 条） */
    @PublishedApi
    internal fun appendGameLog(text: String) {
        _gameLogs.update { old -> (old + GameLogEntry(gameLogSeq.incrementAndGet(), text)).takeLast(2000) }
    }
    /** 替换全部游戏日志（切换实例/版本时使用） */
    @PublishedApi
    internal fun setGameLogs(texts: List<String>) {
        _gameLogs.value = texts.map { GameLogEntry(gameLogSeq.incrementAndGet(), it) }
    }

    // ===== 日志导出/分享 =====
    private val _logSharing = MutableStateFlow(false)
    val logSharing: StateFlow<Boolean> = _logSharing.asStateFlow()
    private val _shareUrl = MutableStateFlow<String?>(null)
    val shareUrl: StateFlow<String?> = _shareUrl.asStateFlow()

    @PublishedApi internal val _gameRunning = MutableStateFlow(false)
    val gameRunning: StateFlow<Boolean> = _gameRunning.asStateFlow()

    /** 启动准备互斥：防双击并行构建两套 profile；进程已启动后释放以允许多开 */
    @PublishedApi internal val launchPreparing = java.util.concurrent.atomic.AtomicBoolean(false)

    // ===== 多实例启动（M29 Launch 扩展访问） =====
    data class RunningInstance(
        val id: String,
        val versionId: String,
        val accountName: String,
        val startTime: Long,
        val active: Boolean = false
    )
    @PublishedApi internal val _runningInstances = MutableStateFlow<List<RunningInstance>>(emptyList())
    val runningInstances: StateFlow<List<RunningInstance>> = _runningInstances.asStateFlow()
    // 使用 ConcurrentHashMap 避免多实例并发启动/退出时 put/remove/迭代 导致 ConcurrentModificationException
    // 内层 MutableList 仍用 synchronized(logs) 保护（见日志回调处）
    @PublishedApi internal val instanceLogs = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    @PublishedApi internal val instanceLoggers = java.util.concurrent.ConcurrentHashMap<String, GameLogger?>()

    // ===== 预判启动（M29 Launch 扩展访问） =====
    // 预热策略：不启动 MC 进程（会弹窗口），而是预构建 LaunchProfile + 预热 JVM 页缓存
    // 用户点击启动时，若版本匹配则复用预存的 profile 跳过 build() 阶段
    @PublishedApi @Volatile internal var preheatedProfile: com.pmcl.core.launch.LaunchProfile? = null
    @PublishedApi @Volatile internal var preheatedJavaExe: String = ""
    @PublishedApi @Volatile internal var preheatedVersionId: String = ""
    /** 预热协程句柄：离开启动页时取消，避免晚到写入覆盖 */
    @PublishedApi @Volatile internal var preheatJob: kotlinx.coroutines.Job? = null
    /** 预热代数：cancel / 新一轮预热时递增，完成写入前校验 */
    @PublishedApi internal val preheatGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    @PublishedApi internal val _predictiveState = MutableStateFlow<PredictiveState>(PredictiveState.Idle)
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
    @PublishedApi internal val _compatOptions = MutableStateFlow<List<CompatOption>>(emptyList())
    val compatOptions: StateFlow<List<CompatOption>> = _compatOptions.asStateFlow()
    @PublishedApi internal val _compatTitle = MutableStateFlow("")
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

    // ===== 世界 / 截图 / 资源包 / 光影 / 数据包（M29 Content 扩展访问） =====
    @PublishedApi internal val _worlds = MutableStateFlow<List<WorldManager.WorldInfo>>(emptyList())
    val worlds: StateFlow<List<WorldManager.WorldInfo>> = _worlds.asStateFlow()

    @PublishedApi internal val _screenshots = MutableStateFlow<List<ScreenshotManager.Screenshot>>(emptyList())
    val screenshots: StateFlow<List<ScreenshotManager.Screenshot>> = _screenshots.asStateFlow()

    @PublishedApi internal val _resourcePacks = MutableStateFlow<List<ResourcePackManager.Pack>>(emptyList())
    val resourcePacks: StateFlow<List<ResourcePackManager.Pack>> = _resourcePacks.asStateFlow()

    @PublishedApi internal val _shaderPacks = MutableStateFlow<List<ShaderPackManager.ShaderPack>>(emptyList())
    val shaderPacks: StateFlow<List<ShaderPackManager.ShaderPack>> = _shaderPacks.asStateFlow()

    @PublishedApi internal val _datapacks = MutableStateFlow<List<DatapackManager.Datapack>>(emptyList())
    val datapacks: StateFlow<List<DatapackManager.Datapack>> = _datapacks.asStateFlow()

    @PublishedApi internal val _selectedDatapackWorld = MutableStateFlow<WorldManager.WorldInfo?>(null)
    val selectedDatapackWorld: StateFlow<WorldManager.WorldInfo?> = _selectedDatapackWorld.asStateFlow()

    // ===== 完整性校验 / 崩溃分析 =====
    private val _integrityResult = MutableStateFlow<IntegrityChecker.Result?>(null)
    val integrityResult: StateFlow<IntegrityChecker.Result?> = _integrityResult.asStateFlow()

    @PublishedApi internal val _crashReports = MutableStateFlow<List<CrashAnalyzer.CrashReport>>(emptyList())
    val crashReports: StateFlow<List<CrashAnalyzer.CrashReport>> = _crashReports.asStateFlow()

    /** 游戏异常退出事件（null 表示无崩溃，UI 监听此流弹出崩溃窗口） */
    data class CrashEvent(
        val exitCode: Int,
        val report: CrashAnalyzer.CrashReport?,   // 崩溃报告（可能为 null，如 crash-reports 无新增）
        val recentLogs: List<String>,              // 最近日志片段
        val versionId: String
    )
    @PublishedApi internal val _crashEvent = MutableStateFlow<CrashEvent?>(null)
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
    // M29：方法见 LauncherViewModelNews.kt
    @PublishedApi internal val _newsItems = MutableStateFlow<List<com.pmcl.core.news.NewsItem>>(emptyList())
    val newsItems: StateFlow<List<com.pmcl.core.news.NewsItem>> = _newsItems.asStateFlow()

    @PublishedApi internal val _newsLoading = MutableStateFlow(false)
    val newsLoading: StateFlow<Boolean> = _newsLoading.asStateFlow()

    // 新闻文章详情
    @PublishedApi internal val _articleContent = MutableStateFlow<com.pmcl.core.news.ArticleContent?>(null)
    val articleContent: StateFlow<com.pmcl.core.news.ArticleContent?> = _articleContent.asStateFlow()

    @PublishedApi internal val _articleLoading = MutableStateFlow(false)
    val articleLoading: StateFlow<Boolean> = _articleLoading.asStateFlow()

    @PublishedApi internal val _articleError = MutableStateFlow("")
    val articleError: StateFlow<String> = _articleError.asStateFlow()

    /** 封面图抓取任务（刷新新闻时取消旧任务） */
    @PublishedApi internal var newsImageJob: Job? = null

    // ===== 翻译缓存（key = 原文，value = 译文）=====
    private val _translationCache = MutableStateFlow<Map<String, String>>(emptyMap())
    val translationCache: StateFlow<Map<String, String>> = _translationCache.asStateFlow()

    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating.asStateFlow()
    /** 并发翻译计数器：>0 时 _translating 为 true，用于 UI 显示「翻译中…」 */
    private val translateCounter = java.util.concurrent.atomic.AtomicInteger(0)

    // ===== 多人联机 =====
    // M29：方法见 LauncherViewModelMultiplayer.kt
    @PublishedApi internal val _mpState = MutableStateFlow<com.pmcl.core.multiplayer.MultiplayerManager.State>(
        com.pmcl.core.multiplayer.MultiplayerManager.State.IDLE
    )
    val mpState: StateFlow<com.pmcl.core.multiplayer.MultiplayerManager.State> = _mpState.asStateFlow()

    @PublishedApi internal val _mpProgress = MutableStateFlow("")
    val mpProgress: StateFlow<String> = _mpProgress.asStateFlow()

    @PublishedApi internal val _mpVirtualIp = MutableStateFlow("")
    val mpVirtualIp: StateFlow<String> = _mpVirtualIp.asStateFlow()

    @PublishedApi internal val _mpInvitation = MutableStateFlow("")
    val mpInvitation: StateFlow<String> = _mpInvitation.asStateFlow()

    /** Terracotta 房客模式：本地 MC 连接地址（如 127.0.0.1:25565） */
    @PublishedApi internal val _mpLocalMcAddr = MutableStateFlow("")
    val mpLocalMcAddr: StateFlow<String> = _mpLocalMcAddr.asStateFlow()

    // 陶瓦联机错误信息（供 UI 在失败时展示）
    val mpLastError: String get() = core.multiplayer().lastError

    @PublishedApi internal fun resolveMpBackend(name: String?): com.pmcl.core.multiplayer.MultiplayerManager.Backend =
        when (name) {
            "CONNECTX" -> com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX
            "EASYTIER" -> com.pmcl.core.multiplayer.MultiplayerManager.Backend.EASYTIER
            else -> com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA
        }

    @PublishedApi internal val _mpBackend = MutableStateFlow(resolveMpBackend(preferences.getMpBackend()))

    /** 当前联机后端（可观察，切换后 UI 会重组） */
    val mpBackendState: StateFlow<com.pmcl.core.multiplayer.MultiplayerManager.Backend> =
        _mpBackend.asStateFlow()

    /** 当前联机后端（同步读取） */
    val mpBackend: com.pmcl.core.multiplayer.MultiplayerManager.Backend
        get() = _mpBackend.value

    /** 服务器列表数据项 */
    data class FavoriteServer(val name: String, val host: String, val port: Int)

    /** ping 结果：key = "host:port"，value = 延迟毫秒（-1 不可达，-2 超时） */
    @PublishedApi internal val _serverPings = MutableStateFlow<Map<String, Long>>(emptyMap())
    val serverPings: StateFlow<Map<String, Long>> = _serverPings.asStateFlow()

    /** 服务器列表（可观察） */
    @PublishedApi internal val _favoriteServers = MutableStateFlow<List<FavoriteServer>>(emptyList())
    val favoriteServers: StateFlow<List<FavoriteServer>> = _favoriteServers.asStateFlow()

    /** 服务器完整状态（可观察），key = "host:port" */
    @PublishedApi internal val _serverStatuses =
        MutableStateFlow<Map<String, com.pmcl.core.multiplayer.ServerPinger.ServerStatus>>(emptyMap())
    val serverStatuses: StateFlow<Map<String, com.pmcl.core.multiplayer.ServerPinger.ServerStatus>> =
        _serverStatuses.asStateFlow()

    /** 正在 ping 中的服务器集合（key = "host:port"） */
    @PublishedApi internal val _pingingServers = MutableStateFlow<Set<String>>(emptySet())
    val pingingServers: StateFlow<Set<String>> = _pingingServers.asStateFlow()


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
    @PublishedApi internal var gameLogger: GameLogger? = null

    val systemInfo: String
        get() = with(core.runtime()) {
            "OS: ${getOsName()}  |  内存: ${getAvailableMemoryMb()}/${getTotalMemoryMb()} MB  |  推荐: ${getRecommendedMaxMemoryMb()} MB"
        }

    val config: LauncherConfig get() = core.getConfig()
    val preferences: Preferences get() = core.getPreferences()

    /** mods 目录扫描缓存：key=目录路径, value=[mtime, 扫描结果] */
    // M30 / M29：类型安全缓存；扩展函数需访问故 PublishedApi
    @PublishedApi internal data class ModScanCacheEntry(val dirMtime: Long, val mods: List<ModMeta>)
    @PublishedApi internal val modScanCache = java.util.concurrent.ConcurrentHashMap<Path, ModScanCacheEntry>()

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
        // 初始化联机后端（勿读 mpBackend：_mpBackend 声明在 init 之后，此时尚未赋值）
        core.multiplayer().setBackend(
            when (preferences.getMpBackend()) {
                "CONNECTX" -> com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX
                "EASYTIER" -> com.pmcl.core.multiplayer.MultiplayerManager.Backend.EASYTIER
                else -> com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA
            }
        )
        syncConnectXConfig()
        // 启动时应用网络偏好（含 Java 全局代理系统属性，让头像/皮肤图片下载能走代理）
        core.applyNetworkPreferences()
        // 全局注册下载队列监听：悬浮队列卡片不依赖进入下载页也能刷新进度
        initDownloadQueue()
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
            } catch (t: Throwable) {
                System.err.println("[VM] 加载音乐播放列表失败: ${t.message}")
                _status.value = I18n.t("music.playlist_load_failed", t.message ?: I18n.t("common.unknown"))
            }
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


    fun refreshVersions() {
        scope.launch {
            _loading.value = true
            _status.value = I18n.t("status.fetching_version_manifest")
            // 先读缓存秒开
            val cached = withContext(Dispatchers.IO) {
                DataCache.loadWithTimestamp("versions_remote_v2", object : TypeToken<List<McVersion>>() {})
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
                            DataCache.save("versions_remote_v2", list)
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
                DataCache.save("versions_remote_v2", list)
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
     * 应用主题色彩预设。
     * 切换预设时清除莫奈取色和自定义强调色，由 Theme.kt 根据预设生成配色。
     */
    fun applyThemePreset(preset: String, targetThemeState: com.pmcl.ui.theme.ThemeState? = null) {
        val ts = targetThemeState ?: themeState ?: return
        // 清除动态配色与自定义色，让 Theme.kt 回退到预设方案
        ts.enableDynamicColor(false)
        ts.clearCustomAccentColor()
        ts.updateDynamicColorScheme(null)
        preferences.setDynamicColor(false)
        preferences.setCustomAccentColor(-1)
        ts.applyThemePreset(preset)
        preferences.setThemePreset(preset)
        _status.value = I18n.t("status.theme_preset_applied")
    }

    /** 应用特殊色彩模式（normal/amoled/high_contrast/soft） */
    fun applyColorMode(mode: String, targetThemeState: com.pmcl.ui.theme.ThemeState? = null) {
        val ts = targetThemeState ?: themeState ?: return
        ts.applyColorMode(mode)
        preferences.setColorMode(mode)
        _status.value = I18n.t("status.color_mode_applied")
    }

    /**
     * 应用插件主题包。
     * - packId 为空：清除插件主题，回退到预设/默认
     * - packId 非空但找不到对应插件主题包：清除并提示
     * - packId 非空且找到：应用插件主题，覆盖莫奈/自定义色/预设
     */
    fun applyCustomThemePack(packId: String, targetThemeState: com.pmcl.ui.theme.ThemeState? = null) {
        val ts = targetThemeState ?: themeState ?: return
        if (packId.isEmpty()) {
            ts.applyCustomThemePack(null)
            preferences.setCustomThemePackId("")
            _status.value = I18n.t("status.theme_pack_cleared")
            return
        }
        val pack = core.plugins().findThemePack(packId)
        if (pack == null) {
            // 插件未加载或已禁用，清除主题包设置并提示
            ts.applyCustomThemePack(null)
            preferences.setCustomThemePackId("")
            _status.value = I18n.t("status.theme_pack_not_found")
            return
        }
        // 清除莫奈/自定义色/预设（插件主题优先级最高）
        ts.enableDynamicColor(false)
        ts.clearCustomAccentColor()
        ts.updateDynamicColorScheme(null)
        preferences.setDynamicColor(false)
        preferences.setCustomAccentColor(-1)
        ts.applyCustomThemePack(pack)
        preferences.setCustomThemePackId(packId)
        _status.value = I18n.t("status.theme_pack_applied")
    }

    /**
     * 检查持久化的 customThemePackId 是否仍可用，若不可用则清除。
     * 在插件加载完成后调用。
     */
    fun reconcileCustomThemePack(targetThemeState: com.pmcl.ui.theme.ThemeState? = null) {
        val ts = targetThemeState ?: themeState ?: return
        val packId = preferences.getCustomThemePackId()
        if (packId.isNotEmpty()) {
            val pack = core.plugins().findThemePack(packId)
            if (pack != null) {
                ts.applyCustomThemePack(pack)
            } else {
                // 插件已卸载或禁用，清除持久化记录
                ts.applyCustomThemePack(null)
                preferences.setCustomThemePackId("")
            }
        }
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


    /** 在系统文件管理中打开指定目录（内容包 / 模组页共用） */
    @PublishedApi
    internal fun openDir(dir: java.io.File) {
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

    /** 注册队列监听器并刷新任务列表（全局悬浮卡片与下载页共用，幂等） */
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
     * 与主 launch() 对齐：多实例列表 + finally 重置 running，避免异常后 UI 卡在「运行中」。
     */
    fun launchWithSpecificJava(versionId: String, javaPath: String, javaMajorVer: Int, javaArch: String) {
        dismissCompatOptions()
        if (!launchPreparing.compareAndSet(false, true)) {
            _status.value = I18n.t("status.launch_busy")
            return
        }
        scope.launch {
            _status.value = I18n.t("status.launching_with_specific_java")
            var timeTracked = false
            var instanceId: String? = null
            try {
                val account = _launchAccountOverride ?: _account.value
                if (account == null) {
                    _status.value = I18n.t("status.login_first")
                    return@launch
                }
                val profile = withContext(Dispatchers.IO) {
                    core.profileBuilder().build(versionId, account, javaMajorVer, javaArch)
                }
                instanceId = "${versionId}_compat_${System.currentTimeMillis()}"
                val logFile = config.getWorkDir().resolve("logs").resolve("$instanceId.log")
                val instLogger = withContext(Dispatchers.IO) {
                    try { GameLogger(logFile) } catch (e: Throwable) {
                        appendGameLog(I18n.t("status.game_log_create_failed", e.message ?: I18n.t("common.unknown")))
                        null
                    }
                }
                instanceLoggers[instanceId] = instLogger
                gameLogger = instLogger
                val initLogs = mutableListOf(
                    "[PMCL] 使用外部 Java 启动: $javaPath",
                    "[PMCL] Java 版本: $javaMajorVer 架构: $javaArch",
                    ""
                )
                instanceLogs[instanceId] = initLogs
                setGameLogs(initLogs.toList())
                _runningInstances.update { list ->
                    list.map { it.copy(active = false) } + RunningInstance(
                        id = instanceId!!,
                        versionId = versionId,
                        accountName = account.username,
                        startTime = System.currentTimeMillis(),
                        active = true
                    )
                }
                _gameRunning.value = true
                _status.value = I18n.t("status.launching", javaPath, javaMajorVer, javaArch, versionId)
                val sessionModIds = _installedMods.value.mapNotNull { it.getModId().takeIf(String::isNotEmpty) }
                core.playTimeTracker().recordStart(versionId, instanceId ?: "", sessionModIds)
                timeTracked = true
                preferences.recordRecentVersion(versionId)
                preferences.setLastPlayedTime(versionId, System.currentTimeMillis())
                _recentVersions.value = preferences.getRecentVersions()
                _lastPlayedTimes.value = HashMap(preferences.getLastPlayedTimesRaw())
                val future = core.launch().launchAsync(
                    profile, javaPath,
                    { line ->
                        instanceLogs[instanceId]?.let { logs ->
                            synchronized(logs) {
                                logs.add(line)
                                if (logs.size > 2000) logs.subList(0, logs.size - 2000).clear()
                            }
                        }
                        if (_runningInstances.value.any { it.id == instanceId && it.active }) {
                            appendGameLog(line)
                        }
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
                    instLogger
                )
                launchPreparing.set(false)
                val exitCode = withContext(Dispatchers.IO) { future.join() }
                _status.value = I18n.t("status.game_exited", exitCode)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _status.value = I18n.t("status.launch_failed", e.message ?: I18n.t("common.unknown"))
                appendGameLog("启动失败: ${e.message}")
            } finally {
                launchPreparing.set(false)
                if (timeTracked) {
                    core.playTimeTracker().recordEnd(versionId)
                }
                clearLaunchInstanceContext()
                instanceId?.let { id ->
                    _runningInstances.update { list ->
                        val remaining = list.filter { it.id != id }
                        if (remaining.isNotEmpty() && !remaining.any { it.active }) {
                            remaining.mapIndexed { idx, inst ->
                                if (idx == remaining.lastIndex) inst.copy(active = true) else inst
                            }
                        } else remaining
                    }
                    val activeInst = _runningInstances.value.firstOrNull { it.active }
                    if (activeInst != null) {
                        setGameLogs(instanceLogs[activeInst.id]?.let { logs ->
                            synchronized(logs) { logs.toList() }
                        } ?: emptyList())
                    }
                    _gameRunning.value = _runningInstances.value.isNotEmpty()
                    instanceLoggers.remove(id)?.close()
                    instanceLogs.remove(id)
                    gameLogger = instanceLoggers.values.lastOrNull()
                } ?: run {
                    _gameRunning.value = _runningInstances.value.isNotEmpty()
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
                setGameLogs(listOf(
                    "[PMCL] 正在用 ${launcher.name} 启动版本 $versionId",
                    "[PMCL] 命令: ${cmd.joinToString(" ")}",
                    ""
                ))
                withContext(Dispatchers.IO) {
                    val workDir = java.io.File(launcher.gameDir).let {
                        if (it.isDirectory) it else java.io.File(System.getProperty("user.home"))
                    }
                    val proc = ProcessBuilder(cmd).directory(workDir)
                        .redirectErrorStream(true).start()
                    // 纳入 LaunchManager 生命周期，应用退出时可强制清理
                    core.launch().trackExternalProcess(proc)
                    // 消费合并输出流，防止管道缓冲区满导致进程挂起
                    Thread({
                        try {
                            proc.inputStream.use { it.readBytes() }
                        } catch (_: Throwable) {
                        } finally {
                            core.launch().untrackExternalProcess(proc)
                        }
                    }, "ext-launcher-drain").apply { isDaemon = true }.start()
                }
                _status.value = I18n.t("status.external_launcher_opened", launcher.name, versionId)
            } catch (e: Throwable) {
                _status.value = I18n.t("status.external_launcher_failed", launcher.name, e.message ?: I18n.t("common.unknown"))
                setGameLogs(listOf("打开 ${launcher.name} 失败: ${e.message}"))
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
        val trimmed = path.trim()
        if (trimmed.isNotEmpty()) {
            val file = java.io.File(trimmed)
            val name = file.name.lowercase()
            val looksLikeJava = name == "java" || name == "java.exe" || name == "javaw.exe"
            if (!file.isFile || !looksLikeJava) {
                _status.value = I18n.t("status.java_path_invalid", trimmed)
                return
            }
            if (!file.canExecute() && !name.endsWith(".exe")) {
                _status.value = I18n.t("status.java_path_invalid", trimmed)
                return
            }
        }
        preferences.setJavaPath(trimmed)
        _status.value = if (trimmed.isEmpty()) I18n.t("status.java_path_reset") else I18n.t("status.java_path_set", trimmed)
    }

    // ============ 下载飞入动画 ============

    /**
     * 触发下载飞入动画。
     *
     * **下载入队在此处立即执行**，动画仅作视觉反馈。
     * 这样即使动画被中断（页面切换、窗口关闭），下载也不会丢失。
     *
     * @param sourceRect 源卡片在窗口中的位置
     * @param title      飞行卡片上显示的标题
     * @param onDone     实际入队下载的回调（立即执行）
     */
    fun triggerFlyAnimation(
        sourceRect: com.pmcl.ui.animation.Rect,
        title: String,
        onDone: () -> Unit
    ) {
        // 立即执行下载入队，不依赖动画完成
        try { onDone() } catch (e: Throwable) {
            _status.value = I18n.t("status.download_failed", e.message ?: I18n.t("common.unknown"))
        }
        // 启动纯视觉飞入动画
        val target = downloadQueueRect ?: return
        val id = flyIdCounter.incrementAndGet()
        val anim = com.pmcl.ui.animation.DownloadFlyState(
            id = id,
            source = sourceRect,
            target = target,
            title = title
        )
        _flyAnimations.update { it + anim }
    }

    /**
     * 飞入动画完成回调：移除动画、触发脉冲反馈（纯视觉，不执行下载）。
     */
    fun completeFlyAnimation(anim: com.pmcl.ui.animation.DownloadFlyState) {
        _flyAnimations.update { list -> list.filter { it.id != anim.id } }
        _pulseTrigger.update { it + 1 }
    }

    /** 上报悬浮下载队列卡片的窗口坐标 */
    fun updateDownloadQueueRect(rect: com.pmcl.ui.animation.Rect) {
        downloadQueueRect = rect
    }

    /** 清除目标坐标（悬浮卡片卸载时调用，避免飞向幽灵位置） */
    fun clearDownloadQueueRect() {
        downloadQueueRect = null
    }

    // ============ Metal 渲染（Apple Silicon Mac 专用）============

    /** MetalRender 开关状态（UI 观察），乐观更新 + 失败回滚 */
    private val _metalRenderEnabled = MutableStateFlow(preferences.isMetalRenderEnabled())
    val metalRenderEnabled: StateFlow<Boolean> = _metalRenderEnabled.asStateFlow()

    /** 检测当前是否为 Apple Silicon Mac */
    fun isMetalRenderSupported(): Boolean {
        return com.pmcl.core.metal.MetalRenderInstaller.isAppleSiliconMac()
    }

    /** 检查 MetalRender mod 是否已安装 */
    fun isMetalRenderInstalled(): Boolean {
        return try { core.metalRender().isInstalled } catch (e: Throwable) { false }
    }

    /**
     * 切换 Metal 渲染开关。
     * - 开启：从 Modrinth 下载 MetalRender + Sodium + Fabric API + ModMenu 到 mods 目录
     * - 关闭：从 mods 目录删除上述 mod
     *
     * 使用乐观更新：先更新 UI 状态，安装/卸载成功后持久化到 preferences；
     * 失败时回滚 UI 状态，不修改 preferences，避免 UI 与持久化状态不一致。
     *
     * @param gameVersion 目标 MC 版本（从当前选中版本取）
     * @param loader      加载器（默认 fabric）
     */
    fun toggleMetalRender(gameVersion: String?, loader: String = "fabric") {
        val enable = !preferences.isMetalRenderEnabled()
        // 乐观更新 UI 状态
        _metalRenderEnabled.value = enable
        scope.launch {
            try {
                if (enable) {
                    val gv = gameVersion ?: _selectedVersion.value
                    if (gv.isNullOrEmpty()) {
                        _status.value = I18n.t("metal.no_version_selected")
                        // 无版本选择也算失败，回滚
                        _metalRenderEnabled.value = !enable
                        return@launch
                    }
                    _status.value = I18n.t("metal.installing")
                    withContext(Dispatchers.IO) {
                        core.metalRender().install(gv, loader) { progress ->
                            _status.value = I18n.t("metal.downloading", progress)
                        }
                    }
                    preferences.setMetalRenderEnabled(true)
                    _status.value = I18n.t("metal.install_success")
                } else {
                    _status.value = I18n.t("metal.uninstalling")
                    val deleted = withContext(Dispatchers.IO) { core.metalRender().uninstall() }
                    preferences.setMetalRenderEnabled(false)
                    _status.value = I18n.t("metal.uninstall_success", deleted.size)
                }
            } catch (e: Throwable) {
                // 失败时回滚 UI 状态，preferences 保持不变
                _metalRenderEnabled.value = !enable
                _status.value = I18n.t("metal.install_failed", e.message ?: I18n.t("common.unknown"))
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
                append(logs.map { it.text }.joinToString("\n"))
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
                val content = redactSensitiveLogContent(logs.map { it.text }.joinToString("\n"))
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

    /** 分享前脱敏：accessToken / session / Bearer 等常见凭据。 */
    private fun redactSensitiveLogContent(raw: String): String {
        var s = raw
        val patterns = listOf(
            Regex("""(?i)(access[_-]?token\s*[=:]\s*)\S+""") to "$1***",
            Regex("""(?i)(session\s*[=:]\s*)\S+""") to "$1***",
            Regex("""(?i)(authorization\s*:\s*bearer\s+)\S+""") to "$1***",
            Regex("""(?i)(x-auth-token\s*[=:]\s*)\S+""") to "$1***",
            Regex("""(?i)(client[_-]?secret\s*[=:]\s*)\S+""") to "$1***",
            Regex("""(?i)(refresh[_-]?token\s*[=:]\s*)\S+""") to "$1***",
            Regex("""(?i)--accessToken\s+\S+""") to "--accessToken ***",
            Regex("""(?i)--uuid\s+[0-9a-f-]{32,36}""") to "--uuid ***",
        )
        for ((re, rep) in patterns) {
            s = re.replace(s, rep)
        }
        return s
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

    // ============ 独立实例管理 ============

    private val _instances = MutableStateFlow<List<InstanceInfo>>(emptyList())
    val instances: StateFlow<List<InstanceInfo>> = _instances.asStateFlow()

    private val _instanceLaunching = MutableStateFlow<String?>(null)
    val instanceLaunching: StateFlow<String?> = _instanceLaunching.asStateFlow()

    // 实例启动上下文：launch() 读取此字段决定是否按实例模式启动
    @PublishedApi @Volatile internal var _pendingInstanceDir: java.nio.file.Path? = null
    @PublishedApi @Volatile internal var _pendingInstanceInfo: InstanceInfo? = null

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
        // 实例绑定账户：仅覆盖本次 launch，不改动全局选中账号
        val boundUuid = info.getBoundAccountUuid()
        _launchAccountOverride = if (boundUuid.isNotEmpty()) {
            _accounts.value.find { it.getUuid() == boundUuid }
        } else null
        // 设置实例上下文，launch() 会读取此字段用 buildInstance 代替 build
        _pendingInstanceDir = info.getInstanceDir()
        _pendingInstanceInfo = info
        // 选中基础版本并调用现有 launch 流程
        selectVersion(info.getBaseVersionId())
        launch()
    }

    /** 清除实例启动上下文与单次账户覆盖 */
    @PublishedApi
    internal fun clearLaunchInstanceContext() {
        _pendingInstanceDir = null
        _pendingInstanceInfo = null
        _launchAccountOverride = null
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
