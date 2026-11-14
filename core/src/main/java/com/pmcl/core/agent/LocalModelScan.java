package com.pmcl.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 只查已知的本机模型目录和本机回环端口，不遍历整块磁盘。
 */
public final class LocalModelScan {

    public static final String OLLAMA = "ollama";
    public static final String LM_STUDIO = "lmstudio";
    public static final String LLAMA = "llama";
    public static final String GPT4ALL = "gpt4all";
    public static final String HUGGINGFACE = "huggingface";

    private static final String OLLAMA_URL = "http://127.0.0.1:11434/v1";
    private static final String LM_STUDIO_URL = "http://127.0.0.1:1234/v1";
    private static final String LLAMA_URL = "http://127.0.0.1:8080/v1";
    private static final Pattern SHARD = Pattern.compile("-\\d{5}-of-\\d{5}\\.gguf$", Pattern.CASE_INSENSITIVE);

    private static final OkHttpClient PROBE = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(Duration.ofMillis(400))
            .readTimeout(Duration.ofMillis(800))
            .callTimeout(Duration.ofMillis(1200))
            .build();

    private LocalModelScan() {}

    public static final class Found {
        public final String model;
        public final String baseUrl;
        public final String source;
        public final String where;

        public Found(String model, String baseUrl, String source, String where) {
            this.model = model == null ? "" : model;
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.source = source == null ? "" : source;
            this.where = where == null ? "" : where;
        }
    }

    /** 读当前用户的本机模型。测试传入家目录和探测函数，避免碰到真实磁盘和网络。 */
    public static List<Found> scan() {
        String home = System.getProperty("user.home", "");
        List<Found> found = new ArrayList<>();
        if (!home.isBlank()) found.addAll(scan(Path.of(home), LocalModelScan::httpGet));
        String localApp = System.getenv("LOCALAPPDATA");
        if (localApp != null && !localApp.isBlank()) {
            addGguf(found, Path.of(localApp).resolve("nomic.ai").resolve("GPT4All"), GPT4ALL, "", 40);
        }
        return dedup(found);
    }

    static List<Found> scan(Path home, Probe probe) {
        if (home == null) return List.of();
        List<Found> found = new ArrayList<>();
        List<Found> ollamaLive = ollamaNames(probe == null ? null : probe.get("http://127.0.0.1:11434/api/tags"));
        if (!ollamaLive.isEmpty()) found.addAll(ollamaLive);
        else addOllamaFiles(found, home.resolve(".ollama").resolve("models").resolve("manifests"));

        List<Found> studioLive = openAiNames(probe == null ? null : probe.get("http://127.0.0.1:1234/v1/models"), LM_STUDIO, LM_STUDIO_URL);
        if (!studioLive.isEmpty()) found.addAll(studioLive);
        else {
            List<Found> indexed = readLmStudioIndex(home.resolve(".lmstudio").resolve(".internal").resolve("model-index-cache.json"));
            if (!indexed.isEmpty()) found.addAll(indexed);
            else {
                addGguf(found, home.resolve(".lmstudio").resolve("models"), LM_STUDIO, LM_STUDIO_URL, 40);
                addLmStudioWeights(found, home.resolve(".lmstudio").resolve("models"));
                addGguf(found, home.resolve(".cache").resolve("lm-studio").resolve("models"), LM_STUDIO, LM_STUDIO_URL, 40);
            }
        }

        found.addAll(openAiNames(probe == null ? null : probe.get("http://127.0.0.1:8080/v1/models"), LLAMA, LLAMA_URL));
        addGguf(found, home.resolve("Library").resolve("Application Support").resolve("nomic.ai").resolve("GPT4All"), GPT4ALL, "", 40);
        addHuggingFace(found, home.resolve(".cache").resolve("huggingface").resolve("hub"));
        return dedup(found);
    }

    static String httpGet(String url) {
        if (url == null || !(url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:"))) return null;
        try (Response response = PROBE.newCall(new Request.Builder().url(url).get().build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String body = response.body().string();
            return body.length() > 1_000_000 ? null : body;
        } catch (Exception e) {
            return null;
        }
    }

    private static void addOllamaFiles(List<Found> out, Path manifests) {
        Path root = safe(manifests);
        if (root == null) return;
        try (var stream = Files.walk(root, 6)) {
            stream.filter(Files::isRegularFile).limit(800).forEach(file -> {
                Path rel = root.relativize(file);
                if (rel.getNameCount() < 4) return;
                String namespace = rel.getName(rel.getNameCount() - 3).toString();
                String name = rel.getName(rel.getNameCount() - 2).toString();
                String tag = rel.getName(rel.getNameCount() - 1).toString();
                if (name.isBlank() || tag.isBlank()) return;
                String model = "library".equals(namespace) ? name + ":" + tag : namespace + "/" + name + ":" + tag;
                out.add(new Found(model, OLLAMA_URL, OLLAMA, OLLAMA_URL));
            });
        } catch (IOException ignored) {
            // 目录读不到就跳过这一处
        }
    }

    private static void addGguf(List<Found> out, Path dir, String source, String baseUrl, int limit) {
        Path root = safe(dir);
        if (root == null) return;
        int before = out.size();
        try (var stream = Files.walk(root, 5)) {
            stream.filter(Files::isRegularFile).limit(1500).forEach(file -> {
                if (out.size() - before >= limit) return;
                String name = file.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).endsWith(".gguf") || SHARD.matcher(name).find()) return;
                String model = name.substring(0, name.length() - 5);
                String where = baseUrl.isBlank() ? file.getParent().toString() : baseUrl;
                out.add(new Found(model, baseUrl, source, where));
            });
        } catch (IOException ignored) {
            // 目录读不到就跳过这一处
        }
    }

    /** LM Studio 的 MLX / safetensors 模型记在索引里，目录下没有 gguf。 */
    static List<Found> readLmStudioIndex(Path index) {
        Map<String, Found> byDir = new LinkedHashMap<>();
        if (index == null || !Files.isRegularFile(index) || Files.isSymbolicLink(index)) return List.of();
        try {
            if (Files.size(index) > 32L * 1024 * 1024) return List.of();
            JsonObject root = JsonParser.parseString(Files.readString(index)).getAsJsonObject();
            if (!root.has("models") || !root.get("models").isJsonArray()) return List.of();
            for (JsonElement item : root.getAsJsonArray("models")) {
                if (!item.isJsonObject()) continue;
                JsonObject model = item.getAsJsonObject();
                String domain = text(model, "domain");
                if (!domain.isBlank() && !"llm".equals(domain)) continue;
                String dir = text(model, "concreteModelDirAbsolutePath");
                if (!dir.isBlank() && !Files.isDirectory(Path.of(dir))) continue;
                String id = text(model, "indexedModelIdentifier");
                int at = id.indexOf('@');
                if (at > 0) id = id.substring(0, at);
                if (id.isBlank()) id = text(model, "originalIndexedModelIdentifier");
                if (id.isBlank()) continue;
                String key = dir.isBlank() ? id : dir;
                Found next = new Found(id, LM_STUDIO_URL, LM_STUDIO, dir.isBlank() ? LM_STUDIO_URL : dir);
                Found prev = byDir.get(key);
                if (prev == null || prev.model.indexOf('@') >= 0) byDir.put(key, next);
            }
        } catch (Exception ignored) {
            return List.of();
        }
        return new ArrayList<>(byDir.values());
    }

    private static void addLmStudioWeights(List<Found> out, Path modelsRoot) {
        Path root = safe(modelsRoot);
        if (root == null) return;
        int before = out.size();
        try (var stream = Files.walk(root, 4)) {
            stream.filter(Files::isRegularFile).limit(1500).forEach(file -> {
                if (out.size() - before >= 40) return;
                String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.equals("model.safetensors") && !name.endsWith(".safetensors")) return;
                Path dir = file.getParent();
                if (dir == null) return;
                String rel = root.relativize(dir).toString().replace('\\', '/');
                if (rel.isBlank() || ".".equals(rel)) return;
                out.add(new Found(rel, LM_STUDIO_URL, LM_STUDIO, dir.toString()));
            });
        } catch (IOException ignored) {
            // 目录读不到就跳过这一处
        }
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return "";
        return object.get(key).getAsString().trim();
    }

    private static void addHuggingFace(List<Found> out, Path hub) {
        Path root = safe(hub);
        if (root == null || !Files.isDirectory(root)) return;
        try (var children = Files.list(root)) {
            children.filter(Files::isDirectory).limit(80).forEach(dir -> {
                String folder = dir.getFileName().toString();
                if (!folder.startsWith("models--")) return;
                if (!containsGguf(dir)) return;
                String rest = folder.substring("models--".length());
                int split = rest.indexOf("--");
                String model = split < 0 ? rest : rest.substring(0, split) + "/" + rest.substring(split + 2);
                if (!model.isBlank()) out.add(new Found(model, "", HUGGINGFACE, dir.toString()));
            });
        } catch (IOException ignored) {
            // 目录读不到就跳过这一处
        }
    }

    private static boolean containsGguf(Path dir) {
        try (var stream = Files.walk(dir, 3)) {
            return stream.filter(Files::isRegularFile).limit(200)
                    .anyMatch(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"));
        } catch (IOException e) {
            return false;
        }
    }

    static List<Found> ollamaNames(String body) {
        List<Found> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("models") || !root.get("models").isJsonArray()) return out;
            for (JsonElement item : root.getAsJsonArray("models")) {
                if (!item.isJsonObject()) continue;
                JsonElement name = item.getAsJsonObject().get("name");
                if (name != null && name.isJsonPrimitive() && !name.getAsString().isBlank()) {
                    out.add(new Found(name.getAsString().trim(), OLLAMA_URL, OLLAMA, OLLAMA_URL));
                }
            }
        } catch (RuntimeException ignored) {
            // 不是这份列表
        }
        return out;
    }

    static List<Found> openAiNames(String body, String source, String baseUrl) {
        List<Found> out = new ArrayList<>();
        if (body == null || body.isBlank()) return out;
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray data = root.has("data") && root.get("data").isJsonArray() ? root.getAsJsonArray("data") : null;
            if (data == null) return out;
            for (JsonElement item : data) {
                if (!item.isJsonObject()) continue;
                JsonElement id = item.getAsJsonObject().get("id");
                if (id != null && id.isJsonPrimitive() && !id.getAsString().isBlank()) {
                    out.add(new Found(id.getAsString().trim(), baseUrl, source, baseUrl));
                }
            }
        } catch (RuntimeException ignored) {
            // 不是这份列表
        }
        return out;
    }

    private static Path safe(Path path) {
        if (path == null || !Files.isDirectory(path)) return null;
        try {
            Path real = path.toRealPath();
            if (real.getNameCount() < 2) return null;
            return real;
        } catch (IOException e) {
            return null;
        }
    }

    private static List<Found> dedup(List<Found> found) {
        Map<String, Found> map = new LinkedHashMap<>();
        for (Found item : found) {
            if (item.model.isBlank()) continue;
            map.putIfAbsent(item.source + "\n" + item.baseUrl + "\n" + item.model, item);
        }
        List<Found> list = new ArrayList<>(map.values());
        list.sort(Comparator.comparing((Found item) -> item.source).thenComparing(item -> item.model));
        return list;
    }

    @FunctionalInterface
    interface Probe {
        String get(String url);
    }
}
