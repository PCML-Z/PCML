package com.pmcl.core.cursor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** 只绑定 127.0.0.1 的 MCP HTTP。远程地址不是回环就直接拒绝。 */
public final class CursorAgentHttp {
    private final CursorAgentSession session;
    private final AtomicReference<String> token;
    private HttpServer server;
    private ExecutorService pool;
    private volatile int port;

    public CursorAgentHttp(CursorAgentSession session, String token) {
        this.session = session;
        this.token = new AtomicReference<>(token == null ? "" : token);
    }

    public synchronized int start(int preferred) throws IOException {
        if (server != null) return port;
        if (!CursorAgentStore.validToken(token.get())) throw new IOException("no token");
        int begin = preferred == 0 ? 0 : CursorAgentStore.normalizePort(preferred);
        int tries = preferred == 0 ? 1 : 10;
        BindException last = null;
        for (int i = 0; i < tries; i++) {
            int candidate = begin == 0 ? 0 : begin + i;
            if (candidate > 65535) break;
            try {
                bind(candidate);
                return port;
            } catch (BindException e) {
                last = e;
            }
        }
        if (last != null) throw last;
        throw new BindException("bind");
    }

    public synchronized void stop() {
        HttpServer running = server;
        ExecutorService workers = pool;
        server = null;
        pool = null;
        port = 0;
        if (running != null) running.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    public void setToken(String next) {
        token.set(next == null ? "" : next);
    }

    public int port() {
        return port;
    }

    public synchronized InetAddress boundHost() {
        if (server == null || server.getAddress() == null) return null;
        return server.getAddress().getAddress();
    }

    private void bind(int candidate) throws IOException {
        HttpServer created = HttpServer.create(new InetSocketAddress(loopback(), candidate), 8);
        ExecutorService workers = null;
        try {
            InetAddress bound = created.getAddress().getAddress();
            if (bound == null || !bound.isLoopbackAddress() || bound.isAnyLocalAddress()) {
                throw new IOException("refusing non-loopback bind");
            }
            workers = Executors.newFixedThreadPool(4, runnable -> {
                Thread thread = new Thread(runnable, "pmcl-cursor");
                thread.setDaemon(true);
                return thread;
            });
            created.setExecutor(workers);
            created.createContext("/mcp", this::handle);
            created.start();
            this.server = created;
            this.pool = workers;
            this.port = created.getAddress().getPort();
        } catch (IOException e) {
            created.stop(0);
            if (workers != null) workers.shutdownNow();
            this.server = null;
            this.pool = null;
            this.port = 0;
            throw e;
        }
    }

    private void handle(HttpExchange exchange) {
        try {
            InetAddress remote = exchange.getRemoteAddress() == null ? null : exchange.getRemoteAddress().getAddress();
            if (remote == null || !remote.isLoopbackAddress()) {
                write(exchange, 403, "{\"error\":\"local_only\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            String path = exchange.getRequestURI() == null ? "" : exchange.getRequestURI().getPath();
            if (!"/mcp".equals(path)) {
                write(exchange, 404, "{\"error\":\"not_found\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return;
            }
            int localPort = exchange.getLocalAddress().getPort();
            CursorAgentEndpoint.Reply reply = CursorAgentEndpoint.handle(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Host"),
                    exchange.getRequestHeaders().getFirst("Origin"),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    readBody(exchange),
                    token.get(),
                    localPort,
                    session);
            write(exchange, reply.status, reply.body);
        } catch (Exception e) {
            try {
                write(exchange, 500, "{\"error\":\"failed\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException ignored) {}
        } finally {
            exchange.close();
        }
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return in.readNBytes(CursorAgentEndpoint.MAX_BODY + 1);
        }
    }

    private static void write(HttpExchange exchange, int status, byte[] body) throws IOException {
        byte[] payload = body == null ? new byte[0] : body;
        if (payload.length > 0) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        }
        exchange.sendResponseHeaders(status, payload.length == 0 ? -1 : payload.length);
        if (payload.length > 0) exchange.getResponseBody().write(payload);
    }

    private static InetAddress loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
