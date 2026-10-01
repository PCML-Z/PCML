package com.pmcl.core.preferences;

import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 某一个游戏版本自己的设置。数值 0、全屏 -1、空字符串都表示继续用全局设置。
 */
public final class VersionSettings {

    public static final int FULLSCREEN_INHERIT = -1;
    public static final int FULLSCREEN_WINDOW = 0;
    public static final int FULLSCREEN_ON = 1;

    private static final VersionSettings EMPTY = new VersionSettings(
            "", "", "", 0, 0, "", "", 0, 0, FULLSCREEN_INHERIT);

    private final String displayName;
    private final String gameDir;
    private final String iconPath;
    private final int minMemoryMb;
    private final int maxMemoryMb;
    private final String javaPath;
    private final String extraArgs;
    private final int windowWidth;
    private final int windowHeight;
    private final int fullscreenMode;

    public VersionSettings(String displayName, String gameDir, String iconPath,
                           int minMemoryMb, int maxMemoryMb, String javaPath, String extraArgs,
                           int windowWidth, int windowHeight, int fullscreenMode) {
        this.displayName = cleanName(displayName);
        this.gameDir = cleanGameDir(gameDir);
        this.iconPath = cleanIcon(iconPath);
        int min = minMemoryMb <= 0 ? 0 : Math.max(128, minMemoryMb);
        int max = maxMemoryMb <= 0 ? 0 : Math.max(512, maxMemoryMb);
        if (min > 0 && max > 0 && min > max) min = max;
        this.minMemoryMb = min;
        this.maxMemoryMb = max;
        this.javaPath = cleanPath(javaPath, 500);
        this.extraArgs = cleanArgs(extraArgs);
        this.windowWidth = windowWidth <= 0 ? 0 : Math.min(windowWidth, 16384);
        this.windowHeight = windowHeight <= 0 ? 0 : Math.min(windowHeight, 16384);
        if (fullscreenMode == FULLSCREEN_WINDOW || fullscreenMode == FULLSCREEN_ON) {
            this.fullscreenMode = fullscreenMode;
        } else {
            this.fullscreenMode = FULLSCREEN_INHERIT;
        }
    }

    public static VersionSettings empty() {
        return EMPTY;
    }

    public VersionSettings withJavaPath(String javaPath) {
        return new VersionSettings(displayName, gameDir, iconPath, minMemoryMb, maxMemoryMb,
                javaPath, extraArgs, windowWidth, windowHeight, fullscreenMode);
    }

    /**
     * 还没单独填过的字段用默认值补上。已经写过的字段保持不变，所以每个游戏各存一份。
     */
    public VersionSettings fillDefaults(String versionId, String defaultGameDir,
                                        int defaultMinMemoryMb, int defaultMaxMemoryMb,
                                        String defaultJavaPath, String defaultArgs,
                                        int defaultWidth, int defaultHeight,
                                        boolean defaultFullscreen, String defaultIcon) {
        int mode = fullscreenMode;
        if (mode != FULLSCREEN_WINDOW && mode != FULLSCREEN_ON) {
            mode = defaultFullscreen ? FULLSCREEN_ON : FULLSCREEN_WINDOW;
        }
        return new VersionSettings(
                displayName.isEmpty() ? versionId : displayName,
                gameDir.isEmpty() ? defaultGameDir : gameDir,
                iconPath.isEmpty() ? defaultIcon : iconPath,
                minMemoryMb > 0 ? minMemoryMb : defaultMinMemoryMb,
                maxMemoryMb > 0 ? maxMemoryMb : defaultMaxMemoryMb,
                javaPath.isEmpty() ? defaultJavaPath : javaPath,
                extraArgs.isEmpty() ? defaultArgs : extraArgs,
                windowWidth > 0 ? windowWidth : defaultWidth,
                windowHeight > 0 ? windowHeight : defaultHeight,
                mode);
    }

    public boolean isBlank() {
        return displayName.isEmpty() && gameDir.isEmpty() && iconPath.isEmpty()
                && minMemoryMb == 0 && maxMemoryMb == 0 && javaPath.isEmpty() && extraArgs.isEmpty()
                && windowWidth == 0 && windowHeight == 0 && fullscreenMode == FULLSCREEN_INHERIT;
    }

    public String getDisplayName() { return displayName; }
    public String getGameDir() { return gameDir; }
    public String getIconPath() { return iconPath; }
    public int getMinMemoryMb() { return minMemoryMb; }
    public int getMaxMemoryMb() { return maxMemoryMb; }
    public String getJavaPath() { return javaPath; }
    public String getExtraArgs() { return extraArgs; }
    public int getWindowWidth() { return windowWidth; }
    public int getWindowHeight() { return windowHeight; }
    public int getFullscreenMode() { return fullscreenMode; }

    public int memoryMin(int global) { return minMemoryMb > 0 ? minMemoryMb : global; }
    public int memoryMax(int global) { return maxMemoryMb > 0 ? maxMemoryMb : global; }
    public String jvmArgs(String global) { return extraArgs.isEmpty() ? (global == null ? "" : global) : extraArgs; }
    public int width(int global) { return windowWidth > 0 ? windowWidth : global; }
    public int height(int global) { return windowHeight > 0 ? windowHeight : global; }

    public boolean fullscreen(boolean global) {
        if (fullscreenMode == FULLSCREEN_ON) return true;
        if (fullscreenMode == FULLSCREEN_WINDOW) return false;
        return global;
    }

    public String icon(String global) {
        return iconPath.isEmpty() ? (global == null ? "" : global) : iconPath;
    }

    /** 空或非法路径返回 null，表示继续用原来的游戏目录。 */
    public static Path parseGameDir(String raw) {
        String cleaned = cleanGameDir(raw);
        if (cleaned.isEmpty()) return null;
        return Paths.get(cleaned);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        if (!displayName.isEmpty()) o.addProperty("displayName", displayName);
        if (!gameDir.isEmpty()) o.addProperty("gameDir", gameDir);
        if (!iconPath.isEmpty()) o.addProperty("iconPath", iconPath);
        if (minMemoryMb > 0) o.addProperty("minMemoryMb", minMemoryMb);
        if (maxMemoryMb > 0) o.addProperty("maxMemoryMb", maxMemoryMb);
        if (!javaPath.isEmpty()) o.addProperty("javaPath", javaPath);
        if (!extraArgs.isEmpty()) o.addProperty("extraArgs", extraArgs);
        if (windowWidth > 0) o.addProperty("windowWidth", windowWidth);
        if (windowHeight > 0) o.addProperty("windowHeight", windowHeight);
        if (fullscreenMode != FULLSCREEN_INHERIT) o.addProperty("fullscreenMode", fullscreenMode);
        return o;
    }

    public static VersionSettings fromJson(JsonObject o) {
        if (o == null) return empty();
        return new VersionSettings(
                text(o, "displayName"),
                text(o, "gameDir"),
                text(o, "iconPath"),
                number(o, "minMemoryMb"),
                number(o, "maxMemoryMb"),
                text(o, "javaPath"),
                text(o, "extraArgs"),
                number(o, "windowWidth"),
                number(o, "windowHeight"),
                o.has("fullscreenMode") && !o.get("fullscreenMode").isJsonNull()
                        ? o.get("fullscreenMode").getAsInt() : FULLSCREEN_INHERIT);
    }

    private static String text(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int number(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return 0;
        try {
            return o.get(key).getAsInt();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String cleanName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < 64; i++) {
            char c = raw.charAt(i);
            if (c >= 32 && c != '/' && c != '\\') sb.append(c);
        }
        return sb.toString().trim();
    }

    private static String cleanGameDir(String raw) {
        String path = cleanPath(raw, 400);
        if (path.isEmpty() || path.contains("..")) return "";
        try {
            Path parsed = Paths.get(path);
            if (!parsed.isAbsolute()) return "";
            return parsed.normalize().toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String cleanIcon(String raw) {
        String path = cleanPath(raw, 500);
        if (path.isEmpty() || path.contains("..")) return "";
        try {
            Path parsed = Paths.get(path);
            if (!parsed.isAbsolute()) return "";
            parsed = parsed.normalize();
            String name = parsed.getFileName() == null ? "" : parsed.getFileName().toString().toLowerCase();
            if (!name.endsWith(".png")) return "";
            return parsed.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String cleanPath(String raw, int max) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty() || text.length() > max || text.indexOf('\0') >= 0) return "";
        return text;
    }

    private static String cleanArgs(String raw) {
        if (raw == null) return "";
        String text = raw.replace('\r', ' ').trim();
        if (text.length() > 4000) text = text.substring(0, 4000);
        return text;
    }
}
