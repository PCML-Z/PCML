package com.lash.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import com.lash.pmcl.core.LauncherCore
import com.lash.pmcl.core.download.DownloadQueueState
import com.lash.pmcl.ui.screens.DownloadsScreen
import com.lash.pmcl.ui.screens.ModsMarketScreen
import com.lash.pmcl.ui.screens.VersionsScreen
import kotlinx.coroutines.delay

/**
 * 下载中心：Tab 切换 版本安装 / 模组市场 / 下载队列
 * 与桌面端 com.pmcl.ui.page.DownloadHubPage 完全一致。
 */
@Composable
fun DownloadHubPage(core: LauncherCore) {
    var tab by remember { mutableStateOf(0) }
    var queueTick by remember { mutableIntStateOf(0) }
    // 轮询队列状态以实时更新 Badge
    LaunchedEffect(Unit) { while (true) { queueTick++; delay(2000) } }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            listOf("本地版本", "模组市场", "下载队列", "Wiki").forEachIndexed { i, label ->
                Tab(
                    selected = tab == i,
                    onClick = { tab = i },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label)
                            if (i == 2) {
                                val active = remember(queueTick) { DownloadQueueState.activeCount() }
                                if (active > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    Badge { Text("$active") }
                                }
                            }
                        }
                    }
                )
            }
        }
        Box(Modifier.fillMaxSize().weight(1f)) {
            when (tab) {
                0 -> VersionsScreen(
                    versionManager = core.versionManager,
                    versionInstaller = core.versionInstaller,
                    modLoaderManager = core.modLoaderManager,
                )
                1 -> ModsMarketScreen(core = core)
                2 -> DownloadsScreen()
                3 -> WikiScreen()
            }
        }
    }
}

@Composable
private fun WikiScreen() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var useWebView by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Wiki", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Minecraft Wiki 浏览器 (选择搜索模式避免模拟器崩溃)",
             style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !useWebView, onClick = { useWebView = false }, label = { Text("搜索") })
            FilterChip(selected = useWebView, onClick = { useWebView = true }, label = { Text("网页 (较慢)") })
        }
        Spacer(Modifier.height(12.dp))

        if (useWebView) {
            Box(Modifier.fillMaxSize().weight(1f)) {
                AndroidView(factory = {
                    android.webkit.WebView(it).apply {
                        try { setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null) } catch (_: Exception) {}
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onReceivedError(v: android.webkit.WebView?,
                                r: android.webkit.WebResourceRequest?, e: android.webkit.WebResourceError?) {
                                v?.loadData("<h2>无法加载</h2>", "text/html", "UTF-8")
                            }
                        }
                        loadUrl("https://minecraft.wiki/")
                    }
                }, modifier = Modifier.fillMaxSize())
            }
        } else {
            var query by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    label = { Text("搜索 Minecraft Wiki") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        if (query.isNotBlank()) ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://minecraft.wiki/w/" +
                                    java.net.URLEncoder.encode(query, "UTF-8"))))
                    }))
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (query.isNotBlank()) ctx.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://minecraft.wiki/w/" +
                                java.net.URLEncoder.encode(query, "UTF-8"))))
                }) { Text("搜索") }
            }

            Spacer(Modifier.height(12.dp))
            Text("快速入口", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val links = listOf(
                "方块" to "Block", "物品" to "Item", "生物" to "Mob",
                "合成" to "Crafting", "红石" to "Redstone", "附魔" to "Enchanting",
                "版本" to "Version", "指令" to "Commands", "世界生成" to "World_generation")
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                links.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEach { (label, page) ->
                            OutlinedButton(onClick = {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://minecraft.wiki/w/$page")))
                            }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Text("Wiki 将在系统浏览器中打开", style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}