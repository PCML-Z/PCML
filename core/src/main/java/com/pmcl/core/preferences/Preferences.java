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
 */
public final class Preferences {

    private final Path file;
    private final Gson gson = new Gson();

    // 默认值
    private boolean useDarkTheme = false;
    private boolean dynamicColor = false; // 莫奈取色：主题颜色跟随桌面壁纸
    private String language = "zh_CN";             // zh_CN / en_US
    private boolean firstLaunchCompleted = false;  // 是否完成首次启动欢迎流程
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

    // 游戏通用行为
    private int gameWindowWidth = 854;       // 窗口初始宽度（--width）
    private int gameWindowHeight = 480;      // 窗口初始高度（--height）
    private boolean gameFullscreen = false;  // 全屏启动（--fullscreen）
    private boolean gameDemo = false;        // 演示模式（--demo）
    private String gameServerHost = "";      // 启动后自动连接服务器地址（--server）
    private int gameServerPort = 25565;      // 服务器端口（--port）
    private String gameRenderer = "AUTO";    // 渲染器：AUTO/OPENGL/VULKAN（--renderer）

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

    // ===== 多人联机 =====
    private String mpBackend = "EASYTIER";         // EASYTIER / CONNECTX
    private String connectxServerAddress = "";     // ConnectX 服务器地址
    private int connectxServerPort = 3535;         // ConnectX 服务器端口
    private String connectxBinaryPath = "";        // ConnectX.ClientConsole 二进制路径

    public Preferences(Path file) {
        this.file = file;
        load();
    }

    public boolean isUseDarkTheme() { return useDarkTheme; }
    public void setUseDarkTheme(boolean v) { useDarkTheme = v; save(); }
    public boolean isDynamicColor() { return dynamicColor; }
    public void setDynamicColor(boolean v) { dynamicColor = v; save(); }

    public String getLanguage() { return language; }
    public void setLanguage(String v) { language = v; save(); }

    /** 首次启动欢迎流程是否已完成 */
    public boolean isFirstLaunchCompleted() { return firstLaunchCompleted; }
    public void setFirstLaunchCompleted(boolean v) { firstLaunchCompleted = v; save(); }

    /** 返回固定磁贴列表的副本（避免外部原地修改导致 StateFlow 引用比较失效） */
    public java.util.List<String> getPinnedVersions() {
        return new java.util.ArrayList<>(pinnedVersions);
    }

    public void setPinnedVersions(java.util.List<String> v) {
        pinnedVersions = new java.util.ArrayList<>(v); save();
    }

    public void pinVersion(String versionId) {
        if (!pinnedVersions.contains(versionId)) {
            pinnedVersions.add(versionId); save();
        }
    }

    public void unpinVersion(String versionId) {
        pinnedVersions.remove(versionId);
        // 同步清理磁贴自定义名称
        pinnedTileLabels.remove(versionId);
        save();
    }

    public boolean isPinned(String versionId) {
        return pinnedVersions.contains(versionId);
    }

    // ===== 磁贴自定义名称（持久化） =====
    public String getPinnedTileLabel(String versionId) {
        return pinnedTileLabels.get(versionId);
    }

    /** 返回整个磁贴标签映射的副本（供 ViewModel 批量加载） */
    public java.util.Map<String, String> getPinnedTileLabelsRaw() {
        return new java.util.HashMap<>(pinnedTileLabels);
    }

    /** 设置/更新磁贴自定义名称；传 null 或空串则清除该标签 */
    public void setPinnedTileLabel(String versionId, String label) {
        if (label == null || label.isEmpty()) {
            pinnedTileLabels.remove(versionId);
        } else {
            pinnedTileLabels.put(versionId, label);
        }
        save();
    }

    // ===== 最近使用（LRU，最多 5 个） =====
    /** 返回最近使用列表的副本（避免外部原地修改导致 StateFlow 引用比较失效） */
    public java.util.List<String> getRecentVersions() {
        return new java.util.ArrayList<>(recentVersions);
    }

    /** 记录一次启动，置顶到最近使用列表头部（去重，超限裁剪） */
    public void recordRecentVersion(String versionId) {
        recentVersions.remove(versionId);
        recentVersions.add(0, versionId);
        while (recentVersions.size() > 5) {
            recentVersions.remove(recentVersions.size() - 1);
        }
        save();
    }

    public void clearRecentVersions() {
        recentVersions.clear(); save();
    }

    /** 从最近使用列表移除某版本（版本被删除时清理用） */
    public void removeRecentVersion(String versionId) {
        if (recentVersions.remove(versionId)) save();
    }

    // ===== 最后选中版本（启动时恢复） =====
    public String getLastSelectedVersion() { return lastSelectedVersion; }
    public void setLastSelectedVersion(String v) {
        lastSelectedVersion = v == null ? "" : v; save();
    }

    // ===== 最后游玩时间戳（versionId → epoch millis） =====
    public Long getLastPlayedTime(String versionId) {
        return lastPlayedTimes.get(versionId);
    }

    /** 返回整个最后游玩时间映射的副本（供 ViewModel 批量加载） */
    public java.util.Map<String, Long> getLastPlayedTimesRaw() {
        return new java.util.HashMap<>(lastPlayedTimes);
    }

    public void setLastPlayedTime(String versionId, long epochMillis) {
        lastPlayedTimes.put(versionId, epochMillis); save();
    }

    /** 移除某版本的最后游玩时间记录（版本被删除时清理用） */
    public void removeLastPlayedTime(String versionId) {
        if (lastPlayedTimes.remove(versionId) != null) save();
    }

    public String getCustomJvmArgs() { return customJvmArgs; }
    public void setCustomJvmArgs(String v) { customJvmArgs = v; save(); }

    public String getGcType() { return gcType; }
    public void setGcType(String v) { gcType = v; save(); }

    public boolean isUseAikarFlags() { return useAikarFlags; }
    public void setUseAikarFlags(boolean v) { useAikarFlags = v; save(); }

    public int getMinMemoryMb() { return minMemoryMb; }
    public void setMinMemoryMb(int v) { minMemoryMb = v; save(); }

    public int getMaxMemoryMb() { return maxMemoryMb; }
    public void setMaxMemoryMb(int v) { maxMemoryMb = v; save(); }

    public String getJavaPath() { return javaPath; }
    public void setJavaPath(String v) { javaPath = v == null ? "" : v; save(); }

    // ===== 游戏通用行为 =====
    public int getGameWindowWidth() { return gameWindowWidth; }
    public void setGameWindowWidth(int v) {
        gameWindowWidth = Math.max(1, v); save();
    }

    public int getGameWindowHeight() { return gameWindowHeight; }
    public void setGameWindowHeight(int v) {
        gameWindowHeight = Math.max(1, v); save();
    }

    public boolean isGameFullscreen() { return gameFullscreen; }
    public void setGameFullscreen(boolean v) { gameFullscreen = v; save(); }

    public boolean isGameDemo() { return gameDemo; }
    public void setGameDemo(boolean v) { gameDemo = v; save(); }

    public String getGameServerHost() { return gameServerHost; }
    public void setGameServerHost(String v) {
        gameServerHost = v == null ? "" : v.trim(); save();
    }

    public int getGameServerPort() { return gameServerPort; }
    public void setGameServerPort(int v) {
        if (v > 0 && v < 65536) gameServerPort = v; save();
    }

    /** 渲染器类型：AUTO（不注入）/ OPENGL / VULKAN */
    public String getGameRenderer() { return gameRenderer; }
    public void setGameRenderer(String v) {
        if (v == null) v = "AUTO";
        String upper = v.toUpperCase(java.util.Locale.ROOT);
        if (upper.equals("OPENGL") || upper.equals("VULKAN") || upper.equals("AUTO")) {
            gameRenderer = upper;
        }
        save();
    }

    // ===== 网络配置 =====
    public String getMirrorType() { return mirrorType; }
    public void setMirrorType(String v) { mirrorType = v; save(); }

    public String getCustomMirrorBase() { return customMirrorBase; }
    public void setCustomMirrorBase(String v) { customMirrorBase = v; save(); }

    public boolean isUseProxy() { return useProxy; }
    public void setUseProxy(boolean v) { useProxy = v; save(); }

    public String getProxyHost() { return proxyHost; }
    public void setProxyHost(String v) { proxyHost = v; save(); }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int v) { proxyPort = v; save(); }

    public boolean isUseHttpAuth() { return useHttpAuth; }
    public void setUseHttpAuth(boolean v) { useHttpAuth = v; save(); }

    public String getProxyUsername() { return proxyUsername; }
    public void setProxyUsername(String v) { proxyUsername = v; save(); }

    public String getProxyPassword() { return proxyPassword; }
    public void setProxyPassword(String v) { proxyPassword = v; save(); }

    public int getDownloadSpeedLimitKb() { return downloadSpeedLimitKb; }
    public void setDownloadSpeedLimitKb(int v) { downloadSpeedLimitKb = v; save(); }

    public int getDownloadRetryCount() { return downloadRetryCount; }
    public void setDownloadRetryCount(int v) { downloadRetryCount = v; save(); }

    public boolean isEnableResume() { return enableResume; }
    public void setEnableResume(boolean v) { enableResume = v; save(); }

    public int getChunkedDownloadThreads() { return chunkedDownloadThreads; }
    public void setChunkedDownloadThreads(int v) {
        chunkedDownloadThreads = Math.max(1, v); save();
    }

    // ===== 多人联机 =====
    public String getMpBackend() {
        // 默认使用 Terracotta（HMCL 同款官方陶瓦联机实现）
        if (mpBackend == null || mpBackend.isEmpty()) return "TERRACOTTA";
        return mpBackend;
    }
    public void setMpBackend(String v) {
        mpBackend = (v == null || v.isEmpty()) ? "TERRACOTTA" : v.toUpperCase(Locale.ROOT); save();
    }
    public String getConnectxServerAddress() { return connectxServerAddress; }
    public void setConnectxServerAddress(String v) { connectxServerAddress = v == null ? "" : v; save(); }
    public int getConnectxServerPort() { return connectxServerPort; }
    public void setConnectxServerPort(int v) {
        if (v > 0 && v < 65536) connectxServerPort = v; save();
    }
    public String getConnectxBinaryPath() { return connectxBinaryPath; }
    public void setConnectxBinaryPath(String v) { connectxBinaryPath = v == null ? "" : v; save(); }

    /** 从磁盘加载（不存在则保持默认） */
    public void load() {
        if (!Files.exists(file)) return;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (o.has("useDarkTheme")) useDarkTheme = o.get("useDarkTheme").getAsBoolean();
            if (o.has("dynamicColor")) dynamicColor = o.get("dynamicColor").getAsBoolean();
            if (o.has("language")) language = o.get("language").getAsString();
            if (o.has("firstLaunchCompleted")) firstLaunchCompleted = o.get("firstLaunchCompleted").getAsBoolean();
            if (o.has("pinnedVersions")) {
                pinnedVersions = new java.util.ArrayList<>();
                for (var e : o.getAsJsonArray("pinnedVersions")) {
                    pinnedVersions.add(e.getAsString());
                }
            }
            if (o.has("recentVersions")) {
                recentVersions = new java.util.ArrayList<>();
                for (var e : o.getAsJsonArray("recentVersions")) {
                    recentVersions.add(e.getAsString());
                }
            }
            if (o.has("lastSelectedVersion")) {
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
            if (o.has("customJvmArgs")) customJvmArgs = o.get("customJvmArgs").getAsString();
            if (o.has("gcType")) gcType = o.get("gcType").getAsString();
            if (o.has("useAikarFlags")) useAikarFlags = o.get("useAikarFlags").getAsBoolean();
            if (o.has("minMemoryMb")) minMemoryMb = o.get("minMemoryMb").getAsInt();
            if (o.has("maxMemoryMb")) maxMemoryMb = o.get("maxMemoryMb").getAsInt();
            if (o.has("javaPath")) javaPath = o.get("javaPath").getAsString();
            if (o.has("gameWindowWidth")) gameWindowWidth = o.get("gameWindowWidth").getAsInt();
            if (o.has("gameWindowHeight")) gameWindowHeight = o.get("gameWindowHeight").getAsInt();
            if (o.has("gameFullscreen")) gameFullscreen = o.get("gameFullscreen").getAsBoolean();
            if (o.has("gameDemo")) gameDemo = o.get("gameDemo").getAsBoolean();
            if (o.has("gameServerHost")) gameServerHost = o.get("gameServerHost").getAsString();
            if (o.has("gameServerPort")) gameServerPort = o.get("gameServerPort").getAsInt();
            if (o.has("gameRenderer")) gameRenderer = o.get("gameRenderer").getAsString();
            if (o.has("mirrorType")) mirrorType = o.get("mirrorType").getAsString();
            if (o.has("customMirrorBase")) customMirrorBase = o.get("customMirrorBase").getAsString();
            if (o.has("useProxy")) useProxy = o.get("useProxy").getAsBoolean();
            if (o.has("proxyHost")) proxyHost = o.get("proxyHost").getAsString();
            if (o.has("proxyPort")) proxyPort = o.get("proxyPort").getAsInt();
            if (o.has("useHttpAuth")) useHttpAuth = o.get("useHttpAuth").getAsBoolean();
            if (o.has("proxyUsername")) proxyUsername = o.get("proxyUsername").getAsString();
            if (o.has("proxyPassword")) proxyPassword = o.get("proxyPassword").getAsString();
            if (o.has("downloadSpeedLimitKb")) downloadSpeedLimitKb = o.get("downloadSpeedLimitKb").getAsInt();
            if (o.has("downloadRetryCount")) downloadRetryCount = o.get("downloadRetryCount").getAsInt();
            if (o.has("enableResume")) enableResume = o.get("enableResume").getAsBoolean();
            if (o.has("chunkedDownloadThreads")) chunkedDownloadThreads = o.get("chunkedDownloadThreads").getAsInt();
            if (o.has("mpBackend")) mpBackend = o.get("mpBackend").getAsString();
            if (o.has("connectxServerAddress")) connectxServerAddress = o.get("connectxServerAddress").getAsString();
            if (o.has("connectxServerPort")) connectxServerPort = o.get("connectxServerPort").getAsInt();
            if (o.has("connectxBinaryPath")) connectxBinaryPath = o.get("connectxBinaryPath").getAsString();
        } catch (Throwable ignored) {
        }
    }

    /** 保存到磁盘 */
    public void save() {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("useDarkTheme", useDarkTheme);
            o.addProperty("dynamicColor", dynamicColor);
            o.addProperty("language", language);
            o.addProperty("firstLaunchCompleted", firstLaunchCompleted);
            com.google.gson.JsonArray pinArr = new com.google.gson.JsonArray();
            for (String v : pinnedVersions) pinArr.add(v);
            o.add("pinnedVersions", pinArr);
            // 最近使用
            com.google.gson.JsonArray recentArr = new com.google.gson.JsonArray();
            for (String v : recentVersions) recentArr.add(v);
            o.add("recentVersions", recentArr);
            o.addProperty("lastSelectedVersion", lastSelectedVersion);
            JsonObject timesObj = new JsonObject();
            for (var entry : lastPlayedTimes.entrySet()) {
                timesObj.addProperty(entry.getKey(), entry.getValue());
            }
            o.add("lastPlayedTimes", timesObj);
            // 磁贴自定义名称
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
            o.addProperty("gameWindowWidth", gameWindowWidth);
            o.addProperty("gameWindowHeight", gameWindowHeight);
            o.addProperty("gameFullscreen", gameFullscreen);
            o.addProperty("gameDemo", gameDemo);
            o.addProperty("gameServerHost", gameServerHost);
            o.addProperty("gameServerPort", gameServerPort);
            o.addProperty("gameRenderer", gameRenderer);
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
            o.addProperty("mpBackend", mpBackend);
            o.addProperty("connectxServerAddress", connectxServerAddress);
            o.addProperty("connectxServerPort", connectxServerPort);
            o.addProperty("connectxBinaryPath", connectxBinaryPath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, gson.toJson(o));
        } catch (Throwable ignored) {
        }
    }
}
