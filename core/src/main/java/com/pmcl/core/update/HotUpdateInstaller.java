package com.pmcl.core.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * 等当前进程退出后，把已校验的文件放进安装目录的 app，再重新打开 PMCL。
 * 覆盖前先把旧文件留在 ~/.pmcl/updates/backup，下次打开对不上时可以装回去。
 */
public final class HotUpdateInstaller {
    private HotUpdateInstaller() {}

    public static void launchAfterExit(Path home, Path appDir, Path stagingRoot,
                                       List<HotUpdateManifest.FileEntry> downloads,
                                       List<String> deletes, Path manifestFile,
                                       boolean backupFirst) throws IOException {
        if (appDir == null || !Files.isDirectory(appDir)) {
            throw new IOException("找不到安装目录里的 app");
        }
        List<String[]> copies = new ArrayList<>();
        for (HotUpdateManifest.FileEntry file : downloads) {
            Path src = stagingRoot.resolve(file.path()).normalize();
            if (!src.startsWith(stagingRoot.normalize()) || !Files.isRegularFile(src)) {
                throw new IOException("暂存文件缺失: " + file.path());
            }
            Path dest = AppResourceCheck.resolve(appDir, file.path());
            Path backup = AppResourceCheck.backupFile(home, file.path());
            copies.add(new String[] {src.toString(), dest.toString(), backup.toString()});
        }
        List<String> deleteDest = new ArrayList<>();
        if (deletes != null) {
            for (String relative : deletes) {
                deleteDest.add(AppResourceCheck.resolve(appDir, relative).toString());
            }
        }
        String manifestSrc = manifestFile == null ? "" : manifestFile.toAbsolutePath().normalize().toString();
        String manifestDest = manifestFile == null
                ? "" : AppResourceCheck.installedManifest(home).toAbsolutePath().normalize().toString();
        String restart = ProcessHandle.current().info().command().orElse("");
        long pid = ProcessHandle.current().pid();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path scriptDir = home.resolve("updates");
        Files.createDirectories(scriptDir);
        if (os.contains("win")) {
            Path script = Files.createTempFile(scriptDir, "pmcl-hot-", ".cmd");
            Files.writeString(script, renderWindows(pid, copies, deleteDest, manifestSrc, manifestDest,
                    restart, backupFirst), StandardCharsets.UTF_8);
            new ProcessBuilder("cmd.exe", "/c", script.toString())
                    .redirectInput(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } else {
            Path script = Files.createTempFile(scriptDir, "pmcl-hot-", ".sh");
            Files.writeString(script, renderUnix(pid, os.contains("mac"), copies, deleteDest,
                    manifestSrc, manifestDest, restart, backupFirst), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(script, EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            } catch (UnsupportedOperationException ignored) {
            }
            new ProcessBuilder("/bin/sh", script.toString())
                    .redirectInput(ProcessBuilder.Redirect.DISCARD)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        }
    }

    static String renderUnix(long pid, boolean mac, List<String[]> copies, List<String> deletes,
                             String manifestSrc, String manifestDest, String restart,
                             boolean backupFirst) {
        StringBuilder sh = new StringBuilder();
        sh.append("#!/bin/sh\nset -eu\n")
                .append("while kill -0 ").append(pid).append(" 2>/dev/null; do sleep 0.2; done\n");
        sh.append("copy_file() {\n")
                .append("  mkdir -p \"$(dirname \"$2\")\"\n");
        if (mac) {
            sh.append("  if ! /bin/cp -f \"$1\" \"$2\"; then\n")
                    .append("    /usr/bin/osascript - \"$1\" \"$2\" <<'APPLESCRIPT'\n")
                    .append("on run argv\n")
                    .append("  set src to item 1 of argv\n")
                    .append("  set dest to item 2 of argv\n")
                    .append("  do shell script \"d=$(dirname \" & quoted form of dest & \"); /bin/mkdir -p \\\"$d\\\" && /bin/cp -f \" & quoted form of src & \" \" & quoted form of dest with administrator privileges\n")
                    .append("end run\n")
                    .append("APPLESCRIPT\n")
                    .append("  fi\n");
        } else {
            sh.append("  /bin/cp -f \"$1\" \"$2\"\n");
        }
        sh.append("}\n");
        for (String[] copy : copies) {
            if (backupFirst) {
                sh.append("if [ -f ").append(shellQuote(copy[1])).append(" ]; then\n")
                        .append("  mkdir -p \"$(dirname ").append(shellQuote(copy[2])).append(")\"\n")
                        .append("  /bin/cp -f ").append(shellQuote(copy[1])).append(' ')
                        .append(shellQuote(copy[2])).append("\n")
                        .append("fi\n");
            }
            sh.append("copy_file ").append(shellQuote(copy[0])).append(' ')
                    .append(shellQuote(copy[1])).append("\n");
        }
        for (String dest : deletes) {
            sh.append("rm -f -- ").append(shellQuote(dest)).append("\n");
        }
        if (manifestSrc != null && !manifestSrc.isBlank()) {
            sh.append("mkdir -p \"$(dirname ").append(shellQuote(manifestDest)).append(")\"\n")
                    .append("/bin/cp -f ").append(shellQuote(manifestSrc)).append(' ')
                    .append(shellQuote(manifestDest)).append("\n");
        }
        if (restart != null && !restart.isBlank()) {
            sh.append(shellQuote(restart)).append(" >/dev/null 2>&1 &\n");
        }
        sh.append("rm -f -- \"$0\"\n");
        return sh.toString();
    }

    static String renderWindows(long pid, List<String[]> copies, List<String> deletes,
                                String manifestSrc, String manifestDest, String restart,
                                boolean backupFirst) throws IOException {
        StringBuilder cmd = new StringBuilder("@echo off\r\n");
        cmd.append(":wait\r\n")
                .append("tasklist /FI \"PID eq ").append(pid)
                .append("\" 2>NUL | find \"").append(pid).append("\" >NUL\r\n")
                .append("if not errorlevel 1 (timeout /t 1 /nobreak >NUL & goto wait)\r\n");
        for (String[] copy : copies) {
            String src = windowsQuote(copy[0]);
            String dest = windowsQuote(copy[1]);
            String backup = windowsQuote(copy[2]);
            if (backupFirst) {
                cmd.append("if exist ").append(dest).append(" (\r\n")
                        .append("  mkdir \"").append(parentWin(copy[2])).append("\" 2>NUL\r\n")
                        .append("  copy /Y ").append(dest).append(' ').append(backup).append(" >NUL\r\n")
                        .append(")\r\n");
            }
            cmd.append("mkdir \"").append(parentWin(copy[1])).append("\" 2>NUL\r\n")
                    .append("copy /Y ").append(src).append(' ').append(dest).append(" >NUL\r\n")
                    .append("if errorlevel 1 exit /b 1\r\n");
        }
        for (String dest : deletes) {
            cmd.append("del /Q ").append(windowsQuote(dest)).append("\r\n");
        }
        if (manifestSrc != null && !manifestSrc.isBlank()) {
            cmd.append("mkdir \"").append(parentWin(manifestDest)).append("\" 2>NUL\r\n")
                    .append("copy /Y ").append(windowsQuote(manifestSrc)).append(' ')
                    .append(windowsQuote(manifestDest)).append(" >NUL\r\n");
        }
        if (restart != null && !restart.isBlank()) {
            cmd.append("start \"\" ").append(windowsQuote(restart)).append("\r\n");
        }
        cmd.append("del /Q \"%~f0\"\r\n");
        return cmd.toString();
    }

    private static String parentWin(String path) throws IOException {
        int slash = Math.max(path.lastIndexOf('\\'), path.lastIndexOf('/'));
        if (slash <= 0) throw new IOException("更新路径无效");
        return path.substring(0, slash).replace("%", "%%").replace("\"", "");
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
