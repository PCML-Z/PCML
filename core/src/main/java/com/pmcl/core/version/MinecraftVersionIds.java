package com.pmcl.core.version;

/**
 * 从启动器里的版本号取出 Minecraft 游戏版本。
 * 新的快照、预发布和候选版本身带减号，不能从第一个减号切开。
 */
public final class MinecraftVersionIds {

    private MinecraftVersionIds() {}

    public static String gameVersion(String versionId) {
        return gameVersion(versionId, null);
    }

    /** 有父版本时优先用父版本，避免把 fabric-loader-… 当成游戏名。 */
    public static String gameVersion(String versionId, String inheritsFrom) {
        if (inheritsFrom != null && !inheritsFrom.isBlank()) {
            String parent = unwrap(inheritsFrom.trim());
            if (isVanillaId(parent)) return parent;
        }
        if (versionId == null) return "";
        return unwrap(versionId.trim());
    }

    private static String unwrap(String id) {
        if (id.isEmpty()) return "";
        if (isVanillaId(id)) return id;
        int dash = id.indexOf('-');
        if (dash > 0) return id.substring(0, dash);
        return id;
    }

    static boolean isVanillaId(String id) {
        return id.matches("\\d+\\.\\d+-(snapshot|rc|pre)-\\d+")
                || id.matches("\\d+\\.\\d+(\\.\\d+)?-(pre|rc)\\d+")
                || id.matches("\\d+w\\d+[a-z]")
                || id.matches("\\d+\\.\\d+(\\.\\d+)?")
                || id.matches("[ab]\\d+\\.\\d+(\\.\\d+)?")
                || id.matches("inf-\\d+");
    }
}
