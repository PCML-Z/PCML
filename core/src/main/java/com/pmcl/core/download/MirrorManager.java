package com.pmcl.core.download;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 下载镜像源管理。
 * <p>
 * 支持官方 / BMCLAPI / 自定义三种模式。BMCLAPI 通过域名重写加速 Minecraft 资源。
 * <p>
 * 重写规则参考 <a href="https://bmclapi2.bangbang93.com/">BMCLAPI 文档</a>。
 * <p>
 * H1 修复：内置 per-host 熔断器。镜像域名连续失败 N 次后临时熔断（默认 5 分钟 TTL），
 * rewrite 跳过被熔断的镜像域名回退到官方源，避免 BMCLAPI 宕机时全线下载失败。
 */
public final class MirrorManager {

    public enum MirrorType {
        OFFICIAL,
        BMCLAPI,
        CUSTOM
    }

    private volatile MirrorType type = MirrorType.OFFICIAL;
    private volatile String customBase = "";

    /** 官方域名 → BMCLAPI 域名映射 */
    private static final Map<String, String> BMCLAPI_MAP = new LinkedHashMap<>();

    static {
        // 版本清单 / 元数据
        BMCLAPI_MAP.put("piston-meta.mojang.com", "bmclapi2.bangbang93.com");
        BMCLAPI_MAP.put("launchermeta.mojang.com", "bmclapi2.bangbang93.com");
        BMCLAPI_MAP.put("launcher.mojang.com", "bmclapi2.bangbang93.com");
        // 库
        BMCLAPI_MAP.put("libraries.minecraft.net", "bmclapi2.bangbang93.com/maven");
        // 资产
        BMCLAPI_MAP.put("resources.download.minecraft.net", "bmclapi2.bangbang93.com/assets");
        // Forge / Fabric / Quilt
        BMCLAPI_MAP.put("files.minecraftforge.net", "bmclapi2.bangbang93.com");
        BMCLAPI_MAP.put("maven.fabricmc.net", "bmclapi2.bangbang93.com/maven");
        BMCLAPI_MAP.put("meta.fabricmc.net", "bmclapi2.bangbang93.com/fabric-meta");
        BMCLAPI_MAP.put("meta.quiltmc.org", "bmclapi2.bangbang93.com/quilt-meta");
        BMCLAPI_MAP.put("maven.quiltmc.org", "bmclapi2.bangbang93.com/maven");
    }

    /** 熔断阈值：连续失败 N 次后熔断 */
    private static final int CIRCUIT_FAILURE_THRESHOLD = 3;
    /** 熔断 TTL：5 分钟后允许半开探测 */
    private static final long CIRCUIT_TTL_MS = 5 * 60_000L;

    /** per-host 熔断状态：host → [失败计数, 熔断到期时间戳] */
    private final ConcurrentHashMap<String, long[]> circuit = new ConcurrentHashMap<>();

    public MirrorType getType() { return type; }
    public void setType(MirrorType type) { this.type = type; }

    public String getCustomBase() { return customBase; }
    public void setCustomBase(String customBase) { this.customBase = customBase == null ? "" : customBase; }

    /**
     * 标记某 host 下载失败（连续失败达阈值后熔断）。
     * 在 DownloadManager 捕获到 HTTP 4xx/5xx 或网络错误时调用。
     */
    public void markFailure(String host) {
        if (host == null || host.isEmpty()) return;
        long[] state = circuit.compute(host, (k, v) -> {
            long now = System.currentTimeMillis();
            if (v == null) return new long[]{1, 0};
            // 已熔断状态下不累加
            if (v[1] > 0 && now < v[1]) return v;
            // 之前熔断已过期，重置计数
            if (v[1] > 0 && now >= v[1]) return new long[]{1, 0};
            v[0]++;
            if (v[0] >= CIRCUIT_FAILURE_THRESHOLD) {
                v[1] = now + CIRCUIT_TTL_MS;
            }
            return v;
        });
    }

    /** 标记某 host 下载成功（重置失败计数，关闭熔断） */
    public void markSuccess(String host) {
        if (host == null || host.isEmpty()) return;
        circuit.remove(host);
    }

    /** 判断某 host 是否当前处于熔断状态 */
    private boolean isCircuitOpen(String host) {
        long[] state = circuit.get(host);
        if (state == null) return false;
        long now = System.currentTimeMillis();
        if (state[1] == 0) return false;
        if (now >= state[1]) {
            // 熔断过期，允许半开探测（不清除状态，由 markSuccess/markFailure 决定下一步）
            return false;
        }
        return true;
    }

    /** 提取 URL 的 host 部分 */
    private static String extractHost(String url) {
        int schemeIdx = url.indexOf("://");
        if (schemeIdx < 0) return "";
        int hostStart = schemeIdx + 3;
        int pathIdx = url.indexOf('/', hostStart);
        if (pathIdx < 0) pathIdx = url.length();
        // 去掉端口
        int colon = url.indexOf(':', hostStart);
        int end = colon > 0 && colon < pathIdx ? colon : pathIdx;
        return url.substring(hostStart, end);
    }

    /**
     * 根据当前镜像类型重写 URL。
     * 自定义模式：将原 URL 的 scheme+host 替换为 customBase（保持 path）。
     * H1: 镜像 host 熔断时回退到官方源 URL。
     */
    public String rewrite(String url) {
        if (url == null || url.isEmpty()) return url;
        if (type == MirrorType.OFFICIAL) return url;

        if (type == MirrorType.BMCLAPI) {
            for (Map.Entry<String, String> e : BMCLAPI_MAP.entrySet()) {
                String origin = "https://" + e.getKey();
                if (url.startsWith(origin)) {
                    String mirrorHost = e.getValue();
                    // H1: 检查 BMCLAPI 镜像 host 是否熔断
                    if (isCircuitOpen(mirrorHost)) {
                        return url; // 回退到官方源 URL
                    }
                    return "https://" + mirrorHost + url.substring(origin.length());
                }
            }
            return url;
        }

        // CUSTOM：替换 scheme+host，保留 path/query
        if (customBase.isEmpty()) return url;
        int schemeIdx = url.indexOf("://");
        if (schemeIdx < 0) return url;
        String customHost = extractHost(customBase);
        if (!customHost.isEmpty() && isCircuitOpen(customHost)) {
            return url; // 回退到官方源
        }
        int pathIdx = url.indexOf('/', schemeIdx + 3);
        String path = pathIdx >= 0 ? url.substring(pathIdx) : "/";
        String base = customBase.endsWith("/") ? customBase.substring(0, customBase.length() - 1) : customBase;
        return base + path;
    }

    /** 仅供测试 / 调试：手动清除所有熔断状态 */
    public void resetCircuit() {
        circuit.clear();
    }
}
