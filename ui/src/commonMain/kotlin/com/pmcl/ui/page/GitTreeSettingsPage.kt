package com.pmcl.ui.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.update.RepoCommitGraph
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.refreshRepoGitTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LaneColors = listOf(
    Color(0xFF7C4DFF),
    Color(0xFF26A69A),
    Color(0xFF42A5F5),
    Color(0xFFAB47BC),
    Color(0xFF66BB6A),
    Color(0xFFFFA726),
    Color(0xFFEC407A),
    Color(0xFF26C6DA),
)

private val GraphRowHeight = 36.dp
private val LaneGap = 16.dp

@Composable
fun GitTreeSettingsPage(vm: LauncherViewModel) {
    val graph by vm.repoGitTree.collectAsState()
    val loading by vm.repoGitTreeLoading.collectAsState()
    val error by vm.repoGitTreeError.collectAsState()
    val repo = vm.preferences.getGithubRepo().trim().ifBlank { "PCML-Z/PCML" }
    var query by remember { mutableStateOf("") }
    var authorQuery by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var selectedSha by remember { mutableStateOf("") }

    val shown = remember(graph, query, authorQuery, branch) {
        val source = graph ?: return@remember null
        val filtered = source.commits.filter { commit ->
            val text = query.trim()
            val author = authorQuery.trim()
            (text.isEmpty() || commit.subject.contains(text, ignoreCase = true)
                    || commit.sha.contains(text, ignoreCase = true))
                    && (author.isEmpty() || commit.author.contains(author, ignoreCase = true))
                    && (branch.isEmpty() || commit.sources.contains(branch))
        }
        RepoCommitGraph.layout(source.defaultBranch, source.branchNames, filtered)
    }
    val selected = graph?.commits?.firstOrNull { it.sha == selectedSha }

    Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        I18n.t("settings.git_tree.source", repo, graph?.defaultBranch?.ifBlank { "—" } ?: "—"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        I18n.t("settings.git_tree.count", shown?.commits?.size ?: 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                if (loading) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { vm.refreshRepoGitTree(force = true) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = I18n.t("settings.git_tree.refresh"))
                    }
                }
            }
            if (error.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(I18n.t("settings.git_tree.query")) }
                )
                OutlinedTextField(
                    value = authorQuery,
                    onValueChange = { authorQuery = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text(I18n.t("settings.git_tree.author")) }
                )
            }
            val branches = graph?.branchNames.orEmpty()
            if (branches.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = branch.isEmpty(),
                        onClick = { branch = "" },
                        label = { Text(I18n.t("settings.git_tree.all_branches")) }
                    )
                    branches.forEach { name ->
                        FilterChip(
                            selected = branch == name,
                            onClick = { branch = if (branch == name) "" else name },
                            label = { Text(name) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (loading && graph == null && error.isBlank()) {
                Text(
                    I18n.t("settings.git_tree.loading"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Row(Modifier.fillMaxWidth().height(560.dp)) {
                CommitGraphList(
                    graph = shown,
                    selectedSha = selectedSha,
                    onSelect = { selectedSha = it },
                    modifier = Modifier.weight(1.7f).fillMaxHeight()
                )
                Box(
                    Modifier
                        .padding(horizontal = 12.dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                CommitDetail(
                    repo = repo,
                    commit = selected,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun CommitGraphList(
    graph: RepoCommitGraph.Graph?,
    selectedSha: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val commits = graph?.commits.orEmpty()
    if (graph != null && commits.isEmpty()) {
        Text(
            I18n.t("settings.git_tree.empty"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = modifier.padding(top = 8.dp)
        )
        return
    }
    val laneCount = (graph?.maxLane ?: 0) + 1
    val graphWidth = LaneGap * laneCount + 8.dp
    val dotHole = MaterialTheme.colorScheme.surface
    val shown = graph
    if (shown == null) return
    LazyColumn(modifier) {
        items(commits.size, key = { commits[it].sha }) { index ->
            CommitTextRow(shown, index, graphWidth, dotHole, commits[index].sha == selectedSha) {
                onSelect(commits[index].sha)
            }
        }
    }
}

@Composable
private fun CommitTextRow(
    graph: RepoCommitGraph.Graph,
    index: Int,
    graphWidth: androidx.compose.ui.unit.Dp,
    dotHole: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val commit = graph.commits[index]
    Row(
        Modifier
            .fillMaxWidth()
            .height(GraphRowHeight)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CommitLane(graph, index, graphWidth, dotHole)
        if (commit.branches.isNotEmpty()) {
            Text(
                commit.branches.joinToString(" "),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            commit.subject.ifBlank { commit.sha.take(7) },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            commit.author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(108.dp)
        )
        Text(
            commit.date,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            modifier = Modifier.width(118.dp)
        )
    }
}

@Composable
private fun CommitLane(
    graph: RepoCommitGraph.Graph,
    row: Int,
    width: androidx.compose.ui.unit.Dp,
    dotHole: Color
) {
    Canvas(Modifier.width(width).height(GraphRowHeight)) {
        val rowH = size.height
        val laneW = LaneGap.toPx()
        fun x(lane: Int) = 8.dp.toPx() + lane * laneW + laneW / 2f
        fun y(index: Int) = (index - row) * rowH + rowH / 2f
        graph.edges.forEach { edge ->
            if (row < edge.fromRow || row > edge.toRow) return@forEach
            val color = LaneColors[Math.floorMod(edge.fromLane, LaneColors.size)]
            val start = Offset(x(edge.fromLane), y(edge.fromRow))
            val end = Offset(x(edge.toLane), y(edge.toRow))
            if (edge.fromLane == edge.toLane) {
                drawLine(color, start, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Butt)
            } else {
                val path = Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(start.x, (start.y + end.y) / 2f, end.x, (start.y + end.y) / 2f, end.x, end.y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Butt))
            }
        }
        val commit = graph.commits[row]
        val center = Offset(x(commit.lane), rowH / 2f)
        drawCircle(LaneColors[Math.floorMod(commit.lane, LaneColors.size)], radius = 5.dp.toPx(), center = center)
        drawCircle(dotHole, radius = 2.2.dp.toPx(), center = center)
    }
}

@Composable
private fun CommitDetail(
    repo: String,
    commit: RepoCommitGraph.Commit?,
    modifier: Modifier = Modifier
) {
    var files by remember(commit?.sha) { mutableStateOf<List<RepoCommitGraph.FileChange>>(emptyList()) }
    var filesLoading by remember(commit?.sha) { mutableStateOf(false) }
    var filesError by remember(commit?.sha) { mutableStateOf("") }
    LaunchedEffect(commit?.sha) {
        val sha = commit?.sha ?: return@LaunchedEffect
        filesLoading = true
        filesError = ""
        try {
            files = withContext(Dispatchers.IO) { RepoCommitGraph.filesFor(repo, sha) }
        } catch (_: Throwable) {
            filesError = I18n.t("settings.git_tree.files_failed")
        } finally {
            filesLoading = false
        }
    }
    Column(modifier.verticalScroll(rememberScrollState())) {
        Text(
            I18n.t("settings.git_tree.detail_title"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        if (commit == null) {
            Text(
                I18n.t("settings.git_tree.detail_empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            return@Column
        }
        Text(commit.subject, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            listOf(commit.author, commit.date).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Text(
            commit.sha,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.outline
        )
        if (commit.body.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(commit.body, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            I18n.t("settings.git_tree.files"),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        when {
            filesLoading -> Text(
                I18n.t("settings.git_tree.files_loading"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            filesError.isNotBlank() -> Text(
                filesError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            files.isEmpty() -> Text(
                I18n.t("settings.git_tree.files_empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            else -> files.forEach { file ->
                Text(
                    fileStatusLabel(file.status) + "  " + file.path,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun fileStatusLabel(status: String): String = when (status) {
    "added" -> I18n.t("settings.git_tree.status_added")
    "removed" -> I18n.t("settings.git_tree.status_removed")
    "renamed" -> I18n.t("settings.git_tree.status_renamed")
    else -> I18n.t("settings.git_tree.status_modified")
}
