package com.pmcl.core.multiplayer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft Java 版服务器延迟检测。
 * <p>
 * 通过发送 Handshake + Status Request 包，测量往返延迟（毫秒）。
 * 使用 Minecraft 协议（VarInt 编码）。
 * <p>
 * 超时默认 3000ms，返回 -1 表示不可达，-2 表示超时。
 */
public final class ServerPinger {

    /** 不可达 */
    public static final long UNREACHABLE = -1;
    /** 超时 */
    public static final long TIMEOUT = -2;

    private static final int DEFAULT_TIMEOUT_MS = 3000;

    /**
     * ping 服务器，返回延迟（毫秒）。
     *
     * @param host 服务器地址
     * @param port 服务器端口
     * @return 延迟毫秒数；-1 不可达；-2 超时
     */
    public static long ping(String host, int port) {
        return ping(host, port, DEFAULT_TIMEOUT_MS);
    }

    /**
     * ping 服务器，返回延迟（毫秒）。
     *
     * @param host    服务器地址
     * @param port    服务器端口
     * @param timeout 超时毫秒
     * @return 延迟毫秒数；-1 不可达；-2 超时
     */
    public static long ping(String host, int port, int timeout) {
        // 输入校验：避免非法 host/port 触发未预期异常
        if (host == null || host.isEmpty() || port <= 0 || port > 65535 || timeout <= 0) {
            return UNREACHABLE;
        }
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);

            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            // 构造 Handshake 包
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            // Handshake payload: protocolVersion=-1(VarInt) + host(VarInt len + bytes) + port(UShort) + nextState=1(VarInt)
            int handshakePayloadLen = 1 + varIntLength(hostBytes.length) + hostBytes.length + 2 + 1;
            // 写包长度
            writeVarInt(out, handshakePayloadLen);
            // 写包 ID = 0
            writeVarInt(out, 0);
            // protocol version = -1 (ping 通用)
            writeVarInt(out, -1 & 0xFFFFFFFF);
            // server address
            writeVarInt(out, hostBytes.length);
            out.write(hostBytes);
            // server port (unsigned short)
            out.writeShort(port);
            // next state = 1 (Status)
            writeVarInt(out, 1);

            // 发送 Status Request 包（空包，只有包 ID = 0）
            writeVarInt(out, 1); // 包长度=1
            writeVarInt(out, 0); // 包 ID=0
            out.flush();

            // 读取响应
            int packetLength = readVarInt(in); // 响应包总长度
            if (packetLength <= 0) return UNREACHABLE;

            int packetId = readVarInt(in);
            if (packetId != 0) return UNREACHABLE; // 期望 Status Response (ID=0)

            int jsonLen = readVarInt(in);
            if (jsonLen <= 0 || jsonLen > 65536) return UNREACHABLE;

            byte[] jsonBytes = new byte[jsonLen];
            in.readFully(jsonBytes);

            long latency = System.currentTimeMillis() - start;
            // 可选：发送 ping 包让服务器返回更精确的延迟，但 TCP 握手+响应已足够参考
            return latency;

        } catch (java.net.SocketTimeoutException e) {
            return TIMEOUT;
        } catch (IOException e) {
            return UNREACHABLE;
        } catch (Exception e) {
            return UNREACHABLE;
        }
    }

    /**
     * 服务器完整状态信息（MOTD、在线人数、版本等）。
     * latency &lt; 0 表示不可达或超时（使用 UNREACHABLE / TIMEOUT 常量）。
     */
    public static final class ServerStatus {
        private final long latency;
        private final String motd;          // 服务器描述文本（纯文本，已从 JSON 提取）
        private final int onlinePlayers;    // 当前在线人数
        private final int maxPlayers;       // 最大玩家数
        private final String versionName;   // 服务器版本名称（如 "1.20.4"）
        private final int protocolVersion;  // 服务器协议号
        private final String iconBase64;    // 服务器图标（Base64 PNG），可能为 null
        private final String error;         // 错误信息（latency < 0 时填充）

        public ServerStatus(long latency, String motd, int onlinePlayers, int maxPlayers,
                            String versionName, int protocolVersion, String iconBase64, String error) {
            this.latency = latency;
            this.motd = motd != null ? motd : "";
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.versionName = versionName != null ? versionName : "";
            this.protocolVersion = protocolVersion;
            this.iconBase64 = iconBase64;
            this.error = error != null ? error : "";
        }

        public long getLatency() { return latency; }
        public String getMotd() { return motd; }
        public int getOnlinePlayers() { return onlinePlayers; }
        public int getMaxPlayers() { return maxPlayers; }
        public String getVersionName() { return versionName; }
        public int getProtocolVersion() { return protocolVersion; }
        public String getIconBase64() { return iconBase64; }
        public String getError() { return error; }
        public boolean isOnline() { return latency >= 0; }
    }

    /**
     * 完整 ping 服务器，返回包含 MOTD、在线人数、版本等完整信息。
     *
     * @param host    服务器地址
     * @param port    服务器端口
     * @return ServerStatus 对象；latency &lt; 0 表示不可达或超时
     */
    public static ServerStatus pingFull(String host, int port) {
        return pingFull(host, port, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 完整 ping 服务器，返回包含 MOTD、在线人数、版本等完整信息。
     *
     * @param host    服务器地址
     * @param port    服务器端口
     * @param timeout 超时毫秒
     * @return ServerStatus 对象；latency &lt; 0 表示不可达或超时
     */
    public static ServerStatus pingFull(String host, int port, int timeout) {
        if (host == null || host.isEmpty() || port <= 0 || port > 65535 || timeout <= 0) {
            return new ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, "Invalid host/port");
        }
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeout);
            socket.setSoTimeout(timeout);

            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

            // 构造 Handshake 包
            byte[] hostBytes = host.getBytes(StandardCharsets.UTF_8);
            int handshakePayloadLen = 1 + varIntLength(hostBytes.length) + hostBytes.length + 2 + 1;
            writeVarInt(out, handshakePayloadLen);
            writeVarInt(out, 0);
            writeVarInt(out, -1 & 0xFFFFFFFF);
            writeVarInt(out, hostBytes.length);
            out.write(hostBytes);
            out.writeShort(port);
            writeVarInt(out, 1);

            // 发送 Status Request 包
            writeVarInt(out, 1);
            writeVarInt(out, 0);
            out.flush();

            // 读取响应
            int packetLength = readVarInt(in);
            if (packetLength <= 0) {
                return new ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, "Empty response");
            }

            int packetId = readVarInt(in);
            if (packetId != 0) {
                return new ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, "Unexpected packet ID: " + packetId);
            }

            int jsonLen = readVarInt(in);
            if (jsonLen <= 0 || jsonLen > 65536) {
                return new ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, "Invalid JSON length: " + jsonLen);
            }

            byte[] jsonBytes = new byte[jsonLen];
            in.readFully(jsonBytes);
            long latency = System.currentTimeMillis() - start;

            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            return parseStatusJson(json, latency);

        } catch (java.net.SocketTimeoutException e) {
            return new ServerStatus(TIMEOUT, "", 0, 0, "", 0, null, "Timeout");
        } catch (IOException e) {
            return new ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, e.getMessage());
        } catch (Exception e) {
            return new ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, e.getMessage());
        }
    }

    /**
     * 解析 SLP JSON 响应，提取 MOTD、在线人数、版本等信息。
     * 不使用外部 JSON 库，手动解析以保持依赖最小化。
     */
    private static ServerStatus parseStatusJson(String json, long latency) {
        String motd = extractDescription(json);
        int[] players = extractPlayers(json);
        int onlinePlayers = players[0];
        int maxPlayers = players[1];
        String versionName = extractStringField(json, "name");
        int protocolVersion = extractIntField(json, "protocol");
        String icon = extractStringField(json, "favicon");

        return new ServerStatus(latency, motd, onlinePlayers, maxPlayers,
                versionName, protocolVersion, icon, "");
    }

    /**
     * 从 JSON 中提取 description 字段的文本内容。
     * 支持两种格式：
     * 1. "description": "纯文本"
     * 2. "description": { "text": "文本", "extra": [...] }
     */
    private static String extractDescription(String json) {
        int descIdx = json.indexOf("\"description\"");
        if (descIdx < 0) return "";
        int colonIdx = json.indexOf(':', descIdx);
        if (colonIdx < 0) return "";
        int valueStart = colonIdx + 1;
        // 跳过空白
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        if (valueStart >= json.length()) return "";

        char firstChar = json.charAt(valueStart);
        if (firstChar == '"') {
            // 纯文本字符串
            return extractQuotedString(json, valueStart);
        } else if (firstChar == '{') {
            // JSON 对象，提取 "text" 和 "extra" 中的文本
            StringBuilder sb = new StringBuilder();
            String text = extractStringField(json.substring(descIdx), "text");
            if (!text.isEmpty()) sb.append(text);
            // 提取 extra 数组中的文本（简化处理）
            String extraText = extractExtraText(json, descIdx);
            if (!extraText.isEmpty()) sb.append(extraText);
            return sb.toString();
        }
        return "";
    }

    /** 提取 extra 数组中所有 "text" 字段并拼接 */
    private static String extractExtraText(String json, int startOffset) {
        int extraIdx = json.indexOf("\"extra\"", startOffset);
        if (extraIdx < 0) return "";
        int arrStart = json.indexOf('[', extraIdx);
        if (arrStart < 0) return "";
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        int i = arrStart;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') { depth--; if (depth == 0) break; }
            else if (c == '"') {
                // 查找 "text": "..." 模式
                if (i + 7 < json.length() && json.regionMatches(i, "\"text\"", 0, 6)) {
                    int colonIdx = json.indexOf(':', i + 6);
                    if (colonIdx >= 0) {
                        int valStart = colonIdx + 1;
                        while (valStart < json.length() && Character.isWhitespace(json.charAt(valStart))) valStart++;
                        if (valStart < json.length() && json.charAt(valStart) == '"') {
                            sb.append(extractQuotedString(json, valStart));
                        }
                    }
                }
            }
            i++;
        }
        return sb.toString();
    }

    /** 从 JSON 字符串中提取指定字段的字符串值（简化解析，适用于非嵌套场景） */
    private static String extractStringField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return "";
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return "";
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') return "";
        return extractQuotedString(json, valueStart);
    }

    /** 从 JSON 字符串中提取指定字段的整数值 */
    private static int extractIntField(String json, String field) {
        String pattern = "\"" + field + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return 0;
        int colonIdx = json.indexOf(':', idx + pattern.length());
        if (colonIdx < 0) return 0;
        int valueStart = colonIdx + 1;
        while (valueStart < json.length() && Character.isWhitespace(json.charAt(valueStart))) valueStart++;
        int valueEnd = valueStart;
        while (valueEnd < json.length() && (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '-')) {
            valueEnd++;
        }
        if (valueEnd == valueStart) return 0;
        try {
            return Integer.parseInt(json.substring(valueStart, valueEnd).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 提取 players 对象中的 online 和 max 字段 */
    private static int[] extractPlayers(String json) {
        int playersIdx = json.indexOf("\"players\"");
        if (playersIdx < 0) return new int[]{0, 0};
        int online = extractIntField(json.substring(playersIdx), "online");
        int max = extractIntField(json.substring(playersIdx), "max");
        return new int[]{online, max};
    }

    /** 从指定位置开始提取引号内的字符串（处理转义字符） */
    private static String extractQuotedString(String json, int startQuoteIdx) {
        if (startQuoteIdx >= json.length() || json.charAt(startQuoteIdx) != '"') return "";
        StringBuilder sb = new StringBuilder();
        int i = startQuoteIdx + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    default: sb.append(next); break;
                }
                i += 2;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    // ===== VarInt 编码/解码 =====

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.writeByte(value);
                return;
            }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        int length = 0;
        int current;
        do {
            current = in.readByte();
            if (length == 5) {
                // 第 5 字节最多只能使用低 4 位，否则溢出
                if ((current & 0xF0) != 0) throw new IOException("VarInt too big");
            }
            value |= (current & 0x7F) << (length * 7);
            length++;
            if (length > 5) throw new IOException("VarInt too big");
        } while ((current & 0x80) != 0);
        return value;
    }

    private static int varIntLength(int value) {
        int len = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            len++;
        }
        return len;
    }
}
