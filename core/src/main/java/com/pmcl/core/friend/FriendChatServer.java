package com.pmcl.core.friend;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * TCP 聊天服务器：监听虚拟 IP 上的随机端口，接收来自好友的消息。
 * <p>
 * 每条消息是 JSON 行（以 {@code \n} 分隔），最大 {@link FriendProtocol#MAX_MESSAGE_LENGTH} 字节。
 * <p>
 * <b>安全模型（S3 修复）：</b>
 * <ul>
 *   <li>每条连接必须以 {@code auth} 握手开头：{@code {"type":"auth","identity":"XXXXX-...-XXXXX"}}}</li>
 *   <li>服务器通过 {@link #setIdentityValidator(Predicate)} 校验 identity 是否为已知好友</li>
 *   <li>握手必须在 5 秒内完成，否则连接被关闭</li>
 *   <li>未通过握手的连接不会触发任何 {@link MessageListener}</li>
 *   <li>已通过握手的连接的后续消息才会被分发</li>
 * </ul>
 */
public final class FriendChatServer implements AutoCloseable {

    /** 握手超时（毫秒） */
    private static final int AUTH_TIMEOUT_MS = 5000;
    /** 握手后正常读超时（毫秒） */
    private static final int READ_TIMEOUT_MS = 60000;

    private ServerSocket serverSocket;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread acceptThread;
    private int port = 0;

    private final CopyOnWriteArrayList<MessageListener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicInteger connectionCount = new AtomicInteger(0);
    private static final int MAX_CONNECTIONS = 50;

    /** 身份校验器：返回 true 表示该 identity 是已知好友，允许握手通过。
     *  默认拒绝所有连接（安全默认），必须由 FriendManager 设置。 */
    private volatile Predicate<String> identityValidator = id -> false;

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

    /**
     * 设置身份校验器。每条新连接会发送一条 {@code auth} 握手消息，
     * 服务器调用此校验器判断 identity 是否为已知好友。
     * <p>
     * 必须在 {@link #start()} 之前设置；未设置时默认拒绝所有连接。
     *
     * @param validator 返回 true 表示允许该 identity 通过握手
     */
    public void setIdentityValidator(Predicate<String> validator) {
        this.identityValidator = (validator != null) ? validator : (id -> false);
    }

    /** 启动服务器（随机端口） */
    public void start() throws IOException {
        start(0);
    }

    /** 启动服务器（指定端口） */
    public void start(int listenPort) throws IOException {
        if (running.get()) return;

        // 绑定到所有接口（0.0.0.0）—— 这是必要的，因为 LAN 内的对等节点通过组播发现后
        // 需要能 TCP 连接到本服务器。鉴权由 auth 握手提供，而非 IP 限制。
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
            // 阶段 1：握手——5 秒内必须收到 auth 消息，否则关闭连接
            socket.setSoTimeout(AUTH_TIMEOUT_MS);
            String remoteAddr = socket.getInetAddress().getHostAddress();
            String authLine = reader.readLine();
            if (authLine == null) {
                // 客户端连接后立即断开
                return;
            }
            if (authLine.length() > FriendProtocol.MAX_MESSAGE_LENGTH) {
                return;
            }
            String identity = parseAuthIdentity(authLine);
            if (identity == null) {
                System.err.println("[FriendChatServer] 拒绝连接: 无效握手 from " + remoteAddr);
                return;
            }
            if (!identityValidator.test(identity)) {
                System.err.println("[FriendChatServer] 拒绝连接: 未知身份 " + identity + " from " + remoteAddr);
                return;
            }

            // 握手成功——切换到正常读超时
            socket.setSoTimeout(READ_TIMEOUT_MS);
            // 通过 onAuthenticated 通知监听器（让上层能记录在线状态）
            for (MessageListener listener : listeners) {
                try {
                    listener.onAuthenticated(remoteAddr, identity);
                } catch (Exception e) {
                    System.err.println("[FriendChatServer] 监听器异常(onAuth): " + e.getMessage());
                }
            }

            // 阶段 2：分发后续消息
            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.length() > FriendProtocol.MAX_MESSAGE_LENGTH) continue;

                for (MessageListener listener : listeners) {
                    try {
                        listener.onMessage(remoteAddr, identity, line);
                    } catch (Exception e) {
                        System.err.println("[FriendChatServer] 监听器异常: " + e.getMessage());
                    }
                }
            }
        } catch (java.net.SocketTimeoutException e) {
            // 握手超时或读超时，关闭空闲连接
        } catch (IOException e) {
            // 客户端断开连接或服务器已关闭
        }
    }

    /** 从 auth 握手行中提取 identity 字段。返回 null 表示格式无效或非 auth 消息。 */
    private static String parseAuthIdentity(String line) {
        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            if (!"auth".equals(obj.get("type").getAsString())) return null;
            String id = obj.get("identity").getAsString();
            return (id != null && !id.isBlank()) ? id : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------------------
    // 内部类型
    // ---------------------------------------------------------------------------

    /** 消息监听器 */
    public interface MessageListener {
        /**
         * 连接通过握手时调用。可用于记录好友在线状态。
         *
         * @param remoteIp 远端 IP
         * @param identity 通过握手的好友身份 ID
         */
        default void onAuthenticated(String remoteIp, String identity) {}

        /**
         * 收到一条完整 JSON 消息行（在握手通过后才会被调用）。
         *
         * @param remoteIp   远端 IP
         * @param identity   发送方身份 ID（已通过握手校验）
         * @param jsonLine   消息内容
         */
        void onMessage(String remoteIp, String identity, String jsonLine);
    }
}
