package com.pmcl.core.friend;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P2P 对等发现：通过 UDP 广播在虚拟网络上发现附近的好友。
 * <p>
 * 默认端口：{@code 25410}。
 * <p>
 * 工作方式：
 * <ol>
 *   <li>加入联机房间后监听 UDP 广播</li>
 *   <li>定时发送自己的身份信息</li>
 *   <li>收到其他用户的广播时通知监听器</li>
 * </ol>
 */
public final class FriendPeerDiscovery implements AutoCloseable {
    public static final int DEFAULT_PORT = 25410;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private DatagramSocket socket;
    private Thread listenerThread;
    private Thread broadcasterThread;
    private int port;
    private int broadcastPort = DEFAULT_PORT;

    private String myIdentity;
    private String myName;
    private int myChatPort;

    private final CopyOnWriteArrayList<PeerListener> listeners = new CopyOnWriteArrayList<>();

    // ---------------------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------------------

    /** 发现监听器 */
    public interface PeerListener {
        /** 发现一个对等节点 */
        void onPeerDiscovered(DiscoveredPeer peer);
    }

    /** 发现的对等节点 */
    public static final class DiscoveredPeer {
        public final FriendIdentity identity;
        public final String displayName;
        public final String ip;
        public final int chatPort;
        public final long discoveredAt;

        public DiscoveredPeer(FriendIdentity identity, String displayName, String ip, int chatPort) {
            this.identity = identity;
            this.displayName = displayName;
            this.ip = ip;
            this.chatPort = chatPort;
            this.discoveredAt = System.currentTimeMillis();
        }
    }

    public int getPort() {
        return port;
    }

    public void addListener(PeerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(PeerListener listener) {
        listeners.remove(listener);
    }

    /**
     * 启动对等发现（使用默认端口）。
     *
     * @param myIdentity  我的身份 ID
     * @param myName      我的显示名称
     * @param myChatPort  我的聊天服务器端口
     */
    public void start(String myIdentity, String myName, int myChatPort) throws IOException {
        start(myIdentity, myName, myChatPort, DEFAULT_PORT);
    }

    /**
     * 启动对等发现。
     */
    public void start(String myIdentity, String myName, int myChatPort, int listenPort) throws IOException {
        if (running.get()) return;

        this.myIdentity = myIdentity;
        this.myName = myName;
        this.myChatPort = myChatPort;
        this.port = listenPort;
        this.broadcastPort = DEFAULT_PORT;

        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(listenPort));
            socket.setBroadcast(true);
            socket.setSoTimeout(1000); // 1 秒超时，实现响应式关闭
        } catch (SocketException e) {
            // 端口被占用，尝试随机端口
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(0));
            socket.setBroadcast(true);
            try { socket.setSoTimeout(1000); } catch (SocketException ignored) {}
            this.port = socket.getLocalPort();
        }

        running.set(true);

        listenerThread = new Thread(this::listenLoop, "FriendDiscovery-Listen");
        listenerThread.setDaemon(true);
        listenerThread.start();

        broadcasterThread = new Thread(this::broadcastLoop, "FriendDiscovery-Broadcast");
        broadcasterThread.setDaemon(true);
        broadcasterThread.start();
    }

    /** 立即广播一次（用于强制触发发现） */
    public void broadcastNow() {
        broadcastIdentity();
    }

    @Override
    public void close() {
        running.set(false);
        if (listenerThread != null) listenerThread.interrupt();
        if (broadcasterThread != null) broadcasterThread.interrupt();
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    // ---------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------

    private void listenLoop() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running.get()) {
            try {
                socket.receive(packet);
                String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                String senderIp = packet.getAddress().getHostAddress();

                String type = FriendProtocol.peekType(json);
                if (!"discover".equals(type)) continue;

                FriendProtocol.DiscoverMessage msg = FriendProtocol.DiscoverMessage.fromJson(json);
                if (msg.identity == null || msg.identity.equals(myIdentity)) continue;

                FriendIdentity peerId;
                try {
                    peerId = FriendIdentity.parse(msg.identity);
                } catch (IllegalArgumentException e) {
                    continue;
                }

                if (msg.port <= 0) continue;

                DiscoveredPeer peer = new DiscoveredPeer(
                        peerId,
                        msg.name != null ? msg.name : msg.identity,
                        senderIp,
                        msg.port
                );

                for (PeerListener listener : listeners) {
                    try {
                        listener.onPeerDiscovered(peer);
                    } catch (Exception e) {
                        System.err.println("[FriendDiscovery] 监听器异常: " + e.getMessage());
                    }
                }
            } catch (java.net.SocketTimeoutException e) {
                // 正常超时，继续循环
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[FriendDiscovery] 接收错误: " + e.getMessage());
                }
            }
        }
    }

    private void broadcastLoop() {
        while (running.get()) {
            broadcastIdentity();
            try {
                Thread.sleep(5000); // 每 5 秒广播一次
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void broadcastIdentity() {
        if (socket == null || socket.isClosed() || myIdentity == null) return;

        FriendProtocol.DiscoverMessage msg = new FriendProtocol.DiscoverMessage();
        msg.identity = myIdentity;
        msg.name = myName;
        msg.port = myChatPort;

        byte[] data = msg.toJson().getBytes(StandardCharsets.UTF_8);

        try {
            // 广播到 255.255.255.255
            DatagramPacket packet = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName("255.255.255.255"),
                    broadcastPort
            );
            socket.send(packet);
        } catch (IOException e) {
            // 网络不可用时静默失败
        }
    }
}
