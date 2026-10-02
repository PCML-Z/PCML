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
    private final DebugOptions debug;

    public VersionSettings(String displayName, String gameDir, String iconPath,
                           int minMemoryMb, int maxMemoryMb, String javaPath, String extraArgs,
                           int windowWidth, int windowHeight, int fullscreenMode) {
        this(displayName, gameDir, iconPath, minMemoryMb, maxMemoryMb, javaPath, extraArgs,
                windowWidth, windowHeight, fullscreenMode, DebugOptions.defaults());
    }

    public VersionSettings(String displayName, String gameDir, String iconPath,
                           int minMemoryMb, int maxMemoryMb, String javaPath, String extraArgs,
                           int windowWidth, int windowHeight, int fullscreenMode,
                           DebugOptions debug) {
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
        this.debug = debug == null ? DebugOptions.defaults() : debug;
    }

    public static VersionSettings empty() {
        return EMPTY;
    }

    public VersionSettings withJavaPath(String javaPath) {
        return new VersionSettings(displayName, gameDir, iconPath, minMemoryMb, maxMemoryMb,
                javaPath, extraArgs, windowWidth, windowHeight, fullscreenMode, debug);
    }

    public VersionSettings withDebug(DebugOptions debug) {
        return new VersionSettings(displayName, gameDir, iconPath, minMemoryMb, maxMemoryMb,
                javaPath, extraArgs, windowWidth, windowHeight, fullscreenMode, debug);
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
                mode,
                debug);
    }

    public boolean isBlank() {
        return displayName.isEmpty() && gameDir.isEmpty() && iconPath.isEmpty()
                && minMemoryMb == 0 && maxMemoryMb == 0 && javaPath.isEmpty() && extraArgs.isEmpty()
                && windowWidth == 0 && windowHeight == 0 && fullscreenMode == FULLSCREEN_INHERIT
                && debug.isDefault();
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
    public DebugOptions getDebug() { return debug; }

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
        if (!debug.isDefault()) o.add("debug", debug.toJson());
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
                        ? o.get("fullscreenMode").getAsInt() : FULLSCREEN_INHERIT,
                DebugOptions.fromJson(o.has("debug") && o.get("debug").isJsonObject()
                        ? o.getAsJsonObject("debug") : null));
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

    /** 某一个游戏的调试选项。默认值表示不改变原来的启动方式。 */
    public static final class DebugOptions {
        public static final String API_DEFAULT = "DEFAULT";
        public static final String API_OPENGL = "OPENGL";
        public static final String API_VULKAN = "VULKAN";
        public static final String DRIVER_DEFAULT = "DEFAULT";
        public static final String DRIVER_LLVMPIPE = "LLVMPIPE";
        public static final String DRIVER_ZINK = "ZINK";
        public static final String DRIVER_D3D12 = "D3D12";
        public static final String DRIVER_LAVAPIPE = "LAVAPIPE";
        public static final String DRIVER_DOZEN = "DOZEN";

        private static final DebugOptions DEFAULTS = new DebugOptions(
                "", API_DEFAULT, DRIVER_DEFAULT,
                false, false, false, false, false, false, false);

        private final String nativesDir;
        private final String graphicsApi;
        private final String driver;
        private final boolean skipDefaultJvmArgs;
        private final boolean skipOptimizingJvmArgs;
        private final boolean skipGameCheck;
        private final boolean skipJvmCheck;
        private final boolean skipNativesReplace;
        private final boolean useNativeGlfw;
        private final boolean useNativeOpenAl;

        public DebugOptions(String nativesDir, String graphicsApi, String driver,
                            boolean skipDefaultJvmArgs, boolean skipOptimizingJvmArgs,
                            boolean skipGameCheck, boolean skipJvmCheck, boolean skipNativesReplace,
                            boolean useNativeGlfw, boolean useNativeOpenAl) {
            this.nativesDir = cleanNativesDir(nativesDir);
            this.graphicsApi = allowedApi(graphicsApi);
            this.driver = driverFits(this.graphicsApi, driver) ? driver : DRIVER_DEFAULT;
            this.skipDefaultJvmArgs = skipDefaultJvmArgs;
            this.skipOptimizingJvmArgs = skipOptimizingJvmArgs;
            this.skipGameCheck = skipGameCheck;
            this.skipJvmCheck = skipJvmCheck;
            this.skipNativesReplace = skipNativesReplace;
            this.useNativeGlfw = useNativeGlfw;
            this.useNativeOpenAl = useNativeOpenAl;
        }

        public static DebugOptions defaults() {
            return DEFAULTS;
        }

        public String getNativesDir() { return nativesDir; }
        public String getGraphicsApi() { return graphicsApi; }
        public String getDriver() { return driver; }
        public boolean isSkipDefaultJvmArgs() { return skipDefaultJvmArgs; }
        public boolean isSkipOptimizingJvmArgs() { return skipOptimizingJvmArgs; }
        public boolean isSkipGameCheck() { return skipGameCheck; }
        public boolean isSkipJvmCheck() { return skipJvmCheck; }
        public boolean isSkipNativesReplace() { return skipNativesReplace; }
        public boolean isUseNativeGlfw() { return useNativeGlfw; }
        public boolean isUseNativeOpenAl() { return useNativeOpenAl; }

        public boolean isDefault() {
            return nativesDir.isEmpty()
                    && API_DEFAULT.equals(graphicsApi)
                    && DRIVER_DEFAULT.equals(driver)
                    && !skipDefaultJvmArgs && !skipOptimizingJvmArgs
                    && !skipGameCheck && !skipJvmCheck && !skipNativesReplace
                    && !useNativeGlfw && !useNativeOpenAl;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            if (!nativesDir.isEmpty()) o.addProperty("nativesDir", nativesDir);
            if (!API_DEFAULT.equals(graphicsApi)) o.addProperty("graphicsApi", graphicsApi);
            if (!DRIVER_DEFAULT.equals(driver)) o.addProperty("driver", driver);
            if (skipDefaultJvmArgs) o.addProperty("skipDefaultJvmArgs", true);
            if (skipOptimizingJvmArgs) o.addProperty("skipOptimizingJvmArgs", true);
            if (skipGameCheck) o.addProperty("skipGameCheck", true);
            if (skipJvmCheck) o.addProperty("skipJvmCheck", true);
            if (skipNativesReplace) o.addProperty("skipNativesReplace", true);
            if (useNativeGlfw) o.addProperty("useNativeGlfw", true);
            if (useNativeOpenAl) o.addProperty("useNativeOpenAl", true);
            return o;
        }

        public static DebugOptions fromJson(JsonObject o) {
            if (o == null) return defaults();
            return new DebugOptions(
                    text(o, "nativesDir"),
                    text(o, "graphicsApi"),
                    text(o, "driver"),
                    flag(o, "skipDefaultJvmArgs"),
                    flag(o, "skipOptimizingJvmArgs"),
                    flag(o, "skipGameCheck"),
                    flag(o, "skipJvmCheck"),
                    flag(o, "skipNativesReplace"),
                    flag(o, "useNativeGlfw"),
                    flag(o, "useNativeOpenAl"));
        }

        private static boolean driverFits(String api, String driver) {
            if (driver == null || DRIVER_DEFAULT.equals(driver)) return true;
            if (API_OPENGL.equals(api)) {
                return DRIVER_LLVMPIPE.equals(driver) || DRIVER_ZINK.equals(driver) || DRIVER_D3D12.equals(driver);
            }
            if (API_VULKAN.equals(api)) {
                return DRIVER_LAVAPIPE.equals(driver) || DRIVER_DOZEN.equals(driver);
            }
            return false;
        }

        private static String allowedApi(String raw) {
            if (API_OPENGL.equals(raw) || API_VULKAN.equals(raw)) return raw;
            return API_DEFAULT;
        }

        private static String cleanNativesDir(String raw) {
            String path = cleanPath(raw, 500);
            if (path.isEmpty() || path.contains("..")) return "";
            try {
                Path parsed = Paths.get(path);
                if (!parsed.isAbsolute()) return "";
                return parsed.normalize().toString();
            } catch (RuntimeException e) {
                return "";
            }
        }

        private static boolean flag(JsonObject o, String key) {
            if (!o.has(key) || o.get(key).isJsonNull()) return false;
            try {
                return o.get(key).getAsBoolean();
            } catch (RuntimeException e) {
                return false;
            }
        }
    }
}
