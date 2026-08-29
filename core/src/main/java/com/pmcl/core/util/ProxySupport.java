package com.pmcl.core.util;

import com.pmcl.core.preferences.Preferences;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;

/**
 * 将 Preferences 中的代理配置转为 {@link Proxy}，并同步到 JVM 系统属性。
 * <p>
 * 支持 HTTP（CONNECT）与 SOCKS5。HTTP 用户名密码走 OkHttp {@code proxyAuthenticator}
 * 以及 {@link Authenticator}（供 {@code java.net.URL} 使用）；SOCKS5 认证不被 OkHttp 支持，
 * UI 侧应对 SOCKS 隐藏认证项。
 */
public final class ProxySupport {

    public static final String TYPE_HTTP = "HTTP";
    public static final String TYPE_SOCKS5 = "SOCKS5";

    /** 本机 / 回环不走代理，避免访问本地服务被代理劫持。 */
    private static final String NON_PROXY_HOSTS = "localhost|127.*|[::1]|0.0.0.0";

    private static volatile Authenticator installedAuthenticator;

    private ProxySupport() {}

    public static String normalizeType(String type) {
        if (type != null && TYPE_SOCKS5.equalsIgnoreCase(type.trim())) return TYPE_SOCKS5;
        return TYPE_HTTP;
    }

    public static boolean isSocks(String type) {
        return TYPE_SOCKS5.equals(normalizeType(type));
    }

    /**
     * 从偏好构建 OkHttp / {@code java.net} 代理。未启用或 host/port 无效时返回 {@code null}。
     */
    public static Proxy fromPreferences(Preferences pref) {
        if (pref == null || !pref.isUseProxy()) return null;
        String host = pref.getProxyHost();
        int port = pref.getProxyPort();
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) return null;
        if (!isSafeProxyHost(host)) return null;
        Proxy.Type type = isSocks(pref.getProxyType()) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(type, new InetSocketAddress(host, port));
    }

    /** HTTP 代理的 Basic 认证（SOCKS 不可用）。 */
    public static boolean useHttpProxyAuth(Preferences pref) {
        return pref != null
                && pref.isUseProxy()
                && pref.isUseHttpAuth()
                && !isSocks(pref.getProxyType())
                && pref.getProxyUsername() != null
                && !pref.getProxyUsername().isEmpty();
    }

    /**
     * 设置 {@code http.proxyHost} / {@code socksProxyHost} 等系统属性。
     * 优先 Preferences；未启用时回退环境变量 {@code HTTPS_PROXY}/{@code HTTP_PROXY}/{@code ALL_PROXY}。
     */
    public static void applyJvmProperties(Preferences pref) {
        String host = null;
        String port = null;
        boolean socks = false;
        String authUser = null;
        String authPass = null;

        if (pref != null && pref.isUseProxy()) {
            host = pref.getProxyHost();
            int p = pref.getProxyPort();
            if (p > 0) port = String.valueOf(p);
            socks = isSocks(pref.getProxyType());
            if (useHttpProxyAuth(pref)) {
                authUser = pref.getProxyUsername();
                authPass = pref.getProxyPassword();
            }
        } else {
            EnvProxy env = parseEnvProxy();
            if (env != null) {
                host = env.host;
                port = env.port;
                socks = env.socks;
            }
        }

        if (host != null && !host.isEmpty() && port != null) {
            if (!isSafeProxyHost(host)) {
                System.err.println("[ProxySupport] 拒绝设置代理系统属性：host 包含非法字符");
                clearAllProxyProperties();
                clearInstalledAuthenticator();
                return;
            }
            if (socks) {
                clearHttpProxyProperties();
                System.setProperty("socksProxyHost", host);
                System.setProperty("socksProxyPort", port);
                System.setProperty("socksNonProxyHosts", NON_PROXY_HOSTS);
            } else {
                clearSocksProxyProperties();
                System.setProperty("http.proxyHost", host);
                System.setProperty("http.proxyPort", port);
                System.setProperty("https.proxyHost", host);
                System.setProperty("https.proxyPort", port);
                System.setProperty("http.nonProxyHosts", NON_PROXY_HOSTS);
            }
            applyAuthenticator(authUser, authPass);
        } else {
            clearAllProxyProperties();
            clearInstalledAuthenticator();
        }
    }

    private static void applyAuthenticator(String user, String pass) {
        if (user == null || user.isEmpty()) {
            clearInstalledAuthenticator();
            return;
        }
        final String u = user;
        final char[] p = pass == null ? new char[0] : pass.toCharArray();
        Authenticator auth = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) return null;
                return new PasswordAuthentication(u, p);
            }
        };
        Authenticator.setDefault(auth);
        installedAuthenticator = auth;
    }

    private static void clearInstalledAuthenticator() {
        if (installedAuthenticator == null) return;
        if (Authenticator.getDefault() == installedAuthenticator) {
            Authenticator.setDefault(null);
        }
        installedAuthenticator = null;
    }

    private static void clearAllProxyProperties() {
        clearHttpProxyProperties();
        clearSocksProxyProperties();
    }

    private static void clearHttpProxyProperties() {
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.clearProperty("http.nonProxyHosts");
    }

    private static void clearSocksProxyProperties() {
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");
        System.clearProperty("socksNonProxyHosts");
    }

    private static EnvProxy parseEnvProxy() {
        String env = firstEnv("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy",
                "ALL_PROXY", "all_proxy", "SOCKS_PROXY", "socks_proxy");
        if (env == null || env.isEmpty()) return null;
        try {
            URI uri = URI.create(env.contains("://") ? env : "http://" + env);
            String host = uri.getHost();
            int p = uri.getPort();
            if (host == null || host.isEmpty() || p <= 0) return null;
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase();
            boolean socks = scheme.startsWith("socks");
            return new EnvProxy(host, String.valueOf(p), socks);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstEnv(String... names) {
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /**
     * 校验代理 host 字符集，防止 null 字节、控制字符、空白字符注入。
     */
    public static boolean isSafeProxyHost(String host) {
        if (host == null || host.isEmpty()) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.' || c == '-' || c == ':' || c == '[' || c == ']') continue;
            if (c >= 'a' && c <= 'z') continue;
            if (c >= 'A' && c <= 'Z') continue;
            if (c >= '0' && c <= '9') continue;
            return false;
        }
        return true;
    }

    /** 用户粘贴 {@code http://host:port} 时只保留 host。 */
    public static String sanitizeProxyHost(String raw) {
        if (raw == null) return "";
        String h = raw.trim();
        if (h.contains("://")) {
            try {
                URI uri = URI.create(h);
                if (uri.getHost() != null && !uri.getHost().isEmpty()) return uri.getHost();
            } catch (Exception ignored) {}
        }
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < 0) {
            String maybePort = h.substring(colon + 1);
            if (maybePort.chars().allMatch(Character::isDigit)) {
                h = h.substring(0, colon);
            }
        }
        return h;
    }

    private static final class EnvProxy {
        final String host;
        final String port;
        final boolean socks;
        EnvProxy(String host, String port, boolean socks) {
            this.host = host;
            this.port = port;
            this.socks = socks;
        }
    }
}
