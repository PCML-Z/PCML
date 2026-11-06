package com.pmcl.core.ecxp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.pmcl.core.plugin.PluginManager;
import com.pmcl.plugin.EventListener;
import com.pmcl.plugin.PluginContext;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ECXP 应用和启动器之间的本机通道。只监听 127.0.0.1，每行一条 JSON。
 * <p>
 * 应用连上后第一条必须是 {@code {"id":0,"method":"hello","params":{"token":"…"}}}，
 * token 来自启动时的环境变量 PMCL_ECXP_TOKEN，进程退出后作废。
 * 之后的 {@code "instances.listInstances"} 这类方法名对应插件 API 里同名接口的同名方法，
 * 参数按位置放在 params 数组里。
 * 启动器主动推送的消息没有 id，只有 {@code event} 和 {@code data}。
 */
public final class EcxpBridge implements AutoCloseable {

    public static final int API_VERSION = 1;

    private static final int MAX_LINE = 16 * 1024 * 1024;
    private static final int HELLO_TIMEOUT_MS = 10_000;
    private static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .disableHtmlEscaping()
            .registerTypeHierarchyAdapter(Path.class, new PathAdapter())
            .registerTypeHierarchyAdapter(Throwable.class,
                    (JsonSerializer<Throwable>) (t, type, ctx) -> new JsonPrimitive(String.valueOf(t.getMessage())))
            .create();
    private static final Map<String, Method> APIS = apiAccessors();

    private final PluginManager plugins;
    private final String launcherVersion;
    private final Path workDir;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "pmcl-ecxp-call");
        t.setDaemon(true);
        return t;
    });

    private ServerSocket server;
    private EventListener hostListener;

    public EcxpBridge(PluginManager plugins, String launcherVersion, Path workDir) {
        this.plugins = plugins;
        this.launcherVersion = launcherVersion == null ? "" : launcherVersion;
        this.workDir = workDir;
    }

    /** 端口在第一次启动应用时才打开。 */
    public synchronized int ensureStarted() throws IOException {
        if (server != null && !server.isClosed()) return server.getLocalPort();
        ServerSocket socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        server = socket;
        Thread accept = new Thread(() -> acceptLoop(socket), "pmcl-ecxp-bridge");
        accept.setDaemon(true);
        accept.start();
        if (plugins != null && hostListener == null) {
            hostListener = event -> {
                JsonObject data = new JsonObject();
                data.addProperty("type", event.getClass().getSimpleName());
                try {
                    data.add("data", GSON.toJsonTree(event));
                } catch (RuntimeException e) {
                    data.add("data", JsonNull.INSTANCE);
                }
                for (Session s : sessions) s.push("pmcl", data);
            };
            plugins.addHostEventListener(hostListener);
        }
        return socket.getLocalPort();
    }

    /** 给一次启动发 token。应用凭它连上通道，拿到清单里声明的权限。 */
    public String issue(EcxpManifest manifest, Path dataDir) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        PluginContext context = plugins == null ? null
                : plugins.appContext("ecxp." + manifest.getId(), dataDir, manifest.grantedPermissions());
        grants.put(token, new Grant(token, manifest, dataDir, context));
        return token;
    }

    public void revoke(String token) {
        if (token == null) return;
        grants.remove(token);
        for (Session s : sessions) {
            if (s.grant.token.equals(token)) {
                sessions.remove(s);
                s.close();
            }
        }
    }

    public boolean connected(String appId) {
        for (Session s : sessions) {
            if (s.grant.manifest.getId().equals(appId)) return true;
        }
        return false;
    }

    public void push(String appId, String event, JsonElement data) {
        for (Session s : sessions) {
            if (s.grant.manifest.getId().equals(appId)) s.push(event, data);
        }
    }

    /** 当前可调用的接口和方法，按 “接口.方法” 排好。 */
    public static List<String> methodNames() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Method> api : APIS.entrySet()) {
            for (Method m : apiMethods(api.getValue().getReturnType())) out.add(api.getKey() + "." + m.getName());
        }
        out.sort(null);
        return out;
    }

    @Override
    public synchronized void close() {
        for (Session s : sessions) s.close();
        sessions.clear();
        grants.clear();
        if (plugins != null && hostListener != null) plugins.removeHostEventListener(hostListener);
        hostListener = null;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
        workers.shutdownNow();
    }

    private void acceptLoop(ServerSocket socket) {
        while (!socket.isClosed()) {
            try {
                Socket client = socket.accept();
                Thread t = new Thread(() -> serve(client), "pmcl-ecxp-session");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (socket.isClosed()) return;
            }
        }
    }

    private void serve(Socket socket) {
        Session session = null;
        try (socket) {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(HELLO_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();
            String first = readLine(in);
            if (first == null) return;
            JsonObject hello = parseObject(first);
            JsonElement helloId = hello == null ? JsonNull.INSTANCE : idOf(hello);
            Grant grant = hello == null || !"hello".equals(string(hello, "method")) ? null : grantFor(hello);
            if (grant == null) {
                writeRaw(out, error(helloId, "unauthorized", "bad token"));
                return;
            }
            socket.setSoTimeout(0);
            session = new Session(socket, out, grant);
            sessions.add(session);
            session.send(result(helloId, info(grant)));
            String line;
            while ((line = readLine(in)) != null) {
                if (line.isBlank()) continue;
                JsonObject req = parseObject(line);
                if (req == null) continue;
                Session current = session;
                workers.execute(() -> current.send(handle(current, req)));
            }
        } catch (SocketTimeoutException ignored) {
        } catch (IOException ignored) {
        } finally {
            if (session != null) {
                sessions.remove(session);
                session.close();
            }
        }
    }

    private Grant grantFor(JsonObject hello) {
        JsonElement params = hello.get("params");
        if (params == null || !params.isJsonObject()) return null;
        String token = string(params.getAsJsonObject(), "token");
        if (token == null) return null;
        for (Grant g : grants.values()) {
            if (MessageDigest.isEqual(g.token.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
                return g;
            }
        }
        return null;
    }

    private JsonObject handle(Session session, JsonObject req) {
        JsonElement id = idOf(req);
        String method = string(req, "method");
        if (method == null || method.isBlank()) return error(id, "unknown_method", "");
        JsonArray params = req.has("params") && req.get("params").isJsonArray() ? req.getAsJsonArray("params") : new JsonArray();
        try {
            return result(id, dispatch(session, method, params));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String code = cause instanceof SecurityException ? "permission_denied" : "failed";
            return error(id, code, String.valueOf(cause.getMessage() == null ? cause : cause.getMessage()));
        } catch (BridgeError e) {
            return error(id, e.code, e.getMessage());
        } catch (SecurityException e) {
            return error(id, "permission_denied", String.valueOf(e.getMessage()));
        } catch (RuntimeException | ReflectiveOperationException e) {
            return error(id, "failed", String.valueOf(e.getMessage() == null ? e : e.getMessage()));
        }
    }

    private JsonElement dispatch(Session session, String method, JsonArray params) throws ReflectiveOperationException, BridgeError {
        switch (method) {
            case "host.ping" -> { return new JsonPrimitive("pong"); }
            case "host.info" -> { return info(session.grant); }
            case "host.methods" -> {
                JsonArray out = new JsonArray();
                for (Map.Entry<String, Method> api : APIS.entrySet()) {
                    for (Method m : apiMethods(api.getValue().getReturnType())) {
                        JsonObject o = new JsonObject();
                        o.addProperty("name", api.getKey() + "." + m.getName());
                        JsonArray types = new JsonArray();
                        for (Type t : m.getGenericParameterTypes()) types.add(shortType(t));
                        o.add("params", types);
                        o.addProperty("returns", shortType(m.getGenericReturnType()));
                        out.add(o);
                    }
                }
                return out;
            }
            case "host.log" -> {
                String text = params.size() > 1 ? asText(params.get(1)) : params.size() == 1 ? asText(params.get(0)) : "";
                String level = params.size() > 1 ? asText(params.get(0)) : "info";
                System.err.println("[ecxp:" + session.grant.manifest.getId() + "] " + level + ": " + text);
                return JsonNull.INSTANCE;
            }
            default -> { }
        }
        int dot = method.indexOf('.');
        if (dot <= 0 || dot == method.length() - 1) throw new BridgeError("unknown_method", method);
        Method accessor = APIS.get(method.substring(0, dot));
        if (accessor == null) throw new BridgeError("unknown_method", method);
        PluginContext context = session.grant.context;
        if (context == null) throw new BridgeError("api_unavailable", "plugin system is not running");
        Object api = accessor.invoke(context);
        if (api == null) throw new BridgeError("api_unavailable", method);
        String name = method.substring(dot + 1);
        List<Method> candidates = new ArrayList<>();
        for (Method m : apiMethods(accessor.getReturnType())) {
            if (m.getName().equals(name) && m.getParameterCount() >= params.size()) candidates.add(m);
        }
        if (candidates.isEmpty()) throw new BridgeError("unknown_method", method);
        candidates.sort(Comparator.comparingInt(Method::getParameterCount));
        IllegalArgumentException mismatch = null;
        for (Method m : candidates) {
            Object[] args;
            try {
                args = convertArgs(session, m, params);
            } catch (IllegalArgumentException | com.google.gson.JsonParseException e) {
                mismatch = new IllegalArgumentException(e.getMessage(), e);
                continue;
            }
            Object value = m.invoke(api, args);
            return m.getReturnType() == void.class ? JsonNull.INSTANCE : GSON.toJsonTree(value);
        }
        throw new BridgeError("bad_params", mismatch == null ? method : String.valueOf(mismatch.getMessage()));
    }

    private Object[] convertArgs(Session session, Method m, JsonArray params) {
        Type[] types = m.getGenericParameterTypes();
        Class<?>[] raws = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            JsonElement el = i < params.size() ? params.get(i) : JsonNull.INSTANCE;
            Class<?> raw = raws[i];
            if (el == null || el.isJsonNull()) {
                if (raw.isPrimitive()) throw new IllegalArgumentException("argument " + i + " is required");
                args[i] = null;
            } else if (isCallback(raw)) {
                String cb = el.isJsonObject() && el.getAsJsonObject().has("$callback")
                        ? el.getAsJsonObject().get("$callback").getAsString() : el.getAsString();
                args[i] = callbackProxy(raw, session, cb);
            } else {
                args[i] = GSON.fromJson(el, types[i]);
            }
        }
        return args;
    }

    private static boolean isCallback(Class<?> type) {
        if (!type.isInterface() || !type.getName().startsWith("com.pmcl.plugin.")) return false;
        int abstracts = 0;
        for (Method m : type.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) abstracts++;
        }
        return abstracts == 1;
    }

    private static Object callbackProxy(Class<?> type, Session session, String callbackId) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "EcxpCallback(" + callbackId + ")";
                };
            }
            JsonObject data = new JsonObject();
            data.addProperty("id", callbackId);
            data.add("args", GSON.toJsonTree(args == null ? new Object[0] : args));
            session.push("callback", data);
            return defaultValue(method.getReturnType());
        };
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        return 0;
    }

    private JsonObject info(Grant grant) {
        JsonObject o = new JsonObject();
        o.addProperty("apiVersion", API_VERSION);
        o.addProperty("launcher", "PMCL");
        o.addProperty("launcherVersion", launcherVersion);
        o.addProperty("workDir", workDir == null ? "" : workDir.toString());
        o.addProperty("dataDir", grant.dataDir.toString());
        JsonObject app = new JsonObject();
        app.addProperty("id", grant.manifest.getId());
        app.addProperty("name", grant.manifest.getName());
        app.addProperty("version", grant.manifest.getVersion());
        app.addProperty("framework", grant.manifest.getFramework());
        o.add("app", app);
        JsonArray perms = new JsonArray();
        grant.manifest.grantedPermissions().stream().sorted().forEach(perms::add);
        o.add("permissions", perms);
        JsonArray apis = new JsonArray();
        APIS.keySet().forEach(apis::add);
        o.add("apis", apis);
        o.addProperty("apiAvailable", grant.context != null);
        return o;
    }

    private static Map<String, Method> apiAccessors() {
        Map<String, Method> out = new LinkedHashMap<>();
        Method[] methods = PluginContext.class.getMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method m : methods) {
            Class<?> ret = m.getReturnType();
            if (m.getParameterCount() != 0 || !ret.isInterface()) continue;
            if (!ret.getName().startsWith("com.pmcl.plugin.api.")) continue;
            out.put(m.getName(), m);
        }
        return out;
    }

    private static List<Method> apiMethods(Class<?> api) {
        List<Method> out = new ArrayList<>();
        for (Method m : api.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic()) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            out.add(m);
        }
        out.sort(Comparator.comparing(Method::getName).thenComparingInt(Method::getParameterCount));
        return out;
    }

    private static String shortType(Type type) {
        return type.getTypeName().replaceAll("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)*\\.", "");
    }

    private static JsonElement idOf(JsonObject obj) {
        JsonElement id = obj.get("id");
        return id == null ? JsonNull.INSTANCE : id;
    }

    private static String string(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && el.isJsonPrimitive() ? el.getAsString() : null;
    }

    private static String asText(JsonElement el) {
        if (el == null || el.isJsonNull()) return "";
        return el.isJsonPrimitive() ? el.getAsString() : el.toString();
    }

    private static JsonObject parseObject(String line) {
        try {
            JsonElement el = JsonParser.parseString(line);
            return el.isJsonObject() ? el.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonObject result(JsonElement id, JsonElement value) {
        JsonObject o = new JsonObject();
        o.add("id", id);
        o.add("result", value == null ? JsonNull.INSTANCE : value);
        return o;
    }

    private static JsonObject error(JsonElement id, String code, String message) {
        JsonObject o = new JsonObject();
        o.add("id", id);
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message == null ? "" : message);
        o.add("error", err);
        return o;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return buf.toString(StandardCharsets.UTF_8);
            if (b == '\r') continue;
            if (buf.size() >= MAX_LINE) throw new IOException("line too long");
            buf.write(b);
        }
        return buf.size() == 0 ? null : buf.toString(StandardCharsets.UTF_8);
    }

    private static void writeRaw(OutputStream out, JsonObject message) throws IOException {
        out.write((GSON.toJson(message) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private record Grant(String token, EcxpManifest manifest, Path dataDir, PluginContext context) { }

    private static final class Session {
        final Socket socket;
        final OutputStream out;
        final Grant grant;

        Session(Socket socket, OutputStream out, Grant grant) {
            this.socket = socket;
            this.out = out;
            this.grant = grant;
        }

        void push(String event, JsonElement data) {
            JsonObject o = new JsonObject();
            o.addProperty("event", event);
            o.add("data", data == null ? JsonNull.INSTANCE : data);
            send(o);
        }

        void send(JsonObject message) {
            synchronized (out) {
                try {
                    writeRaw(out, message);
                } catch (IOException e) {
                    close();
                }
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class BridgeError extends Exception {
        final String code;

        BridgeError(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final class PathAdapter extends TypeAdapter<Path> {
        @Override
        public void write(JsonWriter out, Path value) throws IOException {
            if (value == null) out.nullValue();
            else out.value(value.toString());
        }

        @Override
        public Path read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            }
            return Path.of(in.nextString());
        }
    }
}
