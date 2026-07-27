package com.pmcl.plugin

/**
 * Extension point: rewrite download / HTTP URLs before the host fetches them.
 *
 * Applied after the built-in mirror rewrite (OFFICIAL / BMCLAPI / CUSTOM).
 * Return the original [url] unchanged when no rewrite is needed.
 * Returning blank / null is treated as "keep previous URL".
 *
 * Requires [com.pmcl.plugin.PluginPermission.NETWORK] when registering.
 */
fun interface UrlRewriteHook {
    /**
     * @param url Absolute HTTP(S) URL after host mirror rewrite
     * @return Rewritten URL, or [url] if unchanged
     */
    fun rewrite(url: String): String
}
