package com.pmcl.ui.page

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.launch.StandaloneMacAppExporter
import com.pmcl.ui.animation.MotionTokens
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.StandaloneExportUi
import com.pmcl.ui.viewmodel.clearStandaloneExport
import com.pmcl.ui.viewmodel.exportStandaloneMacApp
import com.pmcl.ui.viewmodel.standaloneStr
import java.awt.FileDialog
import java.nio.file.Path
import java.nio.file.Paths

@Composable
fun StandaloneAppExportButton(
    vm: LauncherViewModel,
    versionId: String,
    instanceDir: Path? = null,
    defaultName: String,
    enabled: Boolean = true
) {
    if (!StandaloneMacAppExporter.isMacOs()) return
    var showSetup by remember { mutableStateOf(false) }
    val packing by vm.standaloneExport.collectAsState()
    IconButton(
        onClick = { showSetup = true },
        enabled = enabled && versionId.isNotBlank() && packing == null,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            Icons.Filled.Computer,
            contentDescription = standaloneStr("导出独立 App", "Export standalone app"),
            modifier = Modifier.size(18.dp),
            tint = if (enabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
    }
    if (showSetup && packing == null) {
        StandaloneAppExportDialog(
            defaultName = defaultName.ifBlank { versionId },
            onDismiss = { showSetup = false },
            onConfirm = { includeSaves, target ->
                showSetup = false
                vm.exportStandaloneMacApp(versionId, instanceDir, includeSaves, target)
            }
        )
    }
    packing?.let { state ->
        StandaloneExportProgressDialog(
            state = state,
            onDismiss = { vm.clearStandaloneExport() }
        )
    }
}

@Composable
private fun StandaloneAppExportDialog(
    defaultName: String,
    onDismiss: () -> Unit,
    onConfirm: (Boolean, Path) -> Unit
) {
    var includeSaves by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(standaloneStr("导出独立游戏 App", "Export standalone app")) },
        text = {
            Column {
                Text(
                    standaloneStr(
                        "把这个版本的加载器、模组、光影、资源包、资源和 Java 打成可双击的 .app。已与本机 PMCL 强绑定：删除启动器或分发到其他电脑后无法启动。离线会话用当前玩家名。",
                        "Bundle this version's loader, mods, shaders, resource packs, assets, and Java into a .app bound to this machine's PMCL. Deleting the launcher or copying the app elsewhere will block launch."
                    ),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeSaves, onCheckedChange = { includeSaves = it })
                    Spacer(Modifier.width(4.dp))
                    Text(standaloneStr("包含存档和截图", "Include worlds and screenshots"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val fd = FileDialog(
                    java.awt.Frame(),
                    standaloneStr("保存独立 App", "Save standalone app"),
                    FileDialog.SAVE
                )
                fd.file = StandaloneMacAppExporter.sanitizeAppName(defaultName) + ".app"
                fd.isVisible = true
                val file = fd.file
                val dir = fd.directory
                if (file != null && dir != null) {
                    onConfirm(includeSaves, Paths.get(dir, file))
                }
            }) {
                Text(standaloneStr("选择位置", "Choose location"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(standaloneStr("取消", "Cancel"))
            }
        }
    )
}

@Composable
private fun StandaloneExportProgressDialog(
    state: StandaloneExportUi,
    onDismiss: () -> Unit
) {
    val animated by animateFloatAsState(
        targetValue = state.fraction.coerceIn(0f, 1f),
        animationSpec = tween(
            durationMillis = MotionTokens.DURATION_LONG,
            easing = MotionTokens.EasingEmphasizedDecelerate
        ),
        label = "standalone-pack"
    )
    val failed = state.error != null
    val title = when {
        failed -> standaloneStr("打包失败", "Packing failed")
        state.finished -> standaloneStr("打包完成", "Packing done")
        else -> standaloneStr("正在打包", "Packing")
    }
    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                PackingBars(running = state.running && !failed)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (failed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        maxLines = 2
                    )
                    Text(
                        "${(animated * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = if (failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        },
        confirmButton = {
            if (!state.running) {
                TextButton(onClick = onDismiss) {
                    Text(standaloneStr("好", "OK"))
                }
            }
        }
    )
}

@Composable
private fun PackingBars(running: Boolean) {
    val color = MaterialTheme.colorScheme.primary
    val inf = rememberInfiniteTransition(label = "pack-bars")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pack-phase"
    )
    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(5) { i ->
            val wave = if (running) ((phase + i * 0.16f) % 1f) else 0.35f
            val lift = if (wave < 0.5f) wave * 2f else (1f - wave) * 2f
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .width(9.dp)
                    .height((8 + 16 * lift).dp)
                    .graphicsLayer { alpha = 0.4f + 0.6f * lift }
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}
