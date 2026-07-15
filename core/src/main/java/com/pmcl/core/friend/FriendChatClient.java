package com.pmcl.core.friend;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP 聊天客户端：连接到好友的虚拟 IP + 端口发送消息。
 * <p>
 * 使用写线程 + 阻塞队列实现异步发送，避免阻塞 UI 线程。
 */
public final class FriendChatClient implements AutoCloseable {

    private final String host;
    private final int port;
    private final String myIdentity;
    private final String myName;
    private int myChatPort;

    private volatile Socket socket;
    private volatile PrintWriter writer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connecting = new AtomicBoolean(false);
    private Thread writeThread;
    private Thread readThread;

    private final BlockingQueue<String> sendQueue = new LinkedBlockingQueue<>(1000);

    private MessageCallback callback;

    // ---------------------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------------------

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

    /** 设置我的聊天服务器端口（用于在好友请求/应答中携带） */
    public void setMyChatPort(int port) {
        this.myChatPort = port;
    }

    public boolean isConnected() {
        return running.get() && socket != null && socket.isConnected() && !socket.isClosed();
    }

    /** 返回远程主机地址 */
    public String getRemoteHost() {
        return host;
    }

    /** 返回远程主机端口 */
    public int getRemotePort() {
        return port;
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }

    /** 异步连接 */
    public void connectAsync() {
        if (connecting.get() || running.get()) return;
        connecting.set(true);

        new Thread(() -> {
            try {
                connect();
                connecting.set(false);
            } catch (Exception e) {
                connecting.set(false);
                if (callback != null) {
                    callback.onConnectFailed(e.getMessage());
                }
            }
        }, "FriendChat-Connect").start();
    }

    /** 同步连接（阻塞） */
    public void connect() throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
        running.set(true);

        // 启动读取线程
        readThread = new Thread(this::readLoop, "FriendChat-Read");
        readThread.setDaemon(true);
        readThread.start();

        // 启动写入线程
        writeThread = new Thread(this::writeLoop, "FriendChat-Write");
        writeThread.setDaemon(true);
        writeThread.start();

        if (callback != null) callback.onConnected();
    }

    /** 发送消息（非阻塞，入队） */
    public void send(String jsonLine) {
        if (!sendQueue.offer(jsonLine)) {
            System.err.println("[FriendChatClient] 发送队列已满，丢弃消息: " + jsonLine);
        }
    }

    /** 发送聊天文本 */
    public void sendText(String text) {
        FriendProtocol.ChatMessage msg = new FriendProtocol.ChatMessage();
        msg.id = java.util.UUID.randomUUID().toString();
        msg.text = text;
        msg.timestamp = System.currentTimeMillis();
        send(msg.toJson());
    }

    /** 发送好友请求 */
    public void sendFriendRequest() {
        FriendProtocol.FriendRequest req = new FriendProtocol.FriendRequest();
        req.identity = myIdentity;
        req.name = myName;
        req.port = myChatPort;
        send(req.toJson());
    }

    /** 发送好友请求应答 */
    public void sendFriendAck(String targetIdentity, boolean accepted) {
        FriendProtocol.FriendAck ack = new FriendProtocol.FriendAck();
        ack.identity = myIdentity;
        ack.name = myName;
        ack.accepted = accepted;
        ack.port = myChatPort;
        send(ack.toJson());
    }

    /** 发送在线状态 */
    public void sendStatus(boolean online) {
        FriendProtocol.StatusMessage status = new FriendProtocol.StatusMessage();
        status.online = online;
        send(status.toJson());
    }

    /** 断开连接 */
    @Override
    public void close() {
        running.set(false);
        if (writeThread != null) writeThread.interrupt();
        if (readThread != null) readThread.interrupt();
        writeThread = null;
        readThread = null;
        try {
            if (writer != null) writer.close();
        } catch (Exception ignored) {}
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
    }

    // ---------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------

    private void writeLoop() {
        while (running.get()) {
            try {
                String msg = sendQueue.poll(1, TimeUnit.SECONDS);
                if (msg != null && writer != null && !writer.checkError()) {
                    writer.println(msg);
                    if (writer.checkError()) {
                        handleDisconnect("写入失败");
                        break;
                    }
                }
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (callback != null) {
                    try {
                        callback.onMessageReceived(line);
                    } catch (RuntimeException e) {
                        System.err.println("[FriendChatClient] 回调处理异常: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                handleDisconnect("连接断开: " + e.getMessage());
            }
        }
    }

    private void handleDisconnect(String reason) {
        running.set(false);
        if (callback != null) {
            callback.onDisconnected(reason);
        }
    }
}
