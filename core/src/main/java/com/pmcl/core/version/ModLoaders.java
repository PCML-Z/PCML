package com.pmcl.core.version;

import java.util.Locale;

/** 从版本号、父版本或实例记录里认出 Fabric、Forge、Quilt、NeoForge、Forbric、ECXP-Forbric+。 */
public final class ModLoaders {

    private ModLoaders() {}

    public static String fromVersion(String id, String inheritsFrom, String mainClass) {
        return normalize(join(id, inheritsFrom, mainClass));
    }

    /** 空串表示原版或无法判断。NeoForge 要先于 Forge 判断。 */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        if (s.contains("ecxp")) return "ecxp-forbric";
        if (s.contains("forbric")) return "forbric";
        if (s.contains("neoforge")) return "neoforge";
        if (s.contains("quilt")) return "quilt";
        if (s.contains("fabric")) return "fabric";
        if (s.contains("forge") || s.contains("minecraftforge") || s.contains("launchwrapper")) return "forge";
        return "";
    }

    private static String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(part);
        }
        return out.toString();
    }
}
