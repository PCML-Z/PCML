package com.pmcl.core.ecxp;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 找本机的 dotnet 主机，并列出装了哪些 Microsoft.NETCore.App 运行时。
 * 从 macOS 访达启动时 PATH 里通常没有 dotnet，所以还要查常见安装位置。
 */
public final class DotnetRuntime {

    private static final Pattern RUNTIME = Pattern.compile("^Microsoft\\.NETCore\\.App (\\d+)\\.(\\d+)\\.(\\S+)");

    private final Path host;
    private final List<String> versions;
    private final TreeSet<Integer> majors;

    private DotnetRuntime(Path host, List<String> versions, TreeSet<Integer> majors) {
        this.host = host;
        this.versions = List.copyOf(versions);
        this.majors = majors;
    }

    /** 没找到 dotnet 时为 null。 */
    public Path host() { return host; }

    /** 已装运行时的完整版本号，例如 10.0.7。 */
    public List<String> versions() { return versions; }

    public boolean found() { return host != null; }

    public boolean hasMajor(int major) { return majors.contains(major); }

    public int highestMajor() { return majors.isEmpty() ? 0 : majors.last(); }

    /** dotnet 所在目录，给子进程的 DOTNET_ROOT 用。 */
    public Path root() {
        if (host == null) return null;
        try {
            return host.toRealPath().getParent();
        } catch (IOException e) {
            return host.getParent();
        }
    }

    public static DotnetRuntime probe() {
        for (Path candidate : candidates()) {
            if (!Files.isRegularFile(candidate) || !Files.isExecutable(candidate)) continue;
            List<String> lines = listRuntimes(candidate);
            if (lines == null) continue;
            List<String> versions = new ArrayList<>();
            TreeSet<Integer> majors = new TreeSet<>();
            for (String line : lines) {
                Matcher m = RUNTIME.matcher(line.strip());
                if (!m.find()) continue;
                versions.add(m.group(1) + "." + m.group(2) + "." + m.group(3));
                majors.add(Integer.parseInt(m.group(1)));
            }
            return new DotnetRuntime(candidate.toAbsolutePath().normalize(), versions, majors);
        }
        return new DotnetRuntime(null, List.of(), new TreeSet<>());
    }

    private static List<Path> candidates() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exe = windows ? "dotnet.exe" : "dotnet";
        List<Path> out = new ArrayList<>();
        String root = System.getenv("DOTNET_ROOT");
        if (root != null && !root.isBlank()) out.add(Path.of(root, exe));
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (!dir.isBlank()) out.add(Path.of(dir, exe));
            }
        }
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) out.add(Path.of(home, ".dotnet", exe));
        if (windows) {
            String pf = System.getenv("ProgramFiles");
            out.add(Path.of(pf == null || pf.isBlank() ? "C:\\Program Files" : pf, "dotnet", exe));
        } else {
            out.add(Path.of("/usr/local/share/dotnet/dotnet"));
            out.add(Path.of("/opt/homebrew/bin/dotnet"));
            out.add(Path.of("/usr/local/bin/dotnet"));
            out.add(Path.of("/usr/share/dotnet/dotnet"));
            out.add(Path.of("/usr/lib/dotnet/dotnet"));
            out.add(Path.of("/snap/bin/dotnet"));
        }
        return out;
    }

    private static List<String> listRuntimes(Path host) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(host.toString(), "--list-runtimes");
            builder.redirectErrorStream(true);
            builder.environment().put("DOTNET_CLI_TELEMETRY_OPTOUT", "1");
            builder.environment().put("DOTNET_NOLOGO", "1");
            process = builder.start();
            byte[] bytes;
            try (InputStream in = process.getInputStream()) {
                bytes = in.readNBytes(64 * 1024);
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) return null;
            if (process.exitValue() != 0) return null;
            return new String(bytes, StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
