package com.pmcl.core.download;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 下载镜像源管理。
 * <p>
 * 支持官方 / BMCLAPI / 自定义三种模式。BMCLAPI 通过域名重写加速 Minecraft 资源。
 * <p>
 * 重写规则参考 <a href="https://bmclapi2.bangbang93.com/">BMCLAPI 文档</a>。
 */
public final class MirrorManager {

    public enum MirrorType {
        OFFICIAL,
        BMCLAPI,
        CUSTOM
    }

    private MirrorType type = MirrorType.OFFICIAL;
    private String customBase = "";

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

    public MirrorType getType() { return type; }
    public void setType(MirrorType type) { this.type = type; }

    public String getCustomBase() { return customBase; }
    public void setCustomBase(String customBase) { this.customBase = customBase == null ? "" : customBase; }

    /**
     * 根据当前镜像类型重写 URL。
     * 自定义模式：将原 URL 的 scheme+host 替换为 customBase（保持 path）。
     */
    public String rewrite(String url) {
        if (url == null || url.isEmpty()) return url;
        if (type == MirrorType.OFFICIAL) return url;

        if (type == MirrorType.BMCLAPI) {
            for (Map.Entry<String, String> e : BMCLAPI_MAP.entrySet()) {
                String origin = "https://" + e.getKey();
                if (url.startsWith(origin)) {
                    return "https://" + e.getValue() + url.substring(origin.length());
                }
            }
            return url;
        }

        // CUSTOM：替换 scheme+host，保留 path/query
        if (customBase.isEmpty()) return url;
        int schemeIdx = url.indexOf("://");
        if (schemeIdx < 0) return url;
        int pathIdx = url.indexOf('/', schemeIdx + 3);
        String path = pathIdx >= 0 ? url.substring(pathIdx) : "/";
        String base = customBase.endsWith("/") ? customBase.substring(0, customBase.length() - 1) : customBase;
        return base + path;
    }
}
