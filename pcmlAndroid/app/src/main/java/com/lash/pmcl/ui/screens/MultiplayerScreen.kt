package com.lash.pmcl.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.*

/**
 * 多人联机页 — 创建/加入房间，LAN 扫描，邀请码分享。
 * UI 与桌面端结构一致。
 */
enum class MpState { IDLE, SCANNING, CREATING, JOINING, CONNECTED, ERROR }
enum class MpBackend { LAN, EASYTIER_DISABLED, CONNECTX_DISABLED }

@Composable
fun MultiplayerScreen() {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(MpState.IDLE) }
    var progress by remember { mutableStateOf("") }
    var virtualIp by remember { mutableStateOf("") }
    var invitation by remember { mutableStateOf("") }
    var localAddr by remember { mutableStateOf("") }
    var backend by remember { mutableStateOf(MpBackend.LAN) }
    var joinCode by remember { mutableStateOf("") }
    var showHelp by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var foundServers by remember { mutableStateOf<List<LanServerInfo>>(emptyList()) }

    val busy = state == MpState.CREATING || state == MpState.JOINING || state == MpState.SCANNING
    val context = androidx.compose.ui.platform.LocalContext.current

    fun createRoom() {
        scope.launch {
            state = MpState.CREATING; progress = "创建房间中..."
            try {
                withContext(Dispatchers.IO) {
                    // Detect LAN IP
                    val ip = NetworkInterface.getNetworkInterfaces().toList()
                        .flatMap { it.inetAddresses.toList() }
                        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    virtualIp = ip?.hostAddress ?: "127.0.0.1"
                    localAddr = "$virtualIp:25565"
                }
                invitation = "pmcl://join?host=$localAddr&port=25565"
                state = MpState.CONNECTED; progress = "房间已创建"
            } catch (e: Exception) { state = MpState.ERROR; progress = "创建失败: ${e.message}" }
        }
    }

    fun joinRoom() {
        if (joinCode.isBlank()) return
        scope.launch {
            state = MpState.JOINING; progress = "加入房间中..."
            try {
                withContext(Dispatchers.IO) { Thread.sleep(1000) } // simulate handshake
                state = MpState.CONNECTED; progress = "已加入房间"
            } catch (e: Exception) { state = MpState.ERROR; progress = "加入失败: ${e.message}" }
        }
    }

    fun leaveRoom() { state = MpState.IDLE; virtualIp = ""; invitation = ""; localAddr = ""; progress = "" }

    fun scanLan() {
        scope.launch {
            state = MpState.SCANNING; progress = "扫描 LAN..."
            try {
                foundServers = withContext(Dispatchers.IO) { scanLanServers() }
                progress = if (foundServers.isEmpty()) "未发现 LAN 游戏" else "发现 ${foundServers.size} 个服务器"
                state = MpState.IDLE
            } catch (e: Exception) { state = MpState.ERROR; progress = "扫描失败: ${e.message}" }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("多人联机", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("创建房间邀请朋友加入，或使用邀请码加入其他人的房间",
             style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(16.dp))

        // 后端选择
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
             colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp)) {
                Text("联机后端", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(MpBackend.LAN to "LAN 局域网",
                           MpBackend.EASYTIER_DISABLED to "EasyTier (不可用)",
                           MpBackend.CONNECTX_DISABLED to "ConnectX (不可用)").forEach { (be, label) ->
                        FilterChip(selected = backend == be,
                            onClick = { if (be == MpBackend.LAN) backend = be },
                            label = { Text(label) })
                    }
                }
                Text("Android 仅支持 LAN 局域网联机", style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 连接状态
        when (state) {
            MpState.IDLE -> {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                     colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("创建房间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { createRoom() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("创建房间")
                        }
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(12.dp))
                        Text("加入房间", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = joinCode, onValueChange = { joinCode = it },
                                label = { Text("邀请码") }, singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { joinRoom() }, enabled = joinCode.isNotBlank() && !busy) {
                                Text("加入")
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { scanLan() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Search, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("扫描 LAN 游戏")
                        }
                    }
                }
            }
            MpState.CREATING, MpState.JOINING, MpState.SCANNING -> {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                     colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(36.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(progress, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            MpState.CONNECTED -> {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                     colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text("已连接", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall,
                                 color = MaterialTheme.colorScheme.primary)
                        }
                        if (virtualIp.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text("虚拟 IP: $virtualIp", style = MaterialTheme.typography.bodyMedium)
                        }
                        if (localAddr.isNotEmpty()) {
                            Text("本地地址: $localAddr", style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                        if (invitation.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(value = invitation, onValueChange = {}, readOnly = true,
                                label = { Text("邀请码") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val clip = android.content.ClipData.newPlainText("invite", invitation)
                                    (context
                                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager).setPrimaryClip(clip)
                                }) {
                                    Icon(Icons.Filled.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("复制")
                                }
                                OutlinedButton(onClick = {
                                    val ctx = context
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, invitation)
                                    }
                                    ctx.startActivity(android.content.Intent.createChooser(intent, "分享邀请码"))
                                }) {
                                    Icon(Icons.Filled.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("分享")
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { leaveRoom() }, modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Filled.ExitToApp, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("离开房间")
                        }
                    }
                }
            }
            MpState.ERROR -> {
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                     colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(progress, color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { state = MpState.IDLE }, modifier = Modifier.fillMaxWidth()) { Text("重试") }
                    }
                }
            }
        }

        // LAN 扫描结果
        if (foundServers.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("LAN 服务器 (${foundServers.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            foundServers.forEach { srv ->
                Surface(Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Dns, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(srv.motd.ifEmpty { "${srv.host}:${srv.port}" }, fontWeight = FontWeight.Medium)
                            Text("${srv.host}:${srv.port}", style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                        OutlinedButton(onClick = { joinCode = "${srv.host}:${srv.port}" }) { Text("加入") }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { showHelp = true }) {
                Icon(Icons.Filled.Info, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("帮助")
            }
            OutlinedButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("设置")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(progress.ifEmpty { "就绪" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }

    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false },
        title = { Text("联机帮助") },
        text = {
            Column {
                Text("1. 同一 WiFi 下点击「创建房间」")
                Text("2. 分享邀请码给朋友")
                Text("3. 朋友输入邀请码点击「加入」")
                Spacer(Modifier.height(8.dp))
                Text("也可以使用「扫描 LAN」自动发现本地游戏。", color = MaterialTheme.colorScheme.outline,
                     style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } })

    if (showSettings) {
        var easytierPeer by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { showSettings = false },
            title = { Text("联机设置") },
            text = {
                Column {
                    Text("EasyTier 和 ConnectX 在 Android 端暂不可用。使用 LAN 模式即可在同一网络下联机。",
                         color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = easytierPeer, onValueChange = { easytierPeer = it },
                        label = { Text("EasyTier 节点 (不可用)") }, enabled = false, modifier = Modifier.fillMaxWidth())
                }
            }, confirmButton = { TextButton(onClick = { showSettings = false }) { Text("关闭") } })
    }
}

data class LanServerInfo(val host: String, val port: Int, val motd: String)

private fun scanLanServers(): List<LanServerInfo> {
    val results = mutableListOf<LanServerInfo>()
    try {
        val socket = DatagramSocket()
        socket.broadcast = true; socket.soTimeout = 2000
        socket.send(DatagramPacket(byteArrayOf(0xFE.toByte(), 0x01), 2, InetAddress.getByName("255.255.255.255"), 25565))
        val buf = ByteArray(256)
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 4000) {
            try {
                val resp = DatagramPacket(buf, buf.size); socket.receive(resp)
                results.add(LanServerInfo(resp.address.hostAddress ?: "unknown", 25565,
                    String(buf, 0, resp.length, Charsets.UTF_8).trim().take(60)))
            } catch (_: Exception) { break }
        }
        socket.close()
    } catch (_: Exception) {}
    return results.distinctBy { "${it.host}:${it.port}" }
}
