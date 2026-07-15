package com.pmcl.core.friend;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP 聊天服务器：监听虚拟 IP 上的随机端口，接收来自好友的消息。
 * <p>
 * 每条消息是 JSON 行（以 {@code \n} 分隔），最大 {@link FriendProtocol#MAX_MESSAGE_LENGTH} 字节。
 */
public final class FriendChatServer implements AutoCloseable {

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;
    private int port = 0;

    private final CopyOnWriteArrayList<MessageListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private static final int MAX_CONNECTIONS = 50;

    // ---------------------------------------------------------------------------
    // 公共 API
    // ---------------------------------------------------------------------------

    /** 获取监听端口（0 表示未启动或使用随机端口） */
    public int getPort() {
        return port;
    }

    /** 是否正在运行 */
    public boolean isRunning() {
        return running.get();
    }

    /** 启动服务器（随机端口） */
    public void start() throws IOException {
        start(0);
    }

    /** 启动服务器（指定端口） */
    public void start(int listenPort) throws IOException {
        if (running.get()) return;

        serverSocket = new ServerSocket(listenPort);
        this.port = serverSocket.getLocalPort();
        running.set(true);

        acceptThread = new Thread(this::acceptLoop, "FriendChat-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** 停止服务器 */
    @Override
    public void close() {
        running.set(false);
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    /** 添加消息监听器 */
    public void addListener(MessageListener listener) {
        listeners.add(listener);
    }

    /** 移除消息监听器 */
    public void removeListener(MessageListener listener) {
        listeners.remove(listener);
    }

    // ---------------------------------------------------------------------------
    // 内部实现
    // ---------------------------------------------------------------------------

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = serverSocket.accept();
                if (connectionCount.get() >= MAX_CONNECTIONS) {
                    try { client.close(); } catch (IOException ignored) {}
                    continue;
                }
                Thread handle = new Thread(() -> {
                    connectionCount.incrementAndGet();
                    try {
                        handleClient(client);
                    } finally {
                        connectionCount.decrementAndGet();
                    }
                }, "FriendChat-Handler");
                handle.setDaemon(true);
                handle.start();
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[FriendChatServer] Accept 错误: " + e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ie) { break; }
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String remoteAddr = socket.getInetAddress().getHostAddress();

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.length() > FriendProtocol.MAX_MESSAGE_LENGTH) continue;

                for (MessageListener listener : listeners) {
                    try {
                        listener.onMessage(remoteAddr, line);
                    } catch (Exception e) {
                        System.err.println("[FriendChatServer] 监听器异常: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            // 客户端断开连接或服务器已关闭
        }
    }

    // ---------------------------------------------------------------------------
    // 内部类型
    // ---------------------------------------------------------------------------

    /** 消息监听器 */
    @FunctionalInterface
    public interface MessageListener {
        /** 收到一条完整 JSON 消息行 */
        void onMessage(String remoteIp, String jsonLine);
    }
}
