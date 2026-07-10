package com.pmcl.core.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import com.pmcl.core.install.VersionJson.Artifact;

/**
 * 版本依赖库，支持 rules（OS 过滤）和 natives（平台本地库）。
 * <p>
 * 架构感知：当运行在 ARM64 系统（如 Apple Silicon Mac）上时，优先尝试查找 arm64 版本的
 * native 库（如 natives-macos-arm64）。若版本 JSON 的 classifiers 中包含 arm64 变体则使用它，
 * 否则回退到 x86_64 版本（需配合 x86_64 Java + Rosetta 2 运行）。
 * <p>
 * 游戏 Java 的架构可能与启动器自身不同（如启动器用 ARM64 Java 运行，但游戏用 x86_64 Java 8
 * 启动 alpha/beta）。通过 {@link #setArchOverride(String)} 可设置架构覆盖，让 native 选择
 * 匹配游戏 Java 的架构而非启动器的架构。
 */
public final class Library {

    private final String name;          // 如 "com.mojang:minecraft:1.20" 或 "org.lwjgl:lwjgl:3.3.3:natives-macos"
    private final Artifact artifact;
    private final Map<String, Artifact> classifiers;  // classifier → artifact（含 natives）
    private final JsonObject natives;   // 平台 → classifier 映射
    private final JsonArray rules;      // 规则数组
    private final boolean nativeLib;
    private final String nameClassifier; // 从 name 中解析的 classifier（如 "natives-macos"），无则 null

    /** 架构覆盖（ThreadLocal），用于匹配游戏 Java 的架构而非启动器自身的架构 */
    private static final ThreadLocal<String> ARCH_OVERRIDE = new ThreadLocal<>();

    private Library(String name, Artifact artifact, Map<String, Artifact> classifiers,
                    JsonObject natives, JsonArray rules, boolean nativeLib, String nameClassifier) {
        this.name = name;
        this.artifact = artifact;
        this.classifiers = classifiers;
        this.natives = natives;
        this.rules = rules;
        this.nativeLib = nativeLib;
        this.nameClassifier = nameClassifier;
    }

    public static Library parse(JsonObject o) {
        String name = o.get("name").getAsString();
        // 解析 name 中的第四段 classifier（如 "org.lwjgl:lwjgl:3.3.3:natives-macos"）
        String[] parts = name.split(":");
        String nameCls = parts.length >= 4 ? parts[3] : null;
        Artifact art = null;
        Map<String, Artifact> classifs = new LinkedHashMap<>();
        if (o.has("downloads")) {
            JsonObject dl = o.getAsJsonObject("downloads");
            if (dl.has("artifact")) {
                art = VersionJson.Artifact.parse(dl.getAsJsonObject("artifact"));
            }
            if (dl.has("classifiers")) {
                JsonObject clObj = dl.getAsJsonObject("classifiers");
                for (Map.Entry<String, JsonElement> e : clObj.entrySet()) {
                    classifs.put(e.getKey(),
                            VersionJson.Artifact.parse(e.getValue().getAsJsonObject()));
                }
            }
        }
        JsonObject natives = o.has("natives") ? o.getAsJsonObject("natives") : null;
        JsonArray rules = o.has("rules") ? o.getAsJsonArray("rules") : null;
        // native 库：有 natives 字段，或 name classifier 以 "natives-" 开头
        boolean isNative = natives != null || (nameCls != null && nameCls.startsWith("natives-"));
        return new Library(name, art, Collections.unmodifiableMap(classifs),
                natives, rules, isNative, nameCls);
    }

    public String getName() { return name; }
    public Artifact getArtifact() { return artifact; }
    public Map<String, Artifact> getClassifiers() { return classifiers; }
    public boolean isNativeLib() { return nativeLib; }
    /** 从 name 中解析的 classifier（如 "natives-macos"），无则 null。 */
    public String getNameClassifier() { return nameClassifier; }

    /**
     * 设置架构覆盖，让 native classifier 选择匹配游戏 Java 的架构而非启动器自身架构。
     * 传入 "aarch64"/"arm64" 表示 ARM64，"x86_64"/"amd64" 表示 x86_64。
     * 必须在使用后调用 {@link #clearArchOverride()} 清除（建议用 try-finally）。
     */
    public static void setArchOverride(String arch) {
        ARCH_OVERRIDE.set(arch);
    }

    /** 清除架构覆盖 */
    public static void clearArchOverride() {
        ARCH_OVERRIDE.remove();
    }

    /**
     * 判断当前架构（或覆盖架构）是否为 ARM64。
     */
    private static boolean isArm64() {
        String override = ARCH_OVERRIDE.get();
        String arch = (override != null && !override.isEmpty()
                ? override : System.getProperty("os.arch", "")).toLowerCase();
        return arch.contains("aarch64") || arch.contains("arm64");
    }

    /**
     * 当前 OS 的 ARM64 native classifier（如 "natives-macos-arm64"）。
     */
    private static String arm64ClassifierForCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return "natives-macos-arm64";
        if (os.contains("win")) return "natives-windows-arm64";
        return "natives-linux-arm64";
    }

    /**
     * 根据当前 OS 判断是否允许加载该库。
     */
    public boolean appliesToCurrentOs() {
        if (rules == null) return true;
        boolean allowed = false;
        String osName = currentOsName();
        for (JsonElement e : rules) {
            JsonObject rule = e.getAsJsonObject();
            String action = rule.get("action").getAsString();
            if (rule.has("os")) {
                String ruleOs = rule.getAsJsonObject("os").get("name").getAsString();
                if (!ruleOs.equals(osName)) continue;
            }
            allowed = "allow".equals(action);
        }
        return allowed;
    }

    /**
     * 获取当前 OS 对应的 native classifier（如 "natives-linux"），无则返回 null。
     * <p>
     * ARM64 架构优先策略：当检测到 ARM64 时，先尝试查找 arm64 classifier
     * （如 "natives-macos-arm64"），若版本 JSON 的 classifiers 中存在则使用它；
     * 否则回退到 x86_64 版本（需配合 x86_64 Java + Rosetta 2 运行）。
     */
    public String getNativeClassifier() {
        if (natives == null) return null;
        String os = currentOsName();
        if (!natives.has(os)) return null;
        String classifier = natives.get(os).getAsString();

        // ARM64 架构：优先尝试 arm64 classifier（LWJGL 3.x 提供原生 arm64 支持）
        if (isArm64()) {
            String arm64Cls = arm64ClassifierForCurrentOs();
            if (arm64Cls != null && classifiers.containsKey(arm64Cls)) {
                return arm64Cls;
            }
            // arm64 classifier 不存在（如 LWJGL 2.x / alpha/beta），回退到 x86_64
            // 此时游戏 Java 必须也是 x86_64（通过 Rosetta 2），否则 native 加载会失败
        }
        return classifier.replace("${arch}", "64");
    }

    /**
     * 获取当前 OS 对应的 native artifact（解析后的下载信息）。
     * 若没有 natives 或对应 classifier 不存在于 classifiers，返回 null。
     */
    public Artifact getNativeArtifact() {
        String classifier = getNativeClassifier();
        if (classifier == null) return null;
        return classifiers.get(classifier);
    }

    private static String currentOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    /**
     * 根据 maven 坐标计算相对路径（主 artifact）。
     * 如 com.mojang:minecraft:1.20 → com/mojang/minecraft/1.20/minecraft-1.20.jar
     * 若 name 本身带 classifier（四段坐标），则包含 classifier。
     */
    public String getPath() {
        return mavenPath(name, nameClassifier);
    }

    /**
     * 根据 maven 坐标 + classifier 计算相对路径。
     * 如 com.mojang:glfw:1.0.0 + natives-linux →
     *     com/mojang/glfw/1.0.0/glfw-1.0.0-natives-linux.jar
     */
    public String getPathForClassifier(String classifier) {
        return mavenPath(name, classifier);
    }

    private static String mavenPath(String coords, String classifier) {
        String[] parts = coords.split(":");
        String groupPath = parts[0].replace('.', '/');
        String artifactId = parts[1];
        String version = parts[2];
        // 第四段（若存在）作为默认 classifier，可被参数覆盖
        String defaultCls = parts.length >= 4 ? parts[3] : null;
        String cls = classifier != null ? classifier : defaultCls;
        StringBuilder sb = new StringBuilder()
                .append(groupPath).append('/')
                .append(artifactId).append('/')
                .append(version).append('/')
                .append(artifactId).append('-').append(version);
        if (cls != null && !cls.isEmpty()) {
            sb.append('-').append(cls);
        }
        sb.append(".jar");
        return sb.toString();
    }

    /** 暴露 rules（给 installer 使用） */
    public JsonArray getRules() { return rules == null ? new JsonArray() : rules; }

    /**
     * 当前 OS 的 native classifier 后缀（MC 1.18+ 新格式）。
     * macOS Intel: "natives-macos"
     * macOS ARM (Apple Silicon): "natives-macos-arm64"
     * Windows x86_64: "natives-windows"
     * Windows ARM64: "natives-windows-arm64"
     * Linux x86_64: "natives-linux"
     * Linux ARM64: "natives-linux-arm64"
     */
    public static String currentNativeClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean arm = isArm64();
        if (os.contains("mac")) {
            return arm ? "natives-macos-arm64" : "natives-macos";
        }
        if (os.contains("win")) {
            return arm ? "natives-windows-arm64" : "natives-windows";
        }
        return arm ? "natives-linux-arm64" : "natives-linux";
    }

    /**
     * 判断此 library 的 nameClassifier 是否匹配当前平台的 native。
     * 用于 MC 1.18+ 新格式：native 库以独立 library 条目存在，name 带 ":natives-macos" 等。
     */
    public boolean matchesCurrentNative() {
        if (nameClassifier == null) return false;
        String want = currentNativeClassifier();
        return nameClassifier.equals(want);
    }
}
