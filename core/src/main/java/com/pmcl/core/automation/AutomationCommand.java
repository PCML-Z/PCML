package com.pmcl.core.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 用户自己的自动化命令。Kotlin / Java 存源码，C / C++ / Go 只存已编译的可执行文件。 */
public final class AutomationCommand {

    public static final int MAX_COMMANDS = 12;
    public static final int MAX_SOURCE = 20000;
    public static final int MAX_ARGS = 12;

    public static final String KOTLIN = "KOTLIN";
    public static final String JAVA = "JAVA";
    public static final String C = "C";
    public static final String CPP = "CPP";
    public static final String GO = "GO";

    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_LAUNCHER_START = "LAUNCHER_START";
    public static final String TRIGGER_BEFORE_GAME = "BEFORE_GAME";
    public static final String TRIGGER_AFTER_GAME = "AFTER_GAME";

    private final String id;
    private final String name;
    private final String language;
    private final String trigger;
    private final boolean enabled;
    private final String source;
    private final String executable;
    private final List<String> args;

    public AutomationCommand(String id, String name, String language, String trigger, boolean enabled,
                             String source, String executable, List<String> args) {
        this.id = id == null ? "" : id;
        this.name = name == null ? "" : name;
        this.language = language == null ? "" : language;
        this.trigger = trigger == null ? "" : trigger;
        this.enabled = enabled;
        this.source = source == null ? "" : source;
        this.executable = executable == null ? "" : executable;
        this.args = args == null ? List.of() : List.copyOf(args);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getLanguage() { return language; }
    public String getTrigger() { return trigger; }
    public boolean isEnabled() { return enabled; }
    public String getSource() { return source; }
    public String getExecutable() { return executable; }
    public List<String> getArgs() { return args; }

    public static boolean isJvm(String language) {
        return KOTLIN.equals(language) || JAVA.equals(language);
    }

    public boolean isJvm() { return isJvm(language); }

    public static AutomationCommand sanitize(String id, String name, String language, String trigger,
                                              boolean enabled, String source, String executable,
                                              List<String> args) {
        String cleanId = id == null ? "" : id.trim();
        if (!cleanId.matches("[A-Za-z0-9_-]{1,40}")) return null;
        String lang = normalizeLanguage(language);
        if (lang == null) return null;
        String when = normalizeTrigger(trigger);
        if (when == null) return null;
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) cleanName = "command";
        if (cleanName.length() > 48 || hasControl(cleanName)) return null;
        String cleanSource = source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n');
        if (cleanSource.length() > MAX_SOURCE || hasForbidden(cleanSource)) return null;
        String cleanExe = executable == null ? "" : executable.trim();
        if (isJvm(lang)) {
            cleanExe = "";
        } else {
            cleanSource = "";
            if (!cleanExe.isEmpty()) {
                if (cleanExe.length() > 1024 || hasControl(cleanExe)) return null;
                try {
                    if (!Path.of(cleanExe).isAbsolute()) return null;
                } catch (Exception e) {
                    return null;
                }
            }
        }
        List<String> cleanArgs = new ArrayList<>();
        if (args != null) {
            for (String arg : args) {
                if (cleanArgs.size() >= MAX_ARGS) break;
                if (arg == null) continue;
                String value = arg.replace("\r", "").replace("\n", "").trim();
                if (value.isEmpty() || value.length() > 200 || hasControl(value)) continue;
                cleanArgs.add(value);
            }
        }
        return new AutomationCommand(cleanId, cleanName, lang, when, enabled, cleanSource, cleanExe, cleanArgs);
    }

    public static AutomationCommand fromJson(JsonObject o) {
        if (o == null) return null;
        try {
            List<String> args = new ArrayList<>();
            if (o.has("args") && o.get("args").isJsonArray()) {
                for (var el : o.getAsJsonArray("args")) {
                    if (el.isJsonPrimitive()) args.add(el.getAsString());
                }
            }
            boolean enabled = false;
            if (o.has("enabled") && o.get("enabled").isJsonPrimitive()) {
                enabled = o.get("enabled").getAsBoolean();
            }
            return sanitize(
                    text(o, "id"),
                    text(o, "name"),
                    text(o, "language"),
                    text(o, "trigger"),
                    enabled,
                    text(o, "source"),
                    text(o, "executable"),
                    args
            );
        } catch (Exception e) {
            return null;
        }
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("name", name);
        o.addProperty("language", language);
        o.addProperty("trigger", trigger);
        o.addProperty("enabled", enabled);
        o.addProperty("source", source);
        o.addProperty("executable", executable);
        JsonArray arr = new JsonArray();
        for (String arg : args) arr.add(arg);
        o.add("args", arr);
        return o;
    }

    private static String text(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        return o.get(key).getAsString();
    }

    private static String normalizeLanguage(String language) {
        if (language == null) return null;
        String value = language.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case KOTLIN, JAVA, C, CPP, GO -> value;
            default -> null;
        };
    }

    private static String normalizeTrigger(String trigger) {
        if (trigger == null) return null;
        String value = trigger.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case TRIGGER_MANUAL, TRIGGER_LAUNCHER_START, TRIGGER_BEFORE_GAME, TRIGGER_AFTER_GAME -> value;
            default -> null;
        };
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }

    /** 源码允许换行和制表符，其余控制字符丢掉整条命令。 */
    private static boolean hasForbidden(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\n' || c == '\t') continue;
            if (c < 0x20 || c == 0x7f) return true;
        }
        return false;
    }
}
