package com.pmcl.core.multiplayer;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Terracotta 陶瓦联机管理器。
 * <p>
 * 通过下载并启动 Terracotta 官方二进制，以本地 HTTP API 控制联机。
 * 这是 HMCL 同款接入方式，兼容官方房间码格式 U/XXXX-XXXX-XXXX-XXXX。
 * <p>
 * 流程：
 * <ol>
 *   <li>下载对应平台的 terracotta 二进制包（tar.gz）</li>
 *   <li>以 --hmcl &lt;tempfile&gt; 参数启动，Terracotta 会把 HTTP 端口写入该文件</li>
 *   <li>轮询 http://127.0.0.1:&lt;port&gt;/state 获取状态机</li>
 *   <li>调用 /state/scanning 创建房间，/state/guesting 加入房间</li>
 * </ol>
 *
 * @see <a href="https://github.com/burningtnt/Terracotta">Terracotta 项目</a>
 */
public final class TerracottaManager {

    /** 当前 Terracotta 版本 */
    private static final String VERSION = "0.4.2";

    /** 各平台 asset 文件名 + SHA256（从 GitHub Release 获取） */
    private static final List<AssetSpec> ASSETS = Arrays.asList(
        new AssetSpec("windows-x86_64", "terracotta-" + VERSION + "-windows-x86_64-pkg.tar.gz",
                "07ebe139e3ca5f74576e58b1a96efe59abdfbe148d3f1a49bfdca8b6f70745f0"),
        new AssetSpec("windows-arm64", "terracotta-" + VERSION + "-windows-arm64-pkg.tar.gz",
                "acfab0a87a02dedc6dab7c05303186c8907f56f815548b693fb3324358da7d14"),
        new AssetSpec("macos-arm64", "terracotta-" + VERSION + "-macos-arm64-pkg.tar.gz",
                "13de7f9ce8733971b23493fabbe7e16d480f1e0d16a6265b4861f5a01bbecb60"),
        new AssetSpec("macos-x86_64", "terracotta-" + VERSION + "-macos-x86_64-pkg.tar.gz",
                "16306157d89423ce79fa901cdb75a6386ec1a9b1bd43a5d47c2c47cf01a16b86"),
        new AssetSpec("linux-x86_64", "terracotta-" + VERSION + "-linux-x86_64-pkg.tar.gz",
                "675c4fd6c74d49ed8165151ba2be5b6582e0af20fb6d912074543c2484b1e10a"),
        new AssetSpec("linux-arm64", "terracotta-" + VERSION + "-linux-arm64-pkg.tar.gz",
                "845285ff264ac5fbc16db1a1605ad190e7fa64196516068cc309de5a1d2bf66d")
    );

    /** 下载镜像（按顺序尝试，Gitee 国内首选） */
    private static final List<String> MIRROR_TEMPLATES = Arrays.asList(
            // Gitee 官方镜像（国内稳定，burningtnt 已同步镜像）
            "https://gitee.com/burningtnt/Terracotta/releases/download/v" + VERSION + "/%s",
            // GitHub 直连（海外用户）
            "https://github.com/burningtnt/Terracotta/releases/download/v" + VERSION + "/%s",
            // GitHub 代理镜像（备用）
            "https://ghproxy.net/https://github.com/burningtnt/Terracotta/releases/download/v" + VERSION + "/%s",
            "https://ghfast.top/https://github.com/burningtnt/Terracotta/releases/download/v" + VERSION + "/%s"
    );

    /** EasyTier 公共中继节点列表 URL（HMCL 同款） */
    private static final String NODE_LIST_URL = "https://terracotta.glavo.site/nodes";

    /** 从端口文件 JSON 中解析 HTTP 端口，如 "port": 12345 */
    private static final Pattern PORT_PATTERN = Pattern.compile("\"port\"\\s*:\\s*(\\d+)");

    /** 从日志文件中解析 HTTP 端口，如 http://127.0.0.1:12345 */
    private static final Pattern LOG_URL_PATTERN = Pattern.compile("http://127\\.0\\.0\\.1:(\\d+)");

    private final Path binaryDir;
    private final Path binaryPath;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private volatile Process process;
    /** Published port: authenticated proxy (never Terracotta's raw port). */
    private volatile int httpPort = 0;
    /** Upstream Terracotta daemon port (not exposed). */
    private volatile int backendPort = 0;
    private volatile TerracottaAuthProxy authProxy;
    private volatile String apiToken = "";
    private volatile Thread outputThread;
    private volatile String lastStateJson = "";
    private volatile long lastIndex = -1;

    public TerracottaManager() {
        this.binaryDir = Paths.get(System.getProperty("user.home"), ".pmcl", "terracotta");
        this.binaryPath = binaryDir.resolve(isWindows() ? "terracotta.exe" : "terracotta");
    }

    public Path getBinaryPath() { return binaryPath; }

    public boolean isBinaryReady() {
        return Files.exists(binaryPath) && Files.isExecutable(binaryPath);
    }

    public boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * Whether the authenticated HTTP API is reachable.
     * On macOS, {@code --hmcl} secondary mode often exits after writing the port file,
     * so {@link #isRunning()} is false even though the daemon + proxy are usable.
     */
    public boolean isApiReady() {
        if (httpPort <= 0 || apiToken == null || apiToken.isEmpty()) return false;
        try {
            queryState();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public String getLastStateJson() { return lastStateJson; }

    // ============ 二进制下载 ============

    /**
     * 下载并解压 Terracotta 二进制（若尚未就绪）。
     */
    public CompletableFuture<Void> ensureBinary(Consumer<String> progress) {
        if (isBinaryReady()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                Files.createDirectories(binaryDir);
                AssetSpec asset = matchAsset();
                if (asset == null) {
                    throw new RuntimeException("Terracotta 尚未支持当前平台");
                }
                Path tarball = binaryDir.resolve(asset.filename);

                if (progress != null) progress.accept("正在下载 Terracotta v" + VERSION + "…");
                downloadFile(asset.filename, tarball, progress);

                // SHA256 校验
                if (progress != null) progress.accept("校验完整性…");
                String actualSha = sha256(tarball);
                if (!actualSha.equalsIgnoreCase(asset.sha256)) {
                    Files.deleteIfExists(tarball);
                    throw new RuntimeException("SHA256 校验失败：期望 " + asset.sha256 + "，实际 " + actualSha);
                }

                if (progress != null) progress.accept("正在解压…");
                extractTerracotta(tarball, binaryPath);

                if (!isWindows()) {
                    com.pmcl.core.util.NativeBinaryPermissions.prepareDownloadedBinary(binaryPath);
                }
                Files.deleteIfExists(tarball);
                if (progress != null) progress.accept("Terracotta 就绪");
                return (Void) null;
            } catch (Exception e) {
                throw new RuntimeException("下载 Terracotta 失败：" + e.getMessage(), e);
            }
        });
    }

    /** 按当前平台匹配 asset */
    private AssetSpec matchAsset() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform;
        if (os.contains("win")) {
            platform = (arch.contains("aarch64") || arch.contains("arm64")) ? "windows-arm64" : "windows-x86_64";
        } else if (os.contains("mac")) {
            platform = (arch.contains("aarch64") || arch.contains("arm64")) ? "macos-arm64" : "macos-x86_64";
        } else {
            platform = (arch.contains("aarch64") || arch.contains("arm64")) ? "linux-arm64" : "linux-x86_64";
        }
        for (AssetSpec a : ASSETS) {
            if (a.platform.equals(platform)) return a;
        }
        return null;
    }

    // ============ 进程启动 ============

    /**
     * 启动 Terracotta 进程。
     * <p>
     * 采用两阶段策略：
     * <ol>
     *   <li>先尝试 --hmcl 模式：若已有 daemon 在运行，则直接连接（secondary mode），
     *       从端口文件读取 HTTP 端口</li>
     *   <li>若 --hmcl 失败（无 daemon 运行，macOS 上 launchctl 受 SIP 限制失败），
     *       则手动启动 --daemon，从日志文件解析 HTTP 端口</li>
     * </ol>
     * 这样切换房主/房客模式时，已运行的 daemon 会被复用，不会因重复启动而退出。
     */
    public CompletableFuture<Void> start(Consumer<String> progress) {
        return requestLocalNetworkAccess(progress).thenCompose(v -> startAfterPermission(progress));
    }

    private CompletableFuture<Void> startAfterPermission(Consumer<String> progress) {
        if (isApiReady()) return CompletableFuture.completedFuture(null);
        return ensureBinary(progress).thenApplyAsync(v -> {
            // Another create/join may have finished starting while we waited on ensureBinary.
            if (isApiReady()) return (Void) null;
            try {
                // ===== 阶段 1：尝试 --hmcl 模式（连接已有 daemon） =====
                if (progress != null) progress.accept("正在连接 Terracotta…");
                Path portFile = Files.createTempFile("pmcl-terracotta-", ".port");
                Files.deleteIfExists(portFile);

                Process hmclProc = new ProcessBuilder(
                        binaryPath.toString(), "--hmcl", portFile.toString()
                ).redirectErrorStream(true).start();

                // 读取 --hmcl 输出（诊断用）
                java.util.List<String> hmclOutput = new java.util.concurrent.CopyOnWriteArrayList<>();
                Thread hmclReader = new Thread(() -> {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(hmclProc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            hmclOutput.add(line);
                            if (progress != null) {
                                try { progress.accept(line); } catch (Throwable ignored) {}
                            }
                        }
                    } catch (IOException ignored) {}
                }, "terracotta-hmcl");
                hmclReader.setDaemon(true);
                hmclReader.start();

                // 等待端口文件出现（最多 5 秒）
                long deadline = System.currentTimeMillis() + 5000;
                while (System.currentTimeMillis() < deadline) {
                    if (Files.exists(portFile)) {
                        String content = Files.readString(portFile, StandardCharsets.UTF_8).trim();
                        java.util.regex.Matcher m = PORT_PATTERN.matcher(content);
                        if (m.find()) {
                            int rawPort = Integer.parseInt(m.group(1));
                            process = hmclProc; // --hmcl 进程可能已退出（secondary mode 正常行为）
                            Files.deleteIfExists(portFile);
                            installAuthProxy(rawPort);
                            if (progress != null) progress.accept("已连接 Terracotta daemon（经认证代理 :" + httpPort + ")");
                            return (Void) null;
                        }
                    }
                    // --hmcl 进程退出且端口文件未出现 → daemon 未运行，需手动启动
                    if (!hmclProc.isAlive() && !Files.exists(portFile)) {
                        break;
                    }
                    Thread.sleep(100);
                }

                // --hmcl 超时或失败，清理
                if (hmclProc.isAlive()) hmclProc.destroyForcibly();
                Files.deleteIfExists(portFile);

                // ===== 阶段 2：手动启动 --daemon =====
                if (progress != null) progress.accept("正在启动 Terracotta 守护进程…");

                ProcessBuilder pb = new ProcessBuilder(
                        binaryPath.toString(), "--daemon"
                ).redirectErrorStream(true);
                process = pb.start();

                // 异步读取进程输出，同时解析日志文件路径
                java.util.concurrent.atomic.AtomicReference<String> logPathRef =
                        new java.util.concurrent.atomic.AtomicReference<>(null);
                final Process procForThread = process;
                outputThread = new Thread(() -> {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(procForThread.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (progress != null) {
                                try { progress.accept(line); } catch (Throwable ignored) {}
                            }
                            if (logPathRef.get() == null) {
                                int idx = line.indexOf("Logs will be saved to ");
                                if (idx >= 0) {
                                    logPathRef.set(line.substring(
                                            idx + "Logs will be saved to ".length()).trim());
                                }
                            }
                        }
                    } catch (IOException ignored) {}
                }, "terracotta-output");
                outputThread.setDaemon(true);
                outputThread.start();

                // 等待日志文件路径出现（最多 5 秒）
                deadline = System.currentTimeMillis() + 5000;
                while (logPathRef.get() == null && System.currentTimeMillis() < deadline) {
                    if (!process.isAlive()) {
                        throw new IOException("Terracotta daemon 进程异常退出");
                    }
                    Thread.sleep(100);
                }

                String logFilePath = logPathRef.get();
                if (logFilePath == null) {
                    throw new IOException("无法获取 Terracotta 日志文件路径");
                }
                if (progress != null) progress.accept("日志文件：" + logFilePath);

                // 轮询日志文件，解析 HTTP 端口
                // 仅读取新增字节，避免每次迭代都重读整个日志文件
                Path logPath = Paths.get(logFilePath);
                long lastLogPos = 0;
                deadline = System.currentTimeMillis() + 20000;
                while (System.currentTimeMillis() < deadline) {
                    if (!process.isAlive()) {
                        throw new IOException("Terracotta daemon 进程异常退出");
                    }
                    if (Files.exists(logPath)) {
                        long fileLen = Files.size(logPath);
                        // 文件被截断/轮转 → 从头读
                        if (fileLen < lastLogPos) {
                            lastLogPos = 0;
                        }
                        if (fileLen > lastLogPos) {
                            try (RandomAccessFile raf = new RandomAccessFile(logPath.toFile(), "r")) {
                                raf.seek(lastLogPos);
                                byte[] newBytes = new byte[(int) (fileLen - lastLogPos)];
                                raf.readFully(newBytes);
                                lastLogPos = fileLen;
                                String newContent = new String(newBytes, StandardCharsets.UTF_8);
                                java.util.regex.Matcher m = LOG_URL_PATTERN.matcher(newContent);
                                if (m.find()) {
                                    int rawPort = Integer.parseInt(m.group(1));
                                    installAuthProxy(rawPort);
                                    if (progress != null) progress.accept("Terracotta 已启动（经认证代理 :" + httpPort + ")");
                                    return (Void) null;
                                }
                            }
                        }
                    }
                    Thread.sleep(200);
                }
                throw new IOException("等待 Terracotta HTTP 端口超时");
            } catch (Exception e) {
                throw new RuntimeException("启动 Terracotta 失败：" + e.getMessage(), e);
            }
        });
    }

    /**
     * macOS 只有在应用实际访问局域网时才显示本地网络授权框。加入 Minecraft
     * 使用的组播发现地址可触发该授权，同时提前发现已拒绝权限的情况。
     */
    private CompletableFuture<Void> requestLocalNetworkAccess(Consumer<String> progress) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("mac")) return CompletableFuture.completedFuture(null);

        return CompletableFuture.runAsync(() -> {
            if (progress != null) progress.accept("正在申请 macOS 本地网络权限…");
            InetAddress group;
            try {
                group = InetAddress.getByName("224.0.2.60");
            } catch (IOException e) {
                throw new RuntimeException("无法初始化 Minecraft 局域网发现地址", e);
            }

            try (MulticastSocket socket = new MulticastSocket(null)) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(0));
                socket.setSoTimeout(300);
                socket.joinGroup(group);
                try {
                    byte[] buffer = new byte[512];
                    socket.receive(new java.net.DatagramPacket(buffer, buffer.length));
                } catch (SocketTimeoutException ignored) {
                    // 没有正在广播的世界是正常情况；访问组播本身已触发权限检查。
                } finally {
                    try { socket.leaveGroup(group); } catch (IOException ignored) {}
                }
                if (progress != null) progress.accept("本地网络权限检查完成");
            } catch (IOException | SecurityException e) {
                throw new RuntimeException(
                        "无法访问本地网络。请在 macOS「系统设置 → 隐私与安全性 → 本地网络」"
                                + "中允许 PMCL，然后重新创建房间。", e);
            }
        });
    }

    /** 停止 Terracotta 进程 */
    public synchronized void stop() {
        // 先尝试 HTTP /panic?peaceful=true 优雅关闭（经认证代理）
        if (httpPort > 0 && apiToken != null && !apiToken.isEmpty()) {
            try {
                Request req = authedGet("/panic?peaceful=true");
                try (Response resp = http.newCall(req).execute()) {
                    // 忽略响应
                }
            } catch (IOException ignored) {}
        }
        if (authProxy != null) {
            try { authProxy.close(); } catch (Exception ignored) {}
            authProxy = null;
        }
        // 等待 1 秒后强杀
        try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
            try {
                if (!p.waitFor(2000, TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        process = null;
        httpPort = 0;
        backendPort = 0;
        apiToken = "";
        if (outputThread != null) {
            outputThread.interrupt();
            outputThread = null;
        }
    }

    /** Build authenticated GET against the local auth proxy (C8). */
    private Request authedGet(String pathAndQuery) {
        String path = pathAndQuery.startsWith("/") ? pathAndQuery : "/" + pathAndQuery;
        return new Request.Builder()
                .url("http://127.0.0.1:" + httpPort + path)
                .header(TerracottaAuthProxy.HEADER, "Bearer " + apiToken)
                .get()
                .build();
    }

    /**
     * Publish Terracotta backend behind an authenticated loopback proxy.
     * {@link #httpPort} becomes the proxy port; backend port stays private.
     */
    private void installAuthProxy(int terracottaPort) throws IOException {
        if (authProxy != null) {
            try { authProxy.close(); } catch (Exception ignored) {}
            authProxy = null;
        }
        this.backendPort = terracottaPort;
        // Dedicated client: never share dispatcher with callers of queryState/authedGet,
        // otherwise nested OkHttp execute (caller → proxy → backend) can stall.
        OkHttpClient proxyHttp = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
        TerracottaAuthProxy proxy = new TerracottaAuthProxy(terracottaPort, proxyHttp);
        proxy.start();
        this.authProxy = proxy;
        this.apiToken = proxy.getToken();
        this.httpPort = proxy.getListenPort();
        System.out.println("[TerracottaManager] Auth proxy on 127.0.0.1:" + httpPort
                + " → backend :" + backendPort + " (Bearer required)");
    }

    // ============ HTTP API ============

    /**
     * 查询当前状态。
     * GET /state → 返回完整状态 JSON
     */
    public String queryState() throws IOException {
        if (httpPort == 0) throw new IOException("Terracotta 未启动");
        Request req = authedGet("/state");
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            String body = resp.body() != null ? resp.body().string() : "{}";
            lastStateJson = body;
            // 解析 index 用于变化检测
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"index\"\\s*:\\s*(\\d+)").matcher(body);
            if (m.find()) lastIndex = Long.parseLong(m.group(1));
            return body;
        }
    }

    /**
     * 创建房间（房主）。
     * GET /state/scanning?player=&lt;name&gt;&public_nodes=&lt;node1&gt;&public_nodes=&lt;node2&gt;
     * @param playerName 玩家名
     * @param progress   进度回调
     * @return 房间码（U/XXXX-XXXX-XXXX-XXXX）
     */
    public CompletableFuture<String> createRoom(String playerName, Consumer<String> progress) {
        return createRoom(playerName, progress, 0);
    }

    public CompletableFuture<String> createRoom(String playerName, Consumer<String> progress,
                                                int localLanPortHint) {
        return start(progress).thenComposeAsync(v -> {
            try {
                String current = extractJsonField(queryState(), "state");
                // 已建房且网络完全就绪：直接复用，避免 toIdle 拆掉现有房间。
                // host-starting 仅表示已生成房间码，EasyTier 隧道尚未可用，必须继续等待。
                if ("host-ok".equals(current)) {
                    if (progress != null) progress.accept("复用已有房主状态：" + current);
                    return CompletableFuture.completedFuture(lastStateJson);
                }
                if ("host-starting".equals(current)) {
                    if (progress != null) {
                        progress.accept("房间码已生成，正在等待陶瓦网络完全就绪…");
                    }
                    return waitForHostState(progress, localLanPortHint);
                }
                // 仍在扫描：用户超时后开放了局域网再点「创建」→ 只续等，勿 toIdle
                if ("host-scanning".equals(current)) {
                    if (progress != null) {
                        progress.accept("继续扫描局域网世界…请确认 Minecraft 已「对局域网开放」");
                    }
                    return waitForHostState(progress, localLanPortHint);
                }

                // 先重置到 idle 并等待确认，避免 daemon 仍处于上一次的 host/guest 状态
                toIdle();
                // 不传 public_nodes：Terracotta 内部会自动添加 4 个默认中继节点
                // （含 etnode.zkitefly.eu.org，可正常连接）。PMCL 传反而会导致重复。
                okhttp3.HttpUrl.Builder ub = okhttp3.HttpUrl.parse(
                        "http://127.0.0.1:" + httpPort + "/state/scanning").newBuilder();
                ub.addQueryParameter("player", playerName);
                if (progress != null) progress.accept("正在创建房间…");
                Request req = new Request.Builder().url(ub.build())
                        .header(TerracottaAuthProxy.HEADER, "Bearer " + apiToken)
                        .get().build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        throw new IOException("HTTP " + resp.code() + ": " + body);
                    }
                }
                if (progress != null) {
                    progress.accept("正在扫描局域网世界…请在等待期间于 Minecraft 中 Esc →「对局域网开放」");
                }
                // 房间码在 host-starting 时虽已生成，但隧道尚不可用；必须等到 host-ok。
                // 超时 5 分钟，给用户足够时间启动 MC 并开放局域网
                return waitForHostState(progress, localLanPortHint);
            } catch (HostScanningTimeoutException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("创建房间失败：" + e.getMessage(), e);
            }
        });
    }

    /**
     * 等待房主状态，并在 PMCL 已从游戏日志确认 LAN 端口时补发标准 Minecraft
     * 组播公告。该公告可绕过 Windows 防火墙/多网卡导致原始游戏广播未被陶瓦收到。
     */
    private CompletableFuture<String> waitForHostState(Consumer<String> progress,
                                                       int localLanPortHint) {
        CompletableFuture<String> waiting =
                waitForState("host-ok", null, progress, 300000);
        if (localLanPortHint > 0 && localLanPortHint <= 65535) {
            startMinecraftLanBeacon(localLanPortHint, waiting, progress);
        }
        return waiting;
    }

    private void startMinecraftLanBeacon(int port, CompletableFuture<?> until,
                                         Consumer<String> progress) {
        CompletableFuture.runAsync(() -> {
            String payload = "[MOTD]PMCL Local Game[/MOTD][AD]" + port + "[/AD]";
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            InetAddress group;
            try {
                group = InetAddress.getByName("224.0.2.60");
            } catch (IOException e) {
                return;
            }
            if (progress != null) {
                progress.accept("已从 Minecraft 日志检测到局域网端口 " + port
                        + "，正在协助陶瓦发现游戏…");
            }

            try (MulticastSocket socket = new MulticastSocket()) {
                socket.setTimeToLive(1);
                java.net.DatagramPacket packet =
                        new java.net.DatagramPacket(bytes, bytes.length, group, 4445);
                while (!until.isDone()) {
                    boolean sent = false;
                    try {
                        java.util.Enumeration<NetworkInterface> interfaces =
                                NetworkInterface.getNetworkInterfaces();
                        while (interfaces != null && interfaces.hasMoreElements()) {
                            NetworkInterface network = interfaces.nextElement();
                            try {
                                if (!network.isUp() || network.isLoopback()
                                        || !network.supportsMulticast()) continue;
                                boolean hasIpv4 = java.util.Collections.list(
                                        network.getInetAddresses()).stream()
                                        .anyMatch(address -> address instanceof java.net.Inet4Address);
                                if (!hasIpv4) continue;
                                socket.setNetworkInterface(network);
                                socket.send(packet);
                                sent = true;
                            } catch (IOException ignored) {
                            }
                        }
                    } catch (IOException ignored) {
                    }
                    if (!sent) {
                        try { socket.send(packet); } catch (IOException ignored) {}
                    }
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            } catch (IOException ignored) {
            }
        });
    }

    /**
     * 加入房间（房客）。
     * GET /state/guesting?room=&lt;code&gt;&player=&lt;name&gt;&public_nodes=...
     * @param roomCode   房间码 U/XXXX-XXXX-XXXX-XXXX
     * @param playerName 玩家名
     * @param progress   进度回调
     * @return 本地 MC 连接地址（如 127.0.0.1:25565）
     */
    public CompletableFuture<String> joinRoom(String roomCode, String playerName, Consumer<String> progress) {
        return start(progress).thenComposeAsync(v -> {
            try {
                // 检查当前状态：如果正在房主模式，不允许加入（会杀死房主的 EasyTier）
                String currentState = extractJsonField(queryState(), "state");
                if (currentState.startsWith("host-")) {
                    throw new RuntimeException("当前正在房主模式（" + currentState + "），请先点击「离开房间」再加入其他房间。" +
                            "注意：离开房间会关闭你创建的房间，房客将无法连接。");
                }
                // 先重置到 idle 并等待确认，避免 daemon 仍处于上一次的 host/guest 状态
                toIdle();
                // 不传 public_nodes：Terracotta 内部会自动添加 4 个默认中继节点
                okhttp3.HttpUrl.Builder ub = okhttp3.HttpUrl.parse(
                        "http://127.0.0.1:" + httpPort + "/state/guesting").newBuilder();
                ub.addQueryParameter("room", roomCode);
                ub.addQueryParameter("player", playerName);
                if (progress != null) progress.accept("正在加入房间 " + roomCode + "…");
                Request req = new Request.Builder().url(ub.build())
                        .header(TerracottaAuthProxy.HEADER, "Bearer " + apiToken)
                        .get().build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        throw new IOException("HTTP " + resp.code() + ": " + body);
                    }
                }
                if (progress != null) progress.accept("正在连接 P2P 网络，这可能需要 1-2 分钟…");
                // 轮询直到 guest-ok（URL 字段在 guest-ok 状态才可用）
                // 超时 5 分钟，P2P 网络建立可能较慢
                return waitForState("guest-ok", null, progress, 300000);
            } catch (Exception e) {
                throw new RuntimeException("加入房间失败：" + e.getMessage(), e);
            }
        });
    }

    /** 回到空闲状态（M58 修复：端点应为 /state/idle，非 /state/ide） */
    public void toIdle() {
        if (httpPort == 0) return;
        // 记录调用来源，方便排查意外的 toIdle 调用
        String caller = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(2).limit(4)
                .map(f -> {
                    String cls = f.getClassName();
                    int dot = cls.lastIndexOf('.');
                    return (dot >= 0 ? cls.substring(dot + 1) : cls) + "." + f.getMethodName() + ":" + f.getLineNumber();
                })
                .reduce("toIdle() called from:", (a, b) -> a + "\n  -> " + b);
        System.out.println("[TerracottaManager] " + caller);
        try {
            Request req = authedGet("/state/idle");
            try (Response resp = http.newCall(req).execute()) {}
        } catch (IOException ignored) {}
        // 等待 daemon 真正回到 waiting 状态（最多 5 秒）
        // 从 host-ok/guest-ok 退出时需要关闭 easytier 子进程，可能耗时
        for (int i = 0; i < 50; i++) {
            try {
                Thread.sleep(100);
                String json = queryState();
                String state = extractJsonField(json, "state");
                if ("waiting".equals(state)) return;
            } catch (Exception ignored) {}
        }
    }

    /**
     * 轮询状态直到匹配目标状态或超时。
     * @return 匹配时的状态 JSON
     */
    private CompletableFuture<String> waitForState(String targetState, String altState,
                                                    Consumer<String> progress, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        return CompletableFuture.supplyAsync(() -> {
            try {
                String lastLoggedState = "";
                String lastObservedState = "";
                int pollCount = 0;
                int consecutiveQueryFailures = 0;
                while (System.currentTimeMillis() < deadline) {
                    String json;
                    try {
                        json = queryState();
                        consecutiveQueryFailures = 0;
                    } catch (IOException queryError) {
                        consecutiveQueryFailures++;
                        if (progress != null
                                && (consecutiveQueryFailures == 1
                                || consecutiveQueryFailures % 10 == 0)) {
                            progress.accept("Terracotta 状态暂时不可用，正在重试（"
                                    + queryError.getMessage() + "）");
                        }
                        Thread.sleep(500);
                        continue;
                    }
                    String state = extractJsonField(json, "state");
                    lastObservedState = state;
                    if (targetState.equals(state) || (altState != null && altState.equals(state))) {
                        if (progress != null) progress.accept("状态达成：" + state);
                        return json;
                    }
                    // 异常状态
                    if ("exception".equals(state)) {
                        String type = extractJsonField(json, "type");
                        String reason = explainException(type);
                        throw new RuntimeException("Terracotta 异常：" + reason + "（type=" + type + "）");
                    }
                    // 状态变化或周期性心跳时输出进度
                    if (progress != null && !state.equals(lastLoggedState)) {
                        lastLoggedState = state;
                        String msg = "当前状态：" + state;
                        if ("host-scanning".equals(state)) {
                            msg += " — 未检测到局域网世界，请在 Minecraft 中 Esc →「对局域网开放」";
                        } else if ("host-starting".equals(state)) {
                            // 房间码在 host-starting 状态就已可用，提前显示
                            String room = extractJsonField(json, "room");
                            if (room != null && !room.isEmpty()) {
                                msg += "，房间码：" + room;
                            }
                        } else if ("guesting".equals(state) || state.startsWith("guest-")) {
                            msg += " — 正在建立 P2P，请稍候";
                        }
                        progress.accept(msg);
                    } else if (progress != null && pollCount % 20 == 0 && pollCount > 0) {
                        // 每 10 秒输出一次心跳
                        long remaining = (deadline - System.currentTimeMillis()) / 1000;
                        if ("host-scanning".equals(state)) {
                            progress.accept("仍在扫描局域网（剩余 " + remaining
                                    + "s）— 请确认游戏已对局域网开放；macOS 需允许「本地网络」");
                        } else {
                            progress.accept("仍在等待 " + targetState + "（当前：" + state
                                    + "，剩余 " + remaining + "s）");
                        }
                    }
                    pollCount++;
                    Thread.sleep(500);
                }
                String msg = timeoutMessage(targetState, altState, lastObservedState);
                if ("host-scanning".equals(lastObservedState)
                        && ("host-ok".equals(targetState) || "host-starting".equals(altState))) {
                    throw new HostScanningTimeoutException(msg);
                }
                throw new RuntimeException(msg);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("轮询状态失败：" + e.getMessage(), e);
            }
        });
    }

    /**
     * host-scanning 等到超时：daemon 仍在扫局域网，不应被 stop() 杀掉，
     * 以便用户开放局域网后再次点「创建房间」可续等。
     */
    public static final class HostScanningTimeoutException extends RuntimeException {
        public HostScanningTimeoutException(String message) {
            super(message);
        }
    }

    /** 超时失败时给出可操作的说明（host-scanning 最常见）。 */
    private static String timeoutMessage(String targetState, String altState, String lastState) {
        if ("host-scanning".equals(lastState)
                && ("host-ok".equals(targetState) || "host-starting".equals(altState))) {
            return "创建房间超时：未检测到 Minecraft 局域网世界（host-scanning）。"
                    + "请现在游戏内 Esc →「对局域网开放」，然后再次点击「创建房间」继续等待。"
                    + "若已开放仍失败，请检查 macOS「本地网络」权限是否允许 PMCL 与 Minecraft。";
        }
        if (lastState != null && lastState.startsWith("guest-") && "guest-ok".equals(targetState)) {
            return "加入房间超时（最后状态：" + lastState + "）。"
                    + "请确认房间码正确、房主仍在线，并检查本机网络 / 防火墙。";
        }
        String wanted = altState != null ? targetState + "/" + altState : targetState;
        return "等待状态 " + wanted + " 超时（最后状态：" + lastState + "）";
    }

    /** 简单 JSON 字段提取（支持字符串和数字值） */
    private static String extractJsonField(String json, String field) {
        // 先尝试字符串值 "field":"value"
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*?)\"")
                .matcher(json);
        if (m.find()) return m.group(1);
        // 再尝试数字值 "field":123
        m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        return m.find() ? m.group(1) : "";
    }

    /** 解释 Terracotta 异常类型（来自 strings 分析） */
    private static String explainException(String type) {
        if (type == null || type.isEmpty()) return "未知异常";
        switch (type) {
            case "0": return "PingHostFail（无法连接房主。常见原因：1）房主未运行或已下线；2）在同一台电脑上同时创建和加入房间（不支持，需两台设备）；3）房间码输入错误）";
            case "1": return "PingHostRst（房主中断连接）";
            case "2": return "GuestEasytierCrash（房客 EasyTier 崩溃）";
            case "3": return "HostEasytierCrash（房主 EasyTier 崩溃）";
            case "4": return "PingServerRst（中继服务器中断连接，可能是网络问题）";
            case "5": return "ScaffoldingInvalidResponse（中继服务器返回无效响应）";
            case "6": return "BadChar（房间码包含非法字符）";
            case "7": return "BadEnd（房间码格式错误）";
            default: return "异常类型 " + type;
        }
    }

    // ============ 中继节点 ============

    /** 获取 EasyTier 公共中继节点列表 */
    private List<String> fetchPublicNodes() {
        List<String> nodes = new ArrayList<>();
        // 从远程拉取节点列表（terracotta.glavo.site/nodes 返回官方推荐节点）
        String body = null;
        try {
            Request req = new Request.Builder().url(NODE_LIST_URL).get().build();
            try (Response resp = http.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    body = resp.body().string();
                }
            }
        } catch (IOException e) {
            // SSL 失败 → curl fallback
            if (com.pmcl.core.download.CurlFallback.isSslHandshakeFailure(e)
                    && com.pmcl.core.download.CurlFallback.isAvailable()) {
                try {
                    body = com.pmcl.core.download.CurlFallback.getString(NODE_LIST_URL);
                } catch (IOException ignored) {}
            }
        }
        if (body != null && !body.isEmpty()) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            List<String> remote = new ArrayList<>();
            while (m.find()) remote.add(m.group(1));
            if (!remote.isEmpty()) {
                nodes.clear();
                nodes.addAll(remote);
            }
        }
        // 如果远程获取失败，使用可用的默认节点
        // 注意：public.easytier.top / public2.easytier.cn 的 DNS 经常解析失败，
        // 只保留 etnode.zkitefly.eu.org（Cloudflare 托管，稳定可用）
        if (nodes.isEmpty()) {
            nodes.add("https://etnode.zkitefly.eu.org/node1");
            nodes.add("https://etnode.zkitefly.eu.org/node2");
        }
        return nodes;
    }

    // ============ 下载辅助 ============

    private void downloadFile(String filename, Path target, Consumer<String> progress) throws IOException {
        Files.createDirectories(target.getParent());
        IOException lastError = null;
        for (String tmpl : MIRROR_TEMPLATES) {
            String url = String.format(tmpl, filename);
            // 镜像名称用于进度显示
            String name;
            if (tmpl.contains("gitee.com")) name = "Gitee 镜像";
            else if (tmpl.startsWith("https://github.com")) name = "GitHub 直连";
            else name = safeHostname(tmpl);
            try {
                if (progress != null) progress.accept("下载中（" + name + "）…");
                Request req = new Request.Builder().url(url).header("User-Agent", "PMCL/1.0").get().build();
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
                    if (resp.body() == null) throw new IOException("响应体为空");
                    try (InputStream in = resp.body().byteStream()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                return;
            } catch (IOException e) {
                lastError = e;
                if (progress != null) progress.accept(name + " 失败：" + e.getMessage());
                // SSL 握手失败 → 自动 fallback 到 curl（绕过 GFW 的 JA3 指纹干扰）
                if (com.pmcl.core.download.CurlFallback.isSslHandshakeFailure(e)
                        && com.pmcl.core.download.CurlFallback.isAvailable()) {
                    if (progress != null) progress.accept("SSL 失败，改用 curl 下载…");
                    try {
                        com.pmcl.core.download.CurlFallback.downloadFile(url, target);
                        if (progress != null) progress.accept("curl 下载成功");
                        return;
                    } catch (IOException curlErr) {
                        lastError = curlErr;
                        if (progress != null) progress.accept("curl 也失败：" + curlErr.getMessage());
                    }
                }
            }
        }
        throw new IOException("所有镜像下载失败：" + (lastError != null ? lastError.getMessage() : "未知"), lastError);
    }

    /** SHA256 校验 */
    private static String sha256(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = is.read(buffer)) != -1) {
                    md.update(buffer, 0, n);
                }
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            // H13: b & 0xff 防止 byte 符号扩展为 int 时产生 ffffffff 而非 ff
            for (byte b : hash) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** 从 tar.gz 中提取 terracotta 二进制 */
    private void extractTerracotta(Path tarball, Path outBinary) throws IOException {
        try (InputStream fis = Files.newInputStream(tarball);
             GZIPInputStream gz = new GZIPInputStream(fis)) {
            // 手动解析 tar 格式：每个条目由 512 字节头 + 数据块组成，数据按 512 字节对齐
            byte[] header = new byte[512];
            while (true) {
                int read = readFully(gz, header);
                if (read < 512) break; // EOF
                // 全零块表示结束
                boolean allZero = true;
                for (int i = 0; i < 512; i++) {
                    if (header[i] != 0) { allZero = false; break; }
                }
                if (allZero) break;
                // 文件名：header[0..100]
                String name = new String(header, 0, 100, StandardCharsets.US_ASCII).trim().replace("\0", "");
                // 文件大小：header[124..136]，八进制
                String sizeStr = new String(header, 124, 12, StandardCharsets.US_ASCII).trim().replace("\0", "");
                long size = sizeStr.isEmpty() ? 0 : Long.parseLong(sizeStr, 8);
                // 类型标志：header[156]
                byte type = header[156];
                // 仅处理普通文件（type '0' 或 '\0'）
                if ((type == '0' || type == 0) && size > 0) {
                    String lowerName = name.toLowerCase(Locale.ROOT);
                    // tar 包内文件名格式：terracotta-0.4.2-macos-arm64 或 .pkg
                    // 匹配以 terracotta 开头的可执行文件（排除 .pkg / .so / .exe 等非二进制）
                    boolean isBinary = lowerName.startsWith("terracotta")
                            && !lowerName.endsWith(".pkg")
                            && !lowerName.endsWith(".so")
                            && !lowerName.endsWith(".tar.gz")
                            && !lowerName.endsWith(".zip");
                    // 也兼容直接叫 terracotta / terracotta.exe 的情况
                    if (lowerName.endsWith("terracotta") || lowerName.endsWith("terracotta.exe") || isBinary) {
                        // 读取文件内容（校验大小防止 OOM/NegativeArraySizeException）
                        if (size > 200L * 1024 * 1024 || size > Integer.MAX_VALUE) {
                            throw new IOException("tar 条目过大: " + name + " (" + size + " bytes)");
                        }
                        byte[] content = new byte[(int) size];
                        readFully(gz, content);
                        Files.write(outBinary, content);
                        // 跳过填充
                        long padding = (512 - size % 512) % 512;
                        if (padding > 0) {
                            byte[] pad = new byte[(int) padding];
                            readFully(gz, pad);
                        }
                        return;
                    }
                }
                // 跳过数据 + 填充
                long total = (size + 511) / 512 * 512;
                long skipped = gz.skip(total);
                // skip 可能返回少于请求，循环跳过
                while (skipped < total) {
                    long s = gz.skip(total - skipped);
                    if (s <= 0) break;
                    skipped += s;
                }
            }
            throw new IOException("压缩包中未找到 terracotta 二进制");
        }
    }

    /** 完整读取 n 个字节 */
    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    /** 从 URL 模板中安全提取 hostname，split 失败时返回原始模板 */
    private static String safeHostname(String url) {
        try {
            return new java.net.URI(url).getHost();
        } catch (Exception e) {
            return url;
        }
    }

    /** Asset 规格 */
    private static final class AssetSpec {
        final String platform;
        final String filename;
        final String sha256;
        AssetSpec(String platform, String filename, String sha256) {
            this.platform = platform;
            this.filename = filename;
            this.sha256 = sha256;
        }
    }
}
