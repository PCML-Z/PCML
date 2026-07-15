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

        PendingRequest(FriendIdentity identity, String displayName, String ip, int port) {
            this.identity = identity;
            this.displayName = displayName;
            this.ip = ip;
            this.port = port;
            this.receivedAt = System.currentTimeMillis();
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
        this(Paths.get(System.getProperty("user.home"), ".pmcl", "friend-data"));
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

        // 监听聊天服务器消息
        chatServer.addListener((remoteIp, jsonLine) -> handleIncomingMessage(remoteIp, jsonLine));

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
        for (FriendChatClient client : activeClients.values()) {
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
            chatServer.start();
            System.out.println("[FriendManager] 聊天服务器已启动, 端口=" + chatServer.getPort());
        } catch (IOException e) {
            throw new IOException("启动聊天服务器失败: " + e.getMessage(), e);
        }

        try {
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

    /** 发送好友请求到指定 IP + port */
    public void sendFriendRequest(String identity, String displayName, String ip, int port) {
        // 先添加到本地存储
        store.addFriend(identity, displayName, ip, port);
        fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(identity));

        if (ip != null && !ip.isEmpty() && port > 0) {
            // 有地址，尝试连接并发送
            FriendChatClient client = getOrCreateClient(identity, ip, port);
            if (client != null && client.isConnected()) {
                client.sendFriendRequest();
            } else if (client != null) {
                enqueuePending(identity, () -> client.sendFriendRequest());
            }
        } else {
            // 无地址，排队等待发现后自动发送
            enqueuePending(identity, () -> {
                FriendChatClient c = activeClients.get(identity);
                if (c != null) c.sendFriendRequest();
            });
        }
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
        store.addFriend(request.identity.toString(), request.displayName, request.ip, request.port);
        fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(request.identity.toString()));

        // 发送接受应答
        if (request.ip != null && !request.ip.isEmpty() && request.port > 0) {
            FriendChatClient client = getOrCreateClient(request.identity.toString(), request.ip, request.port);
            if (client != null && client.isConnected()) {
                client.sendFriendAck(request.identity.toString(), true);
            } else if (client != null) {
                enqueuePending(request.identity.toString(),
                        () -> client.sendFriendAck(request.identity.toString(), true));
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
    public String sendCallInvite(String identity, String mediaType) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null) return null;

        String callId = UUID.randomUUID().toString();
        FriendProtocol.CallInvite invite = new FriendProtocol.CallInvite();
        invite.callId = callId;
        invite.from = identityManager.getIdentity().toString();
        invite.fromName = identityManager.getDisplayName();
        invite.mediaType = mediaType;

        if (friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return null;

        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        if (client != null && client.isConnected()) {
            client.send(invite.toJson());
        } else if (client != null) {
            enqueuePending(identity, () -> client.send(invite.toJson()));
        }
        return callId;
    }

    /** 接受通话 */
    public void sendCallAccept(String identity, String callId, String sdpOffer) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null) return;

        FriendProtocol.CallAccept accept = new FriendProtocol.CallAccept();
        accept.callId = callId;
        accept.from = identityManager.getIdentity().toString();
        accept.accept = true;
        accept.sdpOffer = sdpOffer != null ? sdpOffer : "";

        if (friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return;

        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        if (client != null && client.isConnected()) {
            client.send(accept.toJson());
        } else if (client != null) {
            enqueuePending(identity, () -> client.send(accept.toJson()));
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
        }
    }

    /** 发送 ICE 候选 */
    public void sendCallIceCandidate(String identity, String callId, String candidate, int sdpMLineIndex, String sdpMid) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null) return;

        FriendProtocol.CallIceCandidate ice = new FriendProtocol.CallIceCandidate();
        ice.callId = callId;
        ice.from = identityManager.getIdentity().toString();
        ice.candidate = candidate != null ? candidate : "";
        ice.sdpMLineIndex = sdpMLineIndex;
        ice.sdpMid = sdpMid != null ? sdpMid : "";

        if (friend.lastIp == null || friend.lastIp.isEmpty() || friend.lastPort <= 0) return;

        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        if (client != null && client.isConnected()) {
            client.send(ice.toJson());
        } else if (client != null) {
            enqueuePending(identity, () -> client.send(ice.toJson()));
        }
    }

    // ---------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------

    private void handleIncomingMessage(String remoteIp, String jsonLine) {
        String type = FriendProtocol.peekType(jsonLine);
        if (type == null) return;

        String myId = identityManager.getIdentity().toString();

        // 判断 remoteIp 是 IP 地址还是 identity
        // 客户端路径传的是 identity，服务器路径传的是 IP
        boolean isIdentity = store.isFriend(remoteIp);
        String actualIp = isIdentity ? null : remoteIp;

        switch (type) {
            case "msg" -> {
                FriendProtocol.ChatMessage msg = FriendProtocol.ChatMessage.fromJson(jsonLine);
                if (msg.text != null && msg.id != null) {
                    // 忽略来自自己的消息（回环）
                    if (msg.from != null && msg.from.equals(myId)) return;
                    // 优先使用消息中的 from 字段识别发送者
                    String senderId;
                    if (msg.from != null && FriendIdentity.isValid(msg.from)) {
                        senderId = FriendIdentity.parse(msg.from).toString();
                    } else if (isIdentity) {
                        senderId = remoteIp;
                    } else {
                        senderId = findIdentityByIp(remoteIp);
                    }
                    if (senderId != null && store.isFriend(senderId)) {
                        store.addMessage(senderId, msg.id, msg.text, msg.timestamp, false);
                        fireEvent(FriendEvent.Type.MESSAGE_RECEIVED, msg);
                    }
                }
            }
            case "friend_req" -> {
                FriendProtocol.FriendRequest req = FriendProtocol.FriendRequest.fromJson(jsonLine);
                if (req.identity != null && !req.identity.equals(myId)) {
                    if (store.isFriend(req.identity)) {
                        // 已是好友，自动应答 accepted 并更新地址
                        String ackIp = actualIp != null ? actualIp : "";
                        int ackPort = req.port > 0 ? req.port : 0;
                        store.addFriend(req.identity,
                                req.name != null ? req.name : req.identity, ackIp, ackPort);
                        FriendChatClient client = getOrCreateClient(req.identity, ackIp, ackPort);
                        if (client != null && client.isConnected()) {
                            client.sendFriendAck(req.identity, true);
                        } else if (client != null) {
                            enqueuePending(req.identity, () -> client.sendFriendAck(req.identity, true));
                        }
                    } else {
                        // 新好友请求
                        FriendIdentity peerId = FriendIdentity.parse(req.identity);
                        String reqIp = actualIp != null ? actualIp : "";
                        int peerPort = req.port > 0 ? req.port : 0;
                        PendingRequest pending = new PendingRequest(peerId,
                                req.name != null ? req.name : req.identity,
                                reqIp, peerPort);
                        fireEvent(FriendEvent.Type.FRIEND_REQUEST_RECEIVED, pending);
                    }
                }
            }
            case "friend_ack" -> {
                FriendProtocol.FriendAck ack = FriendProtocol.FriendAck.fromJson(jsonLine);
                if (ack.identity != null && !ack.identity.equals(myId)) {
                    if (ack.accepted) {
                        String ackIp = actualIp != null ? actualIp : "";
                        int ackPort = ack.port > 0 ? ack.port : 0;
                        store.addFriend(ack.identity, ack.name != null ? ack.name : ack.identity,
                                ackIp, ackPort);
                        fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(ack.identity));
                    } else {
                        // 好友请求被拒绝
                        System.err.println("[FriendManager] 好友请求被拒绝 (" + ack.identity + ")");
                    }
                }
            }
            case "status" -> {
                FriendProtocol.StatusMessage status = FriendProtocol.StatusMessage.fromJson(jsonLine);
                // 识别发送者：优先用 from 字段，其次用 remoteIp（客户端路径）
                String statusSenderId = null;
                if (status.from != null && FriendIdentity.isValid(status.from)) {
                    statusSenderId = FriendIdentity.parse(status.from).toString();
                } else if (isIdentity) {
                    statusSenderId = remoteIp;
                }
                if (statusSenderId != null && store.isFriend(statusSenderId)) {
                    FriendStore.FriendEntry entry = store.getFriend(statusSenderId);
                    if (entry != null) {
                        boolean changed = store.updateOnlineStatus(statusSenderId, status.online,
                                entry.lastIp, entry.lastPort);
                        if (changed) {
                            fireEvent(status.online ? FriendEvent.Type.FRIEND_ONLINE : FriendEvent.Type.FRIEND_OFFLINE, entry);
                        }
                    }
                }
            }
            case "call_invite" -> {
                FriendProtocol.CallInvite invite = FriendProtocol.CallInvite.fromJson(jsonLine);
                // 忽略来自自己的消息（回环）
                if (invite.from != null && !invite.from.equals(myId)) {
                    fireEvent(FriendEvent.Type.CALL_INVITE_RECEIVED, invite);
                }
            }
            case "call_accept" -> {
                FriendProtocol.CallAccept accept = FriendProtocol.CallAccept.fromJson(jsonLine);
                if (accept.from != null && !accept.from.equals(myId)) {
                    fireEvent(FriendEvent.Type.CALL_ACCEPTED, accept);
                }
            }
            case "call_reject" -> {
                FriendProtocol.CallReject reject = FriendProtocol.CallReject.fromJson(jsonLine);
                if (reject.from != null && !reject.from.equals(myId)) {
                    fireEvent(FriendEvent.Type.CALL_REJECTED, reject);
                }
            }
            case "call_end" -> {
                FriendProtocol.CallEnd end = FriendProtocol.CallEnd.fromJson(jsonLine);
                if (end.from != null && !end.from.equals(myId)) {
                    fireEvent(FriendEvent.Type.CALL_ENDED, end);
                }
            }
            case "call_ice" -> {
                FriendProtocol.CallIceCandidate ice = FriendProtocol.CallIceCandidate.fromJson(jsonLine);
                if (ice.from != null && !ice.from.equals(myId)) {
                    fireEvent(FriendEvent.Type.CALL_ICE_CANDIDATE, ice);
                }
            }
        }
    }

    private String findIdentityByIp(String ip) {
        for (FriendStore.FriendEntry friend : store.getAllFriends()) {
            if (ip.equals(friend.lastIp)) return friend.identity;
        }
        return null;
    }

    private FriendChatClient getOrCreateClient(String identity, String ip, int port) {
        if (ip == null || ip.isEmpty() || port <= 0) {
            return null;
        }
        return activeClients.compute(identity, (k, existing) -> {
            if (existing != null) {
                // 地址未变且连接活跃，复用
                if (existing.getRemoteHost().equals(ip) && existing.getRemotePort() == port
                        && existing.isConnected()) {
                    return existing;
                }
                // 地址已变或连接已断开，关闭旧连接重建
                try { existing.close(); } catch (Exception ignored) {}
            }
            FriendChatClient client = new FriendChatClient(ip, port,
                    identityManager.getIdentity().toString(),
                    identityManager.getDisplayName());
            client.setMyChatPort(chatServer.getPort());
            setupClient(identity, client);
            client.connectAsync();
            return client;
        });
    }

    private void setupClient(String identity, FriendChatClient client) {
        client.setCallback(new FriendChatClient.MessageCallback() {
            @Override
            public void onMessageReceived(String jsonLine) {
                handleIncomingMessage(identity, jsonLine);
            }

            @Override
            public void onDisconnected(String reason) {
                activeClients.remove(identity);
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
