package com.pmcl.core.multiplayer;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 联机业务管理器：封装「创建房间 / 加入房间 / 邀请码 / 状态」语义。
 * <p>
 * 支持两种后端：
 * <ul>
 *   <li>{@code EASYTIER} —— 基于 EasyTier P2P 组网（默认，陶瓦联机）</li>
 *   <li>{@code CONNECTX} —— 基于 ConnectX / ZeroTier P2P 联机</li>
 * </ul>
 * <p>
 * 邀请码格式：
 * <ul>
 *   <li>EasyTier v2: {@code pmcl2-<Base64URL(networkName|inviteToken|peer)>}
 *       — network secret is HKDF-derived from the token (raw secret not embedded)</li>
 *   <li>EasyTier legacy: {@code pmcl-<Base64URL(networkName|networkSecret|peer)>}</li>
 *   <li>ConnectX: {@code connectx-<Base64URL(serverAddr|port|roomShortId)>}</li>
 * </ul>
 * 状态机：{@code IDLE → DOWNLOADING → CONNECTING → CONNECTED}，任意状态可回到 {@code DISCONNECTED}。
 */
public final class MultiplayerManager {

    public enum State { IDLE, DOWNLOADING, CONNECTING, CONNECTED, DISCONNECTED, FAILED }
    public enum Backend { EASYTIER, CONNECTX, TERRACOTTA }

    /** 邀请码前缀 */
    private static final String INVITE_PREFIX_EASYTIER = "pmcl-";
    /** EasyTier v2：invite 携带 HKDF 输入 token，不嵌入明文 network_secret */
    private static final String INVITE_PREFIX_EASYTIER_V2 = "pmcl2-";
    private static final String INVITE_PREFIX_CONNECTX = "connectx-";
    private static final byte[] EASYTIER_INVITE_SALT =
            "pmcl-easytier-invite-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EASYTIER_INVITE_INFO =
            "network-secret".getBytes(StandardCharsets.UTF_8);
    /** 从 easytier-core 日志中提取虚拟 IPv4 的正则 */
    private static final Pattern IPV4_PATTERN =
            Pattern.compile("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})");

    private final EasyTierManager easyTier;
    private final ConnectXManager connectX;
    private final TerracottaManager terracotta;
    private volatile com.pmcl.core.plugin.PluginManager pluginManager;
    private volatile Backend backend = Backend.TERRACOTTA;
    private volatile State state = State.IDLE;
    private volatile String virtualIp = "";
    private volatile String currentNetworkName = "";
    private volatile String currentNetworkSecret = "";
    /** Opaque invite token (v2); secret is HKDF(token). Empty for legacy joins. */
    private volatile String currentInviteToken = "";
    private volatile String currentPeer = "";
    private volatile String currentRoomShortId = "";
    /** Terracotta 房间码 U/XXXX-XXXX-XXXX-XXXX */
    private volatile String currentRoomCode = "";
    /** Terracotta 房客模式下的本地 MC 连接地址（如 127.0.0.1:25565） */
    private volatile String localMcAddr = "";
    private volatile String lastError = "";
    /** ConnectX 运行时配置（由偏好同步，供 joinRoom 自动路由） */
    /** User-configured EasyTier shared-node URI (empty → try built-in public peers). */
    private volatile String easytierPeer = "";
    private volatile String connectxBinaryPath = "";
    private volatile String connectxServerAddress = "";
    private volatile int connectxServerPort = 3535;
    /** ConnectX CONNECTING 看门狗代数：CONNECTED/FAILED/leave 时递增作废 */
    private final AtomicLong connectingWatchGen = new AtomicLong(0);
    private static final long CONNECTX_CONNECTING_TIMEOUT_MS = 90_000L;

    public MultiplayerManager() {
        this.easyTier = new EasyTierManager();
        this.connectX = new ConnectXManager();
        this.terracotta = new TerracottaManager();
    }

    /** Optional plugin event bus (set by LauncherCore after PluginManager init). */
    public void setPluginManager(com.pmcl.core.plugin.PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    private void fireRoomCreated(String roomCode, String virtualIp) {
        com.pmcl.core.plugin.PluginManager pm = pluginManager;
        if (pm == null) return;
        try {
            pm.fireEvent(new com.pmcl.plugin.RoomCreatedEvent(
                    roomCode != null ? roomCode : "",
                    virtualIp != null ? virtualIp : ""));
        } catch (Throwable ignored) {}
    }

    private void fireRoomJoined(String roomCode, String virtualIp) {
        com.pmcl.core.plugin.PluginManager pm = pluginManager;
        if (pm == null) return;
        try {
            pm.fireEvent(new com.pmcl.plugin.RoomJoinedEvent(
                    roomCode != null ? roomCode : "",
                    virtualIp != null ? virtualIp : ""));
        } catch (Throwable ignored) {}
    }

    /** 同步 ConnectX 二进制与服务器配置（偏好变更时调用） */
    public void configureConnectX(String binaryPath, String serverAddr, int serverPort) {
        this.connectxBinaryPath = binaryPath != null ? binaryPath.trim() : "";
        this.connectxServerAddress = serverAddr != null ? serverAddr.trim() : "";
        this.connectxServerPort = serverPort > 0 ? serverPort : 3535;
    }

    /** 同步 EasyTier 共享节点（偏好变更时调用）；空则启动时尝试内置公共节点。 */
    public void configureEasyTierPeer(String peerUri) {
        this.easytierPeer = peerUri != null ? peerUri.trim() : "";
    }

    public String getEasytierPeer() {
        return easytierPeer;
    }

    public EasyTierManager getEasyTier() { return easyTier; }
    public ConnectXManager getConnectX() { return connectX; }
    public TerracottaManager getTerracotta() { return terracotta; }
    public Backend getBackend() { return backend; }
    public void setBackend(Backend b) { this.backend = b; }
    public State getState() { return state; }
    public String getVirtualIp() { return virtualIp; }
    public String getLastError() { return lastError; }
    public String getCurrentNetworkName() { return currentNetworkName; }
    public String getCurrentRoomShortId() { return currentRoomShortId; }
    public String getCurrentRoomCode() { return currentRoomCode; }
    public String getLocalMcAddr() { return localMcAddr; }

    /** 是否处于"已加入房间"的活跃状态（含连接中） */
    public boolean isInRoom() {
        State s = state;
        return s == State.CONNECTING || s == State.CONNECTED;
    }

    /** 是否正在占用联机后端（下载/连接中也算忙，防双击双开） */
    public boolean isBusy() {
        State s = state;
        return s == State.DOWNLOADING || s == State.CONNECTING || s == State.CONNECTED;
    }

    // ============ EasyTier 后端 ============

    /**
     * 创建新房间：根据后端分发。
     * EasyTier / Terracotta 直接调用；ConnectX 需使用 createRoomConnectX。
     */
    public synchronized CompletableFuture<Void> createRoom(Consumer<String> onProgress) {
        if (backend == Backend.TERRACOTTA) {
            return createRoomTerracotta(onProgress, "PMCL-Player");
        }
        if (backend == Backend.CONNECTX) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "ConnectX 后端请使用 createRoomConnectX 方法（需要 binaryPath / serverAddr / serverPort）"));
        }
        // S7: synchronized 保证忙状态检查与 state 赋值原子，避免双击启动两个进程
        if (isBusy()) {
            return CompletableFuture.failedFuture(new IllegalStateException("已在房间中或正在连接，请先离开"));
        }
        currentNetworkName = "pmcl-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        // H16: room secret derived via HKDF from a random invite token (token goes in share code)
        byte[] token = new byte[32];
        new java.security.SecureRandom().nextBytes(token);
        currentInviteToken = Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        currentNetworkSecret = deriveEasyTierSecret(token);
        currentPeer = easytierPeer.isEmpty() ? EasyTierManager.PUBLIC_PEER : easytierPeer;
        return startEasyTier(onProgress);
    }

    /**
     * 通过邀请码/房间码加入已有房间（自动识别 Terracotta / EasyTier / ConnectX 格式）。
     */
    public synchronized CompletableFuture<Void> joinRoom(String invitationCode, Consumer<String> onProgress) {
        if (isBusy()) {
            return CompletableFuture.failedFuture(new IllegalStateException("已在房间中或正在连接，请先离开"));
        }
        String trimmed = invitationCode.trim();
        // Terracotta 房间码格式：U/XXXX-XXXX-XXXX-XXXX
        if (trimmed.startsWith("U/") || backend == Backend.TERRACOTTA) {
            return joinRoomTerracotta(trimmed, onProgress, "PMCL-Player");
        }
        if (trimmed.startsWith(INVITE_PREFIX_CONNECTX)) {
            if (connectxBinaryPath.isEmpty() || connectxServerAddress.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                    "ConnectX 未配置二进制路径或服务器地址，请先在联机设置中填写"));
            }
            return joinRoomConnectX(trimmed, onProgress,
                    connectxBinaryPath, connectxServerAddress, connectxServerPort);
        }
        // EasyTier 邀请码
        try {
            String[] parts = parseEasyTierInvitation(invitationCode);
            currentNetworkName = parts[0];
            currentNetworkSecret = parts[1];
            currentPeer = parts[2];
            currentInviteToken = parts.length >= 4 ? parts[3] : "";
            if (currentPeer.isEmpty()) {
                currentPeer = easytierPeer.isEmpty() ? EasyTierManager.PUBLIC_PEER : easytierPeer;
            }
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("邀请码格式无效：" + e.getMessage(), e));
        }
        return startEasyTier(onProgress);
    }

    /** ConnectX 进入 CONNECTING 后若迟迟未解析到 CONNECTED，超时失败避免永久 busy */
    private void armConnectXConnectingWatchdog() {
        final long gen = connectingWatchGen.incrementAndGet();
        CompletableFuture.delayedExecutor(CONNECTX_CONNECTING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .execute(() -> {
                    synchronized (MultiplayerManager.this) {
                        if (gen != connectingWatchGen.get()) return;
                        if (state == State.CONNECTING && backend == Backend.CONNECTX) {
                            state = State.FAILED;
                            lastError = "ConnectX 连接超时：未确认房间就绪，请重试或检查网络";
                            try { connectX.stop(); } catch (Throwable ignored) {}
                        }
                    }
                });
    }

    private void cancelConnectingWatchdog() {
        connectingWatchGen.incrementAndGet();
    }

    /** 离开当前房间 */
    public synchronized void leaveRoom() {
        cancelConnectingWatchdog();
        boolean cleanupOk = true;
        String cleanupError = "";
        if (backend == Backend.TERRACOTTA) {
            try { terracotta.toIdle(); } catch (Throwable t) {
                cleanupOk = false;
                cleanupError = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            try { terracotta.stop(); } catch (Throwable t) {
                cleanupOk = false;
                cleanupError = t.getMessage() != null ? t.getMessage() : t.toString();
            }
        } else if (backend == Backend.CONNECTX) {
            try {
                connectX.leaveRoom().get(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Throwable t) {
                cleanupOk = false;
                cleanupError = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            try { connectX.stop(); } catch (Throwable t) {
                cleanupOk = false;
                cleanupError = t.getMessage() != null ? t.getMessage() : t.toString();
            }
        } else {
            try { easyTier.stop(); } catch (Throwable t) {
                cleanupOk = false;
                cleanupError = t.getMessage() != null ? t.getMessage() : t.toString();
            }
        }
        virtualIp = "";
        currentRoomShortId = "";
        currentRoomCode = "";
        localMcAddr = "";
        currentInviteToken = "";
        if (cleanupOk) {
            state = State.DISCONNECTED;
            lastError = "";
        } else {
            // 进程可能仍存活：不伪装成已断开，便于 UI 重试 leave
            state = State.FAILED;
            lastError = "离开房间时清理失败: " + cleanupError;
        }
    }

    /** 将 exceptionally 中的失败重新抛出，避免 join() 误判成功 */
    private static Void failFuture(Throwable e) {
        throw new java.util.concurrent.CompletionException(e);
    }

    /**
     * 生成当前房间的邀请码 / 房间码。
     * Terracotta 后端直接返回房间码 U/XXXX-XXXX-XXXX-XXXX。
     */
    public String generateInvitation() {
        if (backend == Backend.TERRACOTTA) {
            return currentRoomCode;
        }
        if (backend == Backend.CONNECTX) {
            return generateConnectXInvitation();
        }
        if (currentNetworkName.isEmpty() || currentNetworkSecret.isEmpty()) {
            return "";
        }
        // Prefer v2: share token only; peers derive network_secret via HKDF
        if (currentInviteToken != null && !currentInviteToken.isEmpty()) {
            String raw = currentNetworkName + "|" + currentInviteToken + "|" + currentPeer;
            String b64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
            return INVITE_PREFIX_EASYTIER_V2 + b64;
        }
        // Legacy fallback (joined via old invite): still emit v1 so peers can join
        String raw = currentNetworkName + "|" + currentNetworkSecret + "|" + currentPeer;
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return INVITE_PREFIX_EASYTIER + b64;
    }

    /** HKDF-SHA256 → Base64URL network secret for EasyTier. */
    static String deriveEasyTierSecret(byte[] inviteToken) {
        byte[] okm = com.pmcl.core.friend.FriendCrypto.hkdf(
                inviteToken, EASYTIER_INVITE_SALT, EASYTIER_INVITE_INFO, 32);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(okm);
    }

    // ============ Terracotta 后端 ============

    /**
     * 创建房间（Terracotta）：调用 Terracotta 二进制的 HTTP API。
     * @param playerName 玩家名（用于房间内显示）
     */
    public synchronized CompletableFuture<Void> createRoomTerracotta(Consumer<String> onProgress, String playerName) {
        if (isBusy()) {
            return CompletableFuture.failedFuture(new IllegalStateException("已在房间中或正在连接，请先离开"));
        }
        state = State.DOWNLOADING;
        lastError = "";
        currentRoomCode = "";
        localMcAddr = "";
        return terracotta.createRoom(playerName, onProgress)
                .thenAccept(json -> {
                    // 从状态 JSON 提取房间码
                    currentRoomCode = extractField(json, "room");
                    state = State.CONNECTED;
                    fireRoomCreated(currentRoomCode, localMcAddr);
                    if (onProgress != null) onProgress.accept("✓ 房间已创建，房间码：" + currentRoomCode
                        + "\n⚠ 重要提示："
                        + "\n1. 请保持 PMCL 运行，不要点击「离开房间」"
                        + "\n2. 中继节点连接需要 1-2 分钟，请等待后再让朋友加入"
                        + "\n3. 让朋友在另一台设备上输入此房间码加入");
                })
                .exceptionally(e -> {
                    Throwable root = e;
                    boolean scanningTimeout = false;
                    for (Throwable t = e; t != null; t = t.getCause()) {
                        root = t;
                        if (t instanceof TerracottaManager.HostScanningTimeoutException) {
                            scanningTimeout = true;
                            break;
                        }
                    }
                    state = State.FAILED;
                    lastError = root.getMessage() != null ? root.getMessage() : root.toString();
                    // host-scanning 超时：保留 daemon 继续扫，方便用户开局域网后重试续等
                    if (!scanningTimeout) {
                        try { terracotta.stop(); } catch (Throwable ignored) {}
                    }
                    return failFuture(root);
                });
    }

    /**
     * 加入房间（Terracotta）。
     * @param roomCode 房间码 U/XXXX-XXXX-XXXX-XXXX
     */
    public synchronized CompletableFuture<Void> joinRoomTerracotta(String roomCode, Consumer<String> onProgress, String playerName) {
        if (isBusy()) {
            return CompletableFuture.failedFuture(new IllegalStateException("已在房间中或正在连接，请先离开"));
        }
        // 阻止加入自己的房间码：同一台电脑无法同时当房主和房客
        if (roomCode.equals(currentRoomCode) && !currentRoomCode.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "不能加入自己创建的房间！同一台电脑无法同时当房主和房客。请用另一台设备加入，或先「离开房间」后再加入别人的房间码。"));
        }
        state = State.DOWNLOADING;
        lastError = "";
        currentRoomCode = roomCode;
        localMcAddr = "";
        return terracotta.joinRoom(roomCode, playerName, onProgress)
                .thenAccept(json -> {
                    // 从状态 JSON 提取本地 MC 地址（如 127.0.0.1:25565）
                    localMcAddr = extractField(json, "url");
                    state = State.CONNECTED;
                    fireRoomJoined(currentRoomCode, localMcAddr);
                    if (onProgress != null) onProgress.accept("✓ 已加入房间，MC 地址：" + localMcAddr);
                })
                .exceptionally(e -> {
                    state = State.FAILED;
                    lastError = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    try { terracotta.stop(); } catch (Throwable ignored) {}
                    return failFuture(e);
                });
    }

    /** 简单 JSON 字段提取 */
    private static String extractField(String json, String field) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*?)\"")
                .matcher(json);
        return m.find() ? m.group(1) : "";
    }

    // ============ ConnectX 后端 ============

    /**
     * 创建新房间（ConnectX）：需要先连接 ConnectX 服务器，然后发送 room create 命令。
     * @param onProgress 进度回调
     * @param binaryPath  ConnectX 二进制路径
     * @param serverAddr  ConnectX 服务器地址
     * @param serverPort  ConnectX 服务器端口
     * @param roomName    房间名
     * @param maxUsers    最大人数
     * @param password    密码（可空）
     * @param useRelay    是否使用中继
     */
    public synchronized CompletableFuture<Void> createRoomConnectX(Consumer<String> onProgress,
            String binaryPath, String serverAddr, int serverPort,
            String roomName, int maxUsers, String password, boolean useRelay) {
        if (isBusy()) {
            return CompletableFuture.failedFuture(new IllegalStateException("已在房间中或正在连接，请先离开"));
        }
        backend = Backend.CONNECTX;
        state = State.CONNECTING;
        lastError = "";
        virtualIp = "";
        currentRoomShortId = "";
        armConnectXConnectingWatchdog();
        return connectX.start(binaryPath, serverAddr, serverPort, line -> {
                    if (onProgress != null) onProgress.accept(line);
                    parseConnectXOutput(line);
                })
                .thenCompose(v -> {
                    if (onProgress != null) onProgress.accept("正在创建房间…");
                    return connectX.createRoom(roomName, maxUsers, password, useRelay);
                })
                .thenRun(() -> {
                    // 等待房间创建结果（异步解析输出中的 roomShortId）
                    state = State.CONNECTING;
                })
                .exceptionally(e -> {
                    cancelConnectingWatchdog();
                    state = State.FAILED;
                    lastError = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    try { connectX.stop(); } catch (Throwable ignored) {}
                    return failFuture(e);
                });
    }

    /** 无参版本（使用已存储的 ConnectX 配置），供 ViewModel 调用 */
    public CompletableFuture<Void> createRoomConnectX(Consumer<String> onProgress,
            String binaryPath, String serverAddr, int serverPort) {
        return createRoomConnectX(onProgress, binaryPath, serverAddr, serverPort,
                "PMCL-" + UUID.randomUUID().toString().substring(0, 6), 10, "", false);
    }

    /** 通过邀请码加入 ConnectX 房间 */
    public synchronized CompletableFuture<Void> joinRoomConnectX(String invitationCode, Consumer<String> onProgress,
            String binaryPath, String serverAddr, int serverPort) {
        if (isBusy()) {
            return CompletableFuture.failedFuture(new IllegalStateException("已在房间中或正在连接，请先离开"));
        }
        try {
            String[] parts = parseConnectXInvitation(invitationCode);
            String shortId = parts[2];
            // 如果邀请码中不含服务器地址，使用传入的配置
            String addr = parts[0].isEmpty() ? serverAddr : parts[0];
            int port;
            if (parts[1].equals("0")) {
                port = serverPort;
            } else {
                try {
                    port = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ex) {
                    port = serverPort;
                }
            }
            backend = Backend.CONNECTX;
            state = State.CONNECTING;
            lastError = "";
            virtualIp = "";
            currentRoomShortId = shortId;
            armConnectXConnectingWatchdog();
            return connectX.start(binaryPath, addr, port, line -> {
                        if (onProgress != null) onProgress.accept(line);
                        parseConnectXOutput(line);
                    })
                    .thenCompose(v -> {
                        if (onProgress != null) onProgress.accept("正在加入房间 " + shortId + "…");
                        return connectX.joinRoom(shortId, "");
                    })
                    .exceptionally(e -> {
                        cancelConnectingWatchdog();
                        state = State.FAILED;
                        lastError = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                        try { connectX.stop(); } catch (Throwable ignored) {}
                        return failFuture(e);
                    });
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("ConnectX 邀请码无效：" + e.getMessage(), e));
        }
    }

    /** 生成 ConnectX 邀请码 */
    private String generateConnectXInvitation() {
        String shortId = connectX.getRoomShortId();
        if (shortId.isEmpty()) return "";
        // 从当前 ConnectX 配置读取服务器信息（由调用方维护）
        // 这里用 roomShortId 作为邀请码核心
        String raw = shortId;
        String b64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return INVITE_PREFIX_CONNECTX + b64;
    }

    /** 解析 ConnectX 邀请码 → [serverAddr, port, roomShortId] */
    public static String[] parseConnectXInvitation(String code) {
        if (code == null) throw new IllegalArgumentException("邀请码为空");
        String trimmed = code.trim();
        String b64;
        if (trimmed.startsWith(INVITE_PREFIX_CONNECTX)) {
            b64 = trimmed.substring(INVITE_PREFIX_CONNECTX.length());
        } else {
            b64 = trimmed;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            decoded = Base64.getDecoder().decode(b64);
        }
        String raw = new String(decoded, StandardCharsets.UTF_8);
        // 简单格式：roomShortId（服务器地址由用户在设置中配置）
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("邀请码内容为空");
        }
        return new String[]{"", "0", raw};
    }

    // ============ 内部 ============

    private CompletableFuture<Void> startEasyTier(Consumer<String> onProgress) {
        state = State.DOWNLOADING;
        lastError = "";
        virtualIp = "";
        // Preflight: official public nodes are often NXDOMAIN — fail fast instead of hanging
        String resolvedPeer = EasyTierManager.resolvePeerUri(currentPeer);
        if (resolvedPeer == null) {
            String hint = "无法解析 EasyTier 共享节点"
                    + (currentPeer.isEmpty() ? "（官方 public.easytier.cn 已停用）" : "：" + currentPeer)
                    + "。请在联机设置中填写可用节点（tcp://主机:端口），或改用「陶瓦联机」。";
            state = State.FAILED;
            lastError = hint;
            return CompletableFuture.failedFuture(new IllegalStateException(hint));
        }
        currentPeer = resolvedPeer;
        return easyTier.ensureBinary(onProgress)
                .thenCompose(v -> {
                    state = State.CONNECTING;
                    if (onProgress != null) onProgress.accept("正在连接 EasyTier（节点 " + currentPeer + "）…");
                    // EasyTier 输出同时传给解析器和 UI（方便用户看到实时日志诊断问题）
                    return easyTier.start(currentNetworkName, currentNetworkSecret, currentPeer, line -> {
                        parseEasyTierOutput(line);
                        if (onProgress != null) onProgress.accept(line);
                    });
                })
                .thenCompose(v -> {
                    // EasyTier 进程已启动，等 TUN 网卡就绪后主动查询虚拟 IP
                    if (onProgress != null) onProgress.accept("正在获取虚拟 IP…（需要管理员/sudo 权限创建虚拟网卡）");
                    return CompletableFuture.supplyAsync(() -> {
                        // TUN 网卡创建需要 1-3 秒；进程若已退出则立即失败
                        for (int i = 0; i < 20; i++) {
                            try { Thread.sleep(1000); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt(); break;
                            }
                            if (!easyTier.isRunning()) {
                                String logs = easyTier.recentLogSnippet();
                                String hint = "EasyTier 进程已退出，未能建立虚拟网卡。"
                                        + (logs.isEmpty() ? "" : ("\n" + logs))
                                        + "\n若提示端口占用，请结束残留的 easytier-core 后重试；"
                                        + "官方公共节点已停用时请配置自建节点或改用陶瓦联机。";
                                if (onProgress != null) onProgress.accept(hint);
                                lastError = hint;
                                state = State.FAILED;
                                throw new IllegalStateException(hint);
                            }
                            String ip = easyTier.queryVirtualIp();
                            if (!ip.isEmpty()) {
                                virtualIp = ip;
                                state = State.CONNECTED;
                                String code = !currentRoomCode.isEmpty() ? currentRoomCode
                                        : (!currentNetworkName.isEmpty() ? currentNetworkName : "easytier");
                                fireRoomCreated(code, virtualIp);
                                if (onProgress != null) onProgress.accept("✓ 虚拟 IP：" + ip);
                                break;
                            }
                        }
                        if (virtualIp.isEmpty()) {
                            // 诊断：打印所有网卡名称，帮助排查 TUN 网卡为何没被匹配
                            String ifaces = easyTier.dumpAllInterfaces();
                            if (onProgress != null) onProgress.accept("当前网卡列表：" + ifaces);
                            // 权限提示
                            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
                            String hint;
                            if (os.contains("win")) {
                                hint = "未获取到虚拟 IP。请用「管理员身份」运行 PMCL，EasyTier 需要 WinTun 驱动创建虚拟网卡。";
                            } else if (os.contains("mac")) {
                                hint = "未获取到虚拟 IP。macOS 创建 TUN 网卡需要 root 权限，请用 sudo 运行 PMCL；"
                                        + "也可改用「陶瓦联机」（无需自建 EasyTier 节点）。";
                            } else {
                                hint = "未获取到虚拟 IP。Linux 创建 TUN 网卡需要 root 或 CAP_NET_ADMIN 权限，请用 sudo 运行 PMCL。";
                            }
                            String logs = easyTier.recentLogSnippet();
                            if (!logs.isEmpty()) hint = hint + "\n" + logs;
                            if (onProgress != null) onProgress.accept(hint);
                            lastError = hint;
                            state = State.FAILED;
                            try { easyTier.stop(); } catch (Throwable ignored) {}
                            throw new IllegalStateException(hint);
                        }
                        return (Void) null;
                    });
                })
                .exceptionally(e -> {
                    state = State.FAILED;
                    lastError = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                    try { easyTier.stop(); } catch (Throwable ignored) {}
                    return failFuture(e);
                });
    }

    /** 解析 easytier-core 输出 */
    private void parseEasyTierOutput(String line) {
        if (line == null) return;
        String lower = line.toLowerCase();
        if (lower.contains("tun") && lower.contains("device")) {
            Matcher m = IPV4_PATTERN.matcher(line);
            if (m.find()) virtualIp = m.group(1);
            state = State.CONNECTED;
        } else if (lower.contains("connected") || lower.contains("peer") && lower.contains("estab")) {
            if (state == State.CONNECTING) state = State.CONNECTED;
        }
        if (virtualIp.isEmpty()) {
            Matcher m = IPV4_PATTERN.matcher(line);
            // 注意运算符优先级：必须用括号，否则 && 优先于 || 导致只要含 "ipv4" 就误判
            if (m.find() && (lower.contains("local") || lower.contains("ipv4") || lower.contains("tun"))) {
                virtualIp = m.group(1);
            }
        }
    }

    /** 解析 ConnectX 输出 */
    private void parseConnectXOutput(String line) {
        if (line == null) return;
        String lower = line.toLowerCase();
        // 房间创建/加入成功
        if (lower.contains("room") && (lower.contains("create") || lower.contains("join"))
                && lower.contains("succeeded")) {
            currentRoomShortId = connectX.getRoomShortId();
            if (!currentRoomShortId.isEmpty()) {
                state = State.CONNECTED;
                cancelConnectingWatchdog();
                if (lower.contains("create")) {
                    fireRoomCreated(currentRoomShortId, virtualIp);
                } else {
                    fireRoomJoined(currentRoomShortId, virtualIp);
                }
            }
        }
        // 虚拟 IP
        String ip = connectX.getVirtualIp();
        if (!ip.isEmpty()) {
            virtualIp = ip;
            if (state == State.CONNECTING) {
                state = State.CONNECTED;
                cancelConnectingWatchdog();
            }
        }
        // 错误
        if (lower.contains("error") || lower.contains("failed")) {
            lastError = line;
        }
    }

    /**
     * 解析 EasyTier 邀请码为 [networkName, networkSecret, peer, inviteToken?].
     * v2 ({@code pmcl2-}) embeds an opaque token; secret is HKDF-derived.
     * Legacy ({@code pmcl-}) still embeds plaintext secret for compatibility.
     */
    public static String[] parseEasyTierInvitation(String code) {
        if (code == null) throw new IllegalArgumentException("邀请码为空");
        String trimmed = code.trim();
        String b64;
        boolean v2 = false;
        if (trimmed.startsWith(INVITE_PREFIX_EASYTIER_V2)) {
            b64 = trimmed.substring(INVITE_PREFIX_EASYTIER_V2.length());
            v2 = true;
        } else if (trimmed.startsWith(INVITE_PREFIX_EASYTIER)) {
            b64 = trimmed.substring(INVITE_PREFIX_EASYTIER.length());
        } else if (trimmed.startsWith(INVITE_PREFIX_CONNECTX)) {
            return parseConnectXInvitation(trimmed);
        } else {
            b64 = trimmed;
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            decoded = Base64.getDecoder().decode(b64);
        }
        String raw = new String(decoded, StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 2) {
            throw new IllegalArgumentException("邀请码内容不完整");
        }
        String name = parts[0];
        String peer = parts.length >= 3 ? parts[2] : "";
        if (name.isEmpty()) {
            throw new IllegalArgumentException("网络名为空");
        }
        if (v2) {
            String tokenB64 = parts[1];
            if (tokenB64.isEmpty()) {
                throw new IllegalArgumentException("邀请令牌为空");
            }
            byte[] token = Base64.getUrlDecoder().decode(tokenB64);
            String secret = deriveEasyTierSecret(token);
            return new String[]{name, secret, peer, tokenB64};
        }
        String secret = parts[1];
        if (secret.isEmpty()) {
            throw new IllegalArgumentException("网络名或密钥为空");
        }
        return new String[]{name, secret, peer, ""};
    }

    /** 兼容旧 API */
    public static String[] parseInvitation(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.startsWith(INVITE_PREFIX_CONNECTX)) {
            return parseConnectXInvitation(trimmed);
        }
        return parseEasyTierInvitation(code);
    }
}
