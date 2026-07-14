package com.pmcl.core.friend;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 好友系统消息协议：JSON-over-TCP/UDP。
 * <p>
 * 消息类型：
 * <ul>
 *   <li>{@code discover}    — UDP 广播：声明自己在线</li>
 *   <li>{@code msg}         — TCP 一对一聊天消息</li>
 *   <li>{@code friend_req}  — TCP 好友请求</li>
 *   <li>{@code friend_ack}  — TCP 好友请求应答</li>
 *   <li>{@code status}      — TCP 在线状态变更</li>
 * </ul>
 */
public final class FriendProtocol {

    private static final Gson GSON = new GsonBuilder().create();

    /** 最大消息长度（字节），防止 DoS */
    public static final int MAX_MESSAGE_LENGTH = 65536;

    /** UDP 广播内容 */
    public static final class DiscoverMessage {
        public String type = "discover";
        public String identity;
        public String name;
        public int port;

        public static DiscoverMessage fromJson(String json) {
            return GSON.fromJson(json, DiscoverMessage.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }
    }

    /** 聊天消息 */
    public static final class ChatMessage {
        public String type = "msg";
        public String id;
        public String text;
        public long timestamp;

        public static ChatMessage fromJson(String json) {
            return GSON.fromJson(json, ChatMessage.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }
    }

    /** 好友请求 */
    public static final class FriendRequest {
        public String type = "friend_req";
        public String identity;
        public String name;

        public static FriendRequest fromJson(String json) {
            return GSON.fromJson(json, FriendRequest.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }
    }

    /** 好友请求应答 */
    public static final class FriendAck {
        public String type = "friend_ack";
        public String identity;
        public String name;
        public boolean accepted;

        public static FriendAck fromJson(String json) {
            return GSON.fromJson(json, FriendAck.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }
    }

    /** 在线状态 */
    public static final class StatusMessage {
        public String type = "status";
        public boolean online;
        /** 已知在线的好友身份列表（批量同步） */
        public List<String> knownPeers;

        public static StatusMessage fromJson(String json) {
            return GSON.fromJson(json, StatusMessage.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }
    }

    /** 提取消息类型 */
    public static String peekType(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JsonElement elem = obj.get("type");
            return elem != null ? elem.getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 将完整 JSON 行包装为消息行（追加换行） */
    public static String toLine(String json) {
        return json + "\n";
    }
}
