package com.lash.pmcl.core.mods

/**
 * 一个已安装的 mod 元数据（从 jar 内 fabric.mod.json / mods.toml / META-INF 解析）。
 */
data class ModMeta(
    val modId: String,
    val version: String,
    val name: String,
    val description: String,
    val authors: String,
    val loader: String,          // fabric / forge / quilt / neoforge / unknown
    val depends: List<String>,   // 依赖的 modId
    val conflicts: List<String>, // 冲突的 modId
    val jarFile: String,         // jar 文件名（含 .disabled 后缀则被禁用）
    val disabled: Boolean = jarFile.lowercase().endsWith(".disabled"),
    var source: String? = null,  // 来源标签（版本目录名 / "全局" / "系统"），由 VM 设置
    var tags: List<String> = emptyList(),  // 用户自定义标签，由 ModTagStore 加载
    var jarPath: String? = null,  // jar 绝对路径（用于打开所在文件夹 / 读图标）
    var iconEntry: String? = null // jar 内图标条目路径（fabric icon / forge logoFile）
) {
    override fun toString(): String {
        return "$name ($modId v$version, $loader${if (disabled) ", 已禁用" else ""})"
    }
}
