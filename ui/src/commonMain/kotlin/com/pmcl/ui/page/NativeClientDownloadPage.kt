package com.pmcl.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.unit.dp
import com.pmcl.core.LauncherConfig
import com.pmcl.core.i18n.I18n
import com.pmcl.core.nativeclient.NativeClientCompiler
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.installNativeClient
import com.pmcl.ui.viewmodel.launchNativeClient
import com.pmcl.ui.viewmodel.refreshNativeClients
import com.pmcl.ui.viewmodel.saveNativeCompileRuntime
import com.pmcl.ui.widget.PmclLazyColumn
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private val COMPILE_TOOLS = listOf("CARGO", "GO", "CMAKE", "MAKE", "DOTNET", "ZIG")

@Composable
fun NativeCompileRuntimeSettings(vm: LauncherViewModel) {
    var tools by remember { mutableStateOf(NativeClientCompiler.tools(LauncherConfig.pmclHome())) }
    Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                I18n.t("native.compile_hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            COMPILE_TOOLS.forEach { tool ->
                CompileToolRow(
                    tool = tool,
                    saved = tools.saved(tool),
                    detected = tools.detected(tool),
                    enabled = true,
                    onPick = { path ->
                        vm.saveNativeCompileRuntime(tool, path) {
                            tools = NativeClientCompiler.tools(LauncherConfig.pmclHome())
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun NativeClientDownloadPage(vm: LauncherViewModel, modifier: Modifier = Modifier) {
    val clients by vm.nativeClients.collectAsState()
    val working by vm.nativeClientWorking.collectAsState()
    val status by vm.status.collectAsState()
    var tools by remember { mutableStateOf(NativeClientCompiler.tools(LauncherConfig.pmclHome())) }
    LaunchedEffect(Unit) {
        vm.refreshNativeClients()
        tools = NativeClientCompiler.tools(LauncherConfig.pmclHome())
    }

    Column(modifier) {
        Text(
            I18n.t("native.hint"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        if (status.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                I18n.t("download.status", status),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.height(12.dp))
        PmclLazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
            items(clients.size) { index ->
                val client = clients[index]
                val runtimeReady = tools.saved(client.buildTool).isNotBlank()
                val tag = if (client.installedTag == "source") I18n.t("native.source_build") else client.installedTag
                Card(
                    Modifier.fillMaxWidth().glassCardBorder(),
                    colors = glassCardColors(),
                    elevation = glassCardElevation(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                client.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                I18n.t("native.author", client.author),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            client.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            clientCaption(client),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            when {
                                client.isInstalled -> I18n.t("native.installed", tag.ifBlank { client.minecraftVersion })
                                !client.isReleaseAvailable -> I18n.t("native.unsupported")
                                else -> I18n.t("native.not_installed_yet")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { vm.installNativeClient(client.id, "") },
                                enabled = !working && (client.isReleaseAvailable || runtimeReady)
                            ) {
                                Text(if (client.isReleaseAvailable) I18n.t("native.install") else I18n.t("native.compile"))
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { vm.launchNativeClient(client.id) },
                                enabled = client.isInstalled && !working
                            ) { Text(I18n.t("native.launch")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompileToolRow(
    tool: String,
    saved: String,
    detected: String,
    enabled: Boolean,
    onPick: (String) -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            I18n.t("native.tool." + tool.lowercase()),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(72.dp)
        )
        OutlinedTextField(
            value = saved,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            placeholder = {
                Text(
                    detected.ifBlank { I18n.t("native.runtime_empty") },
                    style = MaterialTheme.typography.labelSmall
                )
            },
            textStyle = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = { pickRuntime()?.let(onPick) }, enabled = enabled) {
            Text(I18n.t("native.browse"))
        }
        TextButton(onClick = { onPick(detected) }, enabled = enabled && detected.isNotBlank()) {
            Text(I18n.t("native.use_detected"))
        }
    }
}

private fun clientCaption(client: com.pmcl.core.nativeclient.NativeClientInstaller.Card): String {
    val parts = ArrayList<String>()
    if (client.isVulkan) parts.add(I18n.t("native.vulkan"))
    if (client.minecraftVersion.isNotBlank()) parts.add("Minecraft " + client.minecraftVersion)
    parts.add(I18n.t("native.tool." + client.buildTool.lowercase()))
    return parts.joinToString(" · ")
}

private fun pickRuntime(): String? {
    val dialog = FileDialog(null as Frame?, I18n.t("native.compile_runtime"), FileDialog.LOAD)
    dialog.isVisible = true
    val file = dialog.file ?: return null
    val directory = dialog.directory ?: return null
    return File(directory, file).absolutePath
}
