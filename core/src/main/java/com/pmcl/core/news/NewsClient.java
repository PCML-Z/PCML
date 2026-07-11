package com.pmcl.core.news;

import com.pmcl.core.download.CurlFallback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 新闻客户端：抓取并解析 Minecraft.net 官方 RSS。
 * <p>
 * 数据源：https://www.minecraft.net/feeds/community-content/rss
 * <p>
 * 使用 JDK 内置 DOM 解析器，无需额外 RSS 库依赖。
 * <p>
 * 网络容错：复用 DownloadManager 的 OkHttpClient（自动应用用户代理配置），
 * 内置 3 次重试（间隔 1s/2s/4s），针对 SSL 握手失败/网络抖动做容错。
 */
public final class NewsClient {

    private static final String FEED_URL = "https://www.minecraft.net/feeds/community-content/rss";
    private static final int DEFAULT_LIMIT = 20;

    /** 重试次数（总请求次数 = RETRY + 1） */
    private static final int RETRY = 3;
    /** 重试基础间隔（毫秒），实际为 base * 2^attempt */
    private static final long RETRY_BASE_MS = 1000L;

    /** 从 &lt;description&gt; HTML 中提取首张图片 URL 的正则 */
    private static final Pattern IMG_PATTERN =
            Pattern.compile("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private volatile OkHttpClient http;

    /** 默认构造：自建客户端（无代理，仅用于无 DownloadManager 的场景） */
    public NewsClient() {
        this.http = new OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(15))
                .readTimeout(java.time.Duration.ofSeconds(30))
                .build();
    }

    /**
     * 复用外部 OkHttpClient（推荐）：自动应用代理配置与共享连接池。
     * 当用户在设置中配置代理后，新闻请求也能走代理，解决 minecraft.net SSL 握手失败问题。
     */
    public NewsClient(OkHttpClient http) {
        this.http = http;
    }

    /**
     * 更新 OkHttpClient 引用（用户在设置中修改代理后调用）。
     */
    public void updateHttpClient(OkHttpClient http) {
        this.http = http;
    }

    /**
     * 异步拉取新闻列表。
     *
     * @param limit 最多返回条数（&lt;=0 表示用默认值 20）
     * @return CompletableFuture&lt;List&lt;NewsItem&gt;&gt;
     */
    public CompletableFuture<List<NewsItem>> fetch(int limit) {
        final int max = limit <= 0 ? DEFAULT_LIMIT : limit;
        return CompletableFuture.supplyAsync(() -> {
            Request req = new Request.Builder()
                    .url(FEED_URL)
                    .header("User-Agent", "PMCL/1.0")
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .get()
                    .build();
            Exception last = null;
            for (int attempt = 0; attempt <= RETRY; attempt++) {
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        throw new RuntimeException("RSS 请求失败：HTTP " + resp.code());
                    }
                    // 关键：直接用字节流，让 DOM 解析器根据 XML 声明识别编码
                    // （Minecraft.net RSS 声明为 utf-16，OkHttp.string() 默认按 UTF-8 解码会破坏 BOM）
                    byte[] bytes = resp.body() != null ? resp.body().bytes() : new byte[0];
                    return parseBytes(bytes, max);
                } catch (Exception e) {
                    last = e;
                    // 任何网络错误都立即 fallback 到 curl（GFW 环境下 OkHttp 的
                    // TLS 指纹/HTTP2 协议均可能被干扰，不只 SSL 握手失败）
                    if (CurlFallback.isAvailable()) {
                        try {
                            byte[] bytes = CurlFallback.getBytes(FEED_URL, "GET", null);
                            return parseBytes(bytes, max);
                        } catch (Exception curlEx) {
                            last = curlEx;
                            // curl 也失败：继续重试 OkHttp
                        }
                    }
                    if (attempt < RETRY) {
                        try {
                            Thread.sleep(RETRY_BASE_MS * (1L << attempt));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            // 友好的中文错误提示，帮助用户定位问题
            String msg = last != null ? last.getMessage() : "未知错误";
            throw new RuntimeException(friendlyError(msg), last);
        });
    }

    /**
     * 生成友好的中文错误信息，提示用户可能的解决方案。
     */
    private String friendlyError(String rawMsg) {
        if (rawMsg == null) rawMsg = "";
        // SSL 握手失败：通常是网络被干扰或需要代理
        if (rawMsg.contains("handshake") || rawMsg.contains("SSL") || rawMsg.contains("TLS")
                || rawMsg.contains("reset") || rawMsg.contains("broken pipe")) {
            return "无法连接 minecraft.net（SSL 握手失败）。可能原因：网络被干扰，请在设置中配置代理后重试。原始错误：" + rawMsg;
        }
        // 连接超时
        if (rawMsg.contains("timeout") || rawMsg.contains("timed out")) {
            return "连接 minecraft.net 超时。请检查网络或配置代理。原始错误：" + rawMsg;
        }
        // DNS 解析失败
        if (rawMsg.contains("UnknownHost") || rawMsg.contains("Unable to resolve")) {
            return "无法解析 minecraft.net 域名。请检查网络或 DNS 设置。原始错误：" + rawMsg;
        }
        return "拉取新闻失败：" + rawMsg;
    }

    /** 同步拉取的便捷重载（默认 20 条） */
    public CompletableFuture<List<NewsItem>> fetch() {
        return fetch(DEFAULT_LIMIT);
    }

    /**
     * 异步抓取并提取单篇新闻的正文 HTML。
     * 从 minecraft.net 文章页面提取 class="article-text" 内的富文本（含 <p>、<h2> 等），
     * 拼接为 HTML 片段供 UI 层渲染。同时提取页面内的所有图片 URL。
     *
     * @param articleUrl 文章链接（来自 NewsItem.getLink()）
     * @return CompletableFuture<ArticleContent>
     */
    public CompletableFuture<ArticleContent> fetchArticle(String articleUrl) {
        return CompletableFuture.supplyAsync(() -> {
            Request req = new Request.Builder()
                    .url(articleUrl)
                    .header("User-Agent", "PMCL/1.0")
                    .header("Accept", "text/html, */*")
                    .get()
                    .build();
            Exception last = null;
            for (int attempt = 0; attempt <= RETRY; attempt++) {
                try (Response resp = http.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        throw new RuntimeException("文章请求失败：HTTP " + resp.code());
                    }
                    byte[] bytes = resp.body() != null ? resp.body().bytes() : new byte[0];
                    String html = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    return extractArticleContent(html, articleUrl);
                } catch (Exception e) {
                    last = e;
                    // 任何网络错误都立即 fallback 到 curl（与 fetch() 一致）
                    if (CurlFallback.isAvailable()) {
                        try {
                            String html = CurlFallback.getString(articleUrl);
                            return extractArticleContent(html, articleUrl);
                        } catch (Exception curlEx) {
                            last = curlEx;
                        }
                    }
                    if (attempt < RETRY) {
                        try {
                            Thread.sleep(RETRY_BASE_MS * (1L << attempt));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            String msg = last != null ? last.getMessage() : "未知错误";
            throw new RuntimeException(friendlyError(msg), last);
        });
    }

    /**
     * 从 minecraft.net 文章 HTML 提取标题、正文 HTML 片段、图片列表。
     * minecraft.net 文章正文位于 <div class="article-text"><div class="MC_Link_Style_RichText">...</div></div>，
     * 标题在 <h1 class="MC_Heading_1">。
     */
    public ArticleContent extractArticleContent(String html, String url) {
        // 提取标题
        String title = extractFirstGroup(html, "<h1[^>]*class=\"[^\"]*MC_Heading_1[^\"]*\"[^>]*>(.*?)</h1>", 1);
        if (title != null) title = stripHtml(title).trim();
        if (title == null || title.isEmpty()) {
            title = extractFirstGroup(html, "<title>(.*?)</title>", 1);
            if (title != null) {
                title = title.replaceAll("\\s*\\|\\s*Minecraft\\s*$", "").trim();
            }
        }
        if (title == null) title = "";

        // 提取正文：所有 article-text > MC_Link_Style_RichText 的内容
        StringBuilder bodyHtml = new StringBuilder();
        java.util.regex.Pattern richTextPattern = java.util.regex.Pattern.compile(
                "<div class=\"MC_Link_Style_RichText\">(.*?)</div>", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = richTextPattern.matcher(html);
        while (m.find()) {
            String chunk = m.group(1).trim();
            // 只保留 <p>、<h2>、<h3>、<ul>、<ol>、<li>、<strong>、<em>、<a>、<img> 相关内容
            // 过滤掉图片 caption 之外的无意义内容
            if (chunk.contains("<p>") || chunk.contains("<h2>") || chunk.contains("<ul>")) {
                bodyHtml.append(chunk).append("\n");
            }
        }

        // 提取文章内图片 URL（article-media 里的 img src）
        java.util.List<String> images = new java.util.ArrayList<>();
        java.util.regex.Pattern imgBlockPattern = java.util.regex.Pattern.compile(
                "<div class=\"article-media\">.*?<img[^>]+src=\"([^\"]+)\"", java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher imgM = imgBlockPattern.matcher(html);
        while (imgM.find()) {
            String src = imgM.group(1);
            // 补全相对路径
            if (src.startsWith("/")) src = "https://www.minecraft.net" + src;
            if (!images.contains(src)) images.add(src);
        }

        // 封面图：articleHeroA 或第一张 article-media 图
        String coverImage = "";
        if (!images.isEmpty()) coverImage = images.get(0);
        // 优先从 hero 区提取
        String heroImg = extractFirstGroup(html,
                "<div class=\"MC_articleHeroA\".*?<img[^>]+src=\"([^\"]+)\"", 1);
        if (heroImg != null) {
            if (heroImg.startsWith("/")) heroImg = "https://www.minecraft.net" + heroImg;
            coverImage = heroImg;
        }

        return new ArticleContent(title, bodyHtml.toString(), images, coverImage, url);
    }

    /** 正则提取第一个匹配的指定捕获组 */
    private String extractFirstGroup(String input, String regex, int group) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher m = p.matcher(input);
        if (m.find()) return m.group(group);
        return null;
    }

    /**
     * 解析 RSS XML 字节流为 NewsItem 列表。
     * 使用字节流而非字符串，让 DOM 解析器根据 XML 声明自动识别编码（utf-8/utf-16 等）。
     */
    List<NewsItem> parseBytes(byte[] xml, int limit) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 禁用外部实体，防止 XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new ByteArrayInputStream(xml));

        List<NewsItem> items = new ArrayList<>();
        NodeList itemNodes = doc.getElementsByTagName("item");
        int count = Math.min(itemNodes.getLength(), limit);
        for (int i = 0; i < count; i++) {
            Node node = itemNodes.item(i);
            if (node instanceof Element) {
                items.add(parseItem((Element) node));
            }
        }
        return items;
    }

    private NewsItem parseItem(Element el) {
        String title = getTag(el, "title");
        String description = getTag(el, "description");
        String pubDate = getTag(el, "pubDate");
        String category = getTag(el, "category");

        // link 可能是标准 RSS 的 <link>文本</link>，
        // 也可能是 Atom 命名空间的 <a10:link href="..."/>（Minecraft.net 用这种）
        String link = getTag(el, "link");
        if (link.isEmpty()) {
            link = getAtomLinkHref(el);
        }

        // 从 description HTML 中提取首张图片 URL
        String imageUrl = "";
        if (description != null && !description.isEmpty()) {
            Matcher m = IMG_PATTERN.matcher(description);
            if (m.find()) {
                imageUrl = m.group(1);
            }
        }

        // 清理 description 中的 HTML 标签，留纯文本摘要
        String cleanDesc = stripHtml(description);

        return new NewsItem(title, link, cleanDesc, pubDate, category, imageUrl);
    }

    /**
     * 提取 Atom 命名空间下 link 元素的 href 属性。
     * 兼容 a10:link（Minecraft.net RSS）和普通 link（带 xmlns）。
     */
    private String getAtomLinkHref(Element parent) {
        // 尝试 a10:link
        NodeList a10 = parent.getElementsByTagName("a10:link");
        if (a10.getLength() > 0 && a10.item(0) instanceof Element) {
            String href = ((Element) a10.item(0)).getAttribute("href");
            if (href != null && !href.isEmpty()) return href.trim();
        }
        // 尝试不带前缀的 link（带命名空间），getElementsByTagNameNS
        try {
            NodeList atomLinks = parent.getElementsByTagNameNS(
                    "http://www.w3.org/2005/Atom", "link");
            if (atomLinks.getLength() > 0 && atomLinks.item(0) instanceof Element) {
                String href = ((Element) atomLinks.item(0)).getAttribute("href");
                if (href != null && !href.isEmpty()) return href.trim();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** 读取元素下指定标签的文本内容 */
    private String getTag(Element parent, String tagName) {
        NodeList list = parent.getElementsByTagName(tagName);
        if (list.getLength() == 0) return "";
        Node first = list.item(0);
        return first.getTextContent() != null ? first.getTextContent().trim() : "";
    }

    /** 粗略剥离 HTML 标签，转纯文本 */
    static String stripHtml(String html) {
        if (html == null || html.isEmpty()) return "";
        // 去 HTML 实体常见转换
        String s = html
                .replaceAll("&nbsp;", " ")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&quot;", "\"")
                .replaceAll("&#39;", "'");
        // 去所有标签
        s = s.replaceAll("<[^>]+>", "");
        // 折叠空白
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }
}
