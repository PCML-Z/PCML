package com.lash.pmcl.core.util

import java.net.InetAddress
import java.net.MalformedURLException
import java.net.URL
import java.net.UnknownHostException
import java.util.Arrays

/**
 * SSRF（服务端请求伪造）防护工具类。
 * <p>
 * 用于校验用户提供的 URL 是否指向可信的外部地址，防止插件安装、自定义下载器等
 * 用户可控的 URL 被用来访问内网服务（如 127.0.0.1、192.168.x.x、10.x.x.x、
 * 169.254.x.x 等）。
 * <p>
 * <b>防护策略：</b>
 * <ul>
 *   <li>协议白名单：仅允许 http / https</li>
 *   <li>主机解析：将主机名解析为 IP 地址，校验 IP 是否为内部地址</li>
 *   <li>私有/回环/链路本地/组播地址全部拒绝</li>
 *   <li>DNS 解析结果的所有 IP 都必须通过校验（防止 DNS 返回多 IP 绕过）</li>
 * </ul>
 */
object SsrfChecker {

    /** 允许的协议白名单。 */
    private val ALLOWED_PROTOCOLS = listOf("http", "https")

    /** 允许的最大 URL 长度。 */
    private const val MAX_URL_LENGTH = 2048

    /**
     * 校验 URL 是否安全（非内网地址）。
     *
     * @param url 待校验的 URL 字符串
     * @return null 表示校验通过，否则返回错误描述
     */
    fun validate(url: String?): String? {
        if (url.isNullOrBlank()) {
            return "URL is null or blank"
        }
        if (url.length > MAX_URL_LENGTH) {
            return "URL exceeds max length of $MAX_URL_LENGTH"
        }

        val parsed: URL
        try {
            parsed = URL(url)
        } catch (e: MalformedURLException) {
            return "Malformed URL: ${e.message}"
        }

        val protocol = parsed.protocol.lowercase()
        if (protocol !in ALLOWED_PROTOCOLS) {
            return "Protocol '$protocol' not allowed (supported: http, https)"
        }

        val host = parsed.host
        if (host.isNullOrBlank()) {
            return "URL host is missing"
        }

        // 拒绝十进制/十六进制/八进制 IP 字面量
        val numericIpError = rejectNumericIpLiteral(host)
        if (numericIpError != null) {
            return numericIpError
        }

        // 解析所有 IP 并校验
        val addresses: Array<InetAddress>
        try {
            addresses = InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            return "Cannot resolve host: $host"
        }

        for (addr in addresses) {
            if (isInternalAddress(addr)) {
                return "Host '$host' resolves to internal address ${addr.hostAddress}"
            }
        }

        return null // 校验通过
    }

    /** 便捷方法：返回 URL 是否安全。 */
    fun isSafe(url: String?): Boolean = validate(url) == null

    /**
     * 校验裸主机名（无 scheme），用于 TCP 探测如服务器 ping。
     *
     * 允许私有局域网 / 回环地址（局域网 Minecraft 服务器合法场景），
     * 但拒绝链路本地（含云 metadata 169.254.169.254）、组播与任意本地 0.0.0.0。
     *
     * @param host 裸主机名或 IP 字面量
     * @return null 表示校验通过，否则返回错误描述
     */
    fun validateHostAllowingPrivateLan(host: String?): String? {
        if (host.isNullOrBlank()) {
            return "Host is null or blank"
        }
        var h = host.trim()
        if (h.length > 253) {
            return "Host exceeds max length"
        }
        if (h.contains("/") || h.contains("\\") || h.contains(" ") || h.contains("\u0000")) {
            return "Host contains illegal characters"
        }
        // 去除 IPv6 方括号
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length - 1)
        }
        val numericIpError = rejectNumericIpLiteral(h)
        if (numericIpError != null) {
            return numericIpError
        }
        val addresses: Array<InetAddress>
        try {
            addresses = InetAddress.getAllByName(h)
        } catch (e: UnknownHostException) {
            return "Cannot resolve host: $host"
        }
        for (addr in addresses) {
            if (addr.isLinkLocalAddress || addr.isMulticastAddress || addr.isAnyLocalAddress) {
                return "Host '$host' resolves to restricted address ${addr.hostAddress}" +
                    " (link-local/multicast/any-local blocked)"
            }
        }
        return null
    }

    /**
     * 判断一个 InetAddress 是否为内部/受限地址。
     * <p>
     * 拒绝的地址类型：
     * <ul>
     *   <li>Loopback: 127.0.0.0/8, ::1</li>
     *   <li>Private: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7</li>
     *   <li>Link-local: 169.254.0.0/16, fe80::/10</li>
     *   <li>Multicast: 224.0.0.0/4, ff00::/8</li>
     *   <li>Any-local: 0.0.0.0, ::</li>
     *   <li>Carrier-grade NAT: 100.64.0.0/10</li>
     * </ul>
     */
    fun isInternalAddress(addr: InetAddress): Boolean {
        val bytes = addr.address
        // 检测 IPv4-mapped IPv6 地址（::ffff:x.x.x.x）
        if (bytes.size == 16
            && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()
            && bytes[4] == 0.toByte() && bytes[5] == 0.toByte() && bytes[6] == 0.toByte() && bytes[7] == 0.toByte()
            && bytes[8] == 0.toByte() && bytes[9] == 0.toByte() && bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        ) {
            // IPv4-mapped IPv6：提取内嵌的 IPv4 地址重新检查
            return try {
                val ipv4 = InetAddress.getByAddress(byteArrayOf(bytes[12], bytes[13], bytes[14], bytes[15]))
                isInternalAddress(ipv4)
            } catch (e: UnknownHostException) {
                true // 不应发生，fail-closed
            }
        }
        return addr.isLoopbackAddress
            || addr.isAnyLocalAddress
            || addr.isLinkLocalAddress
            || addr.isSiteLocalAddress    // 10.x, 172.16-31.x, 192.168.x
            || addr.isMulticastAddress
            || isCarrierGradeNat(addr)    // 100.64.0.0/10
            || isIpv6UniqueLocal(addr)    // fc00::/7
    }

    /** IPv6 Unique Local Address (RFC 4193): fc00::/7 */
    private fun isIpv6UniqueLocal(addr: InetAddress): Boolean {
        val bytes = addr.address
        return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
    }

    /** Carrier-grade NAT (RFC 6598): 100.64.0.0/10 */
    private fun isCarrierGradeNat(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size == 4) {
            return (bytes[0].toInt() and 0xFF) == 100
                && (bytes[1].toInt() and 0xFF) >= 64
                && (bytes[1].toInt() and 0xFF) <= 127
        }
        return false
    }

    /**
     * 拒绝非点分形式的 IPv4 字面量（整型 / 0x… / 0…）。
     */
    fun rejectNumericIpLiteral(host: String?): String? {
        if (host.isNullOrEmpty()) return null
        val h = host
        if (h.startsWith("[") && h.endsWith("]")) return null // IPv6 字面量交给 InetAddress
        // 纯十进制整数主机
        if (h.matches(Regex("\\d{1,10}"))) {
            return "Numeric IP host '$host' is not allowed"
        }
        // 十六进制 / 八进制字面量
        if (h.matches(Regex("0[xX][0-9a-fA-F]+")) || h.matches(Regex("0[0-7]+"))) {
            return "Hex/octal IP host '$host' is not allowed"
        }
        return null
    }
}
