package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.multiplayer.ServerPinger
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.util.Base64

@Composable
fun ServersPage(vm: LauncherViewModel) {
    // 进入页面时加载服务器列表并自动 ping 全部
    LaunchedEffect(Unit) {
        vm.loadFavoriteServers()
        vm.pingAllServersFull()
    }

    val servers by vm.favoriteServers.collectAsState()
    val statuses by vm.serverStatuses.collectAsState()
    val pinging by vm.pingingServers.collectAsState()
    val scroll = rememberScrollState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editIndex by remember { mutableStateOf<Int?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll)) {
        // 标题栏 + 操作按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("servers.title"), style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // 全部刷新
            OutlinedButton(
                onClick = { vm.pingAllServersFull() },
                enabled = servers.isNotEmpty()
            ) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("servers.refresh_all"))
            }
            Spacer(Modifier.width(8.dp))
            // 添加服务器
            Button(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("servers.add"))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(I18n.t("servers.hint"),
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))

        if (servers.isEmpty()) {
            // 空状态
            Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Dns, null, Modifier.size(48.dp),
                         tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text(I18n.t("servers.empty"),
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text(I18n.t("servers.empty_hint"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            // 服务器卡片列表
            servers.forEachIndexed { index, server ->
                val key = "${server.host}:${server.port}"
                val status = statuses[key]
                val isPinging = key in pinging
                ServerCard(
                    name = server.name,
                    host = server.host,
                    port = server.port,
                    status = status,
                    isPinging = isPinging,
                    onPing = { vm.pingServerFull(server.host, server.port) },
                    onConnect = { vm.setDirectConnectServer(server.host, server.port) },
                    onEdit = { editIndex = index },
                    onDelete = { deleteIndex = index }
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // 添加对话框
    if (showAddDialog) {
        ServerEditDialog(
            title = I18n.t("servers.add_server"),
            initialName = "",
            initialHost = "",
            initialPort = "25565",
            onConfirm = { name, host, port ->
                vm.addFavoriteServer(name, host, port)
                showAddDialog = false
                // 添加后自动 ping
                vm.pingServerFull(host, port)
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // 编辑对话框
    editIndex?.let { idx ->
        val s = servers.getOrNull(idx)
        if (s != null) {
            ServerEditDialog(
                title = I18n.t("servers.edit_server"),
                initialName = s.name,
                initialHost = s.host,
                initialPort = s.port.toString(),
                onConfirm = { name, host, port ->
                    vm.updateFavoriteServer(idx, name, host, port)
                    editIndex = null
                    // 编辑后重新 ping
                    vm.pingServerFull(host, port)
                },
                onDismiss = { editIndex = null }
            )
        } else {
            editIndex = null
        }
    }

    // 删除确认对话框
    deleteIndex?.let { idx ->
        val s = servers.getOrNull(idx)
        if (s != null) {
            AlertDialog(
                onDismissRequest = { deleteIndex = null },
                title = { Text(I18n.t("servers.delete_title")) },
                text = { Text(I18n.t("servers.delete_confirm", s.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.removeFavoriteServer(idx)
                        deleteIndex = null
                    }) {
                        Text(I18n.t("common.delete"), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { deleteIndex = null }) {
                        Text(I18n.t("common.cancel"))
                    }
                }
            )
        } else {
            deleteIndex = null
        }
    }
}

/** 单个服务器卡片，展示完整状态信息 */
@Composable
private fun ServerCard(
    name: String,
    host: String,
    port: Int,
    status: ServerPinger.ServerStatus?,
    isPinging: Boolean,
    onPing: () -> Unit,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
        Column(Modifier.padding(12.dp)) {
            // 第一行：图标 + 名称 + 地址 + 操作按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 服务器图标（从 Base64 favicon 解码）
                ServerIcon(status?.iconBase64, 40)
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$host:$port", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace)
                }

                // 单独 ping
                IconButton(onClick = onPing, enabled = !isPinging) {
                    if (isPinging) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Speed, I18n.t("servers.ping"), Modifier.size(18.dp))
                    }
                }
                // 连接
                IconButton(onClick = onConnect) {
                    Icon(Icons.Filled.PlayArrow, I18n.t("servers.connect"), Modifier.size(18.dp),
                         tint = MaterialTheme.colorScheme.primary)
                }
                // 编辑
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, I18n.t("common.edit"), Modifier.size(18.dp))
                }
                // 删除
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, I18n.t("common.delete"), Modifier.size(18.dp),
                         tint = MaterialTheme.colorScheme.error)
                }
            }

            // 第二行：状态信息（MOTD / 延迟 / 人数 / 版本）
            if (status != null && status.isOnline) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // MOTD
                if (status.motd.isNotEmpty()) {
                    Text(status.motd,
                         style = MaterialTheme.typography.bodySmall,
                         maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                }

                // 标签行：延迟 + 人数 + 版本
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 延迟标签
                    StatusChip(
                        text = "${status.latency}ms",
                        color = when {
                            status.latency < 100 -> MaterialTheme.colorScheme.primary
                            status.latency < 300 -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline
                        }
                    )
                    // 在线人数
                    StatusChip(
                        text = I18n.t("servers.players_count", status.onlinePlayers, status.maxPlayers),
                        color = MaterialTheme.colorScheme.secondary
                    )
                    // 版本
                    if (status.versionName.isNotEmpty()) {
                        StatusChip(
                            text = status.versionName,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else if (status != null && !status.isOnline) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Dns, null, Modifier.size(14.dp),
                         tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (status.latency == ServerPinger.TIMEOUT) I18n.t("servers.timeout")
                        else I18n.t("servers.offline"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    if (status.error.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(status.error.take(40),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline,
                             maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else if (isPinging) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(I18n.t("servers.pinging"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** 服务器图标（从 Base64 编码的 PNG 解码） */
@Composable
private fun ServerIcon(iconBase64: String?, sizeDp: Int) {
    var imageBitmap by remember(iconBase64) {
        mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    }
    LaunchedEffect(iconBase64) {
        if (iconBase64 == null || iconBase64.isEmpty()) {
            imageBitmap = null
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) {
            try {
                // favicon 格式为 "data:image/png;base64,<base64>"，需去掉前缀
                val b64 = if (iconBase64.contains(",")) {
                    iconBase64.substring(iconBase64.indexOf(',') + 1)
                } else {
                    iconBase64
                }
                val bytes = Base64.getDecoder().decode(b64)
                imageBitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            } catch (_: Throwable) {
                imageBitmap = null
            }
        }
    }
    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = null,
            modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(6.dp))
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.size(sizeDp.dp)
        ) {
            Icon(Icons.Filled.Dns, null, Modifier.padding(8.dp).size((sizeDp - 16).dp),
                 tint = MaterialTheme.colorScheme.outline)
        }
    }
}

/** 状态标签芯片 */
@Composable
private fun StatusChip(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/** 添加/编辑服务器对话框 */
@Composable
private fun ServerEditDialog(
    title: String,
    initialName: String,
    initialHost: String,
    initialPort: String,
    onConfirm: (String, String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var host by remember { mutableStateOf(initialHost) }
    var port by remember { mutableStateOf(initialPort) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(I18n.t("servers.server_name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text(I18n.t("servers.server_host")) },
                    placeholder = { Text("play.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port, onValueChange = {
                        if (it.isEmpty() || it.toIntOrNull() != null) port = it
                    },
                    label = { Text(I18n.t("servers.server_port")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val p = port.toIntOrNull() ?: 25565
                    if (name.isNotBlank() && host.isNotBlank() && p in 1..65535) {
                        onConfirm(name.trim(), host.trim(), p)
                    }
                },
                enabled = name.isNotBlank() && host.isNotBlank() && (port.toIntOrNull() ?: 0) in 1..65535
            ) {
                Text(I18n.t("common.confirm"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(I18n.t("common.cancel"))
            }
        }
    )
}
