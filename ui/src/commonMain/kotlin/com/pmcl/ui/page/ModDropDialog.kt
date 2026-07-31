package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.pmcl.core.i18n.I18n
import com.pmcl.core.mods.ModDropInfo
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.awt.Dimension

/**
 * Mod 拖放安装对话框：拖入 .jar 后展示 mod 信息 + 兼容本地版本多选 + 安装按钮。
 *
 * 流程：
 * 1. [LauncherViewModel.dropInstallMod] 触发后，state.scanning=true 时显示加载圈
 * 2. 解析完成展示每个 mod 卡片，下方列出兼容本地版本（用 mainClass/inheritsFrom 推导），
 *    用户多选目标版本
 * 3. 点"安装选中"按钮 → [LauncherViewModel.confirmDropInstall] → 复制 jar 到目标 mods 目录
 *    → 关闭对话框
 */
@Composable
fun ModDropDialog(
    state: LauncherViewModel.DropInstallState,
    vm: LauncherViewModel,
    useDark: Boolean = false
) {
    val scheme = if (useDark) darkColorScheme() else lightColorScheme()
    val windowState = rememberWindowState(
        width = 720.dp,
        height = 560.dp,
        position = WindowPosition.Aligned(Alignment.Center)
    )

    Window(
        onCloseRequest = { vm.cancelDropInstall() },
        title = I18n.t("drop_install.title"),
        state = windowState,
        undecorated = false,
        focusable = true
    ) {
        // 限制最小窗口尺寸
        window.minimumSize = Dimension(560, 420)
        MaterialTheme(colorScheme = scheme) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    // 标题栏
                    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Extension, "mod",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                I18n.t("drop_install.title"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { vm.cancelDropInstall() }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Close, "close",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Divider()

                    // 内容区
                    when {
                        state.scanning -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(modifier = Modifier.size(36.dp))
                                    Spacer(Modifier.height(12.dp))
                                    Text(I18n.t("drop_install.scanning"),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        state.items.isEmpty() -> {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(I18n.t("drop_install.empty"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        else -> {
                            Column(
                                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                            ) {
                                state.items.forEach { info ->
                                    ModDropCard(info, vm, state)
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                            // 错误/提示消息
                            state.message?.let { msg ->
                                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                                    Text(
                                        msg,
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            // 底部按钮栏
                            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    val selectedCount = state.items.sumOf {
                                        (state.selectedVersions[it.getJarPath().toString()] ?: emptySet()).size
                                    }
                                    if (selectedCount > 0) {
                                        Text(
                                            I18n.t("drop_install.selected_count", selectedCount),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(end = 12.dp)
                                        )
                                    }
                                    TextButton(onClick = { vm.cancelDropInstall() }) {
                                        Text(I18n.t("drop_install.cancel"))
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(
                                        onClick = { vm.confirmDropInstall() },
                                        enabled = !state.installing && selectedCount > 0
                                    ) {
                                        if (state.installing) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                            Spacer(Modifier.width(8.dp))
                                        }
                                        Text(I18n.t("drop_install.install"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 单个 mod 的卡片：展示 mod 信息 + 兼容版本多选列表。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ModDropCard(
    info: ModDropInfo,
    vm: LauncherViewModel,
    state: LauncherViewModel.DropInstallState
) {
    val jarKey = info.getJarPath().toString()
    val selected = state.selectedVersions[jarKey] ?: emptySet()
    val compat = remember(info, vm.localVersionInfos.value.size) {
        vm.findCompatibleVersions(info)
    }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(Modifier.padding(14.dp)) {
            // 顶部：mod 名 + 版本 + 加载器 chip
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Extension, "mod",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    info.getName().ifEmpty { info.getJarPath().fileName.toString() },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                if (info.getVersion().isNotEmpty() && info.getVersion() != "unknown") {
                    Text(
                        "v${info.getVersion()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(8.dp))
                AssistChip(
                    onClick = {},
                    label = { Text(info.getLoader().ifEmpty { "unknown" }) },
                    leadingIcon = null
                )
                Spacer(Modifier.weight(1f))
                if (info.isModrinthFound()) {
                    Icon(
                        Icons.Filled.CheckCircle, "verified",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (info.getParseError() != null) {
                    Icon(
                        Icons.Filled.Error, "parse error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            // modId / SHA1 / 描述
            if (info.getModId().isNotEmpty() && info.getModId() != info.getName()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "modId: ${info.getModId()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (info.isModrinthFound() && info.getGameVersions().isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${I18n.t("drop_install.game_versions")}: ${info.getGameVersions().joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (info.getDescription().isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    info.getDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // 兼容版本列表
            Spacer(Modifier.height(10.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            Text(
                I18n.t("drop_install.select_versions"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(6.dp))
            if (compat.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Inbox, "no versions",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        I18n.t("drop_install.no_compatible"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // 多选 chips 网格（用 FlowRow 简化布局）
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    compat.forEach { lvi ->
                        val isSelected = selected.contains(lvi.getId())
                        FilterChip(
                            selected = isSelected,
                            onClick = { vm.toggleDropInstallSelection(jarKey, lvi.getId()) },
                            label = { Text(lvi.getId()) },
                            leadingIcon = if (isSelected) {
                                { Icon(Icons.Filled.Check, "selected", modifier = Modifier.size(14.dp)) }
                            } else null
                        )
                    }
                }
            }
        }
    }
}
