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
