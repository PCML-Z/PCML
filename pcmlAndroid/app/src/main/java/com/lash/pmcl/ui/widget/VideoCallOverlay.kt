package com.lash.pmcl.ui.widget

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 视频通话悬浮窗 — 与桌面端 VideoCallOverlay 结构一致。
 */
@Composable
fun VideoCallOverlay(
    visible: Boolean,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var muted by remember { mutableStateOf(false) }
    var cameraOff by remember { mutableStateOf(false) }

    Card(
        Modifier.padding(16.dp).width(240.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            // 对方画面
            Surface(Modifier.fillMaxWidth().height(140.dp), shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.Videocam, null, Modifier.size(36.dp),
                             tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(4.dp))
                        Text("视频通话中", style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 控制按钮
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { muted = !muted }) {
                    Icon(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, null,
                         tint = if (muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { cameraOff = !cameraOff }) {
                    Icon(if (cameraOff) Icons.Filled.VideocamOff else Icons.Filled.Videocam, null,
                         tint = if (cameraOff) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.CallEnd, null, tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
