package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import kotlin.coroutines.coroutineContext

internal data class SearchHit(val file: Path, val line: Int, val text: String, val start: Int, val end: Int)

internal data class SearchResult(val hits: List<SearchHit>, val files: Int, val truncated: Boolean, val error: String?)

private val skippedDirs = setOf("assets", "libraries", "versions", "natives", "runtime", ".git", "node_modules", ".fabric", ".mixin.out", "screenshots")
private const val MAX_SEARCH_FILES = 6000
private const val MAX_HITS = 1500

internal suspend fun searchFiles(
    root: Path,
    query: String,
    caseSensitive: Boolean,
    regex: Boolean,
    fileFilter: String,
): SearchResult {
    val pattern = try {
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        Regex(if (regex) query else Regex.escape(query), options)
    } catch (e: Throwable) {
        return SearchResult(emptyList(), 0, false, e.message ?: "regex")
    }
    val filter = fileFilter.trim().lowercase(Locale.ROOT).removePrefix("*")
    val candidates = ArrayList<Path>()
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (attrs.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
            val name = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
            return if (dir != root && name in skippedDirs) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
        }

        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            if (candidates.size >= MAX_SEARCH_FILES) return FileVisitResult.TERMINATE
            if (!attrs.isRegularFile || attrs.isSymbolicLink || attrs.size() > MAX_VIEW_BYTES) return FileVisitResult.CONTINUE
            if (!isTextFile(file) || isRefused(file)) return FileVisitResult.CONTINUE
            if (filter.isNotEmpty() && !file.fileName.toString().lowercase(Locale.ROOT).contains(filter)) return FileVisitResult.CONTINUE
            candidates.add(file)
            return FileVisitResult.CONTINUE
        }

        override fun visitFileFailed(file: Path, exc: java.io.IOException): FileVisitResult = FileVisitResult.CONTINUE
    })
    val hits = ArrayList<SearchHit>()
    var truncated = candidates.size >= MAX_SEARCH_FILES
    outer@ for (file in candidates) {
        coroutineContext.ensureActive()
        val lines = try { Files.readAllLines(file, StandardCharsets.UTF_8) } catch (_: Throwable) { continue }
        for ((index, line) in lines.withIndex()) {
            val m = pattern.find(line) ?: continue
            val from = (m.range.first - 60).coerceAtLeast(0)
            val snippet = line.substring(from, minOf(line.length, from + 240))
            hits.add(SearchHit(file, index, snippet, m.range.first - from, (m.range.last + 1 - from).coerceAtMost(snippet.length)))
            if (hits.size >= MAX_HITS) {
                truncated = true
                break@outer
            }
        }
    }
    return SearchResult(hits, candidates.size, truncated, null)
}

@Composable
internal fun SearchPanel(root: Path?, onOpen: (Path, Path, Int) -> Unit) {
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<SearchResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun run() {
        val r = root ?: return
        val q = query
        if (q.isBlank()) return
        job?.cancel()
        busy = true
        job = scope.launch {
            try {
                result = withContext(Dispatchers.IO) { searchFiles(r, q, caseSensitive, regex, filter) }
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(30.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            FlatField(
                query, { query = it }, I18n.t("studio.search_hint"), Modifier.width(300.dp), Icons.Filled.Search,
                onKey = { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { run(); true } else false }
            )
            Block("Aa", onClick = { caseSensitive = !caseSensitive }, selected = caseSensitive, height = 30.dp)
            Block(".*", onClick = { regex = !regex }, selected = regex, height = 30.dp)
            FlatField(filter, { filter = it }, I18n.t("studio.search_filter"), Modifier.width(150.dp), Icons.Filled.FilterList)
            Block(
                if (busy) I18n.t("studio.searching") else I18n.t("studio.search"),
                onClick = { run() },
                fill = p.accent,
                enabled = root != null && query.isNotBlank() && !busy,
                height = 30.dp
            )
            val r = result
            FlatText(
                when {
                    root == null -> I18n.t("studio.pick_target")
                    r == null -> I18n.t("studio.search_scope")
                    r.error != null -> r.error
                    else -> I18n.t("studio.search_summary", r.hits.size.toString(), r.files.toString()) + if (r.truncated) " · " + I18n.t("studio.search_truncated") else ""
                },
                color = if (r?.error != null) p.error else p.muted,
                size = 11,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
        }
        val hits = result?.hits.orEmpty()
        val r = root
        if (hits.isEmpty() || r == null) {
            if (result != null && result?.error == null) CenterNote(I18n.t("studio.search_none"))
            return@Column
        }
        val rows = remember(hits) {
            val out = ArrayList<Any>()
            var last: Path? = null
            for (h in hits) {
                if (h.file != last) {
                    out.add(h.file)
                    last = h.file
                }
                out.add(h)
            }
            out
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows) { row ->
                when (row) {
                    is Path -> Row(Modifier.fillMaxWidth().height(26.dp).background(p.panelAlt), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).fillMaxHeight().background(Tiles.Sky))
                        FlatText(
                            row.fileName.toString(),
                            weight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                        FlatText(
                            r.relativize(row).parent?.toString().orEmpty(),
                            color = p.muted,
                            size = 11,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                    }
                    is SearchHit -> Row(
                        Modifier.fillMaxWidth().height(22.dp).clickable { onOpen(r, row.file, row.line) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FlatText(
                            (row.line + 1).toString(),
                            color = p.gutter,
                            size = 11,
                            modifier = Modifier.width(64.dp).padding(start = 16.dp)
                        )
                        Text(
                            buildAnnotatedString {
                                val t = row.text
                                val s = row.start.coerceIn(0, t.length)
                                val e = row.end.coerceIn(s, t.length)
                                append(t.substring(0, s))
                                withStyle(SpanStyle(background = Tiles.Sky.copy(alpha = 0.3f), fontWeight = FontWeight.SemiBold)) {
                                    append(t.substring(s, e))
                                }
                                append(t.substring(e))
                            },
                            style = mono.copy(color = p.text),
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}
