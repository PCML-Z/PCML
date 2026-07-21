package com.pmcl.core.instance;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 独立实例元数据。
 * <p>
 * 每个实例对应 {@code ~/.pmcl/instances/<instanceId>/} 目录，包含独立的 mods/saves/configs。
 * 实例引用一个基础 Minecraft 版本（baseVersionId），启动时使用该版本的 JSON/jar/库文件，
 * 但游戏工作目录指向实例目录。
 * <p>
 * 整合包（modpack）也是一种实例，type 字段区分。
 * 向后兼容：读取时若发现旧格式 modpack.json，自动转换为 InstanceInfo。
 */
public final class InstanceInfo {

    /** 实例类型 */
    public enum Type {
        /** 用户手动创建的自定义实例 */
        CUSTOM,
        /** 从整合包导入的实例 */
        MODPACK
    }

    /** 实例唯一 ID（UUID，用于目录名，避免重命名冲突） */
    private final String instanceId;
    /** 用户可编辑的显示名称 */
    private String name;
    /** 基础 Minecraft 版本 ID（如 "1.20.4"） */
    private String baseVersionId;
    /** 实例类型 */
    private Type type;
    /** 模组加载器（fabric/forge/quilt/neoforge/optifine/liteloader/null） */
    private String loader;
    /** 模组加载器版本（如 "0.15.11"） */
    private String loaderVersion;
    /** 描述信息 */
    private String description;
    /** 图标路径（相对实例目录或绝对路径） */
    private String iconPath;
    /** 创建时间戳（epoch millis） */
    private long createdAt;
    /** 最后游玩时间戳（epoch millis） */
    private long lastPlayedAt;
    /** 总游玩时长（秒） */
    private long totalPlayTimeSeconds;
    /** 实例目录绝对路径（运行时填充，不持久化） */
    private transient Path instanceDir;

    public InstanceInfo(String instanceId, String name, String baseVersionId, Type type) {
        this.instanceId = instanceId;
        this.name = name;
        this.baseVersionId = baseVersionId;
        this.type = type;
        this.createdAt = System.currentTimeMillis();
    }

    /** 从 JSON 加载实例元数据。
     *  M63 修复：必填字段缺失时返回 null 而非 NPE，由调用方决定如何处理。
     */
    public static InstanceInfo fromJson(String json, Path instanceDir) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String id = o.has("instanceId") && !o.get("instanceId").isJsonNull()
                ? o.get("instanceId").getAsString() : UUID.randomUUID().toString();
        // M63: name 字段缺失时使用目录名作为回退，而非 NPE
        String name = o.has("name") && !o.get("name").isJsonNull()
                ? o.get("name").getAsString()
                : (instanceDir != null ? instanceDir.getFileName().toString() : "Unknown");
        String baseVersionId = o.has("baseVersionId") && !o.get("baseVersionId").isJsonNull()
                ? o.get("baseVersionId").getAsString() : "";
        Type type;
        try {
            type = o.has("type") && !o.get("type").isJsonNull()
                    ? Type.valueOf(o.get("type").getAsString()) : Type.CUSTOM;
        } catch (IllegalArgumentException e) {
            type = Type.CUSTOM; // 未知类型回退
        }
        InstanceInfo info = new InstanceInfo(id, name, baseVersionId, type);
        if (o.has("loader") && !o.get("loader").isJsonNull()) info.loader = o.get("loader").getAsString();
        if (o.has("loaderVersion") && !o.get("loaderVersion").isJsonNull()) info.loaderVersion = o.get("loaderVersion").getAsString();
        if (o.has("description") && !o.get("description").isJsonNull()) info.description = o.get("description").getAsString();
        if (o.has("iconPath") && !o.get("iconPath").isJsonNull()) info.iconPath = o.get("iconPath").getAsString();
        if (o.has("createdAt") && !o.get("createdAt").isJsonNull()) info.createdAt = o.get("createdAt").getAsLong();
        if (o.has("lastPlayedAt") && !o.get("lastPlayedAt").isJsonNull()) info.lastPlayedAt = o.get("lastPlayedAt").getAsLong();
        if (o.has("totalPlayTimeSeconds") && !o.get("totalPlayTimeSeconds").isJsonNull()) info.totalPlayTimeSeconds = o.get("totalPlayTimeSeconds").getAsLong();
        info.instanceDir = instanceDir;
        return info;
    }

    /** 兼容旧版 modpack.json 格式转换 */
    public static InstanceInfo fromModpackJson(String json, Path instanceDir) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        String name = o.has("name") ? o.get("name").getAsString() : instanceDir.getFileName().toString();
        String gameVersion = o.has("gameVersion") ? o.get("gameVersion").getAsString() : "";
        String id = UUID.randomUUID().toString();
        InstanceInfo info = new InstanceInfo(id, name, gameVersion, Type.MODPACK);
        if (o.has("loader")) info.loader = o.get("loader").getAsString();
        if (o.has("loaderVersion")) info.loaderVersion = o.get("loaderVersion").getAsString();
        if (o.has("description")) info.description = o.get("description").getAsString();
        info.instanceDir = instanceDir;
        return info;
    }

    /** 序列化为 JSON（不包含 instanceDir） */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("instanceId", instanceId);
        o.addProperty("name", name);
        o.addProperty("baseVersionId", baseVersionId);
        o.addProperty("type", type.name());
        if (loader != null) o.addProperty("loader", loader);
        if (loaderVersion != null) o.addProperty("loaderVersion", loaderVersion);
        if (description != null) o.addProperty("description", description);
        if (iconPath != null) o.addProperty("iconPath", iconPath);
        o.addProperty("createdAt", createdAt);
        o.addProperty("lastPlayedAt", lastPlayedAt);
        o.addProperty("totalPlayTimeSeconds", totalPlayTimeSeconds);
        return o.toString();
    }

    // Getters & Setters
    public String getInstanceId() { return instanceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBaseVersionId() { return baseVersionId; }
    public void setBaseVersionId(String v) { this.baseVersionId = v; }
    public Type getType() { return type; }
    public String getLoader() { return loader; }
    public void setLoader(String l) { this.loader = l; }
    public String getLoaderVersion() { return loaderVersion; }
    public void setLoaderVersion(String v) { this.loaderVersion = v; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getIconPath() { return iconPath; }
    public void setIconPath(String p) { this.iconPath = p; }
    public long getCreatedAt() { return createdAt; }
    public long getLastPlayedAt() { return lastPlayedAt; }
    public void setLastPlayedAt(long t) { this.lastPlayedAt = t; }
    public long getTotalPlayTimeSeconds() { return totalPlayTimeSeconds; }
    public void setTotalPlayTimeSeconds(long t) { this.totalPlayTimeSeconds = t; }
    public Path getInstanceDir() { return instanceDir; }
    public void setInstanceDir(Path dir) { this.instanceDir = dir; }

    /** 实例是否可启动（基础版本非空） */
    public boolean isLaunchable() {
        return baseVersionId != null && !baseVersionId.isEmpty();
    }
}
