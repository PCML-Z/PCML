package com.pmcl.ui.viewmodel

import com.pmcl.core.launch.JavaRuntimeFinder
import com.pmcl.core.launch.StandaloneMacAppExporter
import com.pmcl.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

data class StandaloneExportUi(
    val running: Boolean,
    val message: String,
    val fraction: Float,
    val error: String? = null,
    val finished: Boolean = false
)

/**
 * 导出当前版本/实例为 macOS 独立 .app。逻辑在 [StandaloneMacAppExporter]，不改动启动流程。
 */
fun LauncherViewModel.exportStandaloneMacApp(
    versionId: String,
    instanceDir: Path?,
    includeSaves: Boolean,
    targetApp: Path
) {
    scope.launch {
        val startMsg = standaloneStr("正在打包独立 App…", "Packing standalone app…")
        _status.value = startMsg
        _standaloneExport.value = StandaloneExportUi(
            running = true, message = startMsg, fraction = 0.02f
        )
        try {
            withContext(Dispatchers.IO) {
                val requiredJava = core.profileBuilder().getRequiredJavaVersion(versionId)
                val javaExe = resolveJavaExe(versionId, requiredJava)
                if (javaExe.isEmpty()) {
                    throw IllegalStateException(
                        standaloneStr("未找到该版本可用的 Java", "No Java runtime for this version")
                    )
                }
                val javaMajor = JavaRuntimeFinder.getMajorVersion(javaExe) ?: 0
                val javaArch = JavaRuntimeFinder.getArchitecture(javaExe)
                val account = StandaloneMacAppExporter.offlineCopy(_account.value)
                val profile = if (instanceDir != null) {
                    core.profileBuilder().buildInstance(
                        versionId, instanceDir, account, javaMajor, javaArch
                    )
                } else {
                    core.profileBuilder().build(versionId, account, javaMajor, javaArch)
                }
                StandaloneMacAppExporter().export(
                    profile, javaExe, targetApp, includeSaves, false, config.getWorkDir()
                ) { msg ->
                    _status.value = msg
                    val prev = _standaloneExport.value
                    val frac = standaloneExportFraction(msg, prev?.fraction ?: 0f)
                    _standaloneExport.value = StandaloneExportUi(
                        running = true, message = msg, fraction = frac
                    )
                }
            }
            val done = standaloneStr("独立 App 已导出", "Standalone app exported")
            _status.value = done
            _standaloneExport.value = StandaloneExportUi(
                running = false, message = done, fraction = 1f, finished = true
            )
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val fail = standaloneStr(
                "导出独立 App 失败：${e.message ?: I18n.t("common.unknown")}",
                "Standalone export failed: ${e.message ?: I18n.t("common.unknown")}"
            )
            _status.value = fail
            _standaloneExport.value = StandaloneExportUi(
                running = false, message = fail, fraction = _standaloneExport.value?.fraction ?: 0f,
                error = e.message ?: I18n.t("common.unknown")
            )
        }
    }
}

fun LauncherViewModel.clearStandaloneExport() {
    _standaloneExport.value = null
}

internal fun standaloneExportFraction(msg: String, prev: Float): Float {
    val resource = RESOURCE_COPY.matchEntire(msg)
    if (resource != null) {
        val n = resource.groupValues[1].toFloat()
        val t = resource.groupValues[2].toFloat().coerceAtLeast(1f)
        return maxOf(prev, 0.38f + 0.24f * (n / t).coerceIn(0f, 1f))
    }
    val mapped = when {
        msg.startsWith("准备") || msg.startsWith("Preparing") -> 0.06f
        msg.contains("Java") -> 0.18f
        msg.contains("natives") -> 0.28f
        msg.contains("游戏资源") || msg.contains("assets") -> 0.38f
        msg.contains("模组与配置") -> 0.64f
        msg.contains("libraries") -> 0.74f
        msg.contains("光影") || msg.contains("资源包") -> 0.86f
        msg.contains("绑定") || msg.contains("bind") -> 0.94f
        msg.startsWith("已写出") || msg.startsWith("Wrote") -> 1f
        else -> prev
    }
    return maxOf(prev, mapped)
}

private val RESOURCE_COPY = Regex("复制资源 (\\d+)/(\\d+)")

internal fun standaloneStr(zh: String, en: String): String {
    val loc = I18n.getCurrentLocale()
    return if (loc == I18n.EN_US || loc == I18n.UD_EN) en else zh
}
