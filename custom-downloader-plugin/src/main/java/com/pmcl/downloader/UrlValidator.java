package com.pmcl.downloader;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * URL 校验工具类（Java 实现）。
 * 提供严格的 URL 格式校验和协议白名单检查。
 */
public class UrlValidator {

    /** 允许的协议白名单。 */
    private static final String[] ALLOWED_PROTOCOLS = {"http", "https"};

    /** 允许的最大 URL 长度。 */
    private static final int MAX_URL_LENGTH = 2048;

    /**
     * 校验 URL 是否合法且使用允许的协议。
     *
     * @param url 待校验的 URL 字符串
     * @return true 如果 URL 合法
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

        return null; // 校验通过
    }
}
