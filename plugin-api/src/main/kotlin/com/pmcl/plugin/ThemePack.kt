package com.pmcl.plugin

/**
 * 插件主题包：由插件提供的完整主题配色方案。
 *
 * 一个主题包包含亮色和暗色两套完整 Material3 配色（各 16 个颜色角色），
 * 主程序在用户切换到该主题包时将其转换为 Compose ColorScheme 应用到全局。
 *
 * 设计要点：
 * - 颜色用 ARGB int 表示（alpha=0xFF），不依赖 Compose Material3 类，
 *   保证 plugin-api 模块零运行时依赖。
 * - 主题包为不可变数据类，插件构造后不应修改。
 * - id 必须全局唯一，建议以插件 ID 为前缀（如 "myplugin-ocean"）。
 *
 * 配色角色与 Material3 ColorScheme 对应关系：
 * - primary / onPrimary / primaryContainer / onPrimaryContainer
 * - secondary / onSecondary / tertiary
 * - background / onBackground / surface / onSurface
 * - surfaceVariant / onSurfaceVariant / outline
 * - error / onError
 */
data class ThemePack(
    /** 主题包唯一 ID，3-64 字符，小写字母数字+连字符，建议带插件前缀 */
    val id: String,
    /** 显示名称（任何语言） */
    val name: String,
    /** 亮色模式配色（16 个 ARGB 角色） */
    val lightPalette: ThemePalette,
    /** 暗色模式配色（16 个 ARGB 角色） */
    val darkPalette: ThemePalette,
    /** 预览色（ARGB），用于在主题选择器中展示色块，通常取 primary */
    val previewColor: Int = lightPalette.primary,
    /** 作者（可选，用于 UI 展示） */
    val author: String = "",
    /** 描述（可选，用于 UI 展示） */
    val description: String = ""
) {
    init {
        require(id.matches(ID_REGEX)) {
            "ThemePack.id must match ${ID_REGEX.pattern}, got: $id"
        }
        require(name.isNotBlank()) { "ThemePack.name must not be blank" }
    }

    companion object {
        /** ID 正则：小写字母开头，3-64 字符，小写字母数字+连字符 */
        val ID_REGEX = Regex("^[a-z][a-z0-9-]{1,62}[a-z0-9]$")
    }
}

/**
 * 主题配色板：16 个 Material3 颜色角色（ARGB int）。
 *
 * 所有字段必须是 ARGB int（通常 alpha=0xFF）。
 * 与 [com.pmcl.core.theme.WallpaperColorProvider.FullPalette] 字段一一对应。
 */
data class ThemePalette(
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val tertiary: Int,
    val background: Int,
    val onBackground: Int,
    val surface: Int,
    val onSurface: Int,
    val surfaceVariant: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val error: Int,
    val onError: Int
) {
    companion object {
        /**
         * 从 16 元素 int 数组构造（按字段声明顺序）。
         * 便于插件从 JSON/配置文件加载配色。
         */
        fun fromArray(colors: IntArray): ThemePalette {
            require(colors.size == 16) {
                "ThemePalette requires exactly 16 colors, got ${colors.size}"
            }
            return ThemePalette(
                primary = colors[0], onPrimary = colors[1],
                primaryContainer = colors[2], onPrimaryContainer = colors[3],
                secondary = colors[4], onSecondary = colors[5],
                tertiary = colors[6],
                background = colors[7], onBackground = colors[8],
                surface = colors[9], onSurface = colors[10],
                surfaceVariant = colors[11], onSurfaceVariant = colors[12],
                outline = colors[13],
                error = colors[14], onError = colors[15]
            )
        }
    }
}
