package com.pmcl.ui.page

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pmcl.core.i18n.I18n
import com.pmcl.core.launch.CrashAnalyzer
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.generateSupportPack
import com.pmcl.ui.viewmodel.openCrashHelp
import com.pmcl.ui.viewmodel.relaunchAfterCrash
import java.awt.FileDialog
import java.awt.Frame
import java.text.SimpleDateFormat
import java.util.Date

private val CrashRed = Color(0xFF9B1C1C)
private val CrashRedDark = Color(0xFF2A0B0B)
private val CrashOnRed = Color(0xFFFFF5F5)

@Composable
fun GameCrashPopup(vm: LauncherViewModel) {
    val event by vm.crashEvent.collectAsState()
    val ev = event ?: return
    val packBusy by vm.supportPackBusy.collectAsState()
    val packPath by vm.supportPackPath.collectAsState()
    val gameLogs by vm.gameLogs.collectAsState()

    val reportBody = remember(ev, gameLogs) {
        val fromFile = ev.report?.content?.takeIf { it.isNotBlank() && !ev.live }
        fromFile ?: crashReportText(ev.recentLogs.ifEmpty { gameLogs.map { it.text } })
    }
    val scroll = rememberScrollState()
    LaunchedEffect(reportBody.length, ev.live) {
        if (ev.live) scroll.scrollTo(scroll.maxValue)
    }

    val causes = ev.report?.causes ?: emptyList()
    val recoveryActions = ev.report?.recoveryActions ?: emptyList()
    val infoLine = if (ev.live || ev.exitCode < 0) {
        I18n.t("crash.popup.info_live", ev.versionId)
    } else {
        I18n.t("crash.popup.info", ev.versionId, ev.exitCode)
    }

    Dialog(
        onDismissRequest = { vm.clearCrashEvent() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = CrashRed,
            modifier = Modifier.widthIn(min = 420.dp, max = 720.dp).fillMaxWidth(0.92f)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = CrashOnRed,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        I18n.t("crash.popup.title"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = CrashOnRed
                    )
                    if (ev.live) {
                        Spacer(Modifier.size(10.dp))
                        Text(
                            I18n.t("crash.popup.live"),
                            style = MaterialTheme.typography.labelMedium,
                            color = CrashOnRed.copy(alpha = 0.85f)
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    infoLine,
                    style = MaterialTheme.typography.labelMedium,
                    color = CrashOnRed.copy(alpha = 0.85f)
                )
                if (causes.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    causes.take(3).forEach { c ->
                        Text(
                            "· $c",
                            style = MaterialTheme.typography.bodySmall,
                            color = CrashOnRed
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    I18n.t("crash.popup.report"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = CrashOnRed
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    color = CrashRedDark,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 280.dp)
                ) {
                    Text(
                        reportBody.ifBlank { ev.recentLogs.joinToString("\n") },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFFCDD2),
                        modifier = Modifier
                            .padding(10.dp)
                            .verticalScroll(scroll)
                    )
                }
                if (recoveryActions.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    recoveryActions.take(4).forEach { action ->
                        Surface(
                            onClick = { vm.executeRecoveryAction(action, ev.versionId) },
                            shape = RoundedCornerShape(6.dp),
                            color = Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = CrashOnRed
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    action.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = CrashOnRed
                                )
                            }
                        }
                    }
                }
                if (packPath != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        I18n.t("crash.popup.pack_ok", packPath ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = CrashOnRed
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val outline = ButtonDefaults.outlinedButtonColors(
                        contentColor = CrashOnRed
                    )
                    val fill = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = CrashRed
                    )
                    OutlinedButton(
                        onClick = {
                            pickSupportPackPath(ev.versionId)?.let { path ->
                                vm.generateSupportPack(path, ev.versionId)
                            }
                        },
                        enabled = !packBusy,
                        modifier = Modifier.weight(1f),
                        colors = outline,
                        border = BorderStroke(1.dp, CrashOnRed.copy(alpha = 0.75f))
                    ) {
                        Text(
                            if (packBusy) I18n.t("crash.popup.pack_busy")
                            else I18n.t("crash.popup.pack")
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            vm.clearCrashEvent()
                            vm.openCrashHelp()
                        },
                        modifier = Modifier.weight(1f),
                        colors = outline,
                        border = BorderStroke(1.dp, CrashOnRed.copy(alpha = 0.75f))
                    ) {
                        Text(I18n.t("crash.popup.help"))
                    }
                    Button(
                        onClick = { vm.relaunchAfterCrash(ev.versionId) },
                        modifier = Modifier.weight(1f),
                        colors = fill
                    ) {
                        Text(I18n.t("crash.popup.relaunch"))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = {
                        vm.clearCrashEvent()
                        vm.clearSupportPackPath()
                    }) {
                        Text(I18n.t("crash.popup.close"), color = CrashOnRed)
                    }
                }
            }
        }
    }
}

private fun crashReportText(lines: List<String>): String {
    if (lines.isEmpty()) return ""
    val idx = lines.indexOfFirst { CrashAnalyzer.looksLikeCrash(it) }
    val slice = if (idx >= 0) lines.drop(idx) else lines.takeLast(160)
    return slice.joinToString("\n")
}

private fun pickSupportPackPath(versionId: String): String? {
    val safe = versionId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val ts = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
    val fd = FileDialog(null as Frame?, I18n.t("crash.pack.save"), FileDialog.SAVE)
    fd.file = "PMCL-Support-$safe-$ts.zip"
    fd.isVisible = true
    val dir = fd.directory ?: return null
    val file = fd.file ?: return null
    if (dir.isEmpty() || file.isEmpty()) return null
    val name = if (file.endsWith(".zip", ignoreCase = true)) file else "$file.zip"
    return dir + name
}
