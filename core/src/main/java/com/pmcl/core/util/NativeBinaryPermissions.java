package com.pmcl.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 本地下载的原生二进制权限加固：可执行位 + macOS quarantine 清理。
 */
public final class NativeBinaryPermissions {

    private NativeBinaryPermissions() {}

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    public static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    /**
     * 赋予 owner/group/other 可执行权限；失败时抛 IOException（不再静默）。
     */
    public static void makeExecutable(Path binary) throws IOException {
        if (binary == null || !Files.exists(binary) || isWindows()) return;
        Set<PosixFilePermission> perms = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE);
        try {
            Files.setPosixFilePermissions(binary, perms);
            return;
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX：回退 chmod
        }
        Process p = new ProcessBuilder("chmod", "+x", binary.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        try {
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("chmod +x timed out: " + binary);
            }
            if (p.exitValue() != 0) {
                throw new IOException("chmod +x failed (exit " + p.exitValue() + "): " + binary);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("chmod +x interrupted: " + binary, e);
        }
    }

    /**
     * 清除 macOS quarantine；属性不存在时忽略，其它失败仅打日志（Gatekeeper 仍可能拦截）。
     */
    public static void clearMacQuarantine(Path binary) {
        if (binary == null || !Files.exists(binary) || !isMac()) return;
        try {
            Process p = new ProcessBuilder(
                    "xattr", "-dr", "com.apple.quarantine", binary.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                System.err.println("[NativeBinary] xattr quarantine 超时: " + binary);
                return;
            }
            int code = p.exitValue();
            // 0 = 成功；1 常为属性不存在
            if (code != 0 && code != 1) {
                System.err.println("[NativeBinary] xattr quarantine 失败 exit=" + code + ": " + binary);
            }
        } catch (Exception e) {
            System.err.println("[NativeBinary] xattr quarantine 异常: " + e.getMessage());
        }
    }

    /** makeExecutable + clearMacQuarantine 组合 */
    public static void prepareDownloadedBinary(Path binary) throws IOException {
        makeExecutable(binary);
        clearMacQuarantine(binary);
    }
}
