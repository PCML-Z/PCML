package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.web.WikiBrowser
import com.pmcl.ui.viewmodel.LauncherViewModel

/** 默认首页 URL（"主页"按钮回到这里） */
private const val WIKI_HOME = "https://minecraft.wiki/"

/**
 * 将地址栏输入归一化为可加载的 URL。
 * - 已带 http(s)://：原样返回
 * - 形如域名（含 "." 且不含空格）：补 https://
 * - 其他：当作搜索词，走 WikiBrowser 搜索
 */
private fun normalizeInput(input: String): String {
    val t = input.trim()
    if (t.isEmpty()) return WIKI_HOME
    if (t.startsWith("http://", true) || t.startsWith("https://", true)) return t
    if (t.contains(".") && !t.contains(" ")) return "https://$t"
    return WikiBrowser.searchUrl(t)
}

/**
 * Wiki 浏览页（内嵌 WebView 浏览器）。
 *
 * 改造前：搜索框 + 链接卡片，点击通过系统浏览器打开。
 * 改造后：完整浏览器工具栏（后退/前进/刷新/主页 + 地址栏）+ 书签快捷栏 + 内嵌 JavaFX WebView。
 * 外部浏览器仍可作为兜底（"在外部打开"按钮）。
 */
@Composable
fun WikiPage(vm: LauncherViewModel) {
    val status by vm.status.collectAsState()
    // 地址栏文本（用户输入）
    var addressText by remember { mutableStateOf(WIKI_HOME) }
    // 当前驱动 WebView 加载的 URL
    var currentUrl by remember { mutableStateOf(WIKI_HOME) }
    var title by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    val controller = remember { WikiWebController() }
    val addressFocus = remember { FocusRequester() }

    Column(Modifier.fillMaxSize()) {
        // ===== 顶部工具栏：导航按钮 + 地址栏 + 外部打开 =====
        Surface(tonalElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavIconButton(Icons.AutoMirrored.Filled.ArrowBack, enabled = canBack) { controller.goBack() }
                NavIconButton(Icons.AutoMirrored.Filled.ArrowForward, enabled = canForward) { controller.goForward() }
                NavIconButton(Icons.Filled.Refresh, enabled = !loading) { controller.reload() }
                NavIconButton(Icons.Filled.Home) {
                    addressText = WIKI_HOME
                    currentUrl = WIKI_HOME
                }

                // 地址栏
                OutlinedTextField(
                    value = addressText,
                    onValueChange = { addressText = it },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .focusRequester(addressFocus),
                    placeholder = { Text(I18n.t("wiki.search_placeholder")) },
                    trailingIcon = {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            currentUrl = normalizeInput(addressText)
                            addressText = currentUrl
                        }
                    )
                )

                // 在外部浏览器打开（兜底：WebView 加载失败时）
                IconButton(onClick = { vm.openWikiUrl(currentUrl) }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                }
            }
        }

        // ===== 书签快捷栏 =====
        Surface(tonalElevation = 0.dp) {
            val bookmarks = remember {
                listOf(
                    I18n.t("wiki.mc_wiki") to "https://minecraft.wiki/",
                    I18n.t("wiki.modrinth") to "https://modrinth.com/mods",
                    I18n.t("wiki.curseforge") to "https://www.curseforge.com/minecraft/mc-mods",
                    "Fabric Wiki" to "https://fabricmc.net/wiki/",
                    I18n.t("wiki.forge_docs") to "https://docs.minecraftforge.net/",
                    I18n.t("wiki.mojang") to "https://www.minecraft.net/"
                )
            }
            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 2.dp)
            ) {
                items(bookmarks, key = { it.second }) { (label, url) ->
                    AssistChip(
                        onClick = {
                            addressText = url
                            currentUrl = url
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }

        // ===== 内容区：内嵌 WebView =====
        Box(Modifier.weight(1f).fillMaxWidth()) {
            WikiWebView(
                url = currentUrl,
                controller = controller,
                onUrlChanged = { new ->
                    // WebView 内导航：同步地址栏
                    addressText = new
                    currentUrl = new
                },
                onTitleChanged = { title = it },
                onLoadingChanged = { loading = it },
                onNavigationStateChanged = { back, forward ->
                    canBack = back
                    canForward = forward
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ===== 状态栏 =====
        Surface(tonalElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (loading) I18n.t("wiki.loading") else (title.ifEmpty { status }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                if (currentUrl.isNotBlank()) {
                    Text(
                        currentUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** 圆形导航按钮，禁用时灰显。 */
@Composable
private fun NavIconButton(icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(36.dp)) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}
