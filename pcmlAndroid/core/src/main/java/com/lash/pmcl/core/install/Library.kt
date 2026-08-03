package com.lash.pmcl.core.install

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.lash.pmcl.core.install.VersionJson.Artifact
import java.util.Collections

/**
 * 版本依赖库，支持 rules（OS 过滤）和 natives（平台本地库）。
 *
 * Android 版本：保留 OS 规则解析（Android 上 os.name=linux，os.arch=aarch64），
 * 但 native 库选择对 Android 启动器意义不大（Android 无法启动 MC Java Edition），
 * 主要用于下载/校验管线。
 */
class Library private constructor(
    val name: String,
    val artifact: Artifact?,
    private val classifiers: Map<String, Artifact>,
    private val natives: JsonObject?,
    private val rules: JsonArray?,
    val isNativeLib: Boolean,
    /** 从 name 中解析的 classifier（如 "natives-macos"），无则 null */
    val nameClassifier: String?,
    /** 顶层 maven 仓库 url（Fabric/Forge/NeoForge 第三方库格式），无则空串 */
    val url: String
) {
    fun getClassifiers(): Map<String, Artifact> = classifiers

    /**
     * 判断当前 OS（及 arch 规则）是否允许加载该库。
     * 语义与 Mojang：有 rules 时默认 disallow，按顺序应用每条匹配 rule 的 action（取最后匹配）。
     */
    fun appliesToCurrentOs(): Boolean {
        if (rules == null || rules.size() == 0) return true
        var allowed = false
        val osName = currentOsName()
        val osArch = effectiveArch().lowercase()
        for (e in rules) {
            val rule = e.asJsonObject
            val action = if (rule.has("action") && !rule.get("action").isJsonNull)
                rule.get("action").asString else ""
            if (rule.has("os") && !rule.get("os").isJsonNull) {
                val osObj = rule.getAsJsonObject("os")
                if (osObj.has("name") && !osObj.get("name").isJsonNull) {
                    val ruleOs = osObj.get("name").asString
                    if (ruleOs != osName) continue
                }
                if (osObj.has("arch") && !osObj.get("arch").isJsonNull) {
                    val ruleArch = osObj.get("arch").asString
                    val archMatch = ruleArch == "x86"
                        && (osArch.contains("x86") || osArch.contains("amd64"))
                        || ruleArch == "arm64"
                        && (osArch.contains("aarch64") || osArch.contains("arm64"))
                    if (!archMatch) continue
                }
            }
            allowed = "allow" == action
        }
        return allowed
    }

    /**
     * 获取当前 OS 对应的 native classifier（如 "natives-linux"），无则返回 null。
     */
    fun getNativeClassifier(): String? {
        if (natives == null) return null
        val os = currentOsName()
        if (!natives.has(os) || natives.get(os).isJsonNull) return null
        val classifier = natives.get(os).asString
        // 替换 ${arch} 为空字符串，让 natives-linux 匹配 "natives-linux"
        // 同时尝试带 arch 后缀的变体（如 natives-linux-arm64）
        val noArch = classifier.replace("\${arch}", "")
        // 如果 noArch 有值（不以 } 结尾说明是有效替换），并且 classifiers 中有直接匹配，优先返回
        if (noArch.isNotEmpty() && classifiers.containsKey(noArch)) {
            return noArch
        }
        // 否则尝试带架构后缀（Android ARM64 → "arm64", "aarch64"）
        val archBits = if (isArm64()) "arm64" else "64"
        val withArch = classifier.replace("\${arch}", archBits)
        if (classifiers.containsKey(withArch)) return withArch
        // 再尝试不带 arch 但去掉模板标记的直接匹配
        val bare = classifier.replace("\${arch}", "")
        return if (classifiers.containsKey(bare)) bare else noArch
    }

    /**
     * 获取当前 OS 对应的 native artifact（解析后的下载信息）。
     */
    fun getNativeArtifact(): Artifact? {
        val classifier = getNativeClassifier() ?: return null
        return classifiers[classifier]
    }

    /**
     * 根据 maven 坐标计算相对路径（主 artifact）。
     */
    fun getPath(): String = mavenPath(name, nameClassifier)

    /**
     * 根据 maven 坐标 + classifier 计算相对路径。
     */
    fun getPathForClassifier(classifier: String?): String = mavenPath(name, classifier)

    /** 暴露 rules（给 installer 使用） */
    fun getRules(): JsonArray = rules ?: JsonArray()

    companion object {
        /** 架构覆盖（ThreadLocal），用于匹配游戏 Java 的架构而非启动器自身的架构 */
        private val ARCH_OVERRIDE: ThreadLocal<String?> = ThreadLocal()

        fun setArchOverride(arch: String?) {
            ARCH_OVERRIDE.set(arch)
        }

        fun clearArchOverride() {
            ARCH_OVERRIDE.remove()
        }

        fun getArchOverride(): String? = ARCH_OVERRIDE.get()

        private fun effectiveArch(): String {
            val override = ARCH_OVERRIDE.get()
            return if (!override.isNullOrEmpty()) override
            else System.getProperty("os.arch", "")
        }

        private fun isArm64(): Boolean {
            val arch = effectiveArch().lowercase()
            return arch.contains("aarch64") || arch.contains("arm64")
        }

        fun parse(o: JsonObject): Library {
            val name = if (o.has("name") && !o.get("name").isJsonNull)
                o.get("name").asString else ""
            val parts = name.split(":")
            val nameCls = if (parts.size >= 4) parts[3] else null
            var art: Artifact? = null
            val classifs = LinkedHashMap<String, Artifact>()
            if (o.has("downloads")) {
                val dl = o.getAsJsonObject("downloads")
                if (dl.has("artifact")) {
                    art = Artifact.parse(dl.getAsJsonObject("artifact"))
                }
                if (dl.has("classifiers")) {
                    val clObj = dl.getAsJsonObject("classifiers")
                    for ((key, value) in clObj.entrySet()) {
                        classifs[key] = Artifact.parse(value.asJsonObject)
                    }
                }
            }
            val url = if (o.has("url") && !o.get("url").isJsonNull)
                o.get("url").asString else ""
            val natives = if (o.has("natives")) o.getAsJsonObject("natives") else null
            val rules = if (o.has("rules")) o.getAsJsonArray("rules") else null
            val isNative = natives != null || (nameCls != null && nameCls.startsWith("natives-"))
            return Library(
                name, art, Collections.unmodifiableMap(classifs),
                natives, rules, isNative, nameCls, url
            )
        }

        private fun mavenPath(coords: String, classifier: String?): String {
            val parts = coords.split(":")
            if (parts.size < 3) return coords
            val group = parts[0]
            val artifactId = parts[1]
            val version = parts[2]
            // 拒绝路径穿越式坐标（MITM / 恶意 version.json）
            if (containsPathEscape(group) || containsPathEscape(artifactId)
                || containsPathEscape(version)
                || (classifier != null && containsPathEscape(classifier))
            ) {
                throw IllegalArgumentException("非法 maven 坐标（含路径穿越）: $coords")
            }
            val groupPath = group.replace('.', '/')
            val defaultCls = if (parts.size >= 4) parts[3] else null
            if (defaultCls != null && containsPathEscape(defaultCls)) {
                throw IllegalArgumentException("非法 maven classifier: $coords")
            }
            val cls = classifier ?: defaultCls
            val sb = StringBuilder()
                .append(groupPath).append('/')
                .append(artifactId).append('/')
                .append(version).append('/')
                .append(artifactId).append('-').append(version)
            if (!cls.isNullOrEmpty()) {
                sb.append('-').append(cls)
            }
            sb.append(".jar")
            return sb.toString()
        }

        private fun containsPathEscape(s: String?): Boolean {
            if (s.isNullOrEmpty()) return false
            return s.indexOf('\u0000') >= 0
                || s.contains("..")
                || s.contains("/")
                || s.contains("\\")
        }

        fun currentOsName(): String {
            val os = (System.getProperty("os.name") ?: "").lowercase()
            if (os.contains("win")) return "windows"
            if (os.contains("mac")) return "osx"
            return "linux"
        }

        /**
         * 当前 OS 的 native classifier 后缀。
         * Android 上 os.name=linux，os.arch=aarch64 → "natives-linux-arm64"
         */
        fun currentNativeClassifier(): String {
            val arm = isArm64()
            return if (arm) "natives-linux-arm64" else "natives-linux"
        }

        /**
         * 判断此 library 的 nameClassifier 是否匹配当前平台的 native。
         */
        fun matchesCurrentNative(nameClassifier: String?): Boolean {
            if (nameClassifier == null) return false
            return nameClassifier == currentNativeClassifier()
        }
    }
}
