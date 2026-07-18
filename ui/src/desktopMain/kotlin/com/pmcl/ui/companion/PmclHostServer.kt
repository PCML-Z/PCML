package com.pmcl.ui.companion

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.pmcl.core.auth.Account
import com.pmcl.core.launch.JavaRuntimeFinder
import com.pmcl.core.launch.ProcessMonitor
import com.pmcl.ui.viewmodel.LauncherViewModel
import io.ktor.server.application.*
import io.ktor.server.cio.CIO
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 桌面端 PMCL WebSocket 服务宿主。
 *
 * 暴露：
 * - POST /pmcl/pair        配对（用配对码换 token）
 * - WS  /pmcl              WebSocket（Bearer token 鉴权），承载所有 action
 *
 * action 列表：listVersions / launch / kill / subscribeStats / unsubscribeStats /
 *              installMod / getFriends / getMessages / sendMessage / ping
 */
class PmclHostServer(
    private val vm: LauncherViewModel,
    private val pairing: PairingManager
) {
    @Volatile
    private var server: EmbeddedServer<*, *>? = null
    private val gson: Gson = GsonBuilder().create()

    // ---- server 作用域：所有孤儿协程统一管理，stop() 时统一取消 ----
    private val serverScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> e.printStackTrace() }
    )

    // ---- handleLaunch 同步锁：防止并发启动多个游戏进程 ----
    private val launchLock = Any()

    // ---- 运行进程状态 ----
    private var currentProcess: Process? = null
    private var currentVersionId: String? = null
    private var currentStartTime: Long? = null
    private val processMonitor = ProcessMonitor()

    // ---- WebSocket 连接与会话 ----
    private val connections = ConcurrentHashMap<WebSocketSession, Unit>()
    private val statsSubscribers = java.util.Collections.newSetFromMap(ConcurrentHashMap<WebSocketSession, Boolean>())
    private var statsJob: Job? = null

    // ---- 好友事件监听 ----
    private var friendListener: java.util.function.Consumer<com.pmcl.core.friend.FriendManager.FriendEvent>? = null

    @Volatile
    private var running = false

    // ================================================================
    //  生命周期
    // ================================================================

    @Volatile
    private var actualPort: Int = 0

    fun getActualPort(): Int = actualPort

    fun start() {
        if (running) return
        val basePort = pairing.getPort()
        // 端口占用时自动递增重试（最多 10 次）
        var tryPort = basePort
        for (attempt in 0..9) {
            try {
                server = embeddedServer(CIO, host = "0.0.0.0", port = tryPort) {
                    install(WebSockets)
                    routing {
                        post("/pmcl/pair") { handlePair(call) }
                        webSocket("/pmcl") { handleWebSocket(this) }
                    }
                }.start(wait = false)
                running = true
                actualPort = tryPort
                if (tryPort != basePort) {
                    pairing.setPort(tryPort)  // 持久化新端口
                }
                registerFriendListener()
                println("[PmclHostServer] started on 0.0.0.0:$tryPort" +
                    if (tryPort != basePort) " (auto-incremented from $basePort)" else "")
                return
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                println("[PmclHostServer] port $tryPort unavailable: ${e.message}")
                tryPort++
            }
        }
        println("[PmclHostServer] FAILED to start after 10 port attempts")
    }

    fun stop() {
        running = false
        statsJob?.cancel()
        statsJob = null
        serverScope.cancel()
        server?.stop(1000, 2000)
        server = null
        connections.clear()
        statsSubscribers.clear()
        friendListener?.let { vm.core.friend()?.removeListener(it) }
        friendListener = null
        println("[PmclHostServer] stopped")
    }

    /** 服务是否正在运行 */
    fun isRunning(): Boolean = running

    /** 当前活跃 WebSocket 连接数 */
    fun activeConnectionCount(): Int = connections.size

    // ================================================================
    //  配对
    // ================================================================

    private suspend fun handlePair(call: ApplicationCall) {
        try {
            val body = call.receiveText()
            val obj = JsonParser.parseString(body).asJsonObject
            val code = obj.get("code")?.asString ?: ""
            val deviceName = obj.get("deviceName")?.asString ?: "unknown"

            val result = pairing.pair(code, deviceName)
            if (result == null) {
                call.respondText(
                    """{"error":"invalid_code","message":"配对码错误"}""",
                    io.ktor.http.ContentType.Application.Json,
                    io.ktor.http.HttpStatusCode.Forbidden
                )
            } else {
                val (token, serverName) = result
                val resp = JsonObject()
                resp.addProperty("token", token)
                resp.addProperty("serverName", serverName)
                call.respondText(resp.toString(), io.ktor.http.ContentType.Application.Json)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            call.respondText(
                """{"error":"bad_request","message":"${e.message}"}""",
                io.ktor.http.ContentType.Application.Json,
                io.ktor.http.HttpStatusCode.BadRequest
            )
        }
    }

    // ================================================================
    //  WebSocket 主循环
    // ================================================================

    private suspend fun handleWebSocket(ws: WebSocketServerSession) {
        // 鉴权：从 Authorization header 获取 token
        val authHeader = ws.call.request.headers["Authorization"]
        val token = authHeader?.removePrefix("Bearer ")?.trim()

        if (token == null || !pairing.validateToken(token)) {
            ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return
        }

        connections[ws] = Unit
        try {
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                val envelope = try {
                    JsonParser.parseString(text).asJsonObject
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    continue
                }

                val type = envelope.get("type")?.let { if (it.isJsonNull) null else if (it.isJsonPrimitive) it.asString else null }
                if (type == null) {
                    val errResp = errorEnvelope(null, null, "unknown_format", "缺少 type 字段")
                    ws.send(Frame.Text(gson.toJson(errResp)))
                    continue
                }
                if (type != "request") continue

                val id = envelope.get("id")?.let { if (it.isJsonNull) null else if (it.isJsonPrimitive) it.asString else null }
                    ?: java.util.UUID.randomUUID().toString()
                val action = envelope.get("action")?.let { if (it.isJsonNull) null else if (it.isJsonPrimitive) it.asString else null } ?: ""
                val payload = envelope.get("payload")

                val response = try {
                    handleAction(action, payload, ws)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    errorEnvelope(id, action, "handler_error", e.message ?: "unknown")
                }
                response.addProperty("id", id)
                ws.send(Frame.Text(gson.toJson(response)))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // 连接断开
        } finally {
            connections.remove(ws)
            statsSubscribers.remove(ws)
            if (statsSubscribers.isEmpty()) statsJob?.cancel()
        }
    }

    // ================================================================
    //  Action 分发
    // ================================================================

    private suspend fun handleAction(action: String, payload: JsonElement?, ws: WebSocketServerSession): JsonObject {
        return when (action) {
            "ping" -> okResponse(null)

            "listVersions" -> handleListVersions()

            "launch" -> handleLaunch(payload)

            "kill" -> handleKill()

            "subscribeStats" -> {
                statsSubscribers.add(ws)
                ensureStatsJob()
                okResponse(null)
            }

            "unsubscribeStats" -> {
                statsSubscribers.remove(ws)
                if (statsSubscribers.isEmpty()) statsJob?.cancel()
                okResponse(null)
            }

            "installMod" -> handleInstallMod(payload)

            "getFriends" -> handleGetFriends()

            "getMessages" -> handleGetMessages(payload)

            "sendMessage" -> handleSendMessage(payload)

            else -> errorEnvelope(null, action, "unknown_action", "未知动作: $action")
        }
    }

    // ================================================================
    //  Handler: 版本列表
    // ================================================================

    private fun handleListVersions(): JsonObject {
        val core = vm.core
        val versions = core.versions().listLocalVersions()
        val arr = com.google.gson.JsonArray()
        for (v in versions) {
            val obj = JsonObject()
            obj.addProperty("id", v)
            obj.addProperty("type", inferVersionType(v))
            obj.addProperty("installed", true)
            // lastPlayed 从 preferences 获取
            val lastPlayed = core.getPreferences().getLastPlayedTime(v)
            obj.addProperty("lastPlayed", lastPlayed)
            arr.add(obj)
        }
        return okResponse(arr)
    }

    private fun inferVersionType(versionId: String): String {
        // 快照格式：24w14a, 23w13a_or_whatever 等
        if (Regex("""^\d{2}w\d{2}[a-z]""").matches(versionId)) return "snapshot"
        if (versionId.contains("pre", ignoreCase = true)) return "snapshot"
        if (versionId.contains("rc", ignoreCase = true) &&
            !versionId.contains("craft", ignoreCase = true)) return "snapshot"
        if (versionId.contains("beta", ignoreCase = true)) return "old_beta"
        if (versionId.contains("alpha", ignoreCase = true)) return "old_alpha"
        return "release"
    }

    // ================================================================
    //  Handler: 启动 / 停止游戏
    // ================================================================

    private fun handleLaunch(payload: JsonElement?): JsonObject {
        // 同步检查 + 启动，避免并发请求触发多次启动
        synchronized(launchLock) {
            if (currentProcess?.isAlive == true) {
                return errorEnvelope(null, "launch", "already_running", "游戏已在运行")
            }

            val obj = payload?.asJsonObject ?: return errorEnvelope(null, "launch", "bad_request", "缺少 payload")
            val versionId = obj.get("versionId")?.asString
                ?: return errorEnvelope(null, "launch", "bad_request", "缺少 versionId")

            val account = vm.account.value
                ?: return errorEnvelope(null, "launch", "no_account", "桌面端未登录账号")

            val core = vm.core
            val config = core.getConfig()

            // 确定 Java 路径
            val requiredJavaVer = try {
                core.profileBuilder().getRequiredJavaVersion(versionId)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                8
            }

            val javaExe = run {
                val versionPath = core.getPreferences().getVersionJavaPath(versionId)
                if (versionPath.isNotEmpty()) versionPath
                else {
                    val customPath = core.getPreferences().getJavaPath()
                    if (customPath.isNotEmpty()) customPath
                    else JavaRuntimeFinder.findJavaExecutable(config.getRuntimesDir(), requiredJavaVer) ?: ""
                }
            }
            if (javaExe.isEmpty()) {
                return errorEnvelope(null, "launch", "no_java", "未找到 Java 运行时")
            }

            val javaMajorVer = JavaRuntimeFinder.getMajorVersion(javaExe) ?: 0
            val javaArch = JavaRuntimeFinder.getArchitecture(javaExe)

            // 构造 LaunchProfile
            val profile = try {
                core.profileBuilder().build(versionId, account, javaMajorVer, javaArch)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return errorEnvelope(null, "launch", "profile_build_failed", e.message ?: "构造启动配置失败")
            }

            // 启动：用同步 launch() 在后台线程获取 Process 引用
            currentVersionId = versionId
            currentStartTime = System.currentTimeMillis()

            serverScope.launch {
                try {
                    val proc = core.launch().launch(profile, javaExe, { }, null)
                    currentProcess = proc
                    processMonitor.startTracking(proc)

                    // 推送启动事件
                    val startPayload = JsonObject()
                    startPayload.addProperty("running", true)
                    startPayload.addProperty("versionId", versionId)
                    startPayload.addProperty("startedAt", isoTime(currentStartTime!!))
                    broadcastEvent("launchState", startPayload)

                    // 阻塞等待进程退出
                    val exitCode = proc.waitFor()
                    currentProcess = null
                    currentVersionId = null
                    currentStartTime = null

                    // 推送停止事件
                    val stopPayload = JsonObject()
                    stopPayload.addProperty("running", false)
                    stopPayload.addProperty("versionId", versionId)
                    broadcastEvent("launchState", stopPayload)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    currentProcess = null
                    currentVersionId = null
                    currentStartTime = null

                    val errPayload = JsonObject()
                    errPayload.addProperty("running", false)
                    errPayload.addProperty("versionId", versionId)
                    errPayload.addProperty("error", e.message ?: "启动失败")
                    broadcastEvent("launchState", errPayload)
                }
            }
        }

        return okResponse(null)
    }

    private fun handleKill(): JsonObject {
        val proc = currentProcess
        if (proc != null && proc.isAlive) {
            val killed = processMonitor.forceKill(proc)
            if (!killed) {
                return errorEnvelope(null, "kill", "kill_failed", "无法终止进程")
            }
        }
        currentProcess = null
        currentVersionId = null
        currentStartTime = null
        return okResponse(null)
    }

    // ================================================================
    //  Stats 推送
    // ================================================================

    private fun ensureStatsJob() {
        if (statsJob?.isActive == true) return
        statsJob = serverScope.launch {
            while (statsSubscribers.isNotEmpty()) {
                try {
                    val tick = collectStats()
                    val payload = statsToJson(tick)
                    broadcastEvent("statsTick", payload)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    e.printStackTrace()
                }
                delay(2000) // 2 秒推送一次（异常时同样退避后重试）
            }
        }
    }

    private data class StatsSnapshot(
        val cpuUsage: Double,
        val memoryUsage: Double,
        val memoryTotal: Double,
        val gpuName: String?,
        val gpuUsage: Double?,
        val networkRxKbps: Double?,
        val networkTxKbps: Double?,
        val gameCpuUsage: Double?,
        val gameMemoryMb: Double?,
        val fps: Int?
    )

    private fun collectStats(): StatsSnapshot {
        val rt = vm.core.runtime()
        val cpu = rt.getCpuLoad()
        val memTotal = rt.getTotalMemoryMb() / 1024.0
        val memUsed = memTotal - rt.getAvailableMemoryMb() / 1024.0
        val gpuName = rt.getPrimaryGpuName()
        val net = rt.getNetworkSpeedKbS()  // [上传 KB/s, 下载 KB/s]
        val gpuUsage = null  // oshi 不提供 GPU 使用率

        // 游戏进程采样
        var gameCpu: Double? = null
        var gameMem: Double? = null
        val proc = currentProcess
        if (proc != null && proc.isAlive) {
            try {
                val sample = processMonitor.sample(proc)
                gameCpu = sample.cpuPercent / 100.0
                gameMem = sample.rssMb.toDouble()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }

        return StatsSnapshot(
            cpuUsage = cpu,
            memoryUsage = memUsed,
            memoryTotal = memTotal,
            gpuName = gpuName,
            gpuUsage = gpuUsage,
            networkRxKbps = if (net != null && net.size >= 2) net[1] else null,  // 下载
            networkTxKbps = if (net != null && net.size >= 1) net[0] else null,  // 上传
            gameCpuUsage = gameCpu,
            gameMemoryMb = gameMem,
            fps = null
        )
    }

    private fun statsToJson(s: StatsSnapshot): JsonObject {
        val obj = JsonObject()
        obj.addProperty("cpuUsage", s.cpuUsage)
        obj.addProperty("memoryUsage", s.memoryUsage)
        obj.addProperty("memoryTotal", s.memoryTotal)
        if (s.gpuName != null) obj.addProperty("gpuName", s.gpuName)
        if (s.gpuUsage != null) obj.addProperty("gpuUsage", s.gpuUsage)
        if (s.networkRxKbps != null) obj.addProperty("networkRxKbps", s.networkRxKbps)
        if (s.networkTxKbps != null) obj.addProperty("networkTxKbps", s.networkTxKbps)
        if (s.gameCpuUsage != null) obj.addProperty("gameCpuUsage", s.gameCpuUsage)
        if (s.gameMemoryMb != null) obj.addProperty("gameMemoryMb", s.gameMemoryMb)
        if (s.fps != null) obj.addProperty("fps", s.fps)
        obj.addProperty("timestamp", isoTime(System.currentTimeMillis()))
        return obj
    }

    // ================================================================
    //  Handler: 安装模组到 PC
    // ================================================================

    private fun handleInstallMod(payload: JsonElement?): JsonObject {
        val obj = payload?.asJsonObject
            ?: return errorEnvelope(null, "installMod", "bad_request", "缺少 payload")

        val source = obj.get("source")?.asString ?: "modrinth"
        val projectId = obj.get("projectId")?.asString
            ?: return errorEnvelope(null, "installMod", "bad_request", "缺少 projectId")
        val versionId = obj.get("versionId")?.asString
            ?: return errorEnvelope(null, "installMod", "bad_request", "缺少 versionId")
        val targetMcVersion = obj.get("targetMcVersion")?.asString ?: ""

        if (source != "modrinth") {
            return errorEnvelope(null, "installMod", "unsupported_source", "仅支持 modrinth")
        }

        val core = vm.core
        val taskId = java.util.UUID.randomUUID().toString()

        // 异步执行安装
        serverScope.launch {
            try {
                broadcastInstallProgress(taskId, "downloading", 0.0, "正在查找模组文件…")

                // 通过 ModrinthClient 获取项目所有文件，找到 fileId == versionId 的
                val modrinthClient = core.modMarket().getModrinthClient()
                val files = modrinthClient.listFiles(projectId).join()
                val modFile = files.find { it.getFileId() == versionId }
                    ?: throw RuntimeException("未找到版本 $versionId")

                broadcastInstallProgress(taskId, "downloading", 0.1, "正在下载 ${modFile.getFileName()}…")

                val selectedVersion = vm.selectedVersion.value
                core.modMarket().installMod(
                    modFile, targetMcVersion, selectedVersion, core.getPreferences()
                ) { msg ->
                    broadcastInstallProgress(taskId, "installing", 0.5, msg)
                }.join()

                broadcastInstallProgress(taskId, "done", 1.0, "安装完成")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                broadcastInstallProgress(taskId, "error", 0.0, e.message ?: "安装失败")
            }
        }

        val resp = okResponse(null)
        resp.addProperty("taskId", taskId)
        return resp
    }

    private fun broadcastInstallProgress(taskId: String, stage: String, progress: Double, message: String) {
        val payload = JsonObject()
        payload.addProperty("taskId", taskId)
        payload.addProperty("stage", stage)
        payload.addProperty("progress", progress)
        payload.addProperty("message", message)
        serverScope.launch { broadcastEvent("installProgress", payload) }
    }

    // ================================================================
    //  Handler: 好友聊天
    // ================================================================

    private fun handleGetFriends(): JsonObject {
        val friendMgr = vm.core.friend()
        val arr = com.google.gson.JsonArray()
        if (friendMgr != null) {
            val friends = friendMgr.getFriends()
            for (f in friends) {
                val obj = JsonObject()
                obj.addProperty("id", f.identity)
                obj.addProperty("name", f.displayName)
                obj.addProperty("online", f.online)
                obj.addProperty("lastSeen", isoTime(f.lastSeen))
                obj.addProperty("unread", 0)  // 桌面端不维护未读数
                arr.add(obj)
            }
        }
        return okResponse(arr)
    }

    private fun handleGetMessages(payload: JsonElement?): JsonObject {
        val obj = payload?.asJsonObject
            ?: return errorEnvelope(null, "getMessages", "bad_request", "缺少 payload")
        val friendId = obj.get("friendId")?.asString
            ?: return errorEnvelope(null, "getMessages", "bad_request", "缺少 friendId")
        val limit = if (obj.has("limit")) obj.get("limit").asInt else 50

        val friendMgr = vm.core.friend()
        val arr = com.google.gson.JsonArray()
        if (friendMgr != null) {
            val msgs = friendMgr.getMessages(friendId)
            val start = maxOf(0, msgs.size - limit)
            for (m in msgs.subList(start, msgs.size)) {
                val mobj = JsonObject()
                mobj.addProperty("id", m.id)
                mobj.addProperty("friendId", friendId)
                mobj.addProperty("direction", if (m.fromMe) "sent" else "received")
                mobj.addProperty("text", m.text)
                mobj.addProperty("timestamp", isoTime(m.timestamp))
                arr.add(mobj)
            }
        }
        return okResponse(arr)
    }

    private fun handleSendMessage(payload: JsonElement?): JsonObject {
        val obj = payload?.asJsonObject
            ?: return errorEnvelope(null, "sendMessage", "bad_request", "缺少 payload")
        val friendId = obj.get("friendId")?.asString
            ?: return errorEnvelope(null, "sendMessage", "bad_request", "缺少 friendId")
        val text = obj.get("text")?.asString
            ?: return errorEnvelope(null, "sendMessage", "bad_request", "缺少 text")

        val friendMgr = vm.core.friend()
            ?: return errorEnvelope(null, "sendMessage", "friend_system_offline", "好友系统未启动")

        try {
            friendMgr.sendMessage(friendId, text)
            return okResponse(null)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return errorEnvelope(null, "sendMessage", "send_failed", e.message ?: "发送失败")
        }
    }

    private fun registerFriendListener() {
        val friendMgr = vm.core.friend() ?: return
        val listener = java.util.function.Consumer<FriendManagerEvent> { event ->
            if (event.type == com.pmcl.core.friend.FriendManager.FriendEvent.Type.MESSAGE_RECEIVED) {
                try {
                    val msg = event.data as com.pmcl.core.friend.FriendProtocol.ChatMessage
                    val payload = JsonObject()
                    payload.addProperty("id", msg.id)
                    payload.addProperty("friendId", msg.from ?: "")
                    payload.addProperty("direction", "received")
                    payload.addProperty("text", msg.text)
                    payload.addProperty("timestamp", isoTime(msg.timestamp))
                    serverScope.launch { broadcastEvent("messageReceived", payload) }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                }
            }
        }
        friendMgr.addListener(listener)
        friendListener = listener
    }

    // ================================================================
    //  广播
    // ================================================================

    private suspend fun broadcastEvent(action: String, payload: JsonObject) {
        val envelope = JsonObject()
        envelope.addProperty("type", "event")
        envelope.addProperty("action", action)
        envelope.add("payload", payload)

        val text = gson.toJson(envelope)
        for (ws in connections.keys.toList()) {
            try {
                ws.send(Frame.Text(text))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                connections.remove(ws)
                statsSubscribers.remove(ws)
            }
        }
    }

    // ================================================================
    //  辅助
    // ================================================================

    private fun okResponse(payload: JsonElement?): JsonObject {
        val obj = JsonObject()
        obj.addProperty("type", "response")
        if (payload != null) obj.add("payload", payload)
        return obj
    }

    private fun errorEnvelope(id: String?, action: String?, code: String, message: String): JsonObject {
        val obj = JsonObject()
        obj.addProperty("type", "error")
        if (id != null) obj.addProperty("id", id)
        if (action != null) obj.addProperty("action", action)
        val err = JsonObject()
        err.addProperty("code", code)
        err.addProperty("message", message)
        obj.add("error", err)
        return obj
    }

    private fun isoTime(epochMillis: Long): String {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))
    }
}

// 类型别名，避免导入冲突
private typealias FriendManagerEvent = com.pmcl.core.friend.FriendManager.FriendEvent
