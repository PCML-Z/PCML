package com.pmcl.core.friend;

import java.io.IOException;
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
    private final List<Consumer<FriendEvent>> eventListeners = new CopyOnWriteArrayList<>();

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
                handleIncomingMessage(identity, jsonLine);
            }
        });

        // 监听对等发现
        discovery.addListener(peer -> {
            String peerId = peer.identity.toString();
            if (store.isFriend(peerId)) {
                // 已有活跃客户端时跳过，避免同机器多源 IP（组播/回环）导致反复重连
                if (activeClients.containsKey(peerId)) {
                    fireEvent(FriendEvent.Type.PEERS_UPDATED, peer);
                    return;
                }
                boolean changed = store.updateOnlineStatus(peerId, true, peer.ip, peer.chatPort);
                if (changed) {
                    fireEvent(FriendEvent.Type.FRIEND_ONLINE, store.getFriend(peerId));
                }
                // 主动建立 TCP 连接
                if (peer.ip != null && !peer.ip.isEmpty() && peer.chatPort > 0) {
                    getOrCreateClient(peerId, peer.ip, peer.chatPort);
                }
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

        if (client != null && client.isConnected()) {
            client.send(msg.toJson());
        } else if (client != null) {
            // 连接未建立，加入待发队列
            enqueuePending(identity, () -> client.send(msg.toJson()));
        }
    }

    /** 接受好友请求 */
    public void acceptFriendRequest(PendingRequest request) {
        String peerId = request.identity.toString();
        store.addFriend(peerId, request.displayName, request.ip, request.port);
        if (request.ed25519Pub != null && request.x25519Pub != null) {
            store.setPeerPublicKeys(peerId, request.ed25519Pub, request.x25519Pub);
            try {
                store.setAuthSecret(peerId, FriendCrypto.deriveSharedSecretHex(
                        identityManager.getX25519Private(), request.x25519Pub));
            } catch (Exception ignored) {}
        }
        fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(peerId));

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
    private void handleIncomingMessage(String channelIdentity, String jsonLine) {
        String type = FriendProtocol.peekType(jsonLine);
        if (type == null) return;
        if (channelIdentity == null || channelIdentity.isBlank()) return;

        String myId = identityManager.getIdentity().toString();
        // H12: always attribute to handshake identity; ignore spoofable JSON "from"
        if (channelIdentity.equals(myId)) return;

        switch (type) {
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
                    int ackPort = req.port > 0 ? req.port : 0;
                    FriendStore.FriendEntry existing = store.getFriend(channelIdentity);
                    String ackIp = existing != null && existing.lastIp != null ? existing.lastIp : "";
                    store.addFriend(channelIdentity,
                            req.name != null ? req.name : channelIdentity, ackIp, ackPort);
                    FriendChatClient client = getOrCreateClient(channelIdentity, ackIp, ackPort);
                    if (client != null && client.isConnected()) {
                        client.sendFriendAck(channelIdentity, true);
                    } else if (client != null) {
                        enqueuePending(channelIdentity,
                                () -> client.sendFriendAck(channelIdentity, true));
                    }
                } else {
                    FriendIdentity peerId = FriendIdentity.parse(channelIdentity);
                    FriendStore.FriendEntry existing = store.getFriend(channelIdentity);
                    String reqIp = existing != null && existing.lastIp != null ? existing.lastIp : "";
                    int peerPort = req.port > 0 ? req.port : 0;
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
                handleIncomingMessage(identity, jsonLine);
            }

            @Override
            public void onDisconnected(String reason) {
                // 条件删除：仅当 map 中的 client 仍是当前 client 时才删除
                // 避免旧 client 的异步回调误删已建立的新 client
                activeClients.remove(identity, client);
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
