package com.lash.pmcl.core.update

/**
 * Semver-ish version comparison for update channels (anti-downgrade).
 */
internal object UpdateVersions {

    /**
     * @return true iff [remote] is strictly newer than [current]
     *         (dot-separated numeric segments; non-numeric suffixes ignored per segment)
     */
    fun isNewer(remote: String?, current: String?): Boolean {
        if (remote == null || current == null) return false
        if (remote == current) return false
        val r = remote.split("\\.".toRegex())
        val c = current.split("\\.".toRegex())
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val ri = if (i < r.size) parseIntSafe(r[i]) else 0
            val ci = if (i < c.size) parseIntSafe(c[i]) else 0
            if (ri > ci) return true
            if (ri < ci) return false
        }
        return false
    }

    private fun parseIntSafe(s: String): Int {
        return try {
            val num = s.replace(Regex("[^0-9].*$"), "")
            if (num.isEmpty()) 0 else num.toInt()
        } catch (e: NumberFormatException) {
            0
        }
    }
}
