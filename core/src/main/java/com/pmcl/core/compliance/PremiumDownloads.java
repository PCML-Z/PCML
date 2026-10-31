package com.pmcl.core.compliance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.LauncherConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 记录「凭正版账户验证下载」的游戏版本。
 * <p>
 * 受限地区默认不提供游戏本体下载；持有已验证的正版账户时放行，交换条件是这些版本
 * 只能用正版账户启动——离线账户不得启动它们，否则正版下载物就成了绕过授权的副本。
 * 标记按版本 id 持久化，重装、修复不会丢失；版本删除后残留条目无害。
 */
public final class PremiumDownloads {

    private static final String FILE_NAME = "premium-downloads.json";
    private static final Object LOCK = new Object();
    private static volatile Set<String> cache;

    private PremiumDownloads() {}

    public static boolean isPremiumSourced(String versionId) {
        if (versionId == null || versionId.isBlank()) return false;
        return load().contains(versionId);
    }

    /** 记录该版本是在正版账户验证下下载的；已记录则无操作。 */
    public static void record(String versionId) {
        if (versionId == null || versionId.isBlank()) return;
        synchronized (LOCK) {
            Set<String> next = new HashSet<>(load());
            if (!next.add(versionId)) return;
            write(next);
            cache = next;
        }
    }

    public static void remove(String versionId) {
        if (versionId == null || versionId.isBlank()) return;
        synchronized (LOCK) {
            Set<String> next = new HashSet<>(load());
            if (!next.remove(versionId)) return;
            write(next);
            cache = next;
        }
    }

    private static Set<String> load() {
        Set<String> cached = cache;
        if (cached != null) return cached;
        synchronized (LOCK) {
            if (cache != null) return cache;
            Set<String> loaded = new HashSet<>();
            Path file = file();
            try {
                if (Files.isRegularFile(file)) {
                    JsonElement root = JsonParser.parseString(
                            Files.readString(file, StandardCharsets.UTF_8));
                    if (root.isJsonObject()) {
                        JsonElement versions = root.getAsJsonObject().get("versions");
                        if (versions != null && versions.isJsonArray()) {
                            for (JsonElement element : versions.getAsJsonArray()) {
                                if (element.isJsonPrimitive()) loaded.add(element.getAsString());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[PremiumDownloads] 读取失败: " + e.getMessage());
            }
            cache = loaded;
            return loaded;
        }
    }

    private static void write(Set<String> versions) {
        JsonArray array = new JsonArray();
        versions.stream().sorted().forEach(array::add);
        JsonObject root = new JsonObject();
        root.add("versions", array);
        Path file = file();
        Path tmp = null;
        try {
            Files.createDirectories(file.getParent());
            tmp = file.resolveSibling(file.getFileName() + ".tmp." + UUID.randomUUID());
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (Exception e) {
            System.err.println("[PremiumDownloads] 写入失败: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
    }

    private static Path file() {
        return LauncherConfig.pmclHome().resolve(FILE_NAME);
    }
}
