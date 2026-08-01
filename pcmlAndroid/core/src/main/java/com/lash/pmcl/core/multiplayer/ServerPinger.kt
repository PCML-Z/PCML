package com.lash.pmcl.core.multiplayer

import com.lash.pmcl.core.util.SsrfChecker
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * Minecraft Java 版服务器延迟检测。
 *
 * 通过发送 Handshake + Status Request 包，测量往返延迟（毫秒）。
 * 使用 Minecraft 协议（VarInt 编码）。
 *
 * 超时默认 3000ms，返回 -1 表示不可达，-2 表示超时。
 *
 * Android 版本：从 Java 移植，保留 SLP 协议（Handshake + Status Request）、
 * VarInt 编解码（含溢出保护）、手写 JSON 解析、SSRF 防护（使用 [SsrfChecker]）。
 * 使用 java.net.Socket。
 */
object ServerPinger {

    /** 不可达 */
    const val UNREACHABLE: Long = -1L
    /** 超时 */
    const val TIMEOUT: Long = -2L

    private const val DEFAULT_TIMEOUT_MS = 3000

    /**
     * ping 服务器，返回延迟（毫秒）。
     *
     * @param host 服务器地址
     * @param port 服务器端口
     * @return 延迟毫秒数；-1 不可达；-2 超时
     */
    fun ping(host: String?, port: Int): Long = ping(host, port, DEFAULT_TIMEOUT_MS)

    /**
     * ping 服务器，返回延迟（毫秒）。
     *
     * @param host    服务器地址
     * @param port    服务器端口
     * @param timeout 超时毫秒
     * @return 延迟毫秒数；-1 不可达；-2 超时
     */
    fun ping(host: String?, port: Int, timeout: Int): Long {
        // 输入校验：避免非法 host/port 触发未预期异常
        if (host.isNullOrEmpty()) return UNREACHABLE
        if (port <= 0 || port > 65535 || timeout <= 0) return UNREACHABLE
        val ssrf = SsrfChecker.validateHostAllowingPrivateLan(host)
        if (ssrf != null) {
            return UNREACHABLE
        }
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeout)
                socket.soTimeout = timeout

                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val `in` = DataInputStream(BufferedInputStream(socket.getInputStream()))

                // 构造 Handshake 包
                val hostBytes = host.toByteArray(StandardCharsets.UTF_8)
                // Handshake payload: protocolVersion=-1(VarInt) + host(VarInt len + bytes) + port(UShort) + nextState=1(VarInt)
                val handshakePayloadLen = 1 + varIntLength(hostBytes.size) + hostBytes.size + 2 + 1
                // 写包长度
                writeVarInt(out, handshakePayloadLen)
                // 写包 ID = 0
                writeVarInt(out, 0)
                // protocol version = -1 (ping 通用)
                writeVarInt(out, -1)
                // server address
                writeVarInt(out, hostBytes.size)
                out.write(hostBytes)
                // server port (unsigned short)
                out.writeShort(port)
                // next state = 1 (Status)
                writeVarInt(out, 1)

                // 发送 Status Request 包（空包，只有包 ID = 0）
                writeVarInt(out, 1) // 包长度=1
                writeVarInt(out, 0) // 包 ID=0
                out.flush()

                // 读取响应
                val packetLength = readVarInt(`in`) // 响应包总长度
                if (packetLength <= 0) return UNREACHABLE

                val packetId = readVarInt(`in`)
                if (packetId != 0) return UNREACHABLE // 期望 Status Response (ID=0)

                val jsonLen = readVarInt(`in`)
                if (jsonLen <= 0 || jsonLen > 65536) return UNREACHABLE

                val jsonBytes = ByteArray(jsonLen)
                `in`.readFully(jsonBytes)

                // 可选：发送 ping 包让服务器返回更精确的延迟，但 TCP 握手+响应已足够参考
                return System.currentTimeMillis() - start
            }
        } catch (e: SocketTimeoutException) {
            return TIMEOUT
        } catch (e: IOException) {
            return UNREACHABLE
        } catch (e: Exception) {
            return UNREACHABLE
        }
    }

    /**
     * 服务器完整状态信息（MOTD、在线人数、版本等）。
     * latency < 0 表示不可达或超时（使用 UNREACHABLE / TIMEOUT 常量）。
     */
    class ServerStatus(
        val latency: Long,
        motd: String?,
        val onlinePlayers: Int,
        val maxPlayers: Int,
        versionName: String?,
        val protocolVersion: Int,
        val iconBase64: String?,
        error: String?
    ) {
        val motd: String = motd ?: ""
        val versionName: String = versionName ?: ""
        val error: String = error ?: ""
        val isOnline: Boolean get() = latency >= 0
    }

    /**
     * 完整 ping 服务器，返回包含 MOTD、在线人数、版本等完整信息。
     *
     * @param host    服务器地址
     * @param port    服务器端口
     * @return ServerStatus 对象；latency < 0 表示不可达或超时
     */
    fun pingFull(host: String?, port: Int): ServerStatus = pingFull(host, port, DEFAULT_TIMEOUT_MS)

    /**
     * 完整 ping 服务器，返回包含 MOTD、在线人数、版本等完整信息。
     *
     * @param host    服务器地址
     * @param port    服务器端口
     * @param timeout 超时毫秒
     * @return ServerStatus 对象；latency < 0 表示不可达或超时
     */
    fun pingFull(host: String?, port: Int, timeout: Int): ServerStatus {
        if (host.isNullOrEmpty()) {
            return ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, "Invalid host/port")
        }
        if (port <= 0 || port > 65535 || timeout <= 0) {
            return ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, "Invalid host/port")
        }
        val ssrf = SsrfChecker.validateHostAllowingPrivateLan(host)
        if (ssrf != null) {
            return ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, ssrf)
        }
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeout)
                socket.soTimeout = timeout

                val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val `in` = DataInputStream(BufferedInputStream(socket.getInputStream()))

                // 构造 Handshake 包
                val hostBytes = host.toByteArray(StandardCharsets.UTF_8)
                val handshakePayloadLen = 1 + varIntLength(hostBytes.size) + hostBytes.size + 2 + 1
                writeVarInt(out, handshakePayloadLen)
                writeVarInt(out, 0)
                writeVarInt(out, -1)
                writeVarInt(out, hostBytes.size)
                out.write(hostBytes)
                out.writeShort(port)
                writeVarInt(out, 1)

                // 发送 Status Request 包
                writeVarInt(out, 1)
                writeVarInt(out, 0)
                out.flush()

                // 读取响应
                val packetLength = readVarInt(`in`)
                if (packetLength <= 0) {
                    return ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, "Empty response")
                }

                val packetId = readVarInt(`in`)
                if (packetId != 0) {
                    return ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, "Unexpected packet ID: $packetId")
                }

                val jsonLen = readVarInt(`in`)
                if (jsonLen <= 0 || jsonLen > 65536) {
                    return ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, "Invalid JSON length: $jsonLen")
                }

                val jsonBytes = ByteArray(jsonLen)
                `in`.readFully(jsonBytes)
                val latency = System.currentTimeMillis() - start

                val json = String(jsonBytes, StandardCharsets.UTF_8)
                return parseStatusJson(json, latency)
            }
        } catch (e: SocketTimeoutException) {
            return ServerStatus(TIMEOUT, "", 0, 0, "", 0, null, "Timeout")
        } catch (e: IOException) {
            return ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, e.message)
        } catch (e: Exception) {
            return ServerStatus(UNREACHABLE, "", 0, 0, "", 0, null, e.message)
        }
    }

    /**
     * 解析 SLP JSON 响应，提取 MOTD、在线人数、版本等信息。
     * 不使用外部 JSON 库，手动解析以保持依赖最小化。
     */
    private fun parseStatusJson(json: String, latency: Long): ServerStatus {
        val motd = extractDescription(json)
        val players = extractPlayers(json)
        val onlinePlayers = players[0]
        val maxPlayers = players[1]
        val versionName = extractStringField(json, "name")
        val protocolVersion = extractIntField(json, "protocol")
        val icon = extractStringField(json, "favicon")

        return ServerStatus(latency, motd, onlinePlayers, maxPlayers,
            versionName, protocolVersion, icon, "")
    }

    /**
     * 从 JSON 中提取 description 字段的文本内容。
     * 支持两种格式：
     * 1. "description": "纯文本"
     * 2. "description": { "text": "文本", "extra": [...] }
     */
    private fun extractDescription(json: String): String {
        val descIdx = json.indexOf("\"description\"")
        if (descIdx < 0) return ""
        val colonIdx = json.indexOf(':', descIdx)
        if (colonIdx < 0) return ""
        var valueStart = colonIdx + 1
        while (valueStart < json.length && json[valueStart].isWhitespace()) valueStart++
        if (valueStart >= json.length) return ""

        val firstChar = json[valueStart]
        if (firstChar == '"') {
            // 纯文本字符串
            return extractQuotedString(json, valueStart)
        } else if (firstChar == '{') {
            // JSON 对象，提取 "text" 和 "extra" 中的文本
            val sb = StringBuilder()
            val text = extractStringField(json.substring(descIdx), "text")
            if (text.isNotEmpty()) sb.append(text)
            // 提取 extra 数组中的文本（简化处理）
            val extraText = extractExtraText(json, descIdx)
            if (extraText.isNotEmpty()) sb.append(extraText)
            return sb.toString()
        }
        return ""
    }

    /** 提取 extra 数组中所有 "text" 字段并拼接 */
    private fun extractExtraText(json: String, startOffset: Int): String {
        val extraIdx = json.indexOf("\"extra\"", startOffset)
        if (extraIdx < 0) return ""
        val arrStart = json.indexOf('[', extraIdx)
        if (arrStart < 0) return ""
        val sb = StringBuilder()
        var depth = 0
        var i = arrStart
        while (i < json.length) {
            val c = json[i]
            if (c == '[') {
                depth++
            } else if (c == ']') {
                depth--
                if (depth == 0) break
            } else if (c == '"') {
                // 查找 "text": "..." 模式
                if (i + 7 < json.length && json.regionMatches(i, "\"text\"", 0, 6)) {
                    val colonIdx = json.indexOf(':', i + 6)
                    if (colonIdx >= 0) {
                        var valStart = colonIdx + 1
                        while (valStart < json.length && json[valStart].isWhitespace()) valStart++
                        if (valStart < json.length && json[valStart] == '"') {
                            sb.append(extractQuotedString(json, valStart))
                        }
                    }
                }
            }
            i++
        }
        return sb.toString()
    }

    /** 从 JSON 字符串中提取指定字段的字符串值（简化解析，适用于非嵌套场景） */
    private fun extractStringField(json: String, field: String): String {
        val pattern = "\"$field\""
        val idx = json.indexOf(pattern)
        if (idx < 0) return ""
        val colonIdx = json.indexOf(':', idx + pattern.length)
        if (colonIdx < 0) return ""
        var valueStart = colonIdx + 1
        while (valueStart < json.length && json[valueStart].isWhitespace()) valueStart++
        if (valueStart >= json.length || json[valueStart] != '"') return ""
        return extractQuotedString(json, valueStart)
    }

    /** 从 JSON 字符串中提取指定字段的整数值 */
    private fun extractIntField(json: String, field: String): Int {
        val pattern = "\"$field\""
        val idx = json.indexOf(pattern)
        if (idx < 0) return 0
        val colonIdx = json.indexOf(':', idx + pattern.length)
        if (colonIdx < 0) return 0
        var valueStart = colonIdx + 1
        while (valueStart < json.length && json[valueStart].isWhitespace()) valueStart++
        var valueEnd = valueStart
        while (valueEnd < json.length && (json[valueEnd].isDigit() || json[valueEnd] == '-')) {
            valueEnd++
        }
        if (valueEnd == valueStart) return 0
        return try {
            json.substring(valueStart, valueEnd).trim().toInt()
        } catch (e: NumberFormatException) {
            0
        }
    }

    /** 提取 players 对象中的 online 和 max 字段 */
    private fun extractPlayers(json: String): IntArray {
        val playersIdx = json.indexOf("\"players\"")
        if (playersIdx < 0) return intArrayOf(0, 0)
        val online = extractIntField(json.substring(playersIdx), "online")
        val max = extractIntField(json.substring(playersIdx), "max")
        return intArrayOf(online, max)
    }

    /** 从指定位置开始提取引号内的字符串（处理转义字符） */
    private fun extractQuotedString(json: String, startQuoteIdx: Int): String {
        if (startQuoteIdx >= json.length || json[startQuoteIdx] != '"') return ""
        val sb = StringBuilder()
        var i = startQuoteIdx + 1
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                val next = json[i + 1]
                when (next) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(next)
                }
                i += 2
            } else if (c == '"') {
                break
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // ===== VarInt 编码/解码 =====

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and (0x7F).inv() == 0) {
                out.writeByte(v)
                return
            }
            out.writeByte((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    private fun readVarInt(`in`: DataInputStream): Int {
        var value = 0
        var length = 0
        var current: Int
        do {
            current = `in`.readByte().toInt()
            if (length == 5) {
                // 第 5 字节最多只能使用低 4 位，否则溢出
                if (current and 0xF0 != 0) throw IOException("VarInt too big")
            }
            value = value or ((current and 0x7F) shl (length * 7))
            length++
            if (length > 5) throw IOException("VarInt too big")
        } while (current and 0x80 != 0)
        return value
    }

    private fun varIntLength(value: Int): Int {
        var v = value
        var len = 1
        while (v and (0x7F).inv() != 0) {
            v = v ushr 7
            len++
        }
        return len
    }
}
