package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.news.NewsItem
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Minecraft 新闻页：拉取 Minecraft.net 官方 RSS 并以卡片列表展示。
 *
 * - 进入页面时自动加载一次
 * - 点击卡片在 PMCL 内部加载并显示文章正文
 * - 支持手动刷新
 */
@Composable
fun NewsPage(vm: LauncherViewModel) {
    val news by vm.newsItems.collectAsState()
    val loading by vm.newsLoading.collectAsState()
    val status by vm.status.collectAsState()
    val article by vm.articleContent.collectAsState()
    val articleLoading by vm.articleLoading.collectAsState()
    val articleError by vm.articleError.collectAsState()
    val translationCache by vm.translationCache.collectAsState()
    val translating by vm.translating.collectAsState()
    var translateEnabled by remember { mutableStateOf(false) }
    val format = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 解析 RSS pubDate（RFC-822，含 "Z" UTC 后缀）→ millis，失败返回 0
    fun parsePubDate(raw: String): Long {
        if (raw.isEmpty()) return 0L
        val patterns = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm zzz",
            "dd MMM yyyy HH:mm:ss zzz"
        )
        for (p in patterns) {
            try {
                val sdf = SimpleDateFormat(p, Locale.ENGLISH)
                val parsed = sdf.parse(raw)
                if (parsed != null) return parsed.time
            } catch (_: Throwable) { continue }
        }
        return 0L
    }

    LaunchedEffect(Unit) {
        if (news.isEmpty()) vm.refreshNews()
    }

    // 文章详情视图优先显示
    if (article != null || articleLoading || articleError.isNotEmpty()) {
        val currentArticle = article
        // 进入文章详情时，如果翻译已开启，自动翻译正文文本块
        LaunchedEffect(currentArticle, translateEnabled) {
            if (translateEnabled && currentArticle != null) {
                val blocks = parseHtmlToBlocks(currentArticle.getBodyHtml())
                val texts = blocks.mapNotNull { block ->
                    when (block) {
                        is HtmlBlock.Paragraph -> block.text
                        is HtmlBlock.Heading -> block.text
                        is HtmlBlock.ListItem -> block.text
                        else -> null
                    }
                }.filter { it.isNotBlank() }
                if (texts.isNotEmpty()) vm.translateBatch(texts)
            }
        }

        ArticleDetailView(
            article = currentArticle,
            loading = articleLoading,
            error = articleError,
            translateEnabled = translateEnabled,
            translationCache = translationCache,
            onTranslate = { text -> vm.translateText(text) },
            translating = translating,
            onToggleTranslate = {
                translateEnabled = !translateEnabled
                val art = currentArticle
                if (translateEnabled && art != null) {
                    val blocks = parseHtmlToBlocks(art.getBodyHtml())
                    val texts = blocks.mapNotNull { block ->
                        when (block) {
                            is HtmlBlock.Paragraph -> block.text
                            is HtmlBlock.Heading -> block.text
                            is HtmlBlock.ListItem -> block.text
                            else -> null
                        }
                    }.filter { it.isNotBlank() }
                    if (texts.isNotEmpty()) vm.translateBatch(texts)
                }
            },
            onBack = { vm.clearArticle() },
            onOpenInBrowser = { currentArticle?.getUrl()?.let { vm.openNewsLink(it) } }
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 标题栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(I18n.t("news.title"),
                 style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold,
                 modifier = Modifier.weight(1f))
            // 翻译开关
            FilterChip(
                selected = translateEnabled,
                onClick = {
                    translateEnabled = !translateEnabled
                    if (translateEnabled) {
                        val texts = news.flatMap {
                            listOfNotNull(it.getTitle(), it.getDescription())
                        }.distinct()
                        vm.translateBatch(texts)
                    }
                },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (translateEnabled) Icons.Filled.Translate else Icons.Outlined.Translate,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (translating) I18n.t("mods.translating") else I18n.t("mods.translate"))
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { vm.refreshNews() },
                enabled = !loading
            ) {
                Text(if (loading) I18n.t("common.loading") else I18n.t("common.refresh"))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            I18n.t("news.source"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))

        when {
            loading && news.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(I18n.t("news.fetching"),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            !loading && news.isEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📰", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(12.dp))
                        Text(I18n.t("news.empty"),
                             style = MaterialTheme.typography.titleMedium,
                             fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(status,
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { vm.refreshNews() }) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("common.retry"))
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    itemsIndexed(news, key = { _, item -> item.getLink() }) { _, item ->
                        NewsCard(
                            item = item,
                            format = format,
                            parsePubDate = ::parsePubDate,
                            translateEnabled = translateEnabled,
                            translationCache = translationCache,
                            onClick = { vm.loadArticle(item.getLink()) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * 文章详情视图：在 PMCL 内部显示新闻正文。
 */
@Composable
private fun ArticleDetailView(
    article: com.pmcl.core.news.ArticleContent?,
    loading: Boolean,
    error: String,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap(),
    onTranslate: (String) -> Unit = {},
    onTranslateBatch: (List<String>) -> Unit = {},
    translating: Boolean = false,
    onToggleTranslate: () -> Unit = {},
    onBack: () -> Unit,
    onOpenInBrowser: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 顶部导航栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = I18n.t("common.back"),
                     modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("news.back_to_list"))
            }
            Spacer(Modifier.weight(1f))
            if (article != null) {
                // 翻译开关
                FilterChip(
                    selected = translateEnabled,
                    onClick = onToggleTranslate,
                    label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (translateEnabled) Icons.Filled.Translate else Icons.Outlined.Translate,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(if (translating) I18n.t("mods.translating") else I18n.t("mods.translate"))
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onOpenInBrowser) {
                    Icon(Icons.Filled.Search, contentDescription = null,
                         modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(I18n.t("news.open_browser"))
                }
            }
        }

        when {
            loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(I18n.t("news.loading_article"),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            error.isNotEmpty() -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(I18n.t("news.load_failed"),
                             style = MaterialTheme.typography.titleMedium,
                             fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Text(error,
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            article != null -> {
                ArticleBody(
                    article = article,
                    translateEnabled = translateEnabled,
                    translationCache = translationCache
                )
            }
        }
    }
}

/**
 * 文章正文渲染：标题、封面图、HTML 正文解析为可读文本块。
 */
@Composable
private fun ArticleBody(
    article: com.pmcl.core.news.ArticleContent,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap()
) {
    val blocks = remember(article.getBodyHtml()) { parseHtmlToBlocks(article.getBodyHtml()) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // 封面图
        if (!article.getCoverImage().isNullOrEmpty()) {
            item {
                val cover = rememberUrlImage(article.getCoverImage())
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    if (cover != null) {
                        androidx.compose.foundation.Image(
                            bitmap = cover,
                            contentDescription = article.getTitle(),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("📰", style = MaterialTheme.typography.displaySmall)
                    }
                }
            }
        }

        // 标题
        item {
            val rawTitle = article.getTitle() ?: ""
            val displayTitle = if (translateEnabled) translationCache[rawTitle] ?: rawTitle else rawTitle
            Text(displayTitle,
                 style = MaterialTheme.typography.headlineSmall,
                 fontWeight = FontWeight.Bold)
        }

        // 正文块
        itemsIndexed(blocks, key = { index, _ -> "block-$index" }) { _, block ->
            RenderHtmlBlock(block, translateEnabled, translationCache)
        }

        // 底部链接
        item {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(I18n.t("news.source_link", article.getUrl()),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
        }
    }
}

/**
 * HTML 正文块类型。
 */
private sealed class HtmlBlock {
    data class Paragraph(val text: String, val bold: Boolean = false, val italic: Boolean = false) : HtmlBlock()
    data class Heading(val text: String, val level: Int = 2) : HtmlBlock()
    data class Image(val url: String, val alt: String = "") : HtmlBlock()
    data class ListItem(val text: String, val ordered: Boolean = false) : HtmlBlock()
}

/**
 * 简易 HTML → 块解析器。
 * 识别 <p>、<h2>、<h3>、<ul>/<ol>/<li>、<img>，其余标签剥除为纯文本。
 */
private fun parseHtmlToBlocks(html: String): List<HtmlBlock> {
    val blocks = mutableListOf<HtmlBlock>()
    if (html.isEmpty()) return blocks

    val tagPattern = Regex("<(/?)(p|h2|h3|ul|ol|li|img|strong|b|em|i|br)[^>]*>", RegexOption.IGNORE_CASE)

    var inList = false
    var listOrdered = false
    var currentBold = false
    var currentItalic = false

    // 逐段处理：(标签间文本, 标签)
    val segments = mutableListOf<Pair<String, String>>()
    var textStart = 0
    for (m in tagPattern.findAll(html)) {
        val between = html.substring(textStart, m.range.first)
        val tag = m.value.lowercase()
        segments.add(Pair(between, tag))
        textStart = m.range.last + 1
    }
    segments.add(Pair(html.substring(textStart), ""))

    for ((text, tag) in segments) {
        val cleanText = stripTags(text).trim()
        if (tag.isEmpty()) {
            // 末尾文本
            if (cleanText.isNotEmpty() && !inList) {
                blocks.add(HtmlBlock.Paragraph(cleanText, currentBold, currentItalic))
            }
            continue
        }

        when {
            tag.startsWith("<p") || tag.startsWith("<p ") -> {
                if (cleanText.isNotEmpty()) {
                    blocks.add(HtmlBlock.Paragraph(cleanText, currentBold, currentItalic))
                }
            }
            tag.startsWith("<h2") -> {
                // h2 标签后的文本在下一个 segment
            }
            tag.startsWith("<h3") -> { }
            tag.startsWith("<ul") -> { inList = true; listOrdered = false }
            tag.startsWith("<ol") -> { inList = true; listOrdered = true }
            tag.startsWith("<li") -> {
                if (cleanText.isNotEmpty()) {
                    blocks.add(HtmlBlock.ListItem(cleanText, listOrdered))
                }
            }
            tag.startsWith("<img") -> {
                val src = extractAttr(tag, "src")
                if (src.isNotEmpty()) {
                    val fullSrc = if (src.startsWith("/")) "https://www.minecraft.net$src" else src
                    blocks.add(HtmlBlock.Image(fullSrc, extractAttr(tag, "alt")))
                }
            }
            tag.startsWith("<strong") || tag.startsWith("<b") -> currentBold = true
            tag.startsWith("</strong") || tag.startsWith("</b") -> currentBold = false
            tag.startsWith("<em") || tag.startsWith("<i") -> currentItalic = true
            tag.startsWith("</em") || tag.startsWith("</i") -> currentItalic = false
            tag.startsWith("</ul") || tag.startsWith("</ol") -> inList = false
            tag.startsWith("</h2") || tag.startsWith("</h3") -> { }
            tag.startsWith("</p") || tag.startsWith("</li") -> { }
        }
    }

    // 如果没有解析出块，把整个 HTML 作为纯文本
    if (blocks.isEmpty()) {
        val plain = stripTags(html).trim()
        if (plain.isNotEmpty()) {
            // 按换行分段
            for (para in plain.split("\n\n")) {
                val t = para.trim()
                if (t.isNotEmpty()) blocks.add(HtmlBlock.Paragraph(t))
            }
        }
    }

    return blocks
}

/** 剥除所有 HTML 标签，解码实体 */
private fun stripTags(html: String): String {
    return html
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** 从 HTML 标签中提取属性值 */
private fun extractAttr(tag: String, attr: String): String {
    val m = Regex("$attr\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)
    return m?.groupValues?.get(1) ?: ""
}

/**
 * 渲染单个 HTML 块。
 */
@Composable
private fun RenderHtmlBlock(
    block: HtmlBlock,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap()
) {
    fun tr(text: String): String =
        if (translateEnabled) translationCache[text] ?: text else text

    when (block) {
        is HtmlBlock.Paragraph -> {
            Text(
                tr(block.text),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (block.bold) FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (block.italic) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        is HtmlBlock.Heading -> {
            Spacer(Modifier.height(4.dp))
            Text(
                tr(block.text),
                style = if (block.level == 2) MaterialTheme.typography.titleMedium
                        else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        is HtmlBlock.Image -> {
            val img = rememberUrlImage(block.url)
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (img != null) {
                    androidx.compose.foundation.Image(
                        bitmap = img,
                        contentDescription = block.alt,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(120.dp),
                        contentAlignment = Alignment.Center) {
                        Text("🖼️", style = MaterialTheme.typography.displaySmall)
                    }
                }
            }
        }
        is HtmlBlock.ListItem -> {
            Row {
                Text(if (block.ordered) "• " else "·  ",
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.primary)
                Text(tr(block.text),
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/**
 * 单条新闻卡片：左侧封面图（无图用占位 emoji），右侧标题/摘要/日期/分类。
 */
@Composable
private fun NewsCard(
    item: NewsItem,
    format: SimpleDateFormat,
    parsePubDate: (String) -> Long,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap(),
    onClick: () -> Unit
) {
    val image = rememberUrlImage(item.getImageUrl())
    val pubMillis = remember(item.getPubDate()) { parsePubDate(item.getPubDate()) }

    val displayTitle = if (translateEnabled) translationCache[item.getTitle()] ?: item.getTitle() else item.getTitle()
    val displayDesc = if (translateEnabled) translationCache[item.getDescription()] ?: item.getDescription() else item.getDescription()

    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(10.dp).height(110.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧封面图
            Box(
                modifier = Modifier.size(90.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    androidx.compose.foundation.Image(
                        bitmap = image,
                        contentDescription = item.getTitle(),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (item.getImageUrl().isNotEmpty()) {
                    // 图片 URL 已知，正在下载解码
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    // 图片 URL 尚未抓取或无图
                    Text("📰", style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(Modifier.width(12.dp))

            // 右侧文本
            Column(Modifier.fillMaxHeight().weight(1f)) {
                Text(displayTitle,
                     style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold,
                     maxLines = 2,
                     overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                if (displayDesc.isNotEmpty()) {
                    Text(displayDesc,
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         maxLines = 2,
                         overflow = TextOverflow.Ellipsis,
                         modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!item.getCategory().isNullOrEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(item.getCategory() ?: "",
                                 style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.onPrimaryContainer,
                                 modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (pubMillis > 0) {
                        Text(format.format(Date(pubMillis)),
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.outline,
                             modifier = Modifier.weight(1f))
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    Text(I18n.t("news.view_full"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.primary,
                         fontSize = 11.sp)
                }
            }
        }
    }
}

/**
 * 图片内存缓存：URL → ImageBitmap。避免滚动时重复下载与解码。
 */
// M32 修复：复用全局 LruImageCache
private val newsImageCache = com.pmcl.ui.util.LruImageCache(64)

/**
 * 异步从 URL 加载图片，返回 Skia 解码的 ImageBitmap。
 * 带内存缓存，滚动时不会重复下载。失败返回 null（UI 层用占位图）。
 */
@Composable
private fun rememberUrlImage(url: String): ImageBitmap? {
    val cached = newsImageCache.get(url)
    if (cached != null) return cached
    if (url.isEmpty()) return null

    var image by remember(url) { mutableStateOf<ImageBitmap?>(newsImageCache.get(url)) }
    LaunchedEffect(url) {
        if (url.isEmpty()) {
            image = null
            return@LaunchedEffect
        }
        if (newsImageCache.isKnownFailed(url)) { image = null; return@LaunchedEffect }
        val existing = newsImageCache.get(url)
        if (existing != null) { image = existing; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                if (url.isNullOrBlank()) return@withContext
                val bytes = URL(url).readBytes()
                val bmp = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                newsImageCache.put(url, bmp)
                image = bmp
            } catch (_: Throwable) {
                newsImageCache.markFailed(url)
                image = null
            }
        }
    }
    return image
}
