package com.pmcl.downloader;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;

/**
 * URL 校验工具类（Java 实现）。
 * <p>
 * 提供严格的 URL 格式校验、协议白名单检查，以及 SSRF（服务端请求伪造）防护。
 * <p>
 * <b>S4+M69 安全修复：</b>在原有协议白名单基础上，增加了对解析后 IP 的校验，
 * 拒绝指向内网/回环/链路本地/组播地址的 URL，防止用户被诱导从内部服务下载恶意内容。
 */
public class UrlValidator {

    /** 允许的协议白名单。 */
    private static final String[] ALLOWED_PROTOCOLS = {"http", "https"};

    /** 允许的最大 URL 长度。 */
    private static final int MAX_URL_LENGTH = 2048;

    /**
     * 校验 URL 是否合法、使用允许的协议、且不指向内网地址。
     *
     * @param url 待校验的 URL 字符串
     * @return true 如果 URL 合法且安全
     */
    public static boolean isValidUrl(String url) {
        return getValidationError(url) == null;
    }

    /**
     * 获取 URL 校验错误信息。
     *
     * @param url 待校验的 URL 字符串
     * @return 错误描述，null 表示校验通过
     */
    public static String getValidationError(String url) {
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
        boolean protocolAllowed = false;
        for (String allowed : ALLOWED_PROTOCOLS) {
            if (allowed.equals(protocol)) {
                protocolAllowed = true;
                break;
            }
        }
        if (!protocolAllowed) {
            return "Protocol '" + protocol + "' not allowed (supported: http, https)";
        }

        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            return "URL host is missing";
        }

        // S4+M69 SSRF 防护：解析主机为 IP 并校验是否为内部地址
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(parsed.getHost());
        } catch (UnknownHostException e) {
            return "Cannot resolve host: " + parsed.getHost();
        }
        for (InetAddress addr : addresses) {
            if (isInternalAddress(addr)) {
                return "Host '" + parsed.getHost() + "' resolves to internal address "
                        + addr.getHostAddress()
                        + " (private/loopback/link-local/multicast addresses are blocked)";
            }
        }

        return null; // 校验通过
    }

    /**
     * 判断一个 InetAddress 是否为内部/受限地址。
     * 拒绝：Loopback / Private / Link-local / Multicast / Any-local / CGN (100.64.0.0/10)。
     */
    private static boolean isInternalAddress(InetAddress addr) {
        if (addr.isLoopbackAddress()
                || addr.isAnyLocalAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()    // 10.x, 172.16-31.x, 192.168.x
                || addr.isMulticastAddress()) {
            return true;
        }
        // Carrier-grade NAT (RFC 6598): 100.64.0.0/10
        byte[] bytes = addr.getAddress();
        if (bytes.length == 4) {
            return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xFF) >= 64 && (bytes[1] & 0xFF) <= 127;
        }
        return false;
    }
}
