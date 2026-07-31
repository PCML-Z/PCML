package com.pmcl.core.multiplayer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ConnectX 客户端进程管理器。
 * <p>
 * ConnectX 是基于 ZeroTier SDK 的 P2P Minecraft 联机库（C# 项目）。
 * 本类管理 ConnectX.ClientConsole 进程的启动、命令交互（stdin/stdout REPL）、状态解析。
 * <p>
 * 工作目录：{@code ~/.pmcl/connectx/}，其中放置 appsettings.json 与日志。
 * 用户需自行提供 ConnectX.ClientConsole 二进制路径（通过 Preferences 配置）。
 * <p>
 * 交互协议：ClientConsole 启动后连接服务器，进入 {@code >:} REPL。
 * 发送命令：{@code room create --name X --max-user 10}、{@code room join --room_short_id XXX}、{@code room leave}
 * 输出格式（Serilog）：{@code [yy-MM-dd HH:mm:ss INF]: Room join result received, Info: ...}
 */
public final class ConnectXManager {

    /** 默认 ConnectX 服务器端口 */
    public static final int DEFAULT_PORT = 3535;

    /**
     * Expected SHA-256 of ConnectX.ClientConsole (lowercase hex).
     * Set via {@code -Dpmcl.connectx.sha256=<hex>} or a sidecar {@code <binary>.sha256} file.
     */
    public static final String SHA256_PROPERTY = "pmcl.connectx.sha256";

    /** 从日志中提取 IPv4 的正则 */
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})");
    /** 从日志中提取 RoomShortId 的正则 */
    private static final Pattern SHORT_ID_PATTERN =
            Pattern.compile("RoomShortId['\"]?\\s*[:=]\\s*['\"]?([A-Za-z0-9]{4,12})");
    /** 从日志中提取 GroupId（GUID 格式）的正则 */
    private static final Pattern GROUP_ID_PATTERN =
            Pattern.compile("GroupId['\"]?\\s*[:=]\\s*['\"]?([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    private final Path workDir;
    private volatile Process process;
    private volatile BufferedWriter stdin;
    private volatile Thread outputThread;
    private volatile String virtualIp = "";
    private volatile String roomShortId = "";
    private volatile String roomId = "";
    private volatile String lastError = "";
    private final AtomicBoolean serverConnected = new AtomicBoolean(false);

    public ConnectXManager() {
        this.workDir = Paths.get(System.getProperty("user.home"), ".pmcl", "connectx");
    }

    public Path getWorkDir() { return workDir; }
    public String getVirtualIp() { return virtualIp; }
    public String getRoomShortId() { return roomShortId; }
    public String getRoomId() { return roomId; }
    public String getLastError() { return lastError; }
    public boolean isServerConnected() { return serverConnected.get(); }

    /** 进程是否在运行 */
    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * 启动 ConnectX 客户端进程并连接到服务器。
     *
     * @param binaryPath  ConnectX.ClientConsole 二进制路径
     * @param serverAddr  ConnectX 服务器地址
     * @param serverPort  ConnectX 服务器端口
     * @param onOutput    输出回调（可空）
     * @return 连接成功后完成的 Future
     */
    public synchronized CompletableFuture<Void> start(String binaryPath, String serverAddr, int serverPort,
                                          Consumer<String> onOutput) {
        if (isRunning()) {
            return CompletableFuture.failedFuture(new IllegalStateException("ConnectX 已在运行"));
        }
        if (binaryPath == null || binaryPath.trim().isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("未配置 ConnectX 二进制路径"));
        }
        Path bin = Paths.get(binaryPath);
        if (!Files.exists(bin)) {
            return CompletableFuture.failedFuture(new IllegalStateException("ConnectX 二进制不存在：" + binaryPath));
        }
        try {
            com.pmcl.core.util.NativeBinaryPermissions.prepareDownloadedBinary(bin);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ConnectX 二进制权限加固失败：" + e.getMessage(), e));
        }
        try {
            verifyBinarySha256(bin);
        } catch (IOException e) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("ConnectX 二进制完整性校验失败：" + e.getMessage(), e));
        }

        return CompletableFuture.supplyAsync(() -> {
            synchronized (ConnectXManager.this) {
                if (process != null && process.isAlive()) {
                    throw new RuntimeException("ConnectX 已在运行");
                }
                try {
                    Files.createDirectories(workDir);
                    // 写入 appsettings.json
                    writeAppSettings(serverAddr, serverPort);

                    // 启动进程
                    ProcessBuilder pb = new ProcessBuilder(bin.toString())
                            .directory(workDir.toFile())
                            .redirectErrorStream(true);
                    process = pb.start();
                    stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

                    // 异步读取输出
                    final CompletableFuture<Void> connectedFuture = new CompletableFuture<>();
                    final Process procForThread = process;
                    outputThread = new Thread(() -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(procForThread.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (onOutput != null) {
                                    try { onOutput.accept(line); } catch (Throwable ignored) {}
                                }
                                parseOutputLine(line, connectedFuture);
                            }
                        } catch (IOException e) {
                            // 进程输出读取线程异常退出，记录但不中断
                            System.err.println("[ConnectX] Output reader error: " + e.getMessage());
                        }
                        // 进程结束
                        serverConnected.set(false);
                        if (!connectedFuture.isDone()) {
                            connectedFuture.completeExceptionally(
                                    new RuntimeException("ConnectX 进程已退出"));
                        }
                    }, "connectx-output");
                    outputThread.setDaemon(true);
                    outputThread.start();

                    // 等待服务器连接（最多 30 秒）——在锁外等待会阻塞其他 stop/start，故先释放锁再等
                } catch (Exception e) {
                    stop();
                    throw new RuntimeException("启动 ConnectX 失败：" + e.getMessage(), e);
                }
            }
            // 进程已启动：锁外轮询 serverConnected，避免长时间占锁阻塞 stop()
            long deadline = System.currentTimeMillis() + 30_000L;
            while (System.currentTimeMillis() < deadline) {
                if (serverConnected.get()) {
                    return (Void) null;
                }
                Process p = process;
                if (p == null || !p.isAlive()) {
                    stop();
                    throw new RuntimeException("ConnectX 进程已退出");
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    stop();
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("连接 ConnectX 服务器被中断");
                }
            }
            stop();
            throw new RuntimeException("连接 ConnectX 服务器超时（30秒）");
        });
    }

    /**
     * 创建房间。
     *
     * @param roomName    房间名
     * @param maxUsers    最大人数
     * @param password    密码（可空）
     * @param useRelay    是否使用中继
     * @return 完成时表示命令已发送（房间信息通过 parseOutputLine 异步解析）
     */
    public CompletableFuture<Void> createRoom(String roomName, int maxUsers, String password, boolean useRelay) {
        if (!isRunning() || !serverConnected.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("未连接到 ConnectX 服务器"));
        }
        // H10: roomName/password 未转义双引号会导致命令注入
        // 例如 roomName 含 " 会提前结束引号，后续内容被解释为命令参数
        StringBuilder cmd = new StringBuilder("room create --name \"");
        cmd.append(shellEscape(roomName)).append("\" --max-user ").append(maxUsers);
        if (password != null && !password.isEmpty()) {
            cmd.append(" --password \"").append(shellEscape(password)).append("\"");
        }
        if (useRelay) {
            cmd.append(" --relay");
        }
        return sendCommand(cmd.toString());
    }

    /**
     * 通过短 ID 加入房间。
     */
    public CompletableFuture<Void> joinRoom(String shortId, String password) {
        if (!isRunning() || !serverConnected.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("未连接到 ConnectX 服务器"));
        }
        // H10: shortId/password 转义，防止命令注入
        StringBuilder cmd = new StringBuilder("room join --room_short_id ");
        cmd.append(shellEscape(shortId));
        if (password != null && !password.isEmpty()) {
            cmd.append(" --password \"").append(shellEscape(password)).append("\"");
        }
        return sendCommand(cmd.toString());
    }

    /**
     * H10: Shell 双引号转义。
     * 在双引号字符串内，需要转义的字符：$ ` " \ 和换行。
     * 转义方式：在字符前加反斜杠。
     */
    private static String shellEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '$':  sb.append("\\$");  break;
                case '`':  sb.append("\\`");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 离开房间 */
    public CompletableFuture<Void> leaveRoom() {
        if (!isRunning()) {
            return CompletableFuture.completedFuture(null);
        }
        return sendCommand("room leave");
    }

    /** 停止进程 */
    public synchronized void stop() {
        if (stdin != null) {
            try { stdin.close(); } catch (IOException ignored) {}
        }
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(2000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        process = null;
        stdin = null;
        if (outputThread != null) {
            outputThread.interrupt();
            outputThread = null;
        }
        serverConnected.set(false);
        virtualIp = "";
        roomShortId = "";
        roomId = "";
    }

    // ============ 内部 ============

    /**
     * Verify binary SHA-256 before execute.
     * Expected digest from {@link #SHA256_PROPERTY} or {@code <path>.sha256} sidecar
     * (first whitespace-delimited token, lowercase hex). If neither is set, pin the hash
     * under {@code ~/.pmcl/connectx/} and refuse silent replacement on later starts.
     */
    void verifyBinarySha256(Path bin) throws IOException {
        String expected = System.getProperty(SHA256_PROPERTY);
        if (expected == null || expected.isBlank()) {
            Path sidecar = Paths.get(bin.toString() + ".sha256");
            if (Files.isRegularFile(sidecar)) {
                String line = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
                if (!line.isEmpty()) {
                    expected = line.split("\\s+")[0];
                }
            }
        }
        String actual = sha256Hex(bin);
        if (expected != null && !expected.isBlank()) {
            expected = expected.trim().toLowerCase(Locale.ROOT);
            if (expected.startsWith("sha256:")) {
                expected = expected.substring("sha256:".length());
            }
            if (!expected.equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256 mismatch: expected " + expected + ", got " + actual);
            }
            return;
        }
        // No external expected hash: pin this path's digest so later swaps are detected
        Files.createDirectories(workDir);
        String pathKey = sha256HexOfBytes(bin.toAbsolutePath().normalize().toString()
                .getBytes(StandardCharsets.UTF_8));
        Path pin = workDir.resolve("integrity-" + pathKey + ".sha256");
        if (Files.isRegularFile(pin)) {
            String pinned = Files.readString(pin, StandardCharsets.UTF_8).trim().split("\\s+")[0];
            if (!pinned.equalsIgnoreCase(actual)) {
                throw new IOException(
                        "ConnectX binary SHA-256 changed since last run (pinned " + pinned
                                + ", now " + actual
                                + "). Replace rejected; delete " + pin.getFileName()
                                + " or set -D" + SHA256_PROPERTY + "=<hex> to re-pin.");
            }
        } else {
            Files.writeString(pin, actual + "\n", StandardCharsets.UTF_8);
        }
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return toHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String sha256HexOfBytes(byte[] data) throws IOException {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static String toHex(byte[] dig) {
        StringBuilder sb = new StringBuilder(dig.length * 2);
        for (byte b : dig) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /** 发送命令到 REPL stdin */
    private CompletableFuture<Void> sendCommand(String cmd) {
        return CompletableFuture.runAsync(() -> {
            try {
                synchronized (this) {
                    BufferedWriter w = stdin;
                    if (w == null) throw new IllegalStateException("stdin 不可用");
                    w.write(cmd);
                    w.write("\n");
                    w.flush();
                }
            } catch (IOException e) {
                throw new RuntimeException("发送命令失败：" + e.getMessage(), e);
            }
        });
    }

    /** 写入 appsettings.json */
    private void writeAppSettings(String serverAddr, int serverPort) throws IOException {
        // 用 Gson 构造 JSON，确保所有特殊字符（\n \r \t " \）正确转义
        com.google.gson.Gson gson = new com.google.gson.Gson();
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> serilog = new java.util.LinkedHashMap<>();
        serilog.put("Using", java.util.List.of("Serilog.Sinks.File", "Serilog.Sinks.Console"));
        java.util.Map<String, Object> writeTo = new java.util.LinkedHashMap<>();
        writeTo.put("Name", "Console");
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("outputTemplate", "[{Timestamp:yy-MM-dd HH:mm:ss} {Level:u3}]: {Message:lj}{NewLine}{Exception}");
        writeTo.put("Args", args);
        serilog.put("WriteTo", java.util.List.of(writeTo));
        java.util.Map<String, Object> minLevel = new java.util.LinkedHashMap<>();
        minLevel.put("Default", "Information");
        minLevel.put("Override", java.util.Map.of("Microsoft", "Warning", "System", "Warning"));
        serilog.put("MinimumLevel", minLevel);
        root.put("Serilog", serilog);
        java.util.Map<String, Object> server = new java.util.LinkedHashMap<>();
        server.put("ListenPort", serverPort);
        server.put("ListenAddress", serverAddr);
        root.put("Server", server);
        String json = gson.toJson(root);
        Files.write(workDir.resolve("appsettings.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    /** 解析输出行，提取连接状态、虚拟 IP、房间信息 */
    private void parseOutputLine(String line, CompletableFuture<Void> connectedFuture) {
        if (line == null || line.isEmpty()) return;
        String lower = line.toLowerCase();

        // 服务器连接成功
        if (!serverConnected.get() && (lower.contains("connected") && lower.contains("server")
                || lower.contains("signed in") || lower.contains("isconnected: true"))) {
            serverConnected.set(true);
            if (!connectedFuture.isDone()) {
                connectedFuture.complete(null);
            }
        }

        // 房间创建/加入成功
        if (lower.contains("room") && (lower.contains("create") || lower.contains("join"))
                && lower.contains("result") && lower.contains("succeeded")) {
            // 提取 RoomShortId
            Matcher sidM = SHORT_ID_PATTERN.matcher(line);
            if (sidM.find()) roomShortId = sidM.group(1);
            // 提取 GroupId
            Matcher gidM = GROUP_ID_PATTERN.matcher(line);
            if (gidM.find()) roomId = gidM.group(1);
        }

        // 提取虚拟 IP（ZeroTier 连接建立后会输出 IP）
        Matcher ipM = IPV4_PATTERN.matcher(line);
        if (ipM.find() && (lower.contains("ip") || lower.contains("address")
                || lower.contains("zt") || lower.contains("virtual") || lower.contains("local"))) {
            String ip = ipM.group(1);
            // 过滤掉 127.x、0.0.0.0、255.x
            if (!ip.startsWith("127.") && !ip.equals("0.0.0.0") && !ip.startsWith("255.")) {
                virtualIp = ip;
            }
        }

        // 错误信息
        if (lower.contains("error") || lower.contains("failed") || lower.contains("exception")) {
            lastError = line;
        }
    }
}
