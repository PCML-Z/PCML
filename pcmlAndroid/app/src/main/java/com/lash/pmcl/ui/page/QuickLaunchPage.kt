package com.lash.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.core.auth.Account
import com.lash.pmcl.core.download.DownloadQueueState as DQS
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.modloader.ModLoader
import com.lash.pmcl.core.version.VersionManager
import com.lash.pmcl.ui.modloader.ModLoaderInstallPromptDialog
import com.lash.pmcl.ui.theme.LocalThemeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 快速启动欢迎页：每次启动 PMCL 时首先显示。
 * 与桌面端 com.pmcl.ui.page.QuickLaunchPage 完全一致。
 */
@Composable
fun QuickLaunchPage(
    core: LauncherCore,
    onEnterMain: () -> Unit
) {
    val themeState = LocalThemeState.current
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf<Account?>(null) }
    var localInfos by remember { mutableStateOf<List<VersionManager.LocalVersionInfo>>(emptyList()) }
    var selectedVersion by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf<InstallProgress?>(null) }
    var status by remember { mutableStateOf("就绪") }
    var gameRunning by remember { mutableStateOf(false) }
    var showModLoaderPrompt by remember { mutableStateOf(false) }

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
    val trueDownloadSize = 300_000_000L

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
        )

        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1.2f).fillMaxHeight().padding(24.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column {
                    Text("欢迎使用 PMCL",
                        style = MaterialTheme.typography.displayMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(24.dp))
                    val acc = account
                    if (acc != null) {
                        Text("已登录: ${acc.username}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline)
                    } else {
                        Text("未登录账号",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            Column(
                Modifier.weight(1f).fillMaxHeight().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启动游戏", style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    selectedVersion ?: "未选择版本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(Modifier.height(16.dp))

                val sv = selectedVersion
                if (sv != null) {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        if (themeState.glassTheme) {
                            Box(
                                Modifier.matchParentSize()
                                    .clip(RoundedCornerShape(12.dp))
                                    .blur(18.dp)
                                    .background(
                                        MaterialTheme.colorScheme.surface.copy(alpha = 0.42f),
                                        RoundedCornerShape(12.dp)
                                    )
                            )
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = if (themeState.glassTheme) Color.Transparent
                                    else MaterialTheme.colorScheme.surface,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(sv,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (isInstalled) "已安装" else "未安装",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isInstalled) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = {
                        val vid = selectedVersion ?: return@Button
                        if (isDownloadMode) {
                            showModLoaderPrompt = true
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
                            Text("游戏运行中",
                                style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
                        }
                        installing && isDownloadMode -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onTertiary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("下载中…",
                                style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
                        }
                        isDownloadMode -> {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("下载并安装",
                                style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
                        }
                        else -> {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(24.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("启动 Minecraft",
                                style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
                        }
                    }
                }

                AnimatedVisibility(visible = installing && isDownloadMode && installProgress != null) {
                    val p = installProgress
                    if (p != null) {
                        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(p.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { (p.percent() / 100.0).toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onEnterMain,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("进入 PMCL", style = MaterialTheme.typography.titleMedium)
                }

                if (status.isNotEmpty() && status != "就绪") {
                    Spacer(Modifier.height(8.dp))
                    Text(status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }

    // ===== 模组加载器安装弹窗（与桌面端一致） =====
    if (showModLoaderPrompt && selectedVersion != null) {
        ModLoaderInstallPromptDialog(
            versionId = selectedVersion!!,
            onDismiss = { showModLoaderPrompt = false },
            onSelect = { loader ->
                showModLoaderPrompt = false
                installing = true
                status = "正在下载..."
                val vid = selectedVersion ?: return@ModLoaderInstallPromptDialog
                val queueId = DQS.register("Minecraft $vid (+$loader)", trueDownloadSize)
                scope.launch(Dispatchers.IO) {
                    try {
                        core.versionInstaller.install(vid) { p ->
                            installProgress = p
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
            }
        )
    }
}
