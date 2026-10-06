package com.pmcl.ui.page
import com.pmcl.ui.widget.PmclLazyColumn

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.news.NewsHtml
import com.pmcl.core.news.NewsItem
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.theme.newsPixelFamily
import com.pmcl.ui.theme.withFamily
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
 * Minecraft 新闻页：拉取 Minecraft.net 官方 RSS，列表按报纸版式排。
 *
 * - 进入页面时自动加载一次
 * - 点击标题在 PMCL 内部加载并显示文章正文
 * - 支持手动刷新
 */
@Composable
fun NewsPage(vm: LauncherViewModel) {
    val colors = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val parentType = MaterialTheme.typography
    val pixel = newsPixelFamily()
    val type = remember(parentType, pixel) { parentType.withFamily(pixel) }
    MaterialTheme(colorScheme = colors, typography = type, shapes = shapes) {
        NewsPageContent(vm)
    }
}

@Composable
private fun NewsPageContent(vm: LauncherViewModel) {
    val news by vm.newsItems.collectAsState()
    val loading by vm.newsLoading.collectAsState()
    val status by vm.status.collectAsState()
    val article by vm.articleContent.collectAsState()
    val articleLoading by vm.articleLoading.collectAsState()
    val articleError by vm.articleError.collectAsState()
    val translationCache by vm.translationCache.collectAsState()
    val translating by vm.translating.collectAsState()
    var translateEnabled by remember { mutableStateOf(false) }
    val format = remember { SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()) }

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

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
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
            FilledTonalButton(
                onClick = { vm.refreshNews() },
                enabled = !loading
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (loading) I18n.t("common.loading") else I18n.t("common.refresh"))
            }
        }
        Spacer(Modifier.height(4.dp))

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
                        Text(
                            I18n.t("news.empty"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (status.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(onClick = { vm.refreshNews() }) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("common.retry"))
                        }
                    }
                }
            }
            else -> {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val columns = when {
                        maxWidth < 680.dp -> 1
                        maxWidth < 1080.dp -> 2
                        else -> 3
                    }
                    val editionMillis = remember(news) {
                        news.maxOfOrNull { parsePubDate(it.getPubDate()) } ?: 0L
                    }
                    val rows = remember(news, columns) { news.drop(1).chunked(columns) }
                    val lead = news.first()
                    PmclLazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                        item(key = "masthead") { NewsMasthead(editionMillis) }
                        item(key = "lead:${lead.getLink()}") {
                            LeadStory(
                                item = lead,
                                spread = columns > 1,
                                format = format,
                                parsePubDate = ::parsePubDate,
                                translateEnabled = translateEnabled,
                                translationCache = translationCache,
                                onClick = { vm.loadArticle(lead.getLink()) }
                            )
                        }
                        if (rows.isNotEmpty()) {
                            item(key = "section-rule") {
                                Spacer(Modifier.height(8.dp))
                                NewsDoubleRule()
                            }
                        }
                        itemsIndexed(
                            rows,
                            key = { index, row ->
                                "col-$index:${row.firstOrNull()?.getLink().orEmpty()}"
                            }
                        ) { index, row ->
                            if (index > 0) NewsBar(1.dp, 0.16f)
                            NewsColumnRow(
                                row = row,
                                columns = columns,
                                format = format,
                                parsePubDate = ::parsePubDate,
                                translateEnabled = translateEnabled,
                                translationCache = translationCache,
                                onOpen = { vm.loadArticle(it) }
                            )
                        }
                        item(key = "end-rule") {
                            Spacer(Modifier.height(10.dp))
                            NewsBar(1.dp, 0.28f)
                        }
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
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier
            .widthIn(max = 760.dp)
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = I18n.t("common.back"),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("news.back_to_list"))
            }
            Spacer(Modifier.weight(1f))
            if (article != null) {
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
                FilledTonalButton(onClick = onOpenInBrowser) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(I18n.t("news.open_browser"))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

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

    Box(Modifier.fillMaxSize()) {
        PmclLazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            if (!article.getCoverImage().isNullOrEmpty()) {
                item {
                    val cover = rememberUrlImage(article.getCoverImage())
                    Box(
                        modifier = Modifier.fillMaxWidth().height(280.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(glassSurfaceVariantColor()),
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
                            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            item {
                val rawTitle = article.getTitle() ?: ""
                val displayTitle = if (translateEnabled) translationCache[rawTitle] ?: rawTitle else rawTitle
                if (displayTitle.isNotBlank()) {
                    Text(
                        displayTitle,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = 36.sp,
                            lineHeight = 48.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        softWrap = true
                    )
                }
            }

            itemsIndexed(blocks, key = { index, _ -> "block-$index" }) { _, block ->
                RenderHtmlBlock(block, translateEnabled, translationCache)
            }

            item {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Text(
                    I18n.t("news.source_link", article.getUrl()),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true
            )
        }
        NewsHtml.Kind.HEADING -> {
            Text(
                tr(block.text),
                modifier = Modifier.padding(top = if (block.level <= 2) 12.dp else 6.dp),
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 36.sp),
                fontWeight = FontWeight.Bold,
                softWrap = true
            )
        }
        NewsHtml.Kind.IMAGE -> {
            val img = rememberUrlImage(block.url)
            Box(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(glassSurfaceVariantColor()),
                contentAlignment = Alignment.Center
            ) {
                if (img != null) {
                    androidx.compose.foundation.Image(
                        bitmap = img,
                        contentDescription = block.alt.ifBlank { null },
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
        NewsHtml.Kind.LIST_ITEM -> {
            val marker = if (block.ordered && block.index > 0) "${block.index}." else "•"
            Row(
                Modifier.fillMaxWidth().padding(start = (12 + block.depth * 16).dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    marker,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    tr(block.text),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = true
                )
            }
        }
        null -> Unit
    }
}

@Composable
private fun NewsMasthead(editionMillis: Long) {
    val locale = I18n.getCurrentLocale()
    val edition = remember(editionMillis, locale) {
        if (editionMillis <= 0L) ""
        else {
            val pattern = if (locale.language == "zh" || locale.language == "ja") "yyyy年M月d日" else "MMMM d, yyyy"
            SimpleDateFormat(pattern, locale).format(Date(editionMillis))
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp)) {
        NewsBar(2.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            I18n.t("news.title"),
            modifier = Modifier.fillMaxWidth(),
            fontWeight = FontWeight.Black,
            fontSize = 36.sp,
            lineHeight = 48.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        NewsDoubleRule()
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                I18n.t("news.source"),
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (edition.isNotEmpty()) {
                Text(
                    edition,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        NewsBar(1.dp, 0.45f)
    }
}

@Composable
private fun LeadStory(
    item: NewsItem,
    spread: Boolean,
    format: SimpleDateFormat,
    parsePubDate: (String) -> Long,
    translateEnabled: Boolean,
    translationCache: Map<String, String>,
    onClick: () -> Unit
) {
    val image = rememberUrlImage(item.getImageUrl())
    val pubMillis = remember(item.getPubDate()) { parsePubDate(item.getPubDate()) }
    val title = newsText(item.getTitle(), translateEnabled, translationCache)
    val desc = newsText(item.getDescription(), translateEnabled, translationCache)
    val hasImage = item.getImageUrl().isNotEmpty()
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 16.dp, bottom = 8.dp)) {
        if (spread && hasImage) {
            Row(verticalAlignment = Alignment.Top) {
                NewsPhoto(
                    image = image,
                    waiting = true,
                    contentDescription = title,
                    modifier = Modifier.weight(1.15f).height(248.dp)
                )
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NewsKicker(item.getCategory(), pubMillis, format)
                    NewsHeadline(title, size = 24.sp, lineHeight = 36.sp, maxLines = 4)
                    if (desc.isNotEmpty()) NewsDeck(desc, maxLines = 6)
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NewsKicker(item.getCategory(), pubMillis, format)
                NewsHeadline(title, size = 36.sp, lineHeight = 48.sp, maxLines = 3)
                if (hasImage) {
                    NewsPhoto(
                        image = image,
                        waiting = true,
                        contentDescription = title,
                        modifier = Modifier.fillMaxWidth().height(220.dp)
                    )
                }
                if (desc.isNotEmpty()) NewsDeck(desc, maxLines = 4)
            }
        }
    }
}

@Composable
private fun NewsColumnRow(
    row: List<NewsItem>,
    columns: Int,
    format: SimpleDateFormat,
    parsePubDate: (String) -> Long,
    translateEnabled: Boolean,
    translationCache: Map<String, String>,
    onOpen: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        row.forEachIndexed { index, item ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(vertical = 14.dp)
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                )
            }
            NewsColumnStory(
                item = item,
                spacious = columns == 1,
                padStart = if (index == 0) 0.dp else 16.dp,
                padEnd = if (index == columns - 1) 0.dp else 16.dp,
                format = format,
                parsePubDate = parsePubDate,
                translateEnabled = translateEnabled,
                translationCache = translationCache,
                onClick = { onOpen(item.getLink()) },
                modifier = Modifier.weight(1f)
            )
        }
        repeat(columns - row.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun NewsColumnStory(
    item: NewsItem,
    spacious: Boolean,
    padStart: androidx.compose.ui.unit.Dp,
    padEnd: androidx.compose.ui.unit.Dp,
    format: SimpleDateFormat,
    parsePubDate: (String) -> Long,
    translateEnabled: Boolean,
    translationCache: Map<String, String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val image = rememberUrlImage(item.getImageUrl())
    val pubMillis = remember(item.getPubDate()) { parsePubDate(item.getPubDate()) }
    val title = newsText(item.getTitle(), translateEnabled, translationCache)
    val desc = newsText(item.getDescription(), translateEnabled, translationCache)
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(start = padStart, end = padEnd, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (item.getImageUrl().isNotEmpty()) {
            NewsPhoto(
                image = image,
                waiting = true,
                contentDescription = title,
                modifier = Modifier.fillMaxWidth().height(if (spacious) 180.dp else 108.dp)
            )
        }
        NewsKicker(item.getCategory(), pubMillis, format)
        NewsHeadline(
            title,
            size = 24.sp,
            lineHeight = 36.sp,
            maxLines = 4
        )
        if (desc.isNotEmpty()) NewsDeck(desc, maxLines = 3)
    }
}

@Composable
private fun NewsKicker(category: String?, pubMillis: Long, format: SimpleDateFormat) {
    if (category.isNullOrBlank() && pubMillis <= 0L) return
    val label = category?.takeIf { it.isNotBlank() }?.uppercase(I18n.getCurrentLocale()).orEmpty()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (label.isNotEmpty()) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (pubMillis > 0L) {
            Text(
                format.format(Date(pubMillis)),
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun NewsHeadline(text: String, size: androidx.compose.ui.unit.TextUnit, lineHeight: androidx.compose.ui.unit.TextUnit, maxLines: Int) {
    Text(
        text,
        fontWeight = FontWeight.Bold,
        fontSize = size,
        lineHeight = lineHeight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun NewsDeck(text: String, maxLines: Int) {
    Text(
        text,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun NewsPhoto(
    image: ImageBitmap?,
    waiting: Boolean,
    contentDescription: String?,
    modifier: Modifier
) {
    Box(
        modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (waiting) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun NewsDoubleRule() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        NewsBar(2.5.dp)
        NewsBar(1.dp)
    }
}

@Composable
private fun NewsBar(thickness: androidx.compose.ui.unit.Dp, alpha: Float = 1f) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(thickness)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha))
    )
}

private fun newsText(raw: String, translateEnabled: Boolean, cache: Map<String, String>): String {
    if (!translateEnabled) return raw
    return cache[raw] ?: raw
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
