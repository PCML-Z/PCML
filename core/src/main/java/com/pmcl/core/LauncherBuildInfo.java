package com.pmcl.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Properties;

/**
 * 关于页上的版本信息。数字来自本次构建写入的属性和当前进程，读不到就留空。
 */
public final class LauncherBuildInfo {

    private final Properties props;

    private LauncherBuildInfo(Properties props) {
        this.props = props;
    }

    public static LauncherBuildInfo load() {
        Properties props = new Properties();
        try (InputStream in = LauncherBuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in != null) {
                try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    props.load(reader);
                }
            }
        } catch (IOException ignored) {
            props.clear();
        }
        return new LauncherBuildInfo(props);
    }

    public String framework() {
        return frameworkOf(System.getProperty("java.specification.version", ""),
                System.getProperty("os.name", ""));
    }

    public String runtime() {
        return runtimeOf(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /** {@code release} 或 {@code dev}。 */
    public String channel() {
        if (props.isEmpty()) return "dev";
        String version = binaryVersion().toLowerCase(Locale.ROOT);
        if (version.contains("snapshot") || version.contains("-dev")) return "dev";
        return "release";
    }

    public String buildTimeUtc() {
        String raw = props.getProperty("buildTime", "").trim();
        if (raw.isEmpty()) return "";
        try {
            return java.time.Instant.parse(raw).truncatedTo(ChronoUnit.SECONDS).toString();
        } catch (RuntimeException e) {
            return raw;
        }
    }

    public String binaryVersion() {
        String stamped = props.getProperty("version", "").trim();
        if (!stamped.isEmpty()) return stamped;
        String version = LauncherBuildInfo.class.getPackage().getImplementationVersion();
        if (version == null || version.isBlank()) {
            version = System.getProperty("pmcl.version", "");
        }
        return version == null ? "" : version.trim();
    }

    public String kotlinVersion() {
        return prop("kotlin");
    }

    public String composeVersion() {
        return prop("compose");
    }

    public String okHttpVersion() {
        String stamped = prop("okhttp");
        if (!stamped.isEmpty()) return stamped;
        try {
            Object value = Class.forName("okhttp3.OkHttp").getField("VERSION").get(null);
            return value == null ? "" : value.toString().trim();
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    public String javaVersion() {
        return System.getProperty("java.version", "").trim();
    }

    public static String frameworkOf(String javaSpec, String osName) {
        String spec = javaSpec == null ? "" : javaSpec.trim();
        String os = osToken(osName);
        if (spec.isEmpty()) return os;
        return os.isEmpty() ? "Java " + spec : "Java " + spec + "-" + os;
    }

    public static String runtimeOf(String osName, String osArch) {
        String os = osToken(osName);
        String arch = archToken(osArch);
        if (os.isEmpty()) return arch;
        if (arch.isEmpty()) return os;
        return os + "-" + arch;
    }

    public static String osToken(String osName) {
        String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (name.contains("mac") || name.contains("darwin")) return "osx";
        if (name.contains("win")) return "windows";
        if (name.contains("linux")) return "linux";
        return "";
    }

    public static String archToken(String arch) {
        String name = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        return switch (name) {
            case "aarch64", "arm64" -> "arm64";
            case "x86_64", "amd64" -> "x64";
            case "x86", "i386", "i686" -> "x86";
            default -> name.isBlank() ? "" : name;
        };
    }

    private String prop(String key) {
        return props.getProperty(key, "").trim();
    }
}
