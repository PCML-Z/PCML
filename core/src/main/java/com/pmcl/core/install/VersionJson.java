package com.pmcl.core.install;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解析后的版本 JSON 模型。
 * <p>
 * Mojang 的版本 JSON 结构见
 * https://minecraft.wiki/w/Version_manifest.json
 */
public final class VersionJson {

    private final String id;
    private final String mainClass;
    private final String assets;            // 资产索引名（如 "1.20"）
    private final String inheritsFrom;      // 继承自的版本
    private final int javaVersion;          // 版本要求的 Java 主版本号（0=未指定）
    private final List<Library> libraries;
    private final Artifact clientArtifact;
    private final JsonObject rawJson;

    private VersionJson(String id, String mainClass, String assets, String inheritsFrom,
                        int javaVersion,
                        List<Library> libraries, Artifact clientArtifact, JsonObject rawJson) {
        this.id = id;
        this.mainClass = mainClass;
        this.assets = assets;
        this.inheritsFrom = inheritsFrom;
        this.javaVersion = javaVersion;
        this.libraries = libraries;
        this.clientArtifact = clientArtifact;
        this.rawJson = rawJson;
    }

    public static VersionJson parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String id = root.has("id") ? root.get("id").getAsString() : "";
        String mainClass = root.has("mainClass") ? root.get("mainClass").getAsString() : "";
        String assets = root.has("assets") ? root.get("assets").getAsString() : "";
        String inheritsFrom = root.has("inheritsFrom") ? root.get("inheritsFrom").getAsString() : null;

        // javaVersion.majorVersion：MC 1.13+ 才有此字段，旧版本（alpha/beta/1.7-）返回 0
        // alpha/beta 需要 Java 8；1.13-1.16.5 需要 Java 8；1.17 需要 16；1.18 需要 17；1.20.5+ 需要 21
        int javaVer = 0;
        if (root.has("javaVersion")) {
            JsonObject jv = root.getAsJsonObject("javaVersion");
            if (jv.has("majorVersion")) {
                try { javaVer = jv.get("majorVersion").getAsInt(); } catch (Exception ignored) {}
            }
        }

        List<Library> libs = new ArrayList<>();
        if (root.has("libraries")) {
            for (JsonElement e : root.getAsJsonArray("libraries")) {
                libs.add(Library.parse(e.getAsJsonObject()));
            }
        }

        Artifact client = null;
        if (root.has("downloads")) {
            JsonObject downloads = root.getAsJsonObject("downloads");
            if (downloads.has("client")) {
                client = Artifact.parse(downloads.getAsJsonObject("client"));
            }
        }

        return new VersionJson(id, mainClass, assets, inheritsFrom, javaVer,
                Collections.unmodifiableList(libs), client, root);
    }

    public String getId() { return id; }
    public String getMainClass() { return mainClass; }
    public String getAssets() { return assets; }
    public String getInheritsFrom() { return inheritsFrom; }
    /** 版本要求的 Java 主版本号（0=未指定，alpha/beta/1.7- 通常为 0 表示需要 Java 8） */
    public int getJavaVersion() { return javaVersion; }
    public List<Library> getLibraries() { return libraries; }
    public Artifact getClientArtifact() { return clientArtifact; }
    public JsonObject getRawJson() { return rawJson; }

    /**
     * 解析 JVM 参数（仅新格式 arguments.jvm；旧版本组装默认值）。
     * 支持带 OS 规则的复杂参数（如 macOS 的 -XstartOnFirstThread）。
     */
    public List<String> getJvmArgs() {
        List<String> result = new ArrayList<>();
        if (rawJson.has("arguments")) {
            JsonObject args = rawJson.getAsJsonObject("arguments");
            if (args.has("jvm")) {
                for (JsonElement e : args.getAsJsonArray("jvm")) {
                    if (e.isJsonPrimitive()) {
                        String s = e.getAsString();
                        // 跳过 -cp 和 ${classpath}，由 LaunchProfile.buildCommand 统一处理
                        if (s.equals("-cp") || s.equals("${classpath}")) continue;
                        result.add(s);
                    } else if (e.isJsonObject()) {
                        // 复杂参数：带 rules，需判断当前 OS 是否匹配
                        JsonObject obj = e.getAsJsonObject();
                        if (obj.has("rules") && obj.has("value")) {
                            if (matchesRules(obj.getAsJsonArray("rules"))) {
                                JsonElement val = obj.get("value");
                                if (val.isJsonArray()) {
                                    for (JsonElement v : val.getAsJsonArray()) {
                                        result.add(v.getAsString());
                                    }
                                } else {
                                    result.add(val.getAsString());
                                }
                            }
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 解析游戏参数（旧格式 game 字符串 或 新格式 arguments.game 数组）。
     * 支持带规则的复杂参数（如 demo/resolution 条件参数）。
     */
    public List<String> getGameArgs() {
        List<String> result = new ArrayList<>();
        if (rawJson.has("arguments")) {
            JsonObject args = rawJson.getAsJsonObject("arguments");
            if (args.has("game")) {
                for (JsonElement e : args.getAsJsonArray("game")) {
                    if (e.isJsonPrimitive()) {
                        result.add(e.getAsString());
                    } else if (e.isJsonObject()) {
                        // 复杂参数：带 rules（如 is_demo_user/has_custom_resolution）
                        // 这些 feature 规则默认不匹配，跳过
                    }
                }
            }
        } else if (rawJson.has("minecraftArguments")) {
            // 旧版本空格分隔
            String[] parts = rawJson.get("minecraftArguments").getAsString().split(" ");
            Collections.addAll(result, parts);
        }
        return result;
    }

    /**
     * 判断 rules 数组是否匹配当前系统。
     * rules 为空返回 true；多个 rule 时取最后一个匹配的 action。
     */
    private boolean matchesRules(JsonArray rules) {
        if (rules == null || rules.size() == 0) return true;
        String osName = currentOsName();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        for (JsonElement e : rules) {
            JsonObject rule = e.getAsJsonObject();
            String action = rule.get("action").getAsString();
            if (rule.has("os")) {
                JsonObject osObj = rule.getAsJsonObject("os");
                if (osObj.has("name")) {
                    String ruleOs = osObj.get("name").getAsString();
                    if (!ruleOs.equals(osName)) continue;
                }
                if (osObj.has("arch")) {
                    String ruleArch = osObj.get("arch").getAsString();
                    // "x86" 匹配 x86_64/amd64, "arm64" 匹配 aarch64
                    boolean archMatch = ruleArch.equals("x86") && (osArch.contains("x86") || osArch.contains("amd64"))
                            || ruleArch.equals("arm64") && (osArch.contains("aarch64") || osArch.contains("arm64"));
                    if (!archMatch) continue;
                }
            }
            return "allow".equals(action);
        }
        return false;
    }

    private static String currentOsName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    public JsonArray getRawLibraries() {
        return rawJson.has("libraries") ? rawJson.getAsJsonArray("libraries") : new JsonArray();
    }

    /** 单个下载件（client.jar / artifact / asset） */
    public static final class Artifact {
        private final String url;
        private final String sha1;
        private final long size;

        public Artifact(String url, String sha1, long size) {
            this.url = url;
            this.sha1 = sha1;
            this.size = size;
        }

        public static Artifact parse(JsonObject o) {
            return new Artifact(
                    o.get("url").getAsString(),
                    o.has("sha1") ? o.get("sha1").getAsString() : "",
                    o.has("size") ? o.get("size").getAsLong() : 0
            );
        }

        public String getUrl() { return url; }
        public String getSha1() { return sha1; }
        public long getSize() { return size; }
    }
}
