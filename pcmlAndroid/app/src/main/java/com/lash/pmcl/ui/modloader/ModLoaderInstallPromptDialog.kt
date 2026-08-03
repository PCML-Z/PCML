package com.lash.pmcl.ui.modloader

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.modloader.ModLoader

/**
 * 模组加载器选择条目。
 */
data class LoaderChoice(
    val loader: ModLoader?,
    val label: String,
    val color: Color,
    val description: String
)

private val loaderChoices = listOf(
    LoaderChoice(null, "Vanilla 纯净版", Color(0xFF6B7280), "不安装任何模组加载器，仅安装原版 Minecraft"),
    LoaderChoice(ModLoader.FABRIC, "Fabric", Color(0xFF8B909A), "轻量级模组加载器，社区活跃"),
    LoaderChoice(ModLoader.FORGE, "Forge", Color(0xFF1E4B8C), "传统模组加载器，生态最完善"),
    LoaderChoice(ModLoader.NEOFORGE, "NeoForge", Color(0xFFE36A1E), "Forge 社区分支，现代化架构"),
    LoaderChoice(ModLoader.QUILT, "Quilt", Color(0xFF8B5CF6), "Fabric 分支，更开放的治理模式"),
    LoaderChoice(ModLoader.LITELOADER, "LiteLoader", Color(0xFF4AA3DF), "轻量加载器，兼容原版"),
    LoaderChoice(ModLoader.OPTIFINE, "OptiFine", Color(0xFF7C9A3E), "优化与光影支持"),
)

/**
 * 模组加载器安装选择弹窗。
 * 与桌面端 com.pmcl.ui.modloader.ModLoaderInstallPromptDialog 功能一致。
 *
 * @param versionId 要安装的 Minecraft 版本 ID
 * @param onDismiss 关闭弹窗
 * @param onSelect 选择加载器后回调（null=Vanilla 纯净版）
 */
@Composable
fun ModLoaderInstallPromptDialog(
    versionId: String,
    onDismiss: () -> Unit,
    onSelect: (ModLoader?) -> Unit
) {
    var selected by remember { mutableStateOf<ModLoader?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("安装模组加载器", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "要安装 Minecraft $versionId，是否同步安装模组加载器？",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                loaderChoices.forEach { choice ->
                    val isSelected = selected == choice.loader
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clickable { selected = choice.loader },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        else
                            MaterialTheme.colorScheme.surface,
                        tonalElevation = if (isSelected) 1.dp else 0.dp
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(10.dp),
                                shape = RoundedCornerShape(5.dp),
                                color = choice.color,
                                content = {}
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    choice.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    choice.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (isSelected) {
                                Spacer(Modifier.width(8.dp))
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSelect(selected) }
            ) {
                Text(loaderChoices.find { it.loader == selected }?.label?.let { "安装 $it" } ?: "仅安装 Vanilla")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
