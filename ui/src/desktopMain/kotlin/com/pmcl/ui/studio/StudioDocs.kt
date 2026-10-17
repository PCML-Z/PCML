package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.awt.Desktop
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import javax.xml.parsers.DocumentBuilderFactory

internal enum class DocKind { WORD, EXCEL, PPT, MINDMAP, CSV }

internal data class DocMeta(
    val path: Path,
    val kind: DocKind,
    val name: String,
    val size: Long,
)

internal sealed interface DocPreview {
    data class Text(val lines: List<String>, val note: String = "") : DocPreview
    data class Table(val rows: List<List<String>>, val note: String = "") : DocPreview
    data class Slides(val slides: List<Pair<String, List<String>>>, val note: String = "") : DocPreview
    data class Mind(val root: MindNode, val editable: Boolean) : DocPreview
    data class Failed(val message: String) : DocPreview
    data object Empty : DocPreview
}

internal class MindNode(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    val children: MutableList<MindNode> = mutableStateListOf(),
)

private val wordExt = setOf("doc", "docx", "rtf", "odt")
private val excelExt = setOf("xls", "xlsx", "ods")
private val pptExt = setOf("ppt", "pptx", "odp")
private val mindExt = setOf("mm", "opml", "xmind", "pmclmm")
private val csvExt = setOf("csv", "tsv")

internal fun docKindOf(path: Path): DocKind? {
    val ext = path.fileName?.toString()?.substringAfterLast('.', "")?.lowercase(Locale.ROOT) ?: return null
    return when (ext) {
        in wordExt -> DocKind.WORD
        in excelExt -> DocKind.EXCEL
        in pptExt -> DocKind.PPT
        in mindExt -> DocKind.MINDMAP
        in csvExt -> DocKind.CSV
        else -> null
    }
}

internal fun isDocumentFile(path: Path): Boolean = docKindOf(path) != null

internal fun docColor(kind: DocKind): Color = when (kind) {
    DocKind.WORD -> Tiles.Blue
    DocKind.EXCEL -> Tiles.Forest
    DocKind.PPT -> Tiles.Orange
    DocKind.MINDMAP -> Tiles.Purple
    DocKind.CSV -> Tiles.Teal
}

internal fun docIcon(kind: DocKind): ImageVector = when (kind) {
    DocKind.WORD -> Icons.Filled.Description
    DocKind.EXCEL -> Icons.Filled.GridOn
    DocKind.PPT -> Icons.Filled.Slideshow
    DocKind.MINDMAP -> Icons.Filled.AccountTree
    DocKind.CSV -> Icons.Filled.TableChart
}

internal fun docLabel(kind: DocKind): String = when (kind) {
    DocKind.WORD -> I18n.t("studio.doc_word")
    DocKind.EXCEL -> I18n.t("studio.doc_excel")
    DocKind.PPT -> I18n.t("studio.doc_ppt")
    DocKind.MINDMAP -> I18n.t("studio.doc_mind")
    DocKind.CSV -> I18n.t("studio.doc_csv")
}

internal fun pickDocument(): Path? = try {
    val chooser = JFileChooser()
    chooser.fileSelectionMode = JFileChooser.FILES_ONLY
    chooser.dialogTitle = I18n.t("studio.doc_open")
    chooser.fileFilter = FileNameExtensionFilter(
        I18n.t("studio.doc_filter"),
        "docx", "doc", "rtf", "odt",
        "xlsx", "xls", "ods", "csv", "tsv",
        "pptx", "ppt", "odp",
        "mm", "opml", "xmind", "pmclmm"
    )
    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize()
    } else null
} catch (_: Throwable) {
    null
}

internal fun pickSaveMindMap(): Path? = try {
    val chooser = JFileChooser()
    chooser.dialogTitle = I18n.t("studio.doc_mind_save")
    chooser.fileFilter = FileNameExtensionFilter("PMCL Mind Map (*.pmclmm)", "pmclmm")
    chooser.selectedFile = java.io.File("mindmap.pmclmm")
    if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        var file = chooser.selectedFile
        if (!file.name.lowercase(Locale.ROOT).endsWith(".pmclmm")) {
            file = java.io.File(file.parentFile, file.name + ".pmclmm")
        }
        file.toPath().toAbsolutePath().normalize()
    } else null
} catch (_: Throwable) {
    null
}

internal fun openExternally(path: Path): String? {
    return try {
        val file = path.toFile()
        if (!file.exists()) return I18n.t("studio.doc_missing")
        val os = System.getProperty("os.name", "").lowercase(Locale.ROOT)
        when {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) -> {
                Desktop.getDesktop().open(file)
            }
            os.contains("mac") -> ProcessBuilder("open", file.absolutePath).start()
            os.contains("win") -> ProcessBuilder("cmd", "/c", "start", "", file.absolutePath).start()
            else -> ProcessBuilder("xdg-open", file.absolutePath).start()
        }
        null
    } catch (e: Throwable) {
        I18n.t("studio.doc_open_failed", e.message ?: e.javaClass.simpleName)
    }
}

internal fun loadDocPreview(path: Path): DocPreview {
    val kind = docKindOf(path) ?: return DocPreview.Failed(I18n.t("studio.doc_unsupported"))
    if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) return DocPreview.Failed(I18n.t("studio.doc_missing"))
    return try {
        when (kind) {
            DocKind.CSV -> previewCsv(path)
            DocKind.WORD -> previewWord(path)
            DocKind.EXCEL -> previewExcel(path)
            DocKind.PPT -> previewPpt(path)
            DocKind.MINDMAP -> previewMind(path)
        }
    } catch (e: Throwable) {
        DocPreview.Failed(e.message ?: e.javaClass.simpleName)
    }
}

private fun previewCsv(path: Path): DocPreview {
    val text = Files.readString(path, StandardCharsets.UTF_8)
    val sep = if (path.fileName.toString().endsWith(".tsv", true)) '\t' else ','
    val rows = text.lineSequence().take(200).map { splitCsv(it, sep) }.filter { it.isNotEmpty() }.toList()
    return if (rows.isEmpty()) DocPreview.Empty else DocPreview.Table(rows, I18n.t("studio.doc_preview_note"))
}

private fun splitCsv(line: String, sep: Char): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var q = false
    for (ch in line) {
        when {
            ch == '"' -> q = !q
            ch == sep && !q -> { out += cur.toString(); cur.clear() }
            else -> cur.append(ch)
        }
    }
    out += cur.toString()
    return out
}

private fun previewWord(path: Path): DocPreview {
    val name = path.fileName.toString().lowercase(Locale.ROOT)
    if (!name.endsWith(".docx")) {
        return DocPreview.Text(listOf(I18n.t("studio.doc_legacy_hint")), I18n.t("studio.doc_use_system"))
    }
    ZipFile(path.toFile()).use { zip ->
        val entry = zip.getEntry("word/document.xml") ?: return DocPreview.Failed(I18n.t("studio.doc_bad_zip"))
        if (entry.size > 8_000_000) return DocPreview.Failed(I18n.t("studio.doc_too_large"))
        val xml = zip.getInputStream(entry).use { String(it.readBytes(), StandardCharsets.UTF_8) }
        val lines = extractTaggedText(xml, "w:t").ifEmpty { extractTaggedText(xml, "t") }
            .map { it.trim() }.filter { it.isNotEmpty() }.take(800)
        return if (lines.isEmpty()) DocPreview.Empty
        else DocPreview.Text(lines, I18n.t("studio.doc_preview_note"))
    }
}

private fun previewExcel(path: Path): DocPreview {
    val name = path.fileName.toString().lowercase(Locale.ROOT)
    if (!name.endsWith(".xlsx")) {
        return DocPreview.Text(listOf(I18n.t("studio.doc_legacy_hint")), I18n.t("studio.doc_use_system"))
    }
    ZipFile(path.toFile()).use { zip ->
        val shared = zip.getEntry("xl/sharedStrings.xml")?.let { e ->
            if (e.size > 6_000_000) emptyList()
            else {
                val xml = zip.getInputStream(e).use { String(it.readBytes(), StandardCharsets.UTF_8) }
                extractTaggedText(xml, "t")
            }
        }.orEmpty()
        val sheet = zip.getEntry("xl/worksheets/sheet1.xml") ?: zip.entries().asSequence()
            .firstOrNull { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
            ?: return DocPreview.Failed(I18n.t("studio.doc_bad_zip"))
        if (sheet.size > 8_000_000) return DocPreview.Failed(I18n.t("studio.doc_too_large"))
        val xml = zip.getInputStream(sheet).use { String(it.readBytes(), StandardCharsets.UTF_8) }
        val rows = parseSheetRows(xml, shared).take(120)
        return if (rows.isEmpty()) DocPreview.Empty
        else DocPreview.Table(rows, I18n.t("studio.doc_preview_note"))
    }
}

private fun parseSheetRows(xml: String, shared: List<String>): List<List<String>> {
    val rowRe = Regex("""<row\b[^>]*>(.*?)</row>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    val cellRe = Regex("""<c\b([^>]*)>(?:.*?<v>(.*?)</v>)?""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    return rowRe.findAll(xml).map { row ->
        cellRe.findAll(row.groupValues[1]).map { cell ->
            val attrs = cell.groupValues[1]
            val v = cell.groupValues.getOrNull(2).orEmpty()
            if (attrs.contains("""t="s"""") || attrs.contains("t='s'")) {
                val idx = v.toIntOrNull()
                if (idx != null && idx in shared.indices) shared[idx] else v
            } else v
        }.toList()
    }.filter { it.any { c -> c.isNotBlank() } }.toList()
}

private fun previewPpt(path: Path): DocPreview {
    val name = path.fileName.toString().lowercase(Locale.ROOT)
    if (!name.endsWith(".pptx")) {
        return DocPreview.Text(listOf(I18n.t("studio.doc_legacy_hint")), I18n.t("studio.doc_use_system"))
    }
    ZipFile(path.toFile()).use { zip ->
        val slides = zip.entries().asSequence()
            .filter { it.name.matches(Regex("""ppt/slides/slide\d+\.xml""")) }
            .sortedBy { it.name }
            .take(40)
            .mapIndexed { i, entry ->
                if (entry.size > 4_000_000) return@mapIndexed (I18n.t("studio.doc_slide", (i + 1).toString()) to listOf("…"))
                val xml = zip.getInputStream(entry).use { String(it.readBytes(), StandardCharsets.UTF_8) }
                val texts = extractTaggedText(xml, "a:t").ifEmpty { extractTaggedText(xml, "t") }
                    .map { it.trim() }.filter { it.isNotEmpty() }.take(80)
                I18n.t("studio.doc_slide", (i + 1).toString()) to texts
            }.toList()
        return if (slides.isEmpty()) DocPreview.Empty
        else DocPreview.Slides(slides, I18n.t("studio.doc_preview_note"))
    }
}

private fun previewMind(path: Path): DocPreview {
    val name = path.fileName.toString().lowercase(Locale.ROOT)
    return when {
        name.endsWith(".pmclmm") -> {
            val text = Files.readString(path, StandardCharsets.UTF_8)
            DocPreview.Mind(parsePmclMind(text), editable = true)
        }
        name.endsWith(".mm") -> {
            val text = Files.readString(path, StandardCharsets.UTF_8)
            DocPreview.Mind(parseFreeMind(text), editable = false)
        }
        name.endsWith(".opml") -> {
            val text = Files.readString(path, StandardCharsets.UTF_8)
            DocPreview.Mind(parseOpml(text), editable = false)
        }
        name.endsWith(".xmind") -> {
            ZipFile(path.toFile()).use { zip ->
                val content = zip.getEntry("content.json")
                    ?: zip.entries().asSequence().firstOrNull { it.name.endsWith("content.json") }
                if (content != null && content.size < 4_000_000) {
                    val json = zip.getInputStream(content).use { String(it.readBytes(), StandardCharsets.UTF_8) }
                    val texts = Regex(""""(?:title|text)"\s*:\s*"([^"]+)"""").findAll(json)
                        .map { it.groupValues[1] }.take(200).toList()
                    if (texts.isNotEmpty()) return DocPreview.Text(texts, I18n.t("studio.doc_xmind_note"))
                }
            }
            DocPreview.Text(listOf(I18n.t("studio.doc_xmind_note")), I18n.t("studio.doc_use_system"))
        }
        else -> DocPreview.Failed(I18n.t("studio.doc_unsupported"))
    }
}

private fun extractTaggedText(xml: String, tag: String): List<String> {
    val re = Regex("""<$tag\b[^>]*>(.*?)</$tag>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    return re.findAll(xml).map { unescapeXml(it.groupValues[1].replace(Regex("<[^>]+>"), "")) }.toList()
}

private fun unescapeXml(s: String): String = s
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&apos;", "'")

private fun parseXml(text: String): org.w3c.dom.Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    return factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)))
}

private fun parseFreeMind(text: String): MindNode {
    val doc = parseXml(text)
    val rootEl = doc.getElementsByTagName("node").item(0) as? Element
        ?: return MindNode(title = I18n.t("studio.doc_mind_root"))
    return freemindNode(rootEl)
}

private fun freemindNode(el: Element): MindNode {
    val title = el.getAttribute("TEXT").ifBlank { el.getAttribute("text") }.ifBlank { "…" }
    val node = MindNode(title = title)
    val kids = el.childNodes
    for (i in 0 until kids.length) {
        val n = kids.item(i)
        if (n.nodeType == Node.ELEMENT_NODE && n.nodeName.equals("node", true)) {
            node.children.add(freemindNode(n as Element))
        }
    }
    return node
}

private fun parseOpml(text: String): MindNode {
    val doc = parseXml(text)
    val outlines = doc.getElementsByTagName("outline")
    val rootEl = outlines.item(0) as? Element
        ?: return MindNode(title = I18n.t("studio.doc_mind_root"))
    return opmlNode(rootEl)
}

private fun opmlNode(el: Element): MindNode {
    val title = el.getAttribute("text").ifBlank { el.getAttribute("title") }.ifBlank { "…" }
    val node = MindNode(title = title)
    val kids = el.childNodes
    for (i in 0 until kids.length) {
        val n = kids.item(i)
        if (n.nodeType == Node.ELEMENT_NODE && n.nodeName.equals("outline", true)) {
            node.children.add(opmlNode(n as Element))
        }
    }
    return node
}

private fun parsePmclMind(text: String): MindNode {
    val gson = com.google.gson.JsonParser.parseString(text).asJsonObject
    fun walk(obj: com.google.gson.JsonObject): MindNode {
        val title = obj.get("title")?.asString ?: "…"
        val node = MindNode(id = obj.get("id")?.asString ?: UUID.randomUUID().toString(), title = title)
        obj.getAsJsonArray("children")?.forEach { el ->
            if (el.isJsonObject) node.children.add(walk(el.asJsonObject))
        }
        return node
    }
    return walk(gson)
}

internal fun serializePmclMind(root: MindNode): String {
    fun walk(node: MindNode): com.google.gson.JsonObject {
        val obj = com.google.gson.JsonObject()
        obj.addProperty("id", node.id)
        obj.addProperty("title", node.title)
        val arr = com.google.gson.JsonArray()
        node.children.forEach { arr.add(walk(it)) }
        obj.add("children", arr)
        return obj
    }
    return com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(walk(root))
}

internal fun newMindMap(): MindNode = MindNode(title = I18n.t("studio.doc_mind_root")).also { root ->
    root.children.add(MindNode(title = I18n.t("studio.doc_mind_idea") + " 1"))
    root.children.add(MindNode(title = I18n.t("studio.doc_mind_idea") + " 2"))
}

@Composable
internal fun DocsPanel(
    recent: List<String>,
    onOpen: (Path) -> Unit,
    onPick: () -> Unit,
    onNewMind: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val p = LocalStudio.current
    Column(Modifier.fillMaxSize().background(p.panel)) {
        PanelHeader(I18n.t("studio.docs")) {
            Block("", onClick = onPick, icon = Icons.Filled.FolderOpen, height = 32.dp)
        }
        Row(Modifier.fillMaxWidth().height(40.dp)) {
            Block(I18n.t("studio.doc_open"), onClick = onPick, icon = Icons.Filled.FolderOpen, fill = Tiles.Blue, modifier = Modifier.weight(1f), height = 40.dp)
            Block(I18n.t("studio.doc_new_mind"), onClick = onNewMind, icon = Icons.Filled.AccountTree, fill = Tiles.Purple, modifier = Modifier.weight(1f), height = 40.dp)
        }
        Row(Modifier.fillMaxWidth().height(72.dp)) {
            ColorTile(I18n.t("studio.doc_word"), "DOCX", Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Description, onPick, valueSize = 14, index = 0)
            ColorTile(I18n.t("studio.doc_excel"), "XLSX", Tiles.Forest, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.GridOn, onPick, valueSize = 14, index = 1)
            ColorTile(I18n.t("studio.doc_ppt"), "PPTX", Tiles.Orange, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Slideshow, onPick, valueSize = 14, index = 2)
            ColorTile(I18n.t("studio.doc_mind"), "MM", Tiles.Purple, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.AccountTree, onNewMind, valueSize = 14, index = 3)
        }
        if (recent.isEmpty()) {
            FlatText(I18n.t("studio.doc_empty"), color = p.muted, lines = 4, modifier = Modifier.padding(12.dp))
            return@Column
        }
        FlatText(I18n.t("studio.doc_recent"), color = p.muted, size = 11, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(recent, key = { it }) { path ->
                val file = runCatching { Paths.get(path) }.getOrNull()
                val kind = file?.let { docKindOf(it) }
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clickable { file?.let(onOpen) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(44.dp).background(kind?.let { docColor(it) } ?: Tiles.Slate), contentAlignment = Alignment.Center) {
                        Icon(kind?.let { docIcon(it) } ?: Icons.Filled.Description, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        FlatText(file?.fileName?.toString() ?: path, weight = FontWeight.Medium)
                        FlatText(kind?.let { docLabel(it) } ?: path, color = p.muted, size = 10)
                    }
                    Block("", onClick = { onRemove(path) }, icon = Icons.Filled.Close, height = 44.dp)
                }
            }
        }
    }
}

@Composable
internal fun DocumentEditor(
    filePath: String,
    onReveal: (Path) -> Unit,
) {
    val p = LocalStudio.current
    val path = remember(filePath) { runCatching { Paths.get(filePath).toAbsolutePath().normalize() }.getOrNull() }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    val meta = remember(path) {
        path?.let {
            val kind = docKindOf(it) ?: return@let null
            val size = try { Files.size(it) } catch (_: Throwable) { 0L }
            DocMeta(it, kind, it.fileName?.toString().orEmpty(), size)
        }
    }
    val preview by produceState<DocPreview?>(null, path, reload) {
        value = null
        value = if (path == null) DocPreview.Failed(I18n.t("studio.doc_missing"))
        else withContext(Dispatchers.IO) { loadDocPreview(path) }
    }
    var mindRoot by remember(filePath) { mutableStateOf<MindNode?>(null) }
    LaunchedEffect(preview) {
        val mind = (preview as? DocPreview.Mind)?.root
        if (mind != null) mindRoot = mind
    }
    if (meta == null) {
        CenterNote(I18n.t("studio.doc_missing"))
        return
    }
    val color = docColor(meta.kind)
    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(96.dp)) {
            ColorTile(meta.name, docLabel(meta.kind), color, Modifier.weight(2f).fillMaxHeight(), docIcon(meta.kind), valueSize = 18, index = 0)
            ColorTile(I18n.t("studio.col_size"), formatSize(meta.size), Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), index = 1)
            ColorTile(
                I18n.t("studio.doc_status"),
                if (dirty) I18n.t("studio.doc_dirty") else I18n.t("studio.doc_ready"),
                if (dirty) Tiles.Orange else Tiles.Forest,
                Modifier.weight(1f).fillMaxHeight(),
                index = 2
            )
        }
        Row(Modifier.fillMaxWidth().height(36.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            Block(
                I18n.t("studio.doc_system"),
                onClick = {
                    message = openExternally(meta.path) ?: I18n.t("studio.doc_opened")
                },
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                fill = color,
                height = 36.dp
            )
            if (meta.kind == DocKind.MINDMAP && mindRoot != null) {
                Block(
                    I18n.t("studio.save"),
                    onClick = {
                        scope.launch {
                            message = try {
                                withContext(Dispatchers.IO) {
                                    val target = if (meta.path.fileName.toString().endsWith(".pmclmm", true)) meta.path
                                    else pickSaveMindMap() ?: return@withContext null
                                    writeAtomically(target, serializePmclMind(mindRoot!!))
                                    target
                                }?.let {
                                    dirty = false
                                    I18n.t("studio.saved")
                                }
                            } catch (e: Throwable) {
                                I18n.t("studio.save_failed", e.message ?: e.javaClass.simpleName)
                            }
                        }
                    },
                    icon = Icons.Filled.Save,
                    fill = Tiles.Navy,
                    enabled = dirty || !meta.path.fileName.toString().endsWith(".pmclmm", true),
                    height = 36.dp
                )
                Block(
                    I18n.t("studio.doc_add_child"),
                    onClick = {
                        mindRoot?.children?.add(MindNode(title = I18n.t("studio.doc_mind_idea")))
                        dirty = true
                    },
                    icon = Icons.Filled.Add,
                    height = 36.dp
                )
            }
            Spacer(Modifier.weight(1f))
            Block("", onClick = { reload++; dirty = false }, icon = Icons.Filled.FolderOpen, height = 36.dp)
            Block("", onClick = { onReveal(meta.path.parent ?: meta.path) }, icon = Icons.Filled.FolderOpen, height = 36.dp)
        }
        message?.let {
            FlatText(it, color = Color.White, modifier = Modifier.fillMaxWidth().background(Tiles.Navy).padding(8.dp))
        }
        when (val view = preview) {
            null -> CenterNote(I18n.t("studio.loading"))
            is DocPreview.Failed -> CenterNote(view.message)
            DocPreview.Empty -> CenterNote(I18n.t("studio.doc_empty_preview"))
            is DocPreview.Text -> TextPreview(view.lines, view.note)
            is DocPreview.Table -> TablePreview(view.rows, view.note)
            is DocPreview.Slides -> SlidesPreview(view.slides, view.note)
            is DocPreview.Mind -> {
                val root = mindRoot ?: view.root
                MindPreview(root, editable = view.editable || meta.path.fileName.toString().endsWith(".pmclmm", true)) {
                    dirty = true
                }
            }
        }
    }
}

@Composable
private fun TextPreview(lines: List<String>, note: String) {
    val p = LocalStudio.current
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        if (note.isNotBlank()) {
            FlatText(note, color = Color.White, size = 11, modifier = Modifier.fillMaxWidth().background(Tiles.Graphite).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Row(Modifier.fillMaxWidth().height(30.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            FlatText(I18n.t("studio.lines_n", lines.size.toString()), color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 12.dp).weight(1f))
            Block("", onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }, icon = Icons.Filled.Description, height = 30.dp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(lines) { i, line ->
                FlatText(line, lines = 4, modifier = Modifier.fillMaxWidth().background(if (i % 2 == 0) p.editor else p.panel).padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun TablePreview(rows: List<List<String>>, note: String) {
    val p = LocalStudio.current
    val cols = rows.maxOfOrNull { it.size } ?: 0
    Column(Modifier.fillMaxSize()) {
        if (note.isNotBlank()) {
            FlatText(note, color = Color.White, size = 11, modifier = Modifier.fillMaxWidth().background(Tiles.Graphite).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        val hScroll = rememberScrollState()
        Column(Modifier.fillMaxSize().horizontalScroll(hScroll).verticalScroll(rememberScrollState())) {
            rows.forEachIndexed { r, row ->
                Row(Modifier.heightIn(min = 32.dp).background(if (r == 0) Tiles.Forest.copy(alpha = 0.2f) else if (r % 2 == 0) p.editor else p.panel)) {
                    for (c in 0 until cols) {
                        FlatText(
                            row.getOrNull(c).orEmpty(),
                            weight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.width(140.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                            lines = 2
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlidesPreview(slides: List<Pair<String, List<String>>>, note: String) {
    val p = LocalStudio.current
    Column(Modifier.fillMaxSize()) {
        if (note.isNotBlank()) {
            FlatText(note, color = Color.White, size = 11, modifier = Modifier.fillMaxWidth().background(Tiles.Graphite).padding(horizontal = 12.dp, vertical = 6.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(slides) { index, (title, lines) ->
                Column(Modifier.fillMaxWidth().entrance(index).padding(bottom = 2.dp)) {
                    FlatText(title, color = Color.White, weight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().background(Tiles.Orange).padding(horizontal = 14.dp, vertical = 8.dp))
                    if (lines.isEmpty()) {
                        FlatText("—", color = p.muted, modifier = Modifier.padding(12.dp))
                    } else {
                        lines.forEachIndexed { i, line ->
                            FlatText("• $line", lines = 3, modifier = Modifier.fillMaxWidth().background(if (i % 2 == 0) p.editor else p.panel).padding(horizontal = 16.dp, vertical = 5.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MindPreview(root: MindNode, editable: Boolean, onChange: () -> Unit) {
    val p = LocalStudio.current
    val expanded = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply { put(root.id, true) } }
    Column(Modifier.fillMaxSize()) {
        FlatText(
            if (editable) I18n.t("studio.doc_mind_edit_hint") else I18n.t("studio.doc_mind_view_hint"),
            color = Color.White,
            size = 11,
            modifier = Modifier.fillMaxWidth().background(Tiles.Purple).padding(horizontal = 12.dp, vertical = 6.dp)
        )
        LazyColumn(Modifier.fillMaxSize()) {
            val rows = ArrayList<Pair<MindNode, Int>>()
            fun walk(node: MindNode, depth: Int) {
                rows += node to depth
                if (expanded[node.id] != false) node.children.forEach { walk(it, depth + 1) }
            }
            walk(root, 0)
            items(rows, key = { it.first.id }) { (node, depth) ->
                val open = expanded[node.id] != false
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .background(if (depth == 0) Tiles.Purple.copy(alpha = 0.18f) else if (depth % 2 == 0) p.editor else p.panel)
                        .padding(start = (10 + depth * 18).dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (node.children.isNotEmpty()) {
                        Icon(
                            if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight,
                            null,
                            tint = p.muted,
                            modifier = Modifier.size(16.dp).clickable { expanded[node.id] = !open }
                        )
                    } else Spacer(Modifier.width(16.dp))
                    Box(Modifier.width(4.dp).height(22.dp).background(when (depth) {
                        0 -> Tiles.Purple
                        1 -> Tiles.Blue
                        2 -> Tiles.Teal
                        else -> Tiles.Slate
                    }))
                    Spacer(Modifier.width(8.dp))
                    if (editable) {
                        var editing by remember(node.id) { mutableStateOf(node.title) }
                        FlatField(
                            editing,
                            {
                                editing = it
                                node.title = it
                                onChange()
                            },
                            I18n.t("studio.doc_mind_idea"),
                            Modifier.weight(1f).height(30.dp)
                        )
                        Block("", onClick = {
                            node.children.add(MindNode(title = I18n.t("studio.doc_mind_idea")))
                            expanded[node.id] = true
                            onChange()
                        }, icon = Icons.Filled.Add, height = 36.dp)
                        if (depth > 0) {
                            Block("", onClick = {
                                removeMindChild(root, node.id)
                                onChange()
                            }, icon = Icons.Filled.Close, height = 36.dp)
                        }
                    } else {
                        FlatText(node.title, weight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f), lines = 2)
                    }
                }
            }
        }
    }
}

private fun removeMindChild(root: MindNode, id: String): Boolean {
    val it = root.children.iterator()
    while (it.hasNext()) {
        val child = it.next()
        if (child.id == id) {
            it.remove()
            return true
        }
        if (removeMindChild(child, id)) return true
    }
    return false
}
