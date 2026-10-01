package com.pmcl.ui.page

import androidx.compose.ui.graphics.ImageBitmap
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pmcl.core.version.VersionManager
import com.pmcl.ui.util.decodeSampledBitmap
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * 从本机已安装的 Minecraft 里读取方块中文名和贴图。
 * 语言在资源索引里，贴图在原版 client.jar 里。
 */
internal class BlockCatalog private constructor(
    private val names: Map<String, String>,
    private val jar: ZipFile?
) : AutoCloseable {
    private val texturePaths = HashMap<String, String?>()
    private val images = HashMap<String, ImageBitmap?>()

    fun label(blockId: String): String {
        val path = blockId.substringAfter(':').substringBefore('[')
        return names["block.minecraft.$path"]
            ?: names["item.minecraft.$path"]
            ?: path
    }

    fun image(blockId: String): ImageBitmap? {
        val path = blockId.substringAfter(':').substringBefore('[')
        return images.getOrPut(path) {
            val entry = texturePaths.getOrPut(path) { findTexture(path) } ?: return@getOrPut null
            val zip = jar ?: return@getOrPut null
            val bytes = zip.getInputStream(zip.getEntry(entry) ?: return@getOrPut null).use { it.readBytes() }
            decodeSampledBitmap(bytes, 64)
        }
    }

    override fun close() {
        jar?.close()
    }

    private fun findTexture(blockName: String): String? {
        val merged = LinkedHashMap<String, String>()
        var current = blockName
        val seen = HashSet<String>()
        repeat(6) {
            if (!seen.add(current)) return@repeat
            val model = readModel(current) ?: return@repeat
            val textures = model.getAsJsonObject("textures")
            if (textures != null) {
                for ((key, value) in textures.entrySet()) {
                    if (key !in merged && value.isJsonPrimitive) merged[key] = value.asString
                }
            }
            val parent = model.get("parent")?.takeIf { it.isJsonPrimitive }?.asString ?: return@repeat
            val next = parent.substringAfter(':').substringAfterLast('/')
            if (next.isBlank() || next == current) return@repeat
            current = next
        }
        val preferred = listOf("all", "side", "front", "north", "top", "end", "layer0", "particle")
        var raw = preferred.firstNotNullOfOrNull { merged[it] } ?: merged.values.firstOrNull()
        var guard = 0
        while (raw != null && raw.startsWith("#") && guard++ < 4) {
            raw = merged[raw.removePrefix("#")]
        }
        val entry = raw?.let { textureEntry(it) }
        if (entry != null && jar?.getEntry(entry) != null) return entry
        val direct = "assets/minecraft/textures/block/$blockName.png"
        return direct.takeIf { jar?.getEntry(it) != null }
    }

    private fun readModel(blockName: String): JsonObject? {
        val zip = jar ?: return null
        val entry = zip.getEntry("assets/minecraft/models/block/$blockName.json") ?: return null
        return zip.getInputStream(entry).use { input ->
            JsonParser.parseReader(input.reader(StandardCharsets.UTF_8)).asJsonObject
        }
    }

    companion object {
        fun open(versionId: String, gameDir: Path): BlockCatalog {
            val chain = resolveChain(versionId, gameDir)
            val names = loadNames(chain)
            val jar = openTextureJar(chain)
            return BlockCatalog(names, jar)
        }

        private fun resolveChain(versionId: String, gameDir: Path): List<Path> {
            if (!safeId(versionId)) return emptyList()
            val roots = LinkedHashSet<Path>()
            VersionManager.detectAllMinecraftVersionsDirs().forEach { roots.add(it) }
            val named = gameDir.fileName?.toString()
            if (named == versionId) gameDir.parent?.let { roots.add(it) }
            if (gameDir.parent?.fileName?.toString() == "versions") gameDir.parent?.let { roots.add(it) }
            val start = roots.firstNotNullOfOrNull { dir ->
                val json = dir.resolve(versionId).resolve("$versionId.json")
                json.takeIf { Files.isRegularFile(it) }
            } ?: return emptyList()
            val chain = ArrayList<Path>()
            val seen = HashSet<String>()
            var current: Path? = start
            while (current != null && chain.size < 8) {
                val id = current.parent?.fileName?.toString() ?: break
                if (!seen.add(id)) break
                chain.add(current)
                val parentId = parentOf(current) ?: break
                if (!safeId(parentId)) break
                val versions = current.parent?.parent ?: break
                val parent = versions.resolve(parentId).resolve("$parentId.json")
                current = parent.takeIf { Files.isRegularFile(it) }
            }
            return chain
        }

        private fun parentOf(json: Path): String? {
            val root = readJson(json) ?: return null
            val parent = root.get("inheritsFrom")?.takeIf { it.isJsonPrimitive }?.asString ?: return null
            return parent.takeIf { safeId(it) }
        }

        private fun loadNames(chain: List<Path>): Map<String, String> {
            for (json in chain) {
                val root = readJson(json) ?: continue
                val indexId = root.getAsJsonObject("assetIndex")?.get("id")?.asString ?: continue
                if (!safeId(indexId)) continue
                val versions = json.parent?.parent ?: continue
                val assets = versions.parent?.resolve("assets") ?: continue
                val index = assets.resolve("indexes").resolve("$indexId.json")
                if (!Files.isRegularFile(index)) continue
                val objects = readJson(index)?.getAsJsonObject("objects") ?: continue
                val hash = objects.getAsJsonObject("minecraft/lang/zh_cn.json")
                    ?.get("hash")?.asString ?: continue
                if (!hash.matches(HASH)) continue
                val file = assets.resolve("objects").resolve(hash.take(2)).resolve(hash)
                if (!Files.isRegularFile(file)) continue
                val lang = readJson(file) ?: continue
                val names = HashMap<String, String>()
                for ((key, value) in lang.entrySet()) {
                    if (!value.isJsonPrimitive) continue
                    if (key.startsWith("block.minecraft.") || key.startsWith("item.minecraft.")) {
                        names[key] = value.asString
                    }
                }
                if (names.isNotEmpty()) return names
            }
            return emptyMap()
        }

        private fun openTextureJar(chain: List<Path>): ZipFile? {
            for (json in chain) {
                val id = json.parent?.fileName?.toString() ?: continue
                val jar = json.parent?.resolve("$id.jar") ?: continue
                if (!Files.isRegularFile(jar)) continue
                val zip = ZipFile(jar.toFile())
                if (zip.getEntry("assets/minecraft/textures/block/stone.png") != null) return zip
                zip.close()
            }
            return null
        }

        private fun readJson(path: Path): JsonObject? {
            return try {
                Files.newBufferedReader(path, StandardCharsets.UTF_8).use { reader ->
                    JsonParser.parseReader(reader).asJsonObject
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun safeId(id: String): Boolean {
            return id.isNotBlank() && id.length <= 80 && !id.contains("..") && id.none { it == '/' || it == '\\' }
        }

        private val HASH = Regex("[0-9a-fA-F]{40}")
    }
}

private fun textureEntry(raw: String): String {
    var value = raw.removePrefix("minecraft:")
    if (!value.contains('/')) value = "block/$value"
    return "assets/minecraft/textures/$value.png"
}
