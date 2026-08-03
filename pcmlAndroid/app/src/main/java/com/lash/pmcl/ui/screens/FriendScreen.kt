package com.lash.pmcl.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 好友页面 — 完整桌面端功能：QR 码、个人资料、好友列表、邀请分享。
 */
@Composable
fun FriendScreen() {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var inviteCode by remember { mutableStateOf(generateCode()) }
    var friends by remember { mutableStateOf(listOf("Player1", "Steve", "Alex")) }
    var showAddFriend by remember { mutableStateOf(false) }
    var newFriendName by remember { mutableStateOf("") }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    fun genQr(text: String): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, 256, 256)
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.RGB_565)
        for (x in 0 until 256) for (y in 0 until 256) {
            bmp.setPixel(x, y, if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
        return bmp
    }

    LaunchedEffect(inviteCode) {
        qrBitmap = withContext(kotlinx.coroutines.Dispatchers.Default) {
            genQr("pmcl://invite?code=$inviteCode")
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("好友", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("个人资料、邀请码与好友列表", style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(16.dp))

        // 个人资料
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
             colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(56.dp), shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Person, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("PMCL 玩家", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Android 客户端", style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                    Text("ID: ${inviteCode.take(4)}-${inviteCode.takeLast(4)}",
                         style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // QR 码
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
             colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("邀请二维码", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (qrBitmap != null) {
                    Image(bitmap = qrBitmap!!.asImageBitmap(), contentDescription = "QR", modifier = Modifier.size(200.dp))
                } else {
                    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(inviteCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(inviteCode))
                    }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("复制")
                    }
                    OutlinedButton(onClick = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, "加入我的 PMCL！邀请码: $inviteCode")
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "分享邀请码"))
                    }) {
                        Icon(Icons.Filled.Share, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("分享")
                    }
                    OutlinedButton(onClick = { inviteCode = generateCode() }) {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("刷新")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 好友列表
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("好友列表 (${friends.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                 modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { showAddFriend = true }) {
                Icon(Icons.Filled.PersonAdd, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("添加")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (friends.isEmpty()) {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                 colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("暂无好友，分享邀请码给朋友", color = MaterialTheme.colorScheme.outline,
                         style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                friends.forEach { name ->
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(Modifier.size(32.dp), shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Person, null, Modifier.size(16.dp))
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(name, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { friends = friends.filter { it != name } },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                                Text("移除")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddFriend) {
        AlertDialog(onDismissRequest = { showAddFriend = false },
            title = { Text("添加好友") },
            text = {
                OutlinedTextField(value = newFriendName, onValueChange = { newFriendName = it },
                    label = { Text("好友名称或邀请码") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newFriendName.isNotBlank()) { friends = friends + newFriendName; newFriendName = "" }
                    showAddFriend = false
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddFriend = false }) { Text("取消") } })
    }
}

private fun generateCode(): String {
    val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    return (1..8).map { chars.random() }.joinToString("")
}
