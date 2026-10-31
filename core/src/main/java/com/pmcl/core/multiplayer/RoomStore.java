package com.pmcl.core.multiplayer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 联机房间存档：创建或加入过的房间落到 {@code <workDir>/mp-rooms.json}，
 * 供联机页「我的房间」做一键重连、复制邀请码、重命名和删除。
 * <p>
 * 同一后端 + 同一邀请码（或中继地址）视为同一个房间：重复加入只刷新使用时间，
 * 不会堆出重复条目；用户改过的名字在重新加入时保留。
 */
public final class RoomStore {

    /** 房间存档条目。字段由 Gson 反射读写，保持可变 + 无参构造。 */
    public static final class Room {
        private String id = "";
        private String name = "";
        private String backend = "";
        private String joinCode = "";
        private String address = "";
        private int maxPlayers;
        private long savedAt;
        private long lastUsedAt;

        public Room() {
        }

        public Room(String id, String name, String backend, String joinCode,
                    String address, int maxPlayers, long savedAt, long lastUsedAt) {
            this.id = id;
            this.name = name;
            this.backend = backend;
            this.joinCode = joinCode;
            this.address = address;
            this.maxPlayers = maxPlayers;
            this.savedAt = savedAt;
            this.lastUsedAt = lastUsedAt;
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public String getBackend() { return backend; }
        public String getJoinCode() { return joinCode; }
        public String getAddress() { return address; }
        public int getMaxPlayers() { return maxPlayers; }
        public long getSavedAt() { return savedAt; }
        public long getLastUsedAt() { return lastUsedAt; }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final List<Room> rooms = new ArrayList<>();

    public RoomStore(Path file) {
        this.file = file;
        load();
    }

    /** 按最近使用排序的房间列表（副本）。 */
    public synchronized List<Room> list() {
        List<Room> copy = new ArrayList<>(rooms);
        copy.sort(Comparator.comparingLong(Room::getLastUsedAt).reversed());
        return copy;
    }

    public synchronized Room find(String id) {
        for (Room room : rooms) {
            if (room.id.equals(id)) return room;
        }
        return null;
    }

    /**
     * 记录一个房间。已存在（同后端同邀请码/地址）时刷新使用时间与地址，保留用户改过的名字。
     *
     * @return 落库后的房间
     */
    public synchronized Room remember(String backend, String joinCode, String address,
                                      int maxPlayers, String name) {
        if ((joinCode == null || joinCode.isBlank()) && (address == null || address.isBlank())) {
            throw new IllegalArgumentException("房间缺少邀请码与地址，无法保存");
        }
        String id = roomId(backend, joinCode, address);
        long now = System.currentTimeMillis();
        for (Room room : rooms) {
            if (!room.id.equals(id)) continue;
            room.lastUsedAt = now;
            if (address != null && !address.isBlank()) room.address = address;
            if (maxPlayers > 0) room.maxPlayers = maxPlayers;
            if (room.name.isBlank() && name != null && !name.isBlank()) room.name = name;
            save();
            return room;
        }
        Room room = new Room(id,
                name == null || name.isBlank() ? defaultName(backend, joinCode, address) : name,
                backend == null ? "" : backend,
                joinCode == null ? "" : joinCode,
                address == null ? "" : address,
                Math.max(0, maxPlayers), now, now);
        rooms.add(room);
        save();
        return room;
    }

    public synchronized void rename(String id, String name) {
        Room room = find(id);
        if (room == null || name == null || name.isBlank()) return;
        room.name = name.trim();
        save();
    }

    public synchronized void remove(String id) {
        if (rooms.removeIf(room -> room.id.equals(id))) save();
    }

    /** 房间唯一键：后端 + 房间身份的摘要，保证重连命中同一条记录。 */
    public static String roomId(String backend, String joinCode, String address) {
        String digest = sha256Hex(identity(joinCode, address));
        String prefix = backend == null ? "" : backend.trim().toUpperCase(java.util.Locale.ROOT);
        return prefix + "-" + digest.substring(0, Math.min(12, digest.length()));
    }

    /**
     * 房间身份。EasyTier / ConnectX 的邀请码里带着每次会话都会变的本机 peer 地址，
     * 直接拿整串当键会让同一个网络每重连一次就多出一条记录，所以只取「网络名 + 密钥」。
     */
    private static String identity(String joinCode, String address) {
        String code = joinCode == null ? "" : joinCode.trim();
        if (code.startsWith("pmcl-") || code.startsWith("pmcl2-") || code.startsWith("connectx-")) {
            try {
                String[] parts = MultiplayerManager.parseEasyTierInvitation(code);
                if (parts != null && parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
                    return parts[0] + "|" + parts[1];
                }
            } catch (RuntimeException ignored) {
                // 解析不了就退回整串邀请码
            }
        }
        return code.isEmpty() ? (address == null ? "" : address.trim()) : code;
    }

    private static String defaultName(String backend, String joinCode, String address) {
        String code = joinCode != null && !joinCode.isBlank() ? joinCode : address;
        String shortCode = code == null ? "" : code.trim();
        if (shortCode.length() > 8) shortCode = shortCode.substring(0, 8);
        String label = backend == null || backend.isBlank() ? "ROOM"
                : backend.trim().toUpperCase(java.util.Locale.ROOT);
        return shortCode.isEmpty() ? label : label + " " + shortCode;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) builder.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return builder.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private void load() {
        rooms.clear();
        try {
            if (!Files.isRegularFile(file)) return;
            List<Room> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    new TypeToken<List<Room>>() {}.getType());
            if (loaded != null) {
                for (Room room : loaded) {
                    if (room == null || room.id == null || room.id.isBlank()) continue;
                    rooms.add(room);
                }
            }
        } catch (IOException | RuntimeException e) {
            // 存档坏了不应挡住联机：按空列表继续，下次保存时覆盖
            System.err.println("[RoomStore] 读取房间存档失败: " + file + " (" + e + ")");
        }
    }

    private void save() {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, GSON.toJson(rooms), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[RoomStore] 写入房间存档失败: " + file + " (" + e + ")");
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
        }
    }
}
