package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.multiplayer.MultiplayerManager
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

    var joinCode by remember { mutableStateOf("") }
    var showConnectXSettings by remember { mutableStateOf(false) }
    var connectxBinPath by remember { mutableStateOf(vm.preferences.getConnectxBinaryPath()) }
    var connectxServer by remember { mutableStateOf(vm.preferences.getConnectxServerAddress()) }
    var connectxPort by remember { mutableStateOf(vm.preferences.getConnectxServerPort().toString()) }
    val scroll = rememberScrollState()

    val isConnectX = vm.mpBackend == MultiplayerManager.Backend.CONNECTX

    // 状态 → 文案 / 颜色映射
    val stateText = when (state) {
        MultiplayerManager.State.IDLE         -> "未加入房间"
        MultiplayerManager.State.DOWNLOADING  -> when {
            isConnectX -> "正在连接服务器…"
            vm.mpBackend == MultiplayerManager.Backend.TERRACOTTA -> "正在下载 Terracotta…"
            else -> "正在下载 EasyTier…"
        }
        MultiplayerManager.State.CONNECTING   -> "正在连接…"
        MultiplayerManager.State.CONNECTED    -> "已连接"
        MultiplayerManager.State.DISCONNECTED -> "已断开"
        MultiplayerManager.State.FAILED       -> "连接失败"
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
                MultiplayerManager.Backend.CONNECTX -> "ConnectX 联机"
                MultiplayerManager.Backend.TERRACOTTA -> "陶瓦联机 · Terracotta"
                else -> "陶瓦联机 · EasyTier"
            },
                 style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            // 设置按钮
            if (!inRoom) {
                IconButton(onClick = { showConnectXSettings = true }) {
                    Icon(Icons.Filled.Settings, "联机设置")
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
                MultiplayerManager.Backend.CONNECTX -> "基于 ConnectX / ZeroTier 的 P2P 联机 · 仅支持 Minecraft Java Edition"
                MultiplayerManager.Backend.TERRACOTTA -> "Terracotta 陶瓦联机（HMCL 同款）· 房间码互通 · 仅支持 Minecraft Java Edition"
                else -> "基于 EasyTier 的免费 P2P 联机 · 仅支持 Minecraft Java Edition"
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
                    Text("联机后端", style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(6.dp))
                    val backendList = listOf(
                        Triple("Terracotta（官方）", MultiplayerManager.Backend.TERRACOTTA, 0),
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
                        Text("ConnectX 需要配置客户端二进制和服务器地址（点击右上角设置按钮）",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.tertiary)
                    } else if (vm.mpBackend == MultiplayerManager.Backend.TERRACOTTA) {
                        Spacer(Modifier.height(8.dp))
                        Text("Terracotta 是 HMCL 同款陶瓦联机实现，房间码互通，首次使用需下载约 8-14MB 二进制。",
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
                Text("当前房间", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

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
                                    Text("房间码（发送给朋友加入）",
                                         style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Text(invitation,
                                         style = MaterialTheme.typography.titleLarge,
                                         fontWeight = FontWeight.Bold,
                                         color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                                IconButton(onClick = { vm.copyInvitation() }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, "复制房间码")
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("把房间码发给朋友，他在 PMCL 联机页粘贴后点「加入房间」即可。",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                        // 房客模式下显示本地 MC 连接地址
                        val mcAddr = vm.mpLocalMcAddr.collectAsState().value
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
                                        Text("本地 MC 连接地址",
                                             style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.onTertiaryContainer)
                                        Text(mcAddr,
                                             style = MaterialTheme.typography.titleMedium,
                                             fontWeight = FontWeight.Bold,
                                             color = MaterialTheme.colorScheme.onTertiaryContainer)
                                    }
                                    IconButton(onClick = { vm.copyToClipboard(mcAddr) }) {
                                        Icon(Icons.Filled.Share, "复制地址")
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("在 Minecraft 多人游戏 → 「直接连接」中输入此地址即可加入房主。",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                    } else if (isConnectX) {
                        InfoRow("房间短ID", invitation.removePrefix("connectx-").take(12))
                        if (invitation.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("邀请码（发送给朋友加入）",
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
                                        Icon(Icons.AutoMirrored.Filled.Send, "复制邀请码")
                                    }
                                }
                            )
                        }
                    } else {
                        // EasyTier 后端
                        InfoRow("网络名称", invitation.take(20) + if (invitation.length > 20) "…" else "")
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
                                        Text("你的虚拟 IP",
                                             style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.onPrimaryContainer)
                                        Text(virtualIp,
                                             style = MaterialTheme.typography.titleLarge,
                                             fontWeight = FontWeight.Bold,
                                             color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    IconButton(onClick = { vm.copyToClipboard(virtualIp) }) {
                                        Icon(Icons.Filled.Share, "复制 IP")
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("房客用「直接连接」输入此 IP:端口（端口是房主游戏内「对局域网开放」时显示的端口）",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        } else {
                            Text("虚拟 IP 获取中…请稍候",
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.tertiary)
                        }
                        if (invitation.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("邀请码（发送给朋友加入）",
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
                                        Icon(Icons.AutoMirrored.Filled.Send, "复制邀请码")
                                    }
                                }
                            )
                        }
                    }
                } else {
                    Text("尚未加入任何房间。创建房间或粘贴朋友发来的房间码加入。",
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
                    Text("房主", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Button(
                        onClick = { vm.createRoom() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (busy) "处理中…" else "创建房间")
                    }

                    HorizontalDivider()

                    // 加入房间
                    Text("房客", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = joinCode,
                        onValueChange = { joinCode = it },
                        label = { Text("房间码 / 邀请码") },
                        placeholder = { Text(
                            when {
                                isConnectX -> "粘贴 connectx-… 邀请码"
                                vm.mpBackend == MultiplayerManager.Backend.TERRACOTTA -> "粘贴 U/XXXX-XXXX-XXXX-XXXX 房间码"
                                else -> "粘贴 pmcl-… 邀请码"
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
                        Text(if (busy) "处理中…" else "加入房间")
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
                Text("离开房间")
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
                Text("使用说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val isTerracottaBackend = vm.mpBackend == MultiplayerManager.Backend.TERRACOTTA
                if (isTerracottaBackend) {
                    Text("房主：",
                         style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.SemiBold)
                    Text("1. 点击「创建房间」，首次会自动下载 Terracotta 官方二进制（约 8-14MB）",
                         style = MaterialTheme.typography.bodySmall)
                    Text("2. 房间创建后会得到房间码（U/XXXX-XXXX-XXXX-XXXX）",
                         style = MaterialTheme.typography.bodySmall)
                    Text("3. 进入单人世界 → ESC 菜单 → 「对局域网开放」",
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("房客：",
                         style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.SemiBold)
                    Text("1. 粘贴房主发来的房间码 → 点击「加入房间」",
                         style = MaterialTheme.typography.bodySmall)
                    Text("2. 加入成功后 PMCL 会显示本地 MC 连接地址（如 127.0.0.1:25565）",
                         style = MaterialTheme.typography.bodySmall)
                    Text("3. 启动 Minecraft → 多人游戏 → 「直接连接」→ 输入该地址",
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("Terracotta 陶瓦联机 by Burning_TNT，基于 EasyTier P2P。与 HMCL / PCL CE 房间码互通。",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                } else if (isConnectX) {
                    Text("1. 房主点击「创建房间」，ConnectX 会连接服务器并创建房间。",
                         style = MaterialTheme.typography.bodySmall)
                    Text("2. 房主复制邀请码发给朋友。",
                         style = MaterialTheme.typography.bodySmall)
                    Text("3. 房客粘贴邀请码后点击「加入房间」。",
                         style = MaterialTheme.typography.bodySmall)
                    Text("4. 房主进入单人世界 → 菜单 → 「对局域网开放」",
                         style = MaterialTheme.typography.bodySmall)
                    Text("5. 房客：多人游戏 → 「直接连接」→ 输入 房主虚拟IP:端口",
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("提示：ConnectX 基于 ZeroTier P2P，支持中继模式。需先在设置中配置服务器和客户端。",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                } else {
                    Text("1. 房主点击「创建房间」，等待 EasyTier 启动并分配虚拟 IP。",
                         style = MaterialTheme.typography.bodySmall)
                    Text("2. 房主复制邀请码发给朋友。",
                         style = MaterialTheme.typography.bodySmall)
                    Text("3. 房客粘贴邀请码后点击「加入房间」，等待双方都显示「已连接」。",
                         style = MaterialTheme.typography.bodySmall)
                    Text("4. 房主进入单人世界 → 菜单 → 「对局域网开放」，游戏会显示端口号（如 12345）",
                         style = MaterialTheme.typography.bodySmall)
                    Text("5. 房客：多人游戏 → 「直接连接」→ 输入 房主虚拟IP:端口（如 10.144.144.10:12345）",
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text("⚠ 重要：不要用「多人游戏」列表自动扫描！虚拟网络不转发 LAN 广播，列表里看不到房间，必须用「直接连接」手动输入 IP:端口。",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(4.dp))
                    Text("提示：陶瓦联机基于 P2P，稳定性弱于服务器中继方案。打洞失败时请重试。双方需都显示「已连接」才能联机。",
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
                    Text("错误详情",
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
        title = { Text("ConnectX 设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ConnectX.ClientConsole 二进制路径",
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
                Text("ConnectX 服务器地址",
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
                Text("ConnectX 服务器端口",
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
                Text("说明：ConnectX 是基于 ZeroTier SDK 的 P2P 联机方案。需要自行编译 ConnectX.ClientConsole 并部署 ConnectX.Server。项目地址：github.com/Corona-Studio/ConnectX",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
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
