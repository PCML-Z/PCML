package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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

    // 根据当前小时段生成欢迎语
    val greeting = remember(now) {
        val h = SimpleDateFormat("HH", Locale.getDefault()).format(Date(now)).toIntOrNull() ?: 0
        when (h) {
            in 5..10    -> "早上好"
            in 11..13   -> "中午好"
            in 14..17   -> "下午好"
            in 18..22   -> "晚上好"
            else        -> "夜深了"
        }
    }

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
            // ----- 顶部：左欢迎语 + 右时钟，左右分栏 -----
            Row(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // 左侧：欢迎语
                Column(
                    Modifier.weight(1f).padding(end = 16.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        greeting,
                        style = MaterialTheme.typography.headlineMedium,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
                    )
                    Spacer(Modifier.height(8.dp))
                    val acc = account
                    Text(
                        acc?.username ?: I18n.t("launch.not_logged_in_short"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "PMCL · Minecraft Launcher",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }

                // 右侧：时钟数字本身就是液态玻璃（无背景容器）
                // 分层渲染：模糊外发光 + 高光描边 + 清晰主体文字
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        // 最底层：大半径模糊的白色文字，形成柔和外发光（液态玻璃光晕）
                        Text(
                            timeFmt.format(Date(now)),
                            style = MaterialTheme.typography.displayLarge,
                            fontSize = 140.sp,
                            fontWeight = FontWeight.Light,
                            color = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.blur(28.dp)
                        )
                        // 中层：中等模糊的 primary 色文字，形成玻璃色相底
                        Text(
                            timeFmt.format(Date(now)),
                            style = MaterialTheme.typography.displayLarge,
                            fontSize = 140.sp,
                            fontWeight = FontWeight.Light,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                            modifier = Modifier.blur(12.dp)
                        )
                        // 顶层：清晰主体文字（高透明白色，液态玻璃质感）
                        Text(
                            timeFmt.format(Date(now)),
                            style = MaterialTheme.typography.displayLarge,
                            fontSize = 140.sp,
                            fontWeight = FontWeight.Light,
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    // 装饰分隔条
                    Box(
                        Modifier
                            .width(60.dp)
                            .height(2.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                        Color.Transparent
                                    )
                                ),
                                RoundedCornerShape(1.dp)
                            )
                    )
                    Spacer(Modifier.height(10.dp))
                    // 日期（次级）
                    Text(
                        dateFmt.format(Date(now)),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                    )
                }
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
