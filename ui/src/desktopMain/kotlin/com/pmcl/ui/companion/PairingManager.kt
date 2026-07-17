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
            }
        } catch (e: Exception) {
            config = Config()
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

    fun getPairingCode(): String = config.pairingCode

    @Synchronized
    fun regeneratePairingCode(): String {
        config.pairingCode = generatePairingCode()
        save()
        return config.pairingCode
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
     * @return token + serverName，配对码错误返回 null
     */
    @Synchronized
    fun pair(code: String, deviceName: String): Pair<String, String>? {
        if (code != config.pairingCode) return null
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
     * 移除所有已配对设备。
     */
    @Synchronized
    fun unpairAll() {
        config.devices.clear()
        save()
    }

    fun getDevices(): List<PairedDevice> = config.devices.toList()
}
