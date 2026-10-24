package com.pmcl.core.compliance;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 游戏下载服务合规检测。
 * <p>
 * 依据当地监管政策要求：当本机同时满足
 * <ol>
 *   <li>系统语言为简体中文（zh / zh-CN）；</li>
 *   <li>时区为中国大陆时区（GMT+8，港澳台除外）；</li>
 *   <li>出口 IP 归属地为中国大陆</li>
 * </ol>
 * 时，PMCL 不再向用户提供 Minecraft 游戏下载服务（版本安装、重装、模组加载器安装、社区客户端下载）。
 * 已安装版本的启动（含已安装的社区客户端）、本地内容管理等其余功能不受影响。
 * <p>
 * 判定策略（保守合规）：
 * <ul>
 *   <li>语言或时区任一不匹配 → 不受限（海外华人环境不受影响）；</li>
 *   <li>语言 + 时区都匹配时，若 IP 查询明确返回非中国大陆 → 不受限；</li>
 *   <li>IP 查询失败或尚未完成 → 视为受限（无法通过断网绕过）。</li>
 * </ul>
 * IP 归属地按 {@link #FRESH_TTL_MS} 实时刷新，始终反映当前网络；探测走直连，
 * 量的是本机真实出口而非代理/VPN 出口，因此开启代理也无法绕过限制（详见 {@link #fetchBodyDirect}）。
 */
public final class DownloadCompliance {

    private DownloadCompliance() {
    }

    /** 三态网络归属：TRUE=大陆，FALSE=非大陆，null=未知（未完成/失败）。 */
    private static final AtomicReference<Boolean> MAINLAND_NETWORK = new AtomicReference<>();
    /** 上次成功探测时间戳（毫秒），用于判断缓存是否陈旧、保证实时性。 */
    private static volatile long MAINLAND_NETWORK_AT = 0L;
    /** 是否有探测正在进行（并发去重）。 */
    private static volatile boolean probeInFlight = false;
    /** 实时缓存窗口：窗口内复用结果去重，窗口外重新探测，保证检测实时、准确。 */
    private static final long FRESH_TTL_MS = 5000L;
    /** 归属地探测的连接 / 读取超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 5000;
    /** 探测完成回调（结果落地后一次性通知）。 */
    private static final java.util.List<java.util.function.Consumer<Boolean>> PROBE_CALLBACKS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 中国大陆时区 ID（不含港澳台）。 */
    private static final java.util.Set<String> MAINLAND_TZ = java.util.Set.of(
            "Asia/Shanghai", "Asia/Chongqing", "Asia/Harbin", "Asia/Urumqi",
            "Asia/Kashgar", "PRC", "CTT", "GMT+08:00"
    );

    /** 系统语言是否为简体中文。 */
    public static boolean isSimplifiedChineseLocale() {
        Locale loc = Locale.getDefault();
        if (!"zh".equals(loc.getLanguage())) return false;
        String country = loc.getCountry();
        // 只报 "zh" 或 "zh-CN" 视为简体中文；zh-TW/zh-HK/zh-MO 不是
        return country == null || country.isEmpty() || "CN".equalsIgnoreCase(country);
    }

    /** 时区是否为中国大陆时区（GMT+8，港澳台除外）。 */
    public static boolean isMainlandTimezone() {
        TimeZone tz = TimeZone.getDefault();
        if (MAINLAND_TZ.contains(tz.getID())) return true;
        // 兜底：raw offset 为 +8 且 ID 不含港澳台标识
        return tz.getRawOffset() == 8 * 3600 * 1000
                && !tz.getID().startsWith("Asia/Hong_Kong")
                && !tz.getID().startsWith("Asia/Macau")
                && !tz.getID().startsWith("Asia/Taipei");
    }

    /**
     * 实时刷新出口 IP 归属地（带完成回调）。
     * 缓存仍在 {@link #FRESH_TTL_MS} 内直接复用（去重）；否则后台重新探测（并发去重），
     * 完成后回调「最佳当前估计」（历史值兜底，网络瞬断不闪烁）。
     * 保证检测实时、准确——始终反映当前网络，而非启动时的陈旧快照。
     */
    public static synchronized void refreshNetworkAsync(java.util.function.Consumer<Boolean> onDone) {
        long now = System.currentTimeMillis();
        Boolean cur = MAINLAND_NETWORK.get();
        if (cur != null && (now - MAINLAND_NETWORK_AT) < FRESH_TTL_MS) {
            if (onDone != null) onDone.accept(cur); // 实时缓存仍新鲜
            return;
        }
        if (onDone != null) PROBE_CALLBACKS.add(onDone);
        if (probeInFlight) return; // 已有探测在进行，等待其结果
        probeInFlight = true;
        Thread t = new Thread(() -> {
            Boolean r = probe();
            if (r != null) {
                MAINLAND_NETWORK.set(r);
                MAINLAND_NETWORK_AT = System.currentTimeMillis();
            }
            Boolean best = MAINLAND_NETWORK.get(); // 成功→新值；失败→历史值兜底
            for (java.util.function.Consumer<Boolean> cb : PROBE_CALLBACKS) cb.accept(best);
            PROBE_CALLBACKS.clear();
            synchronized (DownloadCompliance.class) { probeInFlight = false; }
        }, "pmcl-compliance-probe");
        t.setDaemon(true);
        t.start();
    }

    /** 无回调版本：触发一次实时刷新（去重/并发去重）。 */
    public static void refreshNetworkAsync() {
        refreshNetworkAsync(null);
    }

    /** 兼容旧名：等价 {@link #refreshNetworkAsync(java.util.function.Consumer)}。 */
    public static void prefetchNetworkAsync(java.util.function.Consumer<Boolean> onDone) {
        refreshNetworkAsync(onDone);
    }

    /** 兼容旧名：等价 {@link #refreshNetworkAsync()}。 */
    public static void prefetchNetworkAsync() {
        refreshNetworkAsync(null);
    }

    /** 阻塞式查询出口 IP 归属地（带缓存；缓存陈旧或缺失时重新探测，最长阻塞约 15 秒）。 */
    public static Boolean probeMainlandNetwork() {
        Boolean cached = MAINLAND_NETWORK.get();
        if (cached != null && (System.currentTimeMillis() - MAINLAND_NETWORK_AT) < FRESH_TTL_MS) return cached;
        Boolean r = probe();
        if (r != null) {
            MAINLAND_NETWORK.set(r);
            MAINLAND_NETWORK_AT = System.currentTimeMillis();
        }
        return r != null ? r : MAINLAND_NETWORK.get();
    }

    /**
     * 当前是否应对游戏下载服务做出限制（同步，不触发探测）。
     * 语言 + 时区都匹配，且 IP 未被明确判定为非大陆时返回 true（未知按受限，保守）。
     */
    public static boolean isGameDownloadRestrictedSync() {
        if (!isSimplifiedChineseLocale()) return false;
        if (!isMainlandTimezone()) return false;
        // 未知（null）或非大陆（false）：仅当明确为 false 才放行
        return !Boolean.FALSE.equals(MAINLAND_NETWORK.get());
    }

    /** 同步判定（读实时缓存）：结果由 {@link #refreshNetworkAsync} 持续刷新，反映当前网络。 */
    public static boolean isGameDownloadRestricted() {
        return isGameDownloadRestrictedSync();
    }

    /** 受限提示文案的 i18n 键（恒定），便于 UI 统一展示。 */
    public static String restrictionNoticeKey() {
        return "download.restricted_notice";
    }

    /**
     * 服务器连接服务合规检测：仅依据出口 IP 归属地（与下载限制不同，不要求语言或时区匹配）。
     * <p>
     * 当探测明确判定出口 IP 为中国大陆时，PMCL 不再提供 Minecraft 服务器连接服务
     * （收藏服务器列表、测速、直连），仅保留 OPanel 服务器管理。
     * <p>
     * 判定策略（避免误伤）：
     * <ul>
     *   <li>探测明确返回中国大陆 → 受限；</li>
     *   <li>探测明确返回非大陆（含海外） → 不受限；</li>
     *   <li>探测尚未完成或失败（未知） → 不受限（无法确认即不限制，避免误伤断网用户）。</li>
     * </ul>
     */
    public static boolean isServerConnectionRestricted() {
        // 读实时缓存（由 refreshNetworkAsync 持续刷新，反映当前网络）；
        // 仅在明确判定为大陆时限制；未知/失败一律视为不受限
        return Boolean.TRUE.equals(MAINLAND_NETWORK.get());
    }

    /** 服务器连接受限提示文案的 i18n 键（恒定）。 */
    public static String serverConnectionNoticeKey() {
        return "servers.connection_restricted_notice";
    }

    // ————— 内部实现 —————

    /**
     * 探测出口 IP 归属地。
     * <p>
     * 保守合规：任一来源明确判定为大陆即返回 true（单个 GeoIP 源与其他源分歧时不解除限制），
     * 全部来源都明确为非大陆才返回 false，全部失败返回 null（未知）。
     */
    private static Boolean probe() {
        boolean definitiveNonMainland = false;
        for (String[] ep : GEO_ENDPOINTS) {
            Boolean r = parseCountry(fetchCountry(ep[0], ep[1], ep[2], "arr".equals(ep[3])));
            if (r == null) continue;
            if (r) return true;
            definitiveNonMainland = true;
        }
        return definitiveNonMainland ? Boolean.FALSE : null;
    }

    /**
     * 归属地探测端点：{host, path, JSON 字段名, str=字符串字段 / arr=数组首元素}。
     * 均为明文 80 端口（HTTPS 无法真正直连，见 {@link #fetchBodyDirect}）。
     * （注意：ip-api 免费版仅 HTTP 可用，HTTPS 会返回空对象；ipip.net 国家在 location 数组里。）
     */
    private static final String[][] GEO_ENDPOINTS = {
            {"ip-api.com", "/json/?fields=countryCode", "countryCode", "str"},
            {"ipinfo.io", "/json", "country", "str"},
            {"myip.ipip.net", "/json", "location", "arr"},
    };

    /** 解析国家取值：2 字母码或国家名 → 是否中国大陆；无法识别返回 null。 */
    private static Boolean parseCountry(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        // 国家名（中英文）优先：避免中文两字名（如"中国"）被 2 字母码分支误判
        if (s.equals("中国") || s.equalsIgnoreCase("china") || s.equalsIgnoreCase("prc")) return true;
        // 2 字母 ASCII 国家码（仅 A-Z/a-z，排除中文两字名）
        if (s.length() == 2 && isAsciiLetter(s.charAt(0)) && isAsciiLetter(s.charAt(1))) {
            return "CN".equalsIgnoreCase(s);
        }
        // 其他国家名 → 非大陆
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /** 直连请求并提取国家字段取值；失败返回 null。 */
    private static String fetchCountry(String host, String path, String field, boolean arrayFirst) {
        String body = fetchBodyDirect(host, path);
        if (body == null) return null;
        return arrayFirst ? extractJsonArrayFirstString(body, field) : extractJsonStringField(body, field);
    }

    /**
     * 直连（绕开一切代理）发起明文 HTTP GET，返回响应体；失败或非 200 返回 null。
     * <p>
     * 合规判定必须量到本机<b>真实</b>出口 IP，而 JVM 会继承操作系统代理：macOS/Windows 上 JDK
     * 启动时把系统代理写进 {@code http(s).proxyHost} 与 {@code socksProxyHost}，于是
     * {@code HttpURLConnection} 走的是代理出口——用户人在大陆、探测却报代理节点的归属地，限制形同虚设。
     * 传 {@code Proxy.NO_PROXY} 也不够：它对明文 HTTP 有效，但 HTTPS 仍被 {@code socksProxyHost} 劫持。
     * {@code SocketChannel} 不经过 {@code SocksSocketImpl}、也不查 {@code ProxySelector}，是真正的直连。
     */
    private static String fetchBodyDirect(String host, String path) {
        try (java.nio.channels.SocketChannel ch = java.nio.channels.SocketChannel.open()) {
            java.net.Socket sock = ch.socket();
            sock.connect(new java.net.InetSocketAddress(host, 80), CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(READ_TIMEOUT_MS);
            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + host + "\r\n"
                    + "User-Agent: PMCL-Compliance/1.0\r\n"
                    + "Accept: */*\r\n"
                    + "Connection: close\r\n\r\n";
            ch.write(java.nio.ByteBuffer.wrap(request.getBytes(StandardCharsets.US_ASCII)));
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8192);
            while (ch.read(buf) > 0) {
                buf.flip();
                byte[] chunk = new byte[buf.remaining()];
                buf.get(chunk);
                bos.write(chunk);
                buf.clear();
                if (bos.size() > 64 * 1024) break;
            }
            String raw = bos.toString(StandardCharsets.UTF_8);
            if (!raw.startsWith("HTTP/1.1 200") && !raw.startsWith("HTTP/1.0 200")) return null;
            int split = raw.indexOf("\r\n\r\n");
            return split < 0 ? null : raw.substring(split + 4);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 极简 JSON 数组字段首元素提取：找 "field":["x",...] 的第一个字符串。 */
    private static String extractJsonArrayFirstString(String json, String field) {
        int key = json.indexOf("\"" + field + "\"");
        if (key < 0) return null;
        int br = json.indexOf('[', key);
        if (br < 0) return null;
        int q1 = json.indexOf('"', br + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }

    /** 极简 JSON 字符串字段提取：找 "field":"XX"，避免为此引入 JSON 依赖。 */
    private static String extractJsonStringField(String json, String field) {
        int key = json.indexOf("\"" + field + "\"");
        if (key < 0) return null;
        int colon = json.indexOf(':', key + field.length() + 2);
        if (colon < 0) return null;
        int q1 = json.indexOf('"', colon + 1);
        if (q1 < 0) return null;
        int q2 = json.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return json.substring(q1 + 1, q2);
    }
}
