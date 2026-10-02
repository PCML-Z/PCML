package com.pmcl.core.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I18nTest {

    @AfterEach
    void restoreLocale() {
        I18n.clearPluginStrings(null);
        I18n.setLocale(I18n.ZH_CN);
    }

    @Test
    void bundlesMatchAllThreeLanguages() throws Exception {
        Map<String, String[]> entries = readBundles();
        assertTrue(entries.size() > 2000, "文案数量异常: " + entries.size());
        for (var e : entries.entrySet()) {
            String key = e.getKey();
            String[] row = e.getValue();
            I18n.setLocale(I18n.ZH_CN);
            assertEquals(row[0], I18n.t(key), key);
            I18n.setLocale(I18n.EN_US);
            assertEquals(row[1], I18n.t(key), key);
            I18n.setLocale(I18n.JA_JP);
            assertEquals(row[2], I18n.t(key), key);
        }
    }

    @Test
    void traditionalUsesCharacterMap() {
        I18n.setLocale(I18n.ZH_TW);
        assertEquals("刪除", I18n.t("common.delete"));
        assertEquals("啟動", I18n.t("nav.launch"));
    }

    @Test
    void placeholderAndUpsideDownStayIntact() {
        I18n.setLocale(I18n.EN_US);
        String filled = I18n.t("settings.git_tree.source", "main", "origin");
        assertTrue(filled.contains("main") && filled.contains("origin"), filled);

        I18n.setLocale(I18n.UD_EN);
        assertEquals(I18n.toUpsideDown("Copy"), I18n.t("common.copy"));
    }

    @Test
    void pluginStringOverridesBuiltin() {
        I18n.setLocale(I18n.ZH_CN);
        I18n.putPluginStrings("zh_CN", Map.of("common.delete", "插件删除"));
        assertEquals("插件删除", I18n.t("common.delete"));
        I18n.removePluginStrings("zh_CN", java.util.List.of("common.delete"));
        assertEquals("删除", I18n.t("common.delete"));
    }

    private static Map<String, String[]> readBundles() throws Exception {
        Map<String, String[]> entries = new HashMap<>();
        String catalog = read("/i18n/catalog.txt");
        for (String raw : catalog.split("\n", -1)) {
            String name = raw.trim();
            if (name.isEmpty() || name.startsWith("#")) continue;
            String key = null;
            String zh = null;
            String en = null;
            String ja = null;
            for (String lineRaw : read("/i18n/" + name).split("\n", -1)) {
                String line = lineRaw.endsWith("\r") ? lineRaw.substring(0, lineRaw.length() - 1) : lineRaw;
                if (line.isEmpty() || line.startsWith("#")) {
                    if (line.isEmpty() && key != null && zh != null && en != null && ja != null) {
                        entries.put(key, new String[] {zh, en, ja});
                        key = null;
                    }
                    continue;
                }
                if (line.startsWith("zh=")) zh = unescape(line.substring(3));
                else if (line.startsWith("en=")) en = unescape(line.substring(3));
                else if (line.startsWith("ja=")) ja = unescape(line.substring(3));
                else key = line;
            }
        }
        return entries;
    }

    private static String read(String path) throws Exception {
        try (InputStream in = I18n.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') sb.append('\n');
                else if (n == '\\') sb.append('\\');
                else sb.append(n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
