package com.pmcl.core.launch;

import com.pmcl.core.LauncherConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * macOS 14 起，Game Mode 只认带 {@code LSSupportsGameMode} 的游戏应用。
 * 直接拉起 java 不会进入 Game Mode。这里在 {@code ~/.pmcl/game-mode} 放一个
 * 声明了该键的 .app，由它的可执行文件先向 Launch Services 报到，再 exec 成 java。
 * 进程号不变，所以日志管道、工作目录、环境变量和退出码都还是游戏进程自己的。
 * 系统只在全屏时打开 Game Mode，窗口化通常不会。
 */
public final class MacGameMode {
    static final String EXECUTABLE = "pmcl-game-mode-launch";
    private static final String RESOURCE = "/launch/" + EXECUTABLE;

    private MacGameMode() {}

    public static boolean supported() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("mac");
    }

    public static List<String> wrap(List<String> command) throws IOException {
        return wrap(command, LauncherConfig.pmclHome());
    }

    static List<String> wrap(List<String> command, Path home) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IOException("empty command");
        }
        Path exe = install(home);
        List<String> wrapped = new ArrayList<>(command.size() + 1);
        wrapped.add(exe.toString());
        wrapped.addAll(command);
        return wrapped;
    }

    static String infoPlist() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0">
                <dict>
                  <key>CFBundleExecutable</key>
                  <string>pmcl-game-mode-launch</string>
                  <key>CFBundleIdentifier</key>
                  <string>com.pmcl.game</string>
                  <key>CFBundleName</key>
                  <string>Minecraft</string>
                  <key>CFBundleDisplayName</key>
                  <string>Minecraft</string>
                  <key>CFBundlePackageType</key>
                  <string>APPL</string>
                  <key>CFBundleVersion</key>
                  <string>1</string>
                  <key>CFBundleShortVersionString</key>
                  <string>1</string>
                  <key>NSPrincipalClass</key>
                  <string>NSApplication</string>
                  <key>NSHighResolutionCapable</key>
                  <true/>
                  <key>LSApplicationCategoryType</key>
                  <string>public.app-category.games</string>
                  <key>LSSupportsGameMode</key>
                  <true/>
                  <key>GCSupportsControllerUserInteraction</key>
                  <true/>
                </dict>
                </plist>
                """;
    }

    private static synchronized Path install(Path home) throws IOException {
        Path macos = home.resolve("game-mode").resolve("PMCLGame.app").resolve("Contents").resolve("MacOS");
        Files.createDirectories(macos);
        Path contents = macos.getParent();
        Path plist = contents.resolve("Info.plist");
        String xml = infoPlist();
        if (!Files.exists(plist) || !xml.equals(Files.readString(plist))) {
            Files.writeString(plist, xml);
        }
        byte[] bytes;
        try (InputStream in = MacGameMode.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("missing " + RESOURCE);
            }
            bytes = in.readAllBytes();
        }
        String digest = sha256(bytes);
        Path exe = macos.resolve(EXECUTABLE);
        Path stamp = macos.resolve(EXECUTABLE + ".sha256");
        boolean current = Files.exists(exe)
                && Files.exists(stamp)
                && digest.equals(Files.readString(stamp).trim());
        if (!current) {
            Path tmp = macos.resolve(EXECUTABLE + ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, exe, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!exe.toFile().setExecutable(true, false)) {
                throw new IOException("cannot mark executable: " + exe);
            }
            Files.writeString(stamp, digest + "\n");
        }
        return exe;
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
