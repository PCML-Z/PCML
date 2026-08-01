package com.lash.pmcl.core.mods

import java.nio.file.Path

/**
 * 拖入启动器窗口的 mod jar 的解析结果。
 *
 * 包含从 jar 内 fabric.mod.json / mods.toml 解析出的基本信息，
 * 以及通过 SHA1 反查 Modrinth API 拿到的 game_versions / loaders 列表。
 *
 * 当 Modrinth 反查失败或未匹配时，[modrinthFound] 为 false，
 * gameVersions / loaders 为空列表，UI 应允许用户手动选择目标版本。
 */
data class ModDropInfo(
    val modId: String,
    val name: String,
    val version: String,
    val loader: String,          // fabric / forge / quilt / neoforge / unknown
    val authors: String,
    val description: String,
    val jarPath: Path,
    val sha1: String?,
    val gameVersions: List<String>,  // Modrinth 返回的兼容游戏版本列表
    val loaders: List<String>,       // Modrinth 返回的兼容加载器列表
    val modrinthFound: Boolean,      // Modrinth 是否反查到匹配
    val parseError: String?          // 解析失败原因，null 表示解析成功
) {
    /** 解析是否成功（仅判断 ModScanner 是否拿到了 modId） */
    fun isParsed(): Boolean = parseError == null && modId.isNotEmpty()

    override fun toString(): String {
        return "$name ($modId v$version, $loader)"
    }
}
