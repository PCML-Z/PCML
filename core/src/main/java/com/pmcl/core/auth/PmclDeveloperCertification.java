package com.pmcl.core.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用当前 GitHub 账号名下的公开仓库做 PMCL 开发者认证。
 * 仓库主题是 pmcl-plugin 或 ecxp-app 才算。fork 不算。
 * 只请求 api.github.com，不跟随离开该主机的重定向。
 */
public final class PmclDeveloperCertification {

    public static final String PLUGIN_TOPIC = "pmcl-plugin";
    public static final String ECXP_TOPIC = "ecxp-app";

    private static final Set<String> TOPICS = Set.of(PLUGIN_TOPIC, ECXP_TOPIC);
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?");
    private static final int MAX_PAGES = 5;
    private static final int PAGE_SIZE = 100;
    private static final int MAX_BODY = 4 * 1024 * 1024;

    private PmclDeveloperCertification() {}

    public static boolean certify(OkHttpClient http, String login, String accessToken) throws IOException {
        String name = requireLogin(login);
        if (http == null || accessToken == null || accessToken.isBlank()) {
            throw new IOException("github-token");
        }
        for (int page = 1; page <= MAX_PAGES; page++) {
            String url = "https://api.github.com/users/" + name
                    + "/repos?type=owner&per_page=" + PAGE_SIZE + "&page=" + page;
            String body = get(http, url, accessToken);
            if (matches(body)) return true;
            if (repoCount(body) < PAGE_SIZE) return false;
        }
        return false;
    }

    static String requireLogin(String login) throws IOException {
        if (login == null) throw new IOException("github-login");
        String name = login.trim();
        if (!LOGIN.matcher(name).matches()) throw new IOException("github-login");
        return name;
    }

    /** 一页仓库 JSON。非 fork，且主题包含 pmcl-plugin 或 ecxp-app。 */
    static boolean matches(String json) throws IOException {
        JsonArray repos = repos(json);
        for (JsonElement element : repos) {
            if (!element.isJsonObject()) continue;
            JsonObject repo = element.getAsJsonObject();
            if (repo.has("fork") && !repo.get("fork").isJsonNull() && repo.get("fork").getAsBoolean()) {
                continue;
            }
            if (!repo.has("topics") || !repo.get("topics").isJsonArray()) continue;
            if (hasTopic(repo.getAsJsonArray("topics"))) return true;
        }
        return false;
    }

    static int repoCount(String json) throws IOException {
        return repos(json).size();
    }

    private static boolean hasTopic(JsonArray topics) {
        for (JsonElement element : topics) {
            if (!element.isJsonPrimitive()) continue;
            String topic = element.getAsString().trim().toLowerCase(Locale.ROOT);
            if (TOPICS.contains(topic)) return true;
        }
        return false;
    }

    private static JsonArray repos(String json) throws IOException {
        if (json == null || json.isBlank()) throw new IOException("github-shape");
        JsonElement root;
        try {
            root = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IOException("github-shape");
        }
        if (!root.isJsonArray()) throw new IOException("github-shape");
        return root.getAsJsonArray();
    }

    private static String get(OkHttpClient http, String url, String accessToken) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "PMCL")
                .get()
                .build();
        try (Response response = http.newCall(request).execute()) {
            if (!"api.github.com".equalsIgnoreCase(response.request().url().host())) {
                throw new IOException("github-host");
            }
            int code = response.code();
            if (code != 200) throw new IOException("github-" + code);
            ResponseBody body = response.body();
            if (body == null) return "";
            byte[] bytes = body.byteStream().readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) throw new IOException("too-large");
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
