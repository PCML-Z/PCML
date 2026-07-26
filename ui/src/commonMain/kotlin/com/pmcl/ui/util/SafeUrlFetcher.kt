package com.pmcl.ui.util

import com.pmcl.core.util.SsrfChecker
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 安全拉取远程字节（图片等）：协议/主机 SSRF 校验 + 大小上限 + 超时。
 * 替代各页面中裸 [URL.readBytes]。
 */
object SafeUrlFetcher {
    const val DEFAULT_MAX_BYTES: Long = 2L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /**
     * @param allowPrivateLan true 时允许局域网/回环（皮肤站贴图）；false 时拒绝内网
     */
    fun fetchBytes(
        url: String,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        allowPrivateLan: Boolean = false
    ): ByteArray = fetchBytes(url, maxBytes, allowPrivateLan, 0)

    private fun fetchBytes(
        url: String,
        maxBytes: Long,
        allowPrivateLan: Boolean,
        redirectDepth: Int
    ): ByteArray {
        if (url.isBlank()) throw IOException("URL is blank")
        if (redirectDepth > 5) throw IOException("Too many redirects")
        val ssrf = if (allowPrivateLan) {
            SsrfChecker.validateAllowingPrivateLan(url)
        } else {
            SsrfChecker.validate(url)
        }
        if (ssrf != null) throw IOException("SSRF blocked: $ssrf")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "PMCL/1.0")
        }
        try {
            val code = conn.responseCode
            // 手工跟随少量外网重定向并复检 SSRF
            if (code in 301..308) {
                val loc = conn.getHeaderField("Location")
                    ?: throw IOException("Redirect without Location: $code")
                val next = if (loc.startsWith("/")) {
                    val u = URL(url)
                    "${u.protocol}://${u.authority}$loc"
                } else loc
                return fetchBytes(next, maxBytes, allowPrivateLan, redirectDepth + 1)
            }
            if (code !in 200..299) throw IOException("HTTP $code: $url")
            val declared = conn.contentLengthLong
            if (declared > maxBytes) throw IOException("Content-Length $declared exceeds $maxBytes")
            conn.inputStream.use { input ->
                val bos = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    total += n
                    if (total > maxBytes) throw IOException("Response exceeds $maxBytes bytes")
                    bos.write(buf, 0, n)
                }
                return bos.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }
}
