package com.pmcl.core.friend;

import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P2P 对等发现：通过 UDP 组播在本地网络上发现附近的好友。
 * <p>
 * 使用组播组 {@code 239.254.10.10}，默认端口 {@code 25410}。
 * <p>
 * 组播相比广播的优势：
 * <ul>
 *   <li>同机器多实例：所有加入组播组的 socket 都能收到数据包（SO_REUSEADDR 即可）</li>
 *   <li>跨机器：局域网内组播通常可达</li>
 *   <li>不会像 SO_REUSEPORT 那样只分发到一个 socket</li>
 * </ul>
 */
public final class FriendPeerDiscovery implements AutoCloseable {
    public static final int DEFAULT_PORT = 25410;
    public static final String MULTICAST_GROUP = "239.254.10.10";
    public static final int MULTICAST_TTL = 1; // 本地网络

    private final AtomicBoolean running = new AtomicBoolean(false);
    private MulticastSocket socket;
    private InetAddress group;
    private Thread listenerThread;
    private Thread broadcasterThread;
    private int port;

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
     * 启动对等发现（使用默认端口和组播组）。
     *
     * @param myIdentity  我的身份 ID
     * @param myName      我的显示名称
     * @param myChatPort  我的聊天服务器端口
     */
    public void start(String myIdentity, String myName, int myChatPort) throws IOException {
        if (running.get()) return;

        this.myIdentity = myIdentity;
        this.myName = myName;
        this.myChatPort = myChatPort;

        group = InetAddress.getByName(MULTICAST_GROUP);

        // 单一 MulticastSocket：绑定 DEFAULT_PORT，加入组播组
        // SO_REUSEADDR 允许多个实例绑定同一端口，组播保证所有成员都能收到
        socket = new MulticastSocket(null);
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(DEFAULT_PORT));
        socket.setTimeToLive(MULTICAST_TTL);
        // setLoopbackMode(false) = 启用回环，确保同机器多实例能收到彼此的组播
        socket.setLoopbackMode(false);
        socket.setSoTimeout(1000);
        this.port = socket.getLocalPort();

        // 加入组播组（在所有可用接口上）
        try {
            socket.joinGroup(new InetSocketAddress(group, DEFAULT_PORT), null);
        } catch (IOException e) {
            // 退回旧式 joinGroup
            socket.joinGroup(group);
        }

        System.out.println("[FriendDiscovery] 已启动, port=" + port + " group=" + MULTICAST_GROUP);

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
            try {
                socket.leaveGroup(new InetSocketAddress(group, DEFAULT_PORT), null);
            } catch (Exception ignored) {
                try { socket.leaveGroup(group); } catch (Exception ignored2) {}
            }
            socket.close();
        }
    }

    // ---------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------

    private void listenLoop() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

        while (running.get() && socket != null && !socket.isClosed()) {
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

        // 1. 发送到组播组（主要路径，同机器+局域网均可达）
        try {
            DatagramPacket packet = new DatagramPacket(
                data, data.length, group, DEFAULT_PORT
            );
            socket.send(packet);
        } catch (IOException e) {
            System.err.println("[FriendDiscovery] 组播发送失败: " + e.getMessage());
        }

        // 2. 发送到本机回环地址（额外保险，确保同机器多实例可达）
        try {
            DatagramPacket loopback = new DatagramPacket(
                data, data.length,
                InetAddress.getByName("127.0.0.1"),
                DEFAULT_PORT
            );
            socket.send(loopback);
        } catch (IOException ignored) {
        }

        // 3. 广播到所有网络接口的广播地址（跨机器局域网兼容）
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress addr : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = addr.getBroadcast();
                    if (broadcast != null) {
                        try {
                            DatagramPacket packet = new DatagramPacket(
                                data, data.length, broadcast, DEFAULT_PORT
                            );
                            socket.send(packet);
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
