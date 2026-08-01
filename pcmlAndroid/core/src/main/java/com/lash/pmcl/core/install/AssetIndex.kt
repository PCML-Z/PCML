package com.lash.pmcl.core.install

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.util.LinkedHashMap

/**
 * 资产索引：包含所有资源文件（音效、贴图、语言等）的下载信息。
 *
 * Android 版本：从 Java 移植，逻辑保持一致（纯 JSON 解析，无平台依赖）。
 */
class AssetIndex private constructor(
    val id: String
) {
    private val assets: MutableMap<String, Asset> = LinkedHashMap()

    fun getAssets(): Map<String, Asset> = assets

    /**
     * 单个资产条目。
     */
    data class Asset(
        val hash: String,
        val size: Long
    ) {
        /** 资产存放路径：前两位 hash / hash */
        fun getPath(): String {
            if (hash.length < 2) return hash
            return hash.substring(0, 2) + "/" + hash
        }
    }

    companion object {
        /**
         * 解析资产索引；任一 object 缺少 hash 则失败（避免静默跳过导致「安装成功但资源不全」）。
         */
        @Throws(IOException::class)
        fun parse(json: String?): AssetIndex {
            if (json.isNullOrBlank()) {
                throw IOException("资产索引为空")
            }
            val root: JsonObject = try {
                JsonParser.parseString(json).asJsonObject
            } catch (e: Exception) {
                throw IOException("资产索引 JSON 解析失败", e)
            }
            val id = if (root.has("name") && !root.get("name").isJsonNull)
                root.get("name").asString else ""
            val idx = AssetIndex(id)
            if (!root.has("objects") || !root.get("objects").isJsonObject) {
                throw IOException("资产索引缺少 objects")
            }
            var missingHash = 0
            for ((key, value) in root.getAsJsonObject("objects").entrySet()) {
                if (!value.isJsonObject) {
                    missingHash++
                    continue
                }
                val o = value.asJsonObject
                if (!o.has("hash") || o.get("hash").isJsonNull) {
                    missingHash++
                    continue
                }
                val hash = o.get("hash").asString
                if (!hash.matches(Regex("[0-9a-fA-F]{40}"))) {
                    missingHash++
                    continue
                }
                val size = if (o.has("size") && !o.get("size").isJsonNull) o.get("size").asLong else 0L
                idx.assets[key] = Asset(hash, size)
            }
            if (missingHash > 0) {
                throw IOException("资产索引有 $missingHash 个条目缺少有效 SHA-1，拒绝安装")
            }
            if (idx.assets.isEmpty()) {
                throw IOException("资产索引 objects 为空")
            }
            return idx
        }
    }
}
