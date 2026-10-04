package com.pmcl.core.version;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 光影包使用的加载器。和 Fabric/Forge 不是同一类：看游戏里装的是 Iris、OptiFine、Canvas，还是只有原版着色器。
 * Oculus 是 Forge 上的 Iris，按 Iris 光影包筛选。
 */
public final class ShaderLoaders {

    public static final String IRIS = "iris";
    public static final String OPTIFINE = "optifine";
    public static final String CANVAS = "canvas";
    public static final String VANILLA = "vanilla";

    private static final List<String> ORDER = List.of(IRIS, OPTIFINE, CANVAS, VANILLA);

    private ShaderLoaders() {}

    /** 市场文件上的加载器名字。认不出来时返回空串。 */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";
        if (s.equals("iris") || s.equals("oculus")) return IRIS;
        if (s.equals("optifine")) return OPTIFINE;
        if (s.equals("canvas")) return CANVAS;
        if (s.equals("vanilla")) return VANILLA;
        return "";
    }

    /**
     * 这个游戏能用哪些光影加载器。没有任何 Iris / OptiFine / Canvas 时返回原版着色器。
     * 有专用加载器时不再把原版着色器算进去。
     */
    public static List<String> detect(String versionId, String inheritsFrom,
                                       List<String> modIds, List<String> jarNames) {
        LinkedHashSet<String> found = new LinkedHashSet<>();
        considerText(found, versionId);
        considerText(found, inheritsFrom);
        if (modIds != null) {
            for (String id : modIds) {
                String normalized = normalize(id);
                if (!normalized.isEmpty() && !VANILLA.equals(normalized)) found.add(normalized);
            }
        }
        if (jarNames != null) {
            for (String jar : jarNames) considerText(found, jar);
        }
        found.remove("");
        if (found.isEmpty()) found.add(VANILLA);
        if (found.size() > 1) found.remove(VANILLA);
        List<String> ordered = new ArrayList<>();
        for (String id : ORDER) {
            if (found.contains(id)) ordered.add(id);
        }
        return ordered;
    }

    private static void considerText(Set<String> found, String text) {
        if (text == null || text.isBlank()) return;
        String s = text.toLowerCase(Locale.ROOT);
        if (s.contains("optifine")) found.add(OPTIFINE);
        if (s.contains("oculus") || s.contains("iris")) found.add(IRIS);
        if (s.contains("canvas")) found.add(CANVAS);
    }
}
