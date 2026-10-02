package com.pmcl.ui.page

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.TypewriterTitle
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.ManagedProjection
import com.pmcl.ui.viewmodel.deleteProjection
import com.pmcl.ui.viewmodel.importProjection
import com.pmcl.ui.viewmodel.openProjectionsDir
import com.pmcl.ui.viewmodel.refreshProjections
import com.pmcl.ui.viewmodel.revealProjection
import com.pmcl.ui.widget.PmclLazyColumn
import java.awt.FileDialog
import java.awt.Frame
import java.text.SimpleDateFormat
import java.util.Date

@Composable
fun ProjectionsPage(vm: LauncherViewModel) {
    val rows by vm.projections.collectAsState()
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<ManagedProjection?>(null) }
    val clock = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }

    LaunchedEffect(Unit) { vm.refreshProjections() }

    val filtered = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.fileName.contains(query, ignoreCase = true) ||
                it.source.contains(query, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypewriterTitle(
                I18n.t("content.projection.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { vm.openProjectionsDir() }) {
                Text(I18n.t("common.open_dir"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                val fd = FileDialog(null as Frame?, I18n.t("common.import"), FileDialog.LOAD)
                fd.filenameFilter = java.io.FilenameFilter { _, name: String? ->
                    val lower = name?.lowercase(java.util.Locale.ROOT).orEmpty()
                    lower.endsWith(".litematic") || lower.endsWith(".schem") ||
                        lower.endsWith(".schematic") || lower.endsWith(".nbt")
                }
                fd.isVisible = true
                if (fd.file != null) {
                    vm.importProjection(java.io.File(fd.directory, fd.file).absolutePath)
                }
            }) {
                Icon(Icons.Filled.Download, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.import"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = vm::refreshProjections) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("common.refresh"))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            I18n.t("content.projection.hint"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(I18n.t("content.projection.search")) },
            leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            I18n.t("content.projection.count", rows.size),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty()) {
            Surface(
                color = glassSurfaceVariantColor(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (rows.isEmpty()) I18n.t("content.projection.empty")
                        else I18n.t("content.projection.no_match"),
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            PmclLazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filtered.size, key = { filtered[it].path }) { index ->
                    val row = filtered[index]
                    ProjectionCard(row, clock.format(Date(row.modified)), onOpen = { vm.revealProjection(row) }) {
                        pendingDelete = row
                    }
                }
            }
        }
    }

    val deleting = pendingDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(I18n.t("content.projection.delete_title")) },
            text = { Text(I18n.t("content.projection.delete_body", deleting.title)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProjection(deleting)
                    pendingDelete = null
                }) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
}

@Composable
private fun ProjectionCard(
    row: ManagedProjection,
    modified: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().glassCardBorder(),
        shape = RoundedCornerShape(12.dp),
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                Modifier.weight(1f).clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpen
                )
            ) {
                Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    row.summary + " · " + modified,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    I18n.t("content.projection.source", row.source) + " · " + row.fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = I18n.t("common.delete"))
            }
        }
    }
}
