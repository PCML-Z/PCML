package com.pmcl.core.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 启动器国际化。
 * <p>
 * 当前支持：zh_CN（默认）、zh_TW、en_US、ja_JP、ud_EN（颠倒英语，趣味彩蛋，复用 en_US 翻译 + 字符翻转）。
 * 文案在 {@code resources/i18n/}，按键名前缀分成多个文件，由 {@code catalog.txt} 列出。
 * 繁体由简体经 {@code s2t.txt} 逐字转换。
 * <p>
 * 用法：{@code I18n.setLocale(Locale.JAPANESE); I18n.t("launch.start")}
 */
public final class I18n {

    public static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;
    public static final Locale ZH_TW = Locale.TRADITIONAL_CHINESE;
    public static final Locale EN_US = Locale.US;
    public static final Locale JA_JP = Locale.JAPANESE;
    /** 颠倒英语：基于 en_US 翻译做字符级翻转，保留阅读顺序避免布局错乱 */
    public static final Locale UD_EN = Locale.of("ud", "EN");

    private static volatile Locale current = ZH_CN;

    private static final Map<String, String> ZH = new LinkedHashMap<>();
    private static final Map<String, String> ZH_TW_MAP = new LinkedHashMap<>();
    private static final Map<String, String> EN = new LinkedHashMap<>();
    private static final Map<String, String> JA = new LinkedHashMap<>();

    /**
     * Plugin-registered overlays: languageCode → (key → value).
     * Looked up before built-in maps so plugins can localize their own strings
     * (and optionally override host keys — prefer plugin-prefixed keys).
     */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.ConcurrentHashMap<String, String>> PLUGIN_STRINGS =
            new java.util.concurrent.ConcurrentHashMap<>();

    static {
        loadBundles();
        initZhTw();
    }

    private static void loadBundles() {
        String catalog = readUtf8("/i18n/catalog.txt");
        for (String raw : catalog.split("\n", -1)) {
            String name = stripCr(raw).trim();
            if (name.isEmpty() || name.startsWith("#")) continue;
            loadBundle(readUtf8("/i18n/" + name));
        }
    }

    private static void loadBundle(String text) {
        String key = null;
        for (String raw : text.split("\n", -1)) {
            String line = stripCr(raw);
            if (line.isEmpty()) {
                key = null;
                continue;
            }
            if (line.startsWith("#")) continue;
            if (line.startsWith("zh=") || line.startsWith("en=") || line.startsWith("ja=")) {
                if (key == null) throw new IllegalStateException("语言条目缺少键: " + line);
                String value = unescape(line.substring(3));
                if (line.startsWith("zh=")) ZH.put(key, value);
                else if (line.startsWith("en=")) EN.put(key, value);
                else JA.put(key, value);
            } else {
                key = line;
            }
        }
    }

    private static void initZhTw() {
        Map<Character, Character> s2t = loadSimpToTrad();
        for (Map.Entry<String, String> e : ZH.entrySet()) {
            ZH_TW_MAP.put(e.getKey(), toTraditional(e.getValue(), s2t));
        }
    }

    private static Map<Character, Character> loadSimpToTrad() {
        Map<Character, Character> map = new HashMap<>(1024);
        for (String raw : readUtf8("/i18n/s2t.txt").split("\n", -1)) {
            String line = stripCr(raw).trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.length() != 2) throw new IllegalStateException("简繁对照必须是两个字: " + line);
            map.put(line.charAt(0), line.charAt(1));
        }
        return map;
    }

    /** 将简体中文字符串转为繁体中文（字符级替换，不改变标点和占位符 {0} 等） */
    private static String toTraditional(String input, Map<Character, Character> map) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            Character t = map.get(c);
            sb.append(t != null ? t : c);
        }
        return sb.toString();
    }

    private static String readUtf8(String path) {
        try (InputStream in = I18n.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("缺少语言资源 " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取语言资源失败 " + path, e);
        }
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    /** 资源文件里的转义：\\n 是换行，\\\\ 是反斜杠。 */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'n') sb.append('\n');
                else if (n == 'r') sb.append('\r');
                else if (n == '\\') sb.append('\\');
                else sb.append(n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static Locale getCurrentLocale() { return current; }

    public static void setLocale(Locale locale) {
        current = locale;
    }

    /** 翻译键，支持 {0} {1} 等参数占位符 */
    public static String t(String key, Object... args) {
        Map<String, String> map;
        String langCode;
        if (current == EN_US || current == UD_EN) { map = EN; langCode = "en_US"; }
        else if (current == JA_JP) { map = JA; langCode = "ja_JP"; }
        else if (current == ZH_TW) { map = ZH_TW_MAP; langCode = "zh_TW"; }
        else { map = ZH; langCode = "zh_CN"; }

        String val = null;
        var pluginMap = PLUGIN_STRINGS.get(langCode);
        if (pluginMap != null) val = pluginMap.get(key);
        if (val == null) val = map.getOrDefault(key, key);

        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                val = val.replace("{" + i + "}", String.valueOf(args[i]));
            }
        }
        // 颠倒英语：参数填充后再做字符翻转，避免占位符 {0} 被翻转
        if (current == UD_EN) val = toUpsideDown(val);
        return val;
    }

    /**
     * Register or replace plugin translation strings for a language code.
     * Keys should be plugin-prefixed (e.g. {@code myplugin.hello}).
     */
    public static void putPluginStrings(String language, Map<String, String> strings) {
        if (language == null || language.isBlank() || strings == null || strings.isEmpty()) return;
        String lang = language.trim();
        var map = PLUGIN_STRINGS.computeIfAbsent(lang, k -> new java.util.concurrent.ConcurrentHashMap<>());
        for (Map.Entry<String, String> e : strings.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) continue;
            map.put(e.getKey(), e.getValue() != null ? e.getValue() : "");
        }
    }

    /** Remove specific keys previously registered for [language]. */
    public static void removePluginStrings(String language, Iterable<String> keys) {
        if (language == null || language.isBlank() || keys == null) return;
        var map = PLUGIN_STRINGS.get(language.trim());
        if (map == null) return;
        for (String k : keys) {
            if (k != null) map.remove(k);
        }
    }

    /** Clear all plugin strings for one language, or every language when language is blank. */
    public static void clearPluginStrings(String language) {
        if (language == null || language.isBlank()) {
            PLUGIN_STRINGS.clear();
        } else {
            PLUGIN_STRINGS.remove(language.trim());
        }
    }

    /** 颠倒英语字符映射表，索引为 ASCII 码（0-127），未映射位置存放原字符 */
    private static final char[] UPSIDE_DOWN = buildUpsideDownTable();

    private static char[] buildUpsideDownTable() {
        char[] table = new char[128];
        for (int i = 0; i < 128; i++) table[i] = (char) i;
        String src = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,?!'\"()[]{}";
        String dst = "ɐqɔpǝɟƃɥᴉɾʞlɯuodbɹsʇnʌʍxʎz∀ᗺƆᗡƎᖷ⅁HIſʞ˥WNOԀᑫᴚS┴∩ΛMX⅄Z0ƖᄅƐㄣϛ9ㄥ86˙'¿¡,'„)(][}{";
        for (int i = 0; i < src.length() && i < dst.length(); i++) {
            table[src.charAt(i)] = dst.charAt(i);
        }
        return table;
    }

    /**
     * 将字符串中的 ASCII 字母/数字/标点替换为颠倒形态的 Unicode 字符。
     * 仅做字符级替换，不反转字符串顺序，保留 UI 布局的可读性。
     */
    public static String toUpsideDown(String input) {
        if (input == null || input.isEmpty()) return input;
        StringBuilder sb = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            sb.append(c < 128 ? UPSIDE_DOWN[c] : c);
        }
        return sb.toString();
    }
}
