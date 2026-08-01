package com.lash.pmcl.ui.screens

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.preferences.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    downloadManager: DownloadManager,
    preferences: Preferences,
    appVersion: String,
) {
    val context = LocalContext.current

    // 设备总内存 → 推荐最大内存（替代桌面 RuntimeManager.getRecommendedMaxMemoryMb）
    val recommendedMaxMb = remember {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024L * 1024L) * 0.75).toInt()
        }.getOrElse { 2048 }
    }

    val systemInfo = remember {
        buildString {
            append("设备: ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
            append("架构: ${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }
    val workDir = remember { "${context.filesDir.absolutePath}/pmcl" }

    // JVM 相关状态上提，供内存卡 / JVM 卡 / 启动预设卡共享
    var minMem by remember { mutableStateOf(preferences.getMinMemoryMb().toString()) }
    var maxMem by remember { mutableStateOf(preferences.getMaxMemoryMb().toString()) }
    var gcType by remember { mutableStateOf(preferences.getGcType()) }
    var useAikar by remember { mutableStateOf(preferences.isUseAikarFlags()) }
    var customArgs by remember { mutableStateOf(preferences.getCustomJvmArgs()) }

    // 启动预设（Android 端 Preferences 缺少持久化字段，仅当前会话有效）
    val presets = remember { mutableStateListOf<LaunchPreset>() }

    fun savePreset(name: String) {
        presets.add(
            LaunchPreset(
                name = name.trim(),
                minMemoryMb = minMem.toIntOrNull() ?: 0,
                maxMemoryMb = maxMem.toIntOrNull() ?: 0,
                gcType = gcType,
                useAikarFlags = useAikar,
                customJvmArgs = customArgs,
            )
        )
    }

    fun applyPreset(p: LaunchPreset) {
        preferences.setMinMemoryMb(p.minMemoryMb); minMem = p.minMemoryMb.toString()
        preferences.setMaxMemoryMb(p.maxMemoryMb); maxMem = p.maxMemoryMb.toString()
        preferences.setGcType(p.gcType); gcType = p.gcType
        preferences.setUseAikarFlags(p.useAikarFlags); useAikar = p.useAikarFlags
        preferences.setCustomJvmArgs(p.customJvmArgs); customArgs = p.customJvmArgs
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            item { SectionHeader("内存") }
            item { MemoryCard(preferences, minMem, maxMem, recommendedMaxMb, onMinChange = {
                if (it.isEmpty() || it.toIntOrNull() != null) {
                    minMem = it
                    val v = it.toIntOrNull() ?: 0
                    if (v >= 0) preferences.setMinMemoryMb(v)
                }
            }, onMaxChange = {
                if (it.isEmpty() || it.toIntOrNull() != null) {
                    maxMem = it
                    val v = it.toIntOrNull() ?: 0
                    if (v >= 0) preferences.setMaxMemoryMb(v)
                }
            }) }

            item { SectionHeader("JVM 高级配置") }
            item { JvmAdvancedCard(gcType, useAikar, customArgs,
                onGcChange = { gcType = it; preferences.setGcType(it) },
                onAikarChange = { useAikar = it; preferences.setUseAikarFlags(it) },
                onArgsChange = { customArgs = it; preferences.setCustomJvmArgs(it) }) }

            item { SectionHeader("启动预设") }
            item { LaunchPresetCard(presets, ::savePreset, ::applyPreset) { name ->
                presets.removeAll { it.name == name }
            } }

            item { SectionHeader("游戏通用行为") }
            item { GameBehaviorCard(preferences) }

            item { SectionHeader("Minecraft 根目录管理") }
            item { MinecraftRootsCard() }

            item { SectionHeader("澪模式") }
            item { MioModeCard() }

            item { SectionHeader("外观") }
            item { AppearanceCard(preferences) }

            item { SectionHeader("网络配置") }
            item { NetworkConfigCard(preferences, downloadManager) }

            item { SectionHeader("系统信息") }
            item { SystemInfoCard(systemInfo, workDir) }

            item { SectionHeader("GitHub Release 同步") }
            item { GithubSyncCard(preferences) }

            item { SectionHeader("设备绑定保护") }
            item { DeviceBindingCard() }

            item { SectionHeader("关于") }
            item { AboutCard(appVersion) }
        }
    }
}

// ==================== 1. 内存 ====================
@Composable
private fun MemoryCard(
    preferences: Preferences,
    minMem: String,
    maxMem: String,
    recommendedMaxMb: Int,
    onMinChange: (String) -> Unit,
    onMaxChange: (String) -> Unit,
) {
    SettingsCard {
        Text("内存", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = minMem,
                onValueChange = onMinChange,
                label = { Text("最小内存 (MB)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = maxMem,
                onValueChange = onMaxChange,
                label = { Text("最大内存 (MB)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "推荐最大值: $recommendedMaxMb MB（设备总内存的 75%）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

// ==================== 2. JVM 高级配置 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JvmAdvancedCard(
    gcType: String,
    useAikar: Boolean,
    customArgs: String,
    onGcChange: (String) -> Unit,
    onAikarChange: (Boolean) -> Unit,
    onArgsChange: (String) -> Unit,
) {
    SettingsCard {
        Text("JVM 高级配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        Text("GC 类型", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        val gcValues = listOf("G1GC", "ZGC", "ShenandoahGC", "ParallelGC")
        val gcLabels = listOf("G1GC", "ZGC", "Shenandoah", "Parallel")
        SegmentedSelector(
            items = gcLabels,
            selectedIndex = gcValues.indexOf(gcType).coerceAtLeast(0),
            onSelect = { onGcChange(gcValues[it]) },
        )

        Spacer(Modifier.height(12.dp))
        SwitchRow(
            title = "Aikar's Flags",
            desc = "使用社区优化的 JVM 参数组合，提升垃圾回收与吞吐表现",
            checked = useAikar,
            onCheckedChange = onAikarChange,
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = customArgs,
            onValueChange = onArgsChange,
            label = { Text("自定义 JVM 参数") },
            supportingText = { Text("以空格分隔，将追加到默认参数之后") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ==================== 3. 启动预设 ====================
@Composable
private fun LaunchPresetCard(
    presets: MutableList<LaunchPreset>,
    onSave: (String) -> Unit,
    onApply: (LaunchPreset) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bookmarks, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("启动预设", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Button(onClick = { presetName = ""; showSaveDialog = true }) {
                Text("保存当前为预设")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "将当前的内存 / GC / JVM 参数组合保存为预设，便于一键切换。Android 端预设仅在当前会话有效，重启后清空。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )

        if (presets.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            presets.forEach { p ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Medium)
                            Text(
                                "${p.maxMemoryMb}MB | ${p.gcType}" +
                                    if (p.useAikarFlags) " | Aikar" else "" +
                                    if (p.customJvmArgs.isNotEmpty()) " | +JVM" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        OutlinedButton(onClick = { onApply(p) }) { Text("应用") }
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { onDelete(p.name) }) {
                            Icon(Icons.Filled.Delete, "删除预设", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("保存启动预设") },
            text = {
                Column {
                    Text("请输入预设名称", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("预设名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (presets.any { it.name == presetName.trim() }) {
                        Spacer(Modifier.height(4.dp))
                        Text("已存在同名预设，将覆盖原预设", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (presetName.isNotBlank()) {
                        if (presets.any { it.name == presetName.trim() }) {
                            presets.removeAll { it.name == presetName.trim() }
                        }
                        onSave(presetName)
                        showSaveDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("取消") } },
        )
    }
}

// ==================== 4. 游戏通用行为 ====================
@Composable
private fun GameBehaviorCard(preferences: Preferences) {
    var serverHost by remember { mutableStateOf(preferences.getGameServerHost()) }
    var serverPort by remember { mutableStateOf(preferences.getGameServerPort().toString()) }

    SettingsCard {
        Text("游戏通用行为", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text("控制游戏窗口、渲染与启动行为。未标注功能在 Android 端暂不支持。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(12.dp))
        UnsupportedSwitchItem("版本隔离", "每个版本独立存档与配置目录")
        HorizontalDivider()
        UnsupportedRow("窗口分辨率", "游戏窗口的宽 × 高")
        HorizontalDivider()
        UnsupportedRow("渲染器", "AUTO / OPENGL / VULKAN")
        HorizontalDivider()
        UnsupportedSwitchItem("全屏启动", "启动时进入全屏模式")
        HorizontalDivider()
        UnsupportedSwitchItem("Demo 模式", "以演示模式启动游戏")

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Text("自动连接服务器", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = serverHost,
                onValueChange = { serverHost = it; preferences.setGameServerHost(it) },
                label = { Text("服务器地址") }, singleLine = true,
                placeholder = { Text("play.example.net") },
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = serverPort,
                onValueChange = {
                    serverPort = it
                    it.toIntOrNull()?.let { v -> preferences.setGameServerPort(v) }
                },
                label = { Text("端口") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Text("启动游戏后自动连接到该服务器", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        UnsupportedRow("自定义窗口图标", "游戏窗口标题栏图标路径")
        HorizontalDivider()
        UnsupportedRow("自定义主菜单背景视频", "替换游戏主菜单背景动画")
        HorizontalDivider()
        UnsupportedRow("自定义 Natives 路径", "指定本地库加载目录")
    }
}

// ==================== 5. Minecraft 根目录管理 ====================
@Composable
private fun MinecraftRootsCard() {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text("Minecraft 根目录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text("管理额外的 Minecraft 安装根目录。Android 端暂未持久化，重启后清空。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(12.dp))
        UnsupportedBadgeRow()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {}, enabled = false) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加根目录")
            }
        }
    }
}

// ==================== 6. 澪模式 ====================
@Composable
private fun MioModeCard() {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("澪模式", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Switch(checked = false, onCheckedChange = null, enabled = false)
        }
        Spacer(Modifier.height(4.dp))
        Text("澪模式依赖桌面端 JVM 与操作系统接口进行深度调优，Android 平台暂不支持。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("L1 · JVM 级", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        UnsupportedSwitchItem("JVM 激进参数", "激进分配堆与代码缓存")
        UnsupportedSwitchItem("大页内存 + NUMA", "启用透明大页与 NUMA 绑定")
        UnsupportedSwitchItem("实验性 ZGC", "强制使用 ZGC 并放宽超时")
        UnsupportedSwitchItem("LWJGL 渲染加速", "优化 LWJGL 渲染管线")
        UnsupportedSwitchItem("JIT 编译器激进", "提前编译并禁用分层")
        UnsupportedSwitchItem("网络栈优化", "调整 TCP 缓冲与复用")
        UnsupportedSwitchItem("元空间管控", "预分配元空间防止扩容")

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("L2 · 进程级", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        UnsupportedSwitchItem("进程级调优", "调整进程 I/O 与调度")
        UnsupportedSwitchItem("疯狂优先级", "以最高优先级运行（需管理员）")

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("L3 · 系统级", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        UnsupportedSwitchItem("系统电源策略", "启动时切换高性能电源计划（需管理员）")
    }
}

// ==================== 7. 外观 ====================
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceCard(preferences: Preferences) {
    var dark by remember { mutableStateOf(preferences.isUseDarkTheme()) }
    var dynamicColor by remember { mutableStateOf(preferences.isDynamicColor()) }
    var themePreset by remember { mutableStateOf(preferences.getThemePreset()) }
    var accentColor by remember { mutableStateOf(preferences.getCustomAccentColor()) }
    var colorMode by remember { mutableStateOf(preferences.getColorMode()) }
    var language by remember { mutableStateOf(preferences.getLanguage()) }

    SettingsCard {
        Text("外观", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        // 深浅模式
        SwitchRow("深色模式", "切换深色 / 浅色主题", dark) {
            dark = it; preferences.setUseDarkTheme(it)
        }
        Spacer(Modifier.height(4.dp))
        Text("主题更改将在重启启动器后完全生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // 主题色彩预设
        Text("主题色彩预设", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        val presetEnabled = !dynamicColor && accentColor == -1
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            THEME_PRESETS.forEach { (id, seedRgb) ->
                val isSelected = themePreset == id
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(width = 64.dp, height = 64.dp)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(seedRgb or 0xFF000000.toInt()))
                            .then(
                                if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
                            )
                            .clickable(enabled = presetEnabled) { themePreset = id; preferences.setThemePreset(id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) Icon(Icons.Filled.Check, THEME_PRESET_NAMES[id], tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        THEME_PRESET_NAMES[id].orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // 莫奈取色
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = dynamicColor, onCheckedChange = {
                dynamicColor = it; preferences.setDynamicColor(it)
                if (it) { accentColor = -1; preferences.setCustomAccentColor(-1) }
            })
            Spacer(Modifier.width(8.dp))
            Text("莫奈取色 (Material You)", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (dynamicColor) {
                TextButton(onClick = { preferences.setDynamicColor(true) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
                    Text("重新提取", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text("跟随系统壁纸动态生成主题配色", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // 自定义强调色
        Text("自定义强调色", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        val accentEnabled = !dynamicColor
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ACCENT_PRESETS.forEach { (rgb, name) ->
                val currentRgb = if (accentColor != -1) accentColor and 0x00FFFFFF else -1
                val isSelected = currentRgb == rgb
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(rgb or 0xFF000000.toInt()))
                        .then(
                            if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier.border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), CircleShape)
                        )
                        .clickable(enabled = accentEnabled) { accentColor = rgb; preferences.setCustomAccentColor(rgb) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (isSelected) Icon(Icons.Filled.Check, name, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        var hexInput by remember(accentColor) {
            mutableStateOf(if (accentColor != -1) String.format("%06X", accentColor and 0x00FFFFFF) else "")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hexInput,
                onValueChange = { v ->
                    val cleaned = v.filter { it.isLetterOrDigit() }.take(6)
                    hexInput = cleaned
                    if (cleaned.length == 6) {
                        val rgb = cleaned.toInt(16)
                        accentColor = rgb; preferences.setCustomAccentColor(rgb)
                    }
                },
                label = { Text("十六进制") }, prefix = { Text("#") }, singleLine = true,
                enabled = accentEnabled, modifier = Modifier.weight(1f),
            )
            if (accentColor != -1) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { accentColor = -1; preferences.setCustomAccentColor(-1); hexInput = "" }, enabled = accentEnabled) {
                    Icon(Icons.Filled.Clear, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("恢复默认")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // 色彩模式
        Text("色彩模式", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        val modeValues = listOf("normal", "amoled", "high_contrast", "soft")
        val modeLabels = listOf("标准", "AMOLED", "高对比", "柔护眼")
        SegmentedSelector(modeLabels, modeValues.indexOf(colorMode).coerceAtLeast(0)) {
            colorMode = modeValues[it]; preferences.setColorMode(modeValues[it])
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        // 语言
        Text("语言", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        val langValues = listOf("zh_CN", "zh_TW", "en_US", "ja_JP")
        val langLabels = listOf("简体中文", "繁體中文", "English", "日本語")
        SegmentedSelector(langLabels, langValues.indexOf(language).coerceAtLeast(0)) {
            language = langValues[it]; preferences.setLanguage(langValues[it])
        }
        Text("重启启动器后生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        UnsupportedRow("自定义启动器背景", "关闭 / 图片 / 视频")
        HorizontalDivider()
        UnsupportedSwitchItem("预判启动", "基于使用习惯预测并预热版本")
        HorizontalDivider()
        UnsupportedRow("UI 缩放", "调整启动器界面缩放比例 (0.8 ~ 1.5)")
        HorizontalDivider()
        UnsupportedSwitchItem("性能 HUD", "悬浮显示 CPU / 内存 / GPU / FPS 指标")
    }
}

// ==================== 8. 网络配置 ====================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkConfigCard(preferences: Preferences, downloadManager: DownloadManager) {
    var mirrorType by remember { mutableStateOf(preferences.getMirrorType()) }
    var customMirror by remember { mutableStateOf(preferences.getCustomMirrorBase()) }
    var useProxy by remember { mutableStateOf(preferences.isUseProxy()) }
    var proxyHost by remember { mutableStateOf(preferences.getProxyHost()) }
    var proxyPort by remember { mutableStateOf(preferences.getProxyPort().toString()) }
    var useAuth by remember { mutableStateOf(preferences.isUseHttpAuth()) }
    var proxyUser by remember { mutableStateOf(preferences.getProxyUsername()) }
    var proxyPass by remember { mutableStateOf(preferences.getProxyPassword()) }
    var speedLimit by remember { mutableStateOf(preferences.getDownloadSpeedLimitKb().toString()) }
    var retryCount by remember { mutableStateOf(preferences.getDownloadRetryCount().toString()) }
    var enableResume by remember { mutableStateOf(preferences.isEnableResume()) }
    var chunkedThreads by remember { mutableStateOf(preferences.getChunkedDownloadThreads().toString()) }

    SettingsCard {
        Text("网络配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        Text("下载镜像", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        val mValues = listOf("OFFICIAL", "BMCLAPI", "CUSTOM")
        val mLabels = listOf("官方源", "BMCLAPI", "自定义")
        SegmentedSelector(mLabels, mValues.indexOf(mirrorType).coerceAtLeast(0)) {
            mirrorType = mValues[it]; preferences.setMirrorType(mValues[it])
        }
        if (mirrorType == "CUSTOM") {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = customMirror,
                onValueChange = { customMirror = it; preferences.setCustomMirrorBase(it) },
                label = { Text("自定义镜像地址") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(12.dp))
        SwitchRow("HTTP 代理", null, useProxy) { useProxy = it; preferences.setUseProxy(it) }
        if (useProxy) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = proxyHost,
                    onValueChange = { proxyHost = it; preferences.setProxyHost(it) },
                    label = { Text("主机") }, singleLine = true, modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    value = proxyPort,
                    onValueChange = {
                        proxyPort = it
                        it.toIntOrNull()?.let { v -> preferences.setProxyPort(v) }
                    },
                    label = { Text("端口") }, singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            SwitchRow("代理认证", null, useAuth) { useAuth = it; preferences.setUseHttpAuth(it) }
            if (useAuth) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = proxyUser,
                        onValueChange = { proxyUser = it; preferences.setProxyUsername(it) },
                        label = { Text("用户名") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = proxyPass,
                        onValueChange = { proxyPass = it; preferences.setProxyPassword(it) },
                        label = { Text("密码") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = speedLimit,
                onValueChange = {
                    speedLimit = it
                    it.toIntOrNull()?.let { v -> preferences.setDownloadSpeedLimitKb(v) }
                },
                label = { Text("限速 (KB/s)") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = retryCount,
                onValueChange = {
                    retryCount = it
                    it.toIntOrNull()?.let { v -> preferences.setDownloadRetryCount(v) }
                },
                label = { Text("重试次数") }, singleLine = true, modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = chunkedThreads,
                onValueChange = {
                    chunkedThreads = it
                    it.toIntOrNull()?.let { v -> preferences.setChunkedDownloadThreads(v) }
                },
                label = { Text("分块线程") }, singleLine = true, modifier = Modifier.weight(1f),
                supportingText = { Text("当前: ${downloadManager.getChunkedDownloadThreads()}") },
            )
        }
        Text("更改将在下次下载时生效", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(8.dp))
        SwitchRow("断点续传", "下载中断后可从断点继续", enableResume) { enableResume = it; preferences.setEnableResume(it) }
    }
}

// ==================== 9. 系统信息 ====================
@Composable
private fun SystemInfoCard(systemInfo: String, workDir: String) {
    SettingsCard {
        Text("系统信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(systemInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
        Text("工作目录: $workDir", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

// ==================== 10. GitHub Release 同步 ====================
@Composable
private fun GithubSyncCard(preferences: Preferences) {
    var syncEnabled by remember { mutableStateOf(preferences.isGithubSyncEnabled()) }
    var repoInput by remember { mutableStateOf(preferences.getGithubRepo()) }

    SettingsCard {
        Text("GitHub Release 同步", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("直接同步 GitHub Release：启动器定时轮询指定仓库的最新 Release，发现新版本时主动通知（无需独立推送服务器）。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))

        SwitchRow("启用 GitHub Release 同步", null, syncEnabled) {
            syncEnabled = it; preferences.setGithubSyncEnabled(it)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = repoInput,
            onValueChange = { repoInput = it },
            label = { Text("GitHub 仓库") }, placeholder = { Text("owner/repo") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = { preferences.setGithubRepo(repoInput.trim()) }) {
                    Icon(Icons.Filled.Check, "保存仓库")
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        Text("格式: owner/repo（如 peddlejumper/PMCL）。每 30 分钟检查一次。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp).background(
                    color = if (syncEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                )
            )
            Spacer(Modifier.width(8.dp))
            Text(if (syncEnabled) "等待检查..." else "未启用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ==================== 11. 设备绑定保护 ====================
@Composable
private fun DeviceBindingCard() {
    var showNotice by remember { mutableStateOf(false) }

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(8.dp))
            Text("设备绑定保护", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Surface(
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text("未配置 (Android 暂不支持)", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("使用唯一设备加密码将启动器和游戏绑定到当前设备。Android 端暂不支持此功能。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {}, enabled = false) { Text("开启保护") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {}, enabled = false) { Text("导出私钥") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showNotice = true }) { Text("注意事项", style = MaterialTheme.typography.labelSmall) }
        }
    }

    if (showNotice) {
        AlertDialog(
            onDismissRequest = { showNotice = false },
            title = { Text("设备绑定保护 - 注意事项") },
            text = {
                Column {
                    val notices = listOf(
                        "1. 开启保护后，启动器将生成唯一设备加密码和 RSA-2048 密钥对，将启动器和游戏绑定到当前设备。",
                        "2. 绑定后，启动器和游戏文件被复制到其他设备时将无法启动，防止未授权使用。",
                        "3. 私钥是关闭或迁移保护的唯一凭证，开启时务必设置强密码并妥善保存导出的私钥文件。",
                        "4. 私钥丢失后将无法关闭保护，也无法在其他设备恢复，请务必备份。",
                        "5. 设备加密码基于硬件指纹实时派生，不存盘，无法从文件中提取。",
                        "6. 本地私钥副本使用设备码加密，复制到其他设备后因设备码不同无法解密。",
                        "7. 更换硬件可能导致设备码变化，此时需用私钥重新绑定到新设备。",
                        "8. 关闭保护需导入私钥并验证密码，确保仅授权用户可解除绑定。",
                    )
                    notices.forEach { Text(it, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(6.dp)) }
                }
            },
            confirmButton = { Button(onClick = { showNotice = false }) { Text("我已了解") } },
        )
    }
}

// ==================== 12. 关于 ====================
@Composable
private fun AboutCard(appVersion: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showLicense by remember { mutableStateOf(false) }
    var showAgreement by remember { mutableStateOf(false) }
    var showDisclaimer by remember { mutableStateOf(false) }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 2.dp) {
                Icon(Icons.Filled.SportsEsports, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp).padding(8.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("PMCL", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("版本 $appVersion", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("PMCL 是一款跨平台 Minecraft 启动器，提供账号管理、版本安装、模组市场、游戏内容管理等一站式能力。",
            style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(16.dp))
        Text("主要功能", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureColumn("核心", listOf("账号管理", "版本管理", "加载器", "启动预设"), Modifier.weight(1f))
            FeatureColumn("内容", listOf("模组市场", "游戏内容", "配置编辑"), Modifier.weight(1f))
            FeatureColumn("工具", listOf("存档管理", "崩溃分析", "Java 运行时"), Modifier.weight(1f))
            FeatureColumn("扩展", listOf("联机", "新闻", "整合包", "实例"), Modifier.weight(1f))
        }

        Spacer(Modifier.height(16.dp))
        Text("技术栈", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        TechStackTable()

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openUrl("https://github.com/peddlejumper") }) {
                Text("作者 GitHub"); Icon(Icons.Filled.OpenInNew, null, Modifier.size(14.dp))
            }
            OutlinedButton(onClick = { showLicense = true }) {
                Icon(Icons.Filled.Article, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("许可证")
            }
            OutlinedButton(onClick = { showAgreement = true }) {
                Icon(Icons.Filled.Gavel, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("用户协议")
            }
            OutlinedButton(onClick = { showDisclaimer = true }) {
                Icon(Icons.Filled.Shield, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("免责")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openUrl("https://github.com/EasyTier/EasyTier") }) { Text("EasyTier") }
            OutlinedButton(onClick = { openUrl("https://modrinth.com") }) { Text("Modrinth") }
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("© PMCL. 基于 LGPL-3.0 许可证开源。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }

    if (showLicense) {
        AssetDocumentDialog(title = "开源许可证", assetName = "LICENSE.zh.txt", onDismiss = { showLicense = false }, onCopy = { clipboard.setText(AnnotatedString(it)) })
    }
    if (showAgreement) {
        AssetDocumentDialog(title = "用户协议", assetName = "USER_AGREEMENT.txt", onDismiss = { showAgreement = false }, onCopy = { clipboard.setText(AnnotatedString(it)) })
    }
    if (showDisclaimer) {
        AssetDocumentDialog(title = "免责协议", assetName = "DISCLAIMER.txt", onDismiss = { showDisclaimer = false }, onCopy = { clipboard.setText(AnnotatedString(it)) })
    }
}

@Composable
private fun FeatureColumn(title: String, items: List<String>, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        items.forEach { Text("·  $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun TechStackTable() {
    val rows = listOf(
        Triple("Kotlin", KotlinVersion.CURRENT.toString(), "主开发语言"),
        Triple("Jetpack Compose", "BOM", "声明式 UI 框架"),
        Triple("Material 3", "3.x", "设计系统组件"),
        Triple("Android SDK", "API ${Build.VERSION.SDK_INT}", "系统平台"),
        Triple("OkHttp", "4.12.0", "HTTP 客户端"),
        Triple("Gson", "2.11.0", "JSON 序列化"),
        Triple("kotlinx-coroutines", "1.9.0", "异步与协程"),
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("组件", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.3f))
                Text("版本", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.3f))
                Text("用途", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(0.4f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            rows.forEachIndexed { index, (name, version, purpose) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.3f))
                    Text(version, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.3f))
                    Text(purpose, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
                }
                if (index < rows.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            }
        }
    }
}

// ==================== 协议文档查看器 ====================
@Composable
private fun AssetDocumentDialog(
    title: String,
    assetName: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val context = LocalContext.current
    val docText by produceState(initialValue = "", assetName) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(assetName).bufferedReader().use { it.readText() }
            }.getOrElse { "加载文本失败: $assetName" }
        }
    }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Description, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title)
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onCopy(docText) }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制全文")
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (docText.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().height(440.dp),
                    ) {
                        Text(
                            text = docText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.verticalScroll(scrollState).padding(12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ==================== 通用组件 ====================
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SwitchRow(title: String, desc: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (desc != null) Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun UnsupportedBadge() {
    Surface(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
        Text("暂不支持", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
private fun UnsupportedRow(title: String, desc: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (desc != null) Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        UnsupportedBadge()
    }
}

@Composable
private fun UnsupportedSwitchItem(title: String, desc: String? = null) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (desc != null) Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = false, onCheckedChange = null, enabled = false)
    }
}

@Composable
private fun UnsupportedBadgeRow() {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("已添加根目录列表与添加功能", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        UnsupportedBadge()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentedSelector(
    items: List<String>,
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(modifier) {
        items.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selectedIndex,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, items.size),
            ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// ==================== 数据 ====================
private data class LaunchPreset(
    val name: String,
    val minMemoryMb: Int,
    val maxMemoryMb: Int,
    val gcType: String,
    val useAikarFlags: Boolean,
    val customJvmArgs: String,
)

private val THEME_PRESETS = listOf(
    "default" to 0x3D8BFF,
    "ocean" to 0x0277BD,
    "forest" to 0x2E7D32,
    "sunset" to 0xE65100,
    "lavender" to 0x6A1B9A,
    "sakura" to 0xD81B60,
    "midnight" to 0x263238,
)

private val THEME_PRESET_NAMES = mapOf(
    "default" to "默认", "ocean" to "海洋", "forest" to "森林",
    "sunset" to "日落", "lavender" to "薰衣草", "sakura" to "樱花", "midnight" to "午夜",
)

private val ACCENT_PRESETS = listOf(
    0x3D8BFF to "天空蓝", 0x55C57A to "薄荷", 0xFA8C16 to "琥珀",
    0xE91E63 to "玫瑰", 0x9C27B0 to "紫罗兰", 0xF44336 to "猩红",
    0x00BCD4 to "青色", 0x8BC34A to "草绿", 0xFFC107 to "金色",
    0x795548 to "棕色", 0x607D8B to "石板灰", 0x000000 to "黑色",
)
