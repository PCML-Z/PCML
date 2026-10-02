package com.pmcl.core.launch;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 用即将启动游戏的 Java 打开一次麦克风，让 macOS 把权限记在这个 Java 上。
 * 探测类是 Java 8 字节码，旧版本的 Java 也能跑。失败或超时都不阻止启动。
 */
public final class MacMicrophone {
    private MacMicrophone() {}

    public static void request(String javaExecutable) {
        String os = System.getProperty("os.name", "");
        if (!os.toLowerCase(Locale.ROOT).contains("mac")) return;
        if (javaExecutable == null || javaExecutable.isBlank()) return;
        Path root = null;
        try {
            root = Files.createTempDirectory("pmcl-mic");
            Path classFile = root.resolve("com/pmcl/core/launch/MacMicrophoneProbe.class");
            Files.createDirectories(classFile.getParent());
            try (InputStream in = MacMicrophone.class.getResourceAsStream("/launch/MacMicrophoneProbe.class")) {
                if (in == null) {
                    System.err.println("[PMCL] 找不到麦克风权限探测类");
                    return;
                }
                Files.copy(in, classFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Process process = new ProcessBuilder(
                    javaExecutable, "-cp", root.toString(), "com.pmcl.core.launch.MacMicrophoneProbe")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                System.err.println("[PMCL] 麦克风权限请求超时，继续启动");
            }
        } catch (Throwable t) {
            System.err.println("[PMCL] 麦克风权限请求失败，继续启动: " + t.getMessage());
        } finally {
            if (root != null) deleteTree(root);
        }
    }

    private static void deleteTree(Path root) {
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
