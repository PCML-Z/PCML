package com.pmcl.core.market;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MC百科只作为中文索引：搜索页给出条目，条目页里的 Modrinth / CurseForge 链接再交给现有市场客户端。
 * 链接是页面上的 base64，在本地解开，不跟随 link.mcmod.cn 跳转。
 */
public final class McmodCatalog {

    private static final int MAX_PAGE_BYTES = 2 * 1024 * 1024;
    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\b[^>]*href=\"(https://www\\.mcmod\\.cn/(class|modpack)/(\\d+)\\.html)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern BODY = Pattern.compile(
            "<div class=\"body\">(.*?)</div>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TOTAL = Pattern.compile("找到约\\s*(\\d+)\\s*条结果，共约\\s*(\\d+)\\s*页");
    private static final Pattern COVER = Pattern.compile(
            "(?i)(?:https?:)?//i\\.mcmod\\.cn/((?:class|modpack)/cover/[A-Za-z0-9_./-]+\\.(?:jpg|jpeg|png|webp))(?:@[0-9]+x[0-9]+\\.jpg)?");
    private static final Pattern TARGET = Pattern.compile(
            "link\\.mcmod\\.cn/target/([A-Za-z0-9+/=_-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE = Pattern.compile(
            "data-original-title=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern DIRECT = Pattern.compile(
            "https?://(?:www\\.)?(?:curseforge\\.com|modrinth\\.com|github\\.com)/[^\\s\"'<>]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,80}");

    private volatile OkHttpClient http;
    /** 条目页会先用来取封面，点开时再取外链，同一页不重复下载。 */
    private final Map<String, String> entryPages = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 40;
        }
    });

    public McmodCatalog(OkHttpClient http) {
        this.http = http;
    }

    public void updateHttpClient(OkHttpClient http) {
        if (http != null) this.http = http;
    }

    /** pageIndex 从 0 开始。projectType 为 modpack 时查整合包，其余查模组条目。 */
    public SearchPage search(String query, String projectType, int pageIndex) throws IOException {
        String q = sanitizeQuery(query);
        if (q.isEmpty()) return new SearchPage(List.of(), 0, 0);
        int filter = "modpack".equalsIgnoreCase(projectType) ? 2 : 1;
        int page = Math.max(1, pageIndex + 1);
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host("search.mcmod.cn")
                .addPathSegment("s")
                .addQueryParameter("key", q)
                .addQueryParameter("filter", String.valueOf(filter))
                .addQueryParameter("page", String.valueOf(page))
                .build();
        return parseSearch(get(url));
    }

    /** 只接受百科自己的数字条目页，从中取出 Modrinth / CurseForge 链接。 */
    public List<HostLink> linksFor(String kind, String id) throws IOException {
        if (id == null || !id.matches("\\d{1,10}")) {
            throw new IOException("invalid mcmod id");
        }
        String section = "modpack".equals(kind) ? "modpack" : "class";
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host("www.mcmod.cn")
                .addPathSegment(section)
                .addPathSegment(id + ".html")
                .build();
        return parseLinks(get(url));
    }

    /** 从条目页取出封面，缩成小图地址。没有封面时返回空串。 */
    public String coverFor(String kind, String id) throws IOException {
        if (id == null || !id.matches("\\d{1,10}")) return "";
        String section = "modpack".equals(kind) ? "modpack" : "class";
        HttpUrl url = new HttpUrl.Builder()
                .scheme("https")
                .host("www.mcmod.cn")
                .addPathSegment(section)
                .addPathSegment(id + ".html")
                .build();
        return parseCover(get(url));
    }

    static String parseCover(String html) {
        if (html == null || html.isEmpty()) return "";
        Matcher matcher = COVER.matcher(html);
        if (!matcher.find()) return "";
        String path = matcher.group(1);
        if (path.contains("..") || path.contains("//")) return "";
        return "https://i.mcmod.cn/" + path + "@128x128.jpg";
    }

    static String sanitizeQuery(String query) {
        if (query == null) return "";
        String trimmed = query.replaceAll("[\\p{Cntrl}]+", " ").trim();
        if (trimmed.length() > 40) trimmed = trimmed.substring(0, 40).trim();
        return trimmed;
    }

    static SearchPage parseSearch(String html) {
        if (html == null || html.isEmpty()) return new SearchPage(List.of(), 0, 0);
        int totalHits = 0;
        int pageCount = 0;
        Matcher totals = TOTAL.matcher(html);
        if (totals.find()) {
            totalHits = Integer.parseInt(totals.group(1));
            pageCount = Integer.parseInt(totals.group(2));
        }
        List<Entry> entries = new ArrayList<>();
        LinkedHashMap<String, Entry> seen = new LinkedHashMap<>();
        String[] parts = html.split("<div class=\"result-item\">");
        for (int i = 1; i < parts.length; i++) {
            String chunk = parts[i];
            Matcher anchor = ANCHOR.matcher(chunk);
            String kind = null;
            String id = null;
            String name = null;
            while (anchor.find()) {
                String text = textOf(anchor.group(4));
                if (text.isEmpty() || text.contains("mcmod.cn/")) continue;
                kind = "modpack".equals(anchor.group(2)) ? "modpack" : "mod";
                id = anchor.group(3);
                name = text;
                break;
            }
            if (id == null || name == null) continue;
            String summary = "";
            Matcher body = BODY.matcher(chunk);
            if (body.find()) summary = textOf(body.group(1));
            if (summary.length() > 180) summary = summary.substring(0, 180).trim();
            if (name.length() > 120) name = name.substring(0, 120).trim();
            seen.putIfAbsent(kind + "/" + id, new Entry(kind, id, name, summary));
        }
        entries.addAll(seen.values());
        if (pageCount <= 0 && !entries.isEmpty()) pageCount = 1;
        return new SearchPage(entries, totalHits, pageCount);
    }

    static List<HostLink> parseLinks(String html) {
        if (html == null || html.isEmpty()) return List.of();
        String scope = html;
        boolean otherDownloads = false;
        int frame = html.indexOf("class=\"common-link-frame");
        if (frame >= 0) {
            int end = html.indexOf("</ul>", frame);
            if (end < 0 || end - frame > 100_000) end = Math.min(html.length(), frame + 100_000);
            scope = html.substring(frame, end);
            otherDownloads = true;
        }
        LinkedHashMap<String, HostLink> found = new LinkedHashMap<>();
        Matcher targets = TARGET.matcher(scope);
        while (targets.find()) {
            int start = Math.max(0, targets.start() - 500);
            String around = scope.substring(start, targets.end());
            String label = lastTitle(around);
            addLink(found, parseHostUrl(decodeTarget(targets.group(1)), label, otherDownloads));
        }
        Matcher direct = DIRECT.matcher(scope);
        while (direct.find()) {
            addLink(found, parseHostUrl(direct.group(), "", otherDownloads));
        }
        List<HostLink> links = new ArrayList<>(found.values());
        links.sort((a, b) -> {
            int source = Integer.compare(sourceRank(a.source), sourceRank(b.source));
            if (source != 0) return source;
            int type = a.projectType.compareTo(b.projectType);
            if (type != 0) return type;
            return a.slug.compareToIgnoreCase(b.slug);
        });
        return links;
    }

    private static int sourceRank(String source) {
        return switch (source) {
            case "modrinth" -> 0;
            case "curseforge" -> 1;
            case "github" -> 2;
            default -> 3;
        };
    }

    static String decodeTarget(String token) {
        if (token == null || token.isBlank()) return null;
        String padded = token;
        int mod = padded.length() % 4;
        if (mod != 0) padded = padded + "====".substring(mod);
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(padded);
        } catch (IllegalArgumentException e) {
            try {
                raw = Base64.getUrlDecoder().decode(padded);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return new String(raw, StandardCharsets.UTF_8).trim();
    }

    static HostLink parseHostUrl(String rawUrl, String label) {
        return parseHostUrl(rawUrl, label, false);
    }

    /** 可在浏览器打开的 https 页面。启动器不下载、不安装这类地址。 */
    public static boolean isManualBrowseUrl(String url) {
        HostLink link = parseHostUrl(url, "", true);
        return link != null && !link.opensInMarket() && url.equals(link.url);
    }

    static HostLink parseHostUrl(String rawUrl, String label, boolean allowOtherDownloads) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        String url = rawUrl.trim();
        if (url.regionMatches(true, 0, "http://", 0, 7)) {
            url = "https://" + url.substring(7);
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
        if (uri.getUserInfo() != null) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (unsafePath(uri.getRawPath()) || unsafePath(uri.getRawQuery())) return null;
        List<String> parts = segments(uri.getPath());
        for (String part : parts) {
            if (".".equals(part) || "..".equals(part)) return null;
        }
        String cleanLabel = textOf(label == null ? "" : label);
        if (cleanLabel.length() > 80) cleanLabel = cleanLabel.substring(0, 80).trim();
        if ("modrinth.com".equals(host) || "www.modrinth.com".equals(host)) {
            if (parts.size() < 2) return null;
            String type = modrinthType(parts.get(0));
            if (type == null) return null;
            String slug = slugOf(parts.get(1));
            if (slug == null) return null;
            return new HostLink("modrinth", type, slug, cleanLabel, null, null);
        }
        if ("curseforge.com".equals(host) || "www.curseforge.com".equals(host)) {
            if (parts.size() >= 2 && "projects".equalsIgnoreCase(parts.get(0)) && parts.get(1).matches("\\d{1,12}")) {
                return new HostLink("curseforge", "", parts.get(1), cleanLabel, parts.get(1), null);
            }
            if (parts.size() < 3 || !"minecraft".equalsIgnoreCase(parts.get(0))) return null;
            String type = curseforgeType(parts.get(1));
            if (type == null) return null;
            String slug = slugOf(parts.get(2));
            if (slug == null) return null;
            return new HostLink("curseforge", type, slug, cleanLabel, null, null);
        }
        if ("www.github.com".equals(host)) host = "github.com";
        if ("github.com".equals(host)) {
            if (parts.size() < 2) return null;
            if (slugOf(parts.get(0)) == null || slugOf(parts.get(1)) == null) return null;
            String page = pageUrl(host, uri);
            if (page == null) return null;
            return new HostLink("github", "", parts.get(0) + "/" + parts.get(1), cleanLabel, null, page);
        }
        if (!allowOtherDownloads || blockedDownloadHost(host) || referenceOnly(cleanLabel, host)) return null;
        String page = pageUrl(host, uri);
        if (page == null) return null;
        String slug = parts.isEmpty() ? host : host + "/" + parts.get(0);
        if (slug.length() > 80) slug = slug.substring(0, 80);
        return new HostLink("external", "", slug, cleanLabel, null, page);
    }

    private static boolean unsafePath(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.length() > 300) return true;
        return value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0 || value.contains("..");
    }

    private static boolean blockedDownloadHost(String host) {
        if (host.isEmpty() || host.length() > 253 || host.indexOf('.') < 0) return true;
        if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local")) return true;
        if (host.startsWith("[") || host.indexOf(':') >= 0) return true;
        if ("mcmod.cn".equals(host) || host.endsWith(".mcmod.cn")) return true;
        String[] bits = host.split("\\.");
        if (bits.length == 4) {
            boolean numeric = true;
            for (String bit : bits) {
                if (!bit.matches("\\d{1,3}")) numeric = false;
            }
            if (numeric) return true;
        }
        return false;
    }

    /** 维基、论坛和存档页不是模组文件来源。带「下载」的标题仍保留。 */
    private static boolean referenceOnly(String label, String host) {
        if ("web.archive.org".equals(host) || host.endsWith(".archive.org")) return true;
        String text = label == null ? "" : label.toLowerCase(Locale.ROOT);
        if (text.contains("下载")) return false;
        return text.contains("wiki") || text.contains("论坛") || text.contains("forum")
                || text.contains("mcbbs") || text.contains("archive")
                || text.contains("license") || text.contains("协议");
    }

    private static String pageUrl(String host, URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String page = "https://" + host + path;
        String query = uri.getRawQuery();
        if (query != null && !query.isEmpty()) page += "?" + query;
        if (page.length() > 500) return null;
        return page;
    }

    private static void addLink(LinkedHashMap<String, HostLink> found, HostLink link) {
        if (link == null) return;
        String key = link.opensInMarket()
                ? link.source + "/" + link.projectType + "/" + link.slug + "/" + (link.curseforgeId == null ? "" : link.curseforgeId)
                : "ext/" + link.url;
        HostLink prev = found.get(key);
        if (prev == null || prev.label.isEmpty()) found.put(key, link);
    }

    private static String lastTitle(String around) {
        Matcher titles = TITLE.matcher(around);
        String label = "";
        while (titles.find()) label = titles.group(1);
        return label;
    }

    private static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) return out;
        for (String part : path.split("/")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    private static String modrinthType(String segment) {
        return switch (segment.toLowerCase(Locale.ROOT)) {
            case "mod" -> "mod";
            case "modpack" -> "modpack";
            case "resourcepack" -> "resourcepack";
            case "shader" -> "shader";
            case "project" -> "";
            default -> null;
        };
    }

    private static String curseforgeType(String segment) {
        return switch (segment.toLowerCase(Locale.ROOT)) {
            case "mc-mods" -> "mod";
            case "modpacks" -> "modpack";
            case "texture-packs", "resource-packs" -> "resourcepack";
            case "shaders" -> "shader";
            default -> null;
        };
    }

    private static String slugOf(String raw) {
        if (raw == null) return null;
        String slug = raw;
        int cut = slug.indexOf('?');
        if (cut >= 0) slug = slug.substring(0, cut);
        cut = slug.indexOf('#');
        if (cut >= 0) slug = slug.substring(0, cut);
        if (!SLUG.matcher(slug).matches()) return null;
        return slug;
    }

    private static String textOf(String html) {
        String text = html.replaceAll("(?s)<[^>]+>", "");
        text = text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");
        text = text.replaceAll("\\[[^\\[\\]]{0,80}]", "");
        return text.replaceAll("\\s+", " ").trim();
    }

    private String get(HttpUrl url) throws IOException {
        boolean entry = "www.mcmod.cn".equals(url.host());
        String key = url.toString();
        if (entry) {
            String cached = entryPages.get(key);
            if (cached != null) return cached;
        }
        String body = download(url);
        if (entry) entryPages.put(key, body);
        return body;
    }

    private String download(HttpUrl url) throws IOException {
        OkHttpClient client = http.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .addNetworkInterceptor(chain -> {
                    String host = chain.request().url().host();
                    if (!"search.mcmod.cn".equals(host) && !"www.mcmod.cn".equals(host)) {
                        throw new IOException("blocked host: " + host);
                    }
                    return chain.proceed(chain.request());
                })
                .build();
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", "PMCL/1.0")
                .header("Accept", "text/html")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("HTTP " + resp.code());
            }
            byte[] bytes = resp.body().byteStream().readNBytes(MAX_PAGE_BYTES + 1);
            if (bytes.length > MAX_PAGE_BYTES) throw new IOException("mcmod page too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    public static final class Entry {
        private final String kind;
        private final String id;
        private final String name;
        private final String summary;
        private final String coverUrl;

        public Entry(String kind, String id, String name, String summary) {
            this(kind, id, name, summary, "");
        }

        public Entry(String kind, String id, String name, String summary, String coverUrl) {
            this.kind = kind;
            this.id = id;
            this.name = name;
            this.summary = summary == null ? "" : summary;
            this.coverUrl = coverUrl == null ? "" : coverUrl;
        }

        public Entry withCover(String coverUrl) {
            return new Entry(kind, id, name, summary, coverUrl);
        }

        public String getKind() { return kind; }
        public String getId() { return id; }
        public String getName() { return name; }
        public String getSummary() { return summary; }
        public String getCoverUrl() { return coverUrl; }
    }

    public static final class HostLink {
        private final String source;
        private final String projectType;
        private final String slug;
        private final String label;
        private final String curseforgeId;
        private final String url;

        public HostLink(String source, String projectType, String slug, String label, String curseforgeId) {
            this(source, projectType, slug, label, curseforgeId, null);
        }

        public HostLink(String source, String projectType, String slug, String label, String curseforgeId, String url) {
            this.source = source;
            this.projectType = projectType == null ? "" : projectType;
            this.slug = slug;
            this.label = label == null ? "" : label;
            this.curseforgeId = curseforgeId;
            this.url = url == null || url.isBlank() ? null : url;
        }

        public boolean opensInMarket() {
            return "modrinth".equals(source) || "curseforge".equals(source);
        }

        public String getSource() { return source; }
        public String getProjectType() { return projectType; }
        public String getSlug() { return slug; }
        public String getLabel() { return label; }
        public String getCurseforgeId() { return curseforgeId; }
        public String getUrl() { return url; }
    }

    public static final class SearchPage {
        private final List<Entry> entries;
        private final int totalHits;
        private final int pageCount;

        public SearchPage(List<Entry> entries, int totalHits, int pageCount) {
            this.entries = entries == null ? List.of() : List.copyOf(entries);
            this.totalHits = totalHits;
            this.pageCount = pageCount;
        }

        public List<Entry> getEntries() { return entries; }
        public int getTotalHits() { return totalHits; }
        public int getPageCount() { return pageCount; }
    }
}
