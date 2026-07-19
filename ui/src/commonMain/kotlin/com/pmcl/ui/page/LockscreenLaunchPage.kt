package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.ParallaxBackground
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Origin OS2 风格锁屏启动页。
 *
 * 布局：
 * - 全屏背景（视差背景或渐变）
 * - 顶部居中：大时钟 + 日期
 * - 底部：左侧启动主卡片（方形，4dp 圆角）+ 右侧进入主界面按钮列
 *
 * 与 QuickLaunchPage 并存，通过 lockscreenLaunchTheme 开关切换。
 * 保留 onEnterMain 回调进入主界面。
 */
@Composable
fun LockscreenLaunchPage(
    vm: LauncherViewModel,
    onEnterMain: () -> Unit
) {
    val themeState = LocalThemeState.current
    val selectedVersion by vm.selectedVersion.collectAsState()
    val localInfos by vm.localVersionInfos.collectAsState()
    val account by vm.account.collectAsState()
    val gameRunning by vm.gameRunning.collectAsState()
    val status by vm.status.collectAsState()
    val installing by vm.installing.collectAsState()
    val installProgress by vm.installProgress.collectAsState()
    val compatOptions by vm.compatOptions.collectAsState()
    val compatTitle by vm.compatTitle.collectAsState()

    // 每秒刷新的时钟
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("M月d日 EEEE", Locale.CHINESE) }
    val cardShape = RoundedCornerShape(4.dp)

    val isInstalled = selectedVersion != null && localInfos.any { it.getId() == selectedVersion }
    val isDownloadMode = selectedVersion != null && !isInstalled
    val buttonEnabled = selectedVersion != null && !gameRunning && !installing

    Box(Modifier.fillMaxSize()) {
        // ===== 全屏背景层：优先使用视差背景，否则用渐变 =====
        if (themeState.parallaxBackground) {
            ParallaxBackground(modifier = Modifier.fillMaxSize(), useDark = themeState.useDark)
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
            )
        }

        // ===== 内容层 =====
        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ----- 顶部：大时钟 + 日期（居中） -----
            Column(
                Modifier.fillMaxWidth().padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    timeFmt.format(Date(now)),
                    style = MaterialTheme.typography.displayLarge,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    dateFmt.format(Date(now)),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                // 账号信息（小字）
                Spacer(Modifier.height(4.dp))
                val acc = account
                Text(
                    acc?.username ?: I18n.t("launch.not_logged_in_short"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            // ----- 底部：单一大方形卡片，内部左右分栏（启动 / 进入主界面），等高对齐 -----
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = cardShape,
                colors = glassCardColors()
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 180.dp)) {
                    // 左侧：启动区
                    Column(
                        Modifier.weight(1f).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            I18n.t("launch.start"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            selectedVersion ?: I18n.t("launch.no_version_selected"),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            if (selectedVersion == null) ""
                            else if (isInstalled) I18n.t("launch.installed")
                            else I18n.t("launch.not_installed"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isInstalled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary
                        )

                        Spacer(Modifier.weight(1f))

                        // 启动按钮
                        Button(
                            onClick = {
                                if (isDownloadMode) {
                                    selectedVersion?.let { vm.enqueueVersionInstall(it) }
                                } else {
                                    vm.launch()
                                }
                            },
                            enabled = buttonEnabled,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = cardShape,
                            colors = if (isDownloadMode) ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiary
                            ) else ButtonDefaults.buttonColors()
                        ) {
                            when {
                                gameRunning -> {
                                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(I18n.t("launch.game_running"),
                                         style = MaterialTheme.typography.titleMedium)
                                }
                                installing && isDownloadMode -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onTertiary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(I18n.t("launch.downloading"),
                                         style = MaterialTheme.typography.titleMedium)
                                }
                                isDownloadMode -> {
                                    Icon(Icons.Filled.Refresh, null, Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(I18n.t("launch.download_install"),
                                         style = MaterialTheme.typography.titleMedium)
                                }
                                else -> {
                                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(I18n.t("launch.start_minecraft"),
                                         style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }

                        // 下载进度
                        AnimatedVisibility(visible = installing && isDownloadMode && installProgress != null) {
                            val p = installProgress
                            if (p != null) {
                                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
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
                    }

                    // 分隔线
                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 右侧：进入主界面区
                    Column(
                        Modifier.weight(0.55f).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            I18n.t("launch.enter"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "PMCL",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "进入启动器主界面",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )

                        Spacer(Modifier.weight(1f))

                        // 状态信息（如有）
                        if (status.isNotEmpty() && status != I18n.t("launch.ready")) {
                            Text(
                                status,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 2
                            )
                        }

                        // 进入主界面按钮（填充样式，和启动按钮风格统一）
                        Button(
                            onClick = onEnterMain,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = cardShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Text(I18n.t("launch.enter"),
                                 style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "进入",
                                 modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    // ===== 兼容性选项对话框（与 QuickLaunchPage 一致） =====
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
