package com.pmcl.core.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 已添加的外置验证服务器（名称 + Yggdrasil API 地址）。不含密码。
 */
public final class ExternalAuthServerStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_SERVERS = 20;
    private final Object lock = new Object();

    public List<ExternalAuthServer> load(Path file) {
        synchronized (lock) {
            if (file == null || !Files.exists(file)) return new ArrayList<>();
            try {
                String json = Files.readString(file, StandardCharsets.UTF_8);
                Type type = new TypeToken<List<ExternalAuthServer>>() {}.getType();
                List<ExternalAuthServer> list = GSON.fromJson(json, type);
                if (list == null) return new ArrayList<>();
                List<ExternalAuthServer> clean = new ArrayList<>();
                for (ExternalAuthServer server : list) {
                    if (server == null || server.url == null || server.url.isBlank()) continue;
                    if (server.name == null || server.name.isBlank()) server.name = server.url;
                    clean.add(server);
                    if (clean.size() >= MAX_SERVERS) break;
                }
                return clean;
            } catch (Exception e) {
                System.err.println("[ExternalAuthServer] 读取失败: " + e.getMessage());
                return new ArrayList<>();
            }
        }
    }

    public void save(Path file, List<ExternalAuthServer> servers) throws IOException {
        synchronized (lock) {
            List<ExternalAuthServer> clean = new ArrayList<>();
            if (servers != null) {
                for (ExternalAuthServer server : servers) {
                    if (server == null || server.url == null || server.url.isBlank()) continue;
                    if (server.name == null || server.name.isBlank()) server.name = server.url;
                    clean.add(server);
                    if (clean.size() >= MAX_SERVERS) break;
                }
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp." + UUID.randomUUID());
            try {
                Files.writeString(tmp, GSON.toJson(clean), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
                TokenEncryptor.hardenFilePermissions(file);
                tmp = null;
            } finally {
                if (tmp != null) {
                    try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
                }
            }
        }
    }

    /** 外置验证服务器。{@code url} 是规范化后的 {@code /api/yggdrasil} 地址。 */
    public static final class ExternalAuthServer {
        public String name;
        public String url;
    }
}
