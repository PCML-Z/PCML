package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 快速启动欢迎页：每次启动 PMCL 时首先显示。
 * 左右布局与主界面（LaunchPage）一致：左侧欢迎语，右侧启动控制。
 */
@Composable
fun QuickLaunchPage(
    vm: LauncherViewModel,
    onEnterMain: () -> Unit
) {
    val selectedVersion by vm.selectedVersion.collectAsState()
    val localInfos by vm.localVersionInfos.collectAsState()
    val account by vm.account.collectAsState()
    val gameRunning by vm.gameRunning.collectAsState()
    val status by vm.status.collectAsState()
    val installing by vm.installing.collectAsState()
    val installProgress by vm.installProgress.collectAsState()
    val compatOptions by vm.compatOptions.collectAsState()
    val compatTitle by vm.compatTitle.collectAsState()

    val isInstalled = selectedVersion != null && localInfos.any { it.getId() == selectedVersion }
    val isDownloadMode = selectedVersion != null && !isInstalled
    val buttonEnabled = selectedVersion != null && !gameRunning && !installing

    Row(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    MaterialTheme.colorScheme.surface
                )
            )
        )
    ) {
        // ===== 左侧：欢迎语 =====
        Box(
            Modifier
                .weight(1.2f)
                .fillMaxHeight()
                .padding(24.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Column {
                Text(I18n.t("launch.welcome"),
                     style = MaterialTheme.typography.displayMedium,
                     color = MaterialTheme.colorScheme.primary,
                     fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                val acc = account
                if (acc != null) {
                    Text(I18n.t("launch.account_label", acc.username),
                         style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.outline)
                } else {
                    Text(I18n.t("launch.not_logged_in_short"),
                         style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // ===== 右侧：启动控制（和 LaunchPage 右侧一致） =====
        Column(
            Modifier.weight(1f).fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // === 标题栏 ===
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(I18n.t("launch.start"), style = MaterialTheme.typography.headlineSmall,
                     fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                selectedVersion ?: I18n.t("launch.no_version_selected"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(Modifier.height(16.dp))

            // === 选中版本卡片 ===
            val sv = selectedVersion
            if (sv != null) {
                Card(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(sv,
                             style = MaterialTheme.typography.titleMedium,
                             fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (isInstalled) I18n.t("launch.installed") else I18n.t("launch.not_installed"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isInstalled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // === 主按钮（和 LaunchPage 一致：绿色启动 / 紫色下载） ===
            Button(
                onClick = {
                    if (isDownloadMode) {
                        selectedVersion?.let { vm.installVersion(it) }
                    } else {
                        vm.launch()
                    }
                },
                enabled = buttonEnabled,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                shape = RoundedCornerShape(12.dp),
                colors = if (isDownloadMode) ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                ) else ButtonDefaults.buttonColors()
            ) {
                when {
                    gameRunning -> {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(I18n.t("launch.game_running"),
                             style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
                    }
                    installing && isDownloadMode -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onTertiary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(I18n.t("launch.downloading"),
                             style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
                    }
                    isDownloadMode -> {
                        Icon(Icons.Filled.Refresh, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(I18n.t("launch.download_install"),
                             style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
                    }
                    else -> {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(I18n.t("launch.start_minecraft"),
                             style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
                    }
                }
            }

            // === 下载进度条 ===
            AnimatedVisibility(visible = installing && isDownloadMode && installProgress != null) {
                val p = installProgress
                if (p != null) {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text(p.getMessage() ?: "",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { (p.percent() / 100f).toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // === 进入 PMCL 按钮 ===
            OutlinedButton(
                onClick = onEnterMain,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(I18n.t("launch.enter"), style = MaterialTheme.typography.titleMedium)
            }

            // === 状态信息 ===
            if (status.isNotEmpty() && status != I18n.t("launch.ready")) {
                Spacer(Modifier.height(8.dp))
                Text(status,
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }
    }

    // ===== 兼容性选项对话框 =====
    if (compatOptions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { vm.dismissCompatOptions() },
            title = { Text(compatTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    compatOptions.forEach { option ->
                        Surface(
                            onClick = { option.action() },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(option.title, style = MaterialTheme.typography.titleSmall,
                                     fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(option.description, style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                OutlinedButton(onClick = { vm.dismissCompatOptions() }) {
                    Text(I18n.t("common.cancel"))
                }
            }
        )
    }
}
