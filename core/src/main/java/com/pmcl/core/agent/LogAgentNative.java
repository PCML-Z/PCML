package com.pmcl.core.agent;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Axolotl 的日志诊断引擎。Gradle 用 cargo 把它编成本地库，这里只负责加载和传字符串。
 * 修内存、模组和 Java 不经过这个库。
 */
public final class LogAgentNative {

    private static boolean loaded;

    private LogAgentNative() {}

    public static synchronized void ensure() {
        if (loaded) return;
        Path lib = locate();
        System.load(lib.toAbsolutePath().toString());
        loaded = true;
    }

    public static long open() {
        ensure();
        return open0();
    }

    public static void addFile(long session, String name, String sourceType, String content) {
        ensure();
        addFile0(session, name == null ? "" : name, sourceType == null ? "" : sourceType, content == null ? "" : content);
    }

    public static void setModsDir(long session, String path) {
        ensure();
        setModsDir0(session, path == null ? "" : path);
    }

    public static String context(long session) {
        ensure();
        String text = context0(session);
        return text == null ? "" : text;
    }

    public static String tool(long session, String request) {
        ensure();
        String text = tool0(session, request == null ? "{}" : request);
        return text == null ? "" : text;
    }

    public static void close(long session) {
        if (!loaded || session == 0) return;
        close0(session);
    }

    private static Path locate() {
        String override = System.getProperty("pmcl.logagent.lib", "");
        if (!override.isBlank()) {
            Path given = Path.of(override);
            if (Files.isRegularFile(given)) return given;
        }
        String resource = "/native/" + platform() + "/" + libraryName();
        InputStream input = LogAgentNative.class.getResourceAsStream(resource);
        if (input != null) {
            try (input) {
                Path dir = Files.createTempDirectory("pmcl-logagent");
                dir.toFile().deleteOnExit();
                Path lib = dir.resolve(libraryName());
                Files.copy(input, lib, StandardCopyOption.REPLACE_EXISTING);
                lib.toFile().deleteOnExit();
                return lib;
            } catch (Exception e) {
                throw new IllegalStateException("诊断引擎无法解出：" + e.getMessage(), e);
            }
        }
        Path dev = Path.of("native", "logagent", "target", "release", libraryName());
        if (Files.isRegularFile(dev)) return dev.toAbsolutePath();
        Path sibling = Path.of("..", "native", "logagent", "target", "release", libraryName());
        if (Files.isRegularFile(sibling)) return sibling.toAbsolutePath();
        throw new IllegalStateException("没有找到诊断引擎 " + resource);
    }

    private static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        String family = os.contains("mac") ? "darwin" : os.contains("win") ? "windows" : "linux";
        String cpu = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64";
        return family + "-" + cpu;
    }

    private static String libraryName() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("win")) return "pmcllogagent.dll";
        if (os.contains("mac")) return "libpmcllogagent.dylib";
        return "libpmcllogagent.so";
    }

    private static native long open0();

    private static native void addFile0(long session, String name, String sourceType, String content);

    private static native void setModsDir0(long session, String path);

    private static native String context0(long session);

    private static native String tool0(long session, String request);

    private static native void close0(long session);
}
