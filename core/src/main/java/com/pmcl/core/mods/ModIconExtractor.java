package com.pmcl.core.mods;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 从 mod jar 中提取图标字节。
 * <p>
 * 优先使用元数据声明的 {@code iconEntry}；否则尝试常见路径 / 文件名。
 */
public final class ModIconExtractor {

    private static final String[] FALLBACK_NAMES = {
            "icon.png", "logo.png", "pack.png", "mod_icon.png",
            "assets/icon.png", "META-INF/icon.png"
    };
    /** 单图标最大字节，防止恶意 jar 撑爆 UI */
    private static final int MAX_ICON_BYTES = 2_000_000;

    private ModIconExtractor() {}

    /**
     * @param jarPath   jar 绝对路径
     * @param iconEntry 元数据中的图标条目（可空）
     * @return PNG/JPG 等图片字节；失败返回 null
     */
    public static byte[] extract(String jarPath, String iconEntry) {
        if (jarPath == null || jarPath.isEmpty()) return null;
        Path path = Path.of(jarPath);
        if (!Files.isRegularFile(path)) return null;
        try (JarFile jar = new JarFile(path.toFile())) {
            if (iconEntry != null && !iconEntry.isEmpty()) {
                byte[] preferred = readEntry(jar, iconEntry);
                if (preferred != null) return preferred;
                // 去掉前导 /
                if (iconEntry.startsWith("/")) {
                    preferred = readEntry(jar, iconEntry.substring(1));
                    if (preferred != null) return preferred;
                }
            }
            for (String name : FALLBACK_NAMES) {
                byte[] bytes = readEntry(jar, name);
                if (bytes != null) return bytes;
            }
            // 扫描 assets/*/icon.png
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName().toLowerCase(java.util.Locale.ROOT);
                if ((n.endsWith("/icon.png") || n.endsWith("/logo.png"))
                        && e.getSize() > 0 && e.getSize() < 2_000_000) {
                    byte[] bytes = readEntry(jar, e.getName());
                    if (bytes != null) return bytes;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static byte[] readEntry(JarFile jar, String name) {
        try {
            JarEntry entry = jar.getJarEntry(name);
            if (entry == null || entry.isDirectory()) return null;
            long declared = entry.getSize();
            if (declared > MAX_ICON_BYTES) return null;
            try (InputStream in = jar.getInputStream(entry)) {
                return com.pmcl.core.util.SafeZipExtractor.readLimited(in, MAX_ICON_BYTES);
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
