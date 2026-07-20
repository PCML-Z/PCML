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

/**
 * OAuth 2.0 授权码回调服务器。
 * <p>
 * 启动本地 HTTP 服务器监听 {@code http://localhost:<port>/callback}，
 * 等待 Microsoft 授权后重定向到该 URL 并附带 {@code code} 参数。
 * <p>
 * 用法：
 * <pre>{@code
 * try (OAuthCallbackServer server = new OAuthCallbackServer()) {
 *     String redirectUri = server.getRedirectUri();
 *     String authUrl = buildAuthUrl(redirectUri);
 *     WikiBrowser.open(authUrl);  // 打开浏览器
 *     String code = server.getCodeFuture().get(5, TimeUnit.MINUTES);
 *     // 用 code 交换 access_token...
 * }
 * }</pre>
 */
public final class OAuthCallbackServer implements AutoCloseable {

    private final HttpServer server;
    private final int port;
    private final CompletableFuture<String> codeFuture = new CompletableFuture<>();

    public OAuthCallbackServer() throws IOException {
        // 端口 0 = 系统自动分配空闲端口
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        this.port = server.getAddress().getPort();
        server.createContext("/callback", this::handleCallback);
        // 根路径也接收（部分情况下 Microsoft 去掉 path）
        server.createContext("/", this::handleCallback);
        server.start();
    }

    private void handleCallback(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String code = null;
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
                    case "error": error = value; break;
                    case "error_description": errorDesc = value; break;
                }
            }
        }

        String responseHtml;
        if (code != null) {
            responseHtml = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                    + "<title>授权成功</title>"
                    + "<style>body{font-family:-apple-system,sans-serif;text-align:center;padding:60px;}"
                    + "h1{color:#4CAF50;}</style></head>"
                    + "<body><h1>授权成功</h1>"
                    + "<p>请返回 PMCL 启动器，登录即将完成。</p>"
                    + "<script>window.close();</script></body></html>";
            codeFuture.complete(code);
        } else {
            responseHtml = "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
                    + "<title>授权失败</title>"
                    + "<style>body{font-family:-apple-system,sans-serif;text-align:center;padding:60px;}"
                    + "h1{color:#F44336;}</style></head>"
                    + "<body><h1>授权失败</h1>"
                    + "<p>" + (errorDesc != null ? escapeHtml(errorDesc) : (error != null ? escapeHtml(error) : "未知错误")) + "</p>"
                    + "<p>请返回 PMCL 启动器重试。</p></body></html>";
            codeFuture.completeExceptionally(
                    new RuntimeException("授权失败: " + (error != null ? error : "未知") + " " + (errorDesc != null ? errorDesc : "")));
        }

        byte[] respBytes = responseHtml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        exchange.sendResponseHeaders(200, respBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(respBytes);
        }
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
            throw new IOException("登录超时：" + timeoutSeconds + " 秒内未完成授权");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IOException(cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("登录被中断", e);
        }
    }

    @Override
    public void close() {
        // 立即关闭，延迟 0 秒
        server.stop(0);
    }
}
