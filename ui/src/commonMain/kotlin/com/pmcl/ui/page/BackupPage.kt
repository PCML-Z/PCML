package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.backup.TimeMachine
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.AnimatedSegmentedSelector
import com.pmcl.ui.animation.TypewriterTitle
import com.pmcl.ui.navigation.PmclIcons
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
import com.pmcl.ui.viewmodel.setTimeMachineNote
import com.pmcl.ui.viewmodel.setTimeMachinePinned
import com.pmcl.ui.viewmodel.startTimeMachineBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val backupTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
private val backupClock = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val backupDay = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

private val scopeOrder = listOf(
    "worlds", "mods", "configs", "resourcepacks", "shaders", "screenshots", "skins", "launcher"
)

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
    var focusChange by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<TimeMachine.Entry>>(emptyList()) }
    var filesReady by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var confirmRestore by remember { mutableStateOf(false) }
    var restoreAll by remember { mutableStateOf(false) }
    var sliceRestore by remember { mutableStateOf<List<TimeMachine.Entry>?>(null) }
    var sliceLabel by remember { mutableStateOf("") }
    var deleteExtras by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var nextAt by remember { mutableStateOf(0L) }
    var noteText by remember { mutableStateOf("") }
    var settingsOpen by remember { mutableStateOf(false) }
    var historyEntry by remember { mutableStateOf<TimeMachine.Entry?>(null) }
    var revisions by remember { mutableStateOf<List<TimeMachine.Revision>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val now = remember(generation) { System.currentTimeMillis() }

    LaunchedEffect(Unit) { vm.runTimeMachineBackupIfDue() }
    LaunchedEffect(generation) {
        val loaded = withContext(Dispatchers.IO) {
            val machine = vm.core.timeMachine()
            val currentSettings = machine.loadSettings()
            BackupLoad(
                currentSettings,
                machine.listSnapshots(),
                machine.storedSize(),
                machine.nextBackupAt(currentSettings, System.currentTimeMillis())
            )
        }
        settings = loaded.settings
        snapshots = loaded.snapshots
        storedBytes = loaded.stored
        nextAt = loaded.nextAt
        if (snapshots.none { it.id() == selectedId }) selectedId = snapshots.firstOrNull()?.id()
    }
    LaunchedEffect(selectedId, snapshots) {
        noteText = snapshots.firstOrNull { it.id() == selectedId }?.note().orEmpty()
    }
    LaunchedEffect(selectedId) { focusChange = null }
    LaunchedEffect(selectedId, changesOnly, generation) {
        val id = selectedId
        picked = emptySet()
        filesReady = false
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
        filesReady = true
    }

    val visible = entries.filter { entry ->
        val matchesChange = !changesOnly || entry.change() != "unchanged"
        val matchesFocus = focusChange == null || entry.change() == focusChange
        val matchesQuery = query.isBlank() || entry.path().contains(query, ignoreCase = true)
                || entry.scope().contains(query, ignoreCase = true)
                || fileName(entry.path()).contains(query, ignoreCase = true)
        matchesChange && matchesFocus && matchesQuery
    }
    val selectedSnapshot = snapshots.firstOrNull { it.id() == selectedId }
    val added = entries.count { it.change() == "added" }
    val modified = entries.count { it.change() == "modified" }
    val removed = entries.count { it.change() == "removed" }
    val newest = snapshots.firstOrNull()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TypewriterTitle(I18n.t("backup.title"))
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (newest == null) I18n.t("backup.latest") else latestCaption(newest, now),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (newest == null) I18n.t("backup.last_none") else latestHeadline(newest, now),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            statusLine(settings, storedBytes, nextAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Button(onClick = { vm.startTimeMachineBackup(false) }, enabled = !busy) {
                        Icon(Icons.Filled.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(I18n.t("backup.now"))
                    }
                    IconButton(
                        onClick = { vm.openDir(vm.core.timeMachine().storeDir().toFile()) },
                        enabled = !busy
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = I18n.t("backup.open_store"))
                    }
                }
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
                        Text(
                            I18n.t("backup.working", current),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (message.isNotBlank()) {
                    Text(message, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { settingsOpen = !settingsOpen }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(I18n.t("backup.settings"), fontWeight = FontWeight.SemiBold)
                        if (!settingsOpen) {
                            Text(
                                planSummary(settings),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Icon(
                        if (settingsOpen) Icons.Filled.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (settingsOpen) {
                    BackupPlan(
                        settings = settings,
                        busy = busy,
                        onSchedule = { id -> updateSchedule(vm, settings, id) { settings = it } },
                        onKeep = { keep ->
                            settings = copied(settings).also { it.keep = keep }
                            vm.saveTimeMachineSettings(settings)
                        },
                        onToggle = { scope ->
                            settings = copied(settings).also { toggleScope(it, scope) }
                            vm.saveTimeMachineSettings(settings)
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (snapshots.isEmpty()) {
            Card(
                Modifier.weight(1f).fillMaxWidth().glassCardBorder(),
                colors = glassCardColors(),
                elevation = glassCardElevation()
            ) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.History,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(I18n.t("backup.empty"), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    Modifier.weight(0.36f).fillMaxHeight().glassCardBorder(),
                    colors = glassCardColors(),
                    elevation = glassCardElevation()
                ) {
                    Column(Modifier.fillMaxSize().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(I18n.t("backup.snapshots"), fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(
                                I18n.t("backup.snapshot_count", snapshots.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(Modifier.weight(1f)) {
                            timeline(snapshots, now).forEach { row ->
                                when (row) {
                                    is TimelineRow.Day -> item(key = "day-${row.label}") {
                                        Text(
                                            row.label,
                                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    is TimelineRow.Point -> item(key = row.snap.id()) {
                                        SnapshotPoint(
                                            snap = row.snap,
                                            selected = row.snap.id() == selectedId,
                                            enabled = !busy,
                                            onClick = { selectedId = row.snap.id() }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Card(
                    Modifier.weight(0.64f).fillMaxHeight().glassCardBorder(),
                    colors = glassCardColors(),
                    elevation = glassCardElevation()
                ) {
                    if (selectedSnapshot == null) {
                        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(I18n.t("backup.pick_time"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        SnapshotDetail(
                            snap = selectedSnapshot,
                            busy = busy,
                            noteText = noteText,
                            onNote = { noteText = it.replace('\n', ' ').take(40) },
                            onSaveNote = { vm.setTimeMachineNote(selectedSnapshot.id(), noteText) },
                            onPin = { vm.setTimeMachinePinned(selectedSnapshot.id(), !selectedSnapshot.pinned()) },
                            onDelete = { confirmDelete = true },
                            filesReady = filesReady,
                            changesOnly = changesOnly,
                            onChangesOnly = { changesOnly = !changesOnly },
                            added = added,
                            modified = modified,
                            removed = removed,
                            focusChange = focusChange,
                            onFocus = { focusChange = if (focusChange == it) null else it },
                            query = query,
                            onQuery = { query = it },
                            visible = visible,
                            picked = picked,
                            onPicked = { picked = it },
                            onHistory = { entry ->
                                scope.launch {
                                    try {
                                        revisions = withContext(Dispatchers.IO) {
                                            vm.core.timeMachine().history(entry.root(), entry.path())
                                        }
                                    } catch (_: Throwable) {
                                        revisions = emptyList()
                                    }
                                    historyEntry = entry
                                }
                            },
                            onRestoreSelected = {
                                sliceRestore = null
                                restoreAll = false
                                deleteExtras = false
                                confirmRestore = true
                            },
                            onRestoreAll = {
                                sliceRestore = null
                                restoreAll = true
                                deleteExtras = false
                                confirmRestore = true
                            },
                            onRestoreSlice = { root, world, part, folder ->
                                val id = selectedSnapshot.id()
                                scope.launch {
                                    val all = try {
                                        withContext(Dispatchers.IO) { vm.core.timeMachine().entries(id) }
                                    } catch (_: Throwable) {
                                        emptyList()
                                    }
                                    val chosen = sliceEntries(all, root, world, part, folder)
                                    if (chosen.isEmpty()) {
                                        vm.timeMachineMessage.value = I18n.t("backup.slice_empty")
                                        return@launch
                                    }
                                    sliceRestore = chosen
                                    sliceLabel = sliceTitle(world, part, folder)
                                    restoreAll = false
                                    deleteExtras = false
                                    confirmRestore = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (confirmRestore && selectedId != null) {
        AlertDialog(
            onDismissRequest = {
                confirmRestore = false
                sliceRestore = null
            },
            title = { Text(I18n.t("backup.restore_title")) },
            text = {
                Column {
                    val snap = selectedSnapshot
                    if (snap != null) {
                        Text(backupTime.format(Instant.ofEpochMilli(snap.createdAt())), fontWeight = FontWeight.SemiBold)
                        if (snap.note().isNotBlank()) {
                            Text(snap.note(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    val slice = sliceRestore
                    if (slice != null) {
                        Text(sliceLabel, fontWeight = FontWeight.Medium)
                        Text(I18n.t("backup.slice_count", slice.size))
                        Spacer(Modifier.height(8.dp))
                    }
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
                    val slice = sliceRestore
                    confirmRestore = false
                    sliceRestore = null
                    if (id != null) {
                        val chosen = when {
                            restoreAll -> null
                            slice != null -> slice
                            else -> entries.filter { entryKey(it) in picked }
                        }
                        vm.restoreTimeMachine(id, chosen, restoreAll && deleteExtras)
                    }
                }) { Text(I18n.t("common.confirm")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    sliceRestore = null
                }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
    if (confirmDelete && selectedId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(I18n.t("backup.delete_title")) },
            text = {
                Column {
                    val snap = selectedSnapshot
                    if (snap != null) {
                        Text(backupTime.format(Instant.ofEpochMilli(snap.createdAt())), fontWeight = FontWeight.SemiBold)
                        if (snap.note().isNotBlank()) Text(snap.note())
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(I18n.t("backup.delete_body"))
                }
            },
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
    val browsing = historyEntry
    if (browsing != null) {
        AlertDialog(
            onDismissRequest = { historyEntry = null },
            title = { Text(I18n.t("backup.history_title")) },
            text = {
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                    Text(fileName(browsing.path()), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val parent = fileParent(browsing.path())
                    if (parent.isNotBlank()) {
                        Text(parent, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(8.dp))
                    if (revisions.isEmpty()) {
                        Text(I18n.t("backup.history_empty"), color = MaterialTheme.colorScheme.outline)
                    }
                    revisions.forEach { rev ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(backupTime.format(Instant.ofEpochMilli(rev.createdAt())))
                                if (rev.note().isNotBlank()) {
                                    Text(rev.note(), style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            ChangeBadge(rev.change())
                            val file = rev.entry()
                            if (file != null) {
                                TextButton(
                                    onClick = {
                                        historyEntry = null
                                        vm.restoreTimeMachine(rev.snapshotId(), listOf(file), false)
                                    },
                                    enabled = !busy
                                ) { Text(I18n.t("backup.restore_version")) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { historyEntry = null }) { Text(I18n.t("common.close")) }
            }
        )
    }
}

@Composable
private fun SnapshotPoint(
    snap: TimeMachine.Snapshot,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = when {
        selected -> MaterialTheme.colorScheme.primary
        snap.pinned() -> MaterialTheme.colorScheme.tertiary
        snap.label() == "before-restore" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) glassContainerColor(MaterialTheme.colorScheme.secondaryContainer)
                else Color.Transparent
            )
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    backupClock.format(Instant.ofEpochMilli(snap.createdAt())),
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    snapshotLabel(snap.label()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (snap.pinned()) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = I18n.t("backup.pinned"),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
            if (snap.note().isNotBlank()) {
                Text(snap.note(), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                I18n.t("backup.files_n", snap.files()) + " · " + formatBackupBytes(snap.logicalBytes()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnapshotDetail(
    snap: TimeMachine.Snapshot,
    busy: Boolean,
    noteText: String,
    onNote: (String) -> Unit,
    onSaveNote: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    filesReady: Boolean,
    changesOnly: Boolean,
    onChangesOnly: () -> Unit,
    added: Int,
    modified: Int,
    removed: Int,
    focusChange: String?,
    onFocus: (String) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    visible: List<TimeMachine.Entry>,
    picked: Set<String>,
    onPicked: (Set<String>) -> Unit,
    onHistory: (TimeMachine.Entry) -> Unit,
    onRestoreSelected: () -> Unit,
    onRestoreAll: () -> Unit,
    onRestoreSlice: (String, String, String, String) -> Unit,
) {
    val noteDirty = noteText != snap.note()
    var saveBrowse by remember(snap.id()) { mutableStateOf<SaveBrowse?>(null) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    backupTime.format(Instant.ofEpochMilli(snap.createdAt())),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    snapshotLabel(snap.label()) + if (snap.pinned()) " · " + I18n.t("backup.pinned") else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onPin, enabled = !busy) {
                Icon(
                    if (snap.pinned()) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (snap.pinned()) I18n.t("backup.unpin") else I18n.t("backup.pin"),
                    tint = if (snap.pinned()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = I18n.t("backup.delete"),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = noteText,
                onValueChange = onNote,
                placeholder = { Text(I18n.t("backup.note_hint"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                label = { Text(I18n.t("backup.note")) },
                singleLine = true,
                enabled = !busy,
                modifier = Modifier.weight(1f)
            )
            if (noteDirty) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onSaveNote, enabled = !busy) { Text(I18n.t("backup.note_save")) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCell(I18n.t("backup.stat_files"), snap.files().toString(), Modifier.weight(1f))
            StatCell(I18n.t("backup.stat_size"), formatBackupBytes(snap.logicalBytes()), Modifier.weight(1f))
            StatCell(I18n.t("backup.stat_new"), formatBackupBytes(snap.storedBytes()), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = changesOnly,
                onClick = onChangesOnly,
                label = { Text(I18n.t("backup.changes_only")) }
            )
            ChangeFilter("added", added, focusChange == "added", onFocus)
            ChangeFilter("modified", modified, focusChange == "modified", onFocus)
            ChangeFilter("removed", removed, focusChange == "removed", onFocus)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text(I18n.t("backup.search")) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        SaveBrowser(
            visible = visible,
            filesReady = filesReady,
            changesOnly = changesOnly,
            unchanged = entriesLookUnchanged(added, modified, removed) && focusChange == null,
            busy = busy,
            picked = picked,
            onPicked = onPicked,
            browse = saveBrowse,
            onBrowse = { saveBrowse = it },
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = {
                    val entry = visible.firstOrNull { entryKey(it) in picked } ?: return@TextButton
                    onHistory(entry)
                },
                enabled = !busy && picked.count { key -> visible.any { entryKey(it) == key } } == 1
            ) { Text(I18n.t("backup.history")) }
            val place = saveBrowse
            if (place != null) {
                TextButton(
                    onClick = { onRestoreSlice(place.root, place.world, place.part, place.folder) },
                    enabled = !busy
                ) {
                    Text(
                        if (place.part.isBlank() && place.folder.isBlank()) I18n.t("backup.restore_world")
                        else I18n.t("backup.restore_part")
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onRestoreSelected, enabled = !busy && picked.isNotEmpty()) {
                Text(if (picked.isEmpty()) I18n.t("backup.restore_selected") else I18n.t("backup.restore_n", picked.size))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onRestoreAll, enabled = !busy) { Text(I18n.t("backup.restore_all")) }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(glassContainerColor(MaterialTheme.colorScheme.surfaceVariant))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChangeFilter(change: String, count: Int, selected: Boolean, onFocus: (String) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onFocus(change) },
        enabled = count > 0 || selected,
        label = { Text(changeLabel(change) + " " + count) }
    )
}

@Composable
private fun ScopeHeader(
    scope: String,
    count: Int,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
    countLabel: String = count.toString(),
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        Text(scopeLabel(scope), fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(countLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun FileLine(entry: TimeMachine.Entry, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChecked(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Column(Modifier.weight(1f)) {
            Text(fileName(entry.path()), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parent = fileParent(entry.path())
            val where = if (parent.isBlank()) scopeLabel(entry.scope()) else scopeLabel(entry.scope()) + " · " + parent
            Text(where, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            formatBackupBytes(entry.size()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        ChangeBadge(entry.change())
    }
}

@Composable
private fun ChangeBadge(change: String) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (change) {
        "added" -> colors.primaryContainer to colors.onPrimaryContainer
        "modified" -> colors.tertiaryContainer to colors.onTertiaryContainer
        "removed" -> colors.errorContainer to colors.onErrorContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Text(
        changeLabel(change),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = foreground
    )
}

@Composable
private fun BackupPlan(
    settings: TimeMachine.Settings,
    busy: Boolean,
    onSchedule: (String) -> Unit,
    onKeep: (Int) -> Unit,
    onToggle: (String) -> Unit,
) {
    val schedules = listOf("manual", "hourly", "daily", "weekly")
    val labels = listOf(
        I18n.t("backup.schedule_manual"),
        I18n.t("backup.schedule_hourly"),
        I18n.t("backup.schedule_daily"),
        I18n.t("backup.schedule_weekly")
    )
    Text(I18n.t("backup.schedule"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    AnimatedSegmentedSelector(
        items = labels,
        selectedIndex = schedules.indexOf(settings.schedule).coerceAtLeast(0),
        onSelect = { index -> if (!busy) onSchedule(schedules[index]) },
        modifier = Modifier.fillMaxWidth(),
        fillWidth = true,
        height = 40.dp
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(glassContainerColor(MaterialTheme.colorScheme.surfaceVariant))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(I18n.t("backup.keep"), fontWeight = FontWeight.Medium)
            Text(
                I18n.t("backup.keep_desc"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        KeepStepper(value = settings.keep, enabled = !busy, onChange = onKeep)
    }
    Text(I18n.t("backup.scopes"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
    val tiles = listOf(
        ScopeTileSpec("worlds", I18n.t("backup.scope_worlds"), PmclIcons.Globe, settings.worlds),
        ScopeTileSpec("mods", I18n.t("backup.scope_mods"), PmclIcons.Plugins, settings.mods),
        ScopeTileSpec("configs", I18n.t("backup.scope_configs"), PmclIcons.Edit, settings.configs),
        ScopeTileSpec("resourcepacks", I18n.t("backup.scope_resourcepacks"), PmclIcons.Palette, settings.resourcepacks),
        ScopeTileSpec("shaders", I18n.t("backup.scope_shaders"), PmclIcons.Sun, settings.shaders),
        ScopeTileSpec("screenshots", I18n.t("backup.scope_screenshots"), PmclIcons.Image, settings.screenshots),
        ScopeTileSpec("skins", I18n.t("backup.scope_skins"), PmclIcons.Accounts, settings.skins)
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { tile ->
                    ScopeTile(tile, enabled = !busy, onClick = { onToggle(tile.id) }, modifier = Modifier.weight(1f))
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                glassContainerColor(
                    if (settings.launcher) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
            )
            .clickable(enabled = !busy) { onToggle("launcher") }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            PmclIcons.Settings,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = if (settings.launcher) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(I18n.t("backup.scope_launcher"), fontWeight = FontWeight.Medium)
            Text(
                I18n.t("backup.launcher_note"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = settings.launcher, onCheckedChange = null, enabled = !busy)
    }
    Text(
        I18n.t("backup.safety"),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline
    )
}

@Composable
private fun KeepStepper(value: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepperButton(Icons.Filled.Remove, enabled && value > 1) { onChange(value - 1) }
        Text(
            value.toString(),
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold
        )
        StepperButton(Icons.Filled.Add, enabled && value < 200) { onChange(value + 1) }
    }
}

@Composable
private fun StepperButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (enabled) glassContainerColor(MaterialTheme.colorScheme.surfaceVariant) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
private fun ScopeTile(
    tile: ScopeTileSpec,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (tile.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val tint = if (tile.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .height(84.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(glassContainerColor(background))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(tile.icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = tint)
        Spacer(Modifier.height(8.dp))
        Text(
            tile.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (tile.selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (tile.selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class ScopeTileSpec(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val selected: Boolean,
)

private fun toggleScope(settings: TimeMachine.Settings, scope: String) {
    when (scope) {
        "worlds" -> settings.worlds = !settings.worlds
        "mods" -> settings.mods = !settings.mods
        "configs" -> settings.configs = !settings.configs
        "resourcepacks" -> settings.resourcepacks = !settings.resourcepacks
        "shaders" -> settings.shaders = !settings.shaders
        "screenshots" -> settings.screenshots = !settings.screenshots
        "skins" -> settings.skins = !settings.skins
        "launcher" -> settings.launcher = !settings.launcher
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
    next.screenshots = settings.screenshots
    next.skins = settings.skins
    next.launcher = settings.launcher
    return next
}

private data class BackupLoad(
    val settings: TimeMachine.Settings,
    val snapshots: List<TimeMachine.Snapshot>,
    val stored: Long,
    val nextAt: Long,
)

private sealed interface TimelineRow {
    data class Day(val label: String) : TimelineRow
    data class Point(val snap: TimeMachine.Snapshot) : TimelineRow
}

private data class FileGroup(val scope: String, val entries: List<TimeMachine.Entry>)

private fun timeline(snapshots: List<TimeMachine.Snapshot>, now: Long): List<TimelineRow> {
    val rows = ArrayList<TimelineRow>()
    var day = ""
    for (snap in snapshots) {
        val label = dayHeading(snap.createdAt(), now)
        if (label != day) {
            day = label
            rows.add(TimelineRow.Day(label))
        }
        rows.add(TimelineRow.Point(snap))
    }
    return rows
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaveBrowser(
    visible: List<TimeMachine.Entry>,
    filesReady: Boolean,
    changesOnly: Boolean,
    unchanged: Boolean,
    busy: Boolean,
    picked: Set<String>,
    onPicked: (Set<String>) -> Unit,
    browse: SaveBrowse?,
    onBrowse: (SaveBrowse?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val place = browse
    if (!filesReady || (place == null && visible.isEmpty())) {
        Text(
            when {
                !filesReady -> ""
                changesOnly && unchanged -> I18n.t("backup.unchanged")
                else -> I18n.t("backup.no_files")
            },
            modifier = modifier,
            color = MaterialTheme.colorScheme.outline
        )
        return
    }
    Column(modifier) {
        if (place != null) {
            SaveCrumbs(place, onBrowse)
        }
        LazyColumn(Modifier.weight(1f)) {
            if (place == null) {
                fileGroups(visible).forEach { group ->
                    val worlds = if (group.scope == "worlds") saveWorlds(group.entries) else emptyList()
                    val loose = if (group.scope == "worlds") {
                        group.entries.filter { worldName(it.path()) == null }
                    } else {
                        group.entries
                    }
                    item(key = "scope-${group.scope}") {
                        ScopeHeader(
                            scope = group.scope,
                            count = group.entries.size,
                            checked = group.entries.isNotEmpty() && group.entries.all { entryKey(it) in picked },
                            enabled = !busy && group.entries.isNotEmpty(),
                            onChecked = { on -> toggleEntries(group.entries, on, picked, onPicked) },
                            countLabel = if (worlds.isNotEmpty()) I18n.t("backup.world_count", worlds.size) else group.entries.size.toString()
                        )
                    }
                    worlds.forEach { world ->
                        item(key = "world\n${world.root}\n${world.name}") {
                            SaveCard(
                                title = world.name,
                                meta = gameLabel(world.root) + " · " + entryMeta(world.entries),
                                icon = PmclIcons.Globe,
                                checked = world.entries.all { entryKey(it) in picked },
                                enabled = !busy,
                                onChecked = { on -> toggleEntries(world.entries, on, picked, onPicked) },
                                onOpen = { onBrowse(SaveBrowse(world.root, world.name)) }
                            )
                        }
                    }
                    items(loose, key = { entryKey(it) }) { entry ->
                        FileLine(
                            entry = entry,
                            checked = entryKey(entry) in picked,
                            onChecked = { on ->
                                val key = entryKey(entry)
                                onPicked(if (on) picked + key else picked - key)
                            }
                        )
                    }
                }
            } else {
                val worldEntries = visible.filter { inWorld(it, place.root, place.world) }
                if (place.part.isBlank()) {
                    val parts = partOrder.filter { part ->
                        worldEntries.any { partId(restPath(it.path(), place.world)) == part }
                    }
                    if (parts.isEmpty()) {
                        item(key = "empty-world-${place.world}") {
                            Text(
                                I18n.t("backup.no_files"),
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    parts.forEach { part ->
                        val list = worldEntries.filter { partId(restPath(it.path(), place.world)) == part }
                        if (list.isNotEmpty()) {
                            item(key = "part-${place.world}-$part") {
                                SaveCard(
                                    title = partLabel(part),
                                    meta = entryMeta(list),
                                    icon = partIcon(part),
                                    checked = list.all { entryKey(it) in picked },
                                    enabled = !busy,
                                    onChecked = { on -> toggleEntries(list, on, picked, onPicked) },
                                    onOpen = { onBrowse(place.copy(part = part, folder = "")) }
                                )
                            }
                        }
                    }
                } else {
                    val here = sliceEntries(visible, place.root, place.world, place.part, place.folder)
                    val nodes = saveNodes(here) { entry ->
                        var rel = partRelative(entry.path(), place.world, place.part)
                        if (place.folder.isNotBlank()) {
                            val prefix = place.folder.trim('/') + "/"
                            if (rel.startsWith(prefix)) rel = rel.removePrefix(prefix)
                        }
                        rel
                    }
                    if (nodes.isEmpty()) {
                        item(key = "empty-${place.world}-${place.part}-${place.folder}") {
                            Text(I18n.t("backup.no_files"), color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(12.dp))
                        }
                    }
                    nodes.forEach { node ->
                        when (node) {
                            is SaveNode.Folder -> item(key = "dir-${place.part}-${place.folder}-${node.name}") {
                                val next = if (place.folder.isBlank()) node.name else place.folder + "/" + node.name
                                SaveCard(
                                    title = node.name,
                                    meta = entryMeta(node.entries),
                                    icon = Icons.Filled.Folder,
                                    checked = node.entries.all { entryKey(it) in picked },
                                    enabled = !busy,
                                    onChecked = { on -> toggleEntries(node.entries, on, picked, onPicked) },
                                    onOpen = { onBrowse(place.copy(folder = next)) }
                                )
                            }
                            is SaveNode.File -> item(key = entryKey(node.entry)) {
                                FileLine(
                                    entry = node.entry,
                                    checked = entryKey(node.entry) in picked,
                                    onChecked = { on ->
                                        val key = entryKey(node.entry)
                                        onPicked(if (on) picked + key else picked - key)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SaveCrumbs(browse: SaveBrowse, onBrowse: (SaveBrowse?) -> Unit) {
    FlowRow(verticalArrangement = Arrangement.Center) {
        TextButton(onClick = { onBrowse(null) }) { Text(I18n.t("backup.all_files")) }
        Text("/", color = MaterialTheme.colorScheme.outline)
        TextButton(onClick = { onBrowse(browse.copy(part = "", folder = "")) }) { Text(browse.world) }
        if (browse.part.isNotBlank()) {
            Text("/", color = MaterialTheme.colorScheme.outline)
            TextButton(onClick = { onBrowse(browse.copy(folder = "")) }) { Text(partLabel(browse.part)) }
        }
        if (browse.folder.isNotBlank()) {
            val bits = browse.folder.split('/')
            bits.forEachIndexed { index, name ->
                val prefix = bits.take(index + 1).joinToString("/")
                Text("/", color = MaterialTheme.colorScheme.outline)
                if (index == bits.lastIndex) {
                    Text(
                        name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    TextButton(onClick = { onBrowse(browse.copy(folder = prefix)) }) { Text(name) }
                }
            }
        }
    }
}

@Composable
private fun SaveCard(
    title: String,
    meta: String,
    icon: ImageVector,
    checked: Boolean,
    enabled: Boolean,
    onChecked: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled, onClick = onOpen)
                .padding(vertical = 4.dp)
        ) {
            Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onOpen, enabled = enabled) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

private fun toggleEntries(
    entries: List<TimeMachine.Entry>,
    on: Boolean,
    picked: Set<String>,
    onPicked: (Set<String>) -> Unit,
) {
    val keys = entries.map { entryKey(it) }
    onPicked(if (on) picked + keys else picked - keys.toSet())
}

private data class SaveBrowse(
    val root: String,
    val world: String,
    val part: String = "",
    val folder: String = "",
)

private data class SaveWorld(
    val root: String,
    val name: String,
    val entries: List<TimeMachine.Entry>,
)

private sealed interface SaveNode {
    data class Folder(val name: String, val entries: List<TimeMachine.Entry>) : SaveNode
    data class File(val entry: TimeMachine.Entry) : SaveNode
}

private val partOrder = listOf(
    "level", "region", "nether", "end", "dimensions", "players", "maps", "entities", "datapacks", "other"
)

private fun saveWorlds(entries: List<TimeMachine.Entry>): List<SaveWorld> {
    val grouped = LinkedHashMap<String, MutableList<TimeMachine.Entry>>()
    val identity = LinkedHashMap<String, Pair<String, String>>()
    for (entry in entries) {
        val name = worldName(entry.path()) ?: continue
        val root = normRoot(entry.root())
        val key = root + "\n" + name
        grouped.getOrPut(key) { mutableListOf() }.add(entry)
        identity[key] = root to name
    }
    return identity.map { (key, pair) -> SaveWorld(pair.first, pair.second, grouped[key].orEmpty()) }
        .sortedBy { it.name.lowercase() }
}

private fun saveNodes(entries: List<TimeMachine.Entry>, relative: (TimeMachine.Entry) -> String): List<SaveNode> {
    val folders = LinkedHashMap<String, MutableList<TimeMachine.Entry>>()
    val files = mutableListOf<TimeMachine.Entry>()
    for (entry in entries) {
        val rel = relative(entry)
        val slash = rel.indexOf('/')
        if (slash < 0) files.add(entry) else folders.getOrPut(rel.substring(0, slash)) { mutableListOf() }.add(entry)
    }
    val out = ArrayList<SaveNode>()
    folders.keys.sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { name ->
        out.add(SaveNode.Folder(name, folders[name].orEmpty()))
    }
    files.sortedBy { fileName(it.path()).lowercase() }.forEach { out.add(SaveNode.File(it)) }
    return out
}

private fun sliceEntries(
    entries: List<TimeMachine.Entry>,
    root: String,
    world: String,
    part: String,
    folder: String,
): List<TimeMachine.Entry> {
    return entries.filter { entry ->
        if (!inWorld(entry, root, world)) return@filter false
        if (part.isNotBlank() && partId(restPath(entry.path(), world)) != part) return@filter false
        if (folder.isNotBlank()) {
            val rel = if (part.isBlank()) restPath(entry.path(), world) else partRelative(entry.path(), world, part)
            val prefix = folder.trim('/') + "/"
            if (!rel.startsWith(prefix)) return@filter false
        }
        true
    }
}

private fun inWorld(entry: TimeMachine.Entry, root: String, world: String): Boolean {
    return entry.scope() == "worlds" && sameRoot(entry.root(), root) && worldName(entry.path()) == world
}

private fun worldName(path: String): String? {
    val norm = path.replace('\\', '/')
    val slash = norm.indexOf('/')
    if (slash <= 0) return null
    return norm.substring(0, slash)
}

private fun restPath(path: String, world: String): String {
    val norm = path.replace('\\', '/')
    val prefix = world + "/"
    return if (norm.startsWith(prefix)) norm.removePrefix(prefix) else norm
}

private fun partRelative(path: String, world: String, part: String): String {
    val rest = restPath(path, world)
    val top = rest.substringBefore('/')
    val strip = when (part) {
        "nether" -> if (top == "DIM-1") "DIM-1/" else ""
        "end" -> if (top == "DIM1") "DIM1/" else ""
        "region" -> if (top == "region") "region/" else ""
        "maps" -> if (top == "data") "data/" else ""
        "datapacks" -> if (top == "datapacks") "datapacks/" else ""
        else -> ""
    }
    return if (strip.isNotEmpty() && rest.startsWith(strip)) rest.removePrefix(strip) else rest
}

private fun partId(rest: String): String {
    val top = rest.substringBefore('/')
    return when (top) {
        "level.dat", "level.dat_old", "icon.png" -> "level"
        "region" -> "region"
        "DIM-1" -> "nether"
        "DIM1" -> "end"
        "playerdata", "stats", "advancements" -> "players"
        "data" -> "maps"
        "entities", "poi" -> "entities"
        "datapacks" -> "datapacks"
        else -> if (top.startsWith("DIM") || top == "dimensions") "dimensions" else "other"
    }
}

private fun partLabel(part: String): String = when (part) {
    "level" -> I18n.t("backup.part_level")
    "region" -> I18n.t("backup.part_region")
    "nether" -> I18n.t("backup.part_nether")
    "end" -> I18n.t("backup.part_end")
    "dimensions" -> I18n.t("backup.part_dimensions")
    "players" -> I18n.t("backup.part_players")
    "maps" -> I18n.t("backup.part_maps")
    "entities" -> I18n.t("backup.part_entities")
    "datapacks" -> I18n.t("backup.part_datapacks")
    else -> I18n.t("backup.part_other")
}

private fun partIcon(part: String): ImageVector = when (part) {
    "level" -> PmclIcons.Article
    "region" -> PmclIcons.Grid
    "nether" -> PmclIcons.Bolt
    "end" -> PmclIcons.Sun
    "dimensions" -> PmclIcons.Globe
    "players" -> PmclIcons.Accounts
    "maps" -> PmclIcons.Dataset
    "datapacks" -> PmclIcons.Modpack
    else -> Icons.Filled.Folder
}

private fun entryMeta(entries: List<TimeMachine.Entry>): String {
    val parts = ArrayList<String>()
    parts.add(I18n.t("backup.files_n", entries.size))
    parts.add(formatBackupBytes(entries.sumOf { it.size().coerceAtLeast(0L) }))
    val added = entries.count { it.change() == "added" }
    val modified = entries.count { it.change() == "modified" }
    val removed = entries.count { it.change() == "removed" }
    if (added > 0) parts.add(changeLabel("added") + " " + added)
    if (modified > 0) parts.add(changeLabel("modified") + " " + modified)
    if (removed > 0) parts.add(changeLabel("removed") + " " + removed)
    return parts.joinToString(" · ")
}

private fun sliceTitle(world: String, part: String, folder: String): String {
    val bits = ArrayList<String>()
    bits.add(world)
    if (part.isNotBlank()) bits.add(partLabel(part))
    if (folder.isNotBlank()) bits.add(folder.substringAfterLast('/'))
    return bits.joinToString(" · ")
}

private fun gameLabel(root: String): String {
    val norm = root.replace('\\', '/').trimEnd('/')
    val parent = norm.substringBeforeLast('/', "")
    val name = parent.substringAfterLast('/')
    return name.ifBlank { norm.substringAfterLast('/') }
}

private fun normRoot(root: String): String = root.replace('\\', '/').trimEnd('/')

private fun sameRoot(a: String, b: String): Boolean = normRoot(a) == normRoot(b)

private fun fileGroups(entries: List<TimeMachine.Entry>): List<FileGroup> {
    val grouped = entries.groupBy { it.scope() }
    val known = scopeOrder.mapNotNull { scope ->
        grouped[scope]?.let { FileGroup(scope, it) }
    }
    val extra = grouped.filterKeys { it !in scopeOrder }.map { FileGroup(it.key, it.value) }
    return known + extra
}

private fun latestCaption(snap: TimeMachine.Snapshot, now: Long): String {
    val whenText = dayHeading(snap.createdAt(), now) + " " + backupClock.format(Instant.ofEpochMilli(snap.createdAt()))
    return if (snap.note().isNotBlank()) I18n.t("backup.latest") + " · " + whenText else I18n.t("backup.latest")
}

private fun latestHeadline(snap: TimeMachine.Snapshot, now: Long): String {
    if (snap.note().isNotBlank()) return snap.note()
    return dayHeading(snap.createdAt(), now) + " " + backupClock.format(Instant.ofEpochMilli(snap.createdAt()))
}

private fun dayHeading(millis: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return when (day) {
        today -> I18n.t("backup.today")
        today.minusDays(1) -> I18n.t("backup.yesterday")
        else -> backupDay.format(Instant.ofEpochMilli(millis))
    }
}

private fun statusLine(settings: TimeMachine.Settings, storedBytes: Long, nextAt: Long): String {
    val store = I18n.t("backup.store", formatBackupBytes(storedBytes))
    val next = when {
        settings.schedule == "manual" || nextAt <= 0L -> I18n.t("backup.next_off")
        nextAt <= System.currentTimeMillis() -> I18n.t("backup.next_now")
        else -> I18n.t("backup.next_due", backupTime.format(Instant.ofEpochMilli(nextAt)))
    }
    return if (settings.schedule == "manual") {
        store + " · " + next
    } else {
        scheduleLabel(settings.schedule) + " · " + store + " · " + next
    }
}

private fun planSummary(settings: TimeMachine.Settings): String {
    val names = scopeNames(settings)
    val scopes = if (names.isEmpty()) I18n.t("backup.scopes_none") else names.joinToString(" · ")
    return scheduleLabel(settings.schedule) + " · " + I18n.t("backup.keep_n", settings.keep) + " · " + scopes
}

private fun scopeNames(settings: TimeMachine.Settings): List<String> = buildList {
    if (settings.worlds) add(I18n.t("backup.scope_worlds"))
    if (settings.mods) add(I18n.t("backup.scope_mods"))
    if (settings.configs) add(I18n.t("backup.scope_configs"))
    if (settings.resourcepacks) add(I18n.t("backup.scope_resourcepacks"))
    if (settings.shaders) add(I18n.t("backup.scope_shaders"))
    if (settings.screenshots) add(I18n.t("backup.scope_screenshots"))
    if (settings.skins) add(I18n.t("backup.scope_skins"))
    if (settings.launcher) add(I18n.t("backup.scope_launcher"))
}

private fun scheduleLabel(schedule: String): String = when (schedule) {
    "hourly" -> I18n.t("backup.schedule_hourly")
    "daily" -> I18n.t("backup.schedule_daily")
    "weekly" -> I18n.t("backup.schedule_weekly")
    else -> I18n.t("backup.schedule_manual")
}

private fun entriesLookUnchanged(added: Int, modified: Int, removed: Int): Boolean =
    added == 0 && modified == 0 && removed == 0

private fun entryKey(entry: TimeMachine.Entry): String = entry.root() + "\n" + entry.path()

private fun fileName(path: String): String {
    val normalized = path.replace('\\', '/').trimEnd('/')
    val slash = normalized.lastIndexOf('/')
    return if (slash >= 0 && slash < normalized.length - 1) normalized.substring(slash + 1) else normalized
}

private fun fileParent(path: String): String {
    val normalized = path.replace('\\', '/')
    val slash = normalized.lastIndexOf('/')
    return if (slash > 0) normalized.substring(0, slash) else ""
}

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
    "screenshots" -> I18n.t("backup.scope_screenshots")
    "skins" -> I18n.t("backup.scope_skins")
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
