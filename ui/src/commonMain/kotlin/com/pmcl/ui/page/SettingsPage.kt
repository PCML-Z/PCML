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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
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
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
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
        Text(I18n.t("settings.title"), style = MaterialTheme.typography.headlineSmall,
             fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // 内存
        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp)) {
                Text(I18n.t("settings.memory"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = minMem,
                        onValueChange = {
                            // M40 修复：原代码输入 "abc" 时 minMem="abc" 但 toIntOrNull 返回 null，
                            // pref 不更新，导致 UI 显示 "abc" 与持久化状态不一致。
                            // 改为：仅当输入为空或合法整数时才更新 UI 状态；非法输入保持上一次值。
                            // 空输入允许（用户清空准备重新输入），但持久化用 0 兜底。
                            if (it.isEmpty() || it.toIntOrNull() != null) {
                                minMem = it
                                val v = it.toIntOrNull() ?: 0
                                if (v >= 0) pref.setMinMemoryMb(v)
                            }
                        },
                        label = { Text(I18n.t("settings.min_memory")) }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = maxMem,
                        onValueChange = {
                            // M40 修复：同上，非法输入不更新 UI 与持久化
                            if (it.isEmpty() || it.toIntOrNull() != null) {
                                maxMem = it
                                val v = it.toIntOrNull() ?: 0
                                if (v >= 0) pref.setMaxMemoryMb(v)
                            }
                        },
                        label = { Text(I18n.t("settings.max_memory")) }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("settings.recommended_max", com.pmcl.core.runtime.RuntimeManager().getRecommendedMaxMemoryMb()),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }

        Spacer(Modifier.height(16.dp))

        // JVM 高级配置
        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp)) {
                Text(I18n.t("settings.jvm_advanced"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                Text(I18n.t("settings.gc_type"), style = MaterialTheme.typography.labelMedium)
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
                        Text(I18n.t("settings.aikar"))
                        Text(I18n.t("settings.aikar_desc"),
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
                    label = { Text(I18n.t("settings.custom_args")) },
                    supportingText = { Text(I18n.t("settings.custom_args_hint")) },
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

        // Minecraft 根目录管理
        MinecraftRootsCard(vm)

        Spacer(Modifier.height(16.dp))

        // 澪模式
        MioModeCard(pref)

        Spacer(Modifier.height(16.dp))

        // Java 运行时管理
        JavaRuntimeCard(vm, pref)

        Spacer(Modifier.height(16.dp))

        // 外观
        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp)) {
                Text(I18n.t("settings.appearance"), style = MaterialTheme.typography.titleSmall,
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
                    Text(if (themeState.useDark) I18n.t("settings.dark_theme") else I18n.t("settings.light_theme"))
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.theme_persisted"),
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
                                // 开启莫奈时清除自定义强调色，并强制重新取色
                                vm.clearCustomAccentColor(themeState)
                                vm.forceRefreshWallpaperColor(themeState)
                            } else {
                                themeState.updateDynamicColorScheme(null)
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.t("settings.monet_color"), fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    if (themeState.dynamicColor) {
                        TextButton(
                            onClick = { vm.forceRefreshWallpaperColor(themeState) },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                        ) {
                            Text(I18n.t("settings.reextract"), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.monet_color_desc"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 自定义强调色（手动色板选择）
                Text(I18n.t("settings.custom_accent"), style = MaterialTheme.typography.labelMedium,
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
                Text(I18n.t("settings.custom_accent_desc"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                Text(I18n.t("settings.language_label"), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                val langItems = listOf(
                    "zh_CN" to "简体中文",
                    "zh_TW" to "繁體中文",
                    "en_US" to "English",
                    "ja_JP" to "日本語",
                    "ud_EN" to "uʍop-ǝpᴉsdn"
                )
                com.pmcl.ui.animation.AnimatedSegmentedSelector(
                    items = langItems.map { it.second },
                    selectedIndex = langItems.indexOfFirst { it.first == language }.coerceAtLeast(0),
                    onSelect = {
                        language = langItems[it].first
                        vm.setLanguage(langItems[it].first)
                    },
                    fillWidth = true
                )
                Text(I18n.t("settings.language_hint"),
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
                    Text(I18n.t("settings.borderless_window"), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.borderless_window_desc"),
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
                    Text(I18n.t("settings.parallax_background"), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.parallax_background_desc"),
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
                    Text(I18n.t("settings.glass_theme"), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.glass_theme_desc"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 锁屏启动页主题（Origin OS2 风格方形卡片启动页）
                val lockscreenOn by vm.lockscreenLaunchTheme.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = lockscreenOn,
                        onCheckedChange = { v -> vm.setLockscreenLaunchTheme(v) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.t("settings.lockscreen_launch"), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.lockscreen_launch_desc"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // 预判启动：贝叶斯模型预测最可能的版本，进入启动页时后台预热资源
                var predictiveLaunch by remember { mutableStateOf(pref.isPredictiveLaunch()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = predictiveLaunch,
                        onCheckedChange = { v ->
                            predictiveLaunch = v
                            pref.setPredictiveLaunch(v)
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.t("settings.predictive_launch"), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.predictive_launch_desc"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                // UI 缩放
                var uiScale by remember { mutableStateOf(pref.getUiScale()) }
                Text(I18n.t("settings.ui_scale"), style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(I18n.t("settings.ui_scale_small"), style = MaterialTheme.typography.labelSmall,
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
                    Text(I18n.t("settings.ui_scale_large"), style = MaterialTheme.typography.labelSmall,
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
                    }) { Text(I18n.t("settings.ui_scale_reset")) }
                }
                Text(I18n.t("settings.ui_scale_desc"),
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
                    Text(I18n.t("settings.perf_hud"), fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.perf_hud_desc"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)

                if (showHud) {
                    Spacer(Modifier.height(8.dp))
                    Text(I18n.t("settings.perf_hud_metrics"), style = MaterialTheme.typography.labelMedium,
                         fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    val metricOptions = listOf("CPU" to "CPU", "MEM" to I18n.t("settings.memory"), "GPU" to "GPU", "FPS" to "FPS")
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
        Card(Modifier.fillMaxWidth().glassCardBorder(8.dp), shape = RoundedCornerShape(8.dp), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp)) {
                Text(I18n.t("settings.system_info"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(vm.systemInfo,
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("settings.work_dir_value", vm.config.getWorkDir()),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
            }
        }

        Spacer(Modifier.height(16.dp))

        // GitHub Release 同步更新
        GithubSyncCard(vm, pref)

        Spacer(Modifier.height(16.dp))

        // 关于
        AboutCard(vm)
    }
}

@Composable
private fun GithubSyncCard(vm: LauncherViewModel, pref: com.pmcl.core.preferences.Preferences) {
    var syncEnabled by remember { mutableStateOf(pref.isGithubSyncEnabled()) }
    var repoInput by remember { mutableStateOf(pref.getGithubRepo()) }
    val syncActive by vm.syncActive.collectAsState()
    val pushStatusText by vm.pushStatusText.collectAsState()

    Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
        Column(Modifier.padding(16.dp)) {
            Text("GitHub Release 同步", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("直接同步 GitHub Release：启动器定时轮询指定仓库的最新 Release，发现新版本时主动通知（无需独立推送服务器）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = syncEnabled, onCheckedChange = {
                    syncEnabled = it
                    vm.setGithubSyncEnabled(it)
                })
                Spacer(Modifier.width(8.dp))
                Text("启用 GitHub Release 同步")
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = repoInput,
                onValueChange = { repoInput = it },
                label = { Text("GitHub 仓库") },
                placeholder = { Text("owner/repo") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { vm.setGithubRepo(repoInput.trim()) }) {
                        Icon(Icons.Filled.Check, contentDescription = "保存仓库")
                    }
                }
            )
            Spacer(Modifier.height(4.dp))
            Text("格式: owner/repo（如 peddlejumper/PMCL）。Release 资产中需包含名称带 pmcl 字样的 .jar 文件。每 30 分钟检查一次。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(8.dp))
            // 同步状态指示
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp).background(
                        color = if (syncActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (pushStatusText.isEmpty()) {
                        if (syncEnabled) "等待检查..." else "未启用"
                    } else pushStatusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun AboutCard(vm: LauncherViewModel) {
    var showLicenseDialog by remember { mutableStateOf(false) }
    var showAgreementDialog by remember { mutableStateOf(false) }
    var showDisclaimerDialog by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth().glassCardBorder(8.dp), shape = RoundedCornerShape(8.dp), colors = glassCardColors(), elevation = glassCardElevation()) {
        Column(Modifier.padding(16.dp)) {
            // === 头部：Logo + 名称 + 版本 ===
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Image(
                        painter = painterResource("pmcl_icon.png"),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).padding(2.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(I18n.t("about.title"), style = MaterialTheme.typography.titleMedium,
                         fontWeight = FontWeight.Bold)
                    Text(I18n.t("about.version", "1.0.0 (build 20260719.1)"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(I18n.t("about.description"),
                 style = MaterialTheme.typography.bodySmall)

            // === 主要功能：4 列分组 ===
            Spacer(Modifier.height(16.dp))
            Text(I18n.t("about.features"), style = MaterialTheme.typography.labelMedium,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FeatureColumn(
                    title = I18n.t("about.features.core"),
                    items = listOf(
                        I18n.t("about.feat.account"),
                        I18n.t("about.feat.versions"),
                        I18n.t("about.feat.modloaders"),
                        I18n.t("about.feat.presets")
                    ),
                    modifier = Modifier.weight(1f)
                )
                FeatureColumn(
                    title = I18n.t("about.features.content"),
                    items = listOf(
                        I18n.t("about.feat.market"),
                        I18n.t("about.feat.contents"),
                        I18n.t("about.feat.config")
                    ),
                    modifier = Modifier.weight(1f)
                )
                FeatureColumn(
                    title = I18n.t("about.features.tools"),
                    items = listOf(
                        I18n.t("about.feat.saves"),
                        I18n.t("about.feat.nbt"),
                        I18n.t("about.feat.crash"),
                        I18n.t("about.feat.java")
                    ),
                    modifier = Modifier.weight(1f)
                )
                FeatureColumn(
                    title = I18n.t("about.features.extensions"),
                    items = listOf(
                        I18n.t("about.feat.multiplayer"),
                        I18n.t("about.feat.friends"),
                        I18n.t("about.feat.video"),
                        I18n.t("about.feat.music"),
                        I18n.t("about.feat.plugins"),
                        I18n.t("about.feat.news"),
                        I18n.t("about.feat.terminal")
                    ),
                    modifier = Modifier.weight(1f)
                )
            }

            // === 技术栈：表格 ===
            Spacer(Modifier.height(16.dp))
            Text(I18n.t("about.tech_stack"), style = MaterialTheme.typography.labelMedium,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            TechStackTable()

            // === 链接按钮 ===
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    try { com.pmcl.core.web.WikiBrowser.open("https://github.com/peddlejumper") } catch (_: Throwable) {}
                }) { Text(I18n.t("about.author_github")); Icon(Icons.Filled.OpenInNew, null, modifier = Modifier.size(14.dp).padding(start = 2.dp)) }
                OutlinedButton(onClick = { showLicenseDialog = true }) {
                    Icon(Icons.Filled.Article, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("about.view_license"))
                }
                OutlinedButton(onClick = { showAgreementDialog = true }) {
                    Icon(Icons.Filled.Gavel, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("about.user_agreement"))
                }
                OutlinedButton(onClick = { showDisclaimerDialog = true }) {
                    Icon(Icons.Filled.Shield, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("about.disclaimer"))
                }
                OutlinedButton(onClick = {
                    try { com.pmcl.core.web.WikiBrowser.open("https://github.com/EasyTier/EasyTier") } catch (_: Throwable) {}
                }) { Text(I18n.t("about.easytier")) }
                OutlinedButton(onClick = {
                    try { com.pmcl.core.web.WikiBrowser.open("https://modrinth.com") } catch (_: Throwable) {}
                }) { Text("Modrinth") }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(I18n.t("about.copyright"),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }
    }

    if (showLicenseDialog) {
        LicenseViewerDialog(onDismiss = { showLicenseDialog = false })
    }
    if (showAgreementDialog) {
        DocumentViewerDialog(
            title = I18n.t("about.user_agreement"),
            resourceName = "USER_AGREEMENT.txt",
            onDismiss = { showAgreementDialog = false }
        )
    }
    if (showDisclaimerDialog) {
        DocumentViewerDialog(
            title = I18n.t("about.disclaimer"),
            resourceName = "DISCLAIMER.txt",
            onDismiss = { showDisclaimerDialog = false }
        )
    }
}

/** 关于卡片中的功能分组列 */
@Composable
private fun FeatureColumn(
    title: String,
    items: List<String>,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.labelSmall,
             fontWeight = FontWeight.SemiBold,
             color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        items.forEach { f ->
            Text("·  $f",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 关于卡片中的技术栈表格 */
@Composable
private fun TechStackTable() {
    val rows = listOf(
        Triple("Kotlin", KotlinVersion.CURRENT.toString(), I18n.t("about.tech.kotlin")),
        Triple("Compose Multiplatform", "1.7.0", I18n.t("about.tech.compose")),
        Triple("Java", "21", I18n.t("about.tech.java")),
        Triple("OkHttp", "4.12.0", I18n.t("about.tech.okhttp")),
        Triple("FFmpeg", "7.1-1.5.11", I18n.t("about.tech.ffmpeg")),
        Triple("Gradle", "8.10", I18n.t("about.tech.gradle")),
        Triple("Gson", "2.11.0", I18n.t("about.tech.gson")),
        Triple("kotlinx-coroutines", "1.9.0", I18n.t("about.tech.coroutines")),
        Triple("oshi", "6.6.5", I18n.t("about.tech.oshi")),
        Triple("pty4j", "0.13.12", I18n.t("about.tech.pty4j"))
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        tonalElevation = 1.dp
    ) {
        Column {
            // 表头
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(I18n.t("about.tech_component"),
                     style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.SemiBold,
                     color = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.weight(0.3f))
                Text(I18n.t("about.tech_version"),
                     style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.SemiBold,
                     color = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.weight(0.25f))
                Text(I18n.t("about.tech_purpose"),
                     style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.SemiBold,
                     color = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.weight(0.45f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

            // 数据行
            rows.forEachIndexed { index, (name, version, purpose) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(name,
                         style = MaterialTheme.typography.bodySmall,
                         fontWeight = FontWeight.Medium,
                         modifier = Modifier.weight(0.3f))
                    Text(version,
                         style = MaterialTheme.typography.bodySmall,
                         fontFamily = FontFamily.Monospace,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.weight(0.25f))
                    Text(purpose,
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.weight(0.45f))
                }
                if (index < rows.size - 1) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                }
            }
        }
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
                Text(I18n.t("settings.launch_preset"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Button(onClick = { presetName = ""; showSaveDialog = true }) {
                    Text(I18n.t("settings.save_current_as_preset"))
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(I18n.t("settings.launch_preset_desc"),
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
                            }) { Text(I18n.t("common.apply")) }
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { vm.deleteLaunchPreset(p.name) }) {
                                Icon(Icons.Filled.Delete, I18n.t("settings.delete_preset"),
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
            title = { Text(I18n.t("settings.save_launch_preset")) },
            text = {
                Column {
                    Text(I18n.t("settings.name_preset_prompt"), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        label = { Text(I18n.t("settings.preset_name")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (presets.any { it.name == presetName.trim() }) {
                        Spacer(Modifier.height(4.dp))
                        Text(I18n.t("settings.preset_overwrite_warning"),
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
                }) { Text(I18n.t("common.save")) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text(I18n.t("common.cancel")) }
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
                Text(I18n.t("settings.perf.mei_mode"), style = MaterialTheme.typography.titleSmall,
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
            Text(I18n.t("settings.perf.mei_mode_desc"),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(6.dp))
            Text(I18n.t("settings.perf.mei_mode_warning"),
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
                        Text(I18n.t("settings.perf.l1_jvm_aggressive"), fontWeight = FontWeight.Medium)
                        Text(I18n.t("settings.perf.l1_jvm_aggressive_desc"),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.perf.l1_jvm_aggressive_hint"),
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
                        Text(I18n.t("settings.perf.l1_large_pages"), fontWeight = FontWeight.Medium)
                        Text(I18n.t("settings.perf.l1_large_pages_desc"),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.perf.l1_large_pages_hint"),
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
                        Text(I18n.t("settings.perf.l1_experimental_zgc"), fontWeight = FontWeight.Medium)
                        Text(I18n.t("settings.perf.l1_experimental_zgc_desc"),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.perf.l1_experimental_zgc_warning"),
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
                        Text(I18n.t("settings.perf.l1_lwjgl_render"), fontWeight = FontWeight.Medium)
                        Text(I18n.t("settings.perf.l1_lwjgl_render_desc"),
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
                        Text(I18n.t("settings.perf.l1_jit_aggressive"), fontWeight = FontWeight.Medium)
                        Text(I18n.t("settings.perf.l1_jit_aggressive_desc"),
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
                        Text(I18n.t("settings.perf.l1_network_opt"), fontWeight = FontWeight.Medium)
                        Text(I18n.t("settings.perf.l1_network_opt_desc"),
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
                        Text(I18n.t("settings.perf.l1_metaspace"), fontWeight = FontWeight.Medium)
                        Text(I18n.t("settings.perf.l1_metaspace_desc"),
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
                        Text(I18n.t("settings.perf.l2_process"), fontWeight = FontWeight.Medium)
                        Text(I18n.t("settings.perf.l2_process_desc"),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.perf.l2_process_hint"),
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
                        Text(I18n.t("settings.perf.l2_crazy_priority"), fontWeight = FontWeight.Medium)
                        Text(I18n.t("settings.perf.l2_crazy_priority_desc"),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.perf.l2_crazy_priority_warning"),
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
                        Text(I18n.t("settings.perf.l3_system_power"), fontWeight = FontWeight.Medium)
                        Text(I18n.t("settings.perf.l3_system_power_desc"),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("settings.perf.l3_system_power_warning"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun MinecraftRootsCard(vm: LauncherViewModel) {
    val status by vm.status.collectAsState()
    var roots by remember { mutableStateOf(vm.getExtraMinecraftRoots()) }

    Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FolderOpen, null, Modifier.size(18.dp),
                     tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text(I18n.t("settings.minecraft_roots"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text(I18n.t("settings.minecraft_roots_hint"),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))

            // 已添加的根目录列表
            if (roots.isNotEmpty()) {
                roots.forEach { root ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Folder, null, Modifier.size(16.dp),
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            root,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        IconButton(
                            onClick = {
                                vm.removeMinecraftRoot(root)
                                roots = vm.getExtraMinecraftRoots()
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = I18n.t("common.delete"),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    I18n.t("settings.minecraft_roots_empty"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            // 添加按钮
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = {
                        // 使用 JFileChooser 选择目录（比 FileDialog 更适合目录选择）
                        val chooser = javax.swing.JFileChooser()
                        chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                        chooser.dialogTitle = I18n.t("settings.minecraft_roots_select")
                        chooser.isAcceptAllFileFilterUsed = false
                        if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                            val selected = chooser.selectedFile.absolutePath
                            vm.addMinecraftRoot(selected)
                            roots = vm.getExtraMinecraftRoots()
                        }
                    }
                ) {
                    Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("settings.minecraft_roots_add"))
                }
            }

            // 状态提示
            if (status.isNotEmpty() && status.contains("minecraft")) {
                Spacer(Modifier.height(8.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
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
    var menuBgVideoPath by remember { mutableStateOf(pref.getCustomMenuBackgroundVideo()) }
    var customNativesPath by remember { mutableStateOf(pref.getCustomNativesPath()) }
    var versionIsolation by remember { mutableStateOf(pref.isVersionIsolation()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("settings.game_general_behavior"), style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(I18n.t("settings.game_general_behavior_desc"),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = versionIsolation, onCheckedChange = {
                    versionIsolation = it; pref.setVersionIsolation(it)
                })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(I18n.t("settings.version_isolation"))
                    Text(I18n.t("settings.version_isolation_full_desc"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(I18n.t("settings.window_resolution"), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = width,
                    onValueChange = {
                        width = it
                        it.toIntOrNull()?.let { v -> pref.setGameWindowWidth(v) }
                    },
                    label = { Text(I18n.t("settings.width")) }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = {
                        height = it
                        it.toIntOrNull()?.let { v -> pref.setGameWindowHeight(v) }
                    },
                    label = { Text(I18n.t("settings.height")) }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(I18n.t("settings.window_resolution_hint"),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))
            Text(I18n.t("settings.renderer"), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            val rendererItems = listOf("AUTO" to I18n.t("settings.renderer_auto"), "OPENGL" to "OpenGL", "VULKAN" to "Vulkan")
            com.pmcl.ui.animation.AnimatedSegmentedSelector(
                items = rendererItems.map { it.second },
                selectedIndex = rendererItems.indexOfFirst { it.first == renderer }.coerceAtLeast(0),
                onSelect = {
                    renderer = rendererItems[it].first
                    pref.setGameRenderer(rendererItems[it].first)
                },
                fillWidth = true
            )
            Text(I18n.t("settings.renderer_hint"),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = fullscreen, onCheckedChange = { fullscreen = it; pref.setGameFullscreen(it) })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(I18n.t("settings.fullscreen_launch"))
                    Text(I18n.t("settings.fullscreen_launch_desc"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = demo, onCheckedChange = { demo = it; pref.setGameDemo(it) })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(I18n.t("settings.demo_mode"))
                    Text(I18n.t("settings.demo_mode_desc"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(I18n.t("settings.auto_connect_server"), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = serverHost,
                    onValueChange = {
                        serverHost = it
                        pref.setGameServerHost(it)
                    },
                    label = { Text(I18n.t("settings.server_address")) }, singleLine = true,
                    placeholder = { Text(I18n.t("settings.server_address_placeholder")) },
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = {
                        serverPort = it
                        it.toIntOrNull()?.let { v -> pref.setGameServerPort(v) }
                    },
                    label = { Text(I18n.t("settings.server_port")) }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(I18n.t("settings.server_connect_hint"),
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

            Spacer(Modifier.height(12.dp))
            Text(I18n.t("settings.menu_bg_video"), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = menuBgVideoPath,
                onValueChange = {
                    menuBgVideoPath = it
                    pref.setCustomMenuBackgroundVideo(it)
                },
                label = { Text(I18n.t("settings.menu_bg_video_path")) },
                singleLine = true,
                placeholder = { Text(I18n.t("settings.menu_bg_video_empty")) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            val fd = java.awt.FileDialog(
                                null as java.awt.Frame?,
                                I18n.t("settings.menu_bg_video_select"),
                                java.awt.FileDialog.LOAD
                            )
                            fd.filenameFilter = java.io.FilenameFilter { _, name ->
                                val lower = name.lowercase()
                                lower.endsWith(".mp4") || lower.endsWith(".webm")
                                        || lower.endsWith(".mov") || lower.endsWith(".mkv")
                                        || lower.endsWith(".avi")
                            }
                            fd.isVisible = true
                            if (fd.file != null) {
                                val p = java.io.File(fd.directory, fd.file).absolutePath
                                menuBgVideoPath = p
                                pref.setCustomMenuBackgroundVideo(p)
                            }
                        }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = I18n.t("common.browse"))
                        }
                        if (menuBgVideoPath.isNotEmpty()) {
                            IconButton(onClick = {
                                menuBgVideoPath = ""
                                pref.setCustomMenuBackgroundVideo("")
                            }) {
                                Icon(Icons.Filled.Clear, contentDescription = I18n.t("common.remove"))
                            }
                        }
                    }
                }
            )
            Text(I18n.t("settings.menu_bg_video_hint"),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))
            Text(I18n.t("settings.custom_natives"), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = customNativesPath,
                onValueChange = {
                    customNativesPath = it
                    pref.setCustomNativesPath(it)
                },
                label = { Text(I18n.t("settings.custom_natives_path")) },
                singleLine = true,
                placeholder = { Text(I18n.t("settings.custom_natives_empty")) },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    Row {
                        IconButton(onClick = {
                            val fc = javax.swing.JFileChooser()
                            fc.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                            fc.dialogTitle = I18n.t("settings.custom_natives_select")
                            if (fc.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                                val p = fc.selectedFile.absolutePath
                                customNativesPath = p
                                pref.setCustomNativesPath(p)
                            }
                        }) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = I18n.t("common.browse"))
                        }
                        if (customNativesPath.isNotEmpty()) {
                            IconButton(onClick = {
                                customNativesPath = ""
                                pref.setCustomNativesPath("")
                            }) {
                                Icon(Icons.Filled.Clear, contentDescription = I18n.t("common.remove"))
                            }
                        }
                    }
                }
            )
            Text(I18n.t("settings.custom_natives_hint"),
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
            Text(I18n.t("settings.network"), style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            Text(I18n.t("settings.mirror"), style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            val mirrorItems = listOf("OFFICIAL" to I18n.t("settings.mirror_official"), "BMCLAPI" to "BMCLAPI", "CUSTOM" to I18n.t("settings.mirror_custom"))
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
                    label = { Text(I18n.t("settings.custom_mirror_full")) },
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
                Text(I18n.t("settings.http_proxy"))
            }
            if (useProxy) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = proxyHost,
                        onValueChange = {
                            proxyHost = it; pref.setProxyHost(it); vm.applyNetworkPreferences()
                        },
                        label = { Text(I18n.t("settings.proxy_host")) }, singleLine = true,
                        modifier = Modifier.weight(2f)
                    )
                    OutlinedTextField(
                        value = proxyPort,
                        onValueChange = {
                            proxyPort = it
                            it.toIntOrNull()?.let { v -> pref.setProxyPort(v); vm.applyNetworkPreferences() }
                        },
                        label = { Text(I18n.t("settings.proxy_port")) }, singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = useAuth, onCheckedChange = {
                        useAuth = it; pref.setUseHttpAuth(it); vm.applyNetworkPreferences()
                    })
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.t("settings.proxy_auth"))
                }
                if (useAuth) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = proxyUser,
                            onValueChange = {
                                proxyUser = it; pref.setProxyUsername(it); vm.applyNetworkPreferences()
                            },
                            label = { Text(I18n.t("settings.proxy_username")) }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = proxyPass,
                            onValueChange = {
                                proxyPass = it; pref.setProxyPassword(it); vm.applyNetworkPreferences()
                            },
                            label = { Text(I18n.t("settings.proxy_password")) }, singleLine = true,
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
                    label = { Text(I18n.t("settings.speed_limit")) }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = retryCount,
                    onValueChange = {
                        retryCount = it
                        it.toIntOrNull()?.let { v -> pref.setDownloadRetryCount(v); vm.applyNetworkPreferences() }
                    },
                    label = { Text(I18n.t("settings.retry_count")) }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = chunkedThreads,
                    onValueChange = {
                        chunkedThreads = it
                        it.toIntOrNull()?.let { v -> pref.setChunkedDownloadThreads(v); vm.applyNetworkPreferences() }
                    },
                    label = { Text(I18n.t("settings.chunked_threads")) }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(I18n.t("settings.chunked_download_hint"),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = enableResume, onCheckedChange = {
                    enableResume = it; pref.setEnableResume(it); vm.applyNetworkPreferences()
                })
                Spacer(Modifier.width(8.dp))
                Text(I18n.t("settings.enable_resume"))
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
            Text(I18n.t("settings.java_runtime"), style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            // 当前检测到的 Java 路径
            Text(I18n.t("settings.current_java", detectedPath),
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            Text(I18n.t("settings.java_version_hint"),
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
                    else -> I18n.t("settings.unknown_arch")
                }
                val (downloadUrl, downloadLabel) = when {
                    isLoongson -> "https://www.loongnix.cn/zh/api/java/" to I18n.t("settings.go_to_loongson")
                    isRiscV -> "https://adoptium.net/temurin/releases/?version=17&arch=riscv64" to I18n.t("settings.go_to_adoptium")
                    else -> "" to ""
                }
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            I18n.t("settings.arch_not_supported", archName),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            I18n.t("settings.arch_not_supported_detail", archName),
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
                            Text(I18n.t("settings.downloading"))
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
                label = { Text(I18n.t("settings.manual_java_path")) },
                supportingText = { Text(I18n.t("settings.manual_java_path_hint")) },
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
            0x3D8BFF to I18n.t("settings.color.sky_blue"),   // 默认蓝
            0x55C57A to I18n.t("settings.color.mint"),
            0xFA8C16 to I18n.t("settings.color.amber"),
            0xE91E63 to I18n.t("settings.color.rose"),
            0x9C27B0 to I18n.t("settings.color.violet"),
            0xF44336 to I18n.t("settings.color.crimson"),
            0x00BCD4 to I18n.t("settings.color.cyan"),
            0x8BC34A to I18n.t("settings.color.grass"),
            0xFFC107 to I18n.t("settings.color.gold"),
            0x795548 to I18n.t("settings.color.brown"),
            0x607D8B to I18n.t("settings.color.slate"),
            0x000000 to I18n.t("settings.color.black")
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
                label = { Text(I18n.t("settings.custom_hex")) },
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
                    Text(I18n.t("settings.color_default"))
                }
            }
        }

        if (!enabled) {
            Spacer(Modifier.height(4.dp))
            Text(I18n.t("settings.disable_monet_first"),
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
                    ?: I18n.t("settings.license_not_found")
            }.getOrElse { I18n.t("settings.license_load_failed", it.message ?: "") }
        )
    }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Article, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(I18n.t("settings.license_title"))
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
                        Text(I18n.t("common.copy"))
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
                        I18n.t("settings.license_english_ref"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    Text(
                        I18n.t("settings.license_chinese_authoritative"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.close")) }
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
                    ?: I18n.t("settings.doc_not_found", resourceName)
            }.getOrElse { I18n.t("settings.doc_load_failed", it.message ?: "") }
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
                        Text(I18n.t("common.copy_all"))
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
            TextButton(onClick = onDismiss) { Text(I18n.t("common.close")) }
        }
    )
}
