package com.pmcl.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.multiplayer.MultiplayerManager
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 多人联机页面：支持 EasyTier（陶瓦联机）和 ConnectX 两种 P2P 后端。
 *
 * 工作流：
 * 1. 选择联机后端（EasyTier / ConnectX）
 * 2. 房主点击「创建房间」→ 生成邀请码
 * 3. 房客粘贴邀请码 → 点击「加入房间」→ 连接到同一虚拟网络
 * 4. 进入游戏后通过「局域网开放」或直连虚拟 IP 即可联机
 *
 * 注意：仅支持 Minecraft Java Edition。
 */
@Composable
fun MultiplayerPage(vm: LauncherViewModel) {
    val state by vm.mpState.collectAsState()
    val progress by vm.mpProgress.collectAsState()
    val virtualIp by vm.mpVirtualIp.collectAsState()
    val invitation by vm.mpInvitation.collectAsState()
    val status by vm.status.collectAsState()
    val mcAddr by vm.mpLocalMcAddr.collectAsState()

    var joinCode by remember { mutableStateOf("") }
    var showConnectXSettings by remember { mutableStateOf(false) }
    var connectxBinPath by remember { mutableStateOf(vm.preferences.getConnectxBinaryPath() ?: "") }
    var connectxServer by remember { mutableStateOf(vm.preferences.getConnectxServerAddress() ?: "") }
    var connectxPort by remember { mutableStateOf(vm.preferences.getConnectxServerPort().toString()) }
    val scroll = rememberScrollState()

    val isConnectX = vm.mpBackend == MultiplayerManager.Backend.CONNECTX

    // 状态 → 文案 / 颜色映射
    val stateText = when (state) {
        MultiplayerManager.State.IDLE         -> I18n.t("mp.state.idle")
        MultiplayerManager.State.DOWNLOADING  -> when {
            isConnectX -> I18n.t("mp.state.connecting_server")
            vm.mpBackend == MultiplayerManager.Backend.TERRACOTTA -> I18n.t("mp.state.downloading_terracotta")
            else -> I18n.t("mp.state.downloading_easytier")
        }
        MultiplayerManager.State.CONNECTING   -> I18n.t("mp.state.connecting")
        MultiplayerManager.State.CONNECTED    -> I18n.t("mp.state.connected")
        MultiplayerManager.State.DISCONNECTED -> I18n.t("mp.state.disconnected")
        MultiplayerManager.State.FAILED       -> I18n.t("mp.state.failed")
    }
    val stateColor = when (state) {
        MultiplayerManager.State.CONNECTED    -> MaterialTheme.colorScheme.primary
        MultiplayerManager.State.CONNECTING,
        MultiplayerManager.State.DOWNLOADING  -> MaterialTheme.colorScheme.tertiary
        MultiplayerManager.State.FAILED       -> MaterialTheme.colorScheme.error
        else                                  -> MaterialTheme.colorScheme.outline
    }
    val busy = state == MultiplayerManager.State.DOWNLOADING ||
               state == MultiplayerManager.State.CONNECTING
    val inRoom = state == MultiplayerManager.State.CONNECTING ||
                 state == MultiplayerManager.State.CONNECTED

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Share, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(when (vm.mpBackend) {
                MultiplayerManager.Backend.CONNECTX -> I18n.t("mp.connectx")
                MultiplayerManager.Backend.TERRACOTTA -> I18n.t("mp.terracotta")
                else -> I18n.t("mp.easytier")
            },
                 style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            // 设置按钮
            if (!inRoom) {
                IconButton(onClick = { showConnectXSettings = true }) {
                    Icon(Icons.Filled.Settings, I18n.t("mp.settings"))
                }
            }
            // 状态指示
            Surface(
                color = stateColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stateText,
                     style = MaterialTheme.typography.labelMedium,
                     color = stateColor,
                     modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Text(
            when (vm.mpBackend) {
                MultiplayerManager.Backend.CONNECTX -> I18n.t("mp.desc.connectx")
                MultiplayerManager.Backend.TERRACOTTA -> I18n.t("mp.desc.terracotta")
                else -> I18n.t("mp.desc.easytier")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )

        // 后端切换（仅在未在房间中时显示）
        if (!inRoom) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(I18n.t("mp.backend"), style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(6.dp))
                    val backendList = listOf(
                        Triple(I18n.t("mp.terracotta_official"), MultiplayerManager.Backend.TERRACOTTA, 0),
                        Triple("EasyTier", MultiplayerManager.Backend.EASYTIER, 1),
                        Triple("ConnectX", MultiplayerManager.Backend.CONNECTX, 2)
                    )
                    com.pmcl.ui.animation.AnimatedSegmentedSelector(
                        items = backendList.map { it.first },
                        selectedIndex = backendList.indexOfFirst { it.second == vm.mpBackend }.coerceAtLeast(0),
                        onSelect = { vm.setMpBackend(backendList[it].second) },
                        fillWidth = true
                    )
                    if (isConnectX) {
                        Spacer(Modifier.height(8.dp))
                        Text(I18n.t("mp.connectx_hint"),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.tertiary)
                    } else if (vm.mpBackend == MultiplayerManager.Backend.TERRACOTTA) {
                        Spacer(Modifier.height(8.dp))
                        Text(I18n.t("mp.terracotta_hint"),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }

        if (progress.isNotEmpty()) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            // 日志区域：显示最近一行，用等宽字体便于阅读
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    progress,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp).fillMaxWidth()
                )
            }
        }

        HorizontalDivider()

        // === 房间状态卡 ===
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(I18n.t("mp.current_room"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

                if (inRoom) {
                    // === Terracotta 后端：显示房间码 ===
                    val isTerracotta = vm.mpBackend == MultiplayerManager.Backend.TERRACOTTA
                    if (isTerracotta && invitation.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        // 房间码高亮显示
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(I18n.t("mp.room_code"),
                                         style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(invitation,
                                         style = MaterialTheme.typography.titleLarge,
                                         fontWeight = FontWeight.Bold,
                                         color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                IconButton(onClick = { vm.copyInvitation() }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, I18n.t("mp.copy_room_code"))
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(I18n.t("mp.share_room_code_hint"),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                        // 房客模式下显示本地 MC 连接地址
                        if (mcAddr.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(I18n.t("mp.local_mc_addr"),
                                             style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.onTertiaryContainer)
                                        Text(mcAddr,
                                             style = MaterialTheme.typography.titleMedium,
                                             fontWeight = FontWeight.Bold,
                                             color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    }
                                    IconButton(onClick = { vm.copyToClipboard(mcAddr) }) {
                                        Icon(Icons.Filled.Share, I18n.t("mp.copy_addr"))
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(I18n.t("mp.direct_connect_hint"),
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                    } else if (isConnectX) {
                        InfoRow(I18n.t("mp.room_id"), invitation.removePrefix("connectx-").take(12))
                        if (invitation.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(I18n.t("mp.invite_code"),
                                 style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.outline)
                            OutlinedTextField(
                                value = invitation,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                trailingIcon = {
                                    IconButton(onClick = { vm.copyInvitation() }) {
                                        Icon(Icons.AutoMirrored.Filled.Send, I18n.t("mp.copy_invite"))
                                    }
                                }
                            )
                        }
                    } else {
                        // EasyTier 后端
                        InfoRow(I18n.t("mp.network_name"), invitation.take(20) + if (invitation.length > 20) "…" else "")
                        if (virtualIp.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(I18n.t("mp.virtual_ip"),
                                             style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text(virtualIp,
                                             style = MaterialTheme.typography.titleLarge,
                                             fontWeight = FontWeight.Bold,
                                             color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    IconButton(onClick = { vm.copyToClipboard(virtualIp) }) {
                                        Icon(Icons.Filled.Share, I18n.t("mp.copy_ip"))
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(I18n.t("mp.virtual_ip_hint"),
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        } else {
                            Text(I18n.t("mp.ip_acquiring"),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.tertiary)
                        }
                        if (invitation.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(I18n.t("mp.invite_code"),
                                 style = MaterialTheme.typography.labelMedium,
                                 color = MaterialTheme.colorScheme.outline)
                            OutlinedTextField(
                                value = invitation,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                trailingIcon = {
                                    IconButton(onClick = { vm.copyInvitation() }) {
                                        Icon(Icons.AutoMirrored.Filled.Send, I18n.t("mp.copy_invite"))
                                    }
                                }
                            )
                        }
                    }
                } else {
                    Text(I18n.t("mp.not_joined"),
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // === 操作区 ===
        if (!inRoom) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 创建房间
                    Text(I18n.t("mp.host"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = { vm.createRoom() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (busy) I18n.t("common.processing") else I18n.t("mp.create_room"))
                    }

                    HorizontalDivider()

                    // 加入房间
                    Text(I18n.t("mp.guest"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it },
                        label = { Text(I18n.t("mp.room_code_label")) },
                        placeholder = { Text(
                            when {
                                isConnectX -> I18n.t("mp.placeholder.connectx")
                                vm.mpBackend == MultiplayerManager.Backend.TERRACOTTA -> I18n.t("mp.placeholder.terracotta")
                                else -> I18n.t("mp.placeholder.easytier")
                            }
                        ) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !busy
                    )
                    OutlinedButton(
                        onClick = { vm.joinRoom(joinCode) },
                        enabled = !busy && joinCode.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (busy) I18n.t("common.processing") else I18n.t("mp.join_room"))
                    }
                }
            }
        } else {
            // 已在房间 → 显示离开按钮
            Button(
                onClick = { vm.leaveRoom() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(I18n.t("mp.leave_room"))
            }
        }

        // === 使用说明 ===
        Card(
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(I18n.t("mp.usage"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val isTerracottaBackend = vm.mpBackend == MultiplayerManager.Backend.TERRACOTTA
                if (isTerracottaBackend) {
                    Text(I18n.t("mp.host_label"),
                         style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.SemiBold)
                    Text(I18n.t("mp.usage.terracotta.host.1"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.terracotta.host.2"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.terracotta.host.3"),
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text(I18n.t("mp.guest_label"),
                         style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.SemiBold)
                    Text(I18n.t("mp.usage.terracotta.guest.1"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.terracotta.guest.2"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.terracotta.guest.3"),
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("mp.usage.terracotta.note"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                } else if (isConnectX) {
                    Text(I18n.t("mp.usage.connectx.1"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.connectx.2"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.connectx.3"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.connectx.4"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.connectx.5"),
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("mp.usage.connectx.note"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                } else {
                    Text(I18n.t("mp.usage.easytier.1"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.easytier.2"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.easytier.3"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.easytier.4"),
                         style = MaterialTheme.typography.bodySmall)
                    Text(I18n.t("mp.usage.easytier.5"),
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("mp.usage.easytier.warning"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("mp.usage.easytier.note"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // 错误信息（失败时显示）
        val lastError = vm.mpLastError
        if (lastError.isNotEmpty() && state == MultiplayerManager.State.FAILED) {
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                )
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(I18n.t("mp.error_detail"),
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onErrorContainer)
                    Text(lastError,
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        if (status.isNotEmpty()) {
            Text(status,
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }

        // ===== 收藏服务器列表 =====
        FavoriteServersCard(vm)
    }

    // ConnectX 设置弹窗
    if (showConnectXSettings) {
        ConnectXSettingsDialog(
            binPath = connectxBinPath,
            serverAddr = connectxServer,
            serverPort = connectxPort,
            onBinPathChange = { connectxBinPath = it },
            onServerAddrChange = { connectxServer = it },
            onServerPortChange = { connectxPort = it },
            onDismiss = { showConnectXSettings = false },
            onSave = {
                vm.preferences.setConnectxBinaryPath(connectxBinPath)
                vm.preferences.setConnectxServerAddress(connectxServer)
                vm.preferences.setConnectxServerPort(connectxPort.toIntOrNull() ?: 3535)
                showConnectXSettings = false
            }
        )
    }
}

/**
 * ConnectX 配置弹窗。
 */
@Composable
private fun ConnectXSettingsDialog(
    binPath: String,
    serverAddr: String,
    serverPort: String,
    onBinPathChange: (String) -> Unit,
    onServerAddrChange: (String) -> Unit,
    onServerPortChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("mp.connectx_settings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(I18n.t("mp.connectx_binary"),
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(
                    value = binPath,
                    onValueChange = onBinPathChange,
                    placeholder = { Text("/path/to/ConnectX.ClientConsole") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("mp.connectx_server"),
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(
                    value = serverAddr,
                    onValueChange = onServerAddrChange,
                    placeholder = { Text("192.168.1.100 或 connectx.example.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("mp.connectx_port"),
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.outline)
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = onServerPortChange,
                    placeholder = { Text("3535") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("mp.connectx_about"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(I18n.t("common.save")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label,
             style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.outline,
             modifier = Modifier.width(80.dp))
        Text(value,
             style = MaterialTheme.typography.bodyMedium,
             fontWeight = FontWeight.Medium)
    }
}

/**
 * 收藏服务器列表卡片：添加/删除/ping 延迟/设为直连。
 */
@Composable
private fun FavoriteServersCard(vm: LauncherViewModel) {
    // 进入页面时加载列表
    LaunchedEffect(Unit) { vm.loadFavoriteServers() }

    val servers by vm.favoriteServers.collectAsState()
    val pings by vm.serverPings.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newHost by remember { mutableStateOf("") }
    var newPort by remember { mutableStateOf("25565") }

    Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(I18n.t("mp.favorite_servers"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                // 全部 ping
                TextButton(
                    onClick = { vm.pingAllServers() },
                    enabled = servers.isNotEmpty()
                ) {
                    Icon(Icons.Filled.Speed, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("mp.ping_all"))
                }
                // 添加
                IconButton(onClick = { showAddDialog = true }) {
                    Icon(Icons.Filled.Add, I18n.t("mp.add_server"))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(I18n.t("mp.favorite_servers_hint"),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            if (servers.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(I18n.t("mp.no_favorite_servers"),
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            } else {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                servers.forEachIndexed { index, server ->
                    val key = "${server.host}:${server.port}"
                    val ping = pings[key]
                    val pingText = when (ping) {
                        null -> "—"
                        com.pmcl.core.multiplayer.ServerPinger.UNREACHABLE -> I18n.t("mp.server.offline")
                        com.pmcl.core.multiplayer.ServerPinger.TIMEOUT -> I18n.t("mp.server.timeout")
                        else -> "${ping}ms"
                    }
                    val pingColor = when (ping) {
                        null -> MaterialTheme.colorScheme.outline
                        com.pmcl.core.multiplayer.ServerPinger.UNREACHABLE, com.pmcl.core.multiplayer.ServerPinger.TIMEOUT ->
                            MaterialTheme.colorScheme.error
                        else -> if (ping < 100) MaterialTheme.colorScheme.primary
                                else if (ping < 300) MaterialTheme.colorScheme.tertiary
                                else MaterialTheme.colorScheme.outline
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setDirectConnectServer(server.host, server.port) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(server.name,
                                 style = MaterialTheme.typography.bodyMedium,
                                 fontWeight = FontWeight.Medium,
                                 maxLines = 1)
                            Text("${server.host}:${server.port}",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline,
                                 maxLines = 1)
                        }
                        // 延迟
                        Surface(
                            color = pingColor.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                pingText,
                                style = MaterialTheme.typography.labelSmall,
                                color = pingColor,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        // 单个 ping
                        IconButton(
                            onClick = { vm.pingServer(server.host, server.port) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.Speed, I18n.t("mp.ping"), Modifier.size(16.dp))
                        }
                        // 直连
                        IconButton(
                            onClick = { vm.setDirectConnectServer(server.host, server.port) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.PlayArrow, I18n.t("mp.set_direct_connect"), Modifier.size(16.dp))
                        }
                        // 删除
                        IconButton(
                            onClick = { vm.removeFavoriteServer(index) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.Delete, I18n.t("common.delete"), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (index < servers.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // 添加服务器弹窗
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(I18n.t("mp.add_favorite_server")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        label = { Text(I18n.t("mp.server_name_optional")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newHost, onValueChange = { newHost = it },
                        label = { Text(I18n.t("mp.address")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPort, onValueChange = { newPort = it.filter { c -> c.isDigit() } },
                        label = { Text(I18n.t("mp.port")) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val port = newPort.toIntOrNull() ?: 25565
                        if (newHost.isNotBlank()) {
                            vm.addFavoriteServer(newName, newHost.trim(), port)
                            newName = ""; newHost = ""; newPort = "25565"
                            showAddDialog = false
                        }
                    }
                ) { Text(I18n.t("mp.add")) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
}
