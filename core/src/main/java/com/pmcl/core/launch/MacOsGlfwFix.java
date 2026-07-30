package com.pmcl.core.launch;

import com.pmcl.core.download.DownloadManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * macOS 上 MC 1.13–1.16（LWJGL 3.1.x）自带的 GLFW 过旧，在 Apple Silicon / 新系统上
 * 会报 {@code Cocoa: Failed to find service port for display}。
 * <p>
 * 从 LWJGL 3.3.3 提取与游戏 Java 架构匹配的 {@code libglfw.dylib}，覆盖 natives 并可通过
 * {@code -Dorg.lwjgl.glfw.libname=} 强制加载。
 */
public final class MacOsGlfwFix {

    private static final String GLFW_LWJGL_VER = "3.3.3";
    private static final String MARKER = ".pmcl-glfw-fix";
    private static final String MARKER_VALUE = "lwjgl-glfw-" + GLFW_LWJGL_VER;

    private static final String JAR_X64 =
            "https://libraries.minecraft.net/org/lwjgl/lwjgl-glfw/" + GLFW_LWJGL_VER
                    + "/lwjgl-glfw-" + GLFW_LWJGL_VER + "-natives-macos.jar";
    private static final String JAR_ARM64 =
            "https://libraries.minecraft.net/org/lwjgl/lwjgl-glfw/" + GLFW_LWJGL_VER
                    + "/lwjgl-glfw-" + GLFW_LWJGL_VER + "-natives-macos-arm64.jar";
    private static final String JAR_X64_FALLBACK =
            "https://repo1.maven.org/maven2/org/lwjgl/lwjgl-glfw/" + GLFW_LWJGL_VER
                    + "/lwjgl-glfw-" + GLFW_LWJGL_VER + "-natives-macos.jar";
    private static final String JAR_ARM64_FALLBACK =
            "https://repo1.maven.org/maven2/org/lwjgl/lwjgl-glfw/" + GLFW_LWJGL_VER
                    + "/lwjgl-glfw-" + GLFW_LWJGL_VER + "-natives-macos-arm64.jar";

    private MacOsGlfwFix() {}

    /**
     * 1.13–1.16（含 OptiFine 等派生 ID）：官方 LWJGL 3.1.x GLFW 在现代 macOS 上易崩。
     */
    public static boolean shouldApply(String versionId) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return false;
        }
        int rel = RetroWrapperSupport.estimateMinecraftRelease(versionId);
        return rel >= 1013 && rel < 1017;
    }

    /**
     * 确保 natives 目录中有可用的现代 GLFW，返回 dylib 路径（供 libname 参数）。
     *
     * @param javaArch 游戏 Java 架构（x86_64 / aarch64），决定下载哪套 natives
     */
    public static Path ensure(Path nativesDir, Path workDir, DownloadManager downloads,
                              String javaArch) throws IOException {
        if (nativesDir == null || downloads == null) {
            throw new IOException("nativesDir/downloads 为空");
        }
        Files.createDirectories(nativesDir);
        Path dest = nativesDir.resolve("libglfw.dylib");
        Path marker = nativesDir.resolve(MARKER);
        if (Files.isRegularFile(dest) && Files.isRegularFile(marker)) {
            String v = Files.readString(marker).trim();
            if (MARKER_VALUE.equals(v) && Files.size(dest) > 50_000L) {
                return dest;
            }
        }

        boolean arm = isArm64(javaArch);
        String primary = arm ? JAR_ARM64 : JAR_X64;
        String fallback = arm ? JAR_ARM64_FALLBACK : JAR_X64_FALLBACK;
        Path cacheDir = workDir.resolve("libraries").resolve("pmcl-macos-glfw")
                .resolve(GLFW_LWJGL_VER);
        Files.createDirectories(cacheDir);
        String jarName = arm
                ? "lwjgl-glfw-" + GLFW_LWJGL_VER + "-natives-macos-arm64.jar"
                : "lwjgl-glfw-" + GLFW_LWJGL_VER + "-natives-macos.jar";
        Path jar = cacheDir.resolve(jarName);

        if (!Files.isRegularFile(jar) || Files.size(jar) < 10_000L) {
            downloadJar(downloads, primary, fallback, jar);
        }
        extractGlfwDylib(jar, dest);
        if (!Files.isRegularFile(dest) || Files.size(dest) < 50_000L) {
            throw new IOException("未能从 " + jarName + " 提取有效的 libglfw.dylib");
        }
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                    new java.util.HashSet<>(Files.getPosixFilePermissions(dest));
            perms.add(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE);
            perms.add(java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(dest, perms);
        } catch (UnsupportedOperationException ignored) {
        }
        Files.writeString(marker, MARKER_VALUE);
        clearQuarantine(dest);
        System.err.println("[PMCL] 已注入现代 GLFW (" + GLFW_LWJGL_VER
                + (arm ? " arm64" : " x86_64") + ") → " + dest);
        return dest;
    }

    /**
     * 解出 GLFW 窗口图标修复 Java Agent（将 glfwSetWindowIcon 置空）。
     * 与 {@link #ensure} 配套：现代 GLFW 会报 65548，需 Agent 忽略/跳过图标设置。
     */
    public static Path ensureIconFixAgent(Path workDir) throws IOException {
        if (workDir == null) throw new IOException("workDir 为空");
        Path dest = workDir.resolve("boot").resolve("pmcl-glfw-icon-agent.jar");
        byte[] expected;
        try (InputStream in = MacOsGlfwFix.class.getResourceAsStream(
                "/com/pmcl/core/glfw/pmcl-glfw-icon-agent.jar")) {
            if (in == null) {
                throw new IOException("缺少内嵌资源 com/pmcl/core/glfw/pmcl-glfw-icon-agent.jar"
                        + "（请确认 :core:glfwAgentJar 已构建）");
            }
            expected = in.readAllBytes();
        }
        if (Files.isRegularFile(dest) && Files.size(dest) == expected.length) {
            byte[] existing = Files.readAllBytes(dest);
            if (java.util.Arrays.equals(existing, expected)) {
                return dest;
            }
        }
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.write(tmp, expected);
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        clearQuarantine(dest);
        System.err.println("[PMCL] 已准备 GLFW icon-fix agent → " + dest);
        return dest;
    }

    /** 清除下载 dylib 的 macOS quarantine，避免 Gatekeeper 拦截加载。 */
    private static void clearQuarantine(Path file) {
        try {
            Process p = new ProcessBuilder("xattr", "-d", "com.apple.quarantine", file.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private static void downloadJar(DownloadManager downloads, String primary, String fallback,
                                    Path jar) throws IOException {
        IOException last = null;
        for (String url : new String[]{primary, fallback}) {
            try {
                String sha1Body = downloads.downloadString(url + ".sha1").trim();
                String sha1 = sha1Body.split("\\s+")[0];
                if (sha1.length() != 40) {
                    throw new IOException("无效 SHA-1: " + sha1Body);
                }
                downloads.downloadToVerified(url, jar, sha1, null);
                return;
            } catch (IOException e) {
                last = e;
                try { Files.deleteIfExists(jar); } catch (IOException ignored) {}
            }
        }
        throw new IOException("下载 LWJGL GLFW natives 失败", last);
    }

    private static void extractGlfwDylib(Path jar, Path dest) throws IOException {
        Path tmp = dest.resolveSibling("libglfw.dylib.pmcl-tmp");
        Files.deleteIfExists(tmp);
        boolean found = false;
        try (InputStream in = Files.newInputStream(jar);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                String base = name.substring(name.lastIndexOf('/') + 1);
                if (!"libglfw.dylib".equalsIgnoreCase(base)) continue;
                Files.copy(zis, tmp, StandardCopyOption.REPLACE_EXISTING);
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IOException("JAR 中未找到 libglfw.dylib: " + jar);
        }
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isArm64(String javaArch) {
        String a = javaArch != null ? javaArch.toLowerCase(Locale.ROOT) : "";
        if (a.contains("aarch64") || a.contains("arm64")) return true;
        if (a.contains("x86_64") || a.contains("amd64") || a.equals("x64")) return false;
        // 未指定时按进程架构（启动器本身）兜底
        String osArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return osArch.contains("aarch64") || osArch.contains("arm64");
    }
}
