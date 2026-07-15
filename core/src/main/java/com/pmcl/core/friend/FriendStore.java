package com.pmcl.core.friend;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 好友与聊天记录持久化存储（JSON 文件）。
 * <p>
 * 数据文件：
 * <ul>
 *   <li>{@code friends.json} — 好友列表及其元数据</li>
 *   <li>{@code messages/<identity-id>.json} — 每个好友的聊天记录</li>
 * </ul>
 */
public final class FriendStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_MESSAGES_PER_CONVERSATION = 500;

    private final Path dataDir;
    private final Path friendsFile;
    private final Path messagesDir;
    private final Map<String, FriendEntry> friends = new ConcurrentHashMap<>();
    private final Map<String, List<StoredMessage>> conversations = new ConcurrentHashMap<>();

    public FriendStore(Path dataDir) {
        this.dataDir = dataDir;
        this.friendsFile = dataDir.resolve("friends.json");
        this.messagesDir = dataDir.resolve("messages");
    }

    // ---------------------------------------------------------------------------
    // 初始化 & 持久化
    // ---------------------------------------------------------------------------

    /** 从磁盘加载所有数据 */
    public void load() {
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(messagesDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建好友存储目录", e);
        }

        // 加载好友列表
        if (Files.exists(friendsFile)) {
            try {
                String json = Files.readString(friendsFile, StandardCharsets.UTF_8);
                Type type = new TypeToken<List<FriendEntry>>() {}.getType();
                List<FriendEntry> entries = GSON.fromJson(json, type);
                if (entries != null) {
                    for (FriendEntry entry : entries) {
                        if (entry.identity != null && FriendIdentity.isValid(entry.identity)) {
                            friends.put(entry.identity, entry);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[FriendStore] 加载好友列表失败: " + e.getMessage());
            }
        }

        // 加载聊天记录
        try (var stream = Files.list(messagesDir)) {
            Path[] msgFiles = stream
                    .filter(p -> p.toString().endsWith(".json"))
                    .toArray(Path[]::new);
            for (Path file : msgFiles) {
                String fileName = file.getFileName().toString();
                String fileIdentity = fileName.substring(0, fileName.length() - 5); // 去掉 .json（无连字符）
                // 文件名是无连字符格式，需还原为带连字符的 identity 作为 conversations 的键
                String identity;
                try {
                    identity = FriendIdentity.parse(fileIdentity).toString();
                } catch (IllegalArgumentException e) {
                    System.err.println("[FriendStore] 跳过无效身份的聊天记录文件: " + fileName);
                    continue;
                }
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    Type type = new TypeToken<List<StoredMessage>>() {}.getType();
                    List<StoredMessage> msgs = GSON.fromJson(json, type);
                    if (msgs != null) {
                        conversations.put(identity, new CopyOnWriteArrayList<>(msgs));
                    }
                } catch (Exception e) {
                    System.err.println("[FriendStore] 加载聊天记录失败 (" + identity + "): " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[FriendStore] 列出聊天记录文件失败: " + e.getMessage());
        }
    }

    /** 保存好友列表 */
    public synchronized void saveFriends() {
        try {
            List<FriendEntry> entries = new ArrayList<>(friends.values());
            String json = GSON.toJson(entries);
            atomicWrite(friendsFile, json);
        } catch (IOException e) {
            System.err.println("[FriendStore] 保存好友列表失败: " + e.getMessage());
        }
    }

    /** 保存某好友的聊天记录 */
    public synchronized void saveMessages(String identity) {
        try {
            List<StoredMessage> msgs = conversations.getOrDefault(identity, Collections.emptyList());
            String json = GSON.toJson(msgs);
            Path file = messagesDir.resolve(identity.replace("-", "") + ".json");
            atomicWrite(file, json);
        } catch (IOException e) {
            System.err.println("[FriendStore] 保存聊天记录失败 (" + identity + "): " + e.getMessage());
        }
    }

    /** 原子写入：先写临时文件，再原子移动覆盖目标文件 */
    private void atomicWrite(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    // ---------------------------------------------------------------------------
    // 好友管理
    // ---------------------------------------------------------------------------

    /** 获取全部好友 */
    public List<FriendEntry> getAllFriends() {
        return friends.values().stream()
                .sorted(Comparator.comparing(f -> f.displayName))
                .collect(Collectors.toList());
    }

    /** 获取指定好友 */
    public FriendEntry getFriend(String identity) {
        return friends.get(identity);
    }

    /** 是否已是好友 */
    public boolean isFriend(String identity) {
        return friends.containsKey(identity);
    }

    /** 添加好友 */
    public void addFriend(String identity, String displayName, String ip, int port) {
        friends.computeIfAbsent(identity, k -> {
            FriendEntry entry = new FriendEntry();
            entry.identity = identity;
            entry.displayName = displayName;
            entry.addedAt = System.currentTimeMillis();
            return entry;
        });
        FriendEntry entry = friends.get(identity);
        if (entry != null) {
            // 不用空值覆盖已有的有效值
            if (ip != null && !ip.isEmpty()) {
                entry.lastIp = ip;
            }
            if (port > 0) {
                entry.lastPort = port;
            }
            entry.lastSeen = System.currentTimeMillis();
        }
        saveFriends();
    }

    /** 更新好友在线状态，返回状态是否实际变化 */
    public boolean updateOnlineStatus(String identity, boolean online, String ip, int port) {
        FriendEntry entry = friends.get(identity);
        if (entry == null) return false;
        boolean changed = entry.online != online;
        entry.online = online;
        if (online) {
            changed = changed || !Objects.equals(entry.lastIp, ip) || entry.lastPort != port;
            entry.lastIp = ip;
            entry.lastPort = port;
            entry.lastSeen = System.currentTimeMillis();
        }
        if (changed) {
            saveFriends();
        }
        return changed;
    }

    /** 删除好友 */
    public void removeFriend(String identity) {
        friends.remove(identity);
        conversations.remove(identity);
        saveFriends();
        try {
            Files.deleteIfExists(messagesDir.resolve(identity.replace("-", "") + ".json"));
        } catch (IOException ignored) {}
    }

    /** 重置所有好友为离线状态 */
    public void resetAllOnline() {
        for (FriendEntry entry : friends.values()) {
            entry.online = false;
        }
        saveFriends();
    }

    // ---------------------------------------------------------------------------
    // 消息管理
    // ---------------------------------------------------------------------------

    /** 获取与某好友的聊天记录（按时间排序） */
    public List<StoredMessage> getMessages(String identity) {
        return conversations.getOrDefault(identity, Collections.emptyList());
    }

    /** 添加一条消息 */
    public void addMessage(String peerIdentity, String msgId, String text, long timestamp, boolean fromMe) {
        StoredMessage msg = new StoredMessage();
        msg.id = msgId;
        msg.text = text;
        msg.timestamp = timestamp;
        msg.fromMe = fromMe;

        List<StoredMessage> msgs = conversations.computeIfAbsent(peerIdentity, k -> new CopyOnWriteArrayList<>());

        // 消息去重：检查 msgId 是否已存在
        for (StoredMessage existing : msgs) {
            if (msgId != null && msgId.equals(existing.id)) {
                return; // 已存在，不重复添加
            }
        }

        msgs.add(msg);

        // 限制最多 500 条
        if (msgs.size() > MAX_MESSAGES_PER_CONVERSATION) {
            msgs.remove(0);
        }

        saveMessages(peerIdentity);
    }

    // ---------------------------------------------------------------------------
    // 内部类型
    // ---------------------------------------------------------------------------

    /** 好友条目 */
    public static final class FriendEntry {
        public String identity;
        public volatile String displayName;
        public volatile String lastIp;
        public volatile int lastPort;
        public volatile boolean online;
        public volatile long addedAt;
        public volatile long lastSeen;
    }

    /** 聊天消息 */
    public static final class StoredMessage {
        public String id;
        public String text;
        public long timestamp;
        public boolean fromMe;
    }
}
