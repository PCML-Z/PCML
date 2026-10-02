package com.pmcl.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.automation.AutomationCommand
import com.pmcl.core.automation.AutomationRunner
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.AnimatedSegmentedSelector
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.util.UUID

private const val JAVA_TEMPLATE = """public class Main {
    public static void main(String[] args) {
        System.out.println("PMCL");
    }
}
"""

private const val KOTLIN_TEMPLATE = """fun main() {
    println("PMCL")
}
"""

private val LANGUAGES = listOf(
    AutomationCommand.KOTLIN,
    AutomationCommand.JAVA,
    AutomationCommand.C,
    AutomationCommand.CPP,
    AutomationCommand.GO
)

private val TRIGGERS = listOf(
    AutomationCommand.TRIGGER_MANUAL,
    AutomationCommand.TRIGGER_LAUNCHER_START,
    AutomationCommand.TRIGGER_BEFORE_GAME,
    AutomationCommand.TRIGGER_AFTER_GAME
)

@Composable
fun AutomationSettings(vm: LauncherViewModel, pref: com.pmcl.core.preferences.Preferences) {
    val commands = remember {
        mutableStateListOf<AutomationCommand>().apply { addAll(pref.automationCommands) }
    }
    val scope = rememberCoroutineScope()
    var runningId by remember { mutableStateOf<String?>(null) }
    var outputs by remember { mutableStateOf(mapOf<String, String>()) }

    fun persist() {
        pref.automationCommands = commands.toList()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            I18n.t("settings.automation.hint"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        if (commands.isEmpty()) {
            Text(
                I18n.t("settings.automation.empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        commands.forEachIndexed { index, command ->
            CommandCard(
                command = command,
                running = runningId == command.id,
                output = outputs[command.id].orEmpty(),
                onChange = { updated ->
                    commands[index] = updated
                    persist()
                },
                onDelete = {
                    commands.removeAt(index)
                    persist()
                },
                onRun = {
                    if (runningId != null) return@CommandCard
                    runningId = command.id
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                AutomationRunner.run(
                                    command,
                                    vm.config.workDir,
                                    AutomationCommand.TRIGGER_MANUAL,
                                    vm.selectedVersion.value ?: "",
                                    "",
                                    null
                                )
                            }
                            outputs = outputs + (command.id to result.summary())
                        } catch (t: CancellationException) {
                            throw t
                        } catch (t: Throwable) {
                            outputs = outputs + (command.id to (t.message ?: t.javaClass.simpleName))
                        } finally {
                            if (isActive) runningId = null
                        }
                    }
                }
            )
        }
        OutlinedButton(
            onClick = {
                commands.add(AutomationCommand(
                    newId(),
                    I18n.t("settings.automation.default_name"),
                    AutomationCommand.JAVA,
                    AutomationCommand.TRIGGER_MANUAL,
                    true,
                    JAVA_TEMPLATE,
                    "",
                    emptyList()
                ))
                persist()
            },
            enabled = commands.size < AutomationCommand.MAX_COMMANDS && runningId == null
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(I18n.t("settings.automation.add"))
        }
    }
}

@Composable
private fun CommandCard(
    command: AutomationCommand,
    running: Boolean,
    output: String,
    onChange: (AutomationCommand) -> Unit,
    onDelete: () -> Unit,
    onRun: () -> Unit
) {
    val jvm = command.isJvm
    Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = command.name,
                    onValueChange = { onChange(command.copy(name = it.take(48))) },
                    label = { Text(I18n.t("settings.automation.name")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Switch(checked = command.isEnabled(), onCheckedChange = { onChange(command.copy(enabled = it)) })
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = I18n.t("common.delete"))
                }
            }
            Text(I18n.t("settings.automation.language"), style = MaterialTheme.typography.labelMedium)
            AnimatedSegmentedSelector(
                items = LANGUAGES.map { languageLabel(it) },
                selectedIndex = LANGUAGES.indexOf(command.language).coerceAtLeast(0),
                onSelect = { index ->
                    val language = LANGUAGES[index]
                    val source = when {
                        language == AutomationCommand.JAVA &&
                            (command.source.isBlank() || command.source.trim() == KOTLIN_TEMPLATE.trim()) -> JAVA_TEMPLATE
                        language == AutomationCommand.KOTLIN &&
                            (command.source.isBlank() || command.source.trim() == JAVA_TEMPLATE.trim()) -> KOTLIN_TEMPLATE
                        AutomationCommand.isJvm(language) -> command.source
                        else -> ""
                    }
                    val executable = if (AutomationCommand.isJvm(language)) "" else command.executable
                    onChange(command.copy(language = language, source = source, executable = executable))
                },
                scrollable = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(I18n.t("settings.automation.trigger"), style = MaterialTheme.typography.labelMedium)
            AnimatedSegmentedSelector(
                items = TRIGGERS.map { triggerLabel(it) },
                selectedIndex = TRIGGERS.indexOf(command.trigger).coerceAtLeast(0),
                onSelect = { onChange(command.copy(trigger = TRIGGERS[it])) },
                scrollable = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (jvm) {
                OutlinedTextField(
                    value = command.source,
                    onValueChange = { onChange(command.copy(source = it.take(AutomationCommand.MAX_SOURCE))) },
                    label = { Text(I18n.t("settings.automation.source")) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 14,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            } else {
                Text(
                    I18n.t("settings.automation.native_hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        command.executable.ifBlank { I18n.t("settings.automation.no_executable") },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        val fd = FileDialog(null as Frame?, I18n.t("settings.automation.pick"), FileDialog.LOAD)
                        fd.isVisible = true
                        if (fd.file != null) {
                            onChange(command.copy(executable = java.io.File(fd.directory, fd.file).absolutePath))
                        }
                    }) { Text(I18n.t("settings.automation.pick")) }
                }
            }
            OutlinedTextField(
                value = command.args.joinToString("\n"),
                onValueChange = { raw ->
                    val args = raw.replace("\r", "").split('\n').take(AutomationCommand.MAX_ARGS).map { it.take(200) }
                    onChange(command.copy(args = args))
                },
                label = { Text(I18n.t("settings.automation.args")) },
                placeholder = { Text(I18n.t("settings.automation.args_hint")) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onRun, enabled = !running) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (running) I18n.t("settings.automation.running") else I18n.t("settings.automation.run"))
                }
            }
            if (output.isNotBlank()) {
                Text(
                    output,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun languageLabel(language: String): String = when (language) {
    AutomationCommand.KOTLIN -> "Kotlin"
    AutomationCommand.JAVA -> "Java"
    AutomationCommand.C -> "C"
    AutomationCommand.CPP -> "C++"
    AutomationCommand.GO -> "Go"
    else -> language
}

private fun triggerLabel(trigger: String): String = when (trigger) {
    AutomationCommand.TRIGGER_LAUNCHER_START -> I18n.t("settings.automation.trigger_start")
    AutomationCommand.TRIGGER_BEFORE_GAME -> I18n.t("settings.automation.trigger_before")
    AutomationCommand.TRIGGER_AFTER_GAME -> I18n.t("settings.automation.trigger_after")
    else -> I18n.t("settings.automation.trigger_manual")
}

private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

private fun AutomationCommand.copy(
    name: String = this.name,
    language: String = this.language,
    trigger: String = this.trigger,
    enabled: Boolean = this.isEnabled(),
    source: String = this.source,
    executable: String = this.executable,
    args: List<String> = this.args
) = AutomationCommand(id, name, language, trigger, enabled, source, executable, args)
