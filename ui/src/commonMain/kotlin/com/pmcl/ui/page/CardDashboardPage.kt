package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 卡片主题主页：Origin OS2 锁屏风格方形卡片网格。
 *
 * - 4dp 极小圆角的方形卡片
 * - 大卡片：当前版本 + 启动按钮（主视觉）
 * - 副卡片：新闻资讯 / 好友联机
 * - 小卡片：存档 / 截图 / 模组 / 世界 统计快报
 *
 * 开启方式：设置 → 外观 → 卡片主题
 */
@Composable
fun CardDashboardPage(vm: LauncherViewModel) {
    val versions by vm.versions.collectAsState()
    val localInfos by vm.localVersionInfos.collectAsState()
    val selected by vm.selectedVersion.collectAsState()
    val status by vm.status.collectAsState()
    val account by vm.account.collectAsState()
    val gameRunning by vm.gameRunning.collectAsState()
    val installing by vm.installing.collectAsState()
    val installProgress by vm.installProgress.collectAsState()
    val newsItems by vm.newsItems.collectAsState()
    val worlds by vm.worlds.collectAsState()
    val screenshots by vm.screenshots.collectAsState()
    val installedMods by vm.installedMods.collectAsState()
    val pinned by vm.pinnedVersions.collectAsState()
    val recents by vm.recentVersions.collectAsState()

    val friendManager = remember { vm.core.friend() }
    var friends by remember { mutableStateOf(friendManager.getFriends()) }
    LaunchedEffect(Unit) {
        try { friendManager.start() } catch (_: Throwable) {}
        // 持续轮询好友列表（好友系统无 Compose StateFlow 桥接）
        while (true) {
            kotlinx.coroutines.delay(3000)
            friends = friendManager.getFriends()
        }
    }

    LaunchedEffect(Unit) {
        if (versions.isEmpty()) vm.refreshVersions()
        if (newsItems.isEmpty()) vm.refreshNews()
        if (worlds.isEmpty()) vm.refreshWorlds()
        if (screenshots.isEmpty()) vm.refreshScreenshots()
        if (installedMods.isEmpty()) vm.refreshInstalledMods()
    }

    val shape = RoundedCornerShape(4.dp)
    val scroll = rememberScrollState()
    val format = remember { SimpleDateFormat("MM-dd HH:mm") }

    // 当前选中版本是否已安装
    val localInfoIds = remember(localInfos) { localInfos.mapNotNull { it.getId() }.toHashSet() }
    val isInstalled = selected != null && selected in localInfoIds
    val selectedInfo = remember(localInfos, selected) {
        localInfos.firstOrNull { it.getId() == selected }
    }
    // 当前显示版本：选中 → 最近 → 固定 → 第一个本地版本
    val displayVersion = selected
        ?: recents.firstOrNull { it in localInfoIds }
        ?: pinned.firstOrNull { it in localInfoIds }
        ?: localInfos.firstOrNull()?.getId()
    val displayInfo = remember(localInfos, displayVersion) {
        localInfos.firstOrNull { it.getId() == displayVersion }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ===== 顶部 Row：大启动卡片 + 右侧两个副卡片 =====
        Row(
            Modifier.fillMaxWidth().height(280.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 左侧大卡片：版本 + 启动（主视觉）
            Card(
                modifier = Modifier.weight(0.62f).fillMaxHeight(),
                shape = shape,
                colors = glassCardColors()
            ) {
                Column(Modifier.fillMaxSize().padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Bolt, "启动",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("启动游戏", style = MaterialTheme.typography.titleMedium,
                             fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { vm.refreshLocalVersions() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, "扫描", modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    // 版本号大字
                    Text(
                        displayVersion ?: "暂无版本",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (displayInfo != null)
                            "已安装 · ${if (displayInfo.isHasJar()) "完整" else "仅 json"}"
                        else if (displayVersion != null) "未安装，点击安装"
                        else "点击下载首个版本",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.weight(1f))
                    // 账号信息
                    Text(
                        account?.username ?: "未登录",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(12.dp))
                    // 启动按钮
                    Button(
                        onClick = {
                            val v = displayVersion
                            if (v != null) {
                                vm.selectVersion(v)
                                if (v in localInfoIds) vm.launch()
                                else vm.enqueueVersionInstall(v)
                            }
                        },
                        enabled = displayVersion != null && !gameRunning && !installing,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = shape
                    ) {
                        if (installing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("安装中…")
                        } else if (gameRunning) {
                            Text("游戏运行中")
                        } else {
                            Icon(Icons.Filled.PlayArrow, "启动",
                                 modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (isInstalled || displayVersion == null) "启动" else "安装并启动",
                                 fontWeight = FontWeight.Bold)
                        }
                    }
                    if (installing) {
                        Spacer(Modifier.height(8.dp))
                        val p = installProgress
                        if (p != null && p.getTotal() > 0) {
                            LinearProgressIndicator(
                                progress = { (p.percent().toFloat() / 100f).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
            // 右侧副卡片列：新闻 + 好友
            Column(
                Modifier.weight(0.38f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 新闻卡片
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = shape,
                    colors = glassCardColors()
                ) {
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Article, "新闻",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("资讯", style = MaterialTheme.typography.titleSmall,
                                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${newsItems.size}",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                        Spacer(Modifier.height(8.dp))
                        val top = newsItems.take(3)
                        top.forEach { n ->
                            Text(
                                n.getTitle() ?: "(无标题)",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        n.getLink()?.let {
                                            try {
                                                java.awt.Desktop.getDesktop()
                                                    .browse(java.net.URI(it))
                                            } catch (_: Throwable) {}
                                        }
                                    }
                                    .padding(vertical = 3.dp)
                            )
                        }
                        if (top.isEmpty()) {
                            Text("暂无资讯",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
                // 好友联机卡片
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = shape,
                    colors = glassCardColors()
                ) {
                    Column(Modifier.fillMaxSize().padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Group, "好友",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("联机", style = MaterialTheme.typography.titleSmall,
                                 fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("${friends.size}",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                        Spacer(Modifier.height(8.dp))
                        val top = friends.take(3)
                        top.forEach { f ->
                            Text(
                                f.displayName ?: f.identity,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                        }
                        if (top.isEmpty()) {
                            Text("暂无好友",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }

        // ===== 底部小卡片行：统计快报 =====
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                icon = Icons.Filled.Savings,
                label = "存档",
                value = "${worlds.size}",
                modifier = Modifier.weight(1f),
                shape = shape
            )
            StatCard(
                icon = Icons.Filled.Image,
                label = "截图",
                value = "${screenshots.size}",
                modifier = Modifier.weight(1f),
                shape = shape
            )
            StatCard(
                icon = Icons.Filled.Storage,
                label = "模组",
                value = "${installedMods.size}",
                modifier = Modifier.weight(1f),
                shape = shape
            )
            StatCard(
                icon = Icons.Filled.Public,
                label = "世界",
                value = "${worlds.size}",
                modifier = Modifier.weight(1f),
                shape = shape
            )
        }

        // ===== 最近使用版本（小卡片列） =====
        if (recents.isNotEmpty()) {
            Text("最近使用", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                recents.take(3).forEach { vid ->
                    val info = localInfos.firstOrNull { it.getId() == vid }
                    Card(
                        modifier = Modifier.weight(1f).clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {
                            vm.selectVersion(vid)
                        },
                        shape = shape,
                        colors = glassCardColors()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(vid, style = MaterialTheme.typography.bodyMedium,
                                 fontWeight = FontWeight.SemiBold,
                                 maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (info != null) "可启动"
                                else "未安装",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (info != null) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }

        // 状态栏
        Text(
            "状态: $status",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * 1x1 小卡片：图标 + 数字 + 标签
 */
@Composable
private fun StatCard(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp)
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = glassCardColors()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon, label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}
