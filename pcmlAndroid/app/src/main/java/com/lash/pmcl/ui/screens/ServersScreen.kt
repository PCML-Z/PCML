package com.lash.pmcl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.lash.pmcl.core.multiplayer.ServerPinger
import com.lash.pmcl.core.paths.PmclPaths
import com.lash.pmcl.core.preferences.Preferences
import com.lash.pmcl.core.util.FileUtils
import kotlinx.coroutines.launch
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

/**
 * 全局桥接：在 MainActivity 初始化时注入 paths 与 preferences，
 * 供 ServersScreen 在不改变函数签名 `ServersScreen(serverPinger)` 的前提下，
 * 内部使用 paths 做文件持久化、使用 preferences 做直连启动设置。
 */
object ServersScreenBridge {
    @Volatile private var _paths: PmclPaths? = null
    @Volatile private var _preferences: Preferences? = null

    val paths: PmclPaths? get() = _paths
    val preferences: Preferences? get() = _preferences

    fun init(paths: PmclPaths, preferences: Preferences) {
        _paths = paths
        _preferences = preferences
    }
}

/** 一条用户保存的服务器记录（持久化到 servers.json）。 */
data class ServerEntry(
    val id: Long,
    val name: String,
    val host: String,
    val port: Int,
)

/** ping 状态。 */
enum class PingStatus { IDLE, PINGING, ONLINE, OFFLINE }

/** 单台服务器的实时 ping 结果。 */
data class ServerState(
    val status: PingStatus = PingStatus.IDLE,
    val latency: Long = 0,
    val onlinePlayers: Int = 0,
    val maxPlayers: Int = 0,
    val versionName: String = "",
    val motd: String = "",
    val error: String = "",
)

private const val SERVERS_FILE_NAME = "servers.json"
private val gson: Gson = Gson()

/** 从 paths.root/servers.json 读取服务器列表。文件不存在或损坏时返回空列表。 */
private fun loadServers(): List<ServerEntry> {
    val paths = ServersScreenBridge.paths ?: return emptyList()
    val file = paths.root.resolve(SERVERS_FILE_NAME)
    if (!Files.exists(file)) return emptyList()
    return try {
        val json = FileUtils.readString(file)
        if (json.isBlank()) emptyList()
        else {
            val type = object : TypeToken<List<ServerEntry>>() {}.type
            gson.fromJson<List<ServerEntry>>(json, type) ?: emptyList()
        }
    } catch (e: Exception) {
        System.err.println("[ServersScreen] 加载服务器列表失败: ${e.message}")
        emptyList()
    }
}

/** 将服务器列表写入 paths.root/servers.json（临时文件 + 原子移动，避免半成品覆盖）。 */
private fun saveServers(servers: List<ServerEntry>) {
    val paths = ServersScreenBridge.paths ?: return
    val file = paths.root.resolve(SERVERS_FILE_NAME)
    try {
        val tmp = file.resolveSibling("$SERVERS_FILE_NAME.tmp")
        FileUtils.writeString(tmp, gson.toJson(servers))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        System.err.println("[ServersScreen] 保存服务器列表失败: ${e.message}")
    }
}

/** 异步保存，避免阻塞 UI 线程。 */
private fun saveServersAsync(servers: List<ServerEntry>) {
    Thread { saveServers(servers) }.start()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(serverPinger: ServerPinger) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var loading by remember { mutableStateOf(true) }
    var servers by remember { mutableStateOf<List<ServerEntry>>(emptyList()) }
    var states by remember { mutableStateOf<Map<Long, ServerState>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ServerEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<ServerEntry?>(null) }
    val idCounter = remember { AtomicLong(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun pingServer(entry: ServerEntry) {
        states = states + (entry.id to ServerState(status = PingStatus.PINGING))
        Thread {
            val result = serverPinger.pingFull(entry.host, entry.port)
            val newState = if (result.isOnline) {
                ServerState(
                    status = PingStatus.ONLINE,
                    latency = result.latency,
                    onlinePlayers = result.onlinePlayers,
                    maxPlayers = result.maxPlayers,
                    versionName = result.versionName,
                    motd = result.motd,
                )
            } else {
                ServerState(
                    status = PingStatus.OFFLINE,
                    latency = result.latency,
                    error = result.error.ifEmpty {
                        if (result.latency == ServerPinger.TIMEOUT) "连接超时" else "无法连接"
                    },
                )
            }
            states = states + (entry.id to newState)
        }.start()
    }

    /** 直连启动：将服务器地址写入 preferences，启动时由启动参数读取。 */
    fun connectServer(entry: ServerEntry) {
        val prefs = ServersScreenBridge.preferences
        if (prefs != null) {
            prefs.setGameServerHost(entry.host)
            prefs.setGameServerPort(entry.port)
            scope.launch {
                snackbarHostState.showSnackbar("已设置直连服务器：${entry.host}:${entry.port}")
            }
        } else {
            scope.launch { snackbarHostState.showSnackbar("未初始化，无法设置直连") }
        }
    }

    // 启动时从文件加载服务器列表，加载完成后批量 ping
    LaunchedEffect(Unit) {
        Thread {
            try {
                val loaded = loadServers()
                if (loaded.isNotEmpty()) {
                    idCounter.set(loaded.maxOf { it.id } + 1)
                }
                servers = loaded
                loading = false
                loaded.forEach { pingServer(it) }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                loading = false
            }
        }.start()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("服务器") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }, enabled = !loading) {
                        Icon(Icons.Outlined.Add, contentDescription = "添加服务器")
                    }
                    IconButton(onClick = { servers.forEach { pingServer(it) } }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
                error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("加载失败", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(error!!, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                servers.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.Dns, contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text("暂无服务器", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.outline)
                            Text("点击右上角 + 添加",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(servers, key = { it.id }) { entry ->
                            ServerCard(
                                entry = entry,
                                state = states[entry.id] ?: ServerState(),
                                onConnect = { connectServer(entry) },
                                onEdit = { editTarget = entry },
                                onDelete = { deleteTarget = entry },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        ServerEditDialog(
            title = "添加服务器",
            initialName = "",
            initialHost = "",
            initialPort = "25565",
            onDismiss = { showAddDialog = false },
            onConfirm = { name, host, port ->
                val entry = ServerEntry(
                    id = idCounter.incrementAndGet(),
                    name = name,
                    host = host,
                    port = port,
                )
                servers = servers + entry
                saveServersAsync(servers)
                showAddDialog = false
                pingServer(entry)
            },
        )
    }

    editTarget?.let { target ->
        ServerEditDialog(
            title = "编辑服务器",
            initialName = target.name,
            initialHost = target.host,
            initialPort = target.port.toString(),
            onDismiss = { editTarget = null },
            onConfirm = { name, host, port ->
                val updated = target.copy(name = name, host = host, port = port)
                servers = servers.map { if (it.id == target.id) updated else it }
                saveServersAsync(servers)
                editTarget = null
                pingServer(updated)
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除服务器") },
            text = { Text("确定要删除「${target.name.ifEmpty { target.host }}」吗？") },
            confirmButton = {
                TextButton(onClick = {
                    servers = servers.filterNot { it.id == target.id }
                    states = states - target.id
                    saveServersAsync(servers)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ServerCard(
    entry: ServerEntry,
    state: ServerState,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Dns, contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name.ifEmpty { "${entry.host}:${entry.port}" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text("${entry.host}:${entry.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (state.status) {
                    PingStatus.PINGING -> {
                        Text("检测中…", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    PingStatus.ONLINE -> {
                        val info = buildString {
                            if (state.versionName.isNotEmpty()) append(state.versionName)
                            if (state.maxPlayers > 0) {
                                if (isNotEmpty()) append("  ")
                                append("在线 ${state.onlinePlayers}/${state.maxPlayers}")
                            }
                        }
                        if (info.isNotEmpty()) {
                            Text(info, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    PingStatus.OFFLINE -> {
                        Text(state.error.ifEmpty { "离线" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                    PingStatus.IDLE -> {}
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                when (state.status) {
                    PingStatus.PINGING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    PingStatus.ONLINE -> {
                        Text("${state.latency}ms",
                            style = MaterialTheme.typography.labelMedium,
                            color = latencyColor(state.latency))
                    }
                    PingStatus.OFFLINE -> {
                        Text("离线", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    PingStatus.IDLE -> {}
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    IconButton(onClick = onConnect) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "直连启动",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑",
                            modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun latencyColor(latency: Long): Color = when {
    latency < 0 -> MaterialTheme.colorScheme.outline
    latency < 100 -> MaterialTheme.colorScheme.primary
    latency < 300 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.outline
}

@Composable
private fun ServerEditDialog(
    title: String,
    initialName: String,
    initialHost: String,
    initialPort: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, host: String, port: Int) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }
    val portValid = port.toIntOrNull()?.let { it in 1..65535 } ?: false
    val canSubmit = host.isNotBlank() && portValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("地址") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), host.trim(), port.toInt()) },
                enabled = canSubmit,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
