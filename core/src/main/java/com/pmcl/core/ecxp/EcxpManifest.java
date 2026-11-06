package com.pmcl.core.ecxp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.pmcl.plugin.PluginPermission;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ECXP 应用清单 {@code pmcl-app.json}。
 * <p>
 * framework 写 net10.0 或 net11.0。entry 是 dll 时用 dotnet 启动，否则当作自带运行时的可执行文件。
 * permissions 留空表示要全部启动器权限。
 */
public final class EcxpManifest {

    public static final String FILE_NAME = "pmcl-app.json";
    public static final int MIN_FRAMEWORK = 10;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*$");
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._+-]{0,31}$");
    private static final Pattern FRAMEWORK = Pattern.compile("^net(\\d{2,3})(?:\\.0)?$");

    private String id = "";
    private String name = "";
    private String version = "";
    private String author = "";
    private String description = "";
    private String framework = "";
    private String entry = "";
    private String icon = "";
    private List<String> permissions = new ArrayList<>();

    public static EcxpManifest read(Path file) throws IOException, EcxpException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static EcxpManifest parse(String json) throws EcxpException {
        if (json == null || json.isBlank()) throw new EcxpException("missing_manifest");
        String text = json.strip();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        EcxpManifest raw;
        try {
            raw = GSON.fromJson(text, EcxpManifest.class);
        } catch (JsonSyntaxException e) {
            throw new EcxpException("invalid_package");
        }
        if (raw == null) throw new EcxpException("missing_manifest");
        raw.normalize();
        return raw;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getAuthor() { return author; }
    public String getDescription() { return description; }
    public String getFramework() { return framework; }
    public String getEntry() { return entry; }
    public String getIcon() { return icon; }

    /** 目标 .NET 主版本，例如 net11.0 是 11。 */
    public int frameworkMajor() {
        Matcher m = FRAMEWORK.matcher(framework);
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    /** dll 入口要借系统里的 dotnet 运行。 */
    public boolean needsDotnetHost() {
        return entry.toLowerCase(Locale.ROOT).endsWith(".dll");
    }

    public boolean requestsAllPermissions() {
        return permissions.isEmpty();
    }

    /** 应用实际拿到的权限名。 */
    public Set<String> grantedPermissions() {
        return permissions.isEmpty() ? PluginPermission.names() : Set.copyOf(permissions);
    }

    private void normalize() throws EcxpException {
        id = clean(id).toLowerCase(Locale.ROOT);
        name = clean(name);
        version = clean(version);
        author = clean(author);
        description = clean(description);
        framework = clean(framework).toLowerCase(Locale.ROOT);
        entry = clean(entry).replace('\\', '/');
        icon = clean(icon).replace('\\', '/');

        if (id.length() < 2 || id.length() > 64 || !ID.matcher(id).matches()) throw new EcxpException("invalid_id");
        if (name.isEmpty() || name.length() > 64) throw new EcxpException("invalid_package");
        if (!VERSION.matcher(version).matches()) throw new EcxpException("invalid_package");
        if (description.length() > 512 || author.length() > 64) throw new EcxpException("invalid_package");

        Matcher m = FRAMEWORK.matcher(framework);
        if (!m.matches() || Integer.parseInt(m.group(1)) < MIN_FRAMEWORK) {
            throw new EcxpException("invalid_framework", framework);
        }
        framework = "net" + m.group(1) + ".0";

        if (isUnsafeRelative(entry)) throw new EcxpException("invalid_entry");
        if (!icon.isEmpty() && isUnsafeRelative(icon)) icon = "";

        Set<String> known = PluginPermission.names();
        Set<String> picked = new LinkedHashSet<>();
        if (permissions != null) {
            for (String raw : permissions) {
                String p = clean(raw).toUpperCase(Locale.ROOT);
                if (p.isEmpty()) continue;
                if (!known.contains(p)) throw new EcxpException("invalid_permission", p);
                picked.add(p);
            }
        }
        permissions = new ArrayList<>(picked);
    }

    static boolean isUnsafeRelative(String relative) {
        if (relative == null || relative.isBlank()) return true;
        String value = relative.replace('\\', '/');
        if (value.startsWith("/") || value.startsWith("../") || value.contains("/../") || value.endsWith("/..")
                || value.equals("..") || value.contains(":")) {
            return true;
        }
        return Path.of(relative).isAbsolute();
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
