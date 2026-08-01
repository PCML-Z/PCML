package com.lash.pmcl.core.instance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path
import java.util.UUID

/**
 * 独立实例元数据。
 *
 * 每个实例对应 `~/.pmcl/instances/<instanceId>/` 目录，包含独立的 mods/saves/configs。
 * 实例引用一个基础 Minecraft 版本（baseVersionId），启动时使用该版本的 JSON/jar/库文件，
 * 但游戏工作目录指向实例目录。
 *
 * 整合包（modpack）也是一种实例，type 字段区分。
 * 向后兼容：读取时若发现旧格式 modpack.json，自动转换为 InstanceInfo。
 */
data class InstanceInfo(
    /** 实例唯一 ID（UUID，用于目录名，避免重命名冲突） */
    val instanceId: String,
    /** 用户可编辑的显示名称 */
    var name: String,
    /** 基础 Minecraft 版本 ID（如 "1.20.4"） */
    var baseVersionId: String,
    /** 实例类型 */
    var type: Type
) {
    /** 实例类型 */
    enum class Type {
        /** 用户手动创建的自定义实例 */
        CUSTOM,
        /** 从整合包导入的实例 */
        MODPACK
    }

    /** 模组加载器（fabric/forge/quilt/neoforge/optifine/liteloader/null） */
    var loader: String? = null
    /** 模组加载器版本（如 "0.15.11"） */
    var loaderVersion: String? = null
    /** 描述信息 */
    var description: String? = null
    /** 图标路径（相对实例目录或绝对路径） */
    var iconPath: String? = null
    /** 创建时间戳（epoch millis） */
    var createdAt: Long = System.currentTimeMillis()
    /** 最后游玩时间戳（epoch millis） */
    var lastPlayedAt: Long = 0L
    /** 总游玩时长（秒） */
    var totalPlayTimeSeconds: Long = 0L
    /** 绑定的账户 UUID（空字符串 = 使用全局默认账户） */
    var boundAccountUuid: String = ""
    /** 实例目录绝对路径（运行时填充，不持久化） */
    @Transient
    var instanceDir: Path? = null

    companion object {
        /**
         * 从 JSON 加载实例元数据。
         * M63 修复：必填字段缺失时返回回退值而非 NPE，由调用方决定如何处理。
         */
        fun fromJson(json: String, instanceDir: Path?): InstanceInfo {
            val o = JsonParser.parseString(json).asJsonObject
            val id = if (o.has("instanceId") && !o.get("instanceId").isJsonNull)
                o.get("instanceId").asString else UUID.randomUUID().toString()
            // M63: name 字段缺失时使用目录名作为回退，而非 NPE
            val name = if (o.has("name") && !o.get("name").isJsonNull)
                o.get("name").asString
            else
                instanceDir?.fileName?.toString() ?: "Unknown"
            val baseVersionId = if (o.has("baseVersionId") && !o.get("baseVersionId").isJsonNull)
                o.get("baseVersionId").asString else ""
            val type = try {
                if (o.has("type") && !o.get("type").isJsonNull)
                    Type.valueOf(o.get("type").asString) else Type.CUSTOM
            } catch (e: IllegalArgumentException) {
                Type.CUSTOM // 未知类型回退
            }
            val info = InstanceInfo(id, name, baseVersionId, type)
            if (o.has("loader") && !o.get("loader").isJsonNull) info.loader = o.get("loader").asString
            if (o.has("loaderVersion") && !o.get("loaderVersion").isJsonNull) info.loaderVersion = o.get("loaderVersion").asString
            if (o.has("description") && !o.get("description").isJsonNull) info.description = o.get("description").asString
            if (o.has("iconPath") && !o.get("iconPath").isJsonNull) info.iconPath = o.get("iconPath").asString
            if (o.has("createdAt") && !o.get("createdAt").isJsonNull) info.createdAt = o.get("createdAt").asLong
            if (o.has("lastPlayedAt") && !o.get("lastPlayedAt").isJsonNull) info.lastPlayedAt = o.get("lastPlayedAt").asLong
            if (o.has("totalPlayTimeSeconds") && !o.get("totalPlayTimeSeconds").isJsonNull) info.totalPlayTimeSeconds = o.get("totalPlayTimeSeconds").asLong
            if (o.has("boundAccountUuid") && !o.get("boundAccountUuid").isJsonNull) info.boundAccountUuid = o.get("boundAccountUuid").asString
            info.instanceDir = instanceDir
            return info
        }

        /** 兼容旧版 modpack.json 格式转换 */
        fun fromModpackJson(json: String, instanceDir: Path): InstanceInfo {
            val o = JsonParser.parseString(json).asJsonObject
            val name = if (o.has("name")) o.get("name").asString else (instanceDir.fileName?.toString() ?: "Unknown")
            val gameVersion = if (o.has("gameVersion")) o.get("gameVersion").asString else ""
            val id = UUID.randomUUID().toString()
            val info = InstanceInfo(id, name, gameVersion, Type.MODPACK)
            if (o.has("loader")) info.loader = o.get("loader").asString
            if (o.has("loaderVersion")) info.loaderVersion = o.get("loaderVersion").asString
            if (o.has("description")) info.description = o.get("description").asString
            info.instanceDir = instanceDir
            return info
        }
    }

    /** 序列化为 JSON（不包含 instanceDir） */
    fun toJson(): String {
        val o = JsonObject()
        o.addProperty("instanceId", instanceId)
        o.addProperty("name", name)
        o.addProperty("baseVersionId", baseVersionId)
        o.addProperty("type", type.name)
        if (loader != null) o.addProperty("loader", loader)
        if (loaderVersion != null) o.addProperty("loaderVersion", loaderVersion)
        if (description != null) o.addProperty("description", description)
        if (iconPath != null) o.addProperty("iconPath", iconPath)
        o.addProperty("createdAt", createdAt)
        o.addProperty("lastPlayedAt", lastPlayedAt)
        o.addProperty("totalPlayTimeSeconds", totalPlayTimeSeconds)
        if (boundAccountUuid.isNotEmpty()) o.addProperty("boundAccountUuid", boundAccountUuid)
        return o.toString()
    }

    /** 实例是否可启动（基础版本非空） */
    fun isLaunchable(): Boolean = baseVersionId.isNotEmpty()
}
