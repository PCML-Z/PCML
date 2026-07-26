package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.multiplayer.MultiplayerManager
import com.pmcl.ui.animation.AnimatedSegmentedSelector
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 多人联机页：创建 / 加入房间为主，其余收进设置与说明。
 */
@Composable
fun MultiplayerPage(vm: LauncherViewModel) {
    val state by vm.mpState.collectAsState()
    val progress by vm.mpProgress.collectAsState()
    val virtualIp by vm.mpVirtualIp.collectAsState()
    val invitation by vm.mpInvitation.collectAsState()
    val mcAddr by vm.mpLocalMcAddr.collectAsState()
    val backend by vm.mpBackendState.collectAsState()

    var joinCode by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }
    var showConnectXSettings by remember { mutableStateOf(false) }
    var connectxBinPath by remember { mutableStateOf(vm.preferences.getConnectxBinaryPath() ?: "") }
    var connectxServer by remember { mutableStateOf(vm.preferences.getConnectxServerAddress() ?: "") }
    var connectxPort by remember { mutableStateOf(vm.preferences.getConnectxServerPort().toString()) }

    val isConnectX = backend == MultiplayerManager.Backend.CONNECTX
    val isTerracotta = backend == MultiplayerManager.Backend.TERRACOTTA
    val busy = state == MultiplayerManager.State.DOWNLOADING ||
        state == MultiplayerManager.State.CONNECTING
    val inRoom = state == MultiplayerManager.State.CONNECTING ||
        state == MultiplayerManager.State.CONNECTED
    val failed = state == MultiplayerManager.State.FAILED

    val stateText = when (state) {
        MultiplayerManager.State.IDLE -> I18n.t("mp.state.idle")
        MultiplayerManager.State.DOWNLOADING -> when {
            isConnectX -> I18n.t("mp.state.connecting_server")
            isTerracotta -> I18n.t("mp.state.downloading_terracotta")
            else -> I18n.t("mp.state.downloading_easytier")
        }
        MultiplayerManager.State.CONNECTING -> I18n.t("mp.state.connecting")
        MultiplayerManager.State.CONNECTED -> I18n.t("mp.state.connected")
        MultiplayerManager.State.DISCONNECTED -> I18n.t("mp.state.disconnected")
        MultiplayerManager.State.FAILED -> I18n.t("mp.state.failed")
    }
    val stateColor = when (state) {
        MultiplayerManager.State.CONNECTED -> MaterialTheme.colorScheme.primary
        MultiplayerManager.State.CONNECTING,
        MultiplayerManager.State.DOWNLOADING -> MaterialTheme.colorScheme.tertiary
        MultiplayerManager.State.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    val backends = listOf(
        I18n.t("mp.terracotta_official") to MultiplayerManager.Backend.TERRACOTTA,
        "EasyTier" to MultiplayerManager.Backend.EASYTIER,
        "ConnectX" to MultiplayerManager.Backend.CONNECTX
    )

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶栏：标题 + 状态 + 帮助 / 设置
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Share, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                I18n.t("nav.multiplayer"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                color = stateColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    stateText,
                    style = MaterialTheme.typography.labelMedium,
                    color = stateColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            IconButton(onClick = { showHelp = true }) {
                Icon(Icons.Filled.Info, I18n.t("mp.usage"))
            }
            if (!inRoom) {
                IconButton(onClick = { showConnectXSettings = true }) {
                    Icon(Icons.Filled.Settings, I18n.t("mp.settings"))
                }
            }
        }

        if (!inRoom) {
            // 联机方式（单行，不占大卡片）
            Text(
                I18n.t("mp.backend"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
            AnimatedSegmentedSelector(
                items = backends.map { it.first },
                selectedIndex = backends.indexOfFirst { it.second == backend }.coerceAtLeast(0),
                onSelect = { idx -> vm.setMpBackend(backends[idx].second) },
                fillWidth = true,
                height = 34.dp
            )

            // 主操作：创建
            Button(
                onClick = { vm.createRoom() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (busy) I18n.t("common.processing") else I18n.t("mp.create_room"),
                    style = MaterialTheme.typography.titleSmall
                )
            }

            // 加入
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = joinCode,
                    onValueChange = { joinCode = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            when {
                                isConnectX -> I18n.t("mp.placeholder.connectx")
                                isTerracotta -> I18n.t("mp.placeholder.terracotta")
                                else -> I18n.t("mp.placeholder.easytier")
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    singleLine = true,
                    enabled = !busy,
                    shape = RoundedCornerShape(12.dp)
                )
                Button(
                    onClick = { vm.joinRoom(joinCode) },
                    enabled = !busy && joinCode.isNotBlank(),
                    modifier = Modifier.height(56.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("mp.join_room"))
                }
            }

            Text(
                I18n.t("mp.idle_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        } else {
            // 已在房间：突出房间码 / IP，其余精简
            val primaryLabel: String
            val primaryValue: String
            val primaryHint: String
            when {
                isTerracotta && invitation.isNotEmpty() -> {
                    primaryLabel = I18n.t("mp.room_code")
                    primaryValue = invitation
                    primaryHint = I18n.t("mp.share_room_code_hint")
                }
                isConnectX && invitation.isNotEmpty() -> {
                    primaryLabel = I18n.t("mp.invite_code")
                    primaryValue = invitation
                    primaryHint = I18n.t("mp.desc.connectx")
                }
                virtualIp.isNotEmpty() -> {
                    primaryLabel = I18n.t("mp.virtual_ip")
                    primaryValue = virtualIp
                    primaryHint = I18n.t("mp.virtual_ip_hint")
                }
                invitation.isNotEmpty() -> {
                    primaryLabel = I18n.t("mp.invite_code")
                    primaryValue = invitation
                    primaryHint = I18n.t("mp.desc.easytier")
                }
                else -> {
                    primaryLabel = I18n.t("mp.current_room")
                    primaryValue = stateText
                    primaryHint = I18n.t("mp.ip_acquiring")
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        primaryLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            primaryValue,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = {
                            if (primaryValue == virtualIp) vm.copyToClipboard(virtualIp)
                            else vm.copyInvitation()
                        }) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                I18n.t("mp.copy_invite"),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                    Text(
                        primaryHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            if (mcAddr.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                I18n.t("mp.local_mc_addr"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                mcAddr,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        IconButton(onClick = { vm.copyToClipboard(mcAddr) }) {
                            Icon(
                                Icons.Filled.ContentCopy,
                                I18n.t("mp.copy_addr"),
                                tint = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { vm.leaveRoom() },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(I18n.t("mp.leave_room"))
            }
        }

        if (busy || progress.isNotEmpty()) {
            if (busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }
            if (progress.isNotEmpty()) {
                Text(
                    progress,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val lastError = vm.mpLastError
        if (failed && lastError.isNotEmpty()) {
            Text(
                lastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (showHelp) {
        MpHelpDialog(
            backend = backend,
            onDismiss = { showHelp = false }
        )
    }

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

@Composable
private fun MpHelpDialog(
    backend: MultiplayerManager.Backend,
    onDismiss: () -> Unit
) {
    val lines = when (backend) {
        MultiplayerManager.Backend.TERRACOTTA -> listOf(
            I18n.t("mp.host_label"),
            I18n.t("mp.usage.terracotta.host.1"),
            I18n.t("mp.usage.terracotta.host.2"),
            I18n.t("mp.usage.terracotta.host.3"),
            "",
            I18n.t("mp.guest_label"),
            I18n.t("mp.usage.terracotta.guest.1"),
            I18n.t("mp.usage.terracotta.guest.2"),
            I18n.t("mp.usage.terracotta.guest.3"),
            "",
            I18n.t("mp.usage.terracotta.note")
        )
        MultiplayerManager.Backend.CONNECTX -> listOf(
            I18n.t("mp.usage.connectx.1"),
            I18n.t("mp.usage.connectx.2"),
            I18n.t("mp.usage.connectx.3"),
            I18n.t("mp.usage.connectx.4"),
            I18n.t("mp.usage.connectx.5"),
            "",
            I18n.t("mp.usage.connectx.note")
        )
        else -> listOf(
            I18n.t("mp.usage.easytier.1"),
            I18n.t("mp.usage.easytier.2"),
            I18n.t("mp.usage.easytier.3"),
            I18n.t("mp.usage.easytier.4"),
            I18n.t("mp.usage.easytier.5"),
            "",
            I18n.t("mp.usage.easytier.warning"),
            I18n.t("mp.usage.easytier.note")
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("mp.usage")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                lines.forEach { line ->
                    if (line.isEmpty()) Spacer(Modifier.height(4.dp))
                    else Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.ok")) }
        }
    )
}

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
                Text(
                    I18n.t("mp.connectx_binary"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = binPath,
                    onValueChange = onBinPathChange,
                    placeholder = { Text("/path/to/ConnectX.ClientConsole") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    I18n.t("mp.connectx_server"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = serverAddr,
                    onValueChange = onServerAddrChange,
                    placeholder = { Text("192.168.1.100") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    I18n.t("mp.connectx_port"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = onServerPortChange,
                    placeholder = { Text("3535") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Text(
                    I18n.t("mp.connectx_about"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
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
