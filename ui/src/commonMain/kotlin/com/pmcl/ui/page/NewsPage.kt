package com.pmcl.ui.page
import com.pmcl.ui.widget.PmclLazyColumn

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.news.NewsHtml
import com.pmcl.core.news.NewsItem
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.refreshNews
import com.pmcl.ui.viewmodel.loadArticle
import com.pmcl.ui.viewmodel.clearArticle
import com.pmcl.ui.viewmodel.openNewsLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import com.pmcl.ui.util.decodeSampledBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
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
                val texts = articleTexts(currentArticle)
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
                    val texts = articleTexts(art)
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
                PmclLazyColumn(
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
    val blocks = remember(article.getBodyHtml()) { NewsHtml.parse(article.getBodyHtml()) }

    PmclLazyColumn(
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
            if (displayTitle.isNotBlank()) {
                Text(
                    displayTitle,
                    style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 34.sp),
                    fontWeight = FontWeight.Bold,
                    softWrap = true
                )
            }
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
            Text(
                I18n.t("news.source_link", article.getUrl()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                softWrap = true
            )
        }
    }
}

private fun articleTexts(article: com.pmcl.core.news.ArticleContent): List<String> {
    val blocks = NewsHtml.parse(article.getBodyHtml())
    val texts = ArrayList<String>()
    if (!article.getTitle().isNullOrBlank()) texts.add(article.getTitle())
    for (block in blocks) {
        if (block.kind == NewsHtml.Kind.IMAGE || block.text.isBlank()) continue
        texts.add(block.text)
    }
    return texts
}

/**
 * 渲染单个 HTML 块。
 */
@Composable
private fun RenderHtmlBlock(
    block: NewsHtml.Block,
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap()
) {
    fun tr(text: String): String =
        if (translateEnabled) translationCache[text] ?: text else text

    when (block.kind) {
        NewsHtml.Kind.PARAGRAPH -> {
            Text(
                tr(block.text),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true
            )
        }
        NewsHtml.Kind.HEADING -> {
            val style = when (block.level) {
                1, 2 -> MaterialTheme.typography.titleLarge
                3 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            Text(
                tr(block.text),
                modifier = Modifier.padding(top = if (block.level <= 2) 10.dp else 4.dp),
                style = style.copy(lineHeight = if (block.level <= 2) 30.sp else 24.sp),
                fontWeight = FontWeight.Bold,
                softWrap = true
            )
        }
        NewsHtml.Kind.IMAGE -> {
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
        NewsHtml.Kind.LIST_ITEM -> {
            val marker = if (block.ordered && block.index > 0) "${block.index}. " else "• "
            Row(
                Modifier.fillMaxWidth().padding(start = (block.depth * 16).dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    marker,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    tr(block.text),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = true
                )
            }
        }
        null -> Unit
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
        color = glassSurfaceVariantColor(),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(10.dp).heightIn(min = 96.dp),
            verticalAlignment = Alignment.Top
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
            Column(Modifier.weight(1f)) {
                Text(displayTitle,
                     style = MaterialTheme.typography.titleSmall.copy(lineHeight = 20.sp),
                     fontWeight = FontWeight.SemiBold,
                     maxLines = 3,
                     overflow = TextOverflow.Ellipsis,
                     softWrap = true)
                Spacer(Modifier.height(4.dp))
                if (displayDesc.isNotEmpty()) {
                    Text(displayDesc,
                         style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                         color = MaterialTheme.colorScheme.onSurfaceVariant,
                         maxLines = 3,
                         overflow = TextOverflow.Ellipsis,
                         softWrap = true)
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
private val newsImageCache = com.pmcl.ui.util.LruImageCache()

/**
 * 异步从 URL 加载图片，返回 Skia 解码的 ImageBitmap。
 * 内存缓存 → 磁盘缓存（DiskImageCache，跨会话持久）→ 网络下载。
 * 失败返回 null（UI 层用占位图）。
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
                // 1) 磁盘缓存命中：免网络，直接解码
                var bytes = com.pmcl.ui.util.DiskImageCache.readBytes(url)
                var bmp = bytes?.let { decodeSampledBitmap(it, 256) }
                // 2) 未命中或缓存字节损坏：网络下载并落盘
                if (bmp == null) {
                    bytes = com.pmcl.ui.util.SafeUrlFetcher.fetchBytes(url)
                    bmp = decodeSampledBitmap(bytes, 256)
                        ?: throw IllegalStateException("decode failed")
                    com.pmcl.ui.util.DiskImageCache.writeBytes(url, bytes)
                }
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
