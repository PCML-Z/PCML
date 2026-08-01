package com.lash.pmcl.core.modloader

/**
 * Forge / NeoForge Maven 坐标解析（支持 classifier 与 `@ext`）。
 *
 * 例：
 * - `g:a:v` → `g/a/v/a-v.jar`
 * - `g:a:v:c` → `g/a/v/a-v-c.jar`
 * - `g:a:v@zip` → `g/a/v/a-v.zip`
 * - `g:a:v:c@txt` → `g/a/v/a-v-c.txt`
 */
internal object ForgeMavenCoords {

    /** 去掉外层 `[...]`（若有）。 */
    fun stripBrackets(token: String?): String {
        if (token == null) return ""
        val t = token.trim()
        if (t.startsWith("[") && t.endsWith("]") && t.length >= 2) {
            return t.substring(1, t.length - 1)
        }
        return t
    }

    /** 去掉 Forge data 里常见的单引号包裹。 */
    fun stripQuotes(s: String?): String {
        if (s == null) return ""
        val t = s.trim()
        if (t.length >= 2 && t[0] == '\'' && t[t.length - 1] == '\'') {
            return t.substring(1, t.length - 1)
        }
        if (t.length >= 2 && t[0] == '"' && t[t.length - 1] == '"') {
            return t.substring(1, t.length - 1)
        }
        return t
    }

    /**
     * 坐标 → libraries 相对路径。
     * 旧 Forge universal 特例仍由调用方处理。
     */
    fun toPath(coords: String): String {
        var c = stripBrackets(coords)
        if (c.isEmpty()) return c
        var ext = "jar"
        val at = c.lastIndexOf('@')
        if (at > 0) {
            ext = c.substring(at + 1)
            c = c.substring(0, at)
        }
        val parts = c.split(":")
        if (parts.size < 3) return c
        val groupPath = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = if (parts.size >= 4) parts[3] else null
        val sb = StringBuilder()
            .append(groupPath).append('/')
            .append(artifact).append('/')
            .append(version).append('/')
            .append(artifact).append('-').append(version)
        if (!classifier.isNullOrEmpty()) {
            sb.append('-').append(classifier)
        }
        sb.append('.').append(ext)
        return sb.toString()
    }
}
