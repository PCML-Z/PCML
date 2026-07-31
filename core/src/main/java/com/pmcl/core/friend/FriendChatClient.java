package com.pmcl.core.friend;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP 聊天客户端：连接后先完成 {@link FriendSecureChannel} 握手，再收发 AES-GCM 加密行。
 */
public final class FriendChatClient implements AutoCloseable {

    private final String host;
    private final int port;
    private final String myIdentity;
    private final String myName;
    private int myChatPort;

    private volatile FriendSecureChannel.LocalIdentity localKeys;
    private volatile FriendSecureChannel.PeerStaticKeys peerKeys;

    private volatile Socket socket;
    private volatile FriendSecureChannel channel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private Thread writeThread;
    private Thread readThread;

    private final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>(1000);

    private volatile MessageCallback callback;

    public interface MessageCallback {
        void onMessageReceived(String jsonLine);
        void onDisconnected(String reason);
        void onConnected();
        void onConnectFailed(String reason);
    }

    public FriendChatClient(String host, int port, String myIdentity, String myName) {
        this.host = host;
        this.port = port;
        this.myIdentity = myIdentity;
        this.myName = myName;
    }

    public void setCallback(MessageCallback callback) {
        this.callback = callback;
    }

    public void setMyChatPort(int port) {
        this.myChatPort = port;
    }

    /** 设置本机密钥与对端钉扎公钥（对端可为空——好友请求 introduce）。 */
    public void setCrypto(FriendSecureChannel.LocalIdentity local,
                          FriendSecureChannel.PeerStaticKeys peer) {
        this.localKeys = local;
        this.peerKeys = peer;
    }

    /** @deprecated 已由安全信道替代；保留空实现以免旧调用崩溃 */
    @Deprecated
    public void setAuthSecret(String secret) { /* no-op */ }

    public boolean isConnected() {
        return running.get() && socket != null && socket.isConnected() && !socket.isClosed()
                && channel != null;
    }

    public String getRemoteHost() { return host; }
    public int getRemotePort() { return port; }

    public FriendSecureChannel getChannel() { return channel; }

    @Override
    public String toString() {
        return host + ":" + port;
    }

    public void connectAsync() {
        if (!connecting.compareAndSet(false, true)) return;
        if (running.get()) {
            connecting.set(false);
            return;
        }

        Thread t = new Thread(() -> {
            try {
                connect();
                connecting.set(false);
            } catch (Exception e) {
                connecting.set(false);
                running.set(false);
                if (callback != null) {
                    callback.onConnectFailed(e.getMessage());
                }
            }
        }, "FriendChat-Connect-" + host + ":" + port);
        t.setDaemon(true);
        t.start();
    }

    public void connect() throws IOException {
        if (localKeys == null) {
            throw new IOException("missing local crypto keys");
        }
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 5000);
            socket = s;
            channel = FriendSecureChannel.clientHandshake(s, localKeys, peerKeys);
            running.set(true);

            readThread = new Thread(this::readLoop, "FriendChat-Read-" + host + ":" + port);
            readThread.setDaemon(true);
            readThread.start();

            writeThread = new Thread(this::writeLoop, "FriendChat-Write-" + host + ":" + port);
            writeThread.setDaemon(true);
            writeThread.start();

            if (callback != null) callback.onConnected();
        } catch (IOException e) {
            running.set(false);
            try { s.close(); } catch (IOException ignored) {}
            if (channel != null) {
                try { channel.close(); } catch (Exception ignored) {}
                channel = null;
            }
            throw e;
        }
    }

    public void send(String jsonLine) {
        if (!sendQueue.offer(jsonLine)) {
            System.err.println("[FriendChatClient] 发送队列已满，丢弃消息");
        }
    }

    public void sendText(String text) {
        FriendProtocol.ChatMessage msg = new FriendProtocol.ChatMessage();
        msg.id = java.util.UUID.randomUUID().toString();
        msg.text = text;
        msg.timestamp = System.currentTimeMillis();
        msg.from = myIdentity;
        msg.fromName = myName;
        send(msg.toJson());
    }

    public void sendFriendRequest() {
        sendFriendRequest(null);
    }

    /** authSecret 参数已忽略（禁止明文交换）；仅发送身份与端口。 */
    public void sendFriendRequest(String ignoredAuthSecret) {
        FriendProtocol.FriendRequest req = new FriendProtocol.FriendRequest();
        req.identity = myIdentity;
        req.name = myName;
        req.port = myChatPort;
        req.authSecret = null;
        if (localKeys != null) {
            req.ed25519Pub = FriendCrypto.b64(localKeys.edPublic());
            req.x25519Pub = FriendCrypto.b64(localKeys.xPublic());
        }
        send(req.toJson());
    }

    public void sendFriendAck(String targetIdentity, boolean accepted) {
        sendFriendAck(targetIdentity, accepted, null);
    }

    public void sendFriendAck(String targetIdentity, boolean accepted, String ignoredAuthSecret) {
        FriendProtocol.FriendAck ack = new FriendProtocol.FriendAck();
        ack.identity = myIdentity;
        ack.name = myName;
        ack.accepted = accepted;
        ack.port = myChatPort;
        ack.authSecret = null;
        if (localKeys != null) {
            ack.ed25519Pub = FriendCrypto.b64(localKeys.edPublic());
            ack.x25519Pub = FriendCrypto.b64(localKeys.xPublic());
        }
        send(ack.toJson());
    }

    public void sendStatus(boolean online) {
        FriendProtocol.StatusMessage status = new FriendProtocol.StatusMessage();
        status.online = online;
        status.from = myIdentity;
        send(status.toJson());
    }

    @Override
    public void close() {
        if (running.get() && channel != null) {
            try {
                FriendProtocol.StatusMessage offline = new FriendProtocol.StatusMessage();
                offline.online = false;
                offline.from = myIdentity;
                channel.writeLine(offline.toJson());
            } catch (Exception ignored) {}
        }
        running.set(false);
        connecting.set(false);
        if (writeThread != null) writeThread.interrupt();
        if (readThread != null) readThread.interrupt();
        writeThread = null;
        readThread = null;
        if (channel != null) {
            try { channel.close(); } catch (Exception ignored) {}
            channel = null;
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }

    private void writeLoop() {
        while (running.get()) {
            try {
                String msg = sendQueue.poll(15, TimeUnit.SECONDS);
                FriendSecureChannel ch = channel;
                if (ch == null) break;
                if (msg != null) {
                    ch.writeLine(msg);
                } else {
                    FriendProtocol.StatusMessage heartbeat = new FriendProtocol.StatusMessage();
                    heartbeat.online = true;
                    heartbeat.from = myIdentity;
                    ch.writeLine(heartbeat.toJson());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                handleDisconnect("写入失败: " + e.getMessage());
                break;
            }
        }
    }

    private void readLoop() {
        FriendSecureChannel ch = channel;
        if (ch == null) return;
        try {
            while (running.get()) {
                String line = ch.readLine(FriendProtocol.MAX_MESSAGE_LENGTH);
                if (line == null) {
                    handleDisconnect("连接关闭");
                    break;
                }
                MessageCallback cb = callback;
                if (cb != null) cb.onMessageReceived(line);
            }
        } catch (Exception e) {
            if (running.get()) {
                handleDisconnect("读取失败: " + e.getMessage());
            }
        }
    }

    private void handleDisconnect(String reason) {
        if (!running.getAndSet(false)) return;
        MessageCallback cb = callback;
        if (cb != null) {
            try { cb.onDisconnected(reason); } catch (Exception ignored) {}
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}
    }
}
