package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.version.VersionManager
import com.pmcl.core.modloader.ModLoader
import com.pmcl.core.modloader.ModLoaderVersion
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.animation.pressScale
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

@Composable
fun LaunchPage(vm: LauncherViewModel) {
    val versions by vm.versions.collectAsState()
    val localInfos by vm.localVersionInfos.collectAsState()
    val pinned by vm.pinnedVersions.collectAsState()
    val pinnedLabels by vm.pinnedTileLabels.collectAsState()
    val recents by vm.recentVersions.collectAsState()
    val lastPlayedTimes by vm.lastPlayedTimes.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scanProgress by vm.scanProgress.collectAsState()
    val selected by vm.selectedVersion.collectAsState()
    val status by vm.status.collectAsState()
    val account by vm.account.collectAsState()
    val gameLogs by vm.gameLogs.collectAsState()
    val gameRunning by vm.gameRunning.collectAsState()
    val installing by vm.installing.collectAsState()
    val installProgress by vm.installProgress.collectAsState()
    val crashEvent by vm.crashEvent.collectAsState()
    val compatOptions by vm.compatOptions.collectAsState()
    val compatTitle by vm.compatTitle.collectAsState()
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    val formatRelative = remember { SimpleDateFormat("MM-dd HH:mm") }

    // 当前选中版本是否已安装（含 jar/json 才算可启动；仅有 json 也算"已安装"以便下载 client.jar）
    val isInstalled = remember(selected, localInfos) {
        selected != null && localInfos.any { it.getId() == selected }
    }

    // 磁贴操作对话框状态
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    // 扫描中刷新图标旋转动画
    val infiniteTransition = rememberInfiniteTransition(label = "scanRotate")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    LaunchedEffect(Unit) {
        if (versions.isEmpty()) vm.refreshVersions()
    }

    Row(Modifier.fillMaxSize()) {
        // ===== 左侧：统一用 LazyColumn 滚动，避免嵌套滚动冲突 =====
        LazyColumn(
            Modifier.weight(1.2f).fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // 标题栏
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启动", style = MaterialTheme.typography.headlineSmall,
                         fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { vm.refreshLocalVersions() },
                        enabled = !scanning
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            "扫描",
                            Modifier.size(16.dp).then(
                                if (scanning) Modifier.rotate(rotationAngle)
                                else Modifier
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (scanning) "扫描中…" else "扫描")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("已安装 ${localInfos.size} 个本地版本",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }

            // ===== 扫描进度/结果反馈 =====
            item {
                // 用 AnimatedVisibility 让进度条平滑进出
                AnimatedVisibility(visible = scanning) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        val p = scanProgress
                        val fraction = p?.getFraction() ?: 0f
                        val total = p?.getTotal() ?: 0
                        val scanned = p?.getScanned() ?: 0
                        val currentDir = p?.getCurrentDir() ?: ""
                        val currentVer = p?.getCurrentVersion() ?: ""

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (total > 0) "扫描中 $scanned/$total"
                                else "正在列出目录…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            if (total > 0) {
                                Text(
                                    "${(fraction * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
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
                                "当前: $currentDir/$currentVer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                        }
                    }
                }
                // 扫描完成后显示结果摘要（常驻直到下次扫描）
                AnimatedVisibility(visible = !scanning && localInfos.isEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("⚠ ", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "未扫描到本地版本。PMCL 会扫描 ~/.pmcl/versions 和系统默认 Minecraft 目录（Mac: ~/Library/Application Support/minecraft/versions）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // ===== 固定磁贴区 =====
            item {
                Spacer(Modifier.height(12.dp))
                Text("快速启动", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }

            if (pinned.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("点击下方版本的 ☆ 固定为磁贴，可实现一键启动",
                             modifier = Modifier.padding(12.dp),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                // 磁贴两列布局：手动 chunked，避免 LazyVerticalGrid 嵌套滚动
                val rows = pinned.chunked(2)
                rows.forEachIndexed { index, rowVersions ->
                    item(key = "pinned-row-$index") {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowVersions.forEach { versionId ->
                                val info = localInfos.find { it.getId() == versionId }
                                Box(Modifier.weight(1f)) {
                                    PinnedTile(
                                        versionId = versionId,
                                        customLabel = pinnedLabels[versionId],
                                        launchable = info?.isLaunchable() ?: false,
                                        gameRunning = gameRunning,
                                        hasAccount = account != null,
                                        modLoaderHint = info?.let { inferModLoader(it) },
                                        lastPlayedTime = lastPlayedTimes[versionId],
                                        formatRelative = formatRelative,
                                        onLaunch = { vm.quickLaunch(versionId) },
                                        onRename = { renameTarget = versionId },
                                        onDelete = { deleteTarget = versionId }
                                    )
                                }
                            }
                            // 奇数个时补占位保持两列对齐
                            if (rowVersions.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // ===== 最近使用（LRU，自动记录） =====
            // 与固定磁贴去重，避免同一版本出现两次
            val recentNotPinned = recents.filter { vid ->
                vid !in pinned && localInfos.any { it.getId() == vid }
            }
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
                        gameRunning = gameRunning,
                        hasAccount = account != null,
                        onClick = { vm.selectVersion(versionId) },
                        onLaunch = { vm.quickLaunch(versionId) }
                    )
                }
            }

            // ===== 分隔线 =====
            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("本地版本", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }

            // ===== 本地版本列表 =====
            if (localInfos.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text("尚未扫描到本地游戏",
                                 style = MaterialTheme.typography.titleSmall,
                                 fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "PMCL 会自动扫描以下目录：\n" +
                                "• ${vm.config.getVersionsDir()}\n" +
                                "• ~/.minecraft/versions（若存在）\n\n" +
                                "请将已有 Minecraft 版本目录放入上述位置，或前往「下载」页面安装新版本后点击「扫描」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            } else {
                itemsIndexed(localInfos, key = { _, info -> info.getId() }) { index, info ->
                    StaggeredAppear(index) {
                        LocalVersionRow(
                            info = info,
                            selected = info.getId() == selected,
                            pinned = pinned.contains(info.getId()),
                            format = format,
                            onClick = { vm.selectVersion(info.getId()) },
                            onPin = { vm.pinVersion(info.getId()) },
                            onUnpin = { vm.unpinVersion(info.getId()) }
                        )
                    }
                }
            }

            // ===== 分隔线 =====
            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("远程版本（${versions.size}）",
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
            }

            // ===== 版本分类筛选 + 搜索 =====
            item {
                var versionCategory by remember { mutableStateOf(1) }
                var searchQuery by remember { mutableStateOf("") }

                Column {
                    // 分类滑动选择器
                    val categories = listOf("全部", "正式版", "快照", "旧版Beta", "旧版Alpha")
                    com.pmcl.ui.animation.AnimatedSegmentedSelector(
                        items = categories,
                        selectedIndex = versionCategory,
                        onSelect = { versionCategory = it },
                        fillWidth = true,
                        height = 32.dp
                    )
                    Spacer(Modifier.height(6.dp))
                    // 搜索框
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("搜索版本号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    // 过滤逻辑
                    val filtered = remember(versions, versionCategory, searchQuery) {
                        var list = versions
                        if (versionCategory != 0) {
                            val typeFilter = when (versionCategory) {
                                1 -> "release"
                                2 -> "snapshot"
                                3 -> "old_beta"
                                else -> "old_alpha"
                            }
                            list = list.filter { it.getType() == typeFilter }
                        }
                        if (searchQuery.isNotEmpty()) {
                            list = list.filter { it.getId()?.contains(searchQuery, ignoreCase = true) == true }
                        }
                        list.take(200) // 最多显示 200 个，避免列表过长
                    }
                    Text("显示 ${filtered.size} 个版本",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(6.dp))
                    filtered.forEach { v ->
                        RemoteVersionRow(
                            id = v.getId(),
                            type = v.getType(),
                            selected = v.getId() == selected,
                            installed = localInfos.any { it.getId() == v.getId() },
                            onClick = { vm.selectVersion(v.getId()) }
                        )
                    }
                }
            }
        }

        VerticalDivider()

        // ===== 右侧：账号 + 启动 + 日志 =====
        Column(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
            Text("PMCL", style = MaterialTheme.typography.headlineMedium,
                 fontWeight = FontWeight.Bold, fontFamily = FontFamily.SansSerif)
            Spacer(Modifier.height(12.dp))

            AccountCard(account, vm)

            Spacer(Modifier.height(16.dp))

            // 当前选中版本提示
            selected?.let {
                val hintColor = if (isInstalled) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.tertiaryContainer
                Surface(
                    color = hintColor,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("选中：$it",
                             style = MaterialTheme.typography.bodyMedium,
                             fontWeight = FontWeight.SemiBold,
                             modifier = Modifier.weight(1f))
                        Surface(
                            color = if (isInstalled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                if (isInstalled) "已安装" else "未安装",
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 每版本独立 Java 选择（仅在选中版本时显示）
            selected?.let { verId ->
                val pref = remember { vm.preferences }
                var versionJava by remember(verId) { mutableStateOf(pref.getVersionJavaPath(verId)) }
                var javaExpanded by remember(verId) { mutableStateOf(false) }
                val hasVersionJava = versionJava.isNotEmpty()

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().clickable { javaExpanded = !javaExpanded }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Code, null, Modifier.size(18.dp),
                                tint = if (hasVersionJava) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(I18n.t("launch.version_java"),
                                     style = MaterialTheme.typography.labelLarge,
                                     fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (hasVersionJava) versionJava
                                    else I18n.t("launch.version_java_auto"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1
                                )
                            }
                            Icon(
                                if (javaExpanded) Icons.Filled.KeyboardArrowUp
                                else Icons.Filled.KeyboardArrowDown,
                                null, Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        }
                        AnimatedVisibility(visible = javaExpanded) {
                            Column {
                                Spacer(Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = versionJava,
                                    onValueChange = {
                                        versionJava = it
                                        vm.setVersionJavaPath(verId, it)
                                    },
                                    label = { Text(I18n.t("launch.version_java_path")) },
                                    singleLine = true,
                                    placeholder = { Text(I18n.t("launch.version_java_empty")) },
                                    modifier = Modifier.fillMaxWidth(),
                                    trailingIcon = {
                                        Row {
                                            IconButton(onClick = {
                                                val fd = java.awt.FileDialog(
                                                    null as java.awt.Frame?,
                                                    I18n.t("launch.version_java_select"),
                                                    java.awt.FileDialog.LOAD
                                                )
                                                fd.isVisible = true
                                                if (fd.file != null) {
                                                    val p = java.io.File(fd.directory, fd.file).absolutePath
                                                    versionJava = p
                                                    vm.setVersionJavaPath(verId, p)
                                                }
                                            }) {
                                                Icon(Icons.Filled.FolderOpen,
                                                     contentDescription = I18n.t("common.browse"))
                                            }
                                            if (versionJava.isNotEmpty()) {
                                                IconButton(onClick = {
                                                    versionJava = ""
                                                    vm.setVersionJavaPath(verId, "")
                                                }) {
                                                    Icon(Icons.Filled.Clear,
                                                         contentDescription = I18n.t("common.remove"))
                                                }
                                            }
                                        }
                                    }
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(I18n.t("launch.version_java_hint"),
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // 服务器直连快捷入口
            val pref = remember { vm.preferences }
            var serverHost by remember { mutableStateOf(pref.getGameServerHost()) }
            var serverPort by remember { mutableStateOf(pref.getGameServerPort().toString()) }
            var serverExpanded by remember { mutableStateOf(false) }
            val serverEnabled = serverHost.isNotEmpty()

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().clickable { serverExpanded = !serverExpanded }
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Dns, null, Modifier.size(18.dp),
                            tint = if (serverEnabled) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(I18n.t("launch.server_connect"),
                                 style = MaterialTheme.typography.labelLarge,
                                 fontWeight = FontWeight.SemiBold)
                            Text(
                                if (serverEnabled) "$serverHost:$serverPort"
                                else I18n.t("launch.server_empty_hint"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                        }
                        Icon(
                            if (serverExpanded) Icons.Filled.KeyboardArrowUp
                            else Icons.Filled.KeyboardArrowDown,
                            null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                    AnimatedVisibility(visible = serverExpanded) {
                        Column {
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedTextField(
                                    value = serverHost,
                                    onValueChange = {
                                        serverHost = it
                                        pref.setGameServerHost(it)
                                    },
                                    label = { Text(I18n.t("launch.server_address")) },
                                    singleLine = true,
                                    placeholder = { Text(I18n.t("launch.server_leave_empty")) },
                                    modifier = Modifier.weight(2f)
                                )
                                OutlinedTextField(
                                    value = serverPort,
                                    onValueChange = {
                                        serverPort = it
                                        it.toIntOrNull()?.let { v -> pref.setGameServerPort(v) }
                                    },
                                    label = { Text(I18n.t("launch.server_port")) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(I18n.t("launch.server_hint"),
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 启动 / 下载按钮：根据是否安装切换
            val canInstall = selected != null && !installing
            val canLaunch = selected != null && isInstalled && account != null && !installing
            val buttonEnabled = canLaunch || canInstall
            val isDownloadMode = selected != null && !isInstalled
            val runningInstances by vm.runningInstances.collectAsState()

            Button(
                onClick = {
                    if (isDownloadMode) {
                        selected?.let { vm.installVersion(it) }
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

            // 下载进度条
            if (installing && isDownloadMode) {
                installProgress?.let { p ->
                    val fraction = if (p.getTotal() > 0) (p.getCompleted().toFloat() / p.getTotal()).coerceIn(0f, 1f) else 0f
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${p.getStage()} · ${p.getMessage()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.weight(1f),
                            maxLines = 1
                        )
                        if (p.getTotal() > 0) {
                            Text("${(fraction * 100).toInt()}%",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.tertiary,
                                 fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (p.getTotal() > 0) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                }
            } else if (isDownloadMode && !installing && selected != null) {
                Spacer(Modifier.height(6.dp))
                Text("该版本尚未安装，点击按钮即可一键下载",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(8.dp))
            Text("状态：$status",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            // 运行中实例列表（多实例启动支持）
            if (runningInstances.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(I18n.t("launch.running_instances"),
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
                            val isActive = inst.active
                            val runtimeMs = System.currentTimeMillis() - inst.startTime
                            val runtimeStr = formatRuntime(runtimeMs)
                            Surface(
                                color = if (isActive) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    .clickable { vm.selectInstance(inst.id) }
                            ) {
                                Row(
                                    Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.PlayArrow, null, Modifier.size(14.dp),
                                        tint = if (isActive) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(inst.versionId,
                                             style = MaterialTheme.typography.bodySmall,
                                             fontWeight = FontWeight.SemiBold,
                                             maxLines = 1)
                                        Text("${inst.accountName} · $runtimeStr",
                                             style = MaterialTheme.typography.labelSmall,
                                             color = MaterialTheme.colorScheme.outline,
                                             maxLines = 1)
                                    }
                                    if (isActive) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(3.dp)
                                        ) {
                                            Text(I18n.t("launch.active"),
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

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            // 日志标题 + 操作按钮（复制 / 导出 / 分享）
            val logSharing by vm.logSharing.collectAsState()
            val shareUrl by vm.shareUrl.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("游戏日志", style = MaterialTheme.typography.labelLarge,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                var copied by remember { mutableStateOf(false) }
                TextButton(
                    onClick = {
                        val text = gameLogs.joinToString("\n")
                        try {
                            val toolkit = java.awt.Toolkit.getDefaultToolkit()
                            val clipboard = toolkit.systemClipboard
                            clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
                            copied = true
                        } catch (_: Throwable) {}
                    },
                    enabled = gameLogs.isNotEmpty(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp
                    )
                ) {
                    if (copied) {
                        Icon(Icons.Filled.Check, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("已复制", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text("复制日志", style = MaterialTheme.typography.labelSmall)
                    }
                }
                // 1.5 秒后重置 copied 状态
                LaunchedEffect(copied) {
                    if (copied) {
                        kotlinx.coroutines.delay(1500)
                        copied = false
                    }
                }

                // 导出日志到文件
                var showExportDialog by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { showExportDialog = true },
                    enabled = gameLogs.isNotEmpty(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp
                    )
                ) {
                    Icon(Icons.Filled.IosShare, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("log.export"), style = MaterialTheme.typography.labelSmall)
                }
                if (showExportDialog) {
                    val exportState = remember { mutableStateOf<Boolean?>(null) }
                    AlertDialog(
                        onDismissRequest = {
                            if (exportState.value != null) showExportDialog = false
                        },
                        title = { Text(I18n.t("log.export_title")) },
                        text = {
                            Column {
                                Text(I18n.t("log.export_hint"),
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(8.dp))
                                Row {
                                    OutlinedButton(onClick = {
                                        val fd = java.awt.FileDialog(
                                            null as java.awt.Frame?,
                                            I18n.t("log.export_save"),
                                            java.awt.FileDialog.SAVE
                                        )
                                        fd.file = "pmcl-log-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(java.util.Date())}.txt"
                                        fd.isVisible = true
                                        if (fd.file != null) {
                                            val p = java.io.File(fd.directory, fd.file).absolutePath
                                            kotlinx.coroutines.MainScope().launch {
                                                exportState.value = vm.exportLogs(p)
                                                if (exportState.value == true) showExportDialog = false
                                            }
                                        } else {
                                            showExportDialog = false
                                        }
                                    }) { Text(I18n.t("log.choose_file")) }
                                    Spacer(Modifier.width(8.dp))
                                    if (exportState.value == false) {
                                        Text(I18n.t("log.export_failed"),
                                             color = MaterialTheme.colorScheme.error,
                                             style = MaterialTheme.typography.labelSmall,
                                             modifier = Modifier.align(Alignment.CenterVertically))
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = {
                            TextButton(onClick = { showExportDialog = false }) {
                                Text(I18n.t("common.cancel"))
                            }
                        }
                    )
                }

                // 分享到 pastebin
                TextButton(
                    onClick = { vm.shareLogs() },
                    enabled = gameLogs.isNotEmpty() && !logSharing,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp
                    )
                ) {
                    if (logSharing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp
                        )
                        Spacer(Modifier.width(4.dp))
                    } else {
                        Icon(Icons.Filled.CloudUpload, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(I18n.t("log.share"), style = MaterialTheme.typography.labelSmall)
                }
            }
            // 分享成功后弹出 URL 对话框
            shareUrl?.let { url ->
                AlertDialog(
                    onDismissRequest = { vm.clearShareUrl() },
                    title = { Text(I18n.t("log.share_success")) },
                    text = {
                        Column {
                            Text(I18n.t("log.share_url_hint"),
                                 style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    url,
                                    modifier = Modifier.padding(8.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            try {
                                val toolkit = java.awt.Toolkit.getDefaultToolkit()
                                val clipboard = toolkit.systemClipboard
                                clipboard.setContents(java.awt.datatransfer.StringSelection(url), null)
                            } catch (_: Throwable) {}
                            vm.clearShareUrl()
                        }) { Text(I18n.t("common.copy")) }
                    },
                    dismissButton = {
                        Row {
                            OutlinedButton(onClick = {
                                try { com.pmcl.core.web.WikiBrowser.open(url) } catch (_: Throwable) {}
                            }) { Text(I18n.t("common.open")) }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { vm.clearShareUrl() }) {
                                Text(I18n.t("common.close"))
                            }
                        }
                    }
                )
            }
            Spacer(Modifier.height(4.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                if (gameLogs.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("（暂无日志）", color = MaterialTheme.colorScheme.outline)
                    }
                } else {
                    LazyColumn(Modifier.padding(8.dp)) {
                        itemsIndexed(gameLogs.takeLast(500), key = { index, _ -> "log-$index" }) { _, line ->
                            Text(line, style = MaterialTheme.typography.bodySmall,
                                 fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }

    // ===== 磁贴重命名对话框 =====
    renameTarget?.let { targetId ->
        val currentLabel = pinnedLabels[targetId] ?: targetId
        RenameTileDialog(
            versionId = targetId,
            initialText = currentLabel,
            onConfirm = { newName ->
                vm.renamePinnedTile(targetId, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }

    // ===== 磁贴删除确认对话框 =====
    deleteTarget?.let { targetId ->
        val displayName = pinnedLabels[targetId] ?: targetId
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除磁贴") },
            text = {
                Text("确定要删除磁贴「$displayName」吗？\n\n" +
                     "这只会移除快速启动磁贴，不会卸载游戏版本本身。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.unpinVersion(targetId)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteTarget = null }) {
                    Text("取消")
                }
            }
        )
    }

    // ===== 游戏崩溃报错窗口 =====
    crashEvent?.let { ev ->
        CrashReportDialog(
            event = ev,
            onDismiss = { vm.clearCrashEvent() }
        )
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
                    Text("取消")
                }
            }
        )
    }
}

/**
 * 磁贴重命名对话框：允许用户为磁贴设置自定义显示名。
 * 清空文本则恢复为版本 ID。
 */
@Composable
private fun RenameTileDialog(
    versionId: String,
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名磁贴") },
        text = {
            Column {
                Text("为「$versionId」设置自定义显示名：",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(versionId) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text("留空则恢复为版本 ID",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
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

/**
 * 游戏崩溃报错窗口：展示退出码、崩溃原因、修复建议。
 * 支持查看最近日志片段，复制崩溃信息到剪贴板。
 */
@Composable
private fun CrashReportDialog(
    event: LauncherViewModel.CrashEvent,
    onDismiss: () -> Unit
) {
    var showLogs by remember { mutableStateOf(false) }
    val report = event.report
    val causes = report?.getCauses() ?: listOf("进程异常退出（exitCode=${event.exitCode}）")
    val suggestions = report?.getSuggestions() ?: listOf("查看日志末尾寻找异常堆栈")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("游戏异常退出", color = MaterialTheme.colorScheme.error)
            }
        },
        text = {
            Column {
                Text("版本：${event.versionId}  退出码：${event.exitCode}",
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
                if (showLogs) {
                    Spacer(Modifier.height(8.dp))
                    Text("最近日志（末尾 ${event.recentLogs.size} 行）",
                         style = MaterialTheme.typography.labelLarge,
                         fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp)
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

/**
 * 游戏安装前的模组加载器选择对话框。
 *
 * 流程：
 * 1. 用户点击安装游戏时触发此弹窗（安装尚未开始）
 * 2. 用户选择加载器类型（Fabric/Forge/Quilt/NeoForge），可不选
 * 3. 自动加载该加载器的可用版本列表
 * 4. 用户选择具体版本后点击"开始安装"，游戏安装完成后自动继续安装加载器
 * 5. 点击"仅安装原版"则跳过加载器选择，直接安装原版游戏
 *
 * 点击"取消"或对话框外区域则关闭弹窗，不执行任何安装。
 * 此对话框为 public，由 App.kt 全局监听 preInstallEvent 统一弹出，
 * 避免多页面重复弹窗。
 */
@Composable
fun ModLoaderInstallPromptDialog(
    versionId: String,
    vm: LauncherViewModel,
    onDismiss: () -> Unit
) {
    // 从 versionId 提取游戏版本（如 "1.20.4" 或 "1.20.4-forge" → "1.20.4"）
    // versionId 可能是 "1.20.4" 或 "b1.7.3" 等格式，取原始值作为 gameVersion
    val gameVersion = remember(versionId) {
        // 去除可能的 loader 后缀（如 "1.20.4-fabric" → "1.20.4"）
        val dashIdx = versionId.indexOf('-')
        if (dashIdx > 0) versionId.substring(0, dashIdx) else versionId
    }

    val modLoaderVersions by vm.modLoaderVersions.collectAsState()
    val installing by vm.installing.collectAsState()

    // 当前选中的加载器类型（null 表示尚未选择，将仅安装原版）
    var selectedLoader by remember { mutableStateOf<ModLoader?>(null) }
    // 当前选中的加载器版本
    var selectedLoaderVersion by remember { mutableStateOf<String?>(null) }

    // 选择加载器时自动拉取版本列表
    LaunchedEffect(selectedLoader) {
        val loader = selectedLoader
        if (loader != null) {
            selectedLoaderVersion = null
            vm.listModLoaderVersions(loader, gameVersion)
        }
    }

    AlertDialog(
        onDismissRequest = {
            // 安装进行中不允许外部点击关闭
            if (!installing) onDismiss()
        },
        title = {
            Text(
                selectedLoader?.let { "选择 ${it.name} 版本" }
                    ?: "安装 Minecraft - 是否安装模组加载器？"
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "即将安装 Minecraft $gameVersion。\n" +
                    "可选择同时安装模组加载器（安装游戏后自动继续），或跳过仅安装原版。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // 加载器类型选择（流体滑动指示器）
                val loaderOptions = listOf(
                    ModLoader.FABRIC to "Fabric",
                    ModLoader.FORGE to "Forge",
                    ModLoader.QUILT to "Quilt",
                    ModLoader.NEOFORGE to "NeoForge",
                    ModLoader.OPTIFINE to "OptiFine"
                )
                val loaderLabels = loaderOptions.map { it.second }
                val loaderIndex = loaderOptions.indexOfFirst { it.first == selectedLoader }.coerceAtLeast(0)
                com.pmcl.ui.animation.AnimatedSegmentedSelector(
                    items = loaderLabels,
                    selectedIndex = loaderIndex,
                    onSelect = { i ->
                        if (!installing) selectedLoader = loaderOptions[i].first
                    },
                    fillWidth = true,
                    height = 34.dp
                )

                // 版本列表（选择加载器后显示）
                if (selectedLoader != null) {
                    if (modLoaderVersions.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        Text(
                            "可用版本（${modLoaderVersions.size}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                        // 版本列表（限制高度，可滚动）
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(modLoaderVersions.take(20), key = { it.getLoaderVersion() }) { lv ->
                                val isSelected = selectedLoaderVersion == lv.getLoaderVersion()
                                // OptiFine 版本编码为 "type|patch[|forge]"，显示时解码
                                val displayVersion = remember(lv) {
                                    val raw = lv.getLoaderVersion()
                                    if (lv.getLoader() == ModLoader.OPTIFINE) {
                                        val parts = raw.split("|")
                                        if (parts.size >= 2) {
                                            val type = parts[0]
                                            val patch = parts[1]
                                            val forge = parts.size >= 3 && parts[2] == "forge"
                                            (if (forge) "[Forge] " else "") + type + " " + patch
                                        } else raw
                                    } else raw
                                }
                                Surface(
                                    onClick = {
                                        if (!installing) selectedLoaderVersion = lv.getLoaderVersion()
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                displayVersion,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (isSelected) FontWeight.Bold
                                                             else FontWeight.Normal
                                            )
                                            Text(
                                                "MC ${lv.getGameVersion()} · " +
                                                if (lv.isStable()) "稳定版" else "不稳定",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "已选",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!installing) {
                        val loader = selectedLoader
                        val lv = selectedLoaderVersion
                        if (loader != null && lv != null) {
                            // 游戏安装完成后自动继续安装加载器
                            vm.proceedInstall(versionId, loader, lv)
                        } else {
                            // 仅安装原版游戏
                            vm.proceedInstall(versionId, null, null)
                        }
                        onDismiss()
                    }
                },
                // 选中加载器+版本、或未选加载器时均可点击；仅选了加载器但没选版本时禁用
                enabled = !installing && (selectedLoader == null ||
                        (selectedLoader != null && selectedLoaderVersion != null))
            ) {
                Text(if (installing) "安装中…" else "开始安装")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !installing) {
                Text("取消")
            }
        }
    )
}

/**
 * 固定磁贴：渐变背景，点击直接启动。按下时有缩放反馈。
 *
 * 启用条件：版本可启动 + 账号已登录 + 游戏未运行。
 * 信息层级：
 * - 顶行：自定义名/版本 ID + modloader 标签（如有）+ 操作菜单（⋯）
 * - 底行：状态文案 / 上次游玩时间
 *
 * 通过 [onRename] / [onDelete] 回调触发上层对话框，菜单项包含「重命名」「删除磁贴」。
 */
@Composable
private fun PinnedTile(
    versionId: String,
    customLabel: String?,
    launchable: Boolean,
    gameRunning: Boolean,
    hasAccount: Boolean,
    modLoaderHint: String?,
    lastPlayedTime: Long?,
    formatRelative: SimpleDateFormat,
    onLaunch: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val enabled = launchable && hasAccount
    var menuExpanded by remember { mutableStateOf(false) }

    // 显示名：优先自定义名称，否则回退到版本 ID
    val displayName = customLabel?.takeIf { it.isNotEmpty() } ?: versionId

    // 不可启动时灰度渐变
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
            .pressScale(pressed && enabled)  // 按下缩放反馈
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(),
                enabled = enabled
            ) { onLaunch() }
    ) {
        Column(Modifier.padding(12.dp).fillMaxSize()) {
            // 顶行：显示名 + modloader 标签 + 操作菜单
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(displayName,
                         color = Color.White,
                         fontWeight = FontWeight.Bold,
                         fontSize = 15.sp,
                         maxLines = 1)
                    // 若有自定义名称，则在下方用小字显示真实版本 ID
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
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(modLoaderHint,
                             color = Color.White,
                             fontSize = 10.sp,
                             fontWeight = FontWeight.Medium,
                             modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // 操作菜单触发按钮（⋯）
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Text("⋯",
                             color = Color.White.copy(alpha = 0.9f),
                             fontSize = 16.sp,
                             fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
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
            // 底行：状态文案 / 上次游玩时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null,
                     tint = Color.White, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(4.dp))
                val stateText = when {
                    !launchable -> "版本已失效（缺 JSON）"
                    !hasAccount -> "请先登录账号"
                    else -> "点击启动"
                }
                Text(stateText,
                     color = Color.White.copy(alpha = 0.95f),
                     fontSize = 11.sp,
                     modifier = Modifier.weight(1f))
                // 右下角显示上次游玩时间
                if (lastPlayedTime != null && lastPlayedTime > 0) {
                    Text("上次 ${formatRelative.format(Date(lastPlayedTime))}",
                         color = Color.White.copy(alpha = 0.8f),
                         fontSize = 10.sp,
                         maxLines = 1)
                }
            }
        }
    }
}

/**
 * 最近使用行：比磁贴更紧凑，单行显示版本 ID + 上次游玩时间 + 快速启动按钮。
 * 与磁贴去重后展示，避免重复。
 */
@Composable
private fun RecentVersionRow(
    versionId: String,
    lastPlayedTime: Long?,
    formatRelative: SimpleDateFormat,
    gameRunning: Boolean,
    hasAccount: Boolean,
    onClick: () -> Unit,
    onLaunch: () -> Unit
) {
    val canLaunch = hasAccount
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
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
                    Text("上次游玩 ${formatRelative.format(Date(lastPlayedTime))}",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
            // 右侧快速启动按钮
            IconButton(onClick = onLaunch, enabled = canLaunch) {
                Icon(Icons.Filled.PlayArrow, "启动",
                     tint = if (canLaunch) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                     modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * 格式化运行时长（毫秒 → "1h 23m" / "5m 12s" / "42s"）
 */
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

/**
 * 从 LocalVersionInfo 推断 modloader 类型，用于磁贴标签显示。
 * 返回 null 表示原版。
 */
private fun inferModLoader(info: VersionManager.LocalVersionInfo): String? {
    val inherits = info.getInheritsFrom() ?: ""
    val main = info.getMainClass() ?: ""
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

/**
 * 本地版本行：含修改时间、jar/json 状态、固定按钮
 */
@Composable
private fun LocalVersionRow(
    info: VersionManager.LocalVersionInfo,
    selected: Boolean,
    pinned: Boolean,
    format: SimpleDateFormat,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit
) {
    val bg by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        label = "rowBg"
    )
    Surface(onClick = onClick, color = bg, shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.getId(),
                         style = MaterialTheme.typography.bodyLarge,
                         fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
                    if (info.getInheritsFrom() != null) {
                        Spacer(Modifier.width(6.dp))
                        // 继承标签：纯展示，不可点击
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("继承 ${info.getInheritsFrom()}",
                                 style = MaterialTheme.typography.labelSmall,
                                 modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (info.isHasJar()) "✓jar" else "✗jar"}  ${if (info.isHasJson()) "✓json" else "✗json"}",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    val mainClass = info.getMainClass()
                    if (mainClass != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(mainClass.substringAfterLast('.'),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (info.getLastModified() > 0) {
                        Text(format.format(Date(info.getLastModified())),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            // 固定按钮
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
    onClick: () -> Unit
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
                    shape = RoundedCornerShape(4.dp)
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
private fun AccountCard(account: com.pmcl.core.auth.Account?, vm: LauncherViewModel) {
    var username by remember { mutableStateOf("Steve") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("账号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            if (account != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 头像
                    val avatarUrl = account.getAvatarUrl() ?: ""
                    if (avatarUrl.isNotEmpty()) {
                        AvatarImage(avatarUrl)
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Filled.Star, "默认头像", modifier = Modifier.padding(10.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(account.getUsername(), fontWeight = FontWeight.SemiBold)
                        Text((account.getType()?.toString()) ?: "",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                Text("未登录", color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text("离线用户名") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.loginOffline(username) }, enabled = username.isNotBlank()) {
                    Text("离线登录")
                }
                OutlinedButton(onClick = vm::startMicrosoftLogin) {
                    Text("微软登录")
                }
            }
        }
    }
}

/** 启动页账号头像（40dp，带内存缓存） */
private val launchAvatarCache = ConcurrentHashMap<String, ImageBitmap?>()

@Composable
private fun AvatarImage(url: String) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(launchAvatarCache[url]) }
    LaunchedEffect(url) {
        if (url.isEmpty()) { image = null; return@LaunchedEffect }
        if (launchAvatarCache.containsKey(url)) { image = launchAvatarCache[url]; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                if (url.isNullOrBlank()) return@withContext
                val bytes = URL(url).readBytes()
                val bmp = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                launchAvatarCache[url] = bmp
                if (launchAvatarCache.size > 50) launchAvatarCache.clear()
                image = bmp
            } catch (_: Throwable) {
                launchAvatarCache[url] = null
                image = null
            }
        }
    }
    val bmp = image
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "头像",
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Filled.Star, "加载中", modifier = Modifier.padding(10.dp))
        }
    }
}
