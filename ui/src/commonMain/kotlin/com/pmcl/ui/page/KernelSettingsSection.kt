package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.kernel.KernelException
import com.pmcl.core.kernel.KernelManager
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser

/**
 * 设置里的扩展内核：导入 Java、C# 或 Rust 内核，启用后由 KernelManager 加载。
 */
@Composable
fun KernelSettingsSection(vm: LauncherViewModel) {
    val scope = rememberCoroutineScope()
    var kernels by remember { mutableStateOf<List<KernelManager.Installed>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun reload() {
        kernels = vm.core.kernels().list()
    }

    LaunchedEffect(Unit) { reload() }

    fun work(block: () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: KernelException) {
                    kernelError(e)
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            }
            reload()
            message = text
            busy = false
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            I18n.t("kernel.hint"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            I18n.t("kernel.format"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    val fd = FileDialog(null as Frame?, I18n.t("kernel.import"), FileDialog.LOAD)
                    fd.isVisible = true
                    val name = fd.file ?: return@Button
                    val directory = fd.directory ?: return@Button
                    val path = Paths.get(directory, name)
                    work {
                        val installed = vm.core.kernels().install(path)
                        I18n.t("kernel.imported", installed.manifest.name)
                    }
                }
            ) {
                Text(I18n.t("kernel.import"))
            }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val chooser = JFileChooser()
                    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    chooser.dialogTitle = I18n.t("kernel.import_dir")
                    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@OutlinedButton
                    val path: Path = chooser.selectedFile?.toPath() ?: return@OutlinedButton
                    work {
                        val installed = vm.core.kernels().install(path)
                        I18n.t("kernel.imported", installed.manifest.name)
                    }
                }
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(I18n.t("kernel.import_dir"))
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        if (kernels.isEmpty()) {
            Text(
                I18n.t("kernel.empty"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        kernels.forEach { kernel ->
            val manifest = kernel.manifest
            Card(
                modifier = Modifier.fillMaxWidth().glassCardBorder(),
                colors = glassCardColors(),
                elevation = glassCardElevation()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(manifest.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(manifest.version, runtimeLabel(manifest.runtime), manifest.author)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Surface(shape = RoundedCornerShape(8.dp), color = healthColor(kernel.health).copy(alpha = 0.12f)) {
                            Text(
                                I18n.t("kernel.health." + kernel.health),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = healthColor(kernel.health)
                            )
                        }
                    }
                    if (manifest.description.isNotBlank()) {
                        Text(manifest.description, style = MaterialTheme.typography.bodySmall)
                    }
                    if (kernel.detail.isNotBlank() && kernel.health != KernelManager.HEALTH_READY) {
                        Text(kernel.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(I18n.t("kernel.enable"), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = kernel.isEnabled,
                            enabled = !busy,
                            onCheckedChange = { on ->
                                work {
                                    vm.core.kernels().setEnabled(manifest.id, on)
                                    I18n.t(if (on) "kernel.enabled" else "kernel.disabled", manifest.name)
                                }
                            }
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                work {
                                    vm.core.kernels().remove(manifest.id)
                                    I18n.t("kernel.removed", manifest.name)
                                }
                            }
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(I18n.t("kernel.remove"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun healthColor(health: String) = when (health) {
    KernelManager.HEALTH_READY -> MaterialTheme.colorScheme.primary
    KernelManager.HEALTH_MISSING_RUNTIME -> MaterialTheme.colorScheme.tertiary
    KernelManager.HEALTH_FAILED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
}

private fun runtimeLabel(runtime: String): String = when (runtime) {
    "csharp" -> I18n.t("kernel.runtime.csharp")
    "rust" -> I18n.t("kernel.runtime.rust")
    else -> I18n.t("kernel.runtime.java")
}

private fun kernelError(error: KernelException): String {
    val key = "kernel.error." + error.code()
    val text = I18n.t(key)
    return if (text == key) error.code() else text
}
