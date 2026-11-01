package com.pmcl.core.kernel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * 内核清单 {@code pmcl-kernel.json}。
 * <p>
 * runtime 为 java、csharp 或 rust。Java 的 entry 是实现
 * {@link com.pmcl.plugin.kernel.LauncherKernel} 的类名，artifact 是 JAR。
 * C# 的 artifact 是 dll（用 dotnet 启动）或 exe。Rust 的 artifact 是可执行文件。
 */
public final class KernelManifest {

    static final String FILE_NAME = "pmcl-kernel.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$");
    private static final Pattern VERSION = Pattern.compile("^[0-9A-Za-z][0-9A-Za-z._-]{0,31}$");
    private static final Pattern JAVA_CLASS = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*$");

    private String id = "";
    private String name = "";
    private String version = "";
    private String runtime = "";
    private String entry = "";
    private String artifact = "";
    private String description = "";
    private String author = "";

    public KernelManifest() {
    }

    public static KernelManifest read(Path file) throws IOException, KernelException {
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static KernelManifest parse(String json) throws KernelException {
        if (json == null || json.isBlank()) throw new KernelException("missing_manifest");
        String text = json.strip();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        KernelManifest raw;
        try {
            raw = GSON.fromJson(text, KernelManifest.class);
        } catch (JsonSyntaxException e) {
            throw new KernelException("invalid_package");
        }
        if (raw == null) throw new KernelException("missing_manifest");
        raw.normalize();
        return raw;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getRuntime() { return runtime; }
    public String getEntry() { return entry; }
    public String getArtifact() { return artifact; }
    public String getDescription() { return description; }
    public String getAuthor() { return author; }

    /** JAR 导入时，artifact 用实际文件名。 */
    void setArtifact(String artifact) {
        this.artifact = artifact == null ? "" : artifact;
    }

    private void normalize() throws KernelException {
        id = clean(id);
        name = clean(name);
        version = clean(version);
        runtime = clean(runtime).toLowerCase(java.util.Locale.ROOT);
        entry = clean(entry);
        artifact = clean(artifact).replace('\\', '/');
        description = clean(description);
        author = clean(author);

        if (id.length() < 2 || id.length() > 32 || !ID.matcher(id).matches()) {
            throw new KernelException("invalid_id");
        }
        if (name.isEmpty() || name.length() > 64) throw new KernelException("invalid_package");
        if (!VERSION.matcher(version).matches()) throw new KernelException("invalid_package");
        if (description.length() > 256 || author.length() > 64) throw new KernelException("invalid_package");
        if (!runtime.equals("java") && !runtime.equals("csharp") && !runtime.equals("rust")) {
            throw new KernelException("invalid_runtime");
        }
        if (!artifact.isEmpty() && isUnsafeRelative(artifact)) throw new KernelException("invalid_entry");
        switch (runtime) {
            case "java" -> {
                if (!JAVA_CLASS.matcher(entry).matches()) throw new KernelException("invalid_entry");
                if (!artifact.isEmpty() && !artifact.endsWith(".jar")) {
                    throw new KernelException("missing_artifact");
                }
            }
            case "csharp" -> {
                if (artifact.isEmpty()) artifact = entry.replace('\\', '/');
                if (isUnsafeRelative(artifact)
                        || !(artifact.endsWith(".dll") || artifact.endsWith(".exe"))) {
                    throw new KernelException("missing_artifact");
                }
                entry = artifact;
            }
            default -> {
                if (artifact.isEmpty()) artifact = entry.replace('\\', '/');
                if (artifact.isEmpty() || isUnsafeRelative(artifact)) {
                    throw new KernelException("missing_artifact");
                }
                entry = artifact;
            }
        }
    }

    /** 单独导入 JAR 时，清单可以不写 artifact，改用 JAR 自己的文件名。 */
    void bindJarName(String fileName) throws KernelException {
        if (artifact.isEmpty()) artifact = fileName;
        if (isUnsafeRelative(artifact) || !artifact.endsWith(".jar")) {
            throw new KernelException("missing_artifact");
        }
    }

    static boolean isUnsafeRelative(String relative) {
        if (relative == null || relative.isBlank()) return true;
        String value = relative.replace('\\', '/');
        if (value.startsWith("/") || value.startsWith("../") || value.contains("/../") || value.endsWith("/..")
                || value.equals("..")) {
            return true;
        }
        return Path.of(relative).isAbsolute();
    }

    private static String clean(String value) {
        return value == null ? "" : value.strip();
    }
}
