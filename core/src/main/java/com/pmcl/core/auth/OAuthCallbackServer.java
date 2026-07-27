package com.pmcl.core.auth;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OAuth 2.0 授权码回调服务器。
 * <p>
 * 启动本地 HTTP 服务器监听 {@code http://localhost:<port>/callback}，
 * 等待 Microsoft 授权后重定向到该 URL 并附带 {@code code} + {@code state} 参数。
 * <p>
 * 必须校验 {@code state}，防止本机其它进程伪造回调注入授权码。
 * 仅处理 {@code /callback}；其它路径（如 favicon）返回 404，绝不完成 future。
 */
public final class OAuthCallbackServer implements AutoCloseable {

    private final HttpServer server;
    private final int port;
    private final String expectedState;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();
    private final AtomicBoolean completed = new AtomicBoolean(false);

    public OAuthCallbackServer(String expectedState) throws IOException {
        if (expectedState == null || expectedState.isBlank()) {
            throw new IllegalArgumentException("OAuth state must not be blank");
        }
        this.expectedState = expectedState;
        // 端口 0 = 系统自动分配空闲端口
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.port = server.getAddress().getPort();
        server.createContext("/callback", this::handleCallback);
        server.start();
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        // 仅接受 GET；无 query / 无 code|error 时不碰 future（防 favicon 等探针误杀登录）
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendPlain(exchange, 405, "Method Not Allowed");
            return;
        }
        String query = exchange.getRequestURI().getQuery();
        String code = null;
        String state = null;
        String error = null;
        String errorDesc = null;
        if (query != null) {
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length != 2) continue;
                String key = kv[0];
                String value = URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
                switch (key) {
                    case "code": code = value; break;
                    case "state": state = value; break;
                    case "error": error = value; break;
                    case "error_description": errorDesc = value; break;
                }
            }
        }

        if (code == null && error == null) {
            sendPlain(exchange, 400, "Missing code or error");
            return;
        }

        String responseHtml;
        if (code != null) {
            if (!expectedState.equals(state)) {
                responseHtml = htmlPage("授权失败", "#F44336",
                        "OAuth state 校验失败，请返回 PMCL 重试。");
                if (completed.compareAndSet(false, true)) {
                    codeFuture.completeExceptionally(
                            new RuntimeException("OAuth state mismatch（可能的回调伪造）"));
                }
            } else if (completed.compareAndSet(false, true)) {
                responseHtml = htmlPage("授权成功", "#4CAF50",
                        "请返回 PMCL 启动器，登录即将完成。");
                codeFuture.complete(code);
            } else {
                responseHtml = htmlPage("已处理", "#4CAF50", "授权码已接收，请返回启动器。");
            }
        } else {
            responseHtml = htmlPage("授权失败", "#F44336",
                    errorDesc != null ? escapeHtml(errorDesc)
                            : escapeHtml(error != null ? error : "未知错误"));
            if (completed.compareAndSet(false, true)) {
                codeFuture.completeExceptionally(
                        new RuntimeException("授权失败: " + (error != null ? error : "未知")
                                + " " + (errorDesc != null ? errorDesc : "")));
            }
        }

        byte[] respBytes = responseHtml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, respBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(respBytes);
        }
    }

    private static void sendPlain(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String htmlPage(String title, String color, String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                + "<title>" + title + "</title>"
                + "<style>body{font-family:-apple-system,sans-serif;text-align:center;padding:60px;}"
                + "h1{color:" + color + ";}</style></head>"
                + "<body><h1>" + title + "</h1><p>" + body + "</p>"
                + "<script>window.close();</script></body></html>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    /** 获取本地监听端口。 */
    public int getPort() {
        return port;
    }

    /** 获取 redirect_uri，形如 {@code http://localhost:12345/callback}。 */
    public String getRedirectUri() {
        return "http://localhost:" + port + "/callback";
    }

    /** 获取授权码的 CompletableFuture，授权完成后会 complete。 */
    public CompletableFuture<String> getCodeFuture() {
        return codeFuture;
    }

    /** 带超时地等待授权码，超时抛 RuntimeException。 */
    public String awaitCode(long timeoutSeconds) throws IOException {
        try {
            return codeFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("等待授权超时（" + timeoutSeconds + "s）", e);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new IOException("等待授权失败: " + c.getMessage(), e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
