package com.pmcl.ui.page

import com.pmcl.core.i18n.I18n

/**
 * 内容板块通用工具函数。
 *
 * 提取自 ShaderPacksPage.kt，供 ShaderPacksPage / ResourcePacksPage / DatapacksPage /
 * WorldsPage 等多个页面共用，避免重复实现。
 */
object ContentUtils {

    /** 人类可读文件大小：B / KB / MB / GB。 */
    fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    /**
     * 根据 packFormat 推导 MC 版本兼容范围。
     *
     * 来源：https://minecraft.wiki/w/Pack_format
     * 4 → 1.13-1.14.4
     * 5 → 1.15-1.15.2
     * 6 → 1.16-1.16.1
     * 7 → 1.16.2-1.16.5
     * 8 → 1.17-1.17.1
     * 9 → 1.18-1.18.2
     * 12 → 1.19-1.19.3
     * 13 → 1.19.4
     * 15 → 1.20-1.20.1
     * 18 → 1.20.2-1.20.4
     * 22 → 1.20.5-1.20.6
     * 34 → 1.21-1.21.1
     * 42 → 1.21.2-1.21.3
     * 46 → 1.21.4
     * 55 → 1.21.5
     * 66 → 1.21.6-1.21.7
     */
    fun packFormatHint(packFormat: Int): String {
        return when (packFormat) {
            1, 2 -> "1.6-1.12"
            3 -> "1.11-1.12"
            4 -> "1.13-1.14"
            5 -> "1.15"
            6 -> "1.16-1.16.1"
            7 -> "1.16.2-1.16.5"
            8 -> "1.17"
            9 -> "1.18-1.19"
            12 -> "1.19-1.19.3"
            13 -> "1.19.4"
            15 -> "1.20-1.20.1"
            18 -> "1.20.2-1.20.4"
            22 -> "1.20.5-1.20.6"
            34 -> "1.21-1.21.1"
            42 -> "1.21.2-1.21.3"
            46 -> "1.21.4"
            55 -> "1.21.5"
            66 -> "1.21.6+"
            else -> if (packFormat > 66) "1.21.6+" else I18n.t("content.unknown")
        }
    }

    /**
     * 根据启用/禁用/激活状态返回结构化状态标签。
     * 返回 null 表示无需展示状态标签。
     */
    fun statusLabel(disabled: Boolean, active: Boolean = false): String? {
        return when {
            active -> "current"
            disabled -> "disabled"
            else -> null
        }
    }
}
