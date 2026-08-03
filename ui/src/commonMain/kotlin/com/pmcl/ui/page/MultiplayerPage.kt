package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.TypewriterTitle
import com.pmcl.core.multiplayer.EasyTierManager
import com.pmcl.core.multiplayer.MultiplayerManager
import com.pmcl.ui.animation.AnimatedSegmentedSelector
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.setMpBackend
import com.pmcl.ui.viewmodel.createRoom
import com.pmcl.ui.viewmodel.joinRoom
import com.pmcl.ui.viewmodel.leaveRoom
import com.pmcl.ui.viewmodel.copyInvitation
import com.pmcl.ui.viewmodel.copyToClipboard
import com.pmcl.ui.viewmodel.syncConnectXConfig
import com.pmcl.ui.viewmodel.syncEasyTierConfig

/**
 * 多人联机页：由二级侧栏切换 创建与加入 / 联机设置 / 使用说明。
 */
@Composable
fun MultiplayerPage(vm: LauncherViewModel, sectionId: String = "room") {
    when (sectionId) {
        "settings" -> MpSettingsSection(vm)
        "help" -> MpHelpSection(vm)
        else -> MpRoomSection(vm)
    }
}

@Composable
private fun MpRoomSection(vm: LauncherViewModel) {
    val state by vm.mpState.collectAsState()
    val progress by vm.mpProgress.collectAsState()
    val virtualIp by vm.mpVirtualIp.collectAsState()
    val invitation by vm.mpInvitation.collectAsState()
    val mcAddr by vm.mpLocalMcAddr.collectAsState()
    val backend by vm.mpBackendState.collectAsState()

    var joinCode by remember { mutableStateOf("") }

    val isConnectX = backend == MultiplayerManager.Backend.CONNECTX
    val isTerracotta = backend == MultiplayerManager.Backend.TERRACOTTA
    val busy = state == MultiplayerManager.State.DOWNLOADING ||
        state == MultiplayerManager.State.CONNECTING
    val inRoom = state == MultiplayerManager.State.CONNECTING ||
        state == MultiplayerManager.State.CONNECTED
    val failed = state == MultiplayerManager.State.FAILED

    val archSupported = EasyTierManager.isEasyTierSupportedOnCurrentArch()

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Share, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            TypewriterTitle(
                I18n.t("mp.section.room"),
                style = MaterialTheme.typography.titleLarge,
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
        }

        if (!archSupported) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        I18n.t("mp.arch_unsupported"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (!inRoom) {
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

            Button(
                onClick = { vm.createRoom() },
                enabled = !busy && archSupported,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (busy) I18n.t("common.processing") else I18n.t("mp.create_room"),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            if (isTerracotta) {
                Text(
                    I18n.t("mp.terracotta_create_tip"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

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
                    enabled = !busy && archSupported,
                    shape = RoundedCornerShape(12.dp)
                )
                Button(
                    onClick = { vm.joinRoom(joinCode) },
                    enabled = !busy && joinCode.isNotBlank() && archSupported,
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
}

@Composable
private fun MpSettingsSection(vm: LauncherViewModel) {
    var easytierPeer by remember { mutableStateOf(vm.preferences.getEasytierPeer() ?: "") }
    var connectxBinPath by remember { mutableStateOf(vm.preferences.getConnectxBinaryPath() ?: "") }
    var connectxServer by remember { mutableStateOf(vm.preferences.getConnectxServerAddress() ?: "") }
    var connectxPort by remember { mutableStateOf(vm.preferences.getConnectxServerPort().toString()) }
    var savedFlash by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TypewriterTitle(
            I18n.t("mp.section.settings"),
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            I18n.t("mp.easytier_peer"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = easytierPeer,
            onValueChange = { easytierPeer = it },
            placeholder = { Text("tcp://your.host:11010") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Text(
            I18n.t("mp.easytier_peer_hint"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )

        HorizontalDivider()

        Text(
            I18n.t("mp.connectx_settings"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            I18n.t("mp.connectx_binary"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = connectxBinPath,
            onValueChange = { connectxBinPath = it },
            placeholder = { Text("/path/to/ConnectX.ClientConsole") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Text(
            I18n.t("mp.connectx_server"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = connectxServer,
            onValueChange = { connectxServer = it },
            placeholder = { Text("192.168.1.100") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Text(
            I18n.t("mp.connectx_port"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        OutlinedTextField(
            value = connectxPort,
            onValueChange = { connectxPort = it },
            placeholder = { Text("3535") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Text(
            I18n.t("mp.connectx_about"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )

        Button(
            onClick = {
                vm.preferences.setEasytierPeer(easytierPeer)
                vm.preferences.setConnectxBinaryPath(connectxBinPath)
                vm.preferences.setConnectxServerAddress(connectxServer)
                vm.preferences.setConnectxServerPort(connectxPort.toIntOrNull() ?: 3535)
                vm.syncConnectXConfig()
                vm.syncEasyTierConfig()
                savedFlash = true
            },
            modifier = Modifier.fillMaxWidth().height(44.dp)
        ) {
            Text(I18n.t("common.save"))
        }
        if (savedFlash) {
            Text(
                I18n.t("common.save") + " ✓",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun MpHelpSection(vm: LauncherViewModel) {
    val backend by vm.mpBackendState.collectAsState()
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

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TypewriterTitle(
            I18n.t("mp.section.help"),
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            when (backend) {
                MultiplayerManager.Backend.TERRACOTTA -> I18n.t("mp.terracotta_official")
                MultiplayerManager.Backend.CONNECTX -> "ConnectX"
                else -> "EasyTier"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        lines.forEach { line ->
            if (line.isEmpty()) Spacer(Modifier.height(4.dp))
            else Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
