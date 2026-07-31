package com.pmcl.core.multiplayer;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Executors;

/**
 * Loopback reverse proxy that requires {@code Authorization: Bearer &lt;token&gt;}
 * before forwarding to Terracotta's unauthenticated HTTP API (C8).
 * <p>
 * Upstream Terracotta has no auth; this gate ensures only holders of the
 * session token (PMCL process) can drive room/panic endpoints via the
 * published proxy port. The backend port remains process-local and is not
 * exposed through {@link TerracottaManager} APIs.
 */
final class TerracottaAuthProxy implements AutoCloseable {

    static final String HEADER = "Authorization";

    private final String token;
    private final int backendPort;
    private final OkHttpClient http;
    private HttpServer server;
    private int listenPort;

    TerracottaAuthProxy(int backendPort, OkHttpClient http) {
        this.backendPort = backendPort;
        this.http = http;
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    String getToken() { return token; }
    int getListenPort() { return listenPort; }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "terracotta-auth-proxy");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        listenPort = server.getAddress().getPort();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            Headers reqHeaders = ex.getRequestHeaders();
            String auth = reqHeaders.getFirst(HEADER);
            String expected = "Bearer " + token;
            if (auth == null || !expected.equals(auth)) {
                byte[] body = "unauthorized".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(401, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
                return;
            }
            String path = ex.getRequestURI().getRawPath();
            String query = ex.getRequestURI().getRawQuery();
            String url = "http://127.0.0.1:" + backendPort + path
                    + (query != null && !query.isEmpty() ? "?" + query : "");
            Request.Builder rb = new Request.Builder().url(url);
            String method = ex.getRequestMethod();
            if ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)) {
                byte[] reqBody = ex.getRequestBody().readAllBytes();
                rb.method(method, okhttp3.RequestBody.create(reqBody,
                        okhttp3.MediaType.parse("application/octet-stream")));
            } else {
                rb.get();
            }
            try (Response resp = http.newCall(rb.build()).execute()) {
                int code = resp.code();
                byte[] body = resp.body() != null ? resp.body().bytes() : new byte[0];
                Headers out = ex.getResponseHeaders();
                String ct = resp.header("Content-Type");
                if (ct != null) out.set("Content-Type", ct);
                ex.sendResponseHeaders(code, body.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(body);
                }
            }
        } catch (Exception e) {
            byte[] body = ("proxy error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(502, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        } finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
