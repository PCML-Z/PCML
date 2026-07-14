package com.pmcl.core.friend;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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

    private final FriendIdentityManager identityManager;
    private final FriendStore store;
    private final FriendChatServer chatServer;
    private final FriendPeerDiscovery discovery;
    private final Map<String, FriendChatClient> activeClients = new ConcurrentHashMap<>();
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

        // 监听聊天服务器消息
        chatServer.addListener((remoteIp, jsonLine) -> handleIncomingMessage(remoteIp, jsonLine));

        // 监听对等发现
        discovery.addListener(peer -> {
            if (store.isFriend(peer.identity.toString())) {
                store.updateOnlineStatus(peer.identity.toString(), true, peer.ip, peer.chatPort);
                fireEvent(FriendEvent.Type.FRIEND_ONLINE, store.getFriend(peer.identity.toString()));
            }
            fireEvent(FriendEvent.Type.PEERS_UPDATED, peer);
        });

        state = State.READY;
    }

    /** 加入联机网络后启动服务 */
    public void start() throws IOException {
        if (state != State.READY && state != State.STOPPED) return;

        // 启动聊天服务器
        chatServer.start();

        // 启动 UDP 发现
        discovery.start(
                identityManager.getIdentity().toString(),
                identityManager.getDisplayName(),
                chatServer.getPort()
        );

        state = State.RUNNING;
        fireEvent(FriendEvent.Type.STATE_CHANGED, state);
    }

    /** 离开联机网络时停止 */
    public void stop() {
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
        for (FriendStore.FriendEntry entry : store.getAllFriends()) {
            store.updateOnlineStatus(entry.identity, false, "", 0);
        }

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
        // 断开与该好友的连接
        FriendChatClient client = activeClients.remove(identity);
        if (client != null) {
            client.close();
        }
        fireEvent(FriendEvent.Type.FRIEND_REMOVED, identity);
    }

    /** 发送好友请求到指定 IP + port */
    public void sendFriendRequest(String identity, String displayName, String ip, int port) {
        FriendChatClient client = getOrCreateClient(identity, ip, port);
        if (client != null && client.isConnected()) {
            client.sendFriendRequest();
        } else {
            client = new FriendChatClient(ip, port,
                    identityManager.getIdentity().toString(),
                    identityManager.getDisplayName());
            setupClient(identity, client);
            client.connectAsync();
            // 连接成功后发送好友请求（在 onConnected 回调中）
        }
    }

    // ---------------------------------------------------------------------------
    // 消息操作
    // ---------------------------------------------------------------------------

    /** 发送文字消息给好友 */
    public void sendMessage(String identity, String text) {
        FriendStore.FriendEntry friend = store.getFriend(identity);
        if (friend == null || !friend.online) return;

        String msgId = UUID.randomUUID().toString();
        long timestamp = System.currentTimeMillis();

        // 本地存储
        store.addMessage(identity, msgId, text, timestamp, true);

        // 通过网络发送
        FriendChatClient client = getOrCreateClient(identity, friend.lastIp, friend.lastPort);
        if (client != null && client.isConnected()) {
            FriendProtocol.ChatMessage msg = new FriendProtocol.ChatMessage();
            msg.id = msgId;
            msg.text = text;
            msg.timestamp = timestamp;
            client.send(msg.toJson());
        }
    }

    /** 接受好友请求 */
    public void acceptFriendRequest(PendingRequest request) {
        store.addFriend(request.identity.toString(), request.displayName, request.ip, request.port);
        fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(request.identity.toString()));

        // 发送接受应答
        FriendChatClient client = getOrCreateClient(request.identity.toString(), request.ip, request.port);
        if (client != null && client.isConnected()) {
            client.sendFriendAck(request.identity.toString(), true);
        }
    }

    /** 拒绝好友请求 */
    public void rejectFriendRequest(PendingRequest request) {
        FriendChatClient client = getOrCreateClient(request.identity.toString(), request.ip, request.port);
        if (client != null) {
            client.sendFriendAck(request.identity.toString(), false);
            client.close();
            activeClients.remove(request.identity.toString());
        }
    }

    /** 广播发现（手动触发） */
    public void broadcastDiscovery() {
        if (state == State.RUNNING) {
            discovery.broadcastNow();
        }
    }

    // ---------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------

    private void handleIncomingMessage(String remoteIp, String jsonLine) {
        String type = FriendProtocol.peekType(jsonLine);

        switch (type) {
            case "msg" -> {
                FriendProtocol.ChatMessage msg = FriendProtocol.ChatMessage.fromJson(jsonLine);
                if (msg.text != null && msg.id != null) {
                    // 需要通过其他方式确定发送者 identity（从 activeClients 反查）
                    String senderId = findIdentityByIp(remoteIp);
                    if (senderId != null) {
                        store.addMessage(senderId, msg.id, msg.text, msg.timestamp, false);
                        fireEvent(FriendEvent.Type.MESSAGE_RECEIVED, msg);
                    }
                }
            }
            case "friend_req" -> {
                FriendProtocol.FriendRequest req = FriendProtocol.FriendRequest.fromJson(jsonLine);
                if (req.identity != null) {
                    FriendIdentity peerId = FriendIdentity.parse(req.identity);
                    PendingRequest pending = new PendingRequest(peerId,
                            req.name != null ? req.name : req.identity,
                            remoteIp, chatServer.getPort());
                    fireEvent(FriendEvent.Type.FRIEND_REQUEST_RECEIVED, pending);
                }
            }
            case "friend_ack" -> {
                FriendProtocol.FriendAck ack = FriendProtocol.FriendAck.fromJson(jsonLine);
                if (ack.accepted && ack.identity != null) {
                    store.addFriend(ack.identity, ack.name != null ? ack.name : ack.identity,
                            remoteIp, chatServer.getPort());
                    fireEvent(FriendEvent.Type.FRIEND_ADDED, store.getFriend(ack.identity));
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
        return activeClients.computeIfAbsent(identity, k -> {
            FriendChatClient client = new FriendChatClient(ip, port,
                    identityManager.getIdentity().toString(),
                    identityManager.getDisplayName());
            setupClient(identity, client);
            client.connectAsync();
            return client;
        });
    }

    private void setupClient(String identity, FriendChatClient client) {
        client.setCallback(new FriendChatClient.MessageCallback() {
            @Override
            public void onMessageReceived(String jsonLine) {
                handleIncomingMessage("", jsonLine);
            }

            @Override
            public void onDisconnected(String reason) {
                activeClients.remove(identity);
                store.updateOnlineStatus(identity, false, "", 0);
                fireEvent(FriendEvent.Type.FRIEND_OFFLINE, store.getFriend(identity));
            }

            @Override
            public void onConnected() {
                store.updateOnlineStatus(identity, true, client.toString(), 0);
                fireEvent(FriendEvent.Type.FRIEND_ONLINE, store.getFriend(identity));
            }

            @Override
            public void onConnectFailed(String reason) {
                activeClients.remove(identity);
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
