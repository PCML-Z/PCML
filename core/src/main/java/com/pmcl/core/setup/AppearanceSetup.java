package com.pmcl.core.setup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;
import com.pmcl.core.preferences.Preferences;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 安装器和命令行用来设置 PMCL 外观的接口。
 * <p>
 * 只改主题、字体、缩放、语言和玻璃/材质开关，并走 {@link Preferences} 的 setter。
 * 不会整份替换 preferences.json，账号、内存、下载和代理都保持原样。
 * 选择了主题预设时，会清掉自定义强调色、壁纸取色和插件主题包，否则预设不会显示出来。
 * <p>
 * 安装器把选择写到 {@code <工作目录>/appearance-setup.json}。启动器下次启动时读取并删除。
 * 也可以直接执行：
 * {@code java -cp pmcl.jar com.pmcl.core.setup.AppearanceSetup apply-file <文件>}
 */
public final class AppearanceSetup {
    public static final String FILE_NAME = "appearance-setup.json";

    public record Choice(String id, String label) {}

    private AppearanceSetup() {}

    public static List<Choice> themes() {
        return List.of(
                new Choice("default", "默认"),
                new Choice("ocean", "海洋"),
                new Choice("forest", "森林"),
                new Choice("sunset", "日落"),
                new Choice("lavender", "薰衣草"),
                new Choice("sakura", "樱花"),
                new Choice("midnight", "午夜"),
                new Choice("ember", "余烬"),
                new Choice("teal", "青绿"),
                new Choice("sand", "沙色")
        );
    }

    public static List<Choice> colorModes() {
        return List.of(
                new Choice("normal", "标准"),
                new Choice("amoled", "纯黑"),
                new Choice("high_contrast", "高对比"),
                new Choice("soft", "柔和")
        );
    }

    public static List<Choice> languages() {
        return List.of(
                new Choice("zh_CN", "简体中文"),
                new Choice("zh_TW", "繁體中文"),
                new Choice("en_US", "English"),
                new Choice("ja_JP", "日本語"),
                new Choice("ud_EN", "uʍop-ǝpᴉsdn")
        );
    }

    public static String catalogJson() {
        JsonObject root = new JsonObject();
        root.add("themes", choicesJson(themes()));
        root.add("colorModes", choicesJson(colorModes()));
        root.add("languages", choicesJson(languages()));
        JsonObject scale = new JsonObject();
        scale.addProperty("min", 0.8);
        scale.addProperty("max", 1.5);
        root.add("uiScale", scale);
        root.addProperty("font", "空字符串表示自动字体");
        return root.toString();
    }

    private static com.google.gson.JsonArray choicesJson(List<Choice> choices) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        for (Choice choice : choices) {
            JsonObject item = new JsonObject();
            item.addProperty("id", choice.id());
            item.addProperty("label", choice.label());
            array.add(item);
        }
        return array;
    }

    /** 只填写要改的项。没填的项保持启动器里原来的值。 */
    public static final class Selection {
        private String themePreset;
        private Boolean dark;
        private Boolean followSystem;
        private String colorMode;
        private Double uiScale;
        private String font;
        private boolean fontSet;
        private Boolean glass;
        private Boolean material;
        private String language;

        public Selection theme(String id) { themePreset = id; return this; }
        public Selection dark(boolean value) { dark = value; return this; }
        public Selection followSystem(boolean value) { followSystem = value; return this; }
        public Selection colorMode(String id) { colorMode = id; return this; }
        public Selection scale(double value) { uiScale = value; return this; }
        public Selection font(String name) { font = name == null ? "" : name; fontSet = true; return this; }
        public Selection glass(boolean value) { glass = value; return this; }
        public Selection material(boolean value) { material = value; return this; }
        public Selection language(String id) { language = id; return this; }

        public void apply(Preferences preferences) {
            if (preferences == null) throw new IllegalArgumentException("缺少配置");
            validate();
            if (themePreset != null) {
                preferences.setThemePreset(themePreset);
                preferences.setCustomAccentColor(-1);
                preferences.setDynamicColor(false);
                preferences.setCustomThemePackId("");
            }
            if (dark != null) preferences.setUseDarkTheme(dark);
            if (followSystem != null) preferences.setFollowSystemTheme(followSystem);
            if (colorMode != null) preferences.setColorMode(colorMode);
            if (uiScale != null) preferences.setUiScale(uiScale.floatValue());
            if (fontSet) preferences.setLauncherFont(font);
            if (glass != null) preferences.setGlassTheme(glass);
            if (material != null) preferences.setMaterialTheme(material);
            if (language != null) preferences.setLanguage(language);
        }

        public String toJson() {
            validate();
            JsonObject o = new JsonObject();
            if (themePreset != null) o.addProperty("themePreset", themePreset);
            if (dark != null) o.addProperty("dark", dark);
            if (followSystem != null) o.addProperty("followSystem", followSystem);
            if (colorMode != null) o.addProperty("colorMode", colorMode);
            if (uiScale != null) o.addProperty("uiScale", uiScale);
            if (fontSet) o.addProperty("font", font);
            if (glass != null) o.addProperty("glass", glass);
            if (material != null) o.addProperty("material", material);
            if (language != null) o.addProperty("language", language);
            return o.toString();
        }

        private void validate() {
            if (themePreset != null) requireChoice(themePreset, themes(), "未知主题");
            if (colorMode != null) requireChoice(colorMode, colorModes(), "未知色彩模式");
            if (language != null) requireChoice(language, languages(), "未知语言");
            if (uiScale != null && (uiScale.isNaN() || uiScale < 0.8 || uiScale > 1.5)) {
                throw new IllegalArgumentException("界面缩放需在 0.8 到 1.5 之间");
            }
            if (fontSet) {
                if (font.length() > 120 || font.chars().anyMatch(c -> c < 0x20)) {
                    throw new IllegalArgumentException("字体名称无效");
                }
            }
        }
    }

    public static Selection parse(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("个性化配置是空的");
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        Selection selection = new Selection();
        if (o.has("themePreset") && !o.get("themePreset").isJsonNull()) {
            selection.theme(o.get("themePreset").getAsString());
        }
        if (o.has("dark") && !o.get("dark").isJsonNull()) selection.dark(o.get("dark").getAsBoolean());
        if (o.has("followSystem") && !o.get("followSystem").isJsonNull()) {
            selection.followSystem(o.get("followSystem").getAsBoolean());
        }
        if (o.has("colorMode") && !o.get("colorMode").isJsonNull()) {
            selection.colorMode(o.get("colorMode").getAsString());
        }
        if (o.has("uiScale") && !o.get("uiScale").isJsonNull()) selection.scale(o.get("uiScale").getAsDouble());
        if (o.has("font") && !o.get("font").isJsonNull()) selection.font(o.get("font").getAsString());
        if (o.has("glass") && !o.get("glass").isJsonNull()) selection.glass(o.get("glass").getAsBoolean());
        if (o.has("material") && !o.get("material").isJsonNull()) selection.material(o.get("material").getAsBoolean());
        if (o.has("language") && !o.get("language").isJsonNull()) selection.language(o.get("language").getAsString());
        selection.validate();
        return selection;
    }

    public static void write(Path directory, Selection selection) throws IOException {
        if (directory == null) throw new IllegalArgumentException("缺少工作目录");
        Files.createDirectories(directory);
        Path file = directory.resolve(FILE_NAME);
        Path tmp = directory.resolve(FILE_NAME + ".tmp");
        Files.writeString(tmp, selection.toJson(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** 文件不存在时什么也不做。读到了就应用并删除，坏文件改名为 .bad。 */
    public static boolean consume(Preferences preferences, Path file) {
        if (preferences == null || file == null || !Files.isRegularFile(file)) return false;
        try {
            parse(Files.readString(file, StandardCharsets.UTF_8)).apply(preferences);
            preferences.save();
            Files.deleteIfExists(file);
            return true;
        } catch (RuntimeException | IOException e) {
            System.err.println("[Appearance] 未能应用个性化设置: " + e.getMessage());
            try {
                Files.move(file, file.resolveSibling(FILE_NAME + ".bad"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
            }
            return false;
        }
    }

    /**
     * @return 是否改过 Preferences。查看目录时返回 false，避免无意义地重写 preferences.json。
     */
    public static boolean command(String[] args, Preferences preferences) throws IOException {
        if (args == null || args.length == 0 || "help".equals(args[0])) {
            System.out.println("appearance catalog");
            System.out.println("appearance apply --theme midnight --dark false --follow-system false --color-mode normal --scale 1.0 --font \"\" --glass true --material true --language zh_CN");
            System.out.println("appearance apply-file <appearance-setup.json>");
            return false;
        }
        switch (args[0]) {
            case "catalog" -> {
                System.out.println(catalogJson());
                return false;
            }
            case "apply-file" -> {
                if (args.length < 2 || args[1].isBlank()) throw new IllegalArgumentException("缺少配置文件路径");
                Path file = Path.of(args[1]);
                if (!Files.isRegularFile(file)) throw new IllegalArgumentException("找不到 " + file);
                if (!consume(preferences, file)) throw new IllegalArgumentException("个性化配置无效");
                System.out.println("[OK] 已应用外观设置");
                return true;
            }
            case "apply" -> {
                Selection selection = flags(args);
                selection.apply(preferences);
                System.out.println("[OK] 已应用外观设置");
                return true;
            }
            default -> throw new IllegalArgumentException("未知外观命令: " + args[0]);
        }
    }

    public static void main(String[] args) {
        Preferences preferences = null;
        int code = 1;
        try {
            preferences = new Preferences(LauncherConfig.pmclHome().resolve("preferences.json"));
            if (command(args, preferences)) preferences.save();
            code = 0;
        } catch (Exception e) {
            System.err.println(e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (preferences != null) {
                try { preferences.shutdown(); } catch (Throwable ignored) {}
            }
        }
        System.exit(code);
    }

    private static Selection flags(String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("appearance apply 至少需要一项设置");
        Selection selection = new Selection();
        for (int i = 1; i < args.length; i++) {
            String flag = args[i];
            if (i + 1 >= args.length) throw new IllegalArgumentException("缺少参数值: " + flag);
            String value = args[++i];
            switch (flag) {
                case "--theme" -> selection.theme(value);
                case "--dark" -> selection.dark(parseBool(value));
                case "--follow-system" -> selection.followSystem(parseBool(value));
                case "--color-mode" -> selection.colorMode(value);
                case "--scale" -> selection.scale(Double.parseDouble(value));
                case "--font" -> selection.font(value);
                case "--glass" -> selection.glass(parseBool(value));
                case "--material" -> selection.material(parseBool(value));
                case "--language" -> selection.language(value);
                default -> throw new IllegalArgumentException("未知参数: " + flag);
            }
        }
        return selection;
    }

    private static boolean parseBool(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("需要 true 或 false: " + value);
    }

    private static void requireChoice(String value, List<Choice> choices, String message) {
        for (Choice choice : choices) {
            if (choice.id().equals(value)) return;
        }
        throw new IllegalArgumentException(message + ": " + value);
    }
}
