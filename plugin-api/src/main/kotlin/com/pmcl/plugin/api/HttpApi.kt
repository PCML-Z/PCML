package com.pmcl.plugin.api

/**
 * Low-level HTTP with SSRF protection (no arbitrary localhost / private IPs).
 * Requires [com.pmcl.plugin.PluginPermission.NETWORK].
 *
 * Prefer [DownloadsApi] for simple file/text fetches; use this for POST/PUT/custom headers.
 */
interface HttpApi {
    /**
     * Execute an HTTP request.
     *
     * @param method GET / POST / PUT / DELETE / HEAD / PATCH
     * @param url Absolute HTTP(S) URL
     * @param headers Optional request headers (Host / Content-Length ignored)
     * @param body Optional request body (UTF-8); ignored for GET/HEAD
     * @param timeoutMs Overall call timeout hint (host may clamp)
     */
    fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMs: Long = 30_000L,
    ): HttpResponseSummary
}
