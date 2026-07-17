package com.pmcl.ui.companion

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.rememberWindowState
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.geom.RoundRectangle2D
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * iOS 伴随 App 配对对话框：左侧展示配对码的二维码与一维码，右侧展示配对码文字及已配对设备。
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
    val clipboard = LocalClipboardManager.current
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 重命名对话框状态
    var renamingDevice by remember { mutableStateOf<PairingManager.PairedDevice?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf(false) }

    // 打开对话框时刷新配对码（反映最新 IP）
    LaunchedEffect(Unit) {
        pairingCode = pairing.getPairingCode()
    }

    // 由当前配对码生成二维码与一维码（配对码变化时重新生成）
    // bitmap 用更高像素渲染，配合 ContentScale.Fit 在固定显示框内等比放大
    val qrBitmap = remember(pairingCode) {
        BarcodeGenerator.generateQrCode(pairingCode, 420).toComposeImageBitmap()
    }
    val barBitmap = remember(pairingCode) {
        BarcodeGenerator.generateBarcode(pairingCode, 560, 140).toComposeImageBitmap()
    }

    Window(
        onCloseRequest = onDismiss,
        title = "配对至 iPhone",
        undecorated = true,
        transparent = true,
        resizable = false,
        state = rememberWindowState(
            width = 900.dp,
            height = 600.dp,
            position = WindowPosition.Aligned(Alignment.Center)
        )
    ) {
        // 无边框窗口：transparent=true 让边缘 alpha 混合抗锯齿，用 RoundRectangle2D 裁圆角
        DisposableEffect(Unit) {
            val applyShape = {
                window.shape = RoundRectangle2D.Double(
                    0.0, 0.0,
                    window.width.toDouble(), window.height.toDouble(),
                    14.0, 14.0
                )
            }
            applyShape()
            val listener = object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent?) { applyShape() }
                override fun componentMoved(e: ComponentEvent?) { applyShape() }
            }
            window.addComponentListener(listener)
            onDispose { window.removeComponentListener(listener) }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp))
        ) {
            Column(Modifier.fillMaxSize()) {
                // 无边框标题栏：可拖动 + 关闭按钮
                Row(
                    Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp)
                        .then(windowDragModifier()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.PhoneIphone, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "配对至 iPhone",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(18.dp))
                    }
                }

                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                // ===== 左侧：条码面板（固定宽度）=====
                Column(
                    Modifier.width(360.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "用 iOS 端 PCML 扫描下方二维码或一维码即可快速配对",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    HorizontalDivider(Modifier.fillMaxWidth())

                    // 二维码：高像素 bitmap + ContentScale.Fit 在显示框内等比放大
                    // 二维码内容 = 完整配对码（000-000 XXXXX-XXXXX-XXXXX，含字母部分）
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "配对码二维码",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(300.dp)
                        )
                    }
                    Text(
                        "二维码",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 显示二维码内嵌的完整配对码，便于确认包含字母部分
                    Text(
                        pairingCode,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    // 一维码：显示框比例与 bitmap(560:140=4:1) 一致，ContentScale.FillBounds 拉伸贴合
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ) {
                        Image(
                            bitmap = barBitmap,
                            contentDescription = "配对码一维码",
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.width(320.dp).height(80.dp)
                        )
                    }
                    Text(
                        "Code 128 一维码",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                VerticalDivider(Modifier.fillMaxHeight().width(1.dp))

                // ===== 右侧：配对码文字 + 已配对设备（weight 填充剩余宽度，支持滚动）=====
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "在 iOS 端打开 PCML，输入下方配对码完成配对，" +
                            "即可远程控制桌面端 PMCL 启动游戏、浏览模组、好友聊天。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 配对码（大字展示：数字部分 + 字母部分两行）
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
                            Column(
                                Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // 数字部分 000-000
                                Text(
                                    pairingCode.take(7),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(4.dp))
                                // 字母部分 XXXXX-XXXXX-XXXXX
                                Text(
                                    pairingCode.drop(8),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    textAlign = TextAlign.Center
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
                } // 内容 Row 闭合
            } // Column(Surface 内) 闭合
        } // Surface 闭合
    } // Window lambda 闭合

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
 * 窗口拖拽修饰符：按住左键拖拽移动无边框窗口。
 * 用屏幕绝对坐标计算位移，避免相对 dragAmount 累加漂移。
 */
private fun WindowScope.windowDragModifier(): Modifier = Modifier.pointerInput(Unit) {
    var initialMouse: Point? = null
    var initialWindowLoc: Point? = null
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            val mouseLocation = MouseInfo.getPointerInfo()?.location
            if (event.buttons.isPrimaryPressed) {
                if (initialMouse == null && mouseLocation != null) {
                    initialMouse = mouseLocation
                    initialWindowLoc = Point(window.x, window.y)
                }
                val im = initialMouse
                val iwl = initialWindowLoc
                if (event.type == PointerEventType.Move && im != null && iwl != null && mouseLocation != null) {
                    val dx = mouseLocation.x - im.x
                    val dy = mouseLocation.y - im.y
                    window.setLocation(iwl.x + dx, iwl.y + dy)
                }
            } else {
                initialMouse = null
                initialWindowLoc = null
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
