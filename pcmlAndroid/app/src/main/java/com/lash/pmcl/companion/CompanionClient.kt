package com.lash.pmcl.companion

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 配对/连接状态。
 */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val serverName: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Android 端伴随模式客户端：
 * - HTTP 配对（POST /pmcl/pair）换取令牌
 * - WebSocket 长连接（/pmcl，Bearer 令牌认证）
 * - 请求/响应/事件 JSON 协议，与桌面端 PmclHostServer 保持一致
 */
class CompanionClient(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // WebSocket 无超时
        .build()

    private var ws: WebSocket? = null
    private var token: String? = null
    private var host: String = ""
    private var port: Int = 28520

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val pendingRequests = mutableMapOf<String, CompletableDeferred<JsonObject>>()
    private val _events = MutableStateFlow<Pair<String, JsonObject?>?>(null)
    val events: StateFlow<Pair<String, JsonObject?>?> = _events

    // --- Pairing ---

    /**
     * 向桌面端发起配对请求。
     * @param host 桌面端 IP（局域网地址）
     * @param code  6 位配对码
     * @param deviceName 本机设备名称
     * @return Pair<token, serverName> 或 null
     */
    suspend fun pair(host: String, port: Int, code: String, deviceName: String): Pair<String, String>? {
        return withContext(Dispatchers.IO) {
            try {
                val json = gson.toJson(mapOf("code" to code, "deviceName" to deviceName))
                val mediaType = "application/json".toMediaType()
                val body = RequestBody.create(mediaType, json)
                val request = Request.Builder()
                    .url("http://$host:$port/pmcl/pair")
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    val err = try { JsonParser.parseString(errBody).asJsonObject.get("error")?.asString }
                        catch (_: Exception) { null }
                    _state.value = ConnectionState.Error(err ?: "配对失败 (${response.code})")
                    return@withContext null
                }
                val respJson = JsonParser.parseString(response.body!!.string()).asJsonObject
                val t = respJson.get("token").asString
                val sn = respJson.get("serverName").asString
                this@CompanionClient.host = host
                this@CompanionClient.port = port
                this@CompanionClient.token = t
                _state.value = ConnectionState.Connected(sn)
                t to sn
            } catch (e: Exception) {
                _state.value = ConnectionState.Error("配对请求失败: ${e.message}")
                null
            }
        }
    }

    // --- WebSocket ---

    fun connect(host: String, port: Int, token: String) {
        this.host = host
        this.port = port
        this.token = token
        _state.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url("ws://$host:$port/pmcl")
            .addHeader("Authorization", "Bearer $token")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _state.value = ConnectionState.Connected("PMCL Desktop")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = ConnectionState.Disconnected
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _state.value = ConnectionState.Error("连接断开: ${t.message}")
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val msg = JsonParser.parseString(text).asJsonObject
            val type = msg.get("type")?.asString ?: return
            when (type) {
                "response" -> {
                    val id = msg.get("id")?.asString ?: return
                    pendingRequests.remove(id)?.complete(msg)
                }
                "error" -> {
                    val id = msg.get("id")?.asString ?: return
                    pendingRequests.remove(id)?.completeExceptionally(
                        Exception(msg.get("error")?.toString() ?: "unknown error")
                    )
                }
                "event" -> {
                    val action = msg.get("action")?.asString ?: return
                    _events.value = action to msg.get("payload")?.asJsonObject
                }
            }
        } catch (_: Exception) { }
    }

    // --- Request helpers ---

    suspend fun request(action: String, payload: Map<String, Any>? = null): JsonObject? {
        val connected = _state.value is ConnectionState.Connected
        if (!connected) return null
        val id = UUID.randomUUID().toString().take(8)
        val req = mapOf(
            "type" to "request",
            "id" to id,
            "action" to action,
            "payload" to payload
        )
        val deferred = CompletableDeferred<JsonObject>()
        pendingRequests[id] = deferred
        ws?.send(gson.toJson(req))
        return try {
            withTimeout(10_000) { deferred.await() }
        } catch (e: Exception) {
            pendingRequests.remove(id)
            null
        }
    }

    // --- High-level actions ---

    suspend fun listVersions(): List<Map<String, Any>> {
        val resp = request("listVersions") ?: return emptyList()
        return try {
            val arr = resp.getAsJsonArray("payload")
            arr.map { gson.fromJson(it, Map::class.java) as Map<String, Any> }
        } catch (_: Exception) { emptyList() }
    }

    suspend fun launch(versionId: String): Boolean {
        val resp = request("launch", mapOf("versionId" to versionId)) ?: return false
        return !resp.has("error")
    }

    suspend fun kill(): Boolean {
        val resp = request("kill") ?: return false
        return !resp.has("error")
    }

    fun disconnect() {
        ws?.close(1000, "user disconnected")
        ws = null
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        _state.value = ConnectionState.Disconnected
    }

    fun destroy() {
        disconnect()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        scope.cancel()
    }
}
