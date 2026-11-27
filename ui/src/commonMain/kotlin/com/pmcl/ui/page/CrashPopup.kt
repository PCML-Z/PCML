package com.pmcl.ui.page
import com.pmcl.ui.widget.pmclVerticalScroll

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.gson.JsonArray
import com.pmcl.core.agent.AgentApi
import com.pmcl.core.agent.AgentModel
import com.pmcl.core.agent.CrashRepair
import com.pmcl.core.i18n.I18n
import com.pmcl.core.launch.CrashAnalyzer
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.generateSupportPack
import com.pmcl.ui.viewmodel.openCrashHelp
import com.pmcl.ui.viewmodel.refreshInstalledMods
import com.pmcl.ui.viewmodel.relaunchAfterCrash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
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
    val packBusy by vm.supportPackBusy.collectAsState()
    val packPath by vm.supportPackPath.collectAsState()
    val recovery by vm.recoveryMessage.collectAsState()
    val shareUrl by vm.shareUrl.collectAsState()
    val ev = event ?: return

    val reportBody = remember(ev) {
        val fromFile = ev.report?.content?.takeIf { it.isNotBlank() && !ev.live }
        fromFile ?: crashReportText(ev.recentLogs)
    }
    val bodyScroll = rememberScrollState()
    val reportScroll = rememberScrollState()
    LaunchedEffect(reportBody.length, ev.live) {
        if (ev.live) reportScroll.scrollTo(reportScroll.maxValue)
    }

    val causes = ev.report?.causes ?: emptyList()
    val recoveryActions = ev.report?.recoveryActions ?: emptyList()
    val infoLine = if (ev.live || ev.exitCode < 0) {
        I18n.t("crash.popup.info_live", ev.versionId)
    } else {
        I18n.t("crash.popup.info", ev.versionId, ev.exitCode)
    }

    val ai = rememberCrashAiController(
        vm = vm,
        versionId = ev.versionId,
        session = ev.session,
        report = ev.report?.content?.takeIf { it.isNotBlank() && !ev.live } ?: "",
        logs = ev.recentLogs.joinToString("\n")
    )

    Dialog(
        onDismissRequest = { vm.clearCrashEvent() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // 弹窗最多跟窗口一样高。日志区滚动，修复按钮留在窗口里面。
        BoxWithConstraints {
            val heightCap = if (maxHeight.value.isFinite() && maxHeight > 48.dp) maxHeight - 24.dp else maxHeight
            val widthCap = if (maxWidth.value.isFinite()) minOf(720.dp, maxWidth) else 720.dp
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = CrashRed,
                modifier = Modifier.widthIn(max = widthCap).fillMaxWidth(0.92f).heightIn(max = heightCap)
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
                            color = CrashOnRed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (ev.live) {
                            Spacer(Modifier.size(10.dp))
                            Text(
                                I18n.t("crash.popup.live"),
                                style = MaterialTheme.typography.labelMedium,
                                color = CrashOnRed.copy(alpha = 0.85f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        infoLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = CrashOnRed.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Column(
                        Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .pmclVerticalScroll(bodyScroll)
                    ) {
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
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp)
                        ) {
                            Text(
                                reportBody.ifBlank { ev.recentLogs.joinToString("\n") },
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFFFCDD2),
                                modifier = Modifier
                                    .padding(10.dp)
                                    .pmclVerticalScroll(reportScroll)
                            )
                        }
                        if (recoveryActions.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            recoveryActions.take(4).forEach { action ->
                                Surface(
                                    onClick = { vm.executeRecoveryAction(action, ev.versionId, ev.instanceId) },
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
                        CrashAiDetails(ai)
                        if (!recovery.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                recovery ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = CrashOnRed
                            )
                        }
                        if (!shareUrl.isNullOrBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                shareUrl ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = CrashOnRed
                            )
                        }
                        if (packPath != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                I18n.t("crash.popup.pack_ok", packPath ?: ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = CrashOnRed
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    CrashAiButton(ai)
                    Spacer(Modifier.height(8.dp))
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
                                else I18n.t("crash.popup.pack"),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
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
                            Text(
                                I18n.t("crash.popup.help"),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Button(
                            onClick = { vm.relaunchAfterCrash(ev.versionId, ev.instanceId) },
                            enabled = !ev.live,
                            modifier = Modifier.weight(1f),
                            colors = fill
                        ) {
                            Text(
                                I18n.t("crash.popup.relaunch"),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
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
}

private class CrashAiController(
    val busy: Boolean,
    val note: String,
    val outcome: CrashRepair.Outcome?,
    val onClick: () -> Unit,
)

@Composable
private fun rememberCrashAiController(
    vm: LauncherViewModel,
    versionId: String,
    session: Long,
    report: String,
    logs: String
): CrashAiController {
    val scope = rememberCoroutineScope()
    val reportNow = rememberUpdatedState(report)
    val logsNow = rememberUpdatedState(logs)
    val cancelled = remember(versionId, session) { AtomicBoolean(false) }
    var busy by remember(versionId, session) { mutableStateOf(false) }
    var status by remember(versionId, session) { mutableStateOf("") }
    var outcome by remember(versionId, session) { mutableStateOf<CrashRepair.Outcome?>(null) }
    DisposableEffect(versionId, session) {
        onDispose { cancelled.set(true) }
    }
    val current = outcome
    val note = when {
        status.isNotBlank() -> status
        current == null -> ""
        current.status == "need" -> I18n.t("crash.ai.need")
        current.status == "empty" -> I18n.t("crash.ai.empty")
        current.status == "failed" -> I18n.t("crash.ai.failed", current.detail.ifBlank { I18n.t("common.unknown") })
        current.status == "engine" -> I18n.t("crash.ai.engine", current.detail.ifBlank { I18n.t("common.unknown") })
        else -> ""
    }
    return CrashAiController(busy, note, outcome) {
        if (busy) {
            cancelled.set(true)
            busy = false
            status = ""
            return@CrashAiController
        }
        cancelled.set(false)
        busy = true
        status = I18n.t("crash.ai.working")
        outcome = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val endpoint = AgentModel.load(vm.config.workDir)
                    val context = vm.core.plugins().appContext(
                        "pmcl.crash-repair",
                        vm.config.workDir.resolve("crash-repair"),
                        AgentApi.permissions()
                    )
                    val modsArgs = JsonArray()
                    modsArgs.add(versionId)
                    val modsDir = modsDirectory(AgentApi.invoke(context, "mods.resolveModsDir", modsArgs))
                    CrashRepair.investigate(endpoint, versionId, reportNow.value, logsNow.value, object : CrashRepair.Hooks {
                        override fun cancelled(): Boolean = cancelled.get()
                        override fun progress(text: String) {
                            scope.launch { status = text }
                        }
                        override fun call(method: String, args: JsonArray): String {
                            return AgentApi.invoke(context, method, args)
                        }
                    }, modsDir)
                }
                if (cancelled.get()) return@launch
                busy = false
                status = ""
                outcome = result
                if (result.applied.isNotEmpty()) vm.refreshInstalledMods()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cancelled.get()) return@launch
                busy = false
                status = e.message ?: ""
            }
        }
    }
}

@Composable
private fun CrashAiButton(controller: CrashAiController) {
    OutlinedButton(
        onClick = controller.onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = CrashOnRed),
        border = BorderStroke(1.dp, CrashOnRed.copy(alpha = 0.75f))
    ) {
        Text(
            if (controller.busy) I18n.t("crash.ai.stop") else I18n.t("crash.ai.button"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CrashAiDetails(controller: CrashAiController) {
    val note = controller.note
    if (note.isNotBlank()) {
        Text(
            note,
            style = MaterialTheme.typography.labelSmall,
            color = CrashOnRed,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
    val result = controller.outcome
    val insight = result?.insight
    if (result != null && result.status == "ready" && insight != null) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            if (insight.prose.isNotBlank()) {
                Text(insight.prose, style = MaterialTheme.typography.bodySmall, color = CrashOnRed, maxLines = 4)
            }
            if (insight.rootCause.isNotBlank()) {
                Text(
                    I18n.t("crash.ai.cause") + "：" + insight.rootCause,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = CrashOnRed,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            val confidence = insight.confidence
            if (confidence != null) {
                Text(
                    I18n.t("crash.ai.confidence", (confidence * 100).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = CrashOnRed.copy(alpha = 0.85f)
                )
            }
            if (insight.steps.isNotEmpty()) {
                Text(
                    I18n.t("crash.ai.steps"),
                    style = MaterialTheme.typography.labelMedium,
                    color = CrashOnRed,
                    modifier = Modifier.padding(top = 4.dp)
                )
                insight.steps.take(6).forEach { step ->
                    Text("· $step", style = MaterialTheme.typography.labelSmall, color = CrashOnRed, maxLines = 2)
                }
            }
            insight.evidence.take(4).forEach { line ->
                Text("· $line", style = MaterialTheme.typography.labelSmall, color = CrashOnRed, maxLines = 2)
            }
            result.applied.forEach { line ->
                Text(line, style = MaterialTheme.typography.labelSmall, color = CrashOnRed, maxLines = 2)
            }
        }
    }
}

private fun modsDirectory(raw: String): String? {
    val text = raw.trim()
    if (text.isEmpty() || text == "null" || text.startsWith("不能") || text.startsWith("failed")
        || text.startsWith("permission") || text.startsWith("unknown") || text.startsWith("bad_params")
        || text.startsWith("api_unavailable")
    ) return null
    val path = if (text.startsWith("\"")) {
        try {
            com.google.gson.JsonParser.parseString(text).asString
        } catch (_: Exception) {
            return null
        }
    } else {
        text
    }
    return path.takeIf { it.isNotBlank() && !it.startsWith("不能") }
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
    return java.io.File(dir, name).absolutePath
}
