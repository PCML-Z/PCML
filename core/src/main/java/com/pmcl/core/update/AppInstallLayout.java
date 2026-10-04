package com.pmcl.core.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Compose 打包后的安装目录。热更新只动 {@code app/}，不动自带运行时。
 * macOS 在 {@code Contents/app}，Windows 在安装目录下的 {@code app}，Linux 在 {@code lib/app}。
 */
public final class AppInstallLayout {
    private AppInstallLayout() {}

    public static Optional<Path> appDir() {
        String command = ProcessHandle.current().info().command().orElse("");
        if (command.isBlank()) return Optional.empty();
        Path exe;
        try {
            exe = Path.of(command).toAbsolutePath().normalize();
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(exe)) return Optional.empty();
        Path parent = exe.getParent();
        if (parent == null) return Optional.empty();
        if ("MacOS".equals(String.valueOf(parent.getFileName()))) {
            Path contents = parent.getParent();
            if (contents != null && Files.isDirectory(contents.resolve("app"))) {
                return Optional.of(contents.resolve("app"));
            }
        }
        Path sibling = parent.resolve("app");
        if (Files.isDirectory(sibling)) return Optional.of(sibling);
        Path install = parent.getParent();
        if (install != null && "bin".equals(String.valueOf(parent.getFileName()))) {
            Path linuxApp = install.resolve("lib").resolve("app");
            if (Files.isDirectory(linuxApp)) return Optional.of(linuxApp);
        }
        return Optional.empty();
    }
}
