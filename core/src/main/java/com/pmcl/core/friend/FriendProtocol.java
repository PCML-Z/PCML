package com.pmcl.core.friend;

import com.google.gson.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 好友系统消息协议：JSON 行，经 {@link FriendSecureChannel} AES-GCM 加密后传输。
 * <p>
 * 消息类型：
 * <ul>
 *   <li>{@code discover}    — UDP 广播：声明自己在线（仍为明文发现；连接后全部加密）</li>
 *   <li>{@code msg}         — 一对一聊天消息</li>
 *   <li>{@code friend_req}  — 好友请求（携带公钥，不含共享密钥）</li>
 *   <li>{@code friend_ack}  — 好友请求应答</li>
 *   <li>{@code status}      — 在线状态变更</li>
 *   <li>{@code call_*}      — 通话信令</li>
 * </ul>
 */
public final class FriendProtocol {

    private static final Gson GSON = new GsonBuilder().create();

    /** 最大消息长度（字节），防止 DoS */
    public static final int MAX_MESSAGE_LENGTH = 65536;

    /**
     * 有界读行：按字节读取直到 {@code \n}；忽略 {@code \r}；超过 {@code maxBytes} 立即失败。
     * 避免 {@code BufferedReader.readLine} 先分配整行再校验导致的堆耗尽。
     *
     * @return UTF-8 解码后的一行（不含换行）；EOF 且无内容时返回 null
     */
    public static String readLineBounded(java.io.InputStream in, int maxBytes)
            throws java.io.IOException {
        if (in == null) throw new java.io.IOException("input is null");
        if (maxBytes <= 0) throw new java.io.IOException("maxBytes must be positive");
        java.io.ByteArrayOutputStream buf =
                new java.io.ByteArrayOutputStream(Math.min(256, maxBytes));
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') continue;
            if (buf.size() >= maxBytes) {
                throw new java.io.IOException("message exceeds max length " + maxBytes);
            }
            buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** UDP 广播内容（Ed25519 签名；未签名/验签失败的报文一律忽略） */
    public static final class DiscoverMessage {
        public String type = "discover";
        public String identity;
        public String name;
        public int port;
        /** Ed25519 公钥 Base64URL (SPKI) */
        public String ed;
        /** 签名时间戳（毫秒） */
        public long ts;
        /** Ed25519 签名 Base64URL：PMCL-DISCOVER\\n id\\n name\\n port\\n ts */
        public String sig;

        public static DiscoverMessage fromJson(String json) {
            return GSON.fromJson(json, DiscoverMessage.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }

        /** Canonical bytes signed for discovery authenticity. */
        public static byte[] signingPayload(String identity, String name, int port, long ts) {
            String n = name != null ? name : "";
            return ("PMCL-DISCOVER\n" + identity + "\n" + n + "\n" + port + "\n" + ts)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 聊天消息 */
    public static final class ChatMessage {
        public String type = "msg";
        public String id;
        public String text;
        public long timestamp;
        /** 发送方身份 ID（用于服务器路径识别来源） */
        public String from;
        /** 发送方显示名称 */
        public String fromName;

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
        /** 发送方的聊天服务器端口 */
        public int port;
        /**
         * @deprecated 禁止在明文/信道中传输共享密钥；保留字段仅为反序列化兼容，发送端必须为 null。
         */
        @Deprecated
        public String authSecret;
        /** 发送方 Ed25519 公钥（Base64URL SPKI） */
        public String ed25519Pub;
        /** 发送方 X25519 公钥（Base64URL SPKI） */
        public String x25519Pub;

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
        /** 发送方的聊天服务器端口 */
        public int port;
        /** @deprecated 见 {@link FriendRequest#authSecret} */
        @Deprecated
        public String authSecret;
        public String ed25519Pub;
        public String x25519Pub;

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
        /** 发送方身份 ID（用于服务器路径识别来源） */
        public String from;
        /** 已知在线的好友身份列表（批量同步） */
        public List<String> knownPeers;

        public static StatusMessage fromJson(String json) {
            return GSON.fromJson(json, StatusMessage.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }
    }

    /** 通话邀请 */
    public static final class CallInvite {
        public String type = "call_invite";
        public String callId;
        public String from;
        public String fromName;
        /** 媒体类型：video / audio / screen */
        public String mediaType;
        /** 发起方的视频 UDP 端口 */
        public int videoPort;

        public static CallInvite fromJson(String json) {
            return GSON.fromJson(json, CallInvite.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }
    }

    /** 接受通话 */
    public static final class CallAccept {
        public String type = "call_accept";
        public String callId;
        public String from;
        public boolean accept = true;
        /** SDP Offer（信令先于媒体层实现，可能为空字符串） */
        public String sdpOffer;
        /** 接受方的视频 UDP 端口 */
        public int videoPort;

        public static CallAccept fromJson(String json) {
            return GSON.fromJson(json, CallAccept.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }
    }

    /** 拒绝通话 */
    public static final class CallReject {
        public String type = "call_reject";
        public String callId;
        public String from;
        public String reason;

        public static CallReject fromJson(String json) {
            return GSON.fromJson(json, CallReject.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }
    }

    /** 结束通话 */
    public static final class CallEnd {
        public String type = "call_end";
        public String callId;
        public String from;
        public String reason;

        public static CallEnd fromJson(String json) {
            return GSON.fromJson(json, CallEnd.class);
        }

        public String toJson() {
            return GSON.toJson(this);
        }
    }

    /** ICE 候选交换 */
    public static final class CallIceCandidate {
        public String type = "call_ice";
        public String callId;
        public String from;
        /** SDP 候选字符串（信令先于媒体层实现，可能为空字符串） */
        public String candidate;
        public int sdpMLineIndex;
        public String sdpMid;
        /** 本地 ICE ufrag（首次发送候选时带上，用于远端设置） */
        public String ufrag;
        /** 本地 ICE password（首次发送候选时带上，用于远端设置） */
        public String pwd;

        public static CallIceCandidate fromJson(String json) {
            return GSON.fromJson(json, CallIceCandidate.class);
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
