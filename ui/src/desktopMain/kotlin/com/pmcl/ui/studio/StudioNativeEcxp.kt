package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.pmcl.core.ecxp.DotnetRuntime
import com.pmcl.core.ecxp.EcxpAppManager
import com.pmcl.core.ecxp.EcxpException
import com.pmcl.core.ecxp.EcxpManifest
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.navigation.PmclIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

/** ECXP 模块：已装的 .NET 应用排成一列，点一行在独立窗口里打开。 */
@Composable
internal fun EcxpModule(ctx: ModuleCtx) {
    val p = LocalStudio.current
    val manager = remember { ctx.vm.core.ecxp() }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }

    DisposableEffect(manager) {
        val listener = Runnable { tick++ }
        manager.addListener(listener)
        onDispose { manager.removeListener(listener) }
    }
    val apps by produceState(emptyList<EcxpAppManager.Installed>(), tick) {
        value = withContext(Dispatchers.IO) { manager.list() }
    }
    val runtime by produceState<DotnetRuntime?>(null, tick) {
        value = withContext(Dispatchers.IO) { manager.runtime() }
    }
    val env = mapOf(
        "PMCL_ECXP_THEME" to if (p.editor.luminance() < 0.5f) "dark" else "light",
        "PMCL_ECXP_ACCENT" to "#%06X".format(p.accent.toArgb() and 0xFFFFFF),
        "PMCL_ECXP_LANG" to I18n.getCurrentLocale().toLanguageTag(),
    )

    fun work(block: () -> String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                try {
                    Result.success(block())
                } catch (e: EcxpException) {
                    Result.failure(IllegalStateException(studioEcxpError(e)))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            failed = r.isFailure
            message = r.getOrElse { it.message ?: it.toString() }
            tick++
        }
    }

    fun askInstall(path: Path) {
        val manifest: EcxpManifest = try {
            manager.inspect(path)
        } catch (e: EcxpException) {
            failed = true
            message = studioEcxpError(e)
            return
        } catch (e: Exception) {
            failed = true
            message = e.message ?: e.toString()
            return
        }
        val perms = if (manifest.requestsAllPermissions()) I18n.t("ecxp.permissions_all")
        else manifest.grantedPermissions().sorted().joinToString("、") { permissionText(it) }
        val body = listOf(
            listOf(manifest.version, ".NET " + manifest.frameworkMajor(), manifest.author).filter { it.isNotBlank() }.joinToString(" · "),
            manifest.description,
            I18n.t("ecxp.permissions") + "：" + perms,
            I18n.t("ecxp.trust_hint"),
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        ctx.confirm(I18n.t("ecxp.install_title", manifest.name), body) {
            work { I18n.t("ecxp.installed", manager.install(path).manifest.name) }
        }
    }

    val running = apps.count { it.running }
    Column(Modifier.fillMaxSize()) {
        ModuleHero("ECXP", runtimeText(runtime), ctx.color, PmclIcons.Ecxp) {
            HeroButton(I18n.t("ecxp.install"), Icons.Filled.Add, ctx.color, {
                pickFile(I18n.t("ecxp.install"), setOf("ecxp", "zip")).firstOrNull()?.let(::askInstall)
            })
            HeroButton(I18n.t("ecxp.install_dir"), Icons.Filled.FolderOpen, ctx.color, {
                val chooser = JFileChooser()
                chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                chooser.dialogTitle = I18n.t("ecxp.install_dir")
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.toPath()?.let(::askInstall)
            })
            HeroButton(I18n.t("common.refresh"), Icons.Filled.Refresh, ctx.color, {
                work { manager.refreshRuntime(); "" }
            })
        }
        TileRow(72.dp) {
            ColorTile(I18n.t("ecxp.tile_installed"), apps.size.toString(), ctx.color, Modifier.weight(1f).fillMaxHeight(), PmclIcons.Ecxp, index = 0)
            ColorTile(I18n.t("ecxp.running"), running.toString(), if (running > 0) Tiles.Navy else Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), index = 1)
            ColorTile(
                ".NET",
                runtime?.versions()?.map { it.substringBefore('.') }?.distinct()?.joinToString(" · ")?.ifEmpty { "—" } ?: "…",
                if (runtime?.hasMajor(11) == true) Tiles.Teal else Tiles.Slate,
                Modifier.weight(1f).fillMaxHeight(),
                index = 2,
                onClick = { browse("https://dotnet.microsoft.com/download") },
            )
            ColorTile(I18n.t("ecxp.open_folder"), "", Tiles.Graphite, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.FolderOpen, {
                runCatching {
                    Files.createDirectories(manager.appsRoot())
                    Desktop.getDesktop().open(manager.appsRoot().toFile())
                }
            }, index = 3)
        }
        if (message.isNotBlank()) {
            FlatText(
                message,
                color = if (failed) Tiles.Red else p.text,
                size = 11,
                lines = 3,
                modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
        if (apps.isEmpty()) {
            EmptyState(I18n.t("ecxp.empty") + "\n" + I18n.t("ecxp.empty_hint"), PmclIcons.Ecxp, I18n.t("ecxp.install"), ctx.color) {
                pickFile(I18n.t("ecxp.install"), setOf("ecxp", "zip")).firstOrNull()?.let(::askInstall)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                itemsIndexed(apps, key = { _, a -> a.manifest.id }) { i, app ->
                    val m = app.manifest
                    val open = { work { I18n.t(if (manager.launch(m.id, env)) "ecxp.opened" else "ecxp.focused", m.name) } }
                    val missing = m.needsDotnetHost() && runtime?.hasMajor(m.frameworkMajor()) == false
                    ItemRow(
                        "${m.name} ${m.version}",
                        listOf(".NET " + m.frameworkMajor(), m.author, m.description).filter { it.isNotBlank() }.joinToString(" · "),
                        if (missing) Tiles.Slate else ctx.color,
                        i,
                        image = rememberThumb(app.icon, 96),
                        badge = m.name.take(1).uppercase(),
                        dim = missing,
                        height = 56.dp,
                        onClick = open,
                    ) {
                        if (app.running) {
                            FlatText(
                                I18n.t("ecxp.running"),
                                color = Color.White,
                                size = 11,
                                modifier = Modifier.background(Tiles.Navy).padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                            RowButton(Icons.Filled.Stop, { work { manager.stop(m.id); I18n.t("ecxp.stopped", m.name) } }, label = I18n.t("ecxp.stop"))
                        }
                        RowButton(Icons.AutoMirrored.Filled.OpenInNew, open, ctx.color, label = I18n.t(if (app.running) "ecxp.focus" else "ecxp.open"))
                        RowButton(Icons.AutoMirrored.Filled.Article, {
                            runCatching {
                                val log = manager.logFile(m.id)
                                Desktop.getDesktop().open((if (Files.isRegularFile(log)) log else manager.dataDir(m.id)).toFile())
                            }
                        })
                        RowButton(Icons.Filled.Delete, {
                            ctx.confirmDanger(I18n.t("ecxp.remove_title"), I18n.t("ecxp.remove_body", m.name)) {
                                work { manager.remove(m.id); I18n.t("ecxp.removed", m.name) }
                            }
                        })
                    }
                }
            }
        }
    }
}

private fun runtimeText(runtime: DotnetRuntime?): String = when {
    runtime == null -> I18n.t("ecxp.runtime_probing")
    !runtime.found() -> I18n.t("ecxp.runtime_missing")
    runtime.versions().isEmpty() -> I18n.t("ecxp.runtime_none")
    else -> I18n.t("ecxp.runtime_found", runtime.versions().joinToString(", "))
}

private fun permissionText(name: String): String {
    val key = "ecxp.perm." + name.lowercase()
    val text = I18n.t(key)
    return if (text == key) name else text
}

private fun studioEcxpError(error: EcxpException): String {
    val key = "ecxp.error." + error.code()
    val text = I18n.t(key, error.detail())
    return if (text == key) listOf(error.code(), error.detail()).filter { it.isNotBlank() }.joinToString(": ") else text
}
