package com.pmcl.ui.viewmodel

import com.pmcl.core.backup.TimeMachine
import com.pmcl.core.i18n.I18n
import com.pmcl.core.version.VersionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

private val timeMachineDueChecked = AtomicBoolean(false)

internal fun LauncherViewModel.timeMachineExtraRoots(): List<Path> {
    val work = config.workDir.toAbsolutePath().normalize()
    val roots = LinkedHashSet<Path>()
    fun addParent(versions: Path) {
        val parent = versions.toAbsolutePath().normalize().parent ?: return
        if (parent != work) roots.add(parent)
    }
    addParent(config.versionsDir)
    for (dir in VersionManager.detectAllMinecraftVersionsDirs()) addParent(dir)
    for (root in preferences.extraMinecraftRoots) {
        try {
            val path = Path.of(root).toAbsolutePath().normalize()
            if (path != work) roots.add(path)
        } catch (_: Throwable) {
        }
    }
    return roots.toList()
}

fun LauncherViewModel.runTimeMachineBackupIfDue() {
    if (!timeMachineDueChecked.compareAndSet(false, true)) return
    scope.launch {
        val due = withContext(Dispatchers.IO) {
            val machine = core.timeMachine()
            machine.due(machine.loadSettings(), System.currentTimeMillis())
        }
        if (due) startTimeMachineBackup(auto = true)
    }
}

fun LauncherViewModel.startTimeMachineBackup(auto: Boolean = false) {
    if (timeMachineBusy.value) return
    timeMachineBusy.value = true
    timeMachineDone.value = 0
    timeMachineTotal.value = 0
    timeMachineCurrent.value = ""
    scope.launch {
        try {
            val snap = withContext(Dispatchers.IO) {
                val machine = core.timeMachine()
                var last = 0L
                machine.backup(
                    machine.loadSettings(),
                    timeMachineExtraRoots(),
                    if (auto) "auto" else "now"
                ) { done, total, current ->
                    val now = System.currentTimeMillis()
                    if (done == total || now - last >= 200L) {
                        last = now
                        timeMachineDone.value = done
                        timeMachineTotal.value = total
                        timeMachineCurrent.value = current
                    }
                }
            }
            timeMachineMessage.value = I18n.t(
                "backup.done",
                snap.files(),
                formatBackupBytes(snap.storedBytes())
            )
            timeMachineGeneration.value = timeMachineGeneration.value + 1
        } catch (e: Throwable) {
            timeMachineMessage.value = I18n.t("backup.failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            timeMachineBusy.value = false
        }
    }
}

fun LauncherViewModel.saveTimeMachineSettings(settings: TimeMachine.Settings) {
    scope.launch(Dispatchers.IO) {
        try {
            core.timeMachine().saveSettings(settings)
        } catch (e: Throwable) {
            timeMachineMessage.value = I18n.t("backup.failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.restoreTimeMachine(
    snapshotId: String,
    selected: List<TimeMachine.Entry>?,
    deleteExtras: Boolean,
) {
    if (timeMachineBusy.value) return
    timeMachineBusy.value = true
    timeMachineDone.value = 0
    timeMachineTotal.value = 0
    scope.launch {
        try {
            val count = withContext(Dispatchers.IO) {
                var last = 0L
                core.timeMachine().restore(snapshotId, selected, deleteExtras) { done, total, current ->
                    val now = System.currentTimeMillis()
                    if (done == total || now - last >= 200L) {
                        last = now
                        timeMachineDone.value = done
                        timeMachineTotal.value = total
                        timeMachineCurrent.value = current
                    }
                }
            }
            timeMachineMessage.value = I18n.t("backup.restore_done", count)
            timeMachineGeneration.value = timeMachineGeneration.value + 1
        } catch (e: Throwable) {
            timeMachineMessage.value = I18n.t("backup.failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            timeMachineBusy.value = false
        }
    }
}

fun LauncherViewModel.deleteTimeMachineSnapshot(snapshotId: String) {
    if (timeMachineBusy.value) return
    timeMachineBusy.value = true
    scope.launch {
        try {
            withContext(Dispatchers.IO) { core.timeMachine().deleteSnapshot(snapshotId) }
            timeMachineMessage.value = I18n.t("backup.deleted")
            timeMachineGeneration.value = timeMachineGeneration.value + 1
        } catch (e: Throwable) {
            timeMachineMessage.value = I18n.t("backup.failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            timeMachineBusy.value = false
        }
    }
}

internal fun formatBackupBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    do {
        value /= 1024.0
        index++
    } while (value >= 1024.0 && index < units.lastIndex)
    return String.format("%.1f %s", value, units[index])
}
