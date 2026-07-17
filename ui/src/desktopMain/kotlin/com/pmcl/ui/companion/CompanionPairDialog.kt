package com.pmcl.ui.companion

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    var port by remember { mutableStateOf(hostServer.getActualPort().let { if (it > 0) it else pairing.getPort() }) }
    val ips = remember { listLocalIps() }
    val clipboard = LocalClipboardManager.current
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 重命名对话框状态
    var renamingDevice by remember { mutableStateOf<PairingManager.PairedDevice?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf(false) }

    // 打开对话框时刷新一次端口（端口占用时可能被自动递增）
    LaunchedEffect(Unit) {
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
                if (ips.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Wifi, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "未检测到可用的局域网 IPv4 地址，请检查网络连接",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    ips.forEach { ip ->
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
                                Text(
                                    ip,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
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
                                    renamingDevice = device
                                    renameText = device.deviceName
                                    renameError = false
                                }) {
                                    Icon(
                                        Icons.Filled.Edit, "重命名",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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

    // 重命名对话框
    val renaming = renamingDevice
    if (renaming != null) {
        AlertDialog(
            onDismissRequest = { renamingDevice = null },
            title = { Text("重命名设备") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = {
                        renameText = it
                        renameError = it.isBlank()
                    },
                    label = { Text("设备名称") },
                    singleLine = true,
                    isError = renameError,
                    supportingText = if (renameError) {
                        { Text("名称不能为空") }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isBlank()) {
                        renameError = true
                    } else {
                        pairing.renameDevice(renaming.token, renameText)
                        devices = pairing.getDevices()
                        renamingDevice = null
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingDevice = null }) { Text("取消") }
            }
        )
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
