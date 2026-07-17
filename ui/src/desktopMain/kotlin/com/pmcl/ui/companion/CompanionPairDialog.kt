package com.pmcl.ui.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * iOS 伴随 App 配对对话框：展示桌面端局域网 IP、端口、配对码及已配对设备。
 */
@Composable
fun CompanionPairDialog(
    pairing: PairingManager,
    hostServer: PmclHostServer,
    onDismiss: () -> Unit
) {
    // 本地可观察状态
    var pairingCode by remember { mutableStateOf(pairing.getPairingCode()) }
    var devices by remember { mutableStateOf(pairing.getDevices()) }
    var running by remember { mutableStateOf(hostServer.isRunning()) }
    var port by remember { mutableStateOf(hostServer.getActualPort().let { if (it > 0) it else pairing.getPort() }) }
    val ips = remember { listLocalIps() }
    val clipboard = LocalClipboardManager.current
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 打开对话框时刷新一次状态
    LaunchedEffect(Unit) {
        running = hostServer.isRunning()
        val ap = hostServer.getActualPort()
        if (ap > 0) port = ap
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.width(480.dp)
        ) {
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 标题
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.PhoneIphone, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "iOS 伴随 App 配对",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                if (running) "服务运行中" else "服务未运行",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Box(
                                Modifier.size(8.dp).clip(CircleShape)
                                    .background(
                                        if (running) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline
                                    )
                            )
                        }
                    )
                }

                Text(
                    "在 iOS 端打开 PCML，输入下方 IP 地址与配对码完成配对，" +
                        "即可远程控制桌面端 PMCL 启动游戏、浏览模组、好友聊天。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider()

                // 配对码（大字展示）
                Text(
                    "配对码",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(
                            Modifier.padding(vertical = 14.dp, horizontal = 20.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                pairingCode.take(3) + "  " + pairingCode.drop(3),
                                style = MaterialTheme.typography.headlineLarge,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    IconButton(onClick = {
                        pairingCode = pairing.regeneratePairingCode()
                    }) {
                        Icon(Icons.Filled.Refresh, "刷新配对码")
                    }
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(pairingCode))
                    }) {
                        Icon(Icons.Filled.ContentCopy, "复制配对码")
                    }
                }

                // 连接地址
                Text(
                    "连接地址",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 127.0.0.1 — iOS 模拟器使用
                val allIps = remember { listOf("127.0.0.1") + ips }
                allIps.forEachIndexed { index, ip ->
                    val isLoopback = index == 0
                    val label = if (isLoopback) "模拟器" else "真机"
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outlineVariant
                        ),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Wifi, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    ip,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    if (isLoopback) "本机地址 — iOS 模拟器使用"
                                    else "局域网地址 — iPhone 真机使用",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            Text(
                                ":$port",
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString("$ip:$port")) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy, "复制地址",
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }
                }

                // 防火墙提示
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text("提示：", style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "iOS 模拟器用 127.0.0.1 连接；iPhone 真机用局域网 IP，" +
                                "需与电脑在同一 WiFi。若连接失败，请在" +
                                "「系统设置 → 网络 → 防火墙」允许 PMCL 接受传入连接，" +
                                "并确认端口 $port 未被占用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }

                // 已配对设备
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Devices, null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "已配对设备（${devices.size}）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    if (devices.isNotEmpty()) {
                        TextButton(onClick = {
                            pairing.unpairAll()
                            devices = emptyList()
                        }) { Text("全部解绑", style = MaterialTheme.typography.labelSmall) }
                    }
                }

                if (devices.isEmpty()) {
                    Text(
                        "暂无已配对设备",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    devices.forEach { device ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        device.deviceName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        "配对于 ${dateFmt.format(Date(device.pairedAt))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                IconButton(onClick = {
                                    pairing.unpair(device.token)
                                    devices = pairing.getDevices()
                                }) {
                                    Icon(
                                        Icons.Filled.LinkOff, "解绑",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }
            }
        }
    }
}

/**
 * 枚举本机局域网 IPv4 地址：过滤回环、虚拟网卡、未启用接口。
 */
internal fun listLocalIps(): List<String> {
    val result = mutableListOf<String>()
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return result
        for (ni in interfaces) {
            try {
                if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
            } catch (_: Throwable) { continue }
            val addrs = ni.interfaceAddresses
            for (ia in addrs) {
                val addr = ia.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress ?: continue
                    // 过滤 docker0 / 容器网段常见 172.17.x 及 link-local 169.254
                    if (ip.startsWith("169.254.")) continue
                    result.add(ip)
                }
            }
        }
    } catch (_: Throwable) { }
    return result
}
