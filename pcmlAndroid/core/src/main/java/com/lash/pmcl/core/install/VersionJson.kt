package com.lash.pmcl.core.install

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Collections

/**
 * 解析后的版本 JSON 模型。
 * <p>
 * Mojang 的版本 JSON 结构见 https://minecraft.wiki/w/Version_manifest.json
 *
 * Android 版本：保留完整解析能力（libraries、artifact、rules、arguments），
 * 用于下载/校验管线。JVM/game 参数解析保留（os.name=linux on Android），
 * 但 Android 无法实际启动 MC Java Edition。
 */
class VersionJson private constructor(
    val id: String,
    val mainClass: String,
    val assets: String,
    val inheritsFrom: String?,
    /** 版本要求的 Java 主版本号（0=未指定，alpha/beta/1.7- 通常为 0 表示需要 Java 8） */
    val javaVersion: Int,
    val libraries: List<Library>,
    val clientArtifact: Artifact?,
    val rawJson: JsonObject
) {
    /**
     * 游戏 Java 进程的 os.arch（如 "aarch64"、"x86_64"）。
     * 用于 matchesRules 判断 arch-specific 的 JVM 参数是否匹配。
     * null 时回退到启动器 os.arch。
     */
    private var gameJavaArch: String? = null

    fun setGameJavaArch(arch: String?) {
        this.gameJavaArch = arch
    }

    /**
     * 解析 JVM 参数（仅新格式 arguments.jvm；旧版本组装默认值）。
     */
    fun getJvmArgs(): List<String> {
        val result = ArrayList<String>()
        if (rawJson.has("arguments")) {
            val args = rawJson.getAsJsonObject("arguments")
            if (args.has("jvm")) {
                for (e in args.getAsJsonArray("jvm")) {
                    if (e.isJsonPrimitive) {
                        val s = e.asString
                        // 跳过 -cp 和 ${classpath}，由 LaunchProfile.buildCommand 统一处理
                        if (s == "-cp" || s == "\${classpath}") continue
                        result.add(s)
                    } else if (e.isJsonObject) {
                        val obj = e.asJsonObject
                        if (obj.has("rules") && obj.has("value")) {
                            if (matchesRules(obj.getAsJsonArray("rules"))) {
                                val valueElement = obj.get("value")
                                if (valueElement.isJsonArray) {
                                    for (v in valueElement.asJsonArray) {
                                        result.add(v.asString)
                                    }
                                } else {
                                    result.add(valueElement.asString)
                                }
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    /**
     * 解析游戏参数（旧格式 game 字符串 或 新格式 arguments.game 数组）。
     */
    fun getGameArgs(): List<String> = getGameArgs(false, false, 0, 0)

    /**
     * 解析游戏参数，并按启动器偏好评估 feature 规则。
     */
    fun getGameArgs(
        demoUser: Boolean, customResolution: Boolean,
        resolutionWidth: Int, resolutionHeight: Int
    ): List<String> {
        val features = HashMap<String, Boolean>()
        features["is_demo_user"] = demoUser
        features["has_custom_resolution"] = customResolution
        val result = ArrayList<String>()
        if (rawJson.has("arguments")) {
            val args = rawJson.getAsJsonObject("arguments")
            if (args.has("game")) {
                for (e in args.getAsJsonArray("game")) {
                    if (e.isJsonPrimitive) {
                        result.add(e.asString)
                    } else if (e.isJsonObject) {
                        val obj = e.asJsonObject
                        if (obj.has("rules") && obj.has("value")
                            && matchesRules(obj.getAsJsonArray("rules"), features)
                        ) {
                            val valueElement = obj.get("value")
                            if (valueElement.isJsonArray) {
                                for (v in valueElement.asJsonArray) {
                                    result.add(
                                        replaceResolutionPlaceholders(
                                            v.asString, resolutionWidth, resolutionHeight
                                        )
                                    )
                                }
                            } else if (valueElement.isJsonPrimitive) {
                                result.add(
                                    replaceResolutionPlaceholders(
                                        valueElement.asString, resolutionWidth, resolutionHeight
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } else if (rawJson.has("minecraftArguments") && !rawJson.get("minecraftArguments").isJsonNull) {
            // 旧版本空格分隔
            val parts = rawJson.get("minecraftArguments").asString.split(" ")
            result.addAll(parts)
        }
        return result
    }

    fun getRawLibraries(): JsonArray =
        if (rawJson.has("libraries")) rawJson.getAsJsonArray("libraries") else JsonArray()

    private fun matchesRules(rules: JsonArray): Boolean = matchesRules(rules, emptyMap())

    /**
     * 判断 rules 是否匹配：OS + features。
     * 无 rules → true；有 rules 时默认 disallow，按顺序应用每条匹配 rule 的 action（取最后匹配）。
     */
    private fun matchesRules(rules: JsonArray, features: Map<String, Boolean>): Boolean {
        if (rules.size() == 0) return true
        val osName = currentOsName()
        val osArch = if (!gameJavaArch.isNullOrEmpty())
            gameJavaArch!!.lowercase() else (System.getProperty("os.arch") ?: "").lowercase()
        val feats: Map<String, Boolean> = if (features.isNotEmpty()) features else emptyMap()
        var allowed = false
        for (e in rules) {
            val rule = e.asJsonObject
            val action = if (rule.has("action") && !rule.get("action").isJsonNull)
                rule.get("action").asString else ""
            if (rule.has("os")) {
                val osObj = rule.getAsJsonObject("os")
                if (osObj.has("name")) {
                    val ruleOs = osObj.get("name").asString
                    if (ruleOs != osName) continue
                }
                if (osObj.has("arch")) {
                    val ruleArch = osObj.get("arch").asString
                    val archMatch = ruleArch == "x86" && (osArch.contains("x86") || osArch.contains("amd64"))
                        || ruleArch == "arm64" && (osArch.contains("aarch64") || osArch.contains("arm64"))
                    if (!archMatch) continue
                }
            }
            if (rule.has("features") && rule.get("features").isJsonObject) {
                val want = rule.getAsJsonObject("features")
                var featuresMatch = true
                for ((feKey, feVal) in want.entrySet()) {
                    val expected = !feVal.isJsonNull && feVal.asBoolean
                    val actual = java.lang.Boolean.TRUE == feats[feKey]
                    if (expected != actual) {
                        featuresMatch = false
                        break
                    }
                }
                if (!featuresMatch) continue
            }
            allowed = "allow" == action
        }
        return allowed
    }

    companion object {
        fun parse(json: String): VersionJson {
            val root = JsonParser.parseString(json).asJsonObject
            val id = if (root.has("id") && !root.get("id").isJsonNull) root.get("id").asString else ""
            val mainClass = if (root.has("mainClass") && !root.get("mainClass").isJsonNull)
                root.get("mainClass").asString else ""
            val assets = if (root.has("assets") && !root.get("assets").isJsonNull)
                root.get("assets").asString else ""
            val inheritsFrom = if (root.has("inheritsFrom") && !root.get("inheritsFrom").isJsonNull)
                root.get("inheritsFrom").asString else null

            // javaVersion.majorVersion：MC 1.13+ 才有此字段，旧版本返回 0
            var javaVer = 0
            if (root.has("javaVersion")) {
                val jv = root.getAsJsonObject("javaVersion")
                if (jv.has("majorVersion")) {
                    try {
                        javaVer = jv.get("majorVersion").asInt
                    } catch (_: Exception) {
                    }
                }
            }

            val libs = ArrayList<Library>()
            if (root.has("libraries")) {
                for (e in root.getAsJsonArray("libraries")) {
                    libs.add(Library.parse(e.asJsonObject))
                }
            }

            var client: Artifact? = null
            if (root.has("downloads")) {
                val downloads = root.getAsJsonObject("downloads")
                if (downloads.has("client")) {
                    client = Artifact.parse(downloads.getAsJsonObject("client"))
                }
            }

            return VersionJson(
                id, mainClass, assets, inheritsFrom, javaVer,
                Collections.unmodifiableList(libs), client, root
            )
        }

        private fun replaceResolutionPlaceholders(arg: String?, width: Int, height: Int): String {
            if (arg == null) return ""
            return arg
                .replace("\${resolution_width}", Math.max(1, width).toString())
                .replace("\${resolution_height}", Math.max(1, height).toString())
        }

        private fun currentOsName(): String {
            val os = (System.getProperty("os.name") ?: "").lowercase()
            if (os.contains("win")) return "windows"
            if (os.contains("mac")) return "osx"
            return "linux"
        }
    }

    /** 单个下载件（client.jar / artifact / asset） */
    data class Artifact(
        val url: String,
        val sha1: String,
        val size: Long
    ) {
        companion object {
            fun parse(o: JsonObject): Artifact = Artifact(
                url = if (o.has("url") && !o.get("url").isJsonNull) o.get("url").asString else "",
                sha1 = if (o.has("sha1") && !o.get("sha1").isJsonNull) o.get("sha1").asString else "",
                size = if (o.has("size") && !o.get("size").isJsonNull) o.get("size").asLong else 0
            )
        }
    }
}
