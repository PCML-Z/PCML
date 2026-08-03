package com.lash.pmcl.ui.page

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
import com.lash.pmcl.core.download.DownloadQueueState
import com.lash.pmcl.core.download.DownloadQueueState as DQS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.core.auth.Account
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.version.VersionManager
import com.lash.pmcl.ui.theme.LocalThemeState
import com.lash.pmcl.ui.theme.glassCardBorder
import com.lash.pmcl.ui.theme.glassCardColors
import com.lash.pmcl.ui.theme.glassCardElevation
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Origin OS2 风格锁屏启动页。
 * 与桌面端 com.pmcl.ui.page.LockscreenLaunchPage 完全一致。
 */
@Composable
fun LockscreenLaunchPage(
    core: LauncherCore,
    onEnterMain: () -> Unit
) {
    val themeState = LocalThemeState.current
    val cardShape = RoundedCornerShape(4.dp)

    var account by remember { mutableStateOf<Account?>(null) }
    var localInfos by remember { mutableStateOf<List<VersionManager.LocalVersionInfo>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf<InstallProgress?>(null) }
    var status by remember { mutableStateOf("就绪") }
    var gameRunning by remember { mutableStateOf(false) }

    val greeting = remember {
        val h = SimpleDateFormat("HH", Locale.getDefault()).format(Date()).toIntOrNull() ?: 0
        when (h) {
            in 5..10    -> "早上好"
            in 11..13   -> "中午好"
            in 14..17   -> "下午好"
            in 18..22   -> "晚上好"
            else        -> "夜深了"
        }
    }

    LaunchedEffect(Unit) {
        val store = withContext(Dispatchers.IO) { core.authService.loadStore() }
        account = store.accounts.find { it.uuid == store.selectedUuid }
        localInfos = withContext(Dispatchers.IO) { core.versionManager.scanLocalVersions() }
        core.preferences.getLastSelectedVersion()?.takeIf { it.isNotEmpty() }?.let { v -> selectedVersion = v }
        status = if (localInfos.isEmpty()) "暂无已安装版本" else "就绪"
    }

    val isInstalled = selectedVersion != null && localInfos.any { it.id == selectedVersion }
    val isDownloadMode = selectedVersion != null && !isInstalled
    val buttonEnabled = selectedVersion != null && !gameRunning && !installing
    val scope = rememberCoroutineScope()
    val trueDownloadSize = 300_000_000L // Minecraft + libraries + assets typically ~250-300MB

    Box(Modifier.fillMaxSize()) {
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

        Column(
            Modifier.fillMaxSize().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                Modifier.fillMaxWidth().padding(top = 40.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(greeting,
                    style = MaterialTheme.typography.headlineMedium,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f))
                Spacer(Modifier.height(8.dp))
                val acc = account
                Text(
                    acc?.username ?: "未登录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                Spacer(Modifier.height(4.dp))
                Text("PMCL · Minecraft Launcher",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }

            Card(
                modifier = Modifier.fillMaxWidth().glassCardBorder(4.dp),
                shape = cardShape,
                colors = glassCardColors(),
                elevation = glassCardElevation()
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 180.dp)) {
                    Column(
                        Modifier.weight(1f).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("启动游戏", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(
                            selectedVersion ?: "未选择版本",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, maxLines = 1)
                        Text(
                            if (selectedVersion == null) ""
                            else if (isInstalled) "已安装"
                            else "未安装",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isInstalled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary)

                        Spacer(Modifier.weight(1f))

                        Button(
                            onClick = {
                                val vid = selectedVersion ?: return@Button
                                if (isDownloadMode) {
                                    installing = true
                                    status = "正在下载..."
                                    val queueId = DQS.register("Minecraft $vid", trueDownloadSize)
                                    scope.launch(Dispatchers.IO) {
                                        try {
                                            core.versionInstaller.install(vid) { p ->
                                                if (p.total > 0) DQS.progress(queueId, p.completed)
                                            }.get()
                                            DQS.complete(queueId)
                                            withContext(Dispatchers.Main) {
                                                installing = false; installProgress = null
                                                status = "安装完成"
                                                localInfos = core.versionManager.scanLocalVersions()
                                            }
                                        } catch (e: Exception) {
                                            DQS.error(queueId, (e.cause?.message ?: e.message) ?: "未知错误")
                                            withContext(Dispatchers.Main) {
                                                installing = false; installProgress = null
                                                status = "安装失败: ${(e.cause?.message ?: e.message)}"
                                            }
                                        }
                                    }
                                } else if (isInstalled) {
                                    status = "正在启动..."
                                    gameRunning = true
                                    try {
                                        val profile = core.launchManager.buildProfile(vid, account)
                                        core.launchManager.launchAsync(profile, "java", null)
                                            .whenComplete { _, err ->
                                                if (err != null) {
                                                    gameRunning = false
                                                    status = "启动失败: ${err.message}"
                                                } else {
                                                    status = "游戏已启动"
                                                }
                                            }
                                    } catch (e: Exception) {
                                        gameRunning = false
                                        status = "启动失败: ${e.message}"
                                    }
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
                                    Text("游戏运行中", style = MaterialTheme.typography.titleMedium)
                                }
                                installing && isDownloadMode -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onTertiary)
                                    Spacer(Modifier.width(8.dp))
                                    Text("下载中…", style = MaterialTheme.typography.titleMedium)
                                }
                                isDownloadMode -> {
                                    Icon(Icons.Filled.Refresh, null, Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("下载并安装", style = MaterialTheme.typography.titleMedium)
                                }
                                else -> {
                                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(22.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("启动 Minecraft", style = MaterialTheme.typography.titleMedium)
                                }
                            }
                        }

                        AnimatedVisibility(visible = installing && isDownloadMode && installProgress != null) {
                            val p = installProgress
                            if (p != null) {
                                Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    Text(p.message,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline)
                                    Spacer(Modifier.height(4.dp))
                                    LinearProgressIndicator(
                                        progress = { (p.percent() / 100.0).toFloat() },
                                        modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }

                    VerticalDivider(
                        modifier = Modifier.fillMaxHeight(),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                    Column(
                        Modifier.weight(0.55f).padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("进入 PMCL", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text("PMCL", style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text("管理版本、模组、存档等",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)

                        Spacer(Modifier.weight(1f))

                        if (status.isNotEmpty() && status != "就绪") {
                            Text(status, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline, maxLines = 2)
                        }

                        Button(
                            onClick = onEnterMain,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            shape = cardShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
                        ) {
                            Text("进入 PMCL", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, "进入主界面",
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
