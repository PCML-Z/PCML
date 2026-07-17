package com.pmcl.ui.companion

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * 伴随模式配对管理器：配对码生成、token 签发/验证、已配对设备列表。
 * 持久化到 ~/.pmcl/companion.json。
 */
class PairingManager(private val dataFile: Path) {

    data class PairedDevice(
        val token: String,
        val deviceName: String,
        val pairedAt: Long     // epoch millis
    )

    data class Config(
        var enabled: Boolean = false,
        var port: Int = 28520,
        var pairingCode: String = generatePairingCode(),
        var serverName: String = "PMCL Desktop",
        var devices: MutableList<PairedDevice> = mutableListOf()
    )

    @Volatile
    private var config: Config = Config()
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val random = SecureRandom()

    companion object {
        fun generatePairingCode(): String {
            val rnd = SecureRandom()
            return String.format("%06d", rnd.nextInt(1_000_000))
        }

        fun generateToken(): String = UUID.randomUUID().toString().replace("-", "") +
                UUID.randomUUID().toString().replace("-", "")

        /**
         * 将 IP 编码为大写字母：0-9 -> A-J, '.' -> K
         * 例如 "192.168.1.100" -> "BJCKBGIKBBAA"
         */
        fun encodeIp(ip: String): String {
            val sb = StringBuilder()
            for (c in ip) {
                when {
                    c in '0'..'9' -> sb.append(('A' + (c - '0')))
                    c == '.' -> sb.append('K')
                }
            }
            return sb.toString()
        }

        /**
         * 从字母串解码 IP：A-J -> 0-9, K -> '.', 遇到其他字母（填充符）停止
         */
        fun decodeIp(letters: String): String {
            val sb = StringBuilder()
            for (c in letters.uppercase()) {
                when {
                    c in 'A'..'J' -> sb.append((c - 'A'))
                    c == 'K' -> sb.append('.')
                    else -> break
                }
            }
            return sb.toString()
        }

        /**
         * 格式化配对码：000-000 XXXXX-XXXXX-XXXXX
         * 字母部分编码 IP，不足 15 位用 L 填充，超过截断
         */
        fun formatPairingCode(numeric: String, ip: String?): String {
            val encoded = if (ip != null) encodeIp(ip) else ""
            val padded = if (encoded.length >= 15) encoded.take(15) else encoded.padEnd(15, 'L')
            val p1 = padded.take(5)
            val p2 = padded.drop(5).take(5)
            val p3 = padded.drop(10).take(5)
            val n1 = numeric.take(3)
            val n2 = if (numeric.length >= 6) numeric.drop(3).take(3) else numeric.drop(3).padEnd(3, '0')
            return "$n1-$n2 $p1-$p2-$p3"
        }
    }

    init { load() }

    fun load() {
        try {
            if (Files.exists(dataFile)) {
                val json = String(Files.readAllBytes(dataFile), StandardCharsets.UTF_8)
                val obj = JsonParser.parseString(json).asJsonObject
                config = Config(
                    enabled = obj.has("enabled") && obj.get("enabled").asBoolean,
                    port = if (obj.has("port")) obj.get("port").asInt else 28520,
                    pairingCode = if (obj.has("pairingCode")) obj.get("pairingCode").asString else generatePairingCode(),
                    serverName = if (obj.has("serverName")) obj.get("serverName").asString else "PMCL Desktop",
                    devices = mutableListOf()
                )
                if (obj.has("devices")) {
                    for (e in obj.getAsJsonArray("devices")) {
                        val d = e.asJsonObject
                        config.devices.add(PairedDevice(
                            token = d.get("token").asString,
                            deviceName = d.get("deviceName").asString,
                            pairedAt = d.get("pairedAt").asLong
                        ))
                    }
                }
            } else {
                // 首次启动：生成配对码并立即持久化，避免重启后配对码变化
                config = Config()
                save()
            }
        } catch (e: Exception) {
            config = Config()
            save()
        }
    }

    @Synchronized
    fun save() {
        try {
            Files.createDirectories(dataFile.parent)
            val obj = JsonObject()
            obj.addProperty("enabled", config.enabled)
            obj.addProperty("port", config.port)
            obj.addProperty("pairingCode", config.pairingCode)
            obj.addProperty("serverName", config.serverName)
            val arr = com.google.gson.JsonArray()
            for (d in config.devices) {
                val dobj = JsonObject()
                dobj.addProperty("token", d.token)
                dobj.addProperty("deviceName", d.deviceName)
                dobj.addProperty("pairedAt", d.pairedAt)
                arr.add(dobj)
            }
            obj.add("devices", arr)

            val tmp = dataFile.resolveSibling(dataFile.fileName.toString() + ".tmp")
            Files.write(tmp, gson.toJson(obj).toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(tmp, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            // 持久化失败不中断运行
        }
    }

    // ---- 配对码 ----

    /**
     * 返回格式化配对码：000-000 XXXXX-XXXXX-XXXXX
     * 字母部分实时编码当前主局域网 IP（每次调用反映最新网络状态）
     */
    fun getPairingCode(): String {
        val ip = listLocalIps().firstOrNull()
        return formatPairingCode(config.pairingCode, ip)
    }

    @Synchronized
    fun regeneratePairingCode(): String {
        config.pairingCode = generatePairingCode()
        save()
        return getPairingCode()
    }

    // ---- 启用/端口 ----

    fun isEnabled(): Boolean = config.enabled
    fun getPort(): Int = config.port
    fun getServerName(): String = config.serverName

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        config.enabled = enabled
        save()
    }

    @Synchronized
    fun setPort(port: Int) {
        config.port = port
        save()
    }

    @Synchronized
    fun setServerName(name: String) {
        config.serverName = name
        save()
    }

    // ---- 配对/解绑 ----

    /**
     * 用配对码换取 token。配对码正确则签发新 token 并加入设备列表。
     * 接受完整格式（000-000 XXXXX-XXXXX-XXXXX）或纯数字，验证数字部分。
     * @return token + serverName，配对码错误返回 null
     */
    @Synchronized
    fun pair(code: String, deviceName: String): Pair<String, String>? {
        // 提取数字部分（兼容完整格式和纯数字输入）
        val numeric = code.filter { it.isDigit() }.take(6)
        if (numeric.length < 6 || numeric != config.pairingCode) return null
        val token = generateToken()
        config.devices.add(PairedDevice(token, deviceName, Instant.now().toEpochMilli()))
        save()
        return token to config.serverName
    }

    /**
     * 验证 token 是否有效。
     */
    fun validateToken(token: String): Boolean {
        return config.devices.any { it.token == token }
    }

    /**
     * 移除已配对设备（解绑）。
     */
    @Synchronized
    fun unpair(token: String): Boolean {
        val removed = config.devices.removeIf { it.token == token }
        if (removed) save()
        return removed
    }

    /**
     * 重命名已配对设备。
     * @return 是否成功（token 不存在则返回 false）
     */
    @Synchronized
    fun renameDevice(token: String, newName: String): Boolean {
        val idx = config.devices.indexOfFirst { it.token == token }
        if (idx < 0) return false
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return false
        config.devices[idx] = config.devices[idx].copy(deviceName = trimmed)
        save()
        return true
    }

    /**
     * 移除所有已配对设备。
     */
    @Synchronized
    fun unpairAll() {
        config.devices.clear()
        save()
    }

    fun getDevices(): List<PairedDevice> = config.devices.toList()
}
