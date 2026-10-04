package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.pmcl.core.backup.TimeMachine
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.TypewriterTitle
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.theme.glassContainerColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.deleteTimeMachineSnapshot
import com.pmcl.ui.viewmodel.formatBackupBytes
import com.pmcl.ui.viewmodel.restoreTimeMachine
import com.pmcl.ui.viewmodel.runTimeMachineBackupIfDue
import com.pmcl.ui.viewmodel.saveTimeMachineSettings
import com.pmcl.ui.viewmodel.startTimeMachineBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val backupTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupPage(vm: LauncherViewModel) {
    val busy by vm.timeMachineBusy.collectAsState()
    val done by vm.timeMachineDone.collectAsState()
    val total by vm.timeMachineTotal.collectAsState()
    val current by vm.timeMachineCurrent.collectAsState()
    val message by vm.timeMachineMessage.collectAsState()
    val generation by vm.timeMachineGeneration.collectAsState()

    var settings by remember { mutableStateOf(TimeMachine.Settings()) }
    var snapshots by remember { mutableStateOf<List<TimeMachine.Snapshot>>(emptyList()) }
    var storedBytes by remember { mutableStateOf(0L) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var changesOnly by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<TimeMachine.Entry>>(emptyList()) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var confirmRestore by remember { mutableStateOf(false) }
    var restoreAll by remember { mutableStateOf(false) }
    var deleteExtras by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var keepText by remember { mutableStateOf("30") }

    LaunchedEffect(Unit) { vm.runTimeMachineBackupIfDue() }
    LaunchedEffect(generation) {
        val loaded = withContext(Dispatchers.IO) {
            val machine = vm.core.timeMachine()
            Triple(machine.loadSettings(), machine.listSnapshots(), machine.storedSize())
        }
        settings = loaded.first
        snapshots = loaded.second
        storedBytes = loaded.third
        keepText = loaded.first.keep.toString()
        if (snapshots.none { it.id() == selectedId }) selectedId = snapshots.firstOrNull()?.id()
    }
    LaunchedEffect(selectedId, changesOnly, generation) {
        val id = selectedId
        picked = emptySet()
        entries = if (id == null) {
            emptyList()
        } else {
            try {
                withContext(Dispatchers.IO) {
                    val machine = vm.core.timeMachine()
                    if (changesOnly) machine.changes(id) else machine.entries(id)
                }
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    val visible = entries.filter { entry ->
        val matchesChange = !changesOnly || entry.change() != "unchanged"
        val matchesQuery = query.isBlank() || entry.path().contains(query, ignoreCase = true)
                || entry.scope().contains(query, ignoreCase = true)
        matchesChange && matchesQuery
    }
    val selectedSnapshot = snapshots.firstOrNull { it.id() == selectedId }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TypewriterTitle(I18n.t("backup.title"))
        Spacer(Modifier.height(8.dp))
        Text(I18n.t("backup.hint"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        if (busy) {
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { done.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (current.isNotBlank()) {
                Text(I18n.t("backup.working", current), style = MaterialTheme.typography.labelSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
        }
        if (message.isNotBlank()) {
            Text(message, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
        }

        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.startTimeMachineBackup(false) }, enabled = !busy) {
                        Text(I18n.t("backup.now"))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(I18n.t("backup.store", formatBackupBytes(storedBytes)),
                        style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { vm.openDir(vm.core.timeMachine().storeDir().toFile()) }, enabled = !busy) {
                        Text(I18n.t("backup.open_store"))
                    }
                }
                Text(vm.core.timeMachine().storeDir().toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(I18n.t("backup.schedule"), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    scheduleChip(I18n.t("backup.schedule_manual"), settings.schedule == "manual", !busy) {
                        updateSchedule(vm, settings, "manual") { settings = it }
                    }
                    scheduleChip(I18n.t("backup.schedule_hourly"), settings.schedule == "hourly", !busy) {
                        updateSchedule(vm, settings, "hourly") { settings = it }
                    }
                    scheduleChip(I18n.t("backup.schedule_daily"), settings.schedule == "daily", !busy) {
                        updateSchedule(vm, settings, "daily") { settings = it }
                    }
                    scheduleChip(I18n.t("backup.schedule_weekly"), settings.schedule == "weekly", !busy) {
                        updateSchedule(vm, settings, "weekly") { settings = it }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keepText,
                        onValueChange = { text ->
                            val digits = text.filter { it.isDigit() }.take(3)
                            keepText = digits
                            val keep = digits.toIntOrNull() ?: return@OutlinedTextField
                            if (keep in 1..200) {
                                settings = copied(settings).also { it.keep = keep }
                                vm.saveTimeMachineSettings(settings)
                            }
                        },
                        label = { Text(I18n.t("backup.keep")) },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.width(140.dp)
                    )
                }
                Text(I18n.t("backup.scopes"), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    scopeChip(I18n.t("backup.scope_worlds"), settings.worlds, !busy) {
                        settings = copied(settings).also { it.worlds = !it.worlds }
                        vm.saveTimeMachineSettings(settings)
                    }
                    scopeChip(I18n.t("backup.scope_mods"), settings.mods, !busy) {
                        settings = copied(settings).also { it.mods = !it.mods }
                        vm.saveTimeMachineSettings(settings)
                    }
                    scopeChip(I18n.t("backup.scope_configs"), settings.configs, !busy) {
                        settings = copied(settings).also { it.configs = !it.configs }
                        vm.saveTimeMachineSettings(settings)
                    }
                    scopeChip(I18n.t("backup.scope_resourcepacks"), settings.resourcepacks, !busy) {
                        settings = copied(settings).also { it.resourcepacks = !it.resourcepacks }
                        vm.saveTimeMachineSettings(settings)
                    }
                    scopeChip(I18n.t("backup.scope_shaders"), settings.shaders, !busy) {
                        settings = copied(settings).also { it.shaders = !it.shaders }
                        vm.saveTimeMachineSettings(settings)
                    }
                    scopeChip(I18n.t("backup.scope_launcher"), settings.launcher, !busy) {
                        settings = copied(settings).also { it.launcher = !it.launcher }
                        vm.saveTimeMachineSettings(settings)
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (snapshots.isEmpty()) {
            Text(I18n.t("backup.empty"), style = MaterialTheme.typography.bodyMedium)
        } else {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    Modifier.weight(0.34f).fillMaxHeight().glassCardBorder(),
                    colors = glassCardColors(),
                    elevation = glassCardElevation()
                ) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Text(I18n.t("backup.snapshots"), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            items(snapshots, key = { it.id() }) { snap ->
                                val selected = snap.id() == selectedId
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .background(
                                            if (selected) glassContainerColor(MaterialTheme.colorScheme.secondaryContainer)
                                            else androidx.compose.ui.graphics.Color.Transparent,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable(enabled = !busy) { selectedId = snap.id() }
                                        .padding(10.dp)
                                ) {
                                    Text(backupTime.format(Instant.ofEpochMilli(snap.createdAt())),
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface)
                                    Text(snapshotLabel(snap.label()), style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        I18n.t("backup.summary", snap.files(),
                                            formatBackupBytes(snap.logicalBytes()),
                                            formatBackupBytes(snap.storedBytes())),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
                Card(
                    Modifier.weight(0.66f).fillMaxHeight().glassCardBorder(),
                    colors = glassCardColors(),
                    elevation = glassCardElevation()
                ) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconTitle()
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(
                                onClick = {
                                    restoreAll = false
                                    deleteExtras = false
                                    confirmRestore = true
                                },
                                enabled = !busy && picked.isNotEmpty()
                            ) { Text(I18n.t("backup.restore_selected")) }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    restoreAll = true
                                    deleteExtras = false
                                    confirmRestore = true
                                },
                                enabled = !busy && selectedSnapshot != null
                            ) { Text(I18n.t("backup.restore_all")) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = changesOnly,
                                onClick = { changesOnly = !changesOnly },
                                label = { Text(I18n.t("backup.changes_only")) }
                            )
                            Spacer(Modifier.width(8.dp))
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                label = { Text(I18n.t("backup.search")) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (visible.isEmpty()) {
                            Text(
                                if (changesOnly && entries.any { it.change() == "unchanged" }) I18n.t("backup.unchanged")
                                else I18n.t("backup.no_files"),
                                color = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                items(visible, key = { entryKey(it) }) { entry ->
                                    val key = entryKey(entry)
                                    val on = key in picked
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                picked = if (on) picked - key else picked + key
                                            }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(checked = on, onCheckedChange = {
                                            picked = if (it) picked + key else picked - key
                                        })
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.path(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                scopeLabel(entry.scope()) + " · " + changeLabel(entry.change())
                                                        + " · " + formatBackupBytes(entry.size()),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        TextButton(
                            onClick = { confirmDelete = true },
                            enabled = !busy && selectedSnapshot != null
                        ) {
                            Text(I18n.t("backup.delete"))
                        }
                    }
                }
            }
        }
    }

    if (confirmRestore && selectedId != null) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(I18n.t("backup.restore_title")) },
            text = {
                Column {
                    Text(I18n.t("backup.restore_body"))
                    if (restoreAll) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteExtras, onCheckedChange = { deleteExtras = it })
                            Text(I18n.t("backup.delete_extras"))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = selectedId
                    confirmRestore = false
                    if (id != null) {
                        val chosen = if (restoreAll) null else entries.filter { entryKey(it) in picked }
                        vm.restoreTimeMachine(id, chosen, restoreAll && deleteExtras)
                    }
                }) { Text(I18n.t("common.confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
    if (confirmDelete && selectedId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(I18n.t("backup.delete_title")) },
            text = { Text(I18n.t("backup.delete_body")) },
            confirmButton = {
                TextButton(onClick = {
                    val id = selectedId
                    confirmDelete = false
                    if (id != null) vm.deleteTimeMachineSnapshot(id)
                }) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
}

@Composable
private fun scheduleChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) })
}

@Composable
private fun scopeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, enabled = enabled, label = { Text(label) })
}

@Composable
private fun IconTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(Icons.Filled.History, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(I18n.t("backup.files"), fontWeight = FontWeight.SemiBold)
    }
}

private fun updateSchedule(
    vm: LauncherViewModel,
    settings: TimeMachine.Settings,
    schedule: String,
    publish: (TimeMachine.Settings) -> Unit,
) {
    val next = copied(settings).also { it.schedule = schedule }
    publish(next)
    vm.saveTimeMachineSettings(next)
}

private fun copied(settings: TimeMachine.Settings): TimeMachine.Settings {
    val next = TimeMachine.Settings()
    next.schedule = settings.schedule
    next.keep = settings.keep
    next.worlds = settings.worlds
    next.mods = settings.mods
    next.configs = settings.configs
    next.resourcepacks = settings.resourcepacks
    next.shaders = settings.shaders
    next.launcher = settings.launcher
    return next
}

private fun entryKey(entry: TimeMachine.Entry): String = entry.root() + "\n" + entry.path()

private fun snapshotLabel(code: String): String = when (code) {
    "auto" -> I18n.t("backup.label_auto")
    "before-restore" -> I18n.t("backup.label_before")
    else -> I18n.t("backup.label_now")
}

private fun scopeLabel(scope: String): String = when (scope) {
    "worlds" -> I18n.t("backup.scope_worlds")
    "mods" -> I18n.t("backup.scope_mods")
    "configs" -> I18n.t("backup.scope_configs")
    "resourcepacks" -> I18n.t("backup.scope_resourcepacks")
    "shaders" -> I18n.t("backup.scope_shaders")
    "launcher" -> I18n.t("backup.scope_launcher")
    else -> scope
}

private fun changeLabel(change: String): String = when (change) {
    "added" -> I18n.t("backup.change_added")
    "modified" -> I18n.t("backup.change_modified")
    "removed" -> I18n.t("backup.change_removed")
    "unchanged" -> I18n.t("backup.change_unchanged")
    else -> ""
}
