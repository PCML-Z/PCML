package com.pmcl.ui.viewmodel

import com.pmcl.core.automation.AutomationRunner
import com.pmcl.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

suspend fun LauncherViewModel.awaitAutomation(
    event: String,
    versionId: String,
    gameDir: String,
    exitCode: Int?
) {
    try {
        withContext(Dispatchers.IO) {
            AutomationRunner.runTrigger(
                preferences,
                config.workDir,
                event,
                versionId,
                gameDir,
                exitCode
            ) { line ->
                appendGameLog(line)
                val head = line.lineSequence().firstOrNull()?.take(200).orEmpty()
                if (head.isNotEmpty()) _status.value = head
            }
        }
    } catch (t: CancellationException) {
        throw t
    } catch (t: Throwable) {
        _status.value = I18n.t("settings.automation.failed", t.message ?: t.javaClass.simpleName)
    }
}

fun LauncherViewModel.launchAutomation(event: String, versionId: String, gameDir: String, exitCode: Int?) {
    scope.launch {
        awaitAutomation(event, versionId, gameDir, exitCode)
    }
}
