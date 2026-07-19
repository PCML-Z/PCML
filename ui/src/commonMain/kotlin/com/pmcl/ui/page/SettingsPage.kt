package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.viewmodel.LauncherViewModel

@Composable
fun SettingsPage(vm: LauncherViewModel) {
    val pref = remember { vm.preferences }
    val themeState = LocalThemeState.current

    var minMem by remember { mutableStateOf(pref.getMinMemoryMb().toString()) }
    var maxMem by remember { mutableStateOf(pref.getMaxMemoryMb().toString()) }
    var gcType by remember { mutableStateOf(pref.getGcType()) }
    var useAikar by remember { mutableStateOf(pref.isUseAikarFlags()) }
    var customArgs by remember { mutableStateOf(pref.getCustomJvmArgs()) }
    var language by remember { mutableStateOf(pref.getLanguage()) }
    var borderless by remember { mutableStateOf(pref.isBorderlessWindow()) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("设置", style = MaterialTheme.typography.headlineSmall,
             fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // 内存
        Card(Modifier.fillMaxWidth(), colors = glassCardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text("内存", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = minMem,
                        onValueChange = {
                            minMem = it
                            it.toIntOrNull()?.let { v -> pref.setMinMemoryMb(v) }
                        },
                        label = { Text("最小 (MB)") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxMem,
                        onValueChange = {
                            maxMem = it
                            it.toIntOrNull()?.let { v -> pref.setMaxMemoryMb(v) }
                        },
                        label = { Text("最大 (MB)") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("推荐最大：${com.pmcl.core.runtime.RuntimeManager().getRecommendedMaxMemoryMb()} MB",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }

        Spacer(Modifier.height(16.dp))

        // JVM 高级配置
        Card(Modifier.fillMaxWidth(), colors = glassCardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text("JVM 高级配置", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                Text("GC 类型", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                val gcItems = listOf("G1GC", "ZGC", "ShenandoahGC", "ParallelGC")
                com.pmcl.ui.animation.AnimatedSegmentedSelector(
                    items = gcItems,
                    selectedIndex = gcItems.indexOf(gcType).coerceAtLeast(0),
                    onSelect = { gcType = gcItems[it]; pref.setGcType(gcItems[it]) },
                    fillWidth = true
                )

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = useAikar, onCheckedChange = { useAikar = it; pref.setUseAikarFlags(it) })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Aikar's Flags")
                        Text("社区公认的 MC 优化参数集（推荐开启）",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = customArgs,
                    onValueChange = {
                        customArgs = it
                        pref.setCustomJvmArgs(it)
                    },
                    label = { Text("自定义 JVM 参数（空格分隔）") },
                    supportingText = { Text("如 -Dminecraft.api.env=custom，将追加在最后") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 启动预设
        LaunchPresetCard(vm, pref) { applyPresetName ->
            // 应用预设后同步本地 UI 状态
            minMem = pref.getMinMemoryMb().toString()
            maxMem = pref.getMaxMemoryMb().toString()
            gcType = pref.getGcType()
            useAikar = pref.isUseAikarFlags()
            customArgs = pref.getCustomJvmArgs()
        }

        Spacer(Modifier.height(16.dp))

        // 游戏通用行为
        GameBehaviorCard(pref)

        Spacer(Modifier.height(16.dp))

        // 澪模式
        MioModeCard(pref)

        Spacer(Modifier.height(16.dp))

        // Java 运行时管理
        JavaRuntimeCard(vm, pref)

        Spacer(Modifier.height(16.dp))

        // 外观
        Card(Modifier.fillMaxWidth(), colors = glassCardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text("外观", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = themeState.useDark,
                        onCheckedChange = { v ->
                            // 修复：切换深浅模式时重新生成莫奈/自定义配色
                            vm.onThemeModeChanged(v, themeState)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (themeState.useDark) "深色主题" else "浅色主题")
                }
                Spacer(Modifier.height(4.dp))
                Text("主题偏好已持久化到 ~/.pmcl/preferences.json，重启后保留",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 莫奈取色（主题颜色跟随桌面壁纸）
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = themeState.dynamicColor,
                        onCheckedChange = { v ->
                            themeState.enableDynamicColor(v)
                            pref.setDynamicColor(v)
                            if (v) {
                                // 开启莫奈时清除自定义强调色
                                vm.clearCustomAccentColor(themeState)
                                vm.refreshWallpaperColor(themeState)
                            } else {
                                themeState.updateDynamicColorScheme(null)
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("莫奈取色", fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text("开启后主题颜色自动从桌面壁纸提取，实现 Material You 动态配色",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 自定义强调色（手动色板选择）
                Text("自定义强调色", style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                AccentColorPicker(
                    selectedColor = themeState.customAccentColor,
                    enabled = !themeState.dynamicColor,
                    onSelect = { argb ->
                        vm.applyCustomAccentColor(argb, themeState)
                    },
                    onClear = {
                        vm.clearCustomAccentColor(themeState)
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text("手动选择主题强调色，关闭莫奈取色后生效。选择后自动生成协调的完整配色方案",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text("语言 / Language / 言語", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                val langItems = listOf("zh_CN" to "简体中文", "en_US" to "English", "ja_JP" to "日本語")
                com.pmcl.ui.animation.AnimatedSegmentedSelector(
                    items = langItems.map { it.second },
                    selectedIndex = langItems.indexOfFirst { it.first == language }.coerceAtLeast(0),
                    onSelect = {
                        language = langItems[it].first
                        vm.setLanguage(langItems[it].first)
                    },
                    fillWidth = true
                )
                Text("切换语言后部分界面需重启 UI 完全生效",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 无边框窗口模式
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = borderless,
                        onCheckedChange = { v ->
                            borderless = v
                            pref.setBorderlessWindow(v)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("无边框窗口", fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text("开启后使用自定义标题栏，重启启动器后生效",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 视差背景主题
                val parallaxBg by vm.parallaxBackground.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = parallaxBg,
                        onCheckedChange = { v -> vm.setParallaxBackground(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("视差背景", fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text("多层渐变球随鼠标偏移产生 3D 视差效果，实时生效",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 玻璃主题
                val glassOn by vm.glassTheme.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = glassOn,
                        onCheckedChange = { v -> vm.setGlassTheme(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("玻璃主题", fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text("卡片毛玻璃效果，搭配视差背景视觉更佳，实时生效",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // UI 缩放
                var uiScale by remember { mutableStateOf(pref.getUiScale()) }
                Text("界面缩放", style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("小", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = uiScale,
                        onValueChange = { v ->
                            uiScale = v
                            themeState.applyUiScale(v)
                            pref.setUiScale(v)
                        },
                        valueRange = 0.8f..1.5f,
                        steps = 13,  // 0.05 步长：(1.5-0.8)/0.05 = 14 段 → 13 个步进点
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("大", style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(12.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "${"%.0f".format(uiScale * 100)}%",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = {
                        uiScale = 1.0f
                        themeState.applyUiScale(1.0f)
                        pref.setUiScale(1.0f)
                    }) { Text("重置为 100%") }
                }
                Text("调整界面字体和元素大小，实时生效",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 性能 HUD 浮窗
                val showHud by vm.perfHudVisible.collectAsState()
                val hudMetrics by vm.perfHudMetrics.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = showHud,
                        onCheckedChange = { v -> vm.setPerfHudVisible(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("性能 HUD 浮窗", fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text("显示半透明置顶的性能监控小窗，可拖动到任意位置，实时显示 CPU/内存/GPU/FPS",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                if (showHud) {
                    Spacer(Modifier.height(8.dp))
                    Text("显示指标", style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    val metricOptions = listOf("CPU" to "CPU", "MEM" to "内存", "GPU" to "GPU", "FPS" to "FPS")
                    val selectedMetrics = remember(hudMetrics) {
                        hudMetrics.split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toMutableSet()
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        metricOptions.forEach { (key, label) ->
                            FilterChip(
                                selected = key in selectedMetrics,
                                onClick = {
                                    if (key in selectedMetrics && selectedMetrics.size > 1) {
                                        selectedMetrics.remove(key)
                                    } else {
                                        selectedMetrics.add(key)
                                    }
                                    vm.setPerfHudMetrics(selectedMetrics.joinToString(","))
                                },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 网络配置
        NetworkConfigCard(vm, pref)

        Spacer(Modifier.height(16.dp))

        // 系统信息
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = glassCardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text("系统信息", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(vm.systemInfo,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Text("工作目录：${vm.config.getWorkDir()}",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }

        Spacer(Modifier.height(16.dp))

        // 关于
        AboutCard(vm)
    }
}

@Composable
private fun AboutCard(vm: LauncherViewModel) {
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showAgreementDialog by remember { mutableStateOf(false) }
    var showDisclaimerDialog by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), colors = glassCardColors()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Image(
                        painter = painterResource("pmcl_icon.png"),
                        contentDescription = "PMCL 图标",
                        modifier = Modifier.size(48.dp).padding(2.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("PMCL", style = MaterialTheme.typography.titleMedium,
                         fontWeight = FontWeight.Bold)
                    Text("版本 1.0.0 (build 20260708.4)",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("一个使用 Compose Multiplatform UI + Java 内核构建的跨平台 Minecraft 启动器。",
                 style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(12.dp))
            Text("主要功能", style = MaterialTheme.typography.labelMedium,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val features = listOf(
                "微软账号登录（OAuth2 设备码流）",
                "版本安装与库/资源下载",
                "Forge / Fabric / Quilt 模组加载器",
                "Modrinth / CurseForge 模组市场",
                "陶瓦联机（EasyTier P2P）",
                "Minecraft.net 新闻",
                "世界 / 截图 / 资源包 / 数据包管理",
                "崩溃分析与完整性校验"
            )
            features.forEach { f ->
                Text("·  $f",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(12.dp))
            Text("技术栈", style = MaterialTheme.typography.labelMedium,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Kotlin ${KotlinVersion.CURRENT}  ·  Compose Multiplatform 1.7.0  ·  Java 17 内核 / Java 21 运行时  ·  OkHttp  ·  Gradle",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try { com.pmcl.core.web.WikiBrowser.open("https://github.com/peddlejumper") } catch (_: Throwable) {}
                }) { Text("作者 GitHub"); Icon(Icons.Filled.OpenInNew, null, modifier = Modifier.size(14.dp).padding(start = 2.dp)) }
                OutlinedButton(onClick = { showLicenseDialog = true }) {
                    Icon(Icons.Filled.Article, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("查看许可证")
                }
                OutlinedButton(onClick = { showAgreementDialog = true }) {
                    Icon(Icons.Filled.Gavel, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("用户协议")
                }
                OutlinedButton(onClick = { showDisclaimerDialog = true }) {
                    Icon(Icons.Filled.Shield, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("免责协议")
                }
                OutlinedButton(onClick = {
                    try { com.pmcl.core.web.WikiBrowser.open("https://github.com/EasyTier/EasyTier") } catch (_: Throwable) {}
                }) { Text("EasyTier 项目") }
                OutlinedButton(onClick = {
                    try { com.pmcl.core.web.WikiBrowser.open("https://modrinth.com") } catch (_: Throwable) {}
                }) { Text("Modrinth") }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("© 2026 PMCL  ·  仅供学习交流使用  ·  Minecraft 为 Mojang AB 商标",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }
    }

    if (showLicenseDialog) {
        LicenseViewerDialog(onDismiss = { showLicenseDialog = false })
    }
    if (showAgreementDialog) {
        DocumentViewerDialog(
            title = "PMCL 用户协议",
            resourceName = "USER_AGREEMENT.txt",
            onDismiss = { showAgreementDialog = false }
        )
    }
    if (showDisclaimerDialog) {
        DocumentViewerDialog(
            title = "PMCL 免责协议",
            resourceName = "DISCLAIMER.txt",
            onDismiss = { showDisclaimerDialog = false }
        )
    }
}

@Composable
private fun LaunchPresetCard(
    vm: LauncherViewModel,
    pref: com.pmcl.core.preferences.Preferences,
    onPresetApplied: (String) -> Unit
) {
    val presets by vm.launchPresets.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { vm.refreshLaunchPresets() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bookmarks, null, Modifier.size(20.dp),
                     tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("启动预设", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(onClick = { presetName = ""; showSaveDialog = true }) {
                    Text("保存当前为预设")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("保存多套启动参数（如「纯净」vs「modded 加额外参数」），一键切换。",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            if (presets.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                presets.forEach { p ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "${p.maxMemoryMb}MB | ${p.gcType}" +
                                    if (p.useAikarFlags) " | Aikar" else "" +
                                    if (p.customJvmArgs.isNotEmpty()) " | +JVM" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            OutlinedButton(onClick = {
                                vm.applyLaunchPreset(p.name)
                                onPresetApplied(p.name)
                            }) { Text("应用") }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { vm.deleteLaunchPreset(p.name) }) {
                                Icon(Icons.Filled.Delete, "删除预设",
                                     tint = MaterialTheme.colorScheme.error,
                                     modifier = Modifier.size(18.dp))
                            }
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
                    Text("为当前启动参数命名：", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text("预设名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (presets.any { it.name == presetName.trim() }) {
                        Spacer(Modifier.height(4.dp))
                        Text("同名预设将被覆盖",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (presetName.isNotBlank()) {
                        vm.saveLaunchPreset(presetName)
                        showSaveDialog = false
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun MioModeCard(pref: com.pmcl.core.preferences.Preferences) {
    var enabled by remember { mutableStateOf(pref.isMioModeEnabled()) }
    var l1Jvm by remember { mutableStateOf(pref.isMioModeJvm()) }
    var l1LargePages by remember { mutableStateOf(pref.isMioModeLargePages()) }
    var l1Zgc by remember { mutableStateOf(pref.isMioModeZgc()) }
    var l1Render by remember { mutableStateOf(pref.isMioModeRenderOpt()) }
    var l1Jit by remember { mutableStateOf(pref.isMioModeJitAggressive()) }
    var l1Network by remember { mutableStateOf(pref.isMioModeNetworkOpt()) }
    var l1Metaspace by remember { mutableStateOf(pref.isMioModeMetaspace()) }
    var l2Process by remember { mutableStateOf(pref.isMioModeProcess()) }
    var l2Crazy by remember { mutableStateOf(pref.isMioModeCrazyPriority()) }
    var l3System by remember { mutableStateOf(pref.isMioModeSystemPower()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("澪模式", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { v ->
                        enabled = v
                        pref.setMioModeEnabled(v)
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Text("暴力调度 CPU/GPU 性能，抑制低功耗状态，提升调度优先级",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(6.dp))
            Text("注意：软件层无法绕过硬件热保护，本功能只能从避免低功耗状态和提升调度优先级两个角度逼近目标，不能真正突破物理热限制",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.error)

            if (enabled) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // L1：JVM 激进参数
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = l1Jvm,
                        onCheckedChange = { v -> l1Jvm = v; pref.setMioModeJvm(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("L1 JVM 激进参数", fontWeight = FontWeight.Medium)
                        Text("强制 GC 线程数 = CPU 核心数，分配预取优化，JIT final 字段信任",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("零权限零风险，进程退出自动失效。收益约 5-15% 吞吐提升",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))

                // L1+：大页内存 + NUMA
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = l1LargePages,
                        onCheckedChange = { v -> l1LargePages = v; pref.setMioModeLargePages(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("L1+ 大页内存 + NUMA", fontWeight = FontWeight.Medium)
                        Text("UseLargePages + UseNUMA，减少 TLB miss，多路 CPU NUMA 感知",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("JVM 不支持时自动降级为普通页，不会启动失败。需 OS 预分配大页才能完全生效",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))

                // L1+：实验性 ZGC
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = l1Zgc,
                        onCheckedChange = { v -> l1Zgc = v; pref.setMioModeZgc(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("L1+ 实验性 ZGC", fontWeight = FontWeight.Medium)
                        Text("JDK 21 生成式 ZGC，亚毫秒级停顿，自动跳过 Aikar's Flags",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Aikar's Flags 为 G1GC 调校，ZGC 可能负优化 MC 大量区块分配场景。实验性功能，风险自担",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.error)

                Spacer(Modifier.height(12.dp))

                // L1+：LWJGL 渲染加速
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = l1Render,
                        onCheckedChange = { v -> l1Render = v; pref.setMioModeRenderOpt(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("L1+ LWJGL 渲染加速", fontWeight = FontWeight.Medium)
                        Text("高DPI启用 + 系统 malloc + 关闭 debug 输出",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // L1+：JIT 编译器激进
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = l1Jit,
                        onCheckedChange = { v -> l1Jit = v; pref.setMioModeJitAggressive(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("L1+ JIT 编译器激进", fontWeight = FontWeight.Medium)
                        Text("CompileThreshold=5000 + 循环安全点 + 按核心数设编译器线程",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // L1+：网络栈优化
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = l1Network,
                        onCheckedChange = { v -> l1Network = v; pref.setMioModeNetworkOpt(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("L1+ 网络栈优化", fontWeight = FontWeight.Medium)
                        Text("IPv4优先 + 快速路径网络 + DNS缓存30秒（联机场景）",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // L1+：元空间管控
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = l1Metaspace,
                        onCheckedChange = { v -> l1Metaspace = v; pref.setMioModeMetaspace(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("L1+ 元空间管控", fontWeight = FontWeight.Medium)
                        Text("MaxMetaspaceSize=512M + 类数据共享，防 OOM 加速启动",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // L2：进程级调优
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = l2Process,
                        onCheckedChange = { v -> l2Process = v; pref.setMioModeProcess(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("L2 进程级调优", fontWeight = FontWeight.Medium)
                        Text("macOS: taskpolicy 提升 QoS + caffeinate 防休眠；Windows/Linux: 提优先级",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("无需 sudo，游戏退出自动清理 caffeinate 子进程",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))

                // L2+：疯狂优先级
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = l2Crazy,
                        onCheckedChange = { v -> l2Crazy = v; pref.setMioModeCrazyPriority(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("L2+ 疯狂调度优先级", fontWeight = FontWeight.Medium)
                        Text("macOS: taskpolicy -P high + renice -20；Windows: REALTIME；Linux: renice -20",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("拉到系统调度优先级极限。macOS/Linux 需 sudo 授权，Windows 用 REALTIME 可能导致鼠标键盘卡顿",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.error)

                Spacer(Modifier.height(12.dp))

                // L3：系统电源策略
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = l3System,
                        onCheckedChange = { v -> l3System = v; pref.setMioModeSystemPower(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("L3 系统电源策略", fontWeight = FontWeight.Medium)
                        Text("关闭 macOS 低电量模式，游戏退出后自动恢复",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("启动时会弹原生授权框请求管理员密码，影响整机电源策略",
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun GameBehaviorCard(pref: com.pmcl.core.preferences.Preferences) {
    var width by remember { mutableStateOf(pref.getGameWindowWidth().toString()) }
    var height by remember { mutableStateOf(pref.getGameWindowHeight().toString()) }
    var renderer by remember { mutableStateOf(pref.getGameRenderer()) }
    var fullscreen by remember { mutableStateOf(pref.isGameFullscreen()) }
    var demo by remember { mutableStateOf(pref.isGameDemo()) }
    var serverHost by remember { mutableStateOf(pref.getGameServerHost()) }
    var serverPort by remember { mutableStateOf(pref.getGameServerPort().toString()) }
    var windowIconPath by remember { mutableStateOf(pref.getWindowIconPath()) }
    var versionIsolation by remember { mutableStateOf(pref.isVersionIsolation()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("游戏通用行为", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("应用于所有版本的启动参数，留空使用游戏默认值",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = versionIsolation, onCheckedChange = {
                    versionIsolation = it; pref.setVersionIsolation(it)
                })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("版本隔离")
                    Text("各版本使用独立的 mods/saves/config 目录（~/.pmcl/instances/<版本>/）",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("窗口分辨率", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = width,
                    onValueChange = {
                        width = it
                        it.toIntOrNull()?.let { v -> pref.setGameWindowWidth(v) }
                    },
                    label = { Text("宽度") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = {
                        height = it
                        it.toIntOrNull()?.let { v -> pref.setGameWindowHeight(v) }
                    },
                    label = { Text("高度") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text("对应 --width / --height；全屏模式下不生效",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))
            Text("渲染器", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            val rendererItems = listOf("AUTO" to "自动", "OPENGL" to "OpenGL", "VULKAN" to "Vulkan")
            com.pmcl.ui.animation.AnimatedSegmentedSelector(
                items = rendererItems.map { it.second },
                selectedIndex = rendererItems.indexOfFirst { it.first == renderer }.coerceAtLeast(0),
                onSelect = {
                    renderer = rendererItems[it].first
                    pref.setGameRenderer(rendererItems[it].first)
                },
                fillWidth = true
            )
            Text("对应 --renderer；Vulkan 仅 MC 1.21+ 实验性支持，需配合兼容驱动",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = fullscreen, onCheckedChange = { fullscreen = it; pref.setGameFullscreen(it) })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("全屏启动")
                    Text("对应 --fullscreen，覆盖窗口分辨率设置",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = demo, onCheckedChange = { demo = it; pref.setGameDemo(it) })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("演示模式")
                    Text("对应 --demo，未登录账号时也可用",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("启动后自动连接服务器", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = serverHost,
                    onValueChange = {
                        serverHost = it
                        pref.setGameServerHost(it)
                    },
                    label = { Text("服务器地址") }, singleLine = true,
                    placeholder = { Text("留空不连接") },
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = {
                        serverPort = it
                        it.toIntOrNull()?.let { v -> pref.setGameServerPort(v) }
                    },
                    label = { Text("端口") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text("对应 --server / --port；仅对新世界选择界面生效，已有存档不受影响",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))
            Text(I18n.t("settings.window_icon"), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = windowIconPath,
                onValueChange = {
                    windowIconPath = it
                    pref.setWindowIconPath(it)
                },
                label = { Text(I18n.t("settings.window_icon_path")) },
                singleLine = true,
                placeholder = { Text(I18n.t("settings.window_icon_empty")) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            val fd = java.awt.FileDialog(
                                null as java.awt.Frame?,
                                I18n.t("settings.window_icon_select"),
                                java.awt.FileDialog.LOAD
                            )
                            fd.filenameFilter = java.io.FilenameFilter { _, name ->
                                name.lowercase().endsWith(".png")
                            }
                            fd.isVisible = true
                            if (fd.file != null) {
                                val p = java.io.File(fd.directory, fd.file).absolutePath
                                windowIconPath = p
                                pref.setWindowIconPath(p)
                            }
                        }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = I18n.t("common.browse"))
                        }
                        if (windowIconPath.isNotEmpty()) {
                            IconButton(onClick = {
                                windowIconPath = ""
                                pref.setWindowIconPath("")
                            }) {
                                Icon(Icons.Filled.Clear, contentDescription = I18n.t("common.remove"))
                            }
                        }
                    }
                }
            )
            Text(I18n.t("settings.window_icon_hint"),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun NetworkConfigCard(vm: LauncherViewModel, pref: com.pmcl.core.preferences.Preferences) {
    var mirrorType by remember { mutableStateOf(pref.getMirrorType()) }
    var customMirror by remember { mutableStateOf(pref.getCustomMirrorBase()) }
    var useProxy by remember { mutableStateOf(pref.isUseProxy()) }
    var proxyHost by remember { mutableStateOf(pref.getProxyHost()) }
    var proxyPort by remember { mutableStateOf(pref.getProxyPort().toString()) }
    var useAuth by remember { mutableStateOf(pref.isUseHttpAuth()) }
    var proxyUser by remember { mutableStateOf(pref.getProxyUsername()) }
    var proxyPass by remember { mutableStateOf(pref.getProxyPassword()) }
    var speedLimit by remember { mutableStateOf(pref.getDownloadSpeedLimitKb().toString()) }
    var retryCount by remember { mutableStateOf(pref.getDownloadRetryCount().toString()) }
    var enableResume by remember { mutableStateOf(pref.isEnableResume()) }
    var chunkedThreads by remember { mutableStateOf(pref.getChunkedDownloadThreads().toString()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("网络配置", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            Text("下载镜像源", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            val mirrorItems = listOf("OFFICIAL" to "官方", "BMCLAPI" to "BMCLAPI", "CUSTOM" to "自定义")
            com.pmcl.ui.animation.AnimatedSegmentedSelector(
                items = mirrorItems.map { it.second },
                selectedIndex = mirrorItems.indexOfFirst { it.first == mirrorType }.coerceAtLeast(0),
                onSelect = {
                    mirrorType = mirrorItems[it].first
                    pref.setMirrorType(mirrorItems[it].first)
                    vm.applyNetworkPreferences()
                },
                fillWidth = true
            )
            if (mirrorType == "CUSTOM") {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = customMirror,
                    onValueChange = {
                        customMirror = it
                        pref.setCustomMirrorBase(it)
                        vm.applyNetworkPreferences()
                    },
                    label = { Text("自定义镜像基址 (如 https://mirror.example.com)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useProxy, onCheckedChange = {
                    useProxy = it; pref.setUseProxy(it); vm.applyNetworkPreferences()
                })
                Spacer(Modifier.width(8.dp))
                Text("HTTP 代理")
            }
            if (useProxy) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = {
                            proxyHost = it; pref.setProxyHost(it); vm.applyNetworkPreferences()
                        },
                        label = { Text("主机") }, singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = {
                            proxyPort = it
                            it.toIntOrNull()?.let { v -> pref.setProxyPort(v); vm.applyNetworkPreferences() }
                        },
                        label = { Text("端口") }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = useAuth, onCheckedChange = {
                        useAuth = it; pref.setUseHttpAuth(it); vm.applyNetworkPreferences()
                    })
                    Spacer(Modifier.width(8.dp))
                    Text("代理认证")
                }
                if (useAuth) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = proxyUser,
                            onValueChange = {
                                proxyUser = it; pref.setProxyUsername(it); vm.applyNetworkPreferences()
                            },
                            label = { Text("用户名") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = proxyPass,
                            onValueChange = {
                                proxyPass = it; pref.setProxyPassword(it); vm.applyNetworkPreferences()
                            },
                            label = { Text("密码") }, singleLine = true,
                            modifier = Modifier.weight(1f)
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
                        it.toIntOrNull()?.let { v -> pref.setDownloadSpeedLimitKb(v); vm.applyNetworkPreferences() }
                    },
                    label = { Text("限速 (KB/s, 0=不限)") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = retryCount,
                    onValueChange = {
                        retryCount = it
                        it.toIntOrNull()?.let { v -> pref.setDownloadRetryCount(v); vm.applyNetworkPreferences() }
                    },
                    label = { Text("重试次数") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = chunkedThreads,
                    onValueChange = {
                        chunkedThreads = it
                        it.toIntOrNull()?.let { v -> pref.setChunkedDownloadThreads(v); vm.applyNetworkPreferences() }
                    },
                    label = { Text("分片下载连接数") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text("分片下载对大于 8MB 的文件启用多连接并行下载",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enableResume, onCheckedChange = {
                    enableResume = it; pref.setEnableResume(it); vm.applyNetworkPreferences()
                })
                Spacer(Modifier.width(8.dp))
                Text("断点续传（.part 文件）")
            }
        }
    }
}

@Composable
private fun JavaRuntimeCard(vm: LauncherViewModel, pref: com.pmcl.core.preferences.Preferences) {
    val downloading by vm.javaDownloading.collectAsState()
    val dlStatus by vm.javaDownloadStatus.collectAsState()
    var manualPath by remember { mutableStateOf(pref.getJavaPath()) }
    val detectedPath = remember { vm.detectJavaPath() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Java 运行时", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            // 当前检测到的 Java 路径
            Text("当前 Java：$detectedPath",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            Text("MC 1.20.5+ 需要 Java 21；MC 1.17–1.20.4 需要 Java 17；MC 1.12.2 及更早（含 alpha/beta）需要 Java 8。",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))

            // 龙芯/RISC-V 架构检测：Mojang 清单无对应 Java，禁用自动下载
            val isLoongson = com.pmcl.core.launch.JavaRuntimeFinder.isLoongson()
            val isRiscV = com.pmcl.core.launch.JavaRuntimeFinder.isRiscV()
            if (isLoongson || isRiscV) {
                val archName = when {
                    com.pmcl.core.launch.JavaRuntimeFinder.isLoongArch64() -> "LoongArch64"
                    com.pmcl.core.launch.JavaRuntimeFinder.isMips64el() -> "MIPS64el"
                    com.pmcl.core.launch.JavaRuntimeFinder.isRiscV64() -> "RISC-V 64"
                    else -> "未知"
                }
                val (downloadUrl, downloadLabel) = when {
                    isLoongson -> "https://www.loongnix.cn/zh/api/java/" to "前往龙芯开源社区"
                    isRiscV -> "https://adoptium.net/temurin/releases/?version=17&arch=riscv64" to "前往 Adoptium"
                    else -> "" to ""
                }
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "$archName 架构不支持自动下载 Java",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Mojang Java 运行时清单不包含 $archName 架构。请从对应开源社区手动安装 $archName 版 JDK，PMCL 会自动检测系统中的 Java。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                try {
                                    if (System.getProperty("os.name", "").lowercase().contains("linux")) {
                                        Runtime.getRuntime().exec(arrayOf("xdg-open", downloadUrl))
                                    } else {
                                        java.awt.Desktop.getDesktop().browse(java.net.URI(downloadUrl))
                                    }
                                } catch (_: Throwable) {}
                            }
                        ) {
                            Icon(Icons.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(downloadLabel)
                        }
                    }
                }
            } else {
                // 一键下载 Java 8 / 17 / 21
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.downloadJava(8) },
                        enabled = !downloading
                    ) { Text("Java 8") }
                    OutlinedButton(
                        onClick = { vm.downloadJava(17) },
                        enabled = !downloading
                    ) { Text("Java 17") }
                    Button(
                        onClick = { vm.downloadJava(21) },
                        enabled = !downloading
                    ) {
                        if (downloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("正在下载…")
                        } else {
                            Text("Java 21")
                        }
                    }
                }
            }
            if (downloading || dlStatus.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(dlStatus,
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }

            Spacer(Modifier.height(12.dp))

            // 手动指定 Java 路径
            OutlinedTextField(
                value = manualPath,
                onValueChange = {
                    manualPath = it
                    vm.setJavaPath(it.trim())
                },
                label = { Text("手动指定 Java 可执行文件路径") },
                supportingText = { Text("留空则自动检测（优先 runtimes 目录，再系统路径）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 强调色选择器：预设色板 + 自定义颜色输入。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AccentColorPicker(
    selectedColor: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onClear: () -> Unit
) {
    // 预设色板（RGB，不含 alpha）
    val presets = remember {
        listOf(
            0x3D8BFF to "天空蓝",   // 默认蓝
            0x55C57A to "薄荷绿",
            0xFA8C16 to "琥珀橙",
            0xE91E63 to "玫瑰粉",
            0x9C27B0 to "紫罗兰",
            0xF44336 to "赤红",
            0x00BCD4 to "青蓝",
            0x8BC34A to "草绿",
            0xFFC107 to "金黄",
            0x795548 to "棕褐",
            0x607D8B to "蓝灰",
            0x000000 to "纯黑"
        )
    }

    val currentRgb = if (selectedColor != -1) selectedColor and 0x00FFFFFF else -1

    Column(Modifier.fillMaxWidth()) {
        // 预设色板网格
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            presets.forEach { (rgb, name) ->
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
                        .clickable(enabled = enabled) { onSelect(rgb) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = name,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 自定义颜色输入 + 恢复默认按钮
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            var hexInput by remember(selectedColor) {
                mutableStateOf(
                    if (selectedColor != -1) String.format("%06X", selectedColor and 0x00FFFFFF)
                    else ""
                )
            }
            OutlinedTextField(
                value = hexInput,
                onValueChange = { v ->
                    val cleaned = v.filter { it.isLetterOrDigit() }.take(6)
                    hexInput = cleaned
                    if (cleaned.length == 6) {
                        val rgb = cleaned.toInt(16)
                        onSelect(rgb)
                    }
                },
                label = { Text("自定义 HEX") },
                prefix = { Text("#") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.width(8.dp))
            if (selectedColor != -1) {
                OutlinedButton(onClick = onClear, enabled = enabled) {
                    Icon(Icons.Filled.Clear, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("默认")
                }
            }
        }

        if (!enabled) {
            Spacer(Modifier.height(4.dp))
            Text("请先关闭莫奈取色以使用自定义强调色",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * 许可证查看器对话框：支持中英文切换、滚动阅读、复制全文。
 * 从 JAR 资源中加载 LICENSE.zh.txt / LICENSE.en.txt。
 */
@Composable
private fun LicenseViewerDialog(onDismiss: () -> Unit) {
    var isEnglish by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    // 懒加载许可证文本
    val licenseText by remember(isEnglish) {
        mutableStateOf(
            runCatching {
                val resName = if (isEnglish) "LICENSE.en.txt" else "LICENSE.zh.txt"
                Thread.currentThread().contextClassLoader
                    ?.getResourceAsStream(resName)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "许可证文件未找到。"
            }.getOrElse { "加载许可证失败：${it.message}" }
        )
    }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Article, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("PMCL 软件技术许可证 v1.1")
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                // 语言切换 + 复制按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row {
                        FilterChip(
                            selected = !isEnglish,
                            onClick = { isEnglish = false },
                            label = { Text("中文") }
                        )
                        Spacer(Modifier.width(6.dp))
                        FilterChip(
                            selected = isEnglish,
                            onClick = { isEnglish = true },
                            label = { Text("English") }
                        )
                    }
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(licenseText))
                    }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制")
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 许可证正文（可滚动）
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(420.dp)
                ) {
                    Text(
                        text = licenseText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .padding(12.dp)
                    )
                }

                Spacer(Modifier.height(4.dp))
                if (isEnglish) {
                    Text(
                        "此为英文参考翻译,中文版本为权威版本(见第 13.4 条)。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        "此为中文权威版本。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * 通用文档查看器对话框:从 JAR 资源加载文本并展示,支持滚动阅读和复制全文。
 * 用于《用户协议》《免责协议》等单语言文档。
 */
@Composable
private fun DocumentViewerDialog(
    title: String,
    resourceName: String,
    onDismiss: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    val docText by remember(resourceName) {
        mutableStateOf(
            runCatching {
                Thread.currentThread().contextClassLoader
                    ?.getResourceAsStream(resourceName)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "文档未找到:$resourceName"
            }.getOrElse { "加载失败:${it.message}" }
        )
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        clipboardManager.setText(AnnotatedString(docText))
                    }) {
                        Icon(Icons.Filled.ContentCopy, null, Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("复制全文")
                    }
                }

                Spacer(Modifier.height(4.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(440.dp)
                ) {
                    Text(
                        text = docText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .verticalScroll(scrollState)
                            .padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}
