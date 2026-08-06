package com.lash.pmcl.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lash.pmcl.core.auth.Account
import com.lash.pmcl.core.auth.AccountStore
import com.lash.pmcl.core.auth.AuthService
import com.lash.pmcl.core.auth.DeviceCode
import com.lash.pmcl.core.install.InstallProgress
import com.lash.pmcl.core.install.VersionInstaller
import com.lash.pmcl.core.launch.CrashAnalyzer
import com.lash.pmcl.core.launch.GameProcess
import com.lash.pmcl.core.launch.LaunchManager
import com.lash.pmcl.core.preferences.Preferences
import com.lash.pmcl.core.version.McVersion
import com.lash.pmcl.core.version.VersionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

// ==================== 数据类 ====================

private class LogEntry(val seq: Int, val text: String)

private class RunningInstance(
    val id: String,
    val versionId: String,
    val accountName: String,
    val startTime: Long,
    val process: GameProcess,
)

private class CrashEventInfo(
    val versionId: String,
    val exitCode: Int,
    val report: CrashAnalyzer.CrashReport?,
    val recentLogs: List<String>,
)

private data class CompatOption(val title: String, val description: String)

private enum class LogLevelFilter(val label: String) {
    ALL("全部"),
    ERROR("错误"),
    WARN("警告"),
    INFO("信息");

    fun matches(text: String): Boolean {
        if (this == ALL) return true
        val lower = text.lowercase()
        return when (this) {
            ERROR -> lower.contains("/error]") || lower.contains("[error") ||
                lower.contains("exception") || lower.contains("caused by")
            WARN -> lower.contains("/warn]") || lower.contains("[warn") || lower.contains("warning")
            INFO -> lower.contains("/info]") || lower.contains("[info")
            ALL -> true
        }
    }
}

// ==================== 主 Composable ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchScreen(
    authService: AuthService,
    launchManager: LaunchManager,
    versionManager: VersionManager,
    preferences: Preferences,
    versionInstaller: VersionInstaller? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    // ===== 状态 =====
    var account by remember { mutableStateOf<Account?>(null) }
    var localInfos by remember { mutableStateOf<List<VersionManager.LocalVersionInfo>>(emptyList()) }
    var remoteVersions by remember { mutableStateOf<List<McVersion>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableStateOf<VersionManager.ScanProgress?>(null) }
    var installing by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf<InstallProgress?>(null) }
    var status by remember { mutableStateOf("") }
    var pinned by remember { mutableStateOf<List<String>>(emptyList()) }
    var pinnedLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var recents by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastPlayedTimes by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var versionCategory by remember { mutableStateOf(1) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    // 对话框状态
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var crashEvent by remember { mutableStateOf<CrashEventInfo?>(null) }
    var msDeviceCode by remember { mutableStateOf<DeviceCode?>(null) }
    var msStatus by remember { mutableStateOf("") }
    var msLoggingIn by remember { mutableStateOf(false) }
    var showMsDialog by remember { mutableStateOf(false) }
    val compatOptions by remember { mutableStateOf<List<CompatOption>>(emptyList()) }

    // 运行中实例
    val runningInstances = remember { mutableStateListOf<RunningInstance>() }
    var activeInstanceId by remember { mutableStateOf<String?>(null) }
    var launchTab by remember { mutableStateOf(0) } // 0=启动 1=版本列表 2=账号 3=日志

    // 日志
    val logs = remember { mutableStateListOf<LogEntry>() }
    val logSeq = remember { AtomicInteger(0) }

    val crashAnalyzer = remember { CrashAnalyzer() }
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val formatRelative = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // ===== 派生状态 =====
    val localInfoIds = remember(localInfos) { localInfos.map { it.id }.toHashSet() }
    val pinnedIds = remember(pinned) { pinned.toHashSet() }
    val recentNotPinned = remember(recents, pinnedIds, localInfoIds) {
        recents.filter { it !in pinnedIds && it in localInfoIds }
    }
    val isInstalled = remember(selected, localInfoIds) {
        selected != null && selected in localInfoIds
    }
    val gameRunning = runningInstances.isNotEmpty()
    val hasAccount = account != null

    // 搜索防抖
    LaunchedEffect(searchQuery) {
        delay(250)
        debouncedQuery = searchQuery
    }
    val filteredRemote = remember(remoteVersions, versionCategory, debouncedQuery) {
        var list = remoteVersions
        if (versionCategory != 0) {
            val typeFilter = when (versionCategory) {
                1 -> "release"
                2 -> "snapshot"
                3 -> "old_beta"
                else -> "old_alpha"
            }
            list = list.filter { it.type == typeFilter }
        }
        if (debouncedQuery.isNotEmpty()) {
            list = list.filter { it.id.contains(debouncedQuery, ignoreCase = true) }
        }
        list.take(200)
    }

    // ===== 辅助函数 =====
    fun addLog(text: String) {
        logs.add(LogEntry(logSeq.incrementAndGet(), text))
        while (logs.size > 500) logs.removeAt(0)
        com.lash.pmcl.core.launch.LogCollector.add(text)
    }

    fun refreshPinnedLabels() {
        pinnedLabels = pinned.mapNotNull { vid ->
            preferences.getPinnedTileLabel(vid)?.let { vid to it }
        }.toMap()
    }

    fun reloadAccount() {
        scope.launch {
            account = withContext(Dispatchers.IO) {
                try {
                    val store = authService.loadStore()
                    store.accounts.firstOrNull { it.uuid == store.selectedUuid }
                        ?: store.accounts.firstOrNull()
                } catch (_: Exception) { null }
            }
        }
    }

    fun refreshLocal() {
        scanning = true
        scanProgress = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    versionManager.scanAllLocalVersions { p -> scanProgress = p }
                } catch (_: Exception) { emptyList() }
            }
            localInfos = result
            scanning = false
            scanProgress = null
            if (selected == null) {
                val last = preferences.getLastSelectedVersion()
                selected = if (last.isNotEmpty() && result.any { it.id == last }) {
                    last
                } else {
                    result.firstOrNull { it.isLaunchable }?.id
                }
            }
        }
    }

    // ===== 初始加载 =====
    LaunchedEffect(Unit) {
        reloadAccount()
        refreshLocal()
        scope.launch {
            try {
                val remote = withContext(Dispatchers.IO) { versionManager.fetchRemoteVersions().join() }
                remoteVersions = remote
            } catch (_: Exception) {}
        }
        scope.launch {
            pinned = preferences.getPinnedVersions()
            recents = preferences.getRecentVersions()
            lastPlayedTimes = preferences.getAllLastPlayedTimes()
            refreshPinnedLabels()
        }
    }

    // ===== 回调 =====
    fun onSelectVersion(id: String) {
        selected = id
        preferences.setLastSelectedVersion(id)
    }

    fun onPin(id: String) {
        preferences.pinVersion(id)
        pinned = preferences.getPinnedVersions()
        refreshPinnedLabels()
    }

    fun onUnpin(id: String) {
        preferences.unpinVersion(id)
        pinned = preferences.getPinnedVersions()
        refreshPinnedLabels()
    }

    fun doLaunch(versionId: String, acc: Account?) {
        if (acc == null) {
            status = "请先登录账号"
            return
        }
        status = "正在构造启动配置..."
        scope.launch {
            try {
                val profile = withContext(Dispatchers.IO) {
                    launchManager.buildProfile(versionId, acc)
                }
                val deny = launchManager.verifyBeforeLaunch(profile)
                if (deny != null) {
                    status = deny
                    addLog(deny)
                    return@launch
                }
                val javaPath = preferences.getVersionJavaPath(versionId).ifEmpty { "java" }
                addLog("[PMCL] 启动 $versionId（玩家: ${acc.username}）")
                status = "启动中..."
                launchManager.launchAsync(profile, javaPath) { line -> addLog(line) }
                    .whenComplete { proc, err ->
                        if (err != null) {
                            status = "启动失败: ${err.message}"
                            addLog("[PMCL] 启动失败: ${err.message}")
                        } else {
                            status = "游戏进程已启动: $versionId"
                            val inst = RunningInstance(
                                id = UUID.randomUUID().toString(),
                                versionId = versionId,
                                accountName = acc.username,
                                startTime = System.currentTimeMillis(),
                                process = proc,
                            )
                            runningInstances.add(inst)
                            activeInstanceId = inst.id
                            preferences.recordRecentVersion(versionId)
                            preferences.setLastPlayedTime(versionId, System.currentTimeMillis())
                            recents = preferences.getRecentVersions()
                            lastPlayedTimes = preferences.getAllLastPlayedTimes()
                            Thread {
                                try {
                                    val exitCode = proc.waitFor()
                                    runningInstances.remove(inst)
                                    if (exitCode != 0 && exitCode != LaunchManager.EXIT_CANCELLED) {
                                        val recentLogLines = logs.map { it.text }
                                        val report = try {
                                            crashAnalyzer.analyze(recentLogLines.joinToString("\n"), null)
                                        } catch (_: Exception) { null }
                                        crashEvent = CrashEventInfo(
                                            versionId, exitCode, report, recentLogLines.takeLast(50)
                                        )
                                    }
                                } catch (_: InterruptedException) {
                                    Thread.currentThread().interrupt()
                                }
                            }.start()
                        }
                    }
            } catch (e: Exception) {
                status = "构造启动配置失败: ${e.message}"
                addLog("[PMCL] 错误: ${e.message}")
            }
        }
    }

    fun doInstall(versionId: String) {
        val installer = versionInstaller
        if (installer == null) {
            status = "请前往「版本」页面下载安装"
            addLog("[PMCL] 安装功能未启用，请使用版本页面安装 $versionId")
            return
        }
        installing = true
        installProgress = null
        status = "开始安装 $versionId ..."
        addLog("[PMCL] 开始安装 $versionId")
        scope.launch {
            try {
                installer.install(versionId) { p ->
                    installProgress = p
                    status = "${stageText(p.stage)} · ${p.message}"
                }.join()
                val refreshed = withContext(Dispatchers.IO) {
                    try { versionManager.scanAllLocalVersions() } catch (_: Exception) { localInfos }
                }
                localInfos = refreshed
                status = "安装完成: $versionId"
                addLog("[PMCL] 安装完成: $versionId")
            } catch (e: Exception) {
                status = "安装失败: ${e.message}"
                addLog("[PMCL] 安装失败: ${e.message}")
            } finally {
                installing = false
            }
        }
    }

    fun doLoginOffline(username: String) {
        if (username.isBlank()) return
        scope.launch {
            try {
                val acc = authService.offline(username.trim())
                val store = withContext(Dispatchers.IO) { authService.loadStore() }
                val newAccounts = store.accounts.filter { it.uuid != acc.uuid } + acc
                withContext(Dispatchers.IO) {
                    authService.saveStore(AccountStore(newAccounts, acc.uuid))
                }
                preferences.setLastOfflineUsername(username.trim())
                account = acc
                status = "离线登录成功: ${acc.username}"
            } catch (e: Exception) {
                status = "离线登录失败: ${e.message}"
            }
        }
    }

    fun doLoginMicrosoft() {
        msLoggingIn = true
        msStatus = "正在请求设备码..."
        showMsDialog = true
        scope.launch {
            try {
                val dc = withContext(Dispatchers.IO) { authService.requestDeviceCode() }
                msDeviceCode = dc
                msStatus = "请在浏览器中完成登录"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(intent) } catch (_: Exception) {}
                authService.loginMicrosoftAsync(dc) { msg -> msStatus = msg }
                    .whenComplete { acc, err ->
                        msLoggingIn = false
                        if (err != null) {
                            msStatus = "登录失败: ${err.message ?: err.toString()}"
                        } else {
                            msDeviceCode = null
                            msStatus = "登录成功: ${acc.username}"
                            scope.launch {
                                try {
                                    val store = withContext(Dispatchers.IO) { authService.loadStore() }
                                    val newAccounts = store.accounts.filter { it.uuid != acc.uuid } + acc
                                    withContext(Dispatchers.IO) {
                                        authService.saveStore(AccountStore(newAccounts, acc.uuid))
                                    }
                                    account = acc
                                } catch (_: Exception) {}
                                showMsDialog = false
                            }
                        }
                    }
            } catch (e: Exception) {
                msLoggingIn = false
                msStatus = "请求设备码失败: ${e.message}"
            }
        }
    }

    fun doCopyLogs() {
        val text = logs.map { it.text }.joinToString("\n")
        clipboard.setText(AnnotatedString(text))
    }

    fun doShareLogs() {
        val text = logs.map { it.text }.joinToString("\n")
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            context.startActivity(Intent.createChooser(shareIntent, "分享日志"))
        } catch (_: Exception) {}
    }

    // ===== 布局 =====
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (launchTab) {
                // ===== 启动（固定版本快速启动） =====
                0 -> LaunchHomeView(
                    pinned = pinned,
                    pinnedLabels = pinnedLabels,
                    localInfos = localInfos,
                    account = account,
                    status = status,
                    runningInstances = runningInstances,
                    activeInstanceId = activeInstanceId,
                    preferences = preferences,
                    onLaunch = { doLaunch(it, account) },
                    onGoToVersions = { launchTab = 1 },
                )
                // ===== 版本列表（可直接启动游戏） =====
                1 -> LazyColumn(
                    Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    leftPanelItems(
                        localInfos = localInfos,
                        filteredRemote = filteredRemote,
                        selected = selected,
                        scanning = scanning,
                        scanProgress = scanProgress,
                        pinned = pinned,
                        pinnedLabels = pinnedLabels,
                        recentNotPinned = recentNotPinned,
                        lastPlayedTimes = lastPlayedTimes,
                        hasAccount = hasAccount,
                        gameRunning = gameRunning,
                        versionCategory = versionCategory,
                        searchQuery = searchQuery,
                        format = format,
                        formatRelative = formatRelative,
                        onSelectVersion = ::onSelectVersion,
                        onRefreshLocal = ::refreshLocal,
                        onPin = ::onPin,
                        onUnpin = ::onUnpin,
                        onQuickLaunch = { doLaunch(it, account) },
                        onRename = { renameTarget = it },
                        onDelete = { deleteTarget = it },
                        onCategoryChange = { versionCategory = it },
                        onSearchQueryChange = { searchQuery = it },
                    )
                    // 选中版本操作栏（安装未安装的远程版本）
                    selected?.let { sel ->
                        item(key = "selected-action-bar") {
                            val selInstalled = sel in localInfoIds
                            if (!selInstalled) {
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { doInstall(sel) },
                                    enabled = !installing,
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    shape = RoundedCornerShape(8.dp),
                                ) {
                                    if (installing) {
                                        CircularProgressIndicator(
                                            Modifier.size(18.dp), strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary,
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text("安装中...")
                                    } else {
                                        Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("下载安装 $sel")
                                    }
                                }
                                if (installing && installProgress != null) {
                                    val p = installProgress!!
                                    val frac = if (p.total > 0) (p.completed.toFloat() / p.total).coerceIn(0f, 1f) else 0f
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "${stageText(p.stage)} · ${p.message}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    LinearProgressIndicator(
                                        progress = { frac },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
                // ===== 账号 =====
                2 -> Column(
                    Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AccountCard(
                        account = account,
                        initialOfflineUsername = preferences.getLastOfflineUsername().ifBlank { "Steve" },
                        onLoginOffline = ::doLoginOffline,
                        onLoginMicrosoft = ::doLoginMicrosoft,
                    )
                    if (status.isNotEmpty()) {
                        Text("状态: $status",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                // ===== 日志 =====
                3 -> Column(Modifier.fillMaxSize().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("游戏日志", style = MaterialTheme.typography.labelLarge,
                             fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        var copied by remember { mutableStateOf(false) }
                        TextButton(
                            onClick = { doCopyLogs(); copied = true },
                            enabled = logs.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            if (copied) {
                                Icon(Icons.Filled.Check, null, Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("已复制", style = MaterialTheme.typography.labelSmall)
                            } else {
                                Text("复制", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        LaunchedEffect(copied) {
                            if (copied) { delay(1500); copied = false }
                        }
                        TextButton(
                            onClick = ::doShareLogs,
                            enabled = logs.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Filled.IosShare, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("分享", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(
                            onClick = { logs.clear() },
                            enabled = logs.isNotEmpty(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                        ) {
                            Icon(Icons.Filled.Clear, null, Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("清空", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    GameLogPanel(logs) { logs.clear() }
                }
            }
        }

        // ===== 底部导航栏 =====
        NavigationBar {
            NavigationBarItem(
                selected = launchTab == 0,
                onClick = { launchTab = 0 },
                icon = { Icon(Icons.Filled.PlayArrow, contentDescription = "启动") },
                label = { Text("启动") },
            )
            NavigationBarItem(
                selected = launchTab == 1,
                onClick = { launchTab = 1 },
                icon = { Icon(Icons.Filled.Star, contentDescription = "版本列表") },
                label = { Text("版本列表") },
            )
            NavigationBarItem(
                selected = launchTab == 2,
                onClick = { launchTab = 2 },
                icon = { Icon(Icons.Outlined.AccountCircle, contentDescription = "账号") },
                label = { Text("账号") },
            )
            NavigationBarItem(
                selected = launchTab == 3,
                onClick = { launchTab = 3 },
                icon = { Icon(Icons.Filled.Code, contentDescription = "日志") },
                label = { Text("日志") },
            )
        }
    }

    // ===== 对话框 =====
    renameTarget?.let { targetId ->
        val currentLabel = pinnedLabels[targetId] ?: targetId
        RenameTileDialog(
            versionId = targetId,
            initialText = currentLabel,
            onConfirm = { newName ->
                preferences.setPinnedTileLabel(targetId, newName)
                refreshPinnedLabels()
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { targetId ->
        val displayName = pinnedLabels[targetId] ?: targetId
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除磁贴") },
            text = { Text("确定要删除「$displayName」的快捷磁贴吗？（不会删除版本本身）") },
            confirmButton = {
                Button(
                    onClick = {
                        onUnpin(targetId)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }

    crashEvent?.let { ev ->
        CrashReportDialog(
            event = ev,
            onRecovery = { action ->
                when (action.type) {
                    CrashAnalyzer.RecoveryType.SHARE_LOGS -> doShareLogs()
                    else -> status = "已执行: ${action.title}"
                }
                crashEvent = null
            },
            onDismiss = { crashEvent = null },
        )
    }

    if (showMsDialog) {
        MicrosoftLoginDialog(
            deviceCode = msDeviceCode,
            status = msStatus,
            loggingIn = msLoggingIn,
            context = context,
            onDismiss = { showMsDialog = false },
        )
    }

    if (compatOptions.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("兼容性选项") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    compatOptions.forEach { option ->
                        Card(
                            onClick = {},
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.fillMaxWidth(),
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
                OutlinedButton(onClick = {}) { Text("取消") }
            }
        )
    }
}

// ==================== 左侧面板 ====================

@OptIn(ExperimentalMaterial3Api::class)
private fun LazyListScope.leftPanelItems(
    localInfos: List<VersionManager.LocalVersionInfo>,
    filteredRemote: List<McVersion>,
    selected: String?,
    scanning: Boolean,
    scanProgress: VersionManager.ScanProgress?,
    pinned: List<String>,
    pinnedLabels: Map<String, String>,
    recentNotPinned: List<String>,
    lastPlayedTimes: Map<String, Long>,
    hasAccount: Boolean,
    gameRunning: Boolean,
    versionCategory: Int,
    searchQuery: String,
    format: SimpleDateFormat,
    formatRelative: SimpleDateFormat,
    onSelectVersion: (String) -> Unit,
    onRefreshLocal: () -> Unit,
    onPin: (String) -> Unit,
    onUnpin: (String) -> Unit,
    onQuickLaunch: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    onCategoryChange: (Int) -> Unit,
    onSearchQueryChange: (String) -> Unit,
) {
    val localInfoIds = localInfos.map { it.id }.toHashSet()

    // 标题栏 + 扫描按钮
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启动", style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefreshLocal, enabled = !scanning) {
                Icon(
                    Icons.Filled.Refresh, "扫描",
                    Modifier.size(16.dp).then(
                        if (scanning) Modifier.rotate(0f) else Modifier
                    )
                )
                Spacer(Modifier.width(4.dp))
                Text(if (scanning) "扫描中..." else "扫描本地版本")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("已安装版本: ${localInfos.size} 个",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }

    // 扫描进度
    item {
        AnimatedVisibility(visible = scanning) {
            ScanProgressBar(scanProgress)
        }
        AnimatedVisibility(visible = !scanning && localInfos.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("未找到本地版本，请先下载安装一个版本",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }

    // 固定磁贴区
    item {
        Spacer(Modifier.height(12.dp))
        Text("快捷启动", style = MaterialTheme.typography.titleSmall,
             fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
    }

    if (pinned.isEmpty()) {
        item {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("暂无固定磁贴，点击版本旁的星标可添加",
                     modifier = Modifier.padding(12.dp),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }
    } else {
        val rows = pinned.chunked(2)
        rows.forEachIndexed { index, rowVersions ->
            item(key = "pinned-row-$index") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowVersions.forEach { versionId ->
                        val info = localInfos.find { it.id == versionId }
                        Box(Modifier.weight(1f)) {
                            PinnedTile(
                                versionId = versionId,
                                customLabel = pinnedLabels[versionId],
                                launchable = info?.isLaunchable ?: false,
                                hasAccount = hasAccount,
                                modLoaderHint = info?.let { inferModLoader(it) },
                                lastPlayedTime = lastPlayedTimes[versionId],
                                formatRelative = formatRelative,
                                onLaunch = { onQuickLaunch(versionId) },
                                onRename = { onRename(versionId) },
                                onDelete = { onDelete(versionId) },
                            )
                        }
                    }
                    if (rowVersions.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    // 最近使用
    if (recentNotPinned.isNotEmpty()) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Refresh, "最近使用",
                     modifier = Modifier.size(16.dp),
                     tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("最近使用", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold,
                     color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(6.dp))
        }
        items(recentNotPinned, key = { "recent-$it" }) { versionId ->
            RecentVersionRow(
                versionId = versionId,
                lastPlayedTime = lastPlayedTimes[versionId],
                formatRelative = formatRelative,
                hasAccount = hasAccount,
                onClick = { onSelectVersion(versionId) },
                onLaunch = { onQuickLaunch(versionId) },
            )
        }
    }

    // 分隔线 + 本地版本标题
    item {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))
        Text("本地版本", style = MaterialTheme.typography.titleSmall,
             fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
    }

    // 本地版本列表
    if (localInfos.isEmpty()) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("暂无本地版本", style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("请先下载安装一个版本",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    } else {
        itemsIndexed(localInfos, key = { _, info -> info.id }) { _, info ->
            LocalVersionRow(
                info = info,
                selected = info.id == selected,
                pinned = info.id in localInfoIds && pinned.contains(info.id),
                format = format,
                onClick = { onSelectVersion(info.id) },
                onPin = { onPin(info.id) },
                onUnpin = { onUnpin(info.id) },
            )
        }
    }

    // 分隔线 + 远程版本计数
    item {
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("远程版本: ${filteredRemote.size} 个",
             style = MaterialTheme.typography.labelMedium,
             color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
    }

    // 分类筛选 + 搜索
    item {
        Column {
            val categories = listOf("全部", "正式版", "快照", "旧Beta", "旧Alpha")
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                categories.forEachIndexed { index, label ->
                    FilterChip(
                        selected = versionCategory == index,
                        onClick = { onCategoryChange(index) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("搜索版本") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
        }
    }

    // 远程版本列表
    items(filteredRemote, key = { "remote-" + it.id }) { v ->
        RemoteVersionRow(
            id = v.id,
            type = v.type,
            selected = v.id == selected,
            installed = v.id in localInfoIds,
            onClick = { onSelectVersion(v.id) },
        )
    }
}

// ==================== 启动主页 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaunchHomeView(
    pinned: List<String>,
    pinnedLabels: Map<String, String>,
    localInfos: List<VersionManager.LocalVersionInfo>,
    account: Account?,
    status: String,
    runningInstances: List<RunningInstance>,
    activeInstanceId: String?,
    preferences: Preferences,
    onLaunch: (String) -> Unit,
    onGoToVersions: () -> Unit,
) {
    val hasAccount = account != null

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("PMCL", style = MaterialTheme.typography.headlineMedium,
             fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)

        if (pinned.isEmpty()) {
            // 未固定游戏版本
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(24.dp).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Filled.Star, null, Modifier.size(48.dp),
                         tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("未固定游戏版本",
                         style = MaterialTheme.typography.titleMedium,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                    Text("请在版本列表中固定一个版本以快速启动",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = onGoToVersions) {
                        Icon(Icons.Filled.Star, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("前往版本列表")
                    }
                }
            }
        } else {
            // 固定版本快速启动
            Text("快捷启动", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            pinned.forEach { versionId ->
                val info = localInfos.find { it.id == versionId }
                val launchable = info?.isLaunchable ?: false
                val canLaunch = launchable && hasAccount
                val displayName = pinnedLabels[versionId] ?: versionId
                val modLoaderHint = info?.let { inferModLoader(it) }

                val gradient = if (canLaunch) {
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                    )
                } else {
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    )
                }

                Box(
                    Modifier.fillMaxWidth().height(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(gradient)
                        .clickable(enabled = canLaunch) { onLaunch(versionId) }
                ) {
                    Column(Modifier.padding(12.dp).fillMaxSize()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(displayName,
                                     color = MaterialTheme.colorScheme.onPrimary,
                                     fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                                if (pinnedLabels[versionId] != null && pinnedLabels[versionId] != versionId) {
                                    Text(versionId,
                                         color = Color.White.copy(alpha = 0.7f),
                                         fontSize = 11.sp, maxLines = 1)
                                }
                            }
                            if (modLoaderHint != null) {
                                Surface(
                                    color = Color.White.copy(alpha = 0.22f),
                                    shape = RoundedCornerShape(4.dp),
                                ) {
                                    Text(modLoaderHint,
                                         color = MaterialTheme.colorScheme.onPrimary,
                                         fontSize = 10.sp, fontWeight = FontWeight.Medium,
                                         modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                                }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null,
                                 tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            val stateText = when {
                                !launchable -> "版本不可用"
                                !hasAccount -> "未登录账号"
                                else -> "点击启动"
                            }
                            Text(stateText,
                                 color = Color.White.copy(alpha = 0.95f),
                                 fontSize = 12.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // 账号状态
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AccountCircle, null, Modifier.size(28.dp),
                     tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                if (account != null) {
                    Column(Modifier.weight(1f)) {
                        Text(account.username, fontWeight = FontWeight.SemiBold)
                        Text(when (account.type) {
                            Account.AccountType.MICROSOFT -> "微软账号"
                            Account.AccountType.OFFLINE -> "离线账号"
                            Account.AccountType.GITHUB -> "GitHub"
                            Account.AccountType.YGGDRASIL -> "皮肤站"
                        }, style = MaterialTheme.typography.labelSmall,
                           color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    Text("未登录账号", Modifier.weight(1f),
                         color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        // 状态
        if (status.isNotEmpty()) {
            Text("状态: $status",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }

        // 运行中实例
        if (runningInstances.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("运行中实例",
                             style = MaterialTheme.typography.labelMedium,
                             fontWeight = FontWeight.SemiBold,
                             modifier = Modifier.weight(1f))
                        Text("${runningInstances.size}",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.primary,
                             fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    runningInstances.forEach { inst ->
                        val isActive = inst.id == activeInstanceId
                        val runtimeStr = formatRuntime(System.currentTimeMillis() - inst.startTime)
                        Surface(
                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(14.dp),
                                     tint = if (isActive) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.width(6.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(inst.versionId,
                                         style = MaterialTheme.typography.bodySmall,
                                         fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text("${inst.accountName} · $runtimeStr",
                                         style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.outline, maxLines = 1)
                                }
                                if (isActive) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(3.dp),
                                    ) {
                                        Text("活跃",
                                             color = MaterialTheme.colorScheme.onPrimary,
                                             style = MaterialTheme.typography.labelSmall,
                                             modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
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

// ==================== 子组件 ====================

@Composable
private fun PinnedTile(
    versionId: String,
    customLabel: String?,
    launchable: Boolean,
    hasAccount: Boolean,
    modLoaderHint: String?,
    lastPlayedTime: Long?,
    formatRelative: SimpleDateFormat,
    onLaunch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val enabled = launchable && hasAccount
    var menuExpanded by remember { mutableStateOf(false) }
    val displayName = customLabel?.takeIf { it.isNotEmpty() } ?: versionId

    val gradient = if (enabled) {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.primary,
                MaterialTheme.colorScheme.tertiary
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(gradient)
            .clickable(enabled = enabled) { onLaunch() }
    ) {
        Column(Modifier.padding(12.dp).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(displayName,
                         color = MaterialTheme.colorScheme.onPrimary,
                         fontWeight = FontWeight.Bold,
                         fontSize = 15.sp,
                         maxLines = 1)
                    if (customLabel != null && customLabel.isNotEmpty() && customLabel != versionId) {
                        Text(versionId,
                             color = Color.White.copy(alpha = 0.7f),
                             fontSize = 10.sp,
                             maxLines = 1)
                    }
                }
                if (modLoaderHint != null) {
                    Surface(
                        color = Color.White.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(modLoaderHint,
                             color = MaterialTheme.colorScheme.onPrimary,
                             fontSize = 10.sp,
                             fontWeight = FontWeight.Medium,
                             modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(20.dp),
                    ) {
                        Text("⋯",
                             color = Color.White.copy(alpha = 0.9f),
                             fontSize = 16.sp,
                             fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text("删除磁贴",
                                     color = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null,
                     tint = Color.White, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                val stateText = when {
                    !launchable -> "版本不可用"
                    !hasAccount -> "未登录账号"
                    else -> "点击启动"
                }
                Text(stateText,
                     color = Color.White.copy(alpha = 0.95f),
                     fontSize = 11.sp,
                     modifier = Modifier.weight(1f))
                if (lastPlayedTime != null && lastPlayedTime > 0) {
                    Text("上次: ${formatRelative.format(Date(lastPlayedTime))}",
                         color = Color.White.copy(alpha = 0.8f),
                         fontSize = 10.sp,
                         maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun RecentVersionRow(
    versionId: String,
    lastPlayedTime: Long?,
    formatRelative: SimpleDateFormat,
    hasAccount: Boolean,
    onClick: () -> Unit,
    onLaunch: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Refresh, null,
                 tint = MaterialTheme.colorScheme.outline,
                 modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(versionId,
                     style = MaterialTheme.typography.bodyMedium,
                     fontWeight = FontWeight.SemiBold,
                     maxLines = 1)
                if (lastPlayedTime != null && lastPlayedTime > 0) {
                    Text("上次游玩: ${formatRelative.format(Date(lastPlayedTime))}",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
            IconButton(onClick = onLaunch, enabled = hasAccount) {
                Icon(Icons.Filled.PlayArrow, "启动",
                     tint = if (hasAccount) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                     modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun LocalVersionRow(
    info: VersionManager.LocalVersionInfo,
    selected: Boolean,
    pinned: Boolean,
    format: SimpleDateFormat,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surfaceVariant
    Surface(onClick = onClick, color = bg, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.id,
                         style = MaterialTheme.typography.bodyLarge,
                         fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                    if (info.inheritsFrom != null) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text("继承: ${info.inheritsFrom}",
                                 style = MaterialTheme.typography.labelSmall,
                                 modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (info.hasJar) "jar ✓" else "jar ✗",
                         style = MaterialTheme.typography.labelSmall,
                         color = if (info.hasJar) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(6.dp))
                    Text(if (info.hasJson) "json ✓" else "json ✗",
                         style = MaterialTheme.typography.labelSmall,
                         color = if (info.hasJson) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.outline)
                    info.mainClass?.let { mc ->
                        Spacer(Modifier.width(8.dp))
                        Text(mc.substringAfterLast('.'),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                    if (info.lastModified > 0) {
                        Spacer(Modifier.width(8.dp))
                        Text(format.format(Date(info.lastModified)),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            IconButton(onClick = { if (pinned) onUnpin() else onPin() }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (pinned) "取消固定" else "固定",
                    tint = if (pinned) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun RemoteVersionRow(
    id: String,
    type: String,
    selected: Boolean,
    installed: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
             else MaterialTheme.colorScheme.surface
    Surface(onClick = onClick, color = bg, shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(id, style = MaterialTheme.typography.bodySmall,
                 modifier = Modifier.weight(1f))
            if (installed) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    Text("已安装",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSecondaryContainer,
                         modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(type, style = MaterialTheme.typography.labelSmall,
                 color = if (type == "release") MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun AccountCard(
    account: Account?,
    initialOfflineUsername: String,
    onLoginOffline: (String) -> Unit,
    onLoginMicrosoft: () -> Unit,
) {
    var username by remember { mutableStateOf(initialOfflineUsername) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("账号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (account != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.AccountCircle,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(account.username, fontWeight = FontWeight.SemiBold)
                        Text(when (account.type) {
                            Account.AccountType.MICROSOFT -> "微软账号"
                            Account.AccountType.OFFLINE -> "离线账号"
                            Account.AccountType.GITHUB -> "GitHub"
                            Account.AccountType.YGGDRASIL -> "皮肤站"
                        }, style = MaterialTheme.typography.labelSmall,
                           color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                Text("未登录", color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("离线用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onLoginOffline(username) },
                    enabled = username.isNotBlank(),
                ) { Text("离线登录") }
                OutlinedButton(onClick = onLoginMicrosoft) {
                    Text("微软登录")
                }
            }
        }
    }
}

@Composable
private fun GameLogPanel(
    logs: List<LogEntry>,
    onClear: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf(LogLevelFilter.ALL) }
    var autoScroll by remember { mutableStateOf(true) }
    val scrollState = rememberScrollState()

    val displayedLogs = remember(logs, searchQuery, levelFilter) {
        logs.asReversed()
            .asSequence()
            .filter { entry ->
                (searchQuery.isBlank() || entry.text.contains(searchQuery, ignoreCase = true)) &&
                    levelFilter.matches(entry.text)
            }
            .take(200)
            .toList()
            .asReversed()
    }

    LaunchedEffect(displayedLogs.size, autoScroll) {
        if (autoScroll && displayedLogs.isNotEmpty()) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(300.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxSize().padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    placeholder = { Text("搜索日志...", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                )
                IconButton(onClick = { autoScroll = !autoScroll }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (autoScroll) Icons.Filled.KeyboardArrowDown else Icons.Filled.Pause,
                        contentDescription = "自动滚动",
                        modifier = Modifier.size(18.dp),
                        tint = if (autoScroll) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LogLevelFilter.entries.forEach { f ->
                    FilterChip(
                        selected = levelFilter == f,
                        onClick = { levelFilter = f },
                        label = { Text(f.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(28.dp),
                    )
                }
            }
            if (displayedLogs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无日志", color = MaterialTheme.colorScheme.outline)
                }
            } else {
                val errorColor = MaterialTheme.colorScheme.error
                val warnColor = MaterialTheme.colorScheme.tertiary
                val infoColor = MaterialTheme.colorScheme.primary
                val normalColor = MaterialTheme.colorScheme.onSurface
                Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState)) {
                    Column {
                        displayedLogs.forEach { line ->
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = colorForLogLine(line.text, errorColor, warnColor, infoColor, normalColor),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanProgressBar(scanProgress: VersionManager.ScanProgress?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val fraction = scanProgress?.fraction ?: 0f
        val total = scanProgress?.total ?: 0
        val scanned = scanProgress?.scanned ?: 0
        val currentDir = scanProgress?.currentDir ?: ""
        val currentVer = scanProgress?.currentVersion ?: ""

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (total > 0) "扫描中 $scanned / $total"
                else "正在列出目录...",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (total > 0) {
                Text(
                    "${(fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (total > 0) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        if (currentVer.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "正在扫描: $currentDir / $currentVer",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
            )
        }
    }
}

// ==================== 对话框 ====================

@Composable
private fun RenameTileDialog(
    versionId: String,
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名磁贴") },
        text = {
            Column {
                Text("为版本 $versionId 设置自定义名称（清空则恢复默认）",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(versionId) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) { Text("保存") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun CrashReportDialog(
    event: CrashEventInfo,
    onRecovery: (CrashAnalyzer.RecoveryAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var showLogs by remember { mutableStateOf(false) }
    val report = event.report
    val causes = report?.causes ?: listOf("游戏异常退出（退出码 ${event.exitCode}），未生成崩溃报告")
    val suggestions = report?.suggestions ?: listOf("查看日志详细内容寻找异常堆栈")
    val recoveryActions = report?.recoveryActions ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("游戏崩溃", color = MaterialTheme.colorScheme.error)
            }
        },
        text = {
            Column {
                Text("版本: ${event.versionId}  退出码: ${event.exitCode}",
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Text("可能原因", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                causes.forEach { c ->
                    Text("• $c", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text("修复建议", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                suggestions.forEach { s ->
                    Text("• $s", style = MaterialTheme.typography.bodySmall)
                }
                if (recoveryActions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("恢复操作",
                         style = MaterialTheme.typography.labelLarge,
                         fontWeight = FontWeight.SemiBold,
                         color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(6.dp))
                    recoveryActions.forEach { action ->
                        Surface(
                            onClick = { onRecovery(action) },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        ) {
                            Row(
                                Modifier.padding(8.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(action.title,
                                         style = MaterialTheme.typography.labelMedium,
                                         fontWeight = FontWeight.SemiBold)
                                    Text(action.description,
                                         style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
                if (showLogs) {
                    Spacer(Modifier.height(8.dp))
                    Text("最近日志（${event.recentLogs.size} 行）",
                         style = MaterialTheme.typography.labelLarge,
                         fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    ) {
                        Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                            event.recentLogs.forEach { line ->
                                Text(line, style = MaterialTheme.typography.labelSmall,
                                     fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            OutlinedButton(onClick = { showLogs = !showLogs }) {
                Text(if (showLogs) "隐藏日志" else "查看日志")
            }
        }
    )
}

@Composable
private fun MicrosoftLoginDialog(
    deviceCode: DeviceCode?,
    status: String,
    loggingIn: Boolean,
    context: android.content.Context,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!loggingIn) onDismiss() },
        title = { Text("微软登录") },
        text = {
            Column {
                if (deviceCode != null) {
                    Text("请在浏览器中输入以下代码完成登录：",
                         style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            deviceCode.userCode,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("验证地址: ${deviceCode.verificationUri}",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deviceCode.verificationUri))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try { context.startActivity(intent) } catch (_: Exception) {}
                    }) { Text("打开浏览器") }
                }
                if (status.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status,
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.primary)
                }
                if (loggingIn) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loggingIn) { Text("关闭") }
        }
    )
}

// ==================== 辅助函数 ====================

private fun formatRuntime(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

private fun inferModLoader(info: VersionManager.LocalVersionInfo): String? {
    val inherits = info.inheritsFrom ?: ""
    val main = info.mainClass ?: ""
    return when {
        inherits.contains("forge", ignoreCase = true) ||
            main.contains("launchwrapper", ignoreCase = true) -> "Forge"
        inherits.contains("neoforge", ignoreCase = true) -> "NeoForge"
        inherits.contains("fabric", ignoreCase = true) ||
            main.contains("fabricmc", ignoreCase = true) -> "Fabric"
        inherits.contains("quilt", ignoreCase = true) ||
            main.contains("quiltmc", ignoreCase = true) -> "Quilt"
        inherits.contains("liteloader", ignoreCase = true) -> "LiteLoader"
        else -> null
    }
}

private fun colorForLogLine(
    text: String,
    error: Color,
    warn: Color,
    info: Color,
    normal: Color,
): Color {
    val lower = text.lowercase()
    return when {
        lower.contains("/error]") || lower.contains("[error") ||
            lower.contains("exception") || lower.contains("caused by") -> error
        lower.contains("/warn]") || lower.contains("[warn") || lower.contains("warning") -> warn
        lower.contains("/info]") || lower.contains("[info") -> info.copy(alpha = 0.85f)
        else -> normal
    }
}

private fun stageText(stage: InstallProgress.Stage): String = when (stage) {
    InstallProgress.Stage.DOWNLOAD_VERSION_JSON -> "下载版本清单"
    InstallProgress.Stage.DOWNLOAD_CLIENT -> "下载客户端"
    InstallProgress.Stage.DOWNLOAD_LIBRARIES -> "下载依赖库"
    InstallProgress.Stage.DOWNLOAD_ASSET_INDEX -> "下载资产索引"
    InstallProgress.Stage.DOWNLOAD_ASSETS -> "下载资源文件"
    InstallProgress.Stage.DONE -> "安装完成"
    InstallProgress.Stage.FAILED -> "安装失败"
}
