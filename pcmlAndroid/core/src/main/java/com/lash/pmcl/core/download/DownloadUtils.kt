package com.lash.pmcl.core.download

object DownloadUtils {
    fun contentRangeMatches(contentRange: String?, expectedStart: Long): Boolean {
        if (contentRange.isNullOrBlank()) return false
        val s = contentRange.trim()
        if (!s.regionMatches(0, "bytes ", 0, 6, ignoreCase = true)) return false
        val rest = s.substring(6).trim()
        val dash = rest.indexOf('-')
        if (dash <= 0) return false
        return try {
            val start = rest.substring(0, dash).trim().toLong()
            start == expectedStart
        } catch (e: NumberFormatException) {
            false
        }
    }
}
