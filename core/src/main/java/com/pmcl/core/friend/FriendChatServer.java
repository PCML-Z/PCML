package com.pmcl.core.friend;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * TCP 聊天服务器：每条连接先完成 {@link FriendSecureChannel} 握手，再分发加密消息行。
 * <p>
 * Bind address: system property {@code pmcl.friend.bindAddress} (e.g. {@code 127.0.0.1}
 * for loopback testing, or an EasyTier/virtual IP). Default {@code 0.0.0.0} for LAN;
 * only authenticated secure-channel peers proceed past handshake.
 */
public final class FriendChatServer implements AutoCloseable {

    private static final int AUTH_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    /** Optional bind override: {@code -Dpmcl.friend.bindAddress=127.0.0.1} */
    public static final String BIND_ADDRESS_PROPERTY = "pmcl.friend.bindAddress";

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;
    private int port = 0;

    private final CopyOnWriteArrayList<MessageListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private static final int MAX_CONNECTIONS = 50;
    private final Set<Socket> clientSockets = ConcurrentHashMap.newKeySet();

    private volatile Predicate<String> identityValidator = id -> false;
    private volatile Function<String, FriendSecureChannel.PeerStaticKeys> peerKeyLookup = id -> null;
    private volatile FriendSecureChannel.LocalIdentity localIdentity;
    /** Optional: called after handshake to persist introduce keys / open friend_req path */
    private volatile java.util.function.BiConsumer<String, FriendSecureChannel> onChannelEstablished;

    public int getPort() { return port; }
    public boolean isRunning() { return running.get(); }

    public void setIdentityValidator(Predicate<String> validator) {
        this.identityValidator = (validator != null) ? validator : (id -> false);
    }

    /** @deprecated HMAC secret provider removed; secure channel replaces it. */
    @Deprecated
    public void setSecretProvider(Function<String, String> provider) { /* no-op */ }

    public void setLocalIdentity(FriendSecureChannel.LocalIdentity local) {
        this.localIdentity = local;
    }

    public void setPeerKeyLookup(Function<String, FriendSecureChannel.PeerStaticKeys> lookup) {
        this.peerKeyLookup = (lookup != null) ? lookup : (id -> null);
    }

    public void setOnChannelEstablished(java.util.function.BiConsumer<String, FriendSecureChannel> cb) {
        this.onChannelEstablished = cb;
    }

    public void start() throws IOException { start(0); }

    public void start(int listenPort) throws IOException {
        if (running.get()) return;
        if (localIdentity == null) {
            throw new IOException("FriendChatServer local identity keys not set");
        }
        InetAddress bindAddr = resolveBindAddress();
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddr, listenPort));
        this.port = serverSocket.getLocalPort();
        System.out.println("[FriendChatServer] listening on " + bindAddr.getHostAddress() + ":" + port);
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "FriendChat-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * Prefer {@code -Dpmcl.friend.bindAddress=...}; otherwise all interfaces (0.0.0.0)
     * so LAN / virtual-network peers can connect. Auth is enforced by secure channel.
     */
    static InetAddress resolveBindAddress() throws IOException {
        String prop = System.getProperty(BIND_ADDRESS_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            return InetAddress.getByName(prop.trim());
        }
        return InetAddress.getByName("0.0.0.0");
    }

    @Override
    public void close() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
        for (Socket s : clientSockets) {
            try { s.close(); } catch (IOException ignored) {}
        }
        clientSockets.clear();
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
    }

    public void addListener(MessageListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(MessageListener listener) {
        listeners.remove(listener);
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                if (connectionCount.get() >= MAX_CONNECTIONS) {
                    try { socket.close(); } catch (IOException ignored) {}
                    continue;
                }
                connectionCount.incrementAndGet();
                clientSockets.add(socket);
                Thread t = new Thread(() -> {
                    try {
                        handleClient(socket);
                    } finally {
                        clientSockets.remove(socket);
                        connectionCount.decrementAndGet();
                    }
                }, "FriendChat-Client-" + socket.getInetAddress().getHostAddress());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[FriendChatServer] accept 失败: " + e.getMessage());
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        FriendSecureChannel channel = null;
        try (socket) {
            socket.setSoTimeout(AUTH_TIMEOUT_MS);
            String remoteAddr = socket.getInetAddress().getHostAddress();
            channel = FriendSecureChannel.serverHandshake(socket, localIdentity, peerId -> {
                FriendSecureChannel.PeerStaticKeys pinned = peerKeyLookup.apply(peerId);
                if (pinned != null) return pinned;
                // Unknown peer: allow introduce (friend request) — keys self-authenticated via Ed25519
                return null;
            });
            String identity = channel.getPeerIdentity();
            boolean known = identityValidator.test(identity);
            // Allow unknown peers through for friend_req; known peers always OK
            if (!known) {
                System.out.println("[FriendChatServer] introduce handshake from unknown "
                        + identity + " @ " + remoteAddr);
            }

            socket.setSoTimeout(READ_TIMEOUT_MS);
            java.util.function.BiConsumer<String, FriendSecureChannel> established = onChannelEstablished;
            if (established != null) {
                try { established.accept(identity, channel); } catch (Exception ignored) {}
            }
            for (MessageListener listener : listeners) {
                try {
                    listener.onAuthenticated(remoteAddr, identity);
                } catch (Exception e) {
                    System.err.println("[FriendChatServer] 监听器异常(onAuth): " + e.getMessage());
                }
            }

            while (running.get()) {
                String line = channel.readLine(FriendProtocol.MAX_MESSAGE_LENGTH);
                if (line == null) break;
                // Unknown peers: only accept friend_req / friend_ack until befriended
                if (!identityValidator.test(identity)) {
                    String type = FriendProtocol.peekType(line);
                    if (!"friend_req".equals(type) && !"friend_ack".equals(type)) {
                        System.err.println("[FriendChatServer] 拒绝未知身份的非好友请求消息: "
                                + type + " from " + identity);
                        continue;
                    }
                }
                for (MessageListener listener : listeners) {
                    try {
                        listener.onMessage(remoteAddr, identity, line);
                    } catch (Exception e) {
                        System.err.println("[FriendChatServer] 监听器异常: " + e.getMessage());
                    }
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            // handshake / idle timeout
        } catch (IOException e) {
            // disconnect
        } finally {
            if (channel != null) {
                try { channel.close(); } catch (Exception ignored) {}
            }
        }
    }

    public interface MessageListener {
        void onAuthenticated(String remoteAddr, String identity);
        void onMessage(String remoteAddr, String identity, String jsonLine);
    }
}
