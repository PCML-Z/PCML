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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
import com.pmcl.core.instance.InstanceInfo
import com.pmcl.core.version.VersionManager
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.StaggeredAppear
import com.pmcl.ui.animation.pressScale
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.cancelPreheat
import com.pmcl.ui.viewmodel.clearGameLogs
import com.pmcl.ui.viewmodel.launch
import com.pmcl.ui.viewmodel.openGameLogFolder
import com.pmcl.ui.viewmodel.predictAndPreheat
import com.pmcl.ui.viewmodel.lastOfflineUsername
import com.pmcl.ui.viewmodel.loginOffline
import com.pmcl.ui.viewmodel.selectInstance
import com.pmcl.ui.viewmodel.startMicrosoftLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import com.pmcl.ui.util.decodeSampledBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

@Composable
fun LaunchPage(vm: LauncherViewModel) {
    val versions by vm.versions.collectAsState()
    val localInfos by vm.localVersionInfos.collectAsState()
    val instances by vm.instances.collectAsState()
    val instanceLaunching by vm.instanceLaunching.collectAsState()
    val pinned by vm.pinnedVersions.collectAsState()
    val pinnedLabels by vm.pinnedTileLabels.collectAsState()
    val recents by vm.recentVersions.collectAsState()
    val lastPlayedTimes by vm.lastPlayedTimes.collectAsState()
    val scanning by vm.scanning.collectAsState()
    // scanProgress / status 是高频流（扫描期间每版本更新 / 各处当作日志输出）
    // 不在顶层订阅，避免整页 LaunchPage 跟着高频重组；下沉到使用它们的子 Composable
    val selected by vm.selectedVersion.collectAsState()
    val account by vm.account.collectAsState()
    val gameRunning by vm.gameRunning.collectAsState()
    val installing by vm.installing.collectAsState()
    val installProgress by vm.installProgress.collectAsState()
    val crashEvent by vm.crashEvent.collectAsState()
    val compatOptions by vm.compatOptions.collectAsState()
    val compatTitle by vm.compatTitle.collectAsState()
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    val formatRelative = remember { SimpleDateFormat("MM-dd HH:mm") }

    // 预计算本地版本 ID 集合，避免在多处重复 O(n) 线性查找
    val localInfoIds = remember(localInfos) { localInfos.mapNotNull { it.getId() }.toHashSet() }
    // 预计算固定磁贴 ID 集合，供列表项 O(1) 查询是否已固定
    val pinnedIds = remember(pinned) { pinned.toHashSet() }

    // 预计算最近使用（与固定磁贴去重），避免在 LazyListScope 中重复过滤
    val recentNotPinned = remember(recents, pinnedIds, localInfoIds) {
        recents.filter { vid -> vid !in pinnedIds && vid in localInfoIds }
    }

    // 当前选中版本是否已安装（含 jar/json 才算可启动；仅有 json 也算"已安装"以便下载 client.jar）
    // 使用 HashSet 查找将 O(n) 降为 O(1)，remember 缓存避免重复计算
    val isInstalled = remember(selected, localInfoIds) {
        selected != null && selected in localInfoIds
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
        vm.loadInstances()
        // 进入启动页时触发预判启动（若用户在设置中开启了该功能）
        vm.predictAndPreheat()
    }

    // 离开启动页时取消未采用的预热资源，清空预存 profile
    DisposableEffect(Unit) {
        onDispose {
            vm.cancelPreheat()
        }
    }

    // 插件主页卡片（随 plugin revision 刷新）
    var pluginRev by remember { mutableStateOf(0L) }
    LaunchedEffect("plugin-home-cards") {
        while (true) {
            kotlinx.coroutines.delay(1000)
            pluginRev = try { vm.core.plugins().revision } catch (_: Throwable) { 0L }
        }
    }
    val homeCards = remember(pluginRev) {
        try { vm.core.plugins().customHomeCards } catch (_: Throwable) { emptyList() }
    }

    // 远程版本分类筛选状态（提到 LazyColumn 外，供 items() 引用 filtered 列表）
    var versionCategory by remember { mutableStateOf(1) }
    var searchQuery by remember { mutableStateOf("") }
    // M41 修复：searchQuery 每次按键触发 remember 重新计算全量过滤，
    // 列表大时（数百版本）会卡顿。用 debounced 缓存上次输入，仅在停止输入后过滤。
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        kotlinx.coroutines.delay(250)  // 250ms debounce
        debouncedQuery = searchQuery
    }
    val filtered = remember(versions, versionCategory, debouncedQuery) {
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
        if (debouncedQuery.isNotEmpty()) {
            list = list.filter { it.getId()?.contains(debouncedQuery, ignoreCase = true) == true }
        }
        list.take(200) // 最多显示 200 个，避免列表过长
    }

    var launchTab by remember { mutableStateOf(0) } // 0=启动 1=版本 2=账号 3=日志
    val useSegmentedLayout by remember { mutableStateOf(vm.preferences.isUseSegmentedLaunchLayout()) }

    // 右侧详情面板内容提取为可复用 lambda，供分栏布局和最初分栏布局共用
    val rightDetailContent: @Composable () -> Unit = {
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
                        Text(I18n.t("launch.selected_prefix", it),
                             style = MaterialTheme.typography.bodyMedium,
                             fontWeight = FontWeight.SemiBold,
                             modifier = Modifier.weight(1f))
                        Surface(
                            color = if (isInstalled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                if (isInstalled) I18n.t("launch.installed") else I18n.t("launch.not_installed"),
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
                    color = glassSurfaceVariantColor(glassAlpha = 0.4f),
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
                color = glassSurfaceVariantColor(glassAlpha = 0.4f),
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
                Text(I18n.t("launch.not_installed_hint"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(8.dp))
            // status 是高频流（各处当作日志输出），单独订阅避免整页重组
            StatusLine(vm)

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
    }

    // 版本列表内容提取为可复用 lambda，供滑块布局和分栏布局共用
    val versionListContent: LazyListScope.() -> Unit = {
            // 标题栏
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(I18n.t("launch.start"), style = MaterialTheme.typography.headlineSmall,
                         fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = { vm.refreshLocalVersions() },
                        enabled = !scanning
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            I18n.t("launch.scan"),
                            Modifier.size(16.dp).then(
                                if (scanning) Modifier.rotate(rotationAngle)
                                else Modifier
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (scanning) I18n.t("launch.scanning") else I18n.t("launch.scan"))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("launch.installed_count", localInfos.size + instances.size),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }

            // ===== 插件主页卡片 =====
            if (homeCards.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (card in homeCards) {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                tonalElevation = 1.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(card.title, style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold)
                                    if (card.subtitle.isNotBlank()) {
                                        Text(
                                            card.subtitle,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    // Compose 不允许 try/catch 包裹 @Composable；异常由宿主 SafePluginPage 同类策略承接
                                    card.content.invoke()
                                }
                            }
                        }
                    }
                }
            }

            // ===== 扫描进度/结果反馈 =====
            item {
                // 用 AnimatedVisibility 让进度条平滑进出
                // scanProgress 在 ScanProgressBar 内部订阅，避免高频更新触发整页重组
                AnimatedVisibility(visible = scanning) {
                    ScanProgressBar(vm)
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
                            Icon(Icons.Filled.Warning, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                I18n.t("launch.no_local_hint"),
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
                Text(I18n.t("launch.quick_launch"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }

            if (pinned.isEmpty()) {
                item {
                    Surface(
                        color = glassSurfaceVariantColor(),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(I18n.t("launch.pinned_empty_hint"),
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
            // recentNotPinned 已在顶层用 remember 预计算
            if (recentNotPinned.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Refresh, I18n.t("launch.recent"),
                             modifier = Modifier.size(16.dp),
                             tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text(I18n.t("launch.recent"), style = MaterialTheme.typography.titleSmall,
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
                Text(I18n.t("launch.local_versions"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }

            // ===== 本地版本列表 =====
            if (instances.isEmpty() && localInfos.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(I18n.t("launch.local_empty_title"),
                                 style = MaterialTheme.typography.titleSmall,
                                 fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                I18n.t("launch.local_empty_hint", vm.config.getVersionsDir()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            } else {
                items(instances, key = { "instance-${it.getInstanceId()}" }) { info ->
                    LaunchInstanceRow(
                        info = info,
                        launching = instanceLaunching == info.getInstanceId(),
                        enabled = account != null && !gameRunning && instanceLaunching == null,
                        onLaunch = { vm.launchInstance(info.getInstanceId()) }
                    )
                }
                itemsIndexed(localInfos, key = { _, info -> info.getId() }) { index, info ->
                    StaggeredAppear(index) {
                        LocalVersionRow(
                            info = info,
                            selected = info.getId() == selected,
                            pinned = info.getId() in pinnedIds,
                            format = format,
                            gameRunning = gameRunning,
                            hasAccount = account != null,
                            onClick = { vm.selectVersion(info.getId()) },
                            onPin = { vm.pinVersion(info.getId()) },
                            onUnpin = { vm.unpinVersion(info.getId()) },
                            onLaunch = { vm.quickLaunch(info.getId()) }
                        )
                    }
                }
            }

            // ===== 分隔线 =====
            item {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("launch.remote_count", versions.size),
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
            }

            // ===== 版本分类筛选 + 搜索 =====
            item {
                Column {
                    // 分类滑动选择器
                    val categories = listOf(
                        I18n.t("launch.category_all"),
                        I18n.t("launch.category_release"),
                        I18n.t("launch.category_snapshot"),
                        I18n.t("launch.category_old_beta"),
                        I18n.t("launch.category_old_alpha")
                    )
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
                        label = { Text(I18n.t("launch.search_version")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("launch.showing_count", filtered.size),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(6.dp))
                }
            }

            // 远程版本列表：作为 LazyColumn 真正的 items，按视口懒加载
            // 之前用 filtered.forEach 塞进单个 item，导致 200 行全部立即测量+布局，丧失懒加载意义
            items(filtered, key = { v -> "remote-" + v.getId() }) { v ->
                RemoteVersionRow(
                    id = v.getId(),
                    type = v.getType(),
                    selected = v.getId() == selected,
                    installed = v.getId() in localInfoIds,
                    onClick = { vm.selectVersion(v.getId()) }
                )
            }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (useSegmentedLayout) {
            when (launchTab) {
                // ===== 启动：空白页 + 固定启动按钮（多固定版本时弹出选择）=====
                0 -> {
                    var showPinnedPicker by remember { mutableStateOf(false) }

                    // 判断某个固定版本是否可启动
                    fun canLaunch(vid: String): Boolean {
                        val info = localInfos.find { it.getId() == vid }
                        return info?.isLaunchable() == true && account != null && !gameRunning
                    }

                    Box(Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.BottomEnd) {
                        Box {
                            FloatingActionButton(
                                onClick = {
                                    when {
                                        pinned.isEmpty() -> launchTab = 1 // 无固定版本，跳转版本列表
                                        pinned.size == 1 && canLaunch(pinned.first()) ->
                                            vm.quickLaunch(pinned.first())
                                        pinned.isNotEmpty() -> showPinnedPicker = true
                                    }
                                },
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Icon(Icons.Filled.PlayArrow,
                                     contentDescription = I18n.t("launch.start"),
                                     modifier = Modifier.size(28.dp))
                            }

                            DropdownMenu(
                                expanded = showPinnedPicker,
                                onDismissRequest = { showPinnedPicker = false }
                            ) {
                                pinned.forEach { versionId ->
                                    val launchable = canLaunch(versionId)
                                    DropdownMenuItem(
                                        text = { Text(pinnedLabels[versionId] ?: versionId) },
                                        onClick = {
                                            showPinnedPicker = false
                                            if (launchable) vm.quickLaunch(versionId)
                                        },
                                        enabled = launchable,
                                        leadingIcon = {
                                            Icon(Icons.Filled.PlayArrow, null,
                                                 modifier = Modifier.size(18.dp))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // ===== 版本列表（宽屏双栏 / 窄屏单栏）=====
                1 -> BoxWithConstraints(Modifier.fillMaxSize()) {
                    val isWide = maxWidth >= 720.dp
                    Row(Modifier.fillMaxSize()) {
        // ===== 左侧：统一用 LazyColumn 滚动，避免嵌套滚动冲突 =====
        LazyColumn(
            Modifier.weight(if (isWide) 1.2f else 1f).fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            versionListContent()
        }

        if (isWide) {
            VerticalDivider()
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                rightDetailContent()
            }
        } // end if isWide
        } // end Row
        } // end BoxWithConstraints

        // ===== 账号 =====
                2 -> Column(
                    Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AccountCard(account, vm)
                }

        // ===== 日志 =====
                3 -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            // 日志标题 + 操作按钮（复制 / 导出 / 分享）
            val logSharing by vm.logSharing.collectAsState()
            val shareUrl by vm.shareUrl.collectAsState()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(I18n.t("launch.game_log"), style = MaterialTheme.typography.labelLarge,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                var copied by remember { mutableStateOf(false) }
                TextButton(
                    onClick = {
                        val text = vm.gameLogs.value.map { it.text }.joinToString("\n")
                        try {
                            val toolkit = java.awt.Toolkit.getDefaultToolkit()
                            val clipboard = toolkit.systemClipboard
                            clipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
                            copied = true
                        } catch (_: Throwable) {}
                    },
                    enabled = vm.gameLogs.value.isNotEmpty(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp
                    )
                ) {
                    if (copied) {
                        Icon(Icons.Filled.Check, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("launch.copied"), style = MaterialTheme.typography.labelSmall)
                    } else {
                        Text(I18n.t("launch.copy_log"), style = MaterialTheme.typography.labelSmall)
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
                    enabled = vm.gameLogs.value.isNotEmpty(),
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
                    val exportScope = androidx.compose.runtime.rememberCoroutineScope()
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
                                            exportScope.launch {
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
                    enabled = vm.gameLogs.value.isNotEmpty() && !logSharing,
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
                TextButton(
                    onClick = { vm.clearGameLogs() },
                    enabled = vm.gameLogs.value.isNotEmpty(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp
                    )
                ) {
                    Icon(Icons.Filled.Clear, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("log.clear"), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = { vm.openGameLogFolder() },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp
                    )
                ) {
                    Icon(Icons.Filled.FolderOpen, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("log.open_folder"), style = MaterialTheme.typography.labelSmall)
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
                                color = glassSurfaceVariantColor(),
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
                color = glassSurfaceVariantColor(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 300.dp)
            ) {
                GameLogPanel(vm)
            }
        }
            }
            } else {
                // 最初分栏布局：左版本列表 + 右账号日志同屏显示
                Row(Modifier.fillMaxSize()) {
                    LazyColumn(
                        Modifier.weight(1.2f).fillMaxHeight().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        versionListContent()
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AccountCard(account, vm)
                        rightDetailContent()
                        HorizontalDivider()
                        Surface(
                            color = glassSurfaceVariantColor(),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 400.dp)
                        ) {
                            GameLogPanel(vm)
                        }
                    }
                }
            }
        }

        // 底边栏：使用项目现成的 AnimatedSegmentedSelector 滑块切换
        if (useSegmentedLayout) {
            com.pmcl.ui.animation.AnimatedSegmentedSelector(
                items = listOf("启动", "版本", "账号", "日志"),
                selectedIndex = launchTab,
                onSelect = { launchTab = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                fillWidth = true,
                height = 40.dp
            )
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
            title = { Text(I18n.t("launch.delete_tile")) },
            text = {
                Text(I18n.t("launch.delete_tile_confirm", displayName))
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
                ) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                OutlinedButton(onClick = { deleteTarget = null }) {
                    Text(I18n.t("common.cancel"))
                }
            }
        )
    }

    // ===== 游戏崩溃报错窗口 =====
    crashEvent?.let { ev ->
        CrashReportDialog(
            event = ev,
            onRecovery = { action -> vm.executeRecoveryAction(action, ev.versionId) },
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
                        Card(
                            onClick = { vm.invokeCompatOption(option) },
                            shape = RoundedCornerShape(8.dp),
                            colors = glassCardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            elevation = glassCardElevation(),
                            modifier = Modifier.fillMaxWidth().glassCardBorder()
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

    // ===== Java 运行时下载进度（兼容性自动下载等） =====
    val javaDownloading by vm.javaDownloading.collectAsState()
    val javaDownloadStatus by vm.javaDownloadStatus.collectAsState()
    if (javaDownloading) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载 Java 运行时") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(javaDownloadStatus.ifBlank { "准备中…" },
                        style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
            dismissButton = {}
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
        title = { Text(I18n.t("launch.rename_tile")) },
        text = {
            Column {
                Text(I18n.t("launch.rename_tile_hint", versionId),
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
                Text(I18n.t("launch.rename_tile_placeholder"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) { Text(I18n.t("common.save")) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
        }
    )
}

/**
 * 游戏崩溃报错窗口：展示退出码、崩溃原因、修复建议。
 * 支持查看最近日志片段，复制崩溃信息到剪贴板，并显示可执行的恢复操作。
 */
@Composable
private fun CrashReportDialog(
    event: LauncherViewModel.CrashEvent,
    onRecovery: (com.pmcl.core.launch.CrashAnalyzer.RecoveryAction) -> Unit,
    onDismiss: () -> Unit
) {
    var showLogs by remember { mutableStateOf(false) }
    val report = event.report
    val causes = report?.getCauses() ?: listOf(I18n.t("launch.crash_no_report", event.exitCode))
    val suggestions = report?.getSuggestions() ?: listOf(I18n.t("launch.crash_no_report_hint"))
    val recoveryActions = report?.getRecoveryActions() ?: emptyList()

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
                Text(I18n.t("launch.game_crashed"), color = MaterialTheme.colorScheme.error)
            }
        },
        text = {
            Column {
                Text(I18n.t("launch.crash_info", event.versionId, event.exitCode),
                     style = MaterialTheme.typography.labelMedium,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("launch.possible_causes"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                causes.forEach { c ->
                    Text("• $c", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("launch.fix_suggestions"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                suggestions.forEach { s ->
                    Text("• $s", style = MaterialTheme.typography.bodySmall)
                }
                // ===== 可执行的恢复操作 =====
                if (recoveryActions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(I18n.t("crash.recovery_title"),
                         style = MaterialTheme.typography.labelLarge,
                         fontWeight = FontWeight.SemiBold,
                         color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("crash.recovery_hint"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(6.dp))
                    recoveryActions.forEach { action ->
                        Surface(
                            onClick = {
                                onRecovery(action)
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Row(
                                Modifier.padding(8.dp).fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(action.getTitle(),
                                         style = MaterialTheme.typography.labelMedium,
                                         fontWeight = FontWeight.SemiBold)
                                    Text(action.getDescription(),
                                         style = MaterialTheme.typography.labelSmall,
                                         color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
                if (showLogs) {
                    Spacer(Modifier.height(8.dp))
                    Text(I18n.t("launch.recent_logs", event.recentLogs.size),
                         style = MaterialTheme.typography.labelLarge,
                         fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Surface(
                        color = glassSurfaceVariantColor(),
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
            Button(onClick = onDismiss) { Text(I18n.t("common.close")) }
        },
        dismissButton = {
            OutlinedButton(onClick = { showLogs = !showLogs }) {
                Text(if (showLogs) I18n.t("launch.hide_log") else I18n.t("launch.view_log"))
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
                            text = { Text(I18n.t("launch.rename")) },
                            onClick = {
                                menuExpanded = false
                                onRename()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(I18n.t("launch.delete_tile"),
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
                    !launchable -> I18n.t("launch.tile_invalid")
                    !hasAccount -> I18n.t("launch.no_account")
                    else -> I18n.t("launch.click_to_launch")
                }
                Text(stateText,
                     color = Color.White.copy(alpha = 0.95f),
                     fontSize = 11.sp,
                     modifier = Modifier.weight(1f))
                // 右下角显示上次游玩时间
                if (lastPlayedTime != null && lastPlayedTime > 0) {
                    Text(I18n.t("launch.last_played", formatRelative.format(Date(lastPlayedTime))),
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

/**
 * 扫描进度条：单独订阅 scanProgress 高频流，避免父级 LaunchPage 跟着重组。
 */
@Composable
private fun ScanProgressBar(vm: LauncherViewModel) {
    val scanProgress by vm.scanProgress.collectAsState()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val p = scanProgress
        val fraction = p?.getFraction() ?: 0f
        val total = p?.getTotal() ?: 0
        val scanned = p?.getScanned() ?: 0
        val currentDir = p?.getCurrentDir() ?: ""
        val currentVer = p?.getCurrentVersion() ?: ""

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (total > 0) I18n.t("launch.scan_progress", scanned, total)
                else I18n.t("launch.listing_dirs"),
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
                I18n.t("launch.scan_current", currentDir, currentVer),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1
            )
        }
    }
}

/**
 * 状态行：单独订阅 status 高频流（ViewModel 各处都 _status.value = ...）
 */
@Composable
private fun StatusLine(vm: LauncherViewModel) {
    val status by vm.status.collectAsState()
    Text(I18n.t("launch.status_value", status),
         style = MaterialTheme.typography.labelSmall,
         color = MaterialTheme.colorScheme.outline)
}

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
        color = glassSurfaceVariantColor(),
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
                    Text(I18n.t("launch.last_played_full", formatRelative.format(Date(lastPlayedTime))),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }
            // 右侧快速启动按钮
            IconButton(onClick = onLaunch, enabled = canLaunch) {
                Icon(Icons.Filled.PlayArrow, I18n.t("launch.start"),
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

/** 启动页中的独立游戏实例/整合包条目。 */
@Composable
private fun LaunchInstanceRow(
    info: InstanceInfo,
    launching: Boolean,
    enabled: Boolean,
    onLaunch: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Inventory2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    info.getName(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                val loader = info.getLoader().orEmpty()
                val loaderVersion = info.getLoaderVersion().orEmpty()
                Text(
                    buildString {
                        append(info.getBaseVersionId())
                        if (loader.isNotBlank()) {
                            append(" · ")
                            append(loader)
                            if (loaderVersion.isNotBlank()) append(" ").append(loaderVersion)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(8.dp))
            if (launching) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(
                    onClick = onLaunch,
                    enabled = enabled && info.isLaunchable()
                ) {
                    Icon(Icons.Filled.PlayArrow, I18n.t("instance.launch"))
                }
            }
        }
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
    gameRunning: Boolean,
    hasAccount: Boolean,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onLaunch: () -> Unit
) {
    val canLaunch = info.isLaunchable() && hasAccount && !gameRunning
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
                            Text(I18n.t("launch.inherits", info.getInheritsFrom()),
                                 style = MaterialTheme.typography.labelSmall,
                                 modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("jar  json",
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
            // 直接启动按钮
            IconButton(onClick = onLaunch, enabled = canLaunch) {
                Icon(Icons.Filled.PlayArrow, I18n.t("launch.start"),
                     tint = if (canLaunch) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                     modifier = Modifier.size(18.dp))
            }
            // 固定按钮
            IconButton(onClick = { if (pinned) onUnpin() else onPin() }) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (pinned) I18n.t("launch.unpin") else I18n.t("launch.pin"),
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
                    Text(I18n.t("launch.installed"),
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
    // 初始值从持久化读取，避免每次打开页面重置为 Steve
    var username by remember { mutableStateOf(vm.lastOfflineUsername().ifBlank { "Steve" }) }
    Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("launch.account"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
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
                            Icon(Icons.Filled.AccountCircle, I18n.t("launch.default_avatar"), modifier = Modifier.padding(6.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(account.getUsername(), fontWeight = FontWeight.SemiBold)
                        Text(
                            when (account.getType()) {
                                com.pmcl.core.auth.Account.AccountType.OFFLINE -> "离线账号"
                                com.pmcl.core.auth.Account.AccountType.MICROSOFT -> "微软账号"
                                com.pmcl.core.auth.Account.AccountType.YGGDRASIL -> "皮肤站账号"
                                com.pmcl.core.auth.Account.AccountType.GITHUB -> "GitHub 账号"
                                null -> ""
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                Text(I18n.t("launch.not_logged_in_short"), color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text(I18n.t("launch.offline_username")) }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.loginOffline(username) }, enabled = username.isNotBlank()) {
                    Text(I18n.t("launch.offline_login"))
                }
                OutlinedButton(onClick = vm::startMicrosoftLogin) {
                    Text(I18n.t("launch.microsoft_login"))
                }
            }
        }
    }
}

/** 启动页账号头像（40dp，带 LRU 内存缓存） */
private val launchAvatarCache = com.pmcl.ui.util.LruImageCache()

@Composable
private fun AvatarImage(url: String) {
    var image by remember(url) { mutableStateOf(launchAvatarCache.get(url)) }
    LaunchedEffect(url) {
        if (url.isEmpty()) { image = null; return@LaunchedEffect }
        launchAvatarCache.get(url)?.let { image = it; return@LaunchedEffect }
        // 已知失败的 URL 不重试，避免每次重组都重新下载
        if (launchAvatarCache.isKnownFailed(url)) { image = null; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                if (url.isNullOrBlank()) return@withContext
                val bytes = com.pmcl.ui.util.SafeUrlFetcher.fetchBytes(url, allowPrivateLan = true)
                val bmp = decodeSampledBitmap(bytes, 128) ?: throw IllegalStateException("decode failed")
                launchAvatarCache.put(url, bmp)
                image = bmp
            } catch (_: Throwable) {
                // 下载/解码失败：标记为已知失败，下次不重试
                launchAvatarCache.markFailed(url)
                image = null
            }
        }
    }
    val bmp = image
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = I18n.t("launch.avatar"),
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(40.dp)
        ) {
            Icon(Icons.Filled.Star, I18n.t("common.loading"), modifier = Modifier.padding(10.dp))
        }
    }
}

/**
 * 游戏日志面板（独立 Composable，隔离 gameLogs 高频更新，避免触发整个 LaunchPage 重组）
 * 支持搜索、级别过滤、暂停自动滚动、级别着色。
 */
@Composable
private fun GameLogPanel(vm: LauncherViewModel) {
    val gameLogs by vm.gameLogs.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var levelFilter by remember { mutableStateOf(LogLevelFilter.ALL) }
    var autoScroll by remember { mutableStateOf(true) }
    val errorColor = MaterialTheme.colorScheme.error
    val warnColor = MaterialTheme.colorScheme.tertiary
    val infoColor = MaterialTheme.colorScheme.primary
    val normalColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = Modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                placeholder = { Text(I18n.t("log.search_hint"), style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
            )
            IconButton(onClick = { autoScroll = !autoScroll }, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (autoScroll) Icons.Filled.KeyboardArrowDown else Icons.Filled.Pause,
                    I18n.t("log.autoscroll"),
                    modifier = Modifier.size(18.dp),
                    tint = if (autoScroll) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LogLevelFilter.entries.forEach { f ->
                FilterChip(
                    selected = levelFilter == f,
                    onClick = { levelFilter = f },
                    label = { Text(I18n.t(f.i18nKey), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(28.dp)
                )
            }
        }
        if (gameLogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(I18n.t("launch.no_logs"), color = MaterialTheme.colorScheme.outline)
            }
        } else {
            val logListState = rememberLazyListState()
            val displayedLogs = remember(gameLogs, searchQuery, levelFilter) {
                gameLogs.asReversed()
                    .asSequence()
                    .filter { entry ->
                        (searchQuery.isBlank() || entry.text.contains(searchQuery, ignoreCase = true)) &&
                            levelFilter.matches(entry.text)
                    }
                    .take(500)
                    .toList()
                    .asReversed()
            }
            LaunchedEffect(displayedLogs.size, autoScroll) {
                if (autoScroll && displayedLogs.isNotEmpty()) {
                    logListState.scrollToItem(displayedLogs.lastIndex)
                }
            }
            LaunchedEffect(logListState.isScrollInProgress) {
                if (!logListState.isScrollInProgress && displayedLogs.isNotEmpty()) {
                    val lastVisible = logListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    autoScroll = lastVisible >= displayedLogs.lastIndex - 2
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = logListState
            ) {
                itemsIndexed(displayedLogs, key = { _, entry -> entry.seq }) { _, line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = colorForLogLine(line.text, errorColor, warnColor, infoColor, normalColor)
                    )
                }
            }
        }
    }
}

private enum class LogLevelFilter(val i18nKey: String) {
    ALL("log.filter_all"),
    ERROR("log.filter_error"),
    WARN("log.filter_warn"),
    INFO("log.filter_info");

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

private fun colorForLogLine(
    text: String,
    error: Color,
    warn: Color,
    info: Color,
    normal: Color
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
