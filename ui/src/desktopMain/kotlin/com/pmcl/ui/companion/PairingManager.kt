package com.pmcl.ui.companion

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pmcl.core.auth.TokenEncryptor
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
 * <p>
 * pairingCode / device token 经 [TokenEncryptor] 落盘；内存中保持明文供校验与签发。
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
        /** true=绑定 0.0.0.0（局域网可达）；false=仅 127.0.0.1（本机） */
        var exposeLan: Boolean = true,
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
            return String.format("%06d", rnd.nextInt(1, 1_000_000))
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
                var needsResave = false
                val rawCode = if (obj.has("pairingCode")) obj.get("pairingCode").asString else null
                if (rawCode != null && !TokenEncryptor.isEncrypted(rawCode)) needsResave = true
                val pairingCode = when {
                    rawCode == null -> generatePairingCode().also { needsResave = true }
                    else -> {
                        val plain = decryptSecret(rawCode)
                        if (plain.isEmpty()) generatePairingCode().also { needsResave = true } else plain
                    }
                }
                config = Config(
                    enabled = obj.has("enabled") && obj.get("enabled").asBoolean,
                    port = if (obj.has("port")) obj.get("port").asInt else 28520,
                    // 缺省 true：保持旧行为（手机伴随需 LAN）；用户可关掉收紧攻击面
                    exposeLan = !obj.has("exposeLan") || obj.get("exposeLan").asBoolean,
                    pairingCode = pairingCode,
                    serverName = if (obj.has("serverName")) obj.get("serverName").asString else "PMCL Desktop",
                    devices = mutableListOf()
                )
                if (obj.has("devices")) {
                    for (e in obj.getAsJsonArray("devices")) {
                        val d = e.asJsonObject
                        val rawToken = d.get("token").asString
                        if (!TokenEncryptor.isEncrypted(rawToken)) needsResave = true
                        val token = decryptSecret(rawToken)
                        if (token.isEmpty()) {
                            System.err.println("[PairingManager] 跳过无法解密的设备 token")
                            continue
                        }
                        config.devices.add(PairedDevice(
                            token = token,
                            deviceName = d.get("deviceName").asString,
                            pairedAt = d.get("pairedAt").asLong
                        ))
                    }
                }
                if (needsResave) {
                    System.err.println("[PairingManager] 迁移 companion.json 敏感字段为加密存储")
                    save()
                }
            } else {
                // 首次启动：生成配对码并立即持久化，避免重启后配对码变化
                config = Config()
                save()
            }
        } catch (e: Exception) {
            System.err.println("[PairingManager] 配置加载失败，重置为默认: ${e.message}")
            try {
                val backup = dataFile.resolveSibling(dataFile.fileName.toString() + ".corrupt")
                java.nio.file.Files.move(dataFile, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {}
            config = Config()
            // 不立即 save()，避免用空配置覆盖可能仅部分损坏的原文件
        }
    }

    @Synchronized
    fun save() {
        try {
            Files.createDirectories(dataFile.parent)
            val encCode = encryptSecret(config.pairingCode)
            if (config.pairingCode.isNotEmpty() && encCode.isEmpty()) {
                throw IllegalStateException("pairingCode 加密失败，拒绝明文落盘")
            }
            val obj = JsonObject()
            obj.addProperty("enabled", config.enabled)
            obj.addProperty("port", config.port)
            obj.addProperty("exposeLan", config.exposeLan)
            obj.addProperty("pairingCode", encCode)
            obj.addProperty("serverName", config.serverName)
            val arr = com.google.gson.JsonArray()
            for (d in config.devices) {
                val encToken = encryptSecret(d.token)
                if (d.token.isNotEmpty() && encToken.isEmpty()) {
                    throw IllegalStateException("device token 加密失败，拒绝明文落盘")
                }
                val dobj = JsonObject()
                dobj.addProperty("token", encToken)
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
            System.err.println("[PairingManager] 持久化失败: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun encryptSecret(plain: String): String = TokenEncryptor.encrypt(plain)

    private fun decryptSecret(stored: String): String {
        if (stored.isEmpty()) return stored
        val plain = TokenEncryptor.decrypt(stored)
        if (TokenEncryptor.isEncrypted(stored) && plain.isNullOrEmpty()) {
            System.err.println("[PairingManager] 密文解密失败（可能是机器标识变化）")
        }
        return plain ?: ""
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
    fun isExposeLan(): Boolean = config.exposeLan
    /** 绑定地址：LAN 暴露用 0.0.0.0，否则仅本机回环 */
    fun getBindHost(): String = if (config.exposeLan) "0.0.0.0" else "127.0.0.1"
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
    fun setExposeLan(expose: Boolean) {
        config.exposeLan = expose
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
    /** 配对失败计数（防暴力猜 6 位码） */
    @Volatile private var pairFailCount: Int = 0
    @Volatile private var pairLockUntilMs: Long = 0L

    @Synchronized
    fun pair(code: String, deviceName: String): Pair<String, String>? {
        val now = System.currentTimeMillis()
        if (now < pairLockUntilMs) {
            return null
        }
        // 提取数字部分（兼容完整格式和纯数字输入）
        val numeric = code.filter { it.isDigit() }
        if (numeric.length != 6) {
            registerPairFailure(now)
            return null
        }
        if (numeric != config.pairingCode) {
            registerPairFailure(now)
            return null
        }
        pairFailCount = 0
        pairLockUntilMs = 0L
        val token = generateToken()
        config.devices.add(PairedDevice(token, deviceName, Instant.now().toEpochMilli()))
        save()
        return token to config.serverName
    }

    /** 连续失败 5 次锁定 60s；之后每次失败再延长 60s（上限 10 分钟） */
    private fun registerPairFailure(now: Long) {
        pairFailCount++
        if (pairFailCount >= 5) {
            val extra = ((pairFailCount - 5).coerceAtMost(9)) * 60_000L
            pairLockUntilMs = now + 60_000L + extra
        }
    }

    /**
     * 验证 token 是否有效。
     */
    @Synchronized
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
