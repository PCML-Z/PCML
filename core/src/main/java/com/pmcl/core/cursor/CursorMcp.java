package com.pmcl.core.cursor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Cursor 用的 Streamable HTTP MCP。只暴露只读状态、打开页面和启动。 */
public final class CursorMcp {
    static final String INSTRUCTIONS = "Operate this local PMCL launcher. You can read versions, instances, "
            + "account names, and the status line; open a page; select a version; and launch a version or instance. "
            + "Account tokens, passwords, and game logs are not available. Launch tools return when the launcher "
            + "accepts the request; call pmcl_status to see whether the game is running. "
            + "Do not invent page routes; use pmcl_list_pages.";

    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Set<String> PROTOCOLS = Set.of("2024-11-05", "2025-03-26", "2025-06-18");
    private static final Pattern ROUTE = Pattern.compile("[a-z][a-z0-9]{0,31}");
    private static final Pattern SECTION = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Pattern ID = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._+-]{0,79}");
    private static final List<Tool> TOOLS = List.of(
            new Tool("pmcl_status",
                    "Read PMCL: launcher version, status line, selected version, selected instance, current account name and type, and running games. No tokens or passwords.",
                    noArgs()),
            new Tool("pmcl_list_versions", "List installed Minecraft version ids.", noArgs()),
            new Tool("pmcl_list_instances",
                    "List saved instances: name, version, loader, and whether each one can launch. No file paths.",
                    noArgs()),
            new Tool("pmcl_list_accounts",
                    "List account names and types. The current account is marked. Tokens are not included.",
                    noArgs()),
            new Tool("pmcl_list_pages", "List page routes and section ids that pmcl_open_page accepts.", noArgs()),
            new Tool("pmcl_open_page",
                    "Open a launcher page. route is required. section is optional and must belong to that page.",
                    pageSchema()),
            new Tool("pmcl_select_version", "Select an installed version. Does not start the game.",
                    oneString("versionId", "Installed version id from pmcl_list_versions")),
            new Tool("pmcl_launch_version",
                    "Start an installed version with the current account. Returns when the launcher accepts it; then call pmcl_status.",
                    oneString("versionId", "Installed version id from pmcl_list_versions")),
            new Tool("pmcl_launch_instance",
                    "Start a saved instance, the same as the instance launch button. Returns when the launcher accepts it.",
                    oneString("instanceId", "Instance id from pmcl_list_instances")),
            new Tool("pmcl_launch_selected", "Start the version currently selected in the launcher.", noArgs()),
            new Tool("pmcl_refresh", "Rescan local versions and instances.", noArgs())
    );

    private CursorMcp() {}

    public static List<String> toolNames() {
        return TOOLS.stream().map(tool -> tool.name).toList();
    }

    /** 通知返回 null，调用方用 HTTP 202 且不写正文。 */
    public static byte[] dispatch(JsonObject request, CursorAgentSession session) {
        if (request == null) return error(null, -32600, "Invalid Request");
        if (request.has("id") && !request.get("id").isJsonNull() && !validId(request.get("id"))) {
            return error(null, -32600, "Invalid Request");
        }
        boolean hasId = validId(request.get("id"));
        if (!"2.0".equals(text(request.get("jsonrpc")))) {
            return hasId ? error(request.get("id"), -32600, "Invalid Request") : null;
        }
        if (!hasId) return null;
        String method = text(request.get("method"));
        if (method.isEmpty()) return error(request.get("id"), -32600, "Invalid Request");
        try {
            JsonElement result = switch (method) {
                case "initialize" -> initialize(request, session);
                case "ping", "logging/setLevel", "initialized" -> new JsonObject();
                case "tools/list" -> toolsList();
                case "tools/call" -> call(request, session);
                case "resources/list" -> namedList("resources");
                case "resources/templates/list" -> namedList("resourceTemplates");
                case "prompts/list" -> namedList("prompts");
                default -> method.startsWith("notifications/") ? new JsonObject() : null;
            };
            if (result == null) return error(request.get("id"), -32601, "Method not found");
            return ok(request.get("id"), result);
        } catch (RuntimeException e) {
            return error(request.get("id"), -32603, "failed");
        }
    }

    public static byte[] error(JsonElement id, int code, String message) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message == null ? "failed" : message);
        JsonObject res = new JsonObject();
        res.addProperty("jsonrpc", "2.0");
        res.add("id", copyId(id));
        res.add("error", err);
        return JSON.toJson(res).getBytes(StandardCharsets.UTF_8);
    }

    private static JsonObject initialize(JsonObject request, CursorAgentSession session) {
        JsonObject params = asObject(request.get("params"));
        String requested = params == null ? "" : text(params.get("protocolVersion"));
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOLS.contains(requested) ? requested : "2025-03-26");
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        JsonObject caps = new JsonObject();
        caps.add("tools", tools);
        result.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "pmcl");
        String version = "";
        if (session != null) {
            try {
                version = session.launcherVersion();
            } catch (RuntimeException ignored) {
                version = "";
            }
        }
        info.addProperty("version", safeVersion(version));
        result.add("serverInfo", info);
        result.addProperty("instructions", INSTRUCTIONS);
        return result;
    }

    private static JsonObject toolsList() {
        JsonArray arr = new JsonArray();
        for (Tool tool : TOOLS) {
            JsonObject item = new JsonObject();
            item.addProperty("name", tool.name);
            item.addProperty("description", tool.description);
            item.add("inputSchema", tool.schema.deepCopy());
            arr.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("tools", arr);
        return result;
    }

    private static JsonObject call(JsonObject request, CursorAgentSession session) {
        JsonObject params = asObject(request.get("params"));
        String name = params == null ? "" : text(params.get("name"));
        JsonObject args = params == null ? null : asObject(params.get("arguments"));
        if (args == null) args = new JsonObject();
        try {
            return toolResult(invoke(name, args, session), false);
        } catch (CursorAgentException e) {
            return toolText(clip(e.getMessage()), true);
        } catch (RuntimeException e) {
            return toolText(clip(e.getMessage()), true);
        }
    }

    private static JsonObject invoke(String name, JsonObject args, CursorAgentSession session) {
        return switch (name) {
            case "pmcl_status" -> session.status();
            case "pmcl_list_versions" -> session.versions();
            case "pmcl_list_instances" -> session.instances();
            case "pmcl_list_accounts" -> session.accounts();
            case "pmcl_list_pages" -> session.pages();
            case "pmcl_open_page" -> session.openPage(route(args), section(args));
            case "pmcl_select_version" -> session.selectVersion(id(args, "versionId"));
            case "pmcl_launch_version" -> session.launchVersion(id(args, "versionId"));
            case "pmcl_launch_instance" -> session.launchInstance(id(args, "instanceId"));
            case "pmcl_launch_selected" -> session.launchSelected();
            case "pmcl_refresh" -> session.refresh();
            default -> throw new CursorAgentException("没有这个工具");
        };
    }

    private static String route(JsonObject args) {
        String route = text(args.get("route"));
        if (!ROUTE.matcher(route).matches()) throw new CursorAgentException("参数不对");
        return route;
    }

    private static String section(JsonObject args) {
        if (!args.has("section") || args.get("section").isJsonNull()) return "";
        String section = text(args.get("section"));
        if (section.isEmpty()) return "";
        if (!SECTION.matcher(section).matches()) throw new CursorAgentException("参数不对");
        return section;
    }

    private static String id(JsonObject args, String key) {
        String value = text(args.get(key));
        if (!ID.matcher(value).matches() || value.contains("..")) throw new CursorAgentException("参数不对");
        return value;
    }

    private static JsonObject toolResult(JsonElement payload, boolean error) {
        return toolText(payload == null ? "" : JSON.toJson(payload), error);
    }

    private static JsonObject toolText(String text, boolean error) {
        JsonObject part = new JsonObject();
        part.addProperty("type", "text");
        part.addProperty("text", text == null ? "" : text);
        JsonArray content = new JsonArray();
        content.add(part);
        JsonObject result = new JsonObject();
        result.add("content", content);
        result.addProperty("isError", error);
        return result;
    }

    private static JsonObject namedList(String key) {
        JsonObject result = new JsonObject();
        result.add(key, new JsonArray());
        return result;
    }

    private static byte[] ok(JsonElement id, JsonElement result) {
        JsonObject res = new JsonObject();
        res.addProperty("jsonrpc", "2.0");
        res.add("id", copyId(id));
        res.add("result", result);
        return JSON.toJson(res).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean validId(JsonElement id) {
        if (id == null || id.isJsonNull() || !id.isJsonPrimitive()) return false;
        JsonPrimitive primitive = id.getAsJsonPrimitive();
        return primitive.isString() || primitive.isNumber();
    }

    private static JsonElement copyId(JsonElement id) {
        if (!validId(id)) return JsonNull.INSTANCE;
        JsonPrimitive primitive = id.getAsJsonPrimitive();
        if (primitive.isString()) return new JsonPrimitive(primitive.getAsString());
        return new JsonPrimitive(primitive.getAsNumber());
    }

    private static JsonObject asObject(JsonElement el) {
        return el != null && el.isJsonObject() ? el.getAsJsonObject() : null;
    }

    private static String text(JsonElement el) {
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) return "";
        return el.getAsString();
    }

    private static String clip(String message) {
        if (message == null || message.isBlank()) return "failed";
        String flat = message.replace('\r', ' ').replace('\n', ' ').trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200);
    }

    private static String safeVersion(String raw) {
        if (raw == null) return "pmcl";
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 32 || !trimmed.matches("[A-Za-z0-9._+-]+")) return "pmcl";
        return trimmed;
    }

    private static JsonObject noArgs() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    private static JsonObject oneString(String key, String description) {
        JsonObject field = new JsonObject();
        field.addProperty("type", "string");
        field.addProperty("description", description);
        JsonObject props = new JsonObject();
        props.add(key, field);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add(key);
        schema.add("required", required);
        return schema;
    }

    private static JsonObject pageSchema() {
        JsonObject route = new JsonObject();
        route.addProperty("type", "string");
        route.addProperty("description", "Page route from pmcl_list_pages");
        JsonObject section = new JsonObject();
        section.addProperty("type", "string");
        section.addProperty("description", "Optional section id on that page");
        JsonObject props = new JsonObject();
        props.add("route", route);
        props.add("section", section);
        JsonArray required = new JsonArray();
        required.add("route");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        schema.add("required", required);
        return schema;
    }

    private static final class Tool {
        final String name;
        final String description;
        final JsonObject schema;

        Tool(String name, String description, JsonObject schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }
    }
}
