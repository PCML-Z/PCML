package com.pmcl.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pmcl.core.i18n.I18n;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 用户自己的 OpenAI 兼容模型。设置写在工作目录的 agent-model.json，不进 preferences.json。
 * 只负责把对话和工具调用发给模型，并把每一步的想法和最终结果分开。
 * 启动器操作由调用方执行，这里不写 preferences.json。
 */
public final class AgentModel {

    private static final String FILE = "agent-model.json";
    /** 一步会占助手和工具两条，多步思考时不能把前面的结果挤掉。 */
    private static final int MAX_TURNS = 24;
    private static final int MAX_CHARS = 4000;
    /** 不写的话，LM Studio 会把回答停在大约 19 个 token。 */
    private static final int MAX_TOKENS = 1024;

    static int maxTokens() {
        return MAX_TOKENS;
    }
    private static final String SYSTEM =
            "你是 Proxima ML 里的智能体，要替用户分步完成启动器里的操作。"
                    + "一次只做一步：先用不超过二十个字写出这一步在想什么，再调用工具。"
                    + "看完工具结果，再想下一步。没做完就继续，不要提前说已经完成。"
                    + "打开页面或某个设置选项都用 open_page，page 填用户的原话，例如光影、模组、使用 macOS Game Mode 启动游戏。不要调用 ui.navigate。"
                    + "具体操作再用 call_api。安装加载器用 loaders.install。"
                    + "不要删除存档、版本、实例、账号、备份、皮肤或录屏。"
                    + "全部做完后，用一两句话说明结果，不要把思考过程再写进结果。";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(Duration.ofSeconds(8))
            .readTimeout(Duration.ofSeconds(90))
            .callTimeout(Duration.ofSeconds(100))
            .build();

    private AgentModel() {}

    public static final class Endpoint {
        public final String baseUrl;
        public final String apiKey;
        public final String model;

        public Endpoint(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl == null ? "" : baseUrl;
            this.apiKey = apiKey == null ? "" : apiKey;
            this.model = model == null ? "" : model;
        }

        public boolean ready() {
            return !baseUrl.isBlank() && !model.isBlank();
        }
    }

    public static final class Turn {
        public final String role;
        public final String text;

        public Turn(String role, String text) {
            this.role = role;
            this.text = text == null ? "" : text;
        }
    }

    /** 一轮模型回复：给用户看的话，以及要执行的工具调用。 */
    public static final class ToolCall {
        public final String id;
        public final String name;
        public final String arguments;

        public ToolCall(String id, String name, String arguments) {
            this.id = id == null || id.isBlank() ? "call" : id;
            this.name = name == null ? "" : name;
            this.arguments = arguments == null || arguments.isBlank() ? "{}" : arguments;
        }
    }

    /** 发给模型的一条历史。tool 用 toolCallId；assistant 可以带上它发起的调用。 */
    public static final class Exchange {
        public final String role;
        public final String content;
        public final String toolCallId;
        public final List<ToolCall> calls;

        public Exchange(String role, String content, String toolCallId, List<ToolCall> calls) {
            this.role = role == null ? "" : role;
            this.content = content == null ? "" : content;
            this.toolCallId = toolCallId;
            this.calls = calls == null ? List.of() : calls;
        }
    }

    /**
     * 一轮模型回复。thought 是这一步的想法，content 是做完后给用户的话。
     * 还有工具要调用时，content 为空。
     */
    public static final class Step {
        public final String content;
        public final String thought;
        public final List<ToolCall> calls;

        public Step(String content, String thought, List<ToolCall> calls) {
            this.content = content == null ? "" : content;
            this.thought = thought == null ? "" : thought;
            this.calls = calls == null ? List.of() : calls;
        }
    }

    public static Endpoint load(Path workDir) {
        if (workDir == null) return new Endpoint("", "", "");
        Path file = workDir.resolve(FILE);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file)) return new Endpoint("", "", "");
        try {
            if (Files.size(file) > 16_384) return new Endpoint("", "", "");
            JsonObject object = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            return new Endpoint(text(object, "baseUrl"), text(object, "apiKey"), text(object, "model"));
        } catch (Exception e) {
            return new Endpoint("", "", "");
        }
    }

    public static void save(Path workDir, String baseUrl, String apiKey, String model) throws IOException {
        if (workDir == null || !Files.isDirectory(workDir)) throw new IOException(I18n.t("launch.agent_store"));
        String url = chatUrl(baseUrl);
        String name = cleanModel(model);
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.length() > 512 || key.indexOf('\n') >= 0 || key.indexOf('\r') >= 0) {
            throw new IOException(I18n.t("launch.agent_store"));
        }
        Path file = workDir.resolve(FILE);
        if (Files.isSymbolicLink(file)) throw new IOException(I18n.t("launch.agent_store"));
        JsonObject object = new JsonObject();
        object.addProperty("baseUrl", url.substring(0, url.length() - "/chat/completions".length()));
        object.addProperty("apiKey", key);
        object.addProperty("model", name);
        Files.writeString(file, object.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            // Windows 没有 POSIX 权限
        }
    }

    public static String ask(Endpoint endpoint, List<Turn> turns) throws IOException {
        List<Exchange> history = new ArrayList<>();
        if (turns != null) {
            for (Turn turn : turns) {
                if (turn == null) continue;
                history.add(new Exchange(turn.role, turn.text, null, null));
            }
        }
        Step step = step(endpoint, history, null);
        String reply = step.content.isBlank() ? step.thought : step.content;
        if (reply.isBlank()) throw new IOException(I18n.t("launch.agent_empty"));
        return reply.length() > 8000 ? reply.substring(0, 8000) : reply;
    }

    /** 带工具的一轮。thought 是这一步的想法，calls 由调用方执行后再继续 step。 */
    public static Step step(Endpoint endpoint, List<Exchange> history, String toolsJson) throws IOException {
        return step(endpoint, history, toolsJson, null);
    }

    /**
     * 同 {@link #step(Endpoint, List, String)}。system 只给崩溃修复换提示词，空则仍用调度中心里的提示词。
     */
    public static Step step(Endpoint endpoint, List<Exchange> history, String toolsJson, String system) throws IOException {
        if (endpoint == null || !endpoint.ready()) throw new IOException(I18n.t("launch.agent_need"));
        int port = loopbackPort(endpoint.baseUrl);
        boolean woke = false;
        if (port == 1234 && !portOpen("127.0.0.1", port)) {
            wakeLmStudio(port, cleanModel(endpoint.model));
            woke = true;
        }
        try {
            return complete(endpoint, history, toolsJson, system);
        } catch (IOException first) {
            if (port != 1234 || woke || !canWake(first)) throw friendly(port, first);
            wakeLmStudio(port, cleanModel(endpoint.model));
            try {
                return complete(endpoint, history, toolsJson, system);
            } catch (IOException second) {
                throw friendly(port, second);
            }
        }
    }

    private static Step complete(Endpoint endpoint, List<Exchange> history, String toolsJson, String systemPrompt) throws IOException {
        String url = chatUrl(endpoint.baseUrl);
        JsonObject body = new JsonObject();
        body.addProperty("model", cleanModel(endpoint.model));
        body.addProperty("stream", false);
        body.addProperty("max_tokens", MAX_TOKENS);
        if (toolsJson != null && !toolsJson.isBlank()) {
            body.add("tools", JsonParser.parseString(toolsJson));
            body.addProperty("tool_choice", "auto");
        }
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", systemPrompt == null || systemPrompt.isBlank() ? SYSTEM : systemPrompt);
        messages.add(system);
        List<Exchange> kept = history == null ? List.of() : history;
        int from = Math.max(0, kept.size() - MAX_TURNS);
        for (int i = from; i < kept.size(); i++) {
            Exchange turn = kept.get(i);
            if (turn == null) continue;
            JsonObject message = messageOf(turn);
            if (message != null) messages.add(message);
        }
        body.add("messages", messages);
        Request.Builder request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json")));
        if (!endpoint.apiKey.isBlank()) request.header("Authorization", "Bearer " + endpoint.apiKey.trim());
        try (Response response = HTTP.newCall(request.build()).execute()) {
            String raw = response.body() == null ? "" : response.body().string();
            if (raw.length() > 1_000_000) raw = raw.substring(0, 1_000_000);
            if (!response.isSuccessful()) {
                String detail = errorMessage(raw);
                if (detail.isBlank()) detail = Integer.toString(response.code());
                throw new IOException(I18n.t("launch.agent_http", detail));
            }
            Step step = readStep(raw);
            if (step.calls.isEmpty() && step.content.isBlank() && step.thought.isBlank()) {
                throw new IOException(I18n.t("launch.agent_empty"));
            }
            String reply = step.content.length() > 8000 ? step.content.substring(0, 8000) : step.content;
            return new Step(reply, step.thought, step.calls);
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException(I18n.t("launch.agent_failed"), e);
        }
    }

    /** LM Studio 默认端口没人听的时候，用本机的 lms 把服务和模型拉起来。 */
    static void wakeLmStudio(int port, String model) throws IOException {
        Path lms = lmsBinary();
        if (lms == null) throw new IOException(I18n.t("launch.agent_offline", "127.0.0.1:" + port));
        if (!portOpen("127.0.0.1", port)) {
            try {
                run(serverStartCommand(lms, port), 25);
            } catch (IOException startFailed) {
                if (!portOpen("127.0.0.1", port)) throw startFailed;
            }
        }
        run(loadCommand(lms, model), 180);
    }

    static int loopbackPort(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) return -1;
        try {
            URI uri = URI.create(baseUrl.trim());
            String host = uri.getHost();
            if (host == null) return -1;
            String name = host.toLowerCase(java.util.Locale.ROOT);
            if (!name.equals("127.0.0.1") && !name.equals("localhost") && !name.equals("::1")) return -1;
            return uri.getPort();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    static List<String> serverStartCommand(Path lms, int port) {
        return List.of(lms.toString(), "server", "start", "-p", Integer.toString(port));
    }

    static List<String> loadCommand(Path lms, String model) {
        return List.of(lms.toString(), "load", model, "-y", "--identifier", model);
    }

    private static boolean canWake(IOException error) {
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
        return message.contains("failed to connect")
                || message.contains("connection refused")
                || message.contains("connect")
                || message.contains("not found")
                || message.contains("not loaded");
    }

    private static IOException friendly(int port, IOException error) {
        if (port > 0 && canWake(error)) {
            return new IOException(I18n.t("launch.agent_offline", "127.0.0.1:" + port), error);
        }
        return error;
    }

    private static boolean portOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 400);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path lmsBinary() {
        String home = System.getProperty("user.home", "");
        if (home.isBlank()) return null;
        Path bin = Path.of(home, ".lmstudio", "bin", "lms");
        if (Files.isExecutable(bin)) return bin;
        Path exe = Path.of(home, ".lmstudio", "bin", "lms.exe");
        if (Files.isRegularFile(exe)) return exe;
        return null;
    }

    private static void run(List<String> command, int timeoutSec) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder output = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (output.length() < 500) output.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // 进程结束时读完即可
            }
        }, "lms-output");
        drain.setDaemon(true);
        drain.start();
        boolean finished;
        try {
            finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(I18n.t("launch.agent_offline", "127.0.0.1"), e);
        }
        if (!finished) {
            process.destroyForcibly();
            throw new IOException(I18n.t("launch.agent_offline", "127.0.0.1"));
        }
        if (process.exitValue() != 0) {
            String detail = output.toString().replace('\n', ' ').trim();
            if (detail.length() > 160) detail = detail.substring(0, 160);
            throw new IOException(I18n.t("launch.agent_offline", detail.isBlank() ? "lms" : detail));
        }
    }

    static String chatUrl(String baseUrl) throws IOException {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        while (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.length() > 500) throw new IOException(I18n.t("launch.agent_bad_url"));
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IOException(I18n.t("launch.agent_bad_url"));
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IOException(I18n.t("launch.agent_bad_url"));
        }
        if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IOException(I18n.t("launch.agent_bad_url"));
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (path.endsWith("/chat/completions")) return trimmed;
        return trimmed + "/chat/completions";
    }

    private static JsonObject messageOf(Exchange turn) {
        if (turn.role == null) return null;
        if ("tool".equals(turn.role)) {
            if (turn.toolCallId == null || turn.toolCallId.isBlank()) return null;
            JsonObject message = new JsonObject();
            message.addProperty("role", "tool");
            message.addProperty("tool_call_id", turn.toolCallId);
            message.addProperty("content", clipText(turn.content));
            return message;
        }
        if (!"user".equals(turn.role) && !"assistant".equals(turn.role)) return null;
        if ((turn.content == null || turn.content.isBlank()) && turn.calls.isEmpty()) return null;
        JsonObject message = new JsonObject();
        message.addProperty("role", turn.role);
        message.addProperty("content", clipText(turn.content));
        if (!turn.calls.isEmpty()) {
            JsonArray calls = new JsonArray();
            for (ToolCall call : turn.calls) {
                if (call == null || call.name.isBlank()) continue;
                JsonObject item = new JsonObject();
                item.addProperty("id", call.id);
                item.addProperty("type", "function");
                JsonObject function = new JsonObject();
                function.addProperty("name", call.name);
                function.addProperty("arguments", call.arguments);
                item.add("function", function);
                calls.add(item);
            }
            if (!calls.isEmpty()) message.add("tool_calls", calls);
        }
        return message;
    }

    private static String clipText(String text) {
        String value = text == null ? "" : text.trim();
        return value.length() > MAX_CHARS ? value.substring(0, MAX_CHARS) : value;
    }

    static Step readStep(String raw) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (!root.has("choices") || !root.get("choices").isJsonArray() || root.getAsJsonArray("choices").isEmpty()) {
                throw new IOException(I18n.t("launch.agent_bad_reply"));
            }
            JsonElement first = root.getAsJsonArray("choices").get(0);
            if (!first.isJsonObject() || !first.getAsJsonObject().has("message")
                    || !first.getAsJsonObject().get("message").isJsonObject()) {
                throw new IOException(I18n.t("launch.agent_bad_reply"));
            }
            JsonObject message = first.getAsJsonObject().getAsJsonObject("message");
            String content = contentText(message.get("content")).trim();
            String reasoning = text(message, "reasoning_content").trim();
            List<ToolCall> calls = toolCalls(message);
            ThoughtText split = splitThought(content);
            if (calls.isEmpty()) {
                List<ToolCall> embedded = embeddedCall(split.answer.isBlank() ? content : split.answer);
                if (!embedded.isEmpty()) {
                    return new Step("", clipThought(firstText(split.thought, reasoning)), embedded);
                }
            }
            if (!calls.isEmpty()) {
                String plan = firstText(split.answer, split.thought, reasoning);
                return new Step("", clipThought(plan), calls);
            }
            return new Step(split.answer, clipThought(firstText(split.thought, reasoning)), List.of());
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException(I18n.t("launch.agent_bad_reply"));
        }
    }

    private static List<ToolCall> toolCalls(JsonObject message) {
        if (!message.has("tool_calls") || !message.get("tool_calls").isJsonArray()) return List.of();
        List<ToolCall> calls = new ArrayList<>();
        int n = 0;
        for (JsonElement item : message.getAsJsonArray("tool_calls")) {
            if (!item.isJsonObject()) continue;
            JsonObject call = item.getAsJsonObject();
            if (!call.has("function") || !call.get("function").isJsonObject()) continue;
            JsonObject function = call.getAsJsonObject("function");
            String name = text(function, "name");
            if (name.isBlank()) continue;
            String arguments = "{}";
            if (function.has("arguments")) {
                JsonElement args = function.get("arguments");
                arguments = args.isJsonPrimitive() ? args.getAsString() : args.toString();
            }
            n++;
            String id = text(call, "id");
            if (id.isBlank()) id = "call-" + n;
            calls.add(new ToolCall(id, name, arguments));
        }
        return calls;
    }

    /** 把思考标签和最终答复拆开。没闭合的标签算作这一步的想法。 */
    static ThoughtText splitThought(String content) {
        if (content == null || content.isBlank()) return new ThoughtText("", "");
        StringBuilder thought = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        int i = 0;
        while (i < content.length()) {
            int open = indexOfIgnoreCase(content, "<think>", i);
            if (open < 0) {
                answer.append(content, i, content.length());
                break;
            }
            answer.append(content, i, open);
            int start = open + "<think>".length();
            int close = indexOfIgnoreCase(content, "</think>", start);
            if (close < 0) {
                if (thought.length() > 0) thought.append(' ');
                thought.append(content, start, content.length());
                break;
            }
            if (thought.length() > 0) thought.append(' ');
            thought.append(content, start, close);
            i = close + "</think>".length();
        }
        return new ThoughtText(thought.toString().trim(), answer.toString().trim());
    }

    static String clipThought(String raw) {
        String text = raw == null ? "" : raw.replace('\n', ' ').replace('\r', ' ').trim();
        text = text.replaceAll(" {2,}", " ");
        if (text.length() <= 80) return text;
        return text.substring(0, 80).trim() + "…";
    }

    private static String firstText(String... parts) {
        if (parts == null) return "";
        for (String part : parts) {
            if (part != null && !part.isBlank()) return part.trim();
        }
        return "";
    }

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        return text.toLowerCase(java.util.Locale.ROOT).indexOf(needle.toLowerCase(java.util.Locale.ROOT), from);
    }

    static final class ThoughtText {
        final String thought;
        final String answer;

        ThoughtText(String thought, String answer) {
            this.thought = thought;
            this.answer = answer;
        }
    }

    /** 小模型有时不走 tool_calls，把调用写成一段 JSON。 */
    private static List<ToolCall> embeddedCall(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            int newline = trimmed.indexOf('\n');
            if (newline >= 0) trimmed = trimmed.substring(newline + 1);
            if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
        }
        if (!trimmed.startsWith("{") || !trimmed.contains("\"name\"")) return List.of();
        try {
            JsonObject object = JsonParser.parseString(trimmed).getAsJsonObject();
            if (!object.has("name") || !object.get("name").isJsonPrimitive()) return List.of();
            String arguments = "{}";
            if (object.has("arguments")) {
                JsonElement args = object.get("arguments");
                arguments = args.isJsonPrimitive() ? args.getAsString() : args.toString();
            }
            return List.of(new ToolCall("call-1", object.get("name").getAsString(), arguments));
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static String contentText(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        if (!content.isJsonArray()) return "";
        StringBuilder text = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            if (part.isJsonPrimitive()) text.append(part.getAsString());
            else if (part.isJsonObject() && part.getAsJsonObject().has("text")
                    && part.getAsJsonObject().get("text").isJsonPrimitive()) {
                text.append(part.getAsJsonObject().get("text").getAsString());
            }
        }
        return text.toString();
    }

    static String readReply(String raw) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (!root.has("choices") || !root.get("choices").isJsonArray()) {
                throw new IOException(I18n.t("launch.agent_bad_reply"));
            }
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices.isEmpty() || !choices.get(0).isJsonObject()) {
                throw new IOException(I18n.t("launch.agent_bad_reply"));
            }
            JsonObject first = choices.get(0).getAsJsonObject();
            if (!first.has("message") || !first.get("message").isJsonObject()) {
                throw new IOException(I18n.t("launch.agent_bad_reply"));
            }
            JsonElement content = first.getAsJsonObject("message").get("content");
            if (content == null || content.isJsonNull()) return "";
            if (content.isJsonPrimitive()) return content.getAsString();
            if (content.isJsonArray()) {
                StringBuilder text = new StringBuilder();
                for (JsonElement part : content.getAsJsonArray()) {
                    if (part.isJsonPrimitive()) text.append(part.getAsString());
                    else if (part.isJsonObject() && part.getAsJsonObject().has("text")
                            && part.getAsJsonObject().get("text").isJsonPrimitive()) {
                        text.append(part.getAsJsonObject().get("text").getAsString());
                    }
                }
                return text.toString();
            }
            throw new IOException(I18n.t("launch.agent_bad_reply"));
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException(I18n.t("launch.agent_bad_reply"));
        }
    }

    private static String errorMessage(String raw) {
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            if (!root.has("error")) return "";
            JsonElement error = root.get("error");
            if (error.isJsonPrimitive()) return clip(error.getAsString());
            if (error.isJsonObject() && error.getAsJsonObject().has("message")
                    && error.getAsJsonObject().get("message").isJsonPrimitive()) {
                return clip(error.getAsJsonObject().get("message").getAsString());
            }
        } catch (RuntimeException ignored) {
            // 错误正文不是 JSON
        }
        return "";
    }

    private static String clip(String value) {
        String text = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    private static String cleanModel(String model) throws IOException {
        String name = model == null ? "" : model.trim();
        if (name.isEmpty() || name.length() > 128 || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0) {
            throw new IOException(I18n.t("launch.agent_need"));
        }
        return name;
    }

    private static String text(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) return "";
        return object.get(key).getAsString();
    }
}
