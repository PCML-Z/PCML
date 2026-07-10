package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.ui.theme.LocalThemeState
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
        Card(Modifier.fillMaxWidth()) {
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
        Card(Modifier.fillMaxWidth()) {
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

        // 游戏通用行为
        GameBehaviorCard(pref)

        Spacer(Modifier.height(16.dp))

        // Java 运行时管理
        JavaRuntimeCard(vm, pref)

        Spacer(Modifier.height(16.dp))

        // 外观
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("外观", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = themeState.useDark,
                        onCheckedChange = { v ->
                            themeState.set(v)
                            pref.setUseDarkTheme(v)
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
                            if (v) vm.refreshWallpaperColor(themeState)
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

                Text("语言 / Language", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                val langItems = listOf("zh_CN" to "简体中文", "en_US" to "English")
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
            }
        }

        Spacer(Modifier.height(16.dp))

        // 网络配置
        NetworkConfigCard(vm, pref)

        Spacer(Modifier.height(16.dp))

        // 系统信息
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
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
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
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

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("游戏通用行为", style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("应用于所有版本的启动参数，留空使用游戏默认值",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

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
            Text("MC 1.20.5+ 需要 Java 21；MC 1.17–1.20.4 需要 Java 17。Java 24+ 可能导致 Render thread 崩溃。",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(12.dp))

            // 一键下载 Java 21
            Button(
                onClick = { vm.downloadJava21() },
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
                    Text("一键下载 Java 21（Mojang 官方）")
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
