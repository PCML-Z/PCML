package com.pmcl.ui.studio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties
import java.util.zip.ZipFile
import javax.imageio.ImageIO

internal data class ModMeta(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val authors: List<String>,
    val loader: String,
    val depends: List<String>,
    val homepage: String,
    val icon: ImageBitmap?,
    val provides: List<String> = emptyList(),
)

internal data class PackFile(
    val path: Path,
    val name: String,
    val size: Long,
    val directory: Boolean,
    val modified: Long,
)

private const val MAX_META_BYTES = 1_000_000L
private const val MAX_ICON_BYTES = 2_000_000L

private fun ZipFile.text(name: String): String? {
    val entry = getEntry(name) ?: return null
    if (entry.size > MAX_META_BYTES) return null
    return getInputStream(entry).use { String(it.readBytes(), StandardCharsets.UTF_8) }
}

private fun ZipFile.bytes(name: String, limit: Long): ByteArray? {
    val entry = getEntry(name.removePrefix("/")) ?: return null
    if (entry.size > limit) return null
    return getInputStream(entry).use { it.readBytes() }
}

internal fun readModMeta(path: Path): ModMeta? = try {
    ZipFile(path.toFile()).use { zip ->
        val fabric = zip.text("fabric.mod.json")
        val quilt = if (fabric == null) zip.text("quilt.mod.json") else null
        val meta = when {
            fabric != null -> parseFabric(zip, fabric)
            quilt != null -> parseQuilt(zip, quilt)
            else -> {
                val neo = zip.text("META-INF/neoforge.mods.toml")
                val forge = if (neo == null) zip.text("META-INF/mods.toml") else null
                val toml = neo ?: forge ?: return@use null
                parseToml(zip, toml, if (neo != null) "NeoForge" else "Forge")
            }
        }
        meta?.copy(provides = (meta.provides + nestedIds(zip)).distinct())
    }
} catch (_: Throwable) {
    null
}

private const val MAX_NESTED = 96
private const val MAX_NESTED_BYTES = 24L * 1024 * 1024

/** 读取 jar-in-jar（META-INF/jars、META-INF/jarjar）里内嵌模组的 ID，用来判断依赖是否已经自带。 */
private fun nestedIds(zip: ZipFile): List<String> {
    val out = ArrayList<String>()
    var seen = 0
    for (entry in zip.entries()) {
        val name = entry.name
        if (!name.endsWith(".jar") || !(name.startsWith("META-INF/jars/") || name.startsWith("META-INF/jarjar/"))) continue
        if (++seen > MAX_NESTED || entry.size > MAX_NESTED_BYTES) continue
        try {
            java.util.zip.ZipInputStream(zip.getInputStream(entry)).use { inner ->
                while (true) {
                    val e = inner.nextEntry ?: break
                    when (e.name) {
                        "fabric.mod.json", "quilt.mod.json" -> {
                            val obj = JsonParser.parseString(String(inner.readNBytes(MAX_META_BYTES.toInt()), StandardCharsets.UTF_8)).asJsonObject
                            val id = obj.str("id").ifBlank { obj.getAsJsonObject("quilt_loader")?.str("id").orEmpty() }
                            if (id.isNotBlank()) out += id
                            obj.getAsJsonArray("provides")?.forEach { if (it.isJsonPrimitive) out += it.asString }
                            break
                        }
                        "META-INF/mods.toml", "META-INF/neoforge.mods.toml" -> {
                            val toml = String(inner.readNBytes(MAX_META_BYTES.toInt()), StandardCharsets.UTF_8)
                            Regex("""(?m)^\s*modId\s*=\s*"([^"]+)"""").findAll(toml).forEach { out += it.groupValues[1] }
                            break
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
    }
    return out
}

private fun JsonObject.str(key: String): String =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

private fun parseFabric(zip: ZipFile, json: String): ModMeta {
    val obj = JsonParser.parseString(json).asJsonObject
    val authors = obj.getAsJsonArray("authors")?.mapNotNull { a ->
        when {
            a.isJsonPrimitive -> a.asString
            a.isJsonObject -> a.asJsonObject.str("name").ifBlank { null }
            else -> null
        }
    }.orEmpty()
    val depends = obj.getAsJsonObject("depends")?.keySet()?.toList().orEmpty()
    val icon = obj.get("icon")?.let { el ->
        when {
            el.isJsonPrimitive -> el.asString
            el.isJsonObject -> el.asJsonObject.entrySet().maxByOrNull { it.key.toIntOrNull() ?: 0 }?.value?.asString
            else -> null
        }
    }
    val homepage = obj.getAsJsonObject("contact")?.str("homepage").orEmpty()
    return ModMeta(
        id = obj.str("id"),
        name = obj.str("name").ifBlank { obj.str("id") },
        version = obj.str("version"),
        description = obj.str("description"),
        authors = authors,
        loader = "Fabric",
        depends = depends,
        homepage = homepage,
        icon = icon?.let { decodeIcon(zip, it) },
        provides = obj.getAsJsonArray("provides")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }.orEmpty(),
    )
}

private fun parseQuilt(zip: ZipFile, json: String): ModMeta {
    val root = JsonParser.parseString(json).asJsonObject
    val loader = root.getAsJsonObject("quilt_loader") ?: JsonObject()
    val meta = loader.getAsJsonObject("metadata") ?: JsonObject()
    val depends = loader.getAsJsonArray("depends")?.mapNotNull { d ->
        when {
            d.isJsonPrimitive -> d.asString
            d.isJsonObject -> d.asJsonObject.str("id").ifBlank { null }
            else -> null
        }
    }.orEmpty()
    val icon = meta.get("icon")?.takeIf { it.isJsonPrimitive }?.asString
    return ModMeta(
        id = loader.str("id"),
        name = meta.str("name").ifBlank { loader.str("id") },
        version = loader.str("version"),
        description = meta.str("description"),
        authors = meta.getAsJsonObject("contributors")?.keySet()?.toList().orEmpty(),
        loader = "Quilt",
        depends = depends,
        homepage = meta.getAsJsonObject("contact")?.str("homepage").orEmpty(),
        icon = icon?.let { decodeIcon(zip, it) },
    )
}

private fun tomlValue(src: String, key: String): String? {
    Regex("""(?m)^\s*$key\s*=\s*'''([\s\S]*?)'''""").find(src)?.let { return it.groupValues[1].trim() }
    Regex("""(?m)^\s*$key\s*=\s*\"\"\"([\s\S]*?)\"\"\"""").find(src)?.let { return it.groupValues[1].trim() }
    Regex("""(?m)^\s*$key\s*=\s*"((?:[^"\\\n]|\\.)*)"""").find(src)?.let { return it.groupValues[1] }
    Regex("""(?m)^\s*$key\s*=\s*'([^'\n]*)'""").find(src)?.let { return it.groupValues[1] }
    return null
}

private fun parseToml(zip: ZipFile, toml: String, loader: String): ModMeta {
    val modsAt = toml.indexOf("[[mods]]")
    val section = if (modsAt >= 0) toml.substring(modsAt).let { s ->
        val next = Regex("""(?m)^\s*\[""").findAll(s).map { it.range.first }.firstOrNull { it > 8 }
        if (next != null) s.substring(0, next) else s
    } else toml
    var version = tomlValue(section, "version").orEmpty()
    if (version.contains("\${")) {
        val manifest = zip.text("META-INF/MANIFEST.MF").orEmpty()
        version = Regex("""(?m)^Implementation-Version:\s*(.+)$""").find(manifest)?.groupValues?.get(1)?.trim() ?: version
    }
    val depends = requiredTomlDeps(toml)
    val id = tomlValue(section, "modId").orEmpty()
    return ModMeta(
        id = id,
        name = tomlValue(section, "displayName").orEmpty().ifBlank { id },
        version = version,
        description = tomlValue(section, "description").orEmpty(),
        authors = tomlValue(section, "authors")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
        loader = loader,
        depends = depends,
        homepage = tomlValue(section, "displayURL").orEmpty(),
        icon = (tomlValue(section, "logoFile") ?: tomlValue(toml, "logoFile"))?.let { decodeIcon(zip, it) },
    )
}

/** 只取必需依赖：旧格式 mandatory=true，新格式 type="required"（缺省也算必需）。 */
private fun requiredTomlDeps(toml: String): List<String> {
    val headers = Regex("""(?m)^\s*\[\[dependencies\.[^\]]+\]\]""").findAll(toml).map { it.range.first }.toList()
    val out = ArrayList<String>()
    headers.forEachIndexed { i, start ->
        val end = headers.getOrNull(i + 1) ?: toml.length
        val block = toml.substring(start, end).let { b ->
            val next = Regex("""(?m)^\s*\[(?!\[dependencies)""").find(b, 1)
            if (next != null) b.substring(0, next.range.first) else b
        }
        val id = tomlValue(block, "modId") ?: return@forEachIndexed
        val mandatory = Regex("""(?m)^\s*mandatory\s*=\s*(true|false)""").find(block)?.groupValues?.get(1)
        val type = tomlValue(block, "type")?.lowercase(Locale.ROOT)
        val required = when {
            mandatory != null -> mandatory == "true"
            type != null -> type == "required"
            else -> true
        }
        if (required) out += id
    }
    return out.distinct()
}

internal data class ModIssue(val error: Boolean, val text: String)

private val builtinIds = setOf(
    "minecraft", "java", "fabricloader", "fabric-loader", "quilt_loader", "forge", "neoforge", "fml", "javafml", "lowcodefml", "mixinextras"
)

/** 检查已启用模组：重复 ID、缺失依赖、依赖被停用。 */
internal fun inspectMods(mods: List<ModFile>, metas: Map<String, ModMeta?>): List<ModIssue> {
    fun key(m: ModFile) = m.name.removeSuffix(".disabled")
    val enabled = mods.filter { it.enabled }
    val disabled = mods.filter { !it.enabled }
    val enabledIds = HashMap<String, MutableList<String>>()
    val provided = HashSet<String>()
    for (m in enabled) {
        val meta = metas[key(m)] ?: continue
        if (meta.id.isNotBlank()) enabledIds.getOrPut(meta.id) { ArrayList() } += key(m)
        provided += meta.id
        provided += meta.provides
    }
    val disabledIds = HashSet<String>()
    for (m in disabled) metas[key(m)]?.let { disabledIds += it.id; disabledIds += it.provides }
    val hasFabricApi = "fabric-api" in provided || "fabric" in provided
    val issues = ArrayList<ModIssue>()
    enabledIds.filter { it.value.size > 1 }.forEach { (id, files) ->
        issues += ModIssue(true, com.pmcl.core.i18n.I18n.t("studio.issue_duplicate", id, files.joinToString(", ")))
    }
    for (m in enabled) {
        val meta = metas[key(m)] ?: continue
        for (dep in meta.depends) {
            if (dep in builtinIds || dep in provided) continue
            if (hasFabricApi && dep.startsWith("fabric-")) continue
            val name = meta.name.ifBlank { key(m) }
            issues += if (dep in disabledIds) ModIssue(false, com.pmcl.core.i18n.I18n.t("studio.issue_dep_disabled", name, dep))
            else ModIssue(true, com.pmcl.core.i18n.I18n.t("studio.issue_dep_missing", name, dep))
        }
    }
    return issues.sortedByDescending { it.error }
}

internal fun modListMarkdown(mods: List<ModFile>, metas: Map<String, ModMeta?>): String =
    mods.filter { it.enabled }.joinToString("\n") { m ->
        val meta = metas[m.name]
        "- " + (meta?.name?.ifBlank { null } ?: m.name) + (meta?.version?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "") +
            (meta?.id?.takeIf { it.isNotBlank() }?.let { " (`$it`)" } ?: "")
    }

internal data class StorageEntry(val path: Path, val name: String, val directory: Boolean, val size: Long?)

internal fun storageChildren(dir: Path): List<StorageEntry> = listChildren(dir).map {
    StorageEntry(it, it.fileName.toString(), Files.isDirectory(it), null)
}

/** 删除 logs 里已经压缩归档的旧日志（*.log.gz），返回删除个数和释放的字节数。 */
internal fun cleanOldLogs(gameDir: Path): Pair<Int, Long> {
    val logs = gameDir.resolve("logs")
    if (!Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS)) return 0 to 0L
    var count = 0
    var freed = 0L
    val files = try {
        Files.list(logs).use { stream ->
            stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".log.gz") }
                .toList()
        }
    } catch (_: Throwable) {
        emptyList()
    }
    files.forEach { file ->
        try {
            val size = Files.size(file)
            Files.delete(file)
            count++
            freed += size
        } catch (_: Throwable) {
        }
    }
    return count to freed
}

internal fun countOldLogs(gameDir: Path): Pair<Int, Long> {
    val logs = gameDir.resolve("logs")
    if (!Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS)) return 0 to 0L
    return try {
        Files.list(logs).use { stream ->
            val files = stream.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) && it.fileName.toString().endsWith(".log.gz") }.toList()
            files.size to files.sumOf { try { Files.size(it) } catch (_: Throwable) { 0L } }
        }
    } catch (_: Throwable) {
        0 to 0L
    }
}

private fun decodeIcon(zip: ZipFile, path: String): ImageBitmap? = try {
    zip.bytes(path, MAX_ICON_BYTES)?.let { bytes ->
        ImageIO.read(ByteArrayInputStream(bytes))?.let { scaleImage(it, 128) }
    }
} catch (_: Throwable) {
    null
}

internal fun scaleImage(img: BufferedImage, max: Int): ImageBitmap {
    val scale = minOf(1.0, max.toDouble() / maxOf(img.width, img.height))
    val w = (img.width * scale).toInt().coerceAtLeast(1)
    val h = (img.height * scale).toInt().coerceAtLeast(1)
    val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    val pixelArt = img.width <= 64
    g.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION,
        if (pixelArt) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR else RenderingHints.VALUE_INTERPOLATION_BILINEAR
    )
    g.drawImage(img, 0, 0, w, h, null)
    g.dispose()
    return out.toComposeImageBitmap()
}

/** 截图、存档图标等缩略图缓存，按路径 + 尺寸 + 修改时间索引。 */
internal object ThumbCache {
    private const val MAX_ENTRIES = 240
    private val map = LinkedHashMap<String, ImageBitmap>(64, 0.75f, true)

    fun load(path: Path, max: Int): ImageBitmap? {
        val modified = try { Files.getLastModifiedTime(path).toMillis() } catch (_: Throwable) { 0L }
        val key = "$path|$max|$modified"
        synchronized(map) { map[key]?.let { return it } }
        val bitmap = try {
            if (Files.size(path) > 40L * 1024 * 1024) null
            else ImageIO.read(path.toFile())?.let { scaleImage(it, max) }
        } catch (_: Throwable) {
            null
        } ?: return null
        synchronized(map) {
            map[key] = bitmap
            val it = map.keys.iterator()
            while (map.size > MAX_ENTRIES && it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        return bitmap
    }
}

internal fun packIcon(pack: PackFile): ImageBitmap? = try {
    if (pack.directory) {
        pack.path.resolve("pack.png").takeIf { Files.isRegularFile(it) }?.let { ThumbCache.load(it, 96) }
    } else if (pack.name.lowercase(Locale.ROOT).endsWith(".zip")) {
        ZipFile(pack.path.toFile()).use { zip ->
            zip.bytes("pack.png", MAX_ICON_BYTES)?.let { b -> ImageIO.read(ByteArrayInputStream(b))?.let { scaleImage(it, 96) } }
        }
    } else null
} catch (_: Throwable) {
    null
}

internal fun packDescription(pack: PackFile): String = try {
    val json = if (pack.directory) {
        pack.path.resolve("pack.mcmeta").takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
    } else if (pack.name.lowercase(Locale.ROOT).endsWith(".zip")) {
        ZipFile(pack.path.toFile()).use { it.text("pack.mcmeta") }
    } else null
    val desc = json?.let { JsonParser.parseString(it).asJsonObject.getAsJsonObject("pack")?.get("description") }
    when {
        desc == null -> ""
        desc.isJsonPrimitive -> desc.asString
        desc.isJsonObject -> desc.asJsonObject.str("text")
        desc.isJsonArray -> desc.asJsonArray.joinToString("") { e ->
            if (e.isJsonPrimitive) e.asString else if (e.isJsonObject) e.asJsonObject.str("text") else ""
        }
        else -> ""
    }.replace(Regex("§."), "")
} catch (_: Throwable) {
    ""
}

internal fun listPacks(gameDir: Path?, sub: String): List<PackFile> {
    val dir = gameDir?.resolve(sub) ?: return emptyList()
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return listChildren(dir).mapNotNull { path ->
        val name = path.fileName.toString()
        if (name.startsWith(".")) return@mapNotNull null
        val directory = Files.isDirectory(path)
        if (!directory && !name.lowercase(Locale.ROOT).endsWith(".zip")) return@mapNotNull null
        val size = if (directory) 0L else try { Files.size(path) } catch (_: Throwable) { 0L }
        val modified = try { Files.getLastModifiedTime(path).toMillis() } catch (_: Throwable) { 0L }
        PackFile(path, name, size, directory, modified)
    }
}

internal fun listScreenshots(gameDir: Path?): List<Path> {
    val dir = gameDir?.resolve("screenshots") ?: return emptyList()
    if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return try {
        Files.list(dir).use { stream ->
            stream.filter { p ->
                val n = p.fileName.toString().lowercase(Locale.ROOT)
                !Files.isSymbolicLink(p) && (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg"))
            }.toList()
        }.sortedByDescending { try { Files.getLastModifiedTime(it).toMillis() } catch (_: Throwable) { 0L } }.take(400)
    } catch (_: Throwable) {
        emptyList()
    }
}

/** 粗略统计目录大小，文件太多时提前停下并返回下限。 */
internal fun dirSize(dir: Path, maxFiles: Int = 20_000): Long {
    var total = 0L
    var count = 0
    try {
        Files.walk(dir).use { stream ->
            val it = stream.iterator()
            while (it.hasNext() && count < maxFiles) {
                val p = it.next()
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    total += try { Files.size(p) } catch (_: Throwable) { 0L }
                    count++
                }
            }
        }
    } catch (_: Throwable) {
    }
    return total
}

private val gson = GsonBuilder().disableHtmlEscaping().create()

internal fun readEnabledPacks(gameDir: Path?): List<String> {
    val file = gameDir?.resolve("options.txt") ?: return emptyList()
    if (!Files.isRegularFile(file)) return emptyList()
    return try {
        val line = Files.readAllLines(file, StandardCharsets.UTF_8).firstOrNull { it.startsWith("resourcePacks:") } ?: return emptyList()
        JsonParser.parseString(line.substringAfter(':')).asJsonArray.map { it.asString }
    } catch (_: Throwable) {
        emptyList()
    }
}

/** 在 options.txt 里启用/停用一个资源包。启用的放到最后，也就是优先级最高。 */
internal fun setPackEnabled(gameDir: Path, packName: String, enabled: Boolean) {
    val file = gameDir.resolve("options.txt")
    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) throw IllegalStateException("options.txt")
    val lines = Files.readAllLines(file, StandardCharsets.UTF_8).toMutableList()
    val id = "file/$packName"
    val index = lines.indexOfFirst { it.startsWith("resourcePacks:") }
    val current = if (index >= 0) {
        try {
            JsonParser.parseString(lines[index].substringAfter(':')).asJsonArray.map { it.asString }
        } catch (_: Throwable) {
            listOf("vanilla")
        }
    } else listOf("vanilla")
    val next = current.filter { it != id }.let { if (enabled) it + id else it }
    val array = JsonArray().apply { next.forEach { add(it) } }
    val line = "resourcePacks:" + gson.toJson(array)
    if (index >= 0) lines[index] = line else lines.add(line)
    writeAtomically(file, lines.joinToString("\n", postfix = "\n"))
}

private val shaderConfigs = listOf("config/iris.properties", "config/oculus.properties", "optionsshaders.txt")

internal fun shaderConfig(gameDir: Path?): Path? =
    gameDir?.let { dir -> shaderConfigs.map { dir.resolve(it) }.firstOrNull { Files.isRegularFile(it) && !Files.isSymbolicLink(it) } }

internal fun activeShader(gameDir: Path?): String? {
    val file = shaderConfig(gameDir) ?: return null
    return try {
        if (file.fileName.toString().endsWith(".properties")) {
            val props = Properties()
            Files.newBufferedReader(file, StandardCharsets.UTF_8).use { props.load(it) }
            if (props.getProperty("enableShaders", "true").equals("false", true)) null
            else props.getProperty("shaderPack")
        } else {
            Files.readAllLines(file, StandardCharsets.UTF_8).firstOrNull { it.startsWith("shaderPack=") }?.substringAfter('=')
        }
    } catch (_: Throwable) {
        null
    }
}

/** name 为 null 时关闭光影。 */
internal fun setActiveShader(gameDir: Path, name: String?) {
    val file = shaderConfig(gameDir) ?: throw IllegalStateException("no shader config")
    if (file.fileName.toString().endsWith(".properties")) {
        val props = Properties()
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { props.load(it) }
        if (name == null) {
            props.setProperty("enableShaders", "false")
        } else {
            props.setProperty("shaderPack", name)
            props.setProperty("enableShaders", "true")
        }
        val out = java.io.StringWriter()
        props.store(out, null)
        writeAtomically(file, out.toString())
    } else {
        val lines = Files.readAllLines(file, StandardCharsets.UTF_8).toMutableList()
        val value = name ?: "OFF"
        val index = lines.indexOfFirst { it.startsWith("shaderPack=") }
        if (index >= 0) lines[index] = "shaderPack=$value" else lines.add("shaderPack=$value")
        writeAtomically(file, lines.joinToString("\n", postfix = "\n"))
    }
}

internal fun writeAtomically(file: Path, text: String) {
    val dir = file.parent ?: throw IllegalStateException("no parent")
    val tmp = dir.resolve(file.fileName.toString() + ".pmcl-studio.tmp")
    try {
        Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}
