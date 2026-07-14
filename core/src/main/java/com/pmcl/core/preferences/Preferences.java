package com.pmcl.core.preferences;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 启动器偏好设置持久化（JSON 文件）。
 * <p>
 * 当前存储：useDarkTheme、customJvmArgs、gcType 等。
 * 字段以 JSON 灵活扩展，新增字段无需修改格式版本。
 * <p>
 * 所有公共方法均 synchronized，确保 UI 线程（设置页）与后台协程
 * （recordRecentVersion/setLastPlayedTime）并发访问时的线程安全。
 */
public final class Preferences {

    private final Path file;
    private final Gson gson = new Gson();

    // 默认值
    private boolean useDarkTheme = false;
    private boolean dynamicColor = false; // 莫奈取色：主题颜色跟随桌面壁纸
    private int customAccentColor = -1;   // 自定义强调色 ARGB，-1 表示未设置（使用默认配色）
    private boolean borderlessWindow = false; // 无边框窗口模式（自定义标题栏）
    private float uiScale = 1.0f;             // UI 缩放系数（0.8~1.5），1.0 = 默认大小
    private String language = "zh_CN";             // zh_CN / en_US
    private boolean firstLaunchCompleted = false;  // 是否完成首次启动欢迎流程
    private boolean agreementAccepted = false;     // 用户是否已同意用户协议、免责协议与许可证
    private java.util.List<String> pinnedVersions = new java.util.ArrayList<>();
    private java.util.List<String> recentVersions = new java.util.ArrayList<>();  // 最近使用（LRU，最多 5 个）
    private String lastSelectedVersion = "";       // 上次选中的版本（启动时恢复）
    private java.util.Map<String, Long> lastPlayedTimes = new java.util.HashMap<>();  // versionId → epoch millis
    private java.util.Map<String, String> pinnedTileLabels = new java.util.HashMap<>();  // versionId → 自定义磁贴名称
    private String customJvmArgs = "";
    private String gcType = "G1GC";
    private boolean useAikarFlags = true;
    private int minMemoryMb = 512;
    private int maxMemoryMb = 4096;
    private String javaPath = "";  // 用户指定的 Java 路径，空则自动查找
    // 每版本独立 Java 路径映射：versionId → javaPath，优先级高于全局 javaPath
    private java.util.Map<String, String> versionJavaPaths = new java.util.concurrent.ConcurrentHashMap<>();

    // 游戏通用行为
    private int gameWindowWidth = 854;       // 窗口初始宽度（--width）
    private int gameWindowHeight = 480;      // 窗口初始高度（--height）
    private boolean gameFullscreen = false;  // 全屏启动（--fullscreen）
    private boolean gameDemo = false;        // 演示模式（--demo）
    private String gameServerHost = "";      // 启动后自动连接服务器地址（--server）
    private int gameServerPort = 25565;      // 服务器端口（--port）
    private java.util.List<String[]> favoriteServers = new java.util.ArrayList<>();  // 收藏的服务器列表，每项 [name, host, port]
    private String gameRenderer = "AUTO";    // 渲染器：AUTO/OPENGL/VULKAN（--renderer）
    private String windowIconPath = "";      // 自定义游戏窗口图标 PNG 路径（注入到 <gameDir>/icons/）

    // 网络配置
    private String mirrorType = "OFFICIAL";        // OFFICIAL / BMCLAPI / CUSTOM
    private String customMirrorBase = "";          // 自定义镜像基址
    private boolean useProxy = false;
    private String proxyHost = "";
    private int proxyPort = 0;
    private boolean useHttpAuth = false;
    private String proxyUsername = "";
    private String proxyPassword = "";
    private int downloadSpeedLimitKb = 0;          // 0 = 不限速
    private int downloadRetryCount = 3;
    private boolean enableResume = true;           // 断点续传
    private int chunkedDownloadThreads = 4;        // 分片下载连接数（>1 启用多线程分片）
    private boolean versionIsolation = false;      // 版本隔离：各版本独立 mods/saves/config 目录

    // ===== 启动预设 =====
    private java.util.Map<String, LaunchPreset> launchPresets = new java.util.concurrent.ConcurrentHashMap<>();

    // ===== 多人联机 =====
    private String mpBackend = "EASYTIER";         // EASYTIER / CONNECTX
    private String connectxServerAddress = "";     // ConnectX 服务器地址
    private int connectxServerPort = 3535;         // ConnectX 服务器端口
    private String connectxBinaryPath = "";        // ConnectX.ClientConsole 二进制路径

    // ===== 防抖磁盘写入（性能优化）=====
    // 每次 setter 只标记 dirty 并调度一次延迟写入，连续修改（如拖动 UI 缩放滑块）只会触发一次磁盘 IO。
    // 内存状态始终即时更新（synchronized 保护），仅磁盘写入被合并。
    private volatile boolean dirty = false;
    private final java.util.concurrent.ScheduledExecutorService saveExecutor =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pmcl-prefs-writer");
            t.setDaemon(true);
            return t;
        });
    private java.util.concurrent.ScheduledFuture<?> pendingSave = null;
    private static final long SAVE_DEBOUNCE_MS = 200;

    public Preferences(Path file) {
        this.file = file;
        load();
        // JVM 退出时强制刷新未落盘的修改，避免防抖导致数据丢失
        Runtime.getRuntime().addShutdownHook(new Thread(this::flush, "pmcl-prefs-shutdown"));
    }

    public synchronized boolean isUseDarkTheme() { return useDarkTheme; }
    public synchronized void setUseDarkTheme(boolean v) { useDarkTheme = v; scheduleSave(); }
    public synchronized boolean isDynamicColor() { return dynamicColor; }
    public synchronized void setDynamicColor(boolean v) { dynamicColor = v; scheduleSave(); }
    public synchronized int getCustomAccentColor() { return customAccentColor; }
    public synchronized void setCustomAccentColor(int v) { customAccentColor = v; scheduleSave(); }

    public synchronized boolean isBorderlessWindow() { return borderlessWindow; }
    public synchronized void setBorderlessWindow(boolean v) { borderlessWindow = v; scheduleSave(); }

    /** UI 缩放系数，范围 0.8~1.5，默认 1.0 */
    public synchronized float getUiScale() { return uiScale; }
    public synchronized void setUiScale(float v) { uiScale = Math.max(0.7f, Math.min(1.6f, v)); scheduleSave(); }

    public synchronized String getLanguage() { return language; }
    public synchronized void setLanguage(String v) { language = v; scheduleSave(); }

    /** 首次启动欢迎流程是否已完成 */
    public synchronized boolean isFirstLaunchCompleted() { return firstLaunchCompleted; }
    public synchronized void setFirstLaunchCompleted(boolean v) { firstLaunchCompleted = v; scheduleSave(); }

    /** 用户是否已同意用户协议、免责协议与许可证 */
    public synchronized boolean isAgreementAccepted() { return agreementAccepted; }
    public synchronized void setAgreementAccepted(boolean v) { agreementAccepted = v; scheduleSave(); }

    /** 返回固定磁贴列表的副本（避免外部原地修改导致 StateFlow 引用比较失效） */
    public synchronized java.util.List<String> getPinnedVersions() {
        return new java.util.ArrayList<>(pinnedVersions);
    }

    public synchronized void setPinnedVersions(java.util.List<String> v) {
        pinnedVersions = new java.util.ArrayList<>(v); scheduleSave();
    }

    public synchronized void pinVersion(String versionId) {
        if (!pinnedVersions.contains(versionId)) {
            pinnedVersions.add(versionId); scheduleSave();
        }
    }

    public synchronized void unpinVersion(String versionId) {
        pinnedVersions.remove(versionId);
        // 同步清理磁贴自定义名称
        pinnedTileLabels.remove(versionId);
        scheduleSave();
    }

    public synchronized boolean isPinned(String versionId) {
        return pinnedVersions.contains(versionId);
    }

    // ===== 磁贴自定义名称（持久化） =====
    public synchronized String getPinnedTileLabel(String versionId) {
        return pinnedTileLabels.get(versionId);
    }

    /** 返回整个磁贴标签映射的副本（供 ViewModel 批量加载） */
    public synchronized java.util.Map<String, String> getPinnedTileLabelsRaw() {
        return new java.util.HashMap<>(pinnedTileLabels);
    }

    /** 设置/更新磁贴自定义名称；传 null 或空串则清除该标签 */
    public synchronized void setPinnedTileLabel(String versionId, String label) {
        if (label == null || label.isEmpty()) {
            pinnedTileLabels.remove(versionId);
        } else {
            pinnedTileLabels.put(versionId, label);
        }
        scheduleSave();
    }

    // ===== 最近使用（LRU，最多 5 个） =====
    /** 返回最近使用列表的副本（避免外部原地修改导致 StateFlow 引用比较失效） */
    public synchronized java.util.List<String> getRecentVersions() {
        return new java.util.ArrayList<>(recentVersions);
    }

    /** 记录一次启动，置顶到最近使用列表头部（去重，超限裁剪） */
    public synchronized void recordRecentVersion(String versionId) {
        recentVersions.remove(versionId);
        recentVersions.add(0, versionId);
        while (recentVersions.size() > 5) {
            recentVersions.remove(recentVersions.size() - 1);
        }
        scheduleSave();
    }

    public synchronized void clearRecentVersions() {
        recentVersions.clear(); scheduleSave();
    }

    /** 从最近使用列表移除某版本（版本被删除时清理用） */
    public synchronized void removeRecentVersion(String versionId) {
        if (recentVersions.remove(versionId)) scheduleSave();
    }

    // ===== 最后选中版本（启动时恢复） =====
    public synchronized String getLastSelectedVersion() { return lastSelectedVersion; }
    public synchronized void setLastSelectedVersion(String v) {
        lastSelectedVersion = v == null ? "" : v; scheduleSave();
    }

    // ===== 最后游玩时间戳（versionId → epoch millis） =====
    public synchronized Long getLastPlayedTime(String versionId) {
        return lastPlayedTimes.get(versionId);
    }

    /** 返回整个最后游玩时间映射的副本（供 ViewModel 批量加载） */
    public synchronized java.util.Map<String, Long> getLastPlayedTimesRaw() {
        return new java.util.HashMap<>(lastPlayedTimes);
    }

    public synchronized void setLastPlayedTime(String versionId, long epochMillis) {
        lastPlayedTimes.put(versionId, epochMillis); scheduleSave();
    }

    /** 移除某版本的最后游玩时间记录（版本被删除时清理用） */
    public synchronized void removeLastPlayedTime(String versionId) {
        if (lastPlayedTimes.remove(versionId) != null) scheduleSave();
    }

    public synchronized String getCustomJvmArgs() { return customJvmArgs; }
    public synchronized void setCustomJvmArgs(String v) { customJvmArgs = v; scheduleSave(); }

    public synchronized String getGcType() { return gcType; }
    public synchronized void setGcType(String v) { gcType = v; scheduleSave(); }

    public synchronized boolean isUseAikarFlags() { return useAikarFlags; }
    public synchronized void setUseAikarFlags(boolean v) { useAikarFlags = v; scheduleSave(); }

    public synchronized int getMinMemoryMb() { return minMemoryMb; }
    public synchronized void setMinMemoryMb(int v) { minMemoryMb = v; scheduleSave(); }

    public synchronized int getMaxMemoryMb() { return maxMemoryMb; }
    public synchronized void setMaxMemoryMb(int v) { maxMemoryMb = v; scheduleSave(); }

    public synchronized String getJavaPath() { return javaPath; }
    public synchronized void setJavaPath(String v) { javaPath = v == null ? "" : v; scheduleSave(); }

    /** 获取指定版本的独立 Java 路径，未配置返回空字符串 */
    public synchronized String getVersionJavaPath(String versionId) {
        if (versionId == null) return "";
        String p = versionJavaPaths.get(versionId);
        return p == null ? "" : p;
    }

    /** 设置指定版本的独立 Java 路径，空字符串则清除该版本配置 */
    public synchronized void setVersionJavaPath(String versionId, String javaPath) {
        if (versionId == null || versionId.isEmpty()) return;
        if (javaPath == null || javaPath.isEmpty()) {
            versionJavaPaths.remove(versionId);
        } else {
            versionJavaPaths.put(versionId, javaPath);
        }
        scheduleSave();
    }

    /** 返回所有已配置独立 Java 的版本 ID 集合 */
    public synchronized java.util.Set<String> getVersionsWithCustomJava() {
        return new java.util.LinkedHashSet<>(versionJavaPaths.keySet());
    }

    // ===== 游戏通用行为 =====
    public synchronized int getGameWindowWidth() { return gameWindowWidth; }
    public synchronized void setGameWindowWidth(int v) {
        gameWindowWidth = Math.max(1, v); scheduleSave();
    }

    public synchronized int getGameWindowHeight() { return gameWindowHeight; }
    public synchronized void setGameWindowHeight(int v) {
        gameWindowHeight = Math.max(1, v); scheduleSave();
    }

    public synchronized boolean isGameFullscreen() { return gameFullscreen; }
    public synchronized void setGameFullscreen(boolean v) { gameFullscreen = v; scheduleSave(); }

    public synchronized boolean isGameDemo() { return gameDemo; }
    public synchronized void setGameDemo(boolean v) { gameDemo = v; scheduleSave(); }

    public synchronized String getGameServerHost() { return gameServerHost; }
    public synchronized void setGameServerHost(String v) {
        gameServerHost = v == null ? "" : v.trim(); scheduleSave();
    }

    public synchronized int getGameServerPort() { return gameServerPort; }
    public synchronized void setGameServerPort(int v) {
        if (v > 0 && v < 65536) gameServerPort = v; scheduleSave();
    }

    // ===== 收藏服务器列表 =====
    /** 返回收藏服务器列表的副本，每项为 [name, host, port] */
    public synchronized java.util.List<String[]> getFavoriteServers() {
        return new java.util.ArrayList<>(favoriteServers);
    }

    /** 添加收藏服务器，name 为空时用 host:port 代替 */
    public synchronized void addFavoriteServer(String name, String host, int port) {
        String n = (name == null || name.isBlank()) ? (host + ":" + port) : name.trim();
        favoriteServers.add(new String[]{n, host.trim(), String.valueOf(port)});
        scheduleSave();
    }

    public synchronized void removeFavoriteServer(int index) {
        if (index >= 0 && index < favoriteServers.size()) {
            favoriteServers.remove(index);
            scheduleSave();
        }
    }

    public synchronized void updateFavoriteServer(int index, String name, String host, int port) {
        if (index >= 0 && index < favoriteServers.size()) {
            String n = (name == null || name.isBlank()) ? (host + ":" + port) : name.trim();
            favoriteServers.set(index, new String[]{n, host.trim(), String.valueOf(port)});
            scheduleSave();
        }
    }

    /** 渲染器类型：AUTO（不注入）/ OPENGL / VULKAN */
    public synchronized String getGameRenderer() { return gameRenderer; }
    public synchronized void setGameRenderer(String v) {
        if (v == null) v = "AUTO";
        String upper = v.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("OPENGL") || upper.equals("VULKAN") || upper.equals("AUTO")) {
            gameRenderer = upper;
        }
        scheduleSave();
    }

    /** 自定义游戏窗口图标 PNG 路径（空则使用 MC 默认图标） */
    public synchronized String getWindowIconPath() { return windowIconPath; }
    public synchronized void setWindowIconPath(String v) {
        windowIconPath = v == null ? "" : v.trim(); scheduleSave();
    }

    // ===== 网络配置 =====
    public synchronized String getMirrorType() { return mirrorType; }
    public synchronized void setMirrorType(String v) { mirrorType = v; scheduleSave(); }

    public synchronized String getCustomMirrorBase() { return customMirrorBase; }
    public synchronized void setCustomMirrorBase(String v) { customMirrorBase = v; scheduleSave(); }

    public synchronized boolean isUseProxy() { return useProxy; }
    public synchronized void setUseProxy(boolean v) { useProxy = v; scheduleSave(); }

    public synchronized String getProxyHost() { return proxyHost; }
    public synchronized void setProxyHost(String v) { proxyHost = v; scheduleSave(); }

    public synchronized int getProxyPort() { return proxyPort; }
    public synchronized void setProxyPort(int v) { proxyPort = v; scheduleSave(); }

    public synchronized boolean isUseHttpAuth() { return useHttpAuth; }
    public synchronized void setUseHttpAuth(boolean v) { useHttpAuth = v; scheduleSave(); }

    public synchronized String getProxyUsername() { return proxyUsername; }
    public synchronized void setProxyUsername(String v) { proxyUsername = v; scheduleSave(); }

    public synchronized String getProxyPassword() { return proxyPassword; }
    public synchronized void setProxyPassword(String v) { proxyPassword = v; scheduleSave(); }

    public synchronized int getDownloadSpeedLimitKb() { return downloadSpeedLimitKb; }
    public synchronized void setDownloadSpeedLimitKb(int v) { downloadSpeedLimitKb = v; scheduleSave(); }

    public synchronized int getDownloadRetryCount() { return downloadRetryCount; }
    public synchronized void setDownloadRetryCount(int v) { downloadRetryCount = v; scheduleSave(); }

    public synchronized boolean isEnableResume() { return enableResume; }
    public synchronized void setEnableResume(boolean v) { enableResume = v; scheduleSave(); }

    public synchronized int getChunkedDownloadThreads() { return chunkedDownloadThreads; }
    public synchronized void setChunkedDownloadThreads(int v) {
        chunkedDownloadThreads = Math.max(1, v); scheduleSave();
    }

    public synchronized boolean isVersionIsolation() { return versionIsolation; }
    public synchronized void setVersionIsolation(boolean v) { versionIsolation = v; scheduleSave(); }

    // ===== 多人联机 =====
    public synchronized String getMpBackend() {
        // 默认使用 Terracotta（HMCL 同款官方陶瓦联机实现）
        if (mpBackend == null || mpBackend.isEmpty()) return "TERRACOTTA";
        return mpBackend;
    }
    public synchronized void setMpBackend(String v) {
        mpBackend = (v == null || v.isEmpty()) ? "TERRACOTTA" : v.toUpperCase(Locale.ROOT); scheduleSave();
    }
    public synchronized String getConnectxServerAddress() { return connectxServerAddress; }
    public synchronized void setConnectxServerAddress(String v) { connectxServerAddress = v == null ? "" : v; scheduleSave(); }
    public synchronized int getConnectxServerPort() { return connectxServerPort; }
    public synchronized void setConnectxServerPort(int v) {
        if (v > 0 && v < 65536) connectxServerPort = v; scheduleSave();
    }
    public synchronized String getConnectxBinaryPath() { return connectxBinaryPath; }
    public synchronized void setConnectxBinaryPath(String v) { connectxBinaryPath = v == null ? "" : v; scheduleSave(); }

    // ===== 启动预设 =====

    /** 启动预设：保存一组启动参数快照（内存/JVM/GC/窗口/全屏/服务器等） */
    public static final class LaunchPreset {
        public final String name;
        public final int minMemoryMb;
        public final int maxMemoryMb;
        public final String gcType;
        public final boolean useAikarFlags;
        public final String customJvmArgs;
        public final int gameWindowWidth;
        public final int gameWindowHeight;
        public final boolean gameFullscreen;
        public final boolean gameDemo;
        public final String gameRenderer;
        public final String gameServerHost;
        public final int gameServerPort;

        public LaunchPreset(String name, int minMemoryMb, int maxMemoryMb, String gcType,
                            boolean useAikarFlags, String customJvmArgs,
                            int gameWindowWidth, int gameWindowHeight,
                            boolean gameFullscreen, boolean gameDemo,
                            String gameRenderer, String gameServerHost, int gameServerPort) {
            this.name = name;
            this.minMemoryMb = minMemoryMb;
            this.maxMemoryMb = maxMemoryMb;
            this.gcType = gcType;
            this.useAikarFlags = useAikarFlags;
            this.customJvmArgs = customJvmArgs;
            this.gameWindowWidth = gameWindowWidth;
            this.gameWindowHeight = gameWindowHeight;
            this.gameFullscreen = gameFullscreen;
            this.gameDemo = gameDemo;
            this.gameRenderer = gameRenderer;
            this.gameServerHost = gameServerHost;
            this.gameServerPort = gameServerPort;
        }
    }

    /** 获取所有启动预设（按名称排序） */
    public synchronized java.util.List<LaunchPreset> getLaunchPresets() {
        var list = new java.util.ArrayList<>(launchPresets.values());
        list.sort(java.util.Comparator.comparing(p -> p.name));
        return list;
    }

    /** 将当前启动参数保存为命名预设 */
    public synchronized void saveLaunchPreset(String name) {
        if (name == null || name.isBlank()) return;
        LaunchPreset preset = new LaunchPreset(
                name, minMemoryMb, maxMemoryMb, gcType, useAikarFlags, customJvmArgs,
                gameWindowWidth, gameWindowHeight, gameFullscreen, gameDemo,
                gameRenderer, gameServerHost, gameServerPort);
        launchPresets.put(name, preset);
        scheduleSave();
    }

    /** 加载指定预设到当前启动参数 */
    public synchronized void applyLaunchPreset(String name) {
        LaunchPreset p = launchPresets.get(name);
        if (p == null) return;
        minMemoryMb = p.minMemoryMb;
        maxMemoryMb = p.maxMemoryMb;
        gcType = p.gcType;
        useAikarFlags = p.useAikarFlags;
        customJvmArgs = p.customJvmArgs;
        gameWindowWidth = p.gameWindowWidth;
        gameWindowHeight = p.gameWindowHeight;
        gameFullscreen = p.gameFullscreen;
        gameDemo = p.gameDemo;
        gameRenderer = p.gameRenderer;
        gameServerHost = p.gameServerHost;
        gameServerPort = p.gameServerPort;
        scheduleSave();
    }

    /** 删除指定预设 */
    public synchronized void deleteLaunchPreset(String name) {
        launchPresets.remove(name);
        scheduleSave();
    }

    /** 从磁盘加载（不存在或损坏则保持默认） */
    public synchronized void load() {
        if (!Files.exists(file)) return;
        try {
            String content = Files.readString(file);
            if (content.isBlank()) return;
            var parsed = JsonParser.parseString(content);
            if (parsed == null || !parsed.isJsonObject()) return;
            JsonObject o = parsed.getAsJsonObject();
            if (o.has("useDarkTheme")) useDarkTheme = o.get("useDarkTheme").getAsBoolean();
            if (o.has("dynamicColor")) dynamicColor = o.get("dynamicColor").getAsBoolean();
            if (o.has("customAccentColor")) customAccentColor = o.get("customAccentColor").getAsInt();
            if (o.has("borderlessWindow")) borderlessWindow = o.get("borderlessWindow").getAsBoolean();
            if (o.has("uiScale")) uiScale = o.get("uiScale").getAsFloat();
            if (o.has("language") && !o.get("language").isJsonNull()) language = o.get("language").getAsString();
            if (o.has("firstLaunchCompleted")) firstLaunchCompleted = o.get("firstLaunchCompleted").getAsBoolean();
            if (o.has("agreementAccepted")) agreementAccepted = o.get("agreementAccepted").getAsBoolean();
            if (o.has("pinnedVersions")) {
                pinnedVersions = new java.util.ArrayList<>();
                for (var e : o.getAsJsonArray("pinnedVersions")) {
                    if (!e.isJsonNull()) pinnedVersions.add(e.getAsString());
                }
            }
            if (o.has("recentVersions")) {
                recentVersions = new java.util.ArrayList<>();
                for (var e : o.getAsJsonArray("recentVersions")) {
                    if (!e.isJsonNull()) recentVersions.add(e.getAsString());
                }
            }
            if (o.has("lastSelectedVersion") && !o.get("lastSelectedVersion").isJsonNull()) {
                lastSelectedVersion = o.get("lastSelectedVersion").getAsString();
            }
            if (o.has("lastPlayedTimes") && o.get("lastPlayedTimes").isJsonObject()) {
                lastPlayedTimes = new java.util.HashMap<>();
                JsonObject times = o.getAsJsonObject("lastPlayedTimes");
                for (var entry : times.entrySet()) {
                    try {
                        lastPlayedTimes.put(entry.getKey(), entry.getValue().getAsLong());
                    } catch (Exception ignored) {}
                }
            }
            if (o.has("pinnedTileLabels") && o.get("pinnedTileLabels").isJsonObject()) {
                pinnedTileLabels = new java.util.HashMap<>();
                JsonObject labels = o.getAsJsonObject("pinnedTileLabels");
                for (var entry : labels.entrySet()) {
                    try {
                        pinnedTileLabels.put(entry.getKey(), entry.getValue().getAsString());
                    } catch (Exception ignored) {}
                }
            }
            if (o.has("customJvmArgs") && !o.get("customJvmArgs").isJsonNull()) customJvmArgs = o.get("customJvmArgs").getAsString();
            if (o.has("gcType") && !o.get("gcType").isJsonNull()) gcType = o.get("gcType").getAsString();
            if (o.has("useAikarFlags")) useAikarFlags = o.get("useAikarFlags").getAsBoolean();
            if (o.has("minMemoryMb")) minMemoryMb = o.get("minMemoryMb").getAsInt();
            if (o.has("maxMemoryMb")) maxMemoryMb = o.get("maxMemoryMb").getAsInt();
            if (o.has("javaPath") && !o.get("javaPath").isJsonNull()) javaPath = o.get("javaPath").getAsString();
            if (o.has("versionJavaPaths") && !o.get("versionJavaPaths").isJsonNull()) {
                JsonObject vjp = o.getAsJsonObject("versionJavaPaths");
                versionJavaPaths.clear();
                for (var entry : vjp.entrySet()) {
                    if (!entry.getValue().isJsonNull()) {
                        versionJavaPaths.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
            if (o.has("gameWindowWidth")) gameWindowWidth = o.get("gameWindowWidth").getAsInt();
            if (o.has("gameWindowHeight")) gameWindowHeight = o.get("gameWindowHeight").getAsInt();
            if (o.has("gameFullscreen")) gameFullscreen = o.get("gameFullscreen").getAsBoolean();
            if (o.has("gameDemo")) gameDemo = o.get("gameDemo").getAsBoolean();
            if (o.has("gameServerHost") && !o.get("gameServerHost").isJsonNull()) gameServerHost = o.get("gameServerHost").getAsString();
            if (o.has("gameServerPort")) gameServerPort = o.get("gameServerPort").getAsInt();
            if (o.has("favoriteServers") && o.get("favoriteServers").isJsonArray()) {
                favoriteServers = new java.util.ArrayList<>();
                for (var elem : o.getAsJsonArray("favoriteServers")) {
                    try {
                        var arr = elem.getAsJsonArray();
                        if (arr.size() >= 3) {
                            favoriteServers.add(new String[]{
                                arr.get(0).getAsString(), arr.get(1).getAsString(), arr.get(2).getAsString()
                            });
                        }
                    } catch (Exception ignored) {}
                }
            }
            if (o.has("gameRenderer") && !o.get("gameRenderer").isJsonNull()) gameRenderer = o.get("gameRenderer").getAsString();
            if (o.has("windowIconPath") && !o.get("windowIconPath").isJsonNull()) windowIconPath = o.get("windowIconPath").getAsString();
            if (o.has("mirrorType") && !o.get("mirrorType").isJsonNull()) mirrorType = o.get("mirrorType").getAsString();
            if (o.has("customMirrorBase") && !o.get("customMirrorBase").isJsonNull()) customMirrorBase = o.get("customMirrorBase").getAsString();
            if (o.has("useProxy")) useProxy = o.get("useProxy").getAsBoolean();
            if (o.has("proxyHost") && !o.get("proxyHost").isJsonNull()) proxyHost = o.get("proxyHost").getAsString();
            if (o.has("proxyPort")) proxyPort = o.get("proxyPort").getAsInt();
            if (o.has("useHttpAuth")) useHttpAuth = o.get("useHttpAuth").getAsBoolean();
            if (o.has("proxyUsername") && !o.get("proxyUsername").isJsonNull()) proxyUsername = o.get("proxyUsername").getAsString();
            if (o.has("proxyPassword") && !o.get("proxyPassword").isJsonNull()) proxyPassword = o.get("proxyPassword").getAsString();
            if (o.has("downloadSpeedLimitKb")) downloadSpeedLimitKb = o.get("downloadSpeedLimitKb").getAsInt();
            if (o.has("downloadRetryCount")) downloadRetryCount = o.get("downloadRetryCount").getAsInt();
            if (o.has("enableResume")) enableResume = o.get("enableResume").getAsBoolean();
            if (o.has("chunkedDownloadThreads")) chunkedDownloadThreads = o.get("chunkedDownloadThreads").getAsInt();
            if (o.has("versionIsolation")) versionIsolation = o.get("versionIsolation").getAsBoolean();
            if (o.has("mpBackend") && !o.get("mpBackend").isJsonNull()) mpBackend = o.get("mpBackend").getAsString();
            if (o.has("connectxServerAddress") && !o.get("connectxServerAddress").isJsonNull()) connectxServerAddress = o.get("connectxServerAddress").getAsString();
            if (o.has("connectxServerPort")) connectxServerPort = o.get("connectxServerPort").getAsInt();
            if (o.has("connectxBinaryPath") && !o.get("connectxBinaryPath").isJsonNull()) connectxBinaryPath = o.get("connectxBinaryPath").getAsString();
            if (o.has("launchPresets") && o.get("launchPresets").isJsonObject()) {
                launchPresets.clear();
                JsonObject presetsObj = o.getAsJsonObject("launchPresets");
                for (var entry : presetsObj.entrySet()) {
                    try {
                        JsonObject p = entry.getValue().getAsJsonObject();
                        launchPresets.put(entry.getKey(), new LaunchPreset(
                                entry.getKey(),
                                p.has("minMemoryMb") ? p.get("minMemoryMb").getAsInt() : 512,
                                p.has("maxMemoryMb") ? p.get("maxMemoryMb").getAsInt() : 4096,
                                p.has("gcType") && !p.get("gcType").isJsonNull() ? p.get("gcType").getAsString() : "G1GC",
                                p.has("useAikarFlags") ? p.get("useAikarFlags").getAsBoolean() : true,
                                p.has("customJvmArgs") && !p.get("customJvmArgs").isJsonNull() ? p.get("customJvmArgs").getAsString() : "",
                                p.has("gameWindowWidth") ? p.get("gameWindowWidth").getAsInt() : 854,
                                p.has("gameWindowHeight") ? p.get("gameWindowHeight").getAsInt() : 480,
                                p.has("gameFullscreen") ? p.get("gameFullscreen").getAsBoolean() : false,
                                p.has("gameDemo") ? p.get("gameDemo").getAsBoolean() : false,
                                p.has("gameRenderer") && !p.get("gameRenderer").isJsonNull() ? p.get("gameRenderer").getAsString() : "AUTO",
                                p.has("gameServerHost") && !p.get("gameServerHost").isJsonNull() ? p.get("gameServerHost").getAsString() : "",
                                p.has("gameServerPort") ? p.get("gameServerPort").getAsInt() : 25565
                        ));
                    } catch (Exception ignored2) {}
                }
            }
        } catch (Throwable e) {
            // 配置文件损坏：备份后保持默认，避免静默丢失用户数据
            System.err.println("[Preferences] 配置文件解析失败，将备份后使用默认配置: " + e.getMessage());
            try {
                java.nio.file.Path backup = file.resolveSibling(file.getFileName() + ".corrupt");
                Files.move(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[Preferences] 损坏文件已备份至: " + backup);
            } catch (Throwable backupErr) {
                System.err.println("[Preferences] 备份损坏文件失败: " + backupErr.getMessage());
            }
        }
    }

    /**
     * 标记状态已修改并调度一次防抖磁盘写入。
     * <p>
     * 连续快速调用（如拖动滑块、连续输入）时，仅最后一次调用后 200ms 才真正写盘，
     * 避免每个 setter 都触发完整的 JSON 序列化 + 文件 IO。
     * 内存状态在 synchronized 保护下始终即时生效。
     */
    protected void scheduleSave() {
        dirty = true;
        if (pendingSave == null || pendingSave.isDone()) {
            pendingSave = saveExecutor.schedule(this::doSave, SAVE_DEBOUNCE_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    /** 后台线程执行的实际写盘操作，在 synchronized 块内构建 JSON 快照后异步写盘。
     *  采用 tmp + ATOMIC_MOVE 原子写入，防止崩溃导致配置文件损坏。 */
    private void doSave() {
        JsonObject snapshot;
        synchronized (this) {
            if (!dirty) return;
            dirty = false;
            snapshot = buildJson();
        }
        java.nio.file.Path tmp = null;
        try {
            java.nio.file.Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            // 写入临时文件后原子移动到目标，避免崩溃中途写入损坏文件
            tmp = parent == null
                    ? java.nio.file.Paths.get(file.getFileName() + ".tmp")
                    : parent.resolve(file.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(snapshot));
            Files.move(tmp, file,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            tmp = null; // 移动成功，无需清理
        } catch (Throwable e) {
            System.err.println("[Preferences] 配置保存失败: " + e.getMessage());
            // 清理残留临时文件
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Throwable ignored) {}
            }
        }
    }

    /** 构建完整 JSON 快照（必须在 synchronized 块内调用） */
    private JsonObject buildJson() {
        JsonObject o = new JsonObject();
        o.addProperty("useDarkTheme", useDarkTheme);
        o.addProperty("dynamicColor", dynamicColor);
        o.addProperty("customAccentColor", customAccentColor);
        o.addProperty("borderlessWindow", borderlessWindow);
        o.addProperty("uiScale", uiScale);
        o.addProperty("language", language);
        o.addProperty("firstLaunchCompleted", firstLaunchCompleted);
        o.addProperty("agreementAccepted", agreementAccepted);
        com.google.gson.JsonArray pinArr = new com.google.gson.JsonArray();
        for (String v : pinnedVersions) pinArr.add(v);
        o.add("pinnedVersions", pinArr);
        com.google.gson.JsonArray recentArr = new com.google.gson.JsonArray();
        for (String v : recentVersions) recentArr.add(v);
        o.add("recentVersions", recentArr);
        o.addProperty("lastSelectedVersion", lastSelectedVersion);
        JsonObject timesObj = new JsonObject();
        for (var entry : lastPlayedTimes.entrySet()) {
            timesObj.addProperty(entry.getKey(), entry.getValue());
        }
        o.add("lastPlayedTimes", timesObj);
        JsonObject labelsObj = new JsonObject();
        for (var entry : pinnedTileLabels.entrySet()) {
            labelsObj.addProperty(entry.getKey(), entry.getValue());
        }
        o.add("pinnedTileLabels", labelsObj);
        o.addProperty("customJvmArgs", customJvmArgs);
        o.addProperty("gcType", gcType);
        o.addProperty("useAikarFlags", useAikarFlags);
        o.addProperty("minMemoryMb", minMemoryMb);
        o.addProperty("maxMemoryMb", maxMemoryMb);
        o.addProperty("javaPath", javaPath);
        JsonObject vjp = new JsonObject();
        for (var entry : versionJavaPaths.entrySet()) {
            vjp.addProperty(entry.getKey(), entry.getValue());
        }
        o.add("versionJavaPaths", vjp);
        o.addProperty("gameWindowWidth", gameWindowWidth);
        o.addProperty("gameWindowHeight", gameWindowHeight);
        o.addProperty("gameFullscreen", gameFullscreen);
        o.addProperty("gameDemo", gameDemo);
        o.addProperty("gameServerHost", gameServerHost);
        o.addProperty("gameServerPort", gameServerPort);
        var favArr = new com.google.gson.JsonArray();
        for (var s : favoriteServers) {
            var item = new com.google.gson.JsonArray();
            item.add(s[0]); item.add(s[1]); item.add(s[2]);
            favArr.add(item);
        }
        o.add("favoriteServers", favArr);
        o.addProperty("gameRenderer", gameRenderer);
        o.addProperty("windowIconPath", windowIconPath);
        o.addProperty("mirrorType", mirrorType);
        o.addProperty("customMirrorBase", customMirrorBase);
        o.addProperty("useProxy", useProxy);
        o.addProperty("proxyHost", proxyHost);
        o.addProperty("proxyPort", proxyPort);
        o.addProperty("useHttpAuth", useHttpAuth);
        o.addProperty("proxyUsername", proxyUsername);
        o.addProperty("proxyPassword", proxyPassword);
        o.addProperty("downloadSpeedLimitKb", downloadSpeedLimitKb);
        o.addProperty("downloadRetryCount", downloadRetryCount);
        o.addProperty("enableResume", enableResume);
        o.addProperty("chunkedDownloadThreads", chunkedDownloadThreads);
        o.addProperty("versionIsolation", versionIsolation);
        o.addProperty("mpBackend", mpBackend);
        o.addProperty("connectxServerAddress", connectxServerAddress);
        o.addProperty("connectxServerPort", connectxServerPort);
        o.addProperty("connectxBinaryPath", connectxBinaryPath);
        JsonObject presetsObj = new JsonObject();
        for (var entry : launchPresets.entrySet()) {
            LaunchPreset p = entry.getValue();
            JsonObject po = new JsonObject();
            po.addProperty("minMemoryMb", p.minMemoryMb);
            po.addProperty("maxMemoryMb", p.maxMemoryMb);
            po.addProperty("gcType", p.gcType);
            po.addProperty("useAikarFlags", p.useAikarFlags);
            po.addProperty("customJvmArgs", p.customJvmArgs);
            po.addProperty("gameWindowWidth", p.gameWindowWidth);
            po.addProperty("gameWindowHeight", p.gameWindowHeight);
            po.addProperty("gameFullscreen", p.gameFullscreen);
            po.addProperty("gameDemo", p.gameDemo);
            po.addProperty("gameRenderer", p.gameRenderer);
            po.addProperty("gameServerHost", p.gameServerHost);
            po.addProperty("gameServerPort", p.gameServerPort);
            presetsObj.add(entry.getKey(), po);
        }
        o.add("launchPresets", presetsObj);
        return o;
    }

    /** 立即同步保存到磁盘（取消待执行的防抖写入）。外部需要确保数据立即落盘时调用。 */
    public synchronized void save() {
        if (pendingSave != null) pendingSave.cancel(false);
        dirty = false;
        try {
            JsonObject o = buildJson();
            java.nio.file.Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, gson.toJson(o));
        } catch (Throwable ignored) {
        }
    }

    /** 刷新所有待写入的修改到磁盘（供关闭钩子调用）。不阻塞已有 synchronized 调用方。 */
    public void flush() {
        // 取消待执行的防抖任务，直接同步写盘
        if (pendingSave != null) pendingSave.cancel(false);
        doSave();
    }
}
