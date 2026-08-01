package com.lash.pmcl.core.news

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minecraft 新闻客户端：抓取并解析 Minecraft.net 官方 RSS。
 *
 * 数据源：https://www.minecraft.net/feeds/community-content/rss
 *
 * 使用 Android 内置 DOM 解析器，无需额外 RSS 库依赖。
 *
 * 网络容错：OkHttp 内置 3 次重试（间隔 1s/2s/4s 指数退避），
 * 针对 SSL 握手失败/网络抖动做容错。
 */
class NewsClient(client: OkHttpClient? = null) {

    @Volatile
    private var http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(30))
        .build()

    /**
     * 复用外部 OkHttpClient（推荐）：自动应用代理配置与共享连接池。
     * 当用户在设置中配置代理后，新闻请求也能走代理，解决 minecraft.net SSL 握手失败问题。
     * 用法：NewsClient(yourClient)
     */

    /** 更新 OkHttpClient 引用（用户在设置中修改代理后调用）。 */
    fun updateHttpClient(http: OkHttpClient) {
        this.http = http
    }

    /**
     * 异步拉取新闻列表。
     *
     * @param limit 最多返回条数（<=0 表示用默认值 20）
     */
    fun fetch(limit: Int): CompletableFuture<List<NewsItem>> {
        val max = if (limit <= 0) DEFAULT_LIMIT else limit
        return CompletableFuture.supplyAsync<List<NewsItem>> {
            var last: Exception? = null
            val req = Request.Builder()
                .url(FEED_URL)
                .header("User-Agent", "PMCL/1.0")
                .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                .get()
                .build()
            for (attempt in 0..RETRY) {
                try {
                    val result = http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw RuntimeException("RSS 请求失败：HTTP ${resp.code}")
                        }
                        val bytes = readBodyCapped(resp)
                        parseBytes(bytes, max)
                    }
                    return@supplyAsync result
                } catch (e: Exception) {
                    last = e
                    if (attempt < RETRY) {
                        try {
                            Thread.sleep(RETRY_BASE_MS * (1L shl attempt))
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            }
            val msg = last?.message ?: "未知错误"
            throw RuntimeException(friendlyError(msg), last)
        }
    }

    /** 同步拉取的便捷重载（默认 20 条） */
    fun fetch(): CompletableFuture<List<NewsItem>> = fetch(DEFAULT_LIMIT)

    /**
     * 异步抓取文章页 HTML，仅提取封面图 URL。
     * 比 fetchArticle 轻量：不解析正文，只提取 hero/首张 article-media 图片。
     * 用于 RSS 列表加载后回填 NewsItem.imageUrl。
     *
     * @param articleUrl 文章链接
     * @return 封面图 URL（失败返回空串）
     */
    fun fetchCoverImage(articleUrl: String): CompletableFuture<String> {
        return CompletableFuture.supplyAsync<String> {
            try {
                if (!isAllowedMinecraftHost(articleUrl)) {
                    System.err.println("[NewsClient] 拒绝非 minecraft.net 封面抓取: $articleUrl")
                    return@supplyAsync ""
                }
                val req = Request.Builder()
                    .url(articleUrl)
                    .header("User-Agent", "PMCL/1.0")
                    .header("Accept", "text/html, */*")
                    .get()
                    .build()
                val html: String? = http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else {
                        val bytes = readBodyCapped(resp)
                        String(bytes, StandardCharsets.UTF_8)
                    }
                }
                if (html == null) return@supplyAsync ""
                extractCoverImageFromHtml(html)
            } catch (e: Exception) {
                ""
            }
        }
    }

    /**
     * 异步抓取并提取单篇新闻的正文 HTML。
     * 从 minecraft.net 文章页面提取 class="article-text" 内的富文本（含 <p>、<h2> 等），
     * 拼接为 HTML 片段供 UI 层渲染。同时提取页面内的所有图片 URL。
     *
     * @param articleUrl 文章链接（来自 NewsItem.link）
     */
    fun fetchArticle(articleUrl: String): CompletableFuture<ArticleContent> {
        return CompletableFuture.supplyAsync<ArticleContent> {
            if (!isAllowedMinecraftHost(articleUrl)) {
                throw RuntimeException("拒绝非 minecraft.net 文章抓取: $articleUrl")
            }
            var last: Exception? = null
            val req = Request.Builder()
                .url(articleUrl)
                .header("User-Agent", "PMCL/1.0")
                .header("Accept", "text/html, */*")
                .get()
                .build()
            for (attempt in 0..RETRY) {
                try {
                    val result = http.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw RuntimeException("文章请求失败：HTTP ${resp.code}")
                        }
                        val bytes = readBodyCapped(resp)
                        val html = String(bytes, StandardCharsets.UTF_8)
                        extractArticleContent(html, articleUrl)
                    }
                    return@supplyAsync result
                } catch (e: Exception) {
                    last = e
                    if (attempt < RETRY) {
                        try {
                            Thread.sleep(RETRY_BASE_MS * (1L shl attempt))
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            }
            val msg = last?.message ?: "未知错误"
            throw RuntimeException(friendlyError(msg), last)
        }
    }

    /**
     * 从 minecraft.net 文章 HTML 提取标题、正文 HTML 片段、图片列表。
     * minecraft.net 文章正文位于
     * <div class="article-text"><div class="MC_Link_Style_RichText">...</div></div>，
     * 标题在 <h1 class="MC_Heading_1">。
     */
    fun extractArticleContent(html: String, url: String): ArticleContent {
        // 提取标题
        var title: String? = extractFirstGroup(
            html, "<h1[^>]*class=\"[^\"]*MC_Heading_1[^\"]*\"[^>]*>(.*?)</h1>", 1
        )
        if (title != null) title = stripHtml(title).trim()
        if (title.isNullOrEmpty()) {
            title = extractFirstGroup(html, "<title>(.*?)</title>", 1)
            if (title != null) {
                title = title.replace(Regex("\\s*\\|\\s*Minecraft\\s*$"), "").trim()
            }
        }
        if (title == null) title = ""

        // 提取正文：所有 article-text > MC_Link_Style_RichText 的内容
        val bodyHtml = StringBuilder()
        for (match in RICH_TEXT_PATTERN.findAll(html)) {
            val chunk = match.groupValues[1].trim()
            // 只保留 <p>、<h2>、<h3>、<ul>、<ol>、<li>、<strong>、<em>、<a>、<img> 相关内容
            if (chunk.contains("<p>") || chunk.contains("<h2>") || chunk.contains("<ul>")) {
                bodyHtml.append(chunk).append("\n")
            }
        }

        // 提取文章内图片 URL（article-media 里的 img src）
        val images = mutableListOf<String>()
        for (match in ARTICLE_MEDIA_IMG_PATTERN.findAll(html)) {
            var src = match.groupValues[1]
            // 补全相对路径
            if (src.startsWith("/")) src = "https://www.minecraft.net$src"
            if (!images.contains(src)) images.add(src)
        }

        // 封面图：articleHeroA 或第一张 article-media 图
        var coverImage = if (images.isNotEmpty()) images[0] else ""
        // 优先从 hero 区提取
        val heroImg = extractFirstGroup(
            html, "<div class=\"MC_articleHeroA\".*?<img[^>]+src=\"([^\"]+)\"", 1
        )
        if (heroImg != null) {
            coverImage = if (heroImg.startsWith("/")) "https://www.minecraft.net$heroImg" else heroImg
        }

        return ArticleContent(title, bodyHtml.toString(), images, coverImage, url)
    }

    /** 生成友好的中文错误信息，提示用户可能的解决方案。 */
    private fun friendlyError(rawMsg: String?): String {
        val msg = rawMsg ?: ""
        return when {
            // SSL 握手失败：通常是网络被干扰或需要代理
            msg.contains("handshake") || msg.contains("SSL") || msg.contains("TLS") ||
                msg.contains("reset") || msg.contains("broken pipe") ->
                "无法连接 minecraft.net（SSL 握手失败）。可能原因：网络被干扰，请在设置中配置代理后重试。原始错误：$msg"
            // 连接超时
            msg.contains("timeout") || msg.contains("timed out") ->
                "连接 minecraft.net 超时。请检查网络或配置代理。原始错误：$msg"
            // DNS 解析失败
            msg.contains("UnknownHost") || msg.contains("Unable to resolve") ->
                "无法解析 minecraft.net 域名。请检查网络或 DNS 设置。原始错误：$msg"
            else -> "拉取新闻失败：$msg"
        }
    }

    /** 从文章 HTML 提取封面图 URL：优先 hero 区，其次首张 article-media 图片 */
    private fun extractCoverImageFromHtml(html: String): String {
        val heroImg = extractFirstGroup(
            html, "<div class=\"MC_articleHeroA\".*?<img[^>]+src=\"([^\"]+)\"", 1
        )
        if (!heroImg.isNullOrEmpty()) {
            return if (heroImg.startsWith("/")) "https://www.minecraft.net$heroImg" else heroImg
        }
        val imgM = ARTICLE_MEDIA_IMG_PATTERN.find(html)
        if (imgM != null) {
            var src = imgM.groupValues[1]
            if (src.startsWith("/")) src = "https://www.minecraft.net$src"
            return src
        }
        return ""
    }

    /** 正则提取第一个匹配的指定捕获组 */
    private fun extractFirstGroup(input: String, regex: String, group: Int): String? {
        val p = Regex(regex, RegexOption.DOT_MATCHES_ALL)
        val m = p.find(input)
        return m?.groupValues?.getOrNull(group)
    }

    /**
     * 解析 RSS XML 字节流为 NewsItem 列表。
     * 使用字节流而非字符串，让 DOM 解析器根据 XML 声明自动识别编码（utf-8/utf-16 等）。
     */
    @Throws(Exception::class)
    fun parseBytes(xml: ByteArray, limit: Int): List<NewsItem> {
        val factory = DocumentBuilderFactory.newInstance()
        // 禁用 DOCTYPE 和外部实体，防止 XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(xml))

        val items = mutableListOf<NewsItem>()
        val itemNodes = doc.getElementsByTagName("item")
        val count = minOf(itemNodes.length, limit)
        for (i in 0 until count) {
            val node = itemNodes.item(i)
            if (node is Element) {
                items.add(parseItem(node))
            }
        }
        return items
    }

    private fun parseItem(el: Element): NewsItem {
        val title = getTag(el, "title")
        val description = getTag(el, "description")
        val pubDate = getTag(el, "pubDate")
        val category = getTag(el, "category")

        // link 可能是标准 RSS 的 <link>文本</link>，
        // 也可能是 Atom 命名空间的 <a10:link href="..."/>（Minecraft.net 用这种）
        var link = getTag(el, "link")
        if (link.isEmpty()) {
            link = getAtomLinkHref(el)
        }

        // 从 description HTML 中提取首张图片 URL
        var imageUrl = ""
        if (description.isNotEmpty()) {
            val m = IMG_PATTERN.find(description)
            if (m != null) {
                imageUrl = m.groupValues[1]
            }
        }

        // 清理 description 中的 HTML 标签，留纯文本摘要
        val cleanDesc = stripHtml(description)

        return NewsItem(title, link, cleanDesc, pubDate, category, imageUrl)
    }

    /**
     * 提取 Atom 命名空间下 link 元素的 href 属性。
     * 兼容 a10:link（Minecraft.net RSS）和普通 link（带 xmlns）。
     */
    private fun getAtomLinkHref(parent: Element): String {
        // 尝试 a10:link
        val a10 = parent.getElementsByTagName("a10:link")
        if (a10.length > 0) {
            val first = a10.item(0)
            if (first is Element) {
                val href = first.getAttribute("href")
                if (href.isNotEmpty()) return href.trim()
            }
        }
        // 尝试不带前缀的 link（带命名空间），getElementsByTagNameNS
        try {
            val atomLinks = parent.getElementsByTagNameNS(
                "http://www.w3.org/2005/Atom", "link"
            )
            if (atomLinks.length > 0) {
                val first = atomLinks.item(0)
                if (first is Element) {
                    val href = first.getAttribute("href")
                    if (href.isNotEmpty()) return href.trim()
                }
            }
        } catch (_: Throwable) {}
        return ""
    }

    /** 读取元素下指定标签的文本内容 */
    private fun getTag(parent: Element, tagName: String): String {
        val list = parent.getElementsByTagName(tagName)
        if (list.length == 0) return ""
        val first = list.item(0)
        return first.textContent?.trim() ?: ""
    }

    companion object {
        private const val FEED_URL = "https://www.minecraft.net/feeds/community-content/rss"
        private const val DEFAULT_LIMIT = 20

        /** OkHttp 响应体上限，防异常大包 OOM */
        private const val MAX_BODY_BYTES = 8L * 1024 * 1024

        /** 重试次数（总请求次数 = RETRY + 1） */
        private const val RETRY = 3

        /** 重试基础间隔（毫秒），实际为 base * 2^attempt */
        private const val RETRY_BASE_MS = 1000L

        /** 从 &lt;description&gt; HTML 中提取首张图片 URL 的正则 */
        private val IMG_PATTERN = Regex(
            "<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE
        )

        /** 匹配所有 HTML 标签，用于剥离标签 */
        private val HTML_TAG_PATTERN = Regex("<[^>]+>")

        /** 匹配连续空白字符，用于折叠空白 */
        private val WHITESPACE_PATTERN = Regex("\\s+")

        /** 提取 minecraft.net 文章正文富文本块 */
        private val RICH_TEXT_PATTERN = Regex(
            "<div class=\"MC_Link_Style_RichText\">(.*?)</div>",
            RegexOption.DOT_MATCHES_ALL
        )

        /** 提取 article-media 区块中的图片 URL */
        private val ARTICLE_MEDIA_IMG_PATTERN = Regex(
            "<div class=\"article-media\">.*?<img[^>]+src=\"([^\"]+)\"",
            RegexOption.DOT_MATCHES_ALL
        )

        /** 读取响应体，超过 MAX_BODY_BYTES 拒绝。 */
        @Throws(IOException::class)
        fun readBodyCapped(resp: Response): ByteArray {
            val body = resp.body ?: return ByteArray(0)
            val cl = body.contentLength()
            if (cl > MAX_BODY_BYTES) {
                throw IOException("响应体过大 ($cl > $MAX_BODY_BYTES)")
            }
            val bytes = body.bytes()
            if (bytes.size > MAX_BODY_BYTES) {
                throw IOException("响应体过大 (${bytes.size} > $MAX_BODY_BYTES)")
            }
            return bytes
        }

        /** 仅允许 minecraft.net 及其子域，防止封面抓取变成开放 SSRF */
        fun isAllowedMinecraftHost(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            try {
                val uri = URI.create(url.trim())
                val scheme = uri.scheme ?: return false
                val s = scheme.lowercase(Locale.ROOT)
                if (s != "http" && s != "https") return false
                val host = uri.host ?: return false
                val h = host.lowercase(Locale.ROOT)
                return h == "minecraft.net" || h.endsWith(".minecraft.net")
            } catch (e: Exception) {
                return false
            }
        }

        /** 粗略剥离 HTML 标签，转纯文本 */
        fun stripHtml(html: String?): String {
            if (html.isNullOrEmpty()) return ""
            // 去 HTML 实体常见转换（字面量替换，避免每次编译正则）
            var s = html
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            // 去所有标签
            s = HTML_TAG_PATTERN.replace(s, "")
            // 折叠空白
            s = WHITESPACE_PATTERN.replace(s, " ").trim()
            return s
        }
    }
}
