package com.lash.pmcl.core.preferences

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.util.FileUtils
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 启动器偏好设置持久化（JSON 文件）— Android 精简版。
 *
 * 桌面版字段众多（窗口边框、视差背景、Mio Mode、Metal 渲染、设备绑定等），
 * Android 上无意义或不可用，本类只保留移动端真正使用的子集：
 * - 主题/语言
 * - 下载/网络（镜像、代理、限速、断点续传、分片线程数）
 * - 版本管理（pinned/recent/lastPlayed）
 * - JVM 启动参数（min/max memory、customJvmArgs、gcType）
 * - 首次启动 / 协议同意标志
 *
 * 所有公共方法均 synchronized，保证 UI 线程与后台协程并发安全。
 * 写入采用防抖（200ms），连续修改只触发一次磁盘 IO。
 */
class Preferences(
    private val paths: PmclPaths
) {

    private val file: Path = paths.preferences
    private val gson: Gson = Gson()

    // ===== 主题 / 语言 =====
    private var useDarkTheme: Boolean = false
    private var dynamicColor: Boolean = false          // Android 12+ Material You 取色
    private var customAccentColor: Int = -1            // 自定义强调色 ARGB，-1 表示未设置
    private var themePreset: String = "default"        // default/ocean/forest/sunset/lavender/sakura/midnight
    private var colorMode: String = "normal"           // normal/amoled/high_contrast/soft
    private var glassTheme: Boolean = false            // 玻璃主题
    private var lockscreenLaunchTheme: Boolean = false // Origin OS2 锁屏启动页
    private var uiScale: Float = 1.0f                  // UI 缩放 0.75~1.5
    private var language: String = "zh_CN"             // zh_CN / en_US 等

    // ===== 游戏通用行为 =====
    private var versionIsolation: Boolean = false       // 版本隔离（独立存档/配置目录）
    private var gameResolution: String = ""             // 窗口分辨率 "WxH"，空=默认
        private var gameRenderer: String = "zink"           // Zink = OpenGL via Vulkan（移动端推荐）
    private var gameFullscreen: Boolean = true           // 全屏启动（移动端默认 true）
    private var gameDemoMode: Boolean = false            // Demo 模式
    private var gameCustomIcon: String = ""              // 自定义窗口图标路径（移动端暂不生效）
    private var gameBgVideo: String = ""                 // 自定义主菜单背景视频路径（移动端暂不生效）
    private var gameCustomNatives: String = ""           // 自定义 Natives 路径（移动端暂不生效）

    // ===== 首次启动 =====
    private var firstLaunchCompleted: Boolean = false
    private var agreementAccepted: Boolean = false

    // ===== 版本管理 =====
    private var pinnedVersions: MutableList<String> = ArrayList()
    private var recentVersions: MutableList<String> = ArrayList()  // LRU，最多 5 个
    private var lastSelectedVersion: String = ""
    private var lastOfflineUsername: String = ""
    private var lastPlayedTimes: MutableMap<String, Long> = HashMap()        // versionId → epoch millis
    private var pinnedTileLabels: MutableMap<String, String> = HashMap()     // versionId → 自定义磁贴名称

    // ===== 每版本 Java 路径 / 服务器直连 =====
    private var versionJavaPaths: MutableMap<String, String> = HashMap()
    private var gameServerHost: String = ""
    private var gameServerPort: Int = 25565

    // ===== JVM / 启动 =====
    private var customJvmArgs: String = ""
    private var gcType: String = "G1GC"
    private var useAikarFlags: Boolean = true
    private var minMemoryMb: Int = 512
    private var maxMemoryMb: Int = 2048     // Android 设备内存通常较小，默认 2GB

    // ===== 网络配置 =====
    private var mirrorType: String = "BMCLAPI"         // OFFICIAL / BMCLAPI / CUSTOM（国内默认 BMCLAPI）
    private var customMirrorBase: String = ""
    private var useProxy: Boolean = false
    private var proxyHost: String = ""
    private var proxyPort: Int = 0
    private var useHttpAuth: Boolean = false
    private var proxyUsername: String = ""
    private var proxyPassword: String = ""
    private var downloadSpeedLimitKb: Int = 0           // 0 = 不限速
    private var downloadRetryCount: Int = 3
    private var enableResume: Boolean = true
    private var chunkedDownloadThreads: Int = 4
    private var downloadThreads: Int = 8                // Android 上默认 8（桌面 16）

    // ===== GitHub 同步更新 =====
    private var githubSyncEnabled: Boolean = false
    private var githubRepo: String = ""

    // ===== 防抖磁盘写入 =====
    @Transient
    @Volatile
    private var dirty: Boolean = false

    @Transient
    private val saveExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "pmcl-prefs-writer").apply { isDaemon = true }
    }

    @Transient
    @Volatile
    private var pendingSave: ScheduledFuture<*>? = null

    init {
        load()
        Runtime.getRuntime().addShutdownHook(Thread(this::shutdown, "pmcl-prefs-shutdown"))
    }

    // ==================== 主题 / 语言 ====================
    @Synchronized fun isUseDarkTheme(): Boolean = useDarkTheme
    @Synchronized fun setUseDarkTheme(v: Boolean) { useDarkTheme = v; scheduleSave() }

    @Synchronized fun isDynamicColor(): Boolean = dynamicColor
    @Synchronized fun setDynamicColor(v: Boolean) { dynamicColor = v; scheduleSave() }

    @Synchronized fun getCustomAccentColor(): Int = customAccentColor
    @Synchronized fun setCustomAccentColor(v: Int) { customAccentColor = v; scheduleSave() }

    @Synchronized fun getThemePreset(): String = themePreset
    @Synchronized fun setThemePreset(v: String) { themePreset = v ?: "default"; scheduleSave() }

    @Synchronized fun getColorMode(): String = colorMode
    @Synchronized fun setColorMode(v: String) { colorMode = v ?: "normal"; scheduleSave() }

    @Synchronized fun isGlassTheme(): Boolean = glassTheme
    @Synchronized fun setGlassTheme(v: Boolean) { glassTheme = v; scheduleSave() }

    @Synchronized fun isLockscreenLaunchTheme(): Boolean = lockscreenLaunchTheme
    @Synchronized fun setLockscreenLaunchTheme(v: Boolean) { lockscreenLaunchTheme = v; scheduleSave() }

    @Synchronized fun getUiScale(): Float = uiScale
    @Synchronized fun setUiScale(v: Float) { uiScale = v.coerceIn(0.75f, 1.5f); scheduleSave() }

    @Synchronized fun isVersionIsolation(): Boolean = versionIsolation
    @Synchronized fun setVersionIsolation(v: Boolean) { versionIsolation = v; scheduleSave() }

    @Synchronized fun getGameResolution(): String = gameResolution
    @Synchronized fun setGameResolution(v: String) { gameResolution = v ?: ""; scheduleSave() }

    @Synchronized fun getGameRenderer(): String = gameRenderer
    @Synchronized fun setGameRenderer(v: String) { gameRenderer = v ?: "zink"; scheduleSave() }

    @Synchronized fun isGameFullscreen(): Boolean = gameFullscreen
    @Synchronized fun setGameFullscreen(v: Boolean) { gameFullscreen = v; scheduleSave() }

    @Synchronized fun isGameDemoMode(): Boolean = gameDemoMode
    @Synchronized fun setGameDemoMode(v: Boolean) { gameDemoMode = v; scheduleSave() }

    @Synchronized fun getGameCustomIcon(): String = gameCustomIcon
    @Synchronized fun setGameCustomIcon(v: String) { gameCustomIcon = v ?: ""; scheduleSave() }

    @Synchronized fun getGameBgVideo(): String = gameBgVideo
    @Synchronized fun setGameBgVideo(v: String) { gameBgVideo = v ?: ""; scheduleSave() }

    @Synchronized fun getGameCustomNatives(): String = gameCustomNatives
    @Synchronized fun setGameCustomNatives(v: String) { gameCustomNatives = v ?: ""; scheduleSave() }

    @Synchronized fun getLanguage(): String = language
    @Synchronized fun setLanguage(v: String) { language = v ?: "zh_CN"; scheduleSave() }

    // ==================== 首次启动 ====================
    @Synchronized fun isFirstLaunchCompleted(): Boolean = firstLaunchCompleted
    @Synchronized fun setFirstLaunchCompleted(v: Boolean) { firstLaunchCompleted = v; scheduleSave() }

    @Synchronized fun isAgreementAccepted(): Boolean = agreementAccepted
    @Synchronized fun setAgreementAccepted(v: Boolean) { agreementAccepted = v; scheduleSave() }

    // ==================== 版本管理 ====================
    /** 返回固定版本列表的副本（避免外部修改触发 StateFlow 引用比较失效） */
    @Synchronized fun getPinnedVersions(): List<String> = ArrayList(pinnedVersions)

    @Synchronized fun setPinnedVersions(v: List<String>) {
        pinnedVersions = ArrayList(v); scheduleSave()
    }

    @Synchronized fun pinVersion(versionId: String) {
        if (!pinnedVersions.contains(versionId)) {
            pinnedVersions.add(versionId); scheduleSave()
        }
    }

    @Synchronized fun unpinVersion(versionId: String) {
        pinnedVersions.remove(versionId)
        pinnedTileLabels.remove(versionId)
        scheduleSave()
    }

    @Synchronized fun isPinned(versionId: String): Boolean = pinnedVersions.contains(versionId)

    @Synchronized fun getPinnedTileLabel(versionId: String): String? = pinnedTileLabels[versionId]

    @Synchronized fun setPinnedTileLabel(versionId: String, label: String?) {
        if (label.isNullOrEmpty()) {
            pinnedTileLabels.remove(versionId)
        } else {
            pinnedTileLabels[versionId] = label
        }
        scheduleSave()
    }

    /** 返回最近使用版本列表的副本 */
    @Synchronized fun getRecentVersions(): List<String> = ArrayList(recentVersions)

    @Synchronized fun recordRecentVersion(versionId: String) {
        recentVersions.remove(versionId)
        recentVersions.add(0, versionId)
        while (recentVersions.size > MAX_RECENT) {
            recentVersions.removeAt(recentVersions.size - 1)
        }
        scheduleSave()
    }

    @Synchronized fun getLastSelectedVersion(): String = lastSelectedVersion
    @Synchronized fun setLastSelectedVersion(v: String) { lastSelectedVersion = v ?: ""; scheduleSave() }

    @Synchronized fun getLastOfflineUsername(): String = lastOfflineUsername
    @Synchronized fun setLastOfflineUsername(v: String) { lastOfflineUsername = v ?: ""; scheduleSave() }

    @Synchronized fun getLastPlayedTime(versionId: String): Long = lastPlayedTimes[versionId] ?: 0L

    @Synchronized fun setLastPlayedTime(versionId: String, epochMillis: Long) {
        lastPlayedTimes[versionId] = epochMillis; scheduleSave()
    }

    @Synchronized fun getAllLastPlayedTimes(): Map<String, Long> = HashMap(lastPlayedTimes)

    // ==================== JVM / 启动 ====================
    @Synchronized fun getCustomJvmArgs(): String = customJvmArgs
    @Synchronized fun setCustomJvmArgs(v: String) { customJvmArgs = v ?: ""; scheduleSave() }

    @Synchronized fun getGcType(): String = gcType
    @Synchronized fun setGcType(v: String) { gcType = v ?: "G1GC"; scheduleSave() }

    @Synchronized fun isUseAikarFlags(): Boolean = useAikarFlags
    @Synchronized fun setUseAikarFlags(v: Boolean) { useAikarFlags = v; scheduleSave() }

    @Synchronized fun getMinMemoryMb(): Int = minMemoryMb
    @Synchronized fun setMinMemoryMb(v: Int) { minMemoryMb = v.coerceAtLeast(256); scheduleSave() }

    @Synchronized fun getMaxMemoryMb(): Int = maxMemoryMb
    @Synchronized fun setMaxMemoryMb(v: Int) { maxMemoryMb = v.coerceAtLeast(512); scheduleSave() }

    // ==================== 每版本 Java 路径 / 服务器直连 ====================
    @Synchronized fun getVersionJavaPath(versionId: String): String =
        versionJavaPaths[versionId] ?: ""

    @Synchronized fun setVersionJavaPath(versionId: String, path: String) {
        if (path.isEmpty()) versionJavaPaths.remove(versionId)
        else versionJavaPaths[versionId] = path
        scheduleSave()
    }

    @Synchronized fun getGameServerHost(): String = gameServerHost
    @Synchronized fun setGameServerHost(v: String) { gameServerHost = v ?: ""; scheduleSave() }

    @Synchronized fun getGameServerPort(): Int = gameServerPort
    @Synchronized fun setGameServerPort(v: Int) { gameServerPort = v; scheduleSave() }

    // ==================== 网络配置 ====================
    @Synchronized fun getMirrorType(): String = mirrorType
    @Synchronized fun setMirrorType(v: String) { mirrorType = v ?: "BMCLAPI"; scheduleSave() }

    @Synchronized fun getCustomMirrorBase(): String = customMirrorBase
    @Synchronized fun setCustomMirrorBase(v: String) { customMirrorBase = v ?: ""; scheduleSave() }

    @Synchronized fun isUseProxy(): Boolean = useProxy
    @Synchronized fun setUseProxy(v: Boolean) { useProxy = v; scheduleSave() }

    @Synchronized fun getProxyHost(): String = proxyHost
    @Synchronized fun setProxyHost(v: String) { proxyHost = v ?: ""; scheduleSave() }

    @Synchronized fun getProxyPort(): Int = proxyPort
    @Synchronized fun setProxyPort(v: Int) { proxyPort = v; scheduleSave() }

    @Synchronized fun isUseHttpAuth(): Boolean = useHttpAuth
    @Synchronized fun setUseHttpAuth(v: Boolean) { useHttpAuth = v; scheduleSave() }

    @Synchronized fun getProxyUsername(): String = proxyUsername
    @Synchronized fun setProxyUsername(v: String) { proxyUsername = v ?: ""; scheduleSave() }

    @Synchronized fun getProxyPassword(): String = proxyPassword
    @Synchronized fun setProxyPassword(v: String) { proxyPassword = v ?: ""; scheduleSave() }

    @Synchronized fun getDownloadSpeedLimitKb(): Int = downloadSpeedLimitKb
    @Synchronized fun setDownloadSpeedLimitKb(v: Int) { downloadSpeedLimitKb = v.coerceAtLeast(0); scheduleSave() }

    @Synchronized fun getDownloadRetryCount(): Int = downloadRetryCount
    @Synchronized fun setDownloadRetryCount(v: Int) { downloadRetryCount = v.coerceAtLeast(0); scheduleSave() }

    @Synchronized fun isEnableResume(): Boolean = enableResume
    @Synchronized fun setEnableResume(v: Boolean) { enableResume = v; scheduleSave() }

    @Synchronized fun getChunkedDownloadThreads(): Int = chunkedDownloadThreads
    @Synchronized fun setChunkedDownloadThreads(v: Int) {
        chunkedDownloadThreads = v.coerceIn(1, 16); scheduleSave()
    }

    @Synchronized fun getDownloadThreads(): Int = downloadThreads
    @Synchronized fun setDownloadThreads(v: Int) {
        downloadThreads = v.coerceIn(1, 32); scheduleSave()
    }

    // ==================== GitHub 同步 ====================
    @Synchronized fun isGithubSyncEnabled(): Boolean = githubSyncEnabled
    @Synchronized fun setGithubSyncEnabled(v: Boolean) { githubSyncEnabled = v; scheduleSave() }

    @Synchronized fun getGithubRepo(): String = githubRepo
    @Synchronized fun setGithubRepo(v: String) { githubRepo = v ?: ""; scheduleSave() }

    // ==================== 持久化 ====================
    @Synchronized
    private fun load() {
        if (!Files.exists(file)) return
        try {
            val json = FileUtils.readString(file)
            if (json.isBlank()) return
            val root = JsonParser.parseString(json).asJsonObject
            useDarkTheme = root.optBool("useDarkTheme", false)
            dynamicColor = root.optBool("dynamicColor", false)
            customAccentColor = root.optInt("customAccentColor", -1)
            themePreset = root.optStr("themePreset", "default")
            colorMode = root.optStr("colorMode", "normal")
            glassTheme = root.optBool("glassTheme", false)
            lockscreenLaunchTheme = root.optBool("lockscreenLaunchTheme", false)
            uiScale = root.optFloat("uiScale", 1.0f)
            language = root.optStr("language", "zh_CN")
            versionIsolation = root.optBool("versionIsolation", false)
            gameResolution = root.optStr("gameResolution", "")
            gameRenderer = root.optStr("gameRenderer", "zink")
            gameFullscreen = root.optBool("gameFullscreen", true)
            gameDemoMode = root.optBool("gameDemoMode", false)
            gameCustomIcon = root.optStr("gameCustomIcon", "")
            gameBgVideo = root.optStr("gameBgVideo", "")
            gameCustomNatives = root.optStr("gameCustomNatives", "")
            firstLaunchCompleted = root.optBool("firstLaunchCompleted", false)
            agreementAccepted = root.optBool("agreementAccepted", false)
            lastSelectedVersion = root.optStr("lastSelectedVersion", "")
            lastOfflineUsername = root.optStr("lastOfflineUsername", "")
            customJvmArgs = root.optStr("customJvmArgs", "")
            gcType = root.optStr("gcType", "G1GC")
            useAikarFlags = root.optBool("useAikarFlags", true)
            minMemoryMb = root.optInt("minMemoryMb", 512)
            maxMemoryMb = root.optInt("maxMemoryMb", 2048)
            mirrorType = root.optStr("mirrorType", "BMCLAPI")
            customMirrorBase = root.optStr("customMirrorBase", "")
            useProxy = root.optBool("useProxy", false)
            proxyHost = root.optStr("proxyHost", "")
            proxyPort = root.optInt("proxyPort", 0)
            useHttpAuth = root.optBool("useHttpAuth", false)
            proxyUsername = root.optStr("proxyUsername", "")
            proxyPassword = root.optStr("proxyPassword", "")
            downloadSpeedLimitKb = root.optInt("downloadSpeedLimitKb", 0)
            downloadRetryCount = root.optInt("downloadRetryCount", 3)
            enableResume = root.optBool("enableResume", true)
            chunkedDownloadThreads = root.optInt("chunkedDownloadThreads", 4)
            downloadThreads = root.optInt("downloadThreads", 8)
            githubSyncEnabled = root.optBool("githubSyncEnabled", false)
            githubRepo = root.optStr("githubRepo", "")
            versionJavaPaths = root.optStrMap("versionJavaPaths")
            gameServerHost = root.optStr("gameServerHost", "")
            gameServerPort = root.optInt("gameServerPort", 25565)

            pinnedVersions = root.optStrList("pinnedVersions")
            recentVersions = root.optStrList("recentVersions")
            lastPlayedTimes = root.optLongMap("lastPlayedTimes")
            pinnedTileLabels = root.optStrMap("pinnedTileLabels")
        } catch (e: Exception) {
            // 损坏文件不抛出，使用默认值
            System.err.println("[Preferences] 加载失败，使用默认值: ${e.message}")
        }
    }

    @Synchronized
    fun save() {
        val root = JsonObject().apply {
            addProperty("useDarkTheme", useDarkTheme)
            addProperty("dynamicColor", dynamicColor)
            addProperty("customAccentColor", customAccentColor)
            addProperty("themePreset", themePreset)
            addProperty("colorMode", colorMode)
            addProperty("glassTheme", glassTheme)
            addProperty("lockscreenLaunchTheme", lockscreenLaunchTheme)
            addProperty("uiScale", uiScale)
            addProperty("language", language)
            addProperty("versionIsolation", versionIsolation)
            addProperty("gameResolution", gameResolution)
            addProperty("gameRenderer", gameRenderer)
            addProperty("gameFullscreen", gameFullscreen)
            addProperty("gameDemoMode", gameDemoMode)
            addProperty("gameCustomIcon", gameCustomIcon)
            addProperty("gameBgVideo", gameBgVideo)
            addProperty("gameCustomNatives", gameCustomNatives)
            addProperty("firstLaunchCompleted", firstLaunchCompleted)
            addProperty("agreementAccepted", agreementAccepted)
            addProperty("lastSelectedVersion", lastSelectedVersion)
            addProperty("lastOfflineUsername", lastOfflineUsername)
            addProperty("customJvmArgs", customJvmArgs)
            addProperty("gcType", gcType)
            addProperty("useAikarFlags", useAikarFlags)
            addProperty("minMemoryMb", minMemoryMb)
            addProperty("maxMemoryMb", maxMemoryMb)
            addProperty("mirrorType", mirrorType)
            addProperty("customMirrorBase", customMirrorBase)
            addProperty("useProxy", useProxy)
            addProperty("proxyHost", proxyHost)
            addProperty("proxyPort", proxyPort)
            addProperty("useHttpAuth", useHttpAuth)
            addProperty("proxyUsername", proxyUsername)
            addProperty("proxyPassword", proxyPassword)
            addProperty("downloadSpeedLimitKb", downloadSpeedLimitKb)
            addProperty("downloadRetryCount", downloadRetryCount)
            addProperty("enableResume", enableResume)
            addProperty("chunkedDownloadThreads", chunkedDownloadThreads)
            addProperty("downloadThreads", downloadThreads)
            addProperty("githubSyncEnabled", githubSyncEnabled)
            addProperty("githubRepo", githubRepo)
            add("versionJavaPaths", gson.toJsonTree(versionJavaPaths))
            addProperty("gameServerHost", gameServerHost)
            addProperty("gameServerPort", gameServerPort)
            add("pinnedVersions", gson.toJsonTree(pinnedVersions))
            add("recentVersions", gson.toJsonTree(recentVersions))
            add("lastPlayedTimes", gson.toJsonTree(lastPlayedTimes))
            add("pinnedTileLabels", gson.toJsonTree(pinnedTileLabels))
        }
        try {
            // 写入临时文件后原子重命名，避免半成品覆盖
            val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
            FileUtils.writeString(tmp, gson.toJson(root))
            try {
                Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            dirty = false
        } catch (e: IOException) {
            System.err.println("[Preferences] 保存失败: ${e.message}")
        }
    }

    /** 防抖保存：200ms 内连续修改只触发一次磁盘 IO */
    private val saveGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    private fun scheduleSave() {
        dirty = true
        val gen = saveGeneration.incrementAndGet()
        synchronized(saveExecutor) {
            pendingSave?.cancel(false)
            pendingSave = saveExecutor.schedule({
                if (dirty && saveGeneration.get() == gen) {
                    try { save() } catch (t: Throwable) {
                        System.err.println("[Preferences] 异步保存失败: ${t.message}")
                    }
                }
            }, SAVE_DEBOUNCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    /** 立即落盘并关闭线程池（JVM shutdown hook 调用） */
    fun shutdown() {
        try {
            synchronized(saveExecutor) {
                pendingSave?.cancel(false)
            }
            if (dirty) save()
        } catch (t: Throwable) {
            System.err.println("[Preferences] shutdown 保存失败: ${t.message}")
        } finally {
            saveExecutor.shutdownNow()
            try {
                if (!saveExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    System.err.println("[Preferences] 线程池未能在 2s 内退出")
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /** 强制刷新到磁盘（外部 shutdown 调用） */
    fun flush() {
        synchronized(this) {
            if (dirty) save()
        }
    }

    // ==================== JSON 工具扩展 ====================
    private fun JsonObject.optBool(key: String, default: Boolean): Boolean =
        if (has(key) && !get(key).isJsonNull) get(key).asBoolean else default

    private fun JsonObject.optInt(key: String, default: Int): Int =
        if (has(key) && !get(key).isJsonNull) get(key).asInt else default

    private fun JsonObject.optFloat(key: String, default: Float): Float =
        if (has(key) && !get(key).isJsonNull) get(key).asFloat else default

    private fun JsonObject.optStr(key: String, default: String): String =
        if (has(key) && !get(key).isJsonNull) get(key).asString else default

    private fun JsonObject.optStrList(key: String): MutableList<String> {
        if (!has(key) || !get(key).isJsonArray) return ArrayList()
        val list = ArrayList<String>()
        for (e in get(key).asJsonArray) {
            if (!e.isJsonNull) list.add(e.asString)
        }
        return list
    }

    private fun JsonObject.optLongMap(key: String): MutableMap<String, Long> {
        if (!has(key) || !get(key).isJsonObject) return HashMap()
        val map = HashMap<String, Long>()
        for ((k, v) in get(key).asJsonObject.entrySet()) {
            if (!v.isJsonNull) {
                try { map[k] = v.asLong } catch (_: Exception) {}
            }
        }
        return map
    }

    private fun JsonObject.optStrMap(key: String): MutableMap<String, String> {
        if (!has(key) || !get(key).isJsonObject) return HashMap()
        val map = HashMap<String, String>()
        for ((k, v) in get(key).asJsonObject.entrySet()) {
            if (!v.isJsonNull) map[k] = v.asString
        }
        return map
    }

    companion object {
        private const val SAVE_DEBOUNCE_MS = 200L
        private const val MAX_RECENT = 5
    }
}
