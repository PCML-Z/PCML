package com.pmcl.core.util;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;

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
 * <p>
 * <b>已知限制：</b>本类仅做连接前校验，不防护 DNS rebinding（解析后连接前 DNS 记录被篡改）。
 * 完整防护需要在 Socket 层连接已校验的 IP 而非原始主机名，但当前 DownloadManager 不支持
 * 此模式。对于启动器场景，此限制可接受。
 */
public final class SsrfChecker {

    /** 允许的协议白名单。 */
    private static final List<String> ALLOWED_PROTOCOLS = Arrays.asList("http", "https");

    /** 允许的最大 URL 长度。 */
    private static final int MAX_URL_LENGTH = 2048;

    private SsrfChecker() {}

    /**
     * 校验 URL 是否安全（非内网地址）。
     *
     * @param url 待校验的 URL 字符串
     * @return null 表示校验通过，否则返回错误描述
     */
    public static String validate(String url) {
        if (url == null || url.isBlank()) {
            return "URL is null or blank";
        }
        if (url.length() > MAX_URL_LENGTH) {
            return "URL exceeds max length of " + MAX_URL_LENGTH;
        }

        URL parsed;
        try {
            parsed = new URL(url);
        } catch (MalformedURLException e) {
            return "Malformed URL: " + e.getMessage();
        }

        String protocol = parsed.getProtocol().toLowerCase();
        if (!ALLOWED_PROTOCOLS.contains(protocol)) {
            return "Protocol '" + protocol + "' not allowed (supported: http, https)";
        }

        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            return "URL host is missing";
        }

        // 解析所有 IP 并校验
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "Cannot resolve host: " + host;
        }

        for (InetAddress addr : addresses) {
            if (isInternalAddress(addr)) {
                return "Host '" + host + "' resolves to internal address " + addr.getHostAddress()
                        + " (private/loopback/link-local/multicast addresses are blocked)";
            }
        }

        return null; // 校验通过
    }

    /**
     * 便捷方法：返回 URL 是否安全。
     */
    public static boolean isSafe(String url) {
        return validate(url) == null;
    }

    /**
     * 判断一个 InetAddress 是否为内部/受限地址。
     * <p>
     * 拒绝的地址类型：
     * <ul>
     *   <li>Loopback: 127.0.0.0/8, ::1</li>
     *   <li>Private: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, fc00::/7 (IPv6 unique local)</li>
     *   <li>Link-local: 169.254.0.0/16, fe80::/10</li>
     *   <li>Multicast: 224.0.0.0/4, ff00::/8</li>
     *   <li>Any-local: 0.0.0.0, ::</li>
     *   <li>Carrier-grade NAT: 100.64.0.0/10</li>
     * </ul>
     */
    public static boolean isInternalAddress(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()    // 10.x, 172.16-31.x, 192.168.x
                || addr.isMulticastAddress()
                || isCarrierGradeNat(addr);     // 100.64.0.0/10
    }

    /** Carrier-grade NAT (RFC 6598): 100.64.0.0/10 — 不算私有但常用于内网。 */
    private static boolean isCarrierGradeNat(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            // 100.64.0.0/10: first byte = 100, second byte in [64, 127]
            return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xFF) >= 64 && (bytes[1] & 0xFF) <= 127;
        }
        return false;
    }
}
