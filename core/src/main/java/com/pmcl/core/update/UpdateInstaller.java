package com.pmcl.core.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;

/**
 * 在当前启动器进程退出后安装已校验的更新资产，并尽可能重新启动 PMCL。
 * 安装脚本只接收 {@link SelfUpdater} 已完成 HTTPS、摘要与 Ed25519 校验的本地文件。
 */
public final class UpdateInstaller {

    private UpdateInstaller() {}

    /**
     * 创建并启动平台安装接管进程。调用成功后，宿主应尽快正常退出。
     */
    public static void launchAfterExit(Path downloaded, SelfUpdater.UpdateInfo info)
            throws IOException {
        if (downloaded == null || info == null || !Files.isRegularFile(downloaded)) {
            throw new IOException("更新安装包不存在");
        }
        Path packageFile = downloaded.toAbsolutePath().normalize();
        long pid = ProcessHandle.current().pid();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        if (os.contains("win")) {
            launchWindows(packageFile, info.getAssetKind(), pid);
        } else {
            launchUnix(packageFile, info.getAssetKind(), pid, os.contains("mac"));
        }
    }

    private static void launchUnix(Path packageFile, SelfUpdater.AssetKind kind, long pid,
                                   boolean mac) throws IOException {
        Path script = Files.createTempFile(packageFile.getParent(), "pmcl-install-", ".sh");
        String packageArg = shellQuote(packageFile.toString());
        String currentCommand = ProcessHandle.current().info().command().orElse("");
        StringBuilder sh = new StringBuilder();
        sh.append("#!/bin/sh\nset -eu\n")
                .append("while kill -0 ").append(pid)
                .append(" 2>/dev/null; do sleep 0.2; done\n");

        switch (kind) {
            case JAR -> appendUnixJarInstall(sh, packageFile, packageArg);
            case PKG -> {
                if (!mac) throw new IOException("当前系统不支持 .pkg 更新");
                sh.append("/usr/bin/osascript - ").append(packageArg).append(" <<'APPLESCRIPT'\n")
                        .append("on run argv\n")
                        .append("  do shell script \"/usr/sbin/installer -pkg \" & quoted form of (item 1 of argv) & \" -target /\" with administrator privileges\n")
                        .append("end run\n")
                        .append("APPLESCRIPT\n")
                        .append("rm -f -- ").append(packageArg).append("\n")
                        .append("open -a PMCL >/dev/null 2>&1 || true\n");
            }
            case DMG -> {
                if (!mac) throw new IOException("当前系统不支持 .dmg 更新");
                sh.append("MOUNT=\"$(/usr/bin/hdiutil attach -nobrowse -readonly ")
                        .append(packageArg).append(" | /usr/bin/awk 'END {print $NF}')\"\n")
                        .append("APP=\"$(/usr/bin/find \"$MOUNT\" -maxdepth 1 -name '*.app' -print -quit)\"\n")
                        .append("[ -n \"$APP\" ]\n")
                        .append("DEST=\"/Applications/$(basename \"$APP\")\"\n")
                        .append("/usr/bin/osascript - \"$APP\" \"$DEST\" <<'APPLESCRIPT'\n")
                        .append("on run argv\n")
                        .append("  do shell script \"/usr/bin/ditto \" & quoted form of (item 1 of argv) & \" \" & quoted form of (item 2 of argv) with administrator privileges\n")
                        .append("end run\n")
                        .append("APPLESCRIPT\n")
                        .append("/usr/bin/hdiutil detach \"$MOUNT\" >/dev/null\n")
                        .append("rm -f -- ").append(packageArg).append("\n")
                        .append("open \"$DEST\"\n");
            }
            case DEB -> {
                if (mac) throw new IOException("当前系统不支持 .deb 更新");
                sh.append("pkexec /usr/bin/dpkg -i ").append(packageArg).append("\n")
                        .append("rm -f -- ").append(packageArg).append("\n");
                appendRestartCommand(sh, currentCommand);
            }
            case RPM -> {
                if (mac) throw new IOException("当前系统不支持 .rpm 更新");
                sh.append("pkexec rpm -U --replacepkgs ").append(packageArg).append("\n")
                        .append("rm -f -- ").append(packageArg).append("\n");
                appendRestartCommand(sh, currentCommand);
            }
            case APPIMAGE -> {
                if (mac) throw new IOException("当前系统不支持 AppImage 更新");
                if (currentCommand.isBlank()) throw new IOException("无法定位当前 AppImage");
                sh.append("mv -f -- ").append(packageArg).append(' ')
                        .append(shellQuote(currentCommand)).append("\n")
                        .append("chmod +x -- ").append(shellQuote(currentCommand)).append("\n")
                        .append("exec ").append(shellQuote(currentCommand)).append(" >/dev/null 2>&1 &\n");
            }
            default -> throw new IOException("暂不支持自动安装更新资产: " + kind);
        }

        sh.append("rm -f -- \"$0\"\n");
        Files.writeString(script, sh.toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(script, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            // 非 POSIX 文件系统由 /bin/sh 显式执行，不依赖执行位。
        }
        new ProcessBuilder("/bin/sh", script.toString())
                .redirectInput(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void appendUnixJarInstall(StringBuilder sh, Path packageFile,
                                             String packageArg) throws IOException {
        Path currentJar = currentJarFromCommandLine()
                .orElseThrow(() -> new IOException(
                        "当前为原生安装版，但 Release 未提供对应系统安装包"));
        String current = shellQuote(currentJar.toString());
        String java = shellQuote(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        sh.append("mv -f -- ").append(packageArg).append(' ').append(current).append("\n")
                .append("exec ").append(java).append(" -jar ").append(current)
                .append(" >/dev/null 2>&1 &\n");
    }

    private static void appendRestartCommand(StringBuilder sh, String command) {
        if (command != null && !command.isBlank()) {
            sh.append(shellQuote(command)).append(" >/dev/null 2>&1 &\n");
        }
    }

    private static void launchWindows(Path packageFile, SelfUpdater.AssetKind kind, long pid)
            throws IOException {
        Path script = Files.createTempFile(packageFile.getParent(), "pmcl-install-", ".cmd");
        String pkg = windowsQuote(packageFile.toString());
        String currentCommand = ProcessHandle.current().info().command().orElse("");
        StringBuilder cmd = new StringBuilder("@echo off\r\n");
        cmd.append(":wait\r\n")
                .append("tasklist /FI \"PID eq ").append(pid)
                .append("\" 2>NUL | find \"").append(pid).append("\" >NUL\r\n")
                .append("if not errorlevel 1 (timeout /t 1 /nobreak >NUL & goto wait)\r\n");

        switch (kind) {
            case JAR -> {
                Path currentJar = currentJarFromCommandLine()
                        .orElseThrow(() -> new IOException(
                                "当前为原生安装版，但 Release 未提供 Windows 安装包"));
                String current = windowsQuote(currentJar.toString());
                Path javawPath = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe");
                String javaw = windowsQuote(Files.exists(javawPath)
                        ? javawPath.toString()
                        : Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString());
                cmd.append("move /Y ").append(pkg).append(' ').append(current).append(" >NUL\r\n")
                        .append("start \"\" ").append(javaw).append(" -jar ").append(current).append("\r\n");
            }
            case MSI -> {
                cmd.append("start /wait \"\" msiexec /i ").append(pkg)
                        .append(" /passive /norestart\r\n")
                        .append("del /Q ").append(pkg).append("\r\n");
                appendWindowsRestart(cmd, currentCommand);
            }
            case EXE -> {
                cmd.append("start /wait \"\" ").append(pkg).append(" /quiet\r\n")
                        .append("del /Q ").append(pkg).append("\r\n");
                appendWindowsRestart(cmd, currentCommand);
            }
            default -> throw new IOException("暂不支持自动安装 Windows 更新资产: " + kind);
        }
        cmd.append("del /Q \"%~f0\"\r\n");
        Files.writeString(script, cmd.toString(), StandardCharsets.UTF_8);
        new ProcessBuilder("cmd.exe", "/c", script.toString())
                .redirectInput(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void appendWindowsRestart(StringBuilder cmd, String command) throws IOException {
        if (command != null && !command.isBlank()) {
            cmd.append("start \"\" ").append(windowsQuote(command)).append("\r\n");
        }
    }

    private static Optional<Path> currentJarFromCommandLine() {
        String[] args = ProcessHandle.current().info().arguments().orElse(new String[0]);
        for (int i = 0; i + 1 < args.length; i++) {
            if ("-jar".equals(args[i])) {
                Path jar = Paths.get(args[i + 1]).toAbsolutePath().normalize();
                if (Files.isRegularFile(jar)) return Optional.of(jar);
            }
        }
        String command = System.getProperty("sun.java.command", "");
        if (!command.isBlank()) {
            String first = command.split("\\s+", 2)[0];
            if (first.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                Path jar = Paths.get(first).toAbsolutePath().normalize();
                if (Files.isRegularFile(jar)) return Optional.of(jar);
            }
        }
        return Optional.empty();
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static String windowsQuote(String value) throws IOException {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('"') >= 0) {
            throw new IOException("更新路径包含不安全字符");
        }
        return "\"" + value.replace("%", "%%") + "\"";
    }
}
