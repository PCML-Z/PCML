package com.pmcl.core.friend;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 好友系统总管理器：协调身份、发现、聊天、存储各模块。
 * <p>
 * 生命周期：由 {@code LauncherCore} 创建和管理。
 * <p>
 * 使用方式：
 * <pre>{@code
 * FriendManager fm = new FriendManager();
 * fm.initialize();
 * fm.addListener(eventConsumer);
 * fm.start();                // 联机后启动
 * fm.getIdentityManager().getIdentity();  // 获取我的 ID
 * fm.sendMessage(identity, "Hello!");     // 发消息
 * fm.stop();                 // 离开联机后停止
 * }</pre>
 */
public final class FriendManager implements AutoCloseable {

    // ---------------------------------------------------------------------------
    // 状态
    // ---------------------------------------------------------------------------

    /** 好友系统状态 */
    public enum State {
        /** 未初始化 */
        UNINITIALIZED,
        /** 已初始化，等待联机网络 */
        READY,
        /** 聊天服务运行中 */
        RUNNING,
        /** 已停止 */
        STOPPED
    }

    /** 好友请求（待处理） */
    public static final class PendingRequest {
        public final FriendIdentity identity;
        public final String displayName;
        public final String ip;
        public final int port;
        public final long receivedAt;
        /** @deprecated 不再经网络传递 */
        @Deprecated
        public final String authSecret;
        public final byte[] ed25519Pub;
        public final byte[] x25519Pub;

        PendingRequest(FriendIdentity identity, String displayName, String ip, int port) {
            this(identity, displayName, ip, port, null, null, null);
        }

        PendingRequest(FriendIdentity identity, String displayName, String ip, int port, String authSecret) {
            this(identity, displayName, ip, port, authSecret, null, null);
        }

        PendingRequest(FriendIdentity identity, String displayName, String ip, int port,
                       String authSecret, byte[] ed25519Pub, byte[] x25519Pub) {
            this.identity = identity;
            this.displayName = displayName;
            this.ip = ip;
            this.port = port;
            this.receivedAt = System.currentTimeMillis();
            this.authSecret = authSecret;
            this.ed25519Pub = ed25519Pub;
            this.x25519Pub = x25519Pub;
        }
    }

    /** 同一局域网里打开着 PMCL、还没加为好友的人。 */
    public static final class NearbyPeer {
        public final String identity;
        public final String displayName;
        public final String ip;
        public final int port;
        public final byte[] ed25519;
        public final byte[] x25519;
        final long seenAt;

        NearbyPeer(String identity, String displayName, String ip, int port,
                   byte[] ed25519, byte[] x25519, long seenAt) {
            this.identity = identity;
            this.displayName = displayName;
            this.ip = ip;
            this.port = port;
            this.ed25519 = ed25519;
            this.x25519 = x25519;
            this.seenAt = seenAt;
        }
    }

    /** 好友系统事件 */
    public static final class FriendEvent {
        public enum Type {
            STATE_CHANGED,
            FRIEND_ADDED,
            FRIEND_REMOVED,
            FRIEND_ONLINE,
            FRIEND_OFFLINE,
            MESSAGE_RECEIVED,
            FRIEND_REQUEST_RECEIVED,
            PEERS_UPDATED,
            CALL_INVITE_RECEIVED,
            CALL_ACCEPTED,
            CALL_REJECTED,
            CALL_ENDED,
            CALL_ICE_CANDIDATE,
            MP_SESSION,
            GROUP_CHANGED,
            GROUP_JOIN_RESULT,
        }

        public final Type type;
        public final Object data; // FriendEntry, ChatMessage, PendingRequest 等

        FriendEvent(Type type, Object data) {
            this.type = type;
            this.data = data;
        }
    }

    // ---------------------------------------------------------------------------
    // 内部组件
    // ---------------------------------------------------------------------------

    private volatile FriendIdentityManager identityManager;
    private volatile FriendStore store;
    private final FriendChatServer chatServer;
    private final FriendPeerDiscovery discovery;
    private final Map<String, FriendChatClient> activeClients = new ConcurrentHashMap<>();
    /** 连接建立后待执行的操作队列（每个 identity 一个队列，防止 put 覆盖） */
    private final Map<String, Queue<Runnable>> pendingOnConnect = new ConcurrentHashMap<>();
    /** 等待分享者回复的加入请求：groupId → 分享者身份。 */
    private final Map<String, String> pendingGroupJoins = new ConcurrentHashMap<>();
    private final List<Consumer<FriendEvent>> eventListeners = new CopyOnWriteArrayList<>();
    /** 好友发来的联机房间，key = identity */
    private final Map<String, FriendProtocol.MpSession> peerSessions = new ConcurrentHashMap<>();
    /** 同一局域网里还不是好友的 PMCL，超过一段时间没再广播就去掉。 */
    private final Map<String, NearbyPeer> nearby = new ConcurrentHashMap<>();
    private static final long NEARBY_TTL_MS = 20_000L;
    /** 本机正在分享的房间；空邀请码表示已离开 */
    private volatile FriendProtocol.MpSession localSession;
    private static final long MP_SESSION_TTL_MS = 90_000L;

    private volatile State state = State.UNINITIALIZED;
    private Path dataDir;

    // ---------------------------------------------------------------------------
    // 构造 & 生命周期
    // ---------------------------------------------------------------------------

    public FriendManager() {
        this(com.pmcl.core.LauncherConfig.pmclHome().resolve("friend-data"));
    }

    public FriendManager(Path dataDir) {
        this.dataDir = dataDir;
        this.chatServer = new FriendChatServer();
        this.discovery = new FriendPeerDiscovery();
        this.identityManager = new FriendIdentityManager(dataDir);
        this.store = new FriendStore(dataDir);
    }

    /** 初始化：加载身份、好友列表、聊天记录 */
    public void initialize() {
        if (state != State.UNINITIALIZED) return;

        identityManager.initialize();
        store.load();
        store.resetAllOnline(); // 启动时重置在线状态

        // S3: 身份校验——已知好友或本机身份
        chatServer.setIdentityValidator(identity -> {
            if (identity == null || identity.isBlank()) return false;
            if (identityManager.getIdentity() != null
                    && identity.equals(identityManager.getIdentity().toString())) {
                return true;
            }
            return store.isFriend(identity);
        });
        chatServer.setLocalIdentity(identityManager.asLocalIdentity());
        chatServer.setPeerKeyLookup(store::getPeerStaticKeys);
        chatServer.setOnChannelEstablished((peerId, ch) -> {
            // Persist peer long-term keys learned during handshake
            try {
                store.setPeerPublicKeys(peerId, ch.getPeerEdPub(), ch.getPeerXPub());
                byte[] myX = identityManager.getX25519Private();
                if (myX != null) {
                    String secret = FriendCrypto.deriveSharedSecretHex(myX, ch.getPeerXPub());
                    store.setAuthSecret(peerId, secret);
                }
            } catch (Exception e) {
                System.err.println("[FriendManager] 保存对端公钥失败: " + e.getMessage());
            }
        });

        // 监听聊天服务器消息
        chatServer.addListener(new FriendChatServer.MessageListener() {
            @Override
            public void onAuthenticated(String remoteAddr, String identity) {
                // no-op; online status updated via client path / discovery
            }

            @Override
            public void onMessage(String remoteIp, String identity, String jsonLine) {
                handleIncomingMessage(identity, jsonLine, remoteIp);
            }
        });

        // 监听对等发现
        discovery.addListener(peer -> {
            String peerId = peer.identity.toString();
            if (store.isFriend(peerId)) {
                nearby.remove(peerId);
                // 已有活跃客户端时跳过，避免同机器多源 IP（组播/回环）导致反复重连
                if (activeClients.containsKey(peerId)) {
                    fireEvent(FriendEvent.Type.PEERS_UPDATED, peer);
                    return;
                }
                boolean changed = store.updateOnlineStatus(peerId, true, peer.ip, peer.chatPort);
                if (changed) {
                    fireEvent(FriendEvent.Type.FRIEND_ONLINE, store.getFriend(peerId));
                }
                pushLocalSession(peerId);
                // 主动建立 TCP 连接
                if (peer.ip != null && !peer.ip.isEmpty() && peer.chatPort > 0) {
                    getOrCreateClient(peerId, peer.ip, peer.chatPort);
                }
            } else {
                nearby.put(peerId, new NearbyPeer(
                        peerId,
                        peer.displayName,
                        peer.ip,
                        peer.chatPort,
                        peer.ed25519,
                        peer.x25519,
                        peer.discoveredAt));
            }
            fireEvent(FriendEvent.Type.PEERS_UPDATED, peer);
        });

        state = State.READY;
    }

    /**
     * 切换账户：基于 Minecraft 账户 UUID 派生好友身份。
     * <p>
     * 同一账户 UUID 始终派生出同一好友身份（跨设备一致）。
     * 不同账户的身份和数据完全隔离，存储在各自子目录中。
     * 如果派生身份与当前一致，仅更新显示名称；否则切换数据集。
     *
     * @param accountUuid Minecraft 账户 UUID
     * @param displayName 显示名称（账户用户名）
     */
    public void switchAccount(String accountUuid, String displayName) {
        if (accountUuid == null || accountUuid.isEmpty()) return;

        FriendIdentity newId = FriendIdentity.derive("pmcl-friend:" + accountUuid);

        // 身份未变，仅更新名称
        if (identityManager.getIdentity() != null && newId.equals(identityManager.getIdentity())) {
            identityManager.setDisplayName(displayName);
            discovery.updateLocal(displayName, identityManager.getX25519Public());
            ensureLan();
            return;
        }

        // 身份变化，需要切换数据集
        boolean wasRunning = (state == State.RUNNING);
        if (wasRunning) {
            try { stop(); } catch (Exception e) {
                System.err.println("[FriendManager] 切换账户时停止服务失败: " + e.getMessage());
            }
        }

        // 关闭所有活跃连接（它们属于旧身份）
        // H12: 先清除 callback 再 close，避免 close 触发的异步 onDisconnected 回调
        // 引用 this.store（已切换为新 store），污染新账号数据
        for (FriendChatClient client : activeClients.values()) {
            try { client.setCallback(null); } catch (Exception ignored) {}
            try { client.close(); } catch (Exception ignored) {}
        }
        activeClients.clear();
        pendingOnConnect.clear();
        peerSessions.clear();
        nearby.clear();
        localSession = null;

        // 切换到新身份的数据目录
        String idKey = newId.toString().replace("-", "");
        Path newDir = dataDir.resolve(idKey);

        FriendIdentityManager newIdMgr = new FriendIdentityManager(newDir);
        newIdMgr.initialize();
        newIdMgr.setIdentity(newId, displayName);

        FriendStore newStore = new FriendStore(newDir);
        newStore.load();
        newStore.resetAllOnline();

        this.identityManager = newIdMgr;
        this.store = newStore;

        System.out.println("[FriendManager] 已切换到账户身份: " + newId + " (" + displayName + ")");

        if (wasRunning) {
            try { start(); } catch (Exception e) {
                System.err.println("[FriendManager] 切换账户后启动服务失败: " + e.getMessage());
            }
        }
        ensureLan();

        fireEvent(FriendEvent.Type.STATE_CHANGED, state);
    }

    /** 加入联机网络后启动服务 */
    public void start() throws IOException {
        if (state != State.READY && state != State.STOPPED) return;

        try {
            chatServer.setLocalIdentity(identityManager.asLocalIdentity());
            chatServer.setPeerKeyLookup(store::getPeerStaticKeys);
            chatServer.start();
            System.out.println("[FriendManager] 聊天服务器已启动, 端口=" + chatServer.getPort());
        } catch (IOException e) {
            throw new IOException("启动聊天服务器失败: " + e.getMessage(), e);
        }

        try {
            discovery.setSigningKeys(
                    identityManager.getEd25519Private(),
                    identityManager.getEd25519Public());
            discovery.setX25519Public(identityManager.getX25519Public());
            discovery.start(
                    identityManager.getIdentity().toString(),
                    identityManager.getDisplayName(),
                    chatServer.getPort()
            );
        } catch (IOException e) {
            // 回滚：关闭已启动的聊天服务器
            chatServer.close();
            throw new IOException("启动对等发现失败: " + e.getMessage(), e);
        }

        state = State.RUNNING;
        fireEvent(FriendEvent.Type.STATE_CHANGED, state);
    }

    /** 账号已经就绪就保持局域网在线，添加好友才能看到同一网络里的其他 PMCL。 */
    private void ensureLan() {
        if (identityManager.getIdentity() == null) return;
        if (state == State.READY || state == State.STOPPED) {
            try {
                start();
            } catch (IOException e) {
                System.err.println("[FriendManager] 启动局域网发现失败: " + e.getMessage());
            }
        }
    }

    /** 立刻再广播一次，添加好友窗口打开时不用等下一轮。 */
    public void announcePresence() {
        if (state == State.RUNNING) discovery.broadcastNow();
    }

    /** 最近还在广播、并且还不是好友的人。 */
    public List<NearbyPeer> nearbyPeers() {
        long now = System.currentTimeMillis();
        List<NearbyPeer> out = new ArrayList<>();
        for (Map.Entry<String, NearbyPeer> entry : nearby.entrySet()) {
            NearbyPeer peer = entry.getValue();
            if (now - peer.seenAt > NEARBY_TTL_MS || store.isFriend(entry.getKey())) {
                nearby.remove(entry.getKey());
                continue;
            }
            out.add(peer);
        }
        out.sort(Comparator.comparing(peer -> peer.displayName == null
                ? "" : peer.displayName.toLowerCase(Locale.ROOT)));
        return List.copyOf(out);
    }

    /** 离开联机网络时停止 */
    public void stop() {
        if (state != State.RUNNING) return;

        // 清理待发操作
        pendingOnConnect.clear();

        // 断开所有客户端连接
        for (FriendChatClient client : activeClients.values()) {
            try { client.close(); } catch (Exception ignored) {}
        }
        activeClients.clear();
        peerSessions.clear();
        nearby.clear();
        localSession = null;

        // 关闭发现
        discovery.close();

        // 关闭聊天服务器
        chatServer.close();

        // 标记所有好友离线
        store.resetAllOnline();

        state = State.STOPPED;
        fireEvent(FriendEvent.Type.STATE_CHANGED, state);
    }

    @Override
    public void close() {
        stop();
    }

    // ---------------------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------------------

    public State getState() { return state; }
    public FriendIdentityManager getIdentityManager() { return identityManager; }
    public FriendStore getStore() { return store; }
    public int getChatPort() { return chatServer.getPort(); }

    /**
     * 别人用来连接本机聊天服务器的地址。没在监听时返回空字符串。
     * 优先选局域网 IPv4，没有就用回环地址。
     */
    public String localChatAddress() {
        int port = chatServer.getPort();
        if (!chatServer.isRunning() || port <= 0) return "";
        String ip = siteLocalIpv4();
        if (ip == null) ip = "127.0.0.1";
        return ip + ":" + port;
    }

    /** 手动连接对方的聊天服务器。地址形如 {@code 192.168.1.8:54321} 或 {@code [::1]:54321}。 */
    public ChatConnectResult connectChatServer(String address) {
        ChatServerTarget target = parseChatAddress(address);
        if (target == null) return new ChatConnectResult("invalid", "", "");
        if (identityManager.getIdentity() == null) return new ChatConnectResult("unavailable", "", "");
        if (state != State.RUNNING) {
            try {
                start();
            } catch (IOException e) {
                System.err.println("[FriendManager] 连接前启动聊天服务器失败: " + e.getMessage());
                return new ChatConnectResult("unavailable", "", "");
            }
        }
        if (state != State.RUNNING || !chatServer.isRunning()) {
            return new ChatConnectResult("unavailable", "", "");
        }
        if (isOwnChatEndpoint(target)) return new ChatConnectResult("self", "", "");

        String myId = identityManager.getIdentity().toString();
        String myName = identityManager.getDisplayName() == null ? "" : identityManager.getDisplayName();
        FriendChatClient client = new FriendChatClient(target.host, target.port, myId, myName);
        client.setMyChatPort(chatServer.getPort());
        client.setCrypto(identityManager.asLocalIdentity(), null);
        installDirectCallback(client);
        try {
            client.connect();
        } catch (IOException e) {
            System.err.println("[FriendManager] 连接聊天服务器失败 (" + target.host + ":" + target.port
                    + "): " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
            return new ChatConnectResult("failed", "", "");
        }

        FriendSecureChannel channel = client.getChannel();
        String peerId = channel == null ? null : channel.getPeerIdentity();
        if (peerId == null || !FriendIdentity.isValid(peerId) || peerId.equals(myId)) {
            try { client.close(); } catch (Exception ignored) {}
            return new ChatConnectResult("self", "", "");
        }
        byte[] ed = channel.getPeerEdPub();
        byte[] x = channel.getPeerXPub();
        FriendSecureChannel.PeerStaticKeys pinned = store.getPeerStaticKeys(peerId);
        if (pinned != null && (!Arrays.equals(pinned.edPublic, ed) || !Arrays.equals(pinned.xPublic, x))) {
            try { client.close(); } catch (Exception ignored) {}
            return new ChatConnectResult("mismatch", "", "");
        }

        adoptClient(peerId, client);
        boolean known = store.isFriend(peerId);
        if (!known) {
            store.addFriend(peerId, trimPeerName(target.host), target.host, target.port);
            store.setPeerPublicKeys(peerId, ed, x);
            try {
                byte[] myX = identityManager.getX25519Private();
                if (myX != null) {
                    store.setAuthSecret(peerId, FriendCrypto.deriveSharedSecretHex(myX, x));
                }
            } catch (Exception e) {
                System.err.println("[FriendManager] 连接后派生密钥失败: " + e.getMessage());
            }
            store.updateOnlineStatus(peerId, true, target.host, target.port);
            fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(peerId));
            client.sendFriendRequest();
            FriendStore.FriendEntry added = store.getFriend(peerId);
            String addedName = added != null && added.displayName != null ? added.displayName : target.host;
            return new ChatConnectResult("request_sent", addedName, peerId);
        }
        store.setPeerPublicKeys(peerId, ed, x);
        boolean changed = store.updateOnlineStatus(peerId, true, target.host, target.port);
        if (changed) fireEvent(FriendEvent.Type.FRIEND_ONLINE, store.getFriend(peerId));
        pushLocalSession(peerId);
        FriendStore.FriendEntry existing = store.getFriend(peerId);
        String existingName = existing != null && existing.displayName != null ? existing.displayName : target.host;
        return new ChatConnectResult("connected", existingName, peerId);
    }

    /** 解析 {@code 主机:端口}。不合法时返回 null，不发起连接。 */
    public static ChatServerTarget parseChatAddress(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.length() < 3 || text.length() > 300) return null;
        String host;
        String portText;
        if (text.startsWith("[")) {
            int end = text.indexOf(']');
            if (end <= 1 || end + 2 >= text.length() || text.charAt(end + 1) != ':') return null;
            host = text.substring(1, end);
            portText = text.substring(end + 2);
        } else {
            int colon = text.lastIndexOf(':');
            if (colon <= 0 || colon == text.length() - 1) return null;
            host = text.substring(0, colon);
            portText = text.substring(colon + 1);
        }
        if (!isChatHost(host) || !isPortText(portText)) return null;
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) return null;
        return new ChatServerTarget(host, port);
    }

    /** 对方聊天服务器的主机和端口。 */
    public static final class ChatServerTarget {
        public final String host;
        public final int port;

        private ChatServerTarget(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** {@link #connectChatServer} 的结果。code 取 invalid、unavailable、self、mismatch、failed、connected、request_sent。 */
    public static final class ChatConnectResult {
        public final String code;
        public final String peerName;
        public final String identity;

        public ChatConnectResult(String code, String peerName, String identity) {
            this.code = code == null ? "" : code;
            this.peerName = peerName == null ? "" : peerName;
            this.identity = identity == null ? "" : identity;
        }
    }

    /** 添加事件监听器 */
    public void addListener(Consumer<FriendEvent> listener) {
        eventListeners.add(listener);
    }

    /** 移除事件监听器 */
    public void removeListener(Consumer<FriendEvent> listener) {
        eventListeners.remove(listener);
    }

    /** 获取所有好友 */
    public List<FriendStore.FriendEntry> getFriends() {
        return store.getAllFriends();
    }

    /** 获取与某好友的聊天记录 */
    public List<FriendStore.StoredMessage> getMessages(String identity) {
        return store.getMessages(identity);
    }

    // ---------------------------------------------------------------------------
    // 好友操作
    // ---------------------------------------------------------------------------

    /** 通过 IP + port 添加好友（联机时自动发现） */
    public void addFriend(String identity, String displayName, String ip, int port) {
        store.addFriend(identity, displayName, ip, port);
        fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(identity));
    }

    /** 删除好友 */
    public void removeFriend(String identity) {
        store.removeFriend(identity);
        store.removeMemberFromGroups(identity);
        pendingOnConnect.remove(identity);
        FriendChatClient client = activeClients.remove(identity);
        if (client != null) {
            client.close();
        }
        fireEvent(FriendEvent.Type.FRIEND_REMOVED, identity);
    }

    /** 添加待发操作到队列 */
    private void enqueuePending(String identity, Runnable action) {
        pendingOnConnect.computeIfAbsent(identity, k -> new ConcurrentLinkedQueue<>()).add(action);
    }

    /** 如果 TCP 已连通，立即排空待发队列。修复 enqueue 后 client 已连通的竞态窗口 */
    private void flushIfConnected(String identity) {
        FriendChatClient c = activeClients.get(identity);
        if (c != null && c.isConnected()) {
            Queue<Runnable> actions = pendingOnConnect.remove(identity);
            if (actions != null) {
                for (Runnable action : actions) {
                    try {
                        action.run();
                    } catch (Exception e) {
                        System.err.println("[FriendManager] flushIfConnected 待发操作失败 ("
                                + identity + "): " + e.getMessage());
                    }
                }
            }
        }
    }

    /** 发送好友请求到指定 IP + port（密钥经 ECDH 派生，不再明文交换 authSecret） */
    public void sendFriendRequest(String identity, String displayName, String ip, int port) {
        sendFriendRequest(identity, displayName, ip, port, null, null);
    }

    public void sendFriendRequest(String identity, String displayName, String ip, int port,
                                  byte[] ed25519Pub, byte[] x25519Pub) {
        nearby.remove(identity);
        store.addFriend(identity, displayName, ip, port);
        if (ed25519Pub != null && x25519Pub != null) {
            store.setPeerPublicKeys(identity, ed25519Pub, x25519Pub);
            try {
                String secret = FriendCrypto.deriveSharedSecretHex(
                        identityManager.getX25519Private(), x25519Pub);
                store.setAuthSecret(identity, secret);
            } catch (Exception e) {
                System.err.println("[FriendManager] ECDH 派生共享密钥失败: " + e.getMessage());
            }
        }
        fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(identity));

        if (ip != null && !ip.isEmpty() && port > 0) {
            FriendChatClient client = getOrCreateClient(identity, ip, port);
            if (client != null && client.isConnected()) {
                client.sendFriendRequest();
            } else if (client != null) {
                enqueuePending(identity, client::sendFriendRequest);
            }
        } else {
            FriendChatClient existing = activeClients.get(identity);
            if (existing != null && existing.isConnected()) {
                existing.sendFriendRequest();
            } else {
                enqueuePending(identity, () -> {
                    FriendChatClient c = activeClients.get(identity);
                    if (c != null) c.sendFriendRequest();
                });
            }
        }
    }

    /** @deprecated 使用 ECDH；保留方法签名兼容 */
    @Deprecated
    private static String generateSharedAuthSecret() {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    // ---------------------------------------------------------------------------
    // 消息操作
    // ---------------------------------------------------------------------------

    /** 发送文字消息给好友 */
    public void sendMessage(String identity, String text) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null) return;

        String msgId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();

        // 始终本地存储（即使好友离线，消息也不丢失）
        store.addMessage(identity, msgId, text, timestamp, true);

        // 无网络地址则仅本地存储
        if (friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return;

        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        FriendProtocol.ChatMessage msg = new FriendProtocol.ChatMessage();
        msg.id = msgId;
        msg.text = text;
        msg.timestamp = timestamp;
        msg.from = identityManager.getIdentity().toString();
        msg.fromName = identityManager.getDisplayName();

        sendJson(identity, friend, client, msg.toJson());
    }

    /** 本地聊天组。 */
    public List<FriendStore.GroupEntry> getGroups() {
        return store.getGroups();
    }

    /**
     * 用当前好友建一个聊天组，并把成员名单发给每个被选中的好友。
     * @return 新建的组；名称或成员不合法时返回 null
     */
    public FriendStore.GroupEntry createGroup(String name, List<String> friendIds) {
        String cleanName = cleanGroupName(name);
        if (cleanName == null) return null;
        String myId = identityManager.getIdentity().toString();
        LinkedHashSet<String> members = new LinkedHashSet<>();
        members.add(myId);
        if (friendIds != null) {
            for (String id : friendIds) {
                if (id == null || !store.isFriend(id)) continue;
                try {
                    members.add(FriendIdentity.parse(id).toString());
                } catch (IllegalArgumentException ignored) {}
                if (members.size() >= 12) break;
            }
        }
        if (members.size() < 2) return null;
        FriendStore.GroupEntry group = new FriendStore.GroupEntry();
        group.id = UUID.randomUUID().toString();
        group.name = cleanName;
        group.members = new ArrayList<>(members);
        group.createdAt = System.currentTimeMillis();
        store.putGroup(group);
        broadcastGroup(group);
        fireEvent(FriendEvent.Type.GROUP_CHANGED, group);
        return group;
    }

    /** 退出聊天组，并通知其他成员。 */
    public void leaveGroup(String groupId) {
        FriendStore.GroupEntry group = store.getGroup(groupId);
        if (group == null) return;
        String myId = identityManager.getIdentity().toString();
        FriendProtocol.GroupLeave leave = new FriendProtocol.GroupLeave();
        leave.groupId = groupId;
        String json = leave.toJson();
        for (String member : new ArrayList<>(group.members)) {
            if (member.equals(myId)) continue;
            sendToFriend(member, json);
        }
        store.removeGroup(groupId);
        fireEvent(FriendEvent.Type.GROUP_CHANGED, groupId);
    }

    /**
     * 用群二维码向分享者请求加入。分享者必须已经是好友。
     * @return sent、already、self、not_friend 或 invalid
     */
    public String requestJoinGroup(String groupId, String sharerId) {
        String gid = canonicalGroupId(groupId);
        if (gid == null || !FriendIdentity.isValid(sharerId)) return "invalid";
        String sharer;
        try {
            sharer = FriendIdentity.parse(sharerId).toString();
        } catch (IllegalArgumentException e) {
            return "invalid";
        }
        String myId = identityManager.getIdentity().toString();
        FriendStore.GroupEntry existing = store.getGroup(gid);
        if (existing != null && existing.members != null && existing.members.contains(myId)) return "already";
        if (sharer.equals(myId)) return "self";
        if (!store.isFriend(sharer)) return "not_friend";
        FriendProtocol.GroupJoin join = new FriendProtocol.GroupJoin();
        join.groupId = gid;
        pendingGroupJoins.put(gid, sharer);
        sendToFriend(sharer, join.toJson());
        return "sent";
    }

    /** 向聊天组里其他好友发送一条消息。离线成员只在对方上线且连接恢复时收不到这条。 */
    public void sendGroupMessage(String groupId, String text) {
        FriendStore.GroupEntry group = store.getGroup(groupId);
        if (group == null) return;
        String body = text == null ? "" : text.trim();
        if (body.isEmpty() || body.length() > 2000) return;
        String myId = identityManager.getIdentity().toString();
        String msgId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();
        String myName = identityManager.getDisplayName();
        store.addMessage(FriendStore.groupKey(groupId), msgId, body, timestamp, true, myId, myName);

        FriendProtocol.GroupMessage msg = new FriendProtocol.GroupMessage();
        msg.groupId = groupId;
        msg.id = msgId;
        msg.text = body;
        msg.timestamp = timestamp;
        msg.fromName = myName;
        String json = msg.toJson();
        for (String member : group.members) {
            if (member.equals(myId)) continue;
            sendToFriend(member, json);
        }
    }

    private void broadcastGroup(FriendStore.GroupEntry group) {
        FriendProtocol.GroupUpsert upsert = new FriendProtocol.GroupUpsert();
        upsert.groupId = group.id;
        upsert.name = group.name;
        upsert.members = group.members;
        String json = upsert.toJson();
        String myId = identityManager.getIdentity().toString();
        for (String member : group.members) {
            if (member.equals(myId)) continue;
            sendToFriend(member, json);
        }
    }

    private void sendToFriend(String identity, String json) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null || friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return;
        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        sendJson(identity, friend, client, json);
    }

    private void handleGroupUpsert(String channelIdentity, String jsonLine) {
        if (!store.isFriend(channelIdentity)) return;
        FriendProtocol.GroupUpsert upsert = FriendProtocol.GroupUpsert.fromJson(jsonLine);
        String groupId = canonicalGroupId(upsert == null ? null : upsert.groupId);
        if (groupId == null) return;
        upsert.groupId = groupId;
        String myId = identityManager.getIdentity().toString();
        List<String> members = sanitizeMembers(upsert.members, myId, channelIdentity);
        if (members == null) return;
        String name = cleanGroupName(upsert.name);
        if (name == null) return;
        FriendStore.GroupEntry existing = store.getGroup(upsert.groupId);
        if (existing != null && (existing.members == null || !existing.members.contains(channelIdentity))) {
            return;
        }
        FriendStore.GroupEntry group = existing != null ? existing : new FriendStore.GroupEntry();
        group.id = upsert.groupId;
        group.name = name;
        group.members = members;
        if (group.createdAt <= 0) group.createdAt = System.currentTimeMillis();
        store.putGroup(group);
        fireEvent(FriendEvent.Type.GROUP_CHANGED, group);
    }

    private void handleGroupMessage(String channelIdentity, String jsonLine) {
        if (!store.isFriend(channelIdentity)) return;
        FriendProtocol.GroupMessage msg = FriendProtocol.GroupMessage.fromJson(jsonLine);
        String groupId = canonicalGroupId(msg == null ? null : msg.groupId);
        if (groupId == null || msg.id == null || msg.id.isBlank() || msg.id.length() > 64) return;
        msg.groupId = groupId;
        String body = msg.text == null ? "" : msg.text.trim();
        if (body.isEmpty() || body.length() > 2000) return;
        FriendStore.GroupEntry group = store.getGroup(msg.groupId);
        if (group == null || group.members == null || !group.members.contains(channelIdentity)) return;
        FriendStore.FriendEntry sender = store.getFriend(channelIdentity);
        String name = sender != null && sender.displayName != null && !sender.displayName.isBlank()
                ? sender.displayName : channelIdentity;
        store.addMessage(FriendStore.groupKey(msg.groupId), msg.id, body, msg.timestamp, false,
                channelIdentity, name);
        fireEvent(FriendEvent.Type.MESSAGE_RECEIVED, msg);
    }

    private void handleGroupLeave(String channelIdentity, String jsonLine) {
        if (!store.isFriend(channelIdentity)) return;
        FriendProtocol.GroupLeave leave = FriendProtocol.GroupLeave.fromJson(jsonLine);
        String groupId = canonicalGroupId(leave == null ? null : leave.groupId);
        if (groupId == null) return;
        leave.groupId = groupId;
        FriendStore.GroupEntry group = store.getGroup(leave.groupId);
        if (group == null || group.members == null) return;
        if (group.members.remove(channelIdentity)) {
            store.putGroup(group);
            fireEvent(FriendEvent.Type.GROUP_CHANGED, group);
        }
    }

    private void handleGroupJoin(String channelIdentity, String jsonLine) {
        if (!store.isFriend(channelIdentity)) return;
        FriendProtocol.GroupJoin join = FriendProtocol.GroupJoin.fromJson(jsonLine);
        String groupId = canonicalGroupId(join == null ? null : join.groupId);
        if (groupId == null) return;
        String myId = identityManager.getIdentity().toString();
        FriendStore.GroupEntry group = store.getGroup(groupId);
        if (group == null || group.members == null || !group.members.contains(myId)) {
            replyJoin(channelIdentity, groupId, "unknown");
            return;
        }
        String requester;
        try {
            requester = FriendIdentity.parse(channelIdentity).toString();
        } catch (IllegalArgumentException e) {
            return;
        }
        if (group.members.contains(requester)) {
            broadcastGroup(group);
            replyJoin(channelIdentity, groupId, "ok");
            return;
        }
        if (group.members.size() >= 12) {
            replyJoin(channelIdentity, groupId, "full");
            return;
        }
        List<String> next = new ArrayList<>(group.members);
        next.add(requester);
        List<String> members = sanitizeMembers(next, myId, requester);
        if (members == null) {
            replyJoin(channelIdentity, groupId, "full");
            return;
        }
        group.members = members;
        store.putGroup(group);
        broadcastGroup(group);
        replyJoin(channelIdentity, groupId, "ok");
        fireEvent(FriendEvent.Type.GROUP_CHANGED, group);
    }

    private void handleGroupJoinResult(String channelIdentity, String jsonLine) {
        if (!store.isFriend(channelIdentity)) return;
        FriendProtocol.GroupJoinResult result = FriendProtocol.GroupJoinResult.fromJson(jsonLine);
        String groupId = canonicalGroupId(result == null ? null : result.groupId);
        if (groupId == null || result.status == null) return;
        String status = result.status;
        if (!status.equals("ok") && !status.equals("full") && !status.equals("unknown")) return;
        String expected = pendingGroupJoins.get(groupId);
        if (expected == null) return;
        try {
            if (!FriendIdentity.parse(expected).toString().equals(FriendIdentity.parse(channelIdentity).toString())) {
                return;
            }
        } catch (IllegalArgumentException e) {
            return;
        }
        pendingGroupJoins.remove(groupId);
        fireEvent(FriendEvent.Type.GROUP_JOIN_RESULT, status);
    }

    private void replyJoin(String identity, String groupId, String status) {
        FriendProtocol.GroupJoinResult result = new FriendProtocol.GroupJoinResult();
        result.groupId = groupId;
        result.status = status;
        sendToFriend(identity, result.toJson());
    }

    private static String canonicalGroupId(String id) {
        if (id == null) return null;
        try {
            return UUID.fromString(id).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static List<String> sanitizeMembers(List<String> raw, String requiredA, String requiredB) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (raw != null) {
            for (String id : raw) {
                if (id == null) continue;
                try {
                    out.add(FriendIdentity.parse(id).toString());
                } catch (IllegalArgumentException ignored) {}
                if (out.size() >= 12) break;
            }
        }
        if (!out.contains(requiredA) || !out.contains(requiredB) || out.size() < 2) return null;
        return new ArrayList<>(out);
    }

    private void sendJson(String identity, FriendStore.FriendEntry friend, FriendChatClient client, String json) {
        if (chatServer.sendTo(identity, json)) return;
        if (client != null && client.isConnected()) {
            client.send(json);
        } else if (client != null) {
            enqueuePending(identity, () -> client.send(json));
        }
    }

    private static String cleanGroupName(String name) {
        if (name == null) return null;
        String clean = name.trim();
        if (clean.isEmpty() || clean.length() > 24) return null;
        return clean;
    }

    /** 接受好友请求 */
    public void acceptFriendRequest(PendingRequest request) {
        String peerId = request.identity.toString();
        nearby.remove(peerId);
        store.addFriend(peerId, request.displayName, request.ip, request.port);
        if (request.ed25519Pub != null && request.x25519Pub != null) {
            store.setPeerPublicKeys(peerId, request.ed25519Pub, request.x25519Pub);
            try {
                store.setAuthSecret(peerId, FriendCrypto.deriveSharedSecretHex(
                        identityManager.getX25519Private(), request.x25519Pub));
            } catch (Exception ignored) {}
        }
        fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(peerId));

        if (chatServer.sendTo(peerId, friendAckJson(true))) return;
        if (request.ip != null && !request.ip.isEmpty() && request.port > 0) {
            FriendChatClient client = getOrCreateClient(peerId, request.ip, request.port);
            if (client != null && client.isConnected()) {
                client.sendFriendAck(peerId, true);
            } else if (client != null) {
                enqueuePending(peerId, () -> client.sendFriendAck(peerId, true));
            }
        }
    }

    /** 拒绝好友请求 */
    public void rejectFriendRequest(PendingRequest request) {
        if (chatServer.sendTo(request.identity.toString(), friendAckJson(false))) return;
        if (request.ip != null && !request.ip.isEmpty() && request.port > 0) {
            FriendChatClient client = getOrCreateClient(request.identity.toString(), request.ip, request.port);
            if (client != null && client.isConnected()) {
                client.sendFriendAck(request.identity.toString(), false);
            } else if (client != null) {
                enqueuePending(request.identity.toString(),
                        () -> client.sendFriendAck(request.identity.toString(), false));
            }
        }
    }

    /**
     * 把当前联机房间发给在线好友。邀请码为空时通知对方清除。
     * 只通过已有加密连接发送。
     */
    public void publishMultiplayer(String backend, String invitation, String mcHost, int mcPort) {
        String myId = identityManager.getIdentity() != null
                ? identityManager.getIdentity().toString() : "";
        FriendProtocol.MpSession raw = new FriendProtocol.MpSession();
        raw.backend = backend;
        raw.invitation = invitation;
        raw.mcHost = mcHost;
        raw.mcPort = mcPort;
        FriendProtocol.MpSession clean = FriendProtocol.MpSession.sanitize(raw, myId);
        if (clean == null) {
            if (localSession != null) {
                localSession = null;
                broadcastSession(clearedSession(myId));
            }
            return;
        }
        localSession = clean;
        broadcastSession(clean);
    }

    /** 当前仍有效的好友房间。 */
    public List<FriendProtocol.MpSession> getMultiplayerSessions() {
        long now = System.currentTimeMillis();
        List<FriendProtocol.MpSession> out = new ArrayList<>();
        for (FriendProtocol.MpSession s : peerSessions.values()) {
            if (s == null || s.from == null) continue;
            if (now - s.ts > MP_SESSION_TTL_MS) continue;
            if (s.invitation == null || s.invitation.isBlank()) continue;
            out.add(s);
        }
        return out;
    }

    private FriendProtocol.MpSession clearedSession(String myId) {
        FriendProtocol.MpSession s = new FriendProtocol.MpSession();
        s.from = myId;
        s.invitation = "";
        s.backend = "";
        return s;
    }

    private void broadcastSession(FriendProtocol.MpSession session) {
        if (session == null) return;
        String json = session.toJson();
        for (FriendStore.FriendEntry friend : store.getAllFriends()) {
            sendSession(friend.identity, json);
        }
    }

    private void pushLocalSession(String identity) {
        FriendProtocol.MpSession session = localSession;
        if (session == null || identity == null) return;
        sendSession(identity, session.toJson());
    }

    private void sendSession(String identity, String json) {
        if (chatServer.sendTo(identity, json)) return;
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null || friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return;
        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        if (client == null) return;
        if (client.isConnected()) client.send(json);
        else enqueuePending(identity, () -> client.send(json));
    }

    /** 广播发现（手动触发） */
    public void broadcastDiscovery() {
        if (state == State.RUNNING) {
            discovery.broadcastNow();
        }
    }

    // ---------------------------------------------------------------------------
    // 通话操作（信令层，不涉及实际媒体）
    // ---------------------------------------------------------------------------

    /** 发起通话邀请，返回生成的 callId（好友不存在或无网络地址时返回 null） */
    public String sendCallInvite(String identity, String mediaType, int videoPort) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null) {
            System.err.println("[FriendManager] sendCallInvite 失败：好友不存在 identity=" + identity);
            return null;
        }

        if (friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) {
            System.err.println("[FriendManager] sendCallInvite 失败：好友无网络地址 identity=" + identity
                    + " ip=" + friend.lastIp + " port=" + friend.lastPort);
            return null;
        }

        String callId = UUID.randomUUID().toString();
        FriendProtocol.CallInvite invite = new FriendProtocol.CallInvite();
        invite.callId = callId;
        invite.from = identityManager.getIdentity().toString();
        invite.fromName = identityManager.getDisplayName();
        invite.mediaType = mediaType;
        invite.videoPort = videoPort;

        if (friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return null;

        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        if (client != null && client.isConnected()) {
            client.send(invite.toJson());
        } else if (client != null) {
            enqueuePending(identity, () -> client.send(invite.toJson()));
            flushIfConnected(identity);
        }
        return callId;
    }

    /** 接受通话 */
    public void sendCallAccept(String identity, String callId, String sdpOffer, int videoPort) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null) return;

        FriendProtocol.CallAccept accept = new FriendProtocol.CallAccept();
        accept.callId = callId;
        accept.from = identityManager.getIdentity().toString();
        accept.accept = true;
        accept.sdpOffer = sdpOffer != null ? sdpOffer : "";
        accept.videoPort = videoPort;

        if (friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return;

        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        if (client != null && client.isConnected()) {
            client.send(accept.toJson());
        } else if (client != null) {
            enqueuePending(identity, () -> client.send(accept.toJson()));
            flushIfConnected(identity);
        }
    }

    /** 拒绝通话 */
    public void sendCallReject(String identity, String callId, String reason) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null) return;

        FriendProtocol.CallReject reject = new FriendProtocol.CallReject();
        reject.callId = callId;
        reject.from = identityManager.getIdentity().toString();
        reject.reason = reason != null ? reason : "";

        if (friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return;

        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        if (client != null && client.isConnected()) {
            client.send(reject.toJson());
        } else if (client != null) {
            enqueuePending(identity, () -> client.send(reject.toJson()));
            flushIfConnected(identity);
        }
    }

    /** 结束通话 */
    public void sendCallEnd(String identity, String callId, String reason) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null) return;

        FriendProtocol.CallEnd end = new FriendProtocol.CallEnd();
        end.callId = callId;
        end.from = identityManager.getIdentity().toString();
        end.reason = reason != null ? reason : "";

        if (friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return;

        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        if (client != null && client.isConnected()) {
            client.send(end.toJson());
        } else if (client != null) {
            enqueuePending(identity, () -> client.send(end.toJson()));
            flushIfConnected(identity);
        }
    }

    /** 发送 ICE 候选 */
    public void sendCallIceCandidate(String identity, String callId, String candidate, int sdpMLineIndex, String sdpMid,
                                     String ufrag, String pwd) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null) return;

        FriendProtocol.CallIceCandidate ice = new FriendProtocol.CallIceCandidate();
        ice.callId = callId;
        ice.from = identityManager.getIdentity().toString();
        ice.candidate = candidate != null ? candidate : "";
        ice.sdpMLineIndex = sdpMLineIndex;
        ice.sdpMid = sdpMid != null ? sdpMid : "";
        ice.ufrag = ufrag != null ? ufrag : "";
        ice.pwd = pwd != null ? pwd : "";

        if (friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return;

        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        if (client != null && client.isConnected()) {
            client.send(ice.toJson());
        } else if (client != null) {
            enqueuePending(identity, () -> client.send(ice.toJson()));
            flushIfConnected(identity);
        }
    }

    // ---------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------

    /**
     * @param channelIdentity authenticated peer identity from secure-channel handshake
     *                        (never trust JSON {@code from} for attribution)
     */
    private void handleIncomingMessage(String channelIdentity, String jsonLine, String remoteIp) {
        String type = FriendProtocol.peekType(jsonLine);
        if (type == null) return;
        if (channelIdentity == null || channelIdentity.isBlank()) return;

        String myId = identityManager.getIdentity().toString();
        // H12: always attribute to handshake identity; ignore spoofable JSON "from"
        if (channelIdentity.equals(myId)) return;

        switch (type) {
            case "group_upsert" -> handleGroupUpsert(channelIdentity, jsonLine);
            case "group_msg" -> handleGroupMessage(channelIdentity, jsonLine);
            case "group_leave" -> handleGroupLeave(channelIdentity, jsonLine);
            case "group_join" -> handleGroupJoin(channelIdentity, jsonLine);
            case "group_join_result" -> handleGroupJoinResult(channelIdentity, jsonLine);
            case "msg" -> {
                FriendProtocol.ChatMessage msg = FriendProtocol.ChatMessage.fromJson(jsonLine);
                if (msg.text != null && msg.id != null && store.isFriend(channelIdentity)) {
                    msg.from = channelIdentity;
                    store.addMessage(channelIdentity, msg.id, msg.text, msg.timestamp, false);
                    fireEvent(FriendEvent.Type.MESSAGE_RECEIVED, msg);
                }
            }
            case "friend_req" -> {
                FriendProtocol.FriendRequest req = FriendProtocol.FriendRequest.fromJson(jsonLine);
                // Claimed identity in payload must match authenticated channel peer
                if (req.identity == null || !req.identity.equals(channelIdentity)) {
                    System.err.println("[FriendManager] friend_req identity mismatch channel="
                            + channelIdentity + " claim=" + req.identity);
                    return;
                }
                byte[] ed = null;
                byte[] x = null;
                try {
                    if (req.ed25519Pub != null && !req.ed25519Pub.isBlank())
                        ed = FriendCrypto.b64d(req.ed25519Pub);
                    if (req.x25519Pub != null && !req.x25519Pub.isBlank())
                        x = FriendCrypto.b64d(req.x25519Pub);
                } catch (Exception ignored) {}
                if (ed != null && x != null) {
                    store.setPeerPublicKeys(channelIdentity, ed, x);
                    try {
                        store.setAuthSecret(channelIdentity, FriendCrypto.deriveSharedSecretHex(
                                identityManager.getX25519Private(), x));
                    } catch (Exception ignored) {}
                }
                if (store.isFriend(channelIdentity)) {
                    FriendStore.FriendEntry existing = store.getFriend(channelIdentity);
                    int ackPort = req.port > 0 ? req.port : (existing != null ? existing.lastPort : 0);
                    String ackIp = firstAddress(remoteIp, existing != null ? existing.lastIp : null);
                    store.addFriend(channelIdentity,
                            req.name != null ? req.name : channelIdentity, ackIp, ackPort);
                    if (req.name != null && !req.name.isBlank()) store.setDisplayName(channelIdentity, req.name);
                    if (!chatServer.sendTo(channelIdentity, friendAckJson(true))) {
                        FriendChatClient client = getOrCreateClient(channelIdentity, ackIp, ackPort);
                        if (client != null && client.isConnected()) {
                            client.sendFriendAck(channelIdentity, true);
                        } else if (client != null) {
                            enqueuePending(channelIdentity,
                                    () -> client.sendFriendAck(channelIdentity, true));
                        }
                    }
                } else {
                    FriendIdentity peerId = FriendIdentity.parse(channelIdentity);
                    FriendStore.FriendEntry existing = store.getFriend(channelIdentity);
                    String reqIp = firstAddress(remoteIp, existing != null ? existing.lastIp : null);
                    int peerPort = req.port > 0 ? req.port : (existing != null ? existing.lastPort : 0);
                    PendingRequest pending = new PendingRequest(peerId,
                            req.name != null ? req.name : channelIdentity,
                            reqIp, peerPort, null, ed, x);
                    fireEvent(FriendEvent.Type.FRIEND_REQUEST_RECEIVED, pending);
                }
            }
            case "friend_ack" -> {
                FriendProtocol.FriendAck ack = FriendProtocol.FriendAck.fromJson(jsonLine);
                // Ack is from the peer on this channel (the one accepting/rejecting our request)
                if (ack.accepted) {
                    FriendStore.FriendEntry existing = store.getFriend(channelIdentity);
                    String ackIp = existing != null && existing.lastIp != null ? existing.lastIp : "";
                    int ackPort = ack.port > 0 ? ack.port : (existing != null ? existing.lastPort : 0);
                    String name = ack.name != null ? ack.name
                            : (existing != null ? existing.displayName : channelIdentity);
                    store.addFriend(channelIdentity, name, ackIp, ackPort);
                    if (ack.name != null && !ack.name.isBlank()) store.setDisplayName(channelIdentity, ack.name);
                    try {
                        if (ack.ed25519Pub != null && ack.x25519Pub != null) {
                            byte[] ed = FriendCrypto.b64d(ack.ed25519Pub);
                            byte[] x = FriendCrypto.b64d(ack.x25519Pub);
                            store.setPeerPublicKeys(channelIdentity, ed, x);
                            store.setAuthSecret(channelIdentity, FriendCrypto.deriveSharedSecretHex(
                                    identityManager.getX25519Private(), x));
                        }
                    } catch (Exception ignored) {}
                    fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(channelIdentity));
                } else {
                    System.err.println("[FriendManager] 好友请求被拒绝 (" + channelIdentity + ")");
                }
            }
            case "mp_session" -> {
                if (!store.isFriend(channelIdentity)) return;
                FriendProtocol.MpSession incoming = FriendProtocol.MpSession.fromJson(jsonLine);
                FriendProtocol.MpSession clean = FriendProtocol.MpSession.sanitize(incoming, channelIdentity);
                if (clean == null || clean.invitation == null || clean.invitation.isBlank()) {
                    if (peerSessions.remove(channelIdentity) != null) {
                        fireEvent(FriendEvent.Type.MP_SESSION, null);
                    }
                    return;
                }
                peerSessions.put(channelIdentity, clean);
                fireEvent(FriendEvent.Type.MP_SESSION, clean);
            }
            case "status" -> {
                FriendProtocol.StatusMessage status = FriendProtocol.StatusMessage.fromJson(jsonLine);
                if (store.isFriend(channelIdentity)) {
                    FriendStore.FriendEntry entry = store.getFriend(channelIdentity);
                    if (entry != null) {
                        boolean changed = store.updateOnlineStatus(channelIdentity, status.online,
                                entry.lastIp, entry.lastPort);
                        if (changed) {
                            fireEvent(status.online ? FriendEvent.Type.FRIEND_ONLINE
                                    : FriendEvent.Type.FRIEND_OFFLINE, entry);
                        }
                    }
                }
            }
            case "call_invite" -> {
                FriendProtocol.CallInvite invite = FriendProtocol.CallInvite.fromJson(jsonLine);
                invite.from = channelIdentity;
                fireEvent(FriendEvent.Type.CALL_INVITE_RECEIVED, invite);
            }
            case "call_accept" -> {
                FriendProtocol.CallAccept accept = FriendProtocol.CallAccept.fromJson(jsonLine);
                accept.from = channelIdentity;
                fireEvent(FriendEvent.Type.CALL_ACCEPTED, accept);
            }
            case "call_reject" -> {
                FriendProtocol.CallReject reject = FriendProtocol.CallReject.fromJson(jsonLine);
                reject.from = channelIdentity;
                fireEvent(FriendEvent.Type.CALL_REJECTED, reject);
            }
            case "call_end" -> {
                FriendProtocol.CallEnd end = FriendProtocol.CallEnd.fromJson(jsonLine);
                end.from = channelIdentity;
                fireEvent(FriendEvent.Type.CALL_ENDED, end);
            }
            case "call_ice" -> {
                FriendProtocol.CallIceCandidate ice = FriendProtocol.CallIceCandidate.fromJson(jsonLine);
                ice.from = channelIdentity;
                fireEvent(FriendEvent.Type.CALL_ICE_CANDIDATE, ice);
            }
        }
    }

    /**
     * Derive AES-GCM media key for a video call with a friend (SRTP-like).
     */
    public byte[] deriveMediaKey(String peerIdentity, String callId) {
        FriendSecureChannel.PeerStaticKeys peer = store.getPeerStaticKeys(peerIdentity);
        if (peer == null || identityManager.getX25519Private() == null) {
            throw new IllegalStateException("missing peer/local X25519 keys for media encryption");
        }
        return FriendCrypto.deriveMediaKey(identityManager.getX25519Private(), peer.xPublic, callId);
    }

    private FriendChatClient getOrCreateClient(String identity, String ip, int port) {
        if (ip == null || ip.isEmpty() || port <= 0) {
            return null;
        }
        // H2: 不能在 ConcurrentHashMap.compute 内关闭旧 client
        // 旧 client.close() 会触发 callback.onDisconnected，回调里调用 activeClients.remove，
        // 而 compute 持有段锁期间修改 map 会死锁
        FriendChatClient toClose = null;
        FriendChatClient result = null;
        synchronized (activeClients) {
            FriendChatClient existing = activeClients.get(identity);
            if (existing != null) {
                // 地址未变且连接活跃，复用
                if (existing.getRemoteHost().equals(ip) && existing.getRemotePort() == port
                        && existing.isConnected()) {
                    return existing;
                }
                // 地址已变或连接已断开：先从 map 移除，close 操作放到锁外执行
                toClose = existing;
                activeClients.remove(identity, existing);
            }
            FriendChatClient client = new FriendChatClient(ip, port,
                    identityManager.getIdentity().toString(),
                    identityManager.getDisplayName());
            client.setMyChatPort(chatServer.getPort());
            client.setCrypto(identityManager.asLocalIdentity(), store.getPeerStaticKeys(identity));
            setupClient(identity, client);
            activeClients.put(identity, client);
            result = client;
        }
        // 锁外关闭旧 client，避免回调死锁
        if (toClose != null) {
            try { toClose.close(); } catch (Exception ignored) {}
        }
        // 锁外启动异步连接，避免持锁时间过长
        if (result != null) {
            result.connectAsync();
        }
        return result;
    }

    private void setupClient(String identity, FriendChatClient client) {
        client.setCallback(new FriendChatClient.MessageCallback() {
            @Override
            public void onMessageReceived(String jsonLine) {
                handleIncomingMessage(identity, jsonLine, null);
            }

            @Override
            public void onDisconnected(String reason) {
                // 条件删除：仅当 map 中的 client 仍是当前 client 时才删除
                // 避免旧 client 的异步回调误删已建立的新 client
                activeClients.remove(identity, client);
                peerSessions.remove(identity);
                fireEvent(FriendEvent.Type.MP_SESSION, null);
                boolean changed = store.updateOnlineStatus(identity, false, "", 0);
                if (changed) {
                    FriendStore.FriendEntry entry = store.getFriend(identity);
                    if (entry != null) {
                        fireEvent(FriendEvent.Type.FRIEND_OFFLINE, entry);
                    }
                }
            }

            @Override
            public void onConnected() {
                boolean changed = store.updateOnlineStatus(identity, true,
                        client.getRemoteHost(), client.getRemotePort());
                if (changed) {
                    fireEvent(FriendEvent.Type.FRIEND_ONLINE, store.getFriend(identity));
                }
                pushLocalSession(identity);
                // 排空待发队列
                Queue<Runnable> actions = pendingOnConnect.remove(identity);
                if (actions != null) {
                    for (Runnable action : actions) {
                        try {
                            action.run();
                        } catch (Exception e) {
                            System.err.println("[FriendManager] 待发操作失败 (" + identity + "): " + e.getMessage());
                        }
                    }
                }
            }

            @Override
            public void onConnectFailed(String reason) {
                activeClients.remove(identity);
                // 不清除 pendingOnConnect：保留待发操作，等发现后重连时自动发送
                System.err.println("[FriendManager] 连接好友失败 (" + identity + "): " + reason);
            }
        });
    }

    private void installDirectCallback(FriendChatClient client) {
        client.setCallback(new FriendChatClient.MessageCallback() {
            private String peerId() {
                FriendSecureChannel channel = client.getChannel();
                return channel == null ? null : channel.getPeerIdentity();
            }

            @Override
            public void onMessageReceived(String jsonLine) {
                String id = peerId();
                if (id != null) handleIncomingMessage(id, jsonLine, null);
            }

            @Override
            public void onDisconnected(String reason) {
                String id = peerId();
                if (id == null) return;
                if (!activeClients.remove(id, client)) return;
                peerSessions.remove(id);
                fireEvent(FriendEvent.Type.MP_SESSION, null);
                boolean changed = store.updateOnlineStatus(id, false, "", 0);
                if (changed) {
                    FriendStore.FriendEntry entry = store.getFriend(id);
                    if (entry != null) fireEvent(FriendEvent.Type.FRIEND_OFFLINE, entry);
                }
            }

            @Override
            public void onConnected() {}

            @Override
            public void onConnectFailed(String reason) {}
        });
    }

    private void adoptClient(String identity, FriendChatClient client) {
        FriendChatClient previous;
        synchronized (activeClients) {
            previous = activeClients.put(identity, client);
        }
        if (previous != null && previous != client) {
            try { previous.setCallback(null); } catch (Exception ignored) {}
            try { previous.close(); } catch (Exception ignored) {}
        }
    }

    private String friendAckJson(boolean accepted) {
        FriendProtocol.FriendAck ack = new FriendProtocol.FriendAck();
        FriendIdentity me = identityManager.getIdentity();
        ack.identity = me == null ? "" : me.toString();
        ack.name = identityManager.getDisplayName();
        ack.accepted = accepted;
        ack.port = chatServer.getPort();
        try {
            FriendSecureChannel.LocalIdentity local = identityManager.asLocalIdentity();
            if (local.edPublic() != null && local.xPublic() != null) {
                ack.ed25519Pub = FriendCrypto.b64(local.edPublic());
                ack.x25519Pub = FriendCrypto.b64(local.xPublic());
            }
        } catch (Exception ignored) {}
        return ack.toJson();
    }

    private boolean isOwnChatEndpoint(ChatServerTarget target) {
        if (target.port != chatServer.getPort()) return false;
        String host = target.host.toLowerCase(Locale.ROOT);
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("0.0.0.0") || host.equals("::")) {
            return true;
        }
        if (!isNumericAddress(host)) return false;
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()) return true;
            return NetworkInterface.getByInetAddress(addr) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isChatHost(String host) {
        if (host == null || host.isEmpty() || host.length() > 253) return false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == ':' || c == '_';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean isPortText(String portText) {
        if (portText == null || portText.isEmpty() || portText.length() > 5) return false;
        for (int i = 0; i < portText.length(); i++) {
            char c = portText.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static boolean isNumericAddress(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!((c >= '0' && c <= '9') || c == '.' || c == ':')) return false;
        }
        return true;
    }

    private static String firstAddress(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) return preferred.trim();
        if (fallback != null && !fallback.isBlank()) return fallback.trim();
        return "";
    }

    private static String trimPeerName(String name) {
        if (name == null) return "";
        String clean = name.trim();
        if (clean.length() > 48) clean = clean.substring(0, 48);
        return clean;
    }

    private static String siteLocalIpv4() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) return null;
            String fallback = null;
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String host = addr.getHostAddress();
                    if (host == null || host.isBlank()) continue;
                    if (addr.isSiteLocalAddress()) return host;
                    if (fallback == null) fallback = host;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private void fireEvent(FriendEvent.Type type, Object data) {
        FriendEvent event = new FriendEvent(type, data);
        for (Consumer<FriendEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                System.err.println("[FriendManager] 事件处理异常: " + e.getMessage());
            }
        }
    }
}
