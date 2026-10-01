package com.pmcl.core.market;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.download.DownloadManager;
import com.pmcl.core.plugin.PluginManager;
import com.pmcl.core.util.SsrfChecker;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * PMCL 插件商店。目录在 {@code /wwwroot/cloud/plugin-store/plugins.js}，
 * 对外地址是 {@code https://lash.org.cn/cloud/plugin-store/plugins.js}。
 * 页面只当文本解析，不会执行里面的脚本。
 */
public final class PmclPluginStoreClient {

    public static final String ORIGIN = "https://lash.org.cn";
    public static final String CATALOG = ORIGIN + "/cloud/plugin-store/plugins.js";
    private static final int MAX_BODY = 2_000_000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");

    private final DownloadManager downloads;

    public PmclPluginStoreClient(DownloadManager downloads) {
        this.downloads = downloads;
    }

    public StorePage list(String query, int offset, int limit) throws IOException {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        int safeOffset = Math.max(0, offset);
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<StorePlugin> matched = new ArrayList<>();
        for (StorePlugin plugin : loadAll()) {
            if (q.isEmpty() || contains(plugin, q)) matched.add(plugin);
        }
        int from = Math.min(safeOffset, matched.size());
        int to = Math.min(from + safeLimit, matched.size());
        return new StorePage(matched.size(), new ArrayList<>(matched.subList(from, to)));
    }

    public StorePlugin detail(String id) throws IOException {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IOException("插件 id 无效");
        }
        for (StorePlugin plugin : loadAll()) {
            if (id.equals(plugin.id)) return plugin;
        }
        throw new IOException("没有这个插件");
    }

    private List<StorePlugin> loadAll() throws IOException {
        return parseCatalog(get(HttpUrl.parse(CATALOG)));
    }

    /**
     * 下载安装包，核对 SHA-256，再交给插件管理器。
     * 签名策略与本地安装相同，这里不放宽。
     */
    public void install(StorePlugin plugin, PluginManager plugins) throws Exception {
        if (plugin == null) throw new IOException("没有可安装的插件");
        String hostError = storeHostError(plugin.downloadUrl);
        if (hostError != null) throw new IOException(hostError);
        if (plugin.sha256 == null || !SHA256.matcher(plugin.sha256).matches()) {
            throw new IOException("插件缺少 SHA-256");
        }
        String suffix = "ppk".equals(plugin.packageType) ? ".ppk" : ".jar";
        Path temp = Files.createTempFile("pmcl-store-", suffix);
        try {
            downloads.downloadToSsrfChecked(plugin.downloadUrl, temp);
            String actual = sha256(temp);
            if (!actual.equalsIgnoreCase(plugin.sha256)) {
                throw new IOException("SHA-256 不一致");
            }
            if ("ppk".equals(plugin.packageType)) plugins.installFromPackage(temp);
            else plugins.installFromPath(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 从 {@code var PMCL_PLUGINS = [ ... ];} 里取出数组，不执行脚本。 */
    static List<StorePlugin> parseCatalog(String script) throws IOException {
        if (script == null) throw new IOException("插件商店返回了无法识别的数据");
        int mark = script.indexOf("PMCL_PLUGINS");
        if (mark < 0) throw new IOException("插件商店返回了无法识别的数据");
        int start = script.indexOf('[', mark);
        if (start < 0) throw new IOException("插件商店返回了无法识别的数据");
        JsonArray arr;
        try {
            arr = JsonParser.parseString(sliceJsonArray(script, start)).getAsJsonArray();
        } catch (RuntimeException e) {
            throw new IOException("插件商店返回了无法识别的数据");
        }
        List<StorePlugin> plugins = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            StorePlugin plugin = parsePlugin(el.getAsJsonObject());
            if (plugin != null) plugins.add(plugin);
        }
        return plugins;
    }

    static String sliceJsonArray(String text, int start) throws IOException {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
                continue;
            }
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        throw new IOException("插件商店返回了无法识别的数据");
    }

    private static boolean contains(StorePlugin plugin, String query) {
        return has(plugin.id, query) || has(plugin.name, query) || has(plugin.summary, query)
                || has(plugin.author, query) || has(plugin.description, query);
    }

    private static boolean has(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    static StorePlugin parsePlugin(JsonObject obj) {
        if (obj == null) return null;
        String id = text(obj, "id");
        String downloadUrl = text(obj, "downloadUrl");
        String sha = text(obj, "sha256");
        String type = text(obj, "packageType");
        if (!ID.matcher(id).matches()) return null;
        if (storeHostError(downloadUrl) != null) return null;
        if (!SHA256.matcher(sha).matches()) return null;
        if (!"jar".equals(type) && !"ppk".equals(type)) return null;
        String name = text(obj, "name");
        if (name.isBlank()) name = id;
        String icon = text(obj, "iconUrl");
        if (storeHostError(icon) != null) icon = "";
        String home = text(obj, "homepage");
        if (!home.isBlank() && storeHostError(home) != null) home = "";
        long downloads = 0;
        if (obj.has("downloads") && obj.get("downloads").isJsonPrimitive()) {
            try {
                downloads = obj.get("downloads").getAsLong();
            } catch (RuntimeException ignored) {
                downloads = 0;
            }
        }
        long size = 0;
        if (obj.has("size") && obj.get("size").isJsonPrimitive()) {
            try {
                size = obj.get("size").getAsLong();
            } catch (RuntimeException ignored) {
                size = 0;
            }
        }
        return new StorePlugin(
                id,
                name,
                text(obj, "version"),
                text(obj, "author"),
                text(obj, "summary"),
                text(obj, "description"),
                downloads,
                text(obj, "updatedAt"),
                icon,
                home,
                type,
                downloadUrl,
                sha.toLowerCase(Locale.ROOT),
                size
        );
    }

    /** 通过返回 null。只允许 https://lash.org.cn 与 www，且不能带账号或其它端口。 */
    static String storeHostError(String url) {
        if (url == null || url.isBlank()) return "地址为空";
        URL parsed;
        try {
            parsed = new URL(url);
        } catch (Exception e) {
            return "地址无效";
        }
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) return "只接受 https";
        if (parsed.getUserInfo() != null && !parsed.getUserInfo().isBlank()) return "地址不能带账号";
        int port = parsed.getPort();
        if (port != -1 && port != 443) return "端口不被允许";
        String host = parsed.getHost();
        if (host == null) return "地址无效";
        host = host.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (!"lash.org.cn".equals(host) && !"www.lash.org.cn".equals(host)) {
            return "安装包必须来自 lash.org.cn";
        }
        String ssrf = SsrfChecker.validate(url);
        if (ssrf != null) return ssrf;
        return null;
    }

    private String get(HttpUrl url) throws IOException {
        if (url == null) throw new IOException("插件商店地址无效");
        String hostError = storeHostError(url.toString());
        if (hostError != null) throw new IOException(hostError);
        OkHttpClient http = downloads.httpClient();
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "PMCL-PluginStore")
                .build();
        try (Response response = http.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (text.length() > MAX_BODY) throw new IOException("插件商店响应过大");
            if (!response.isSuccessful()) {
                throw new IOException("插件商店 HTTP " + response.code());
            }
            return text;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) digest.update(buf, 0, n);
            }
            byte[] raw = digest.digest();
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("无法计算 SHA-256", e);
        }
    }

    private static String text(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        JsonElement el = obj.get(key);
        if (!el.isJsonPrimitive()) return "";
        try {
            return el.getAsString().trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static final class StorePage {
        public final int total;
        public final List<StorePlugin> plugins;

        public StorePage(int total, List<StorePlugin> plugins) {
            this.total = total;
            this.plugins = plugins;
        }
    }
}
