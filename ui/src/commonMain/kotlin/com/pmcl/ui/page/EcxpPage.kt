package com.pmcl.ui.page

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.ecxp.DotnetRuntime
import com.pmcl.core.ecxp.EcxpAppManager
import com.pmcl.core.ecxp.EcxpException
import com.pmcl.core.ecxp.EcxpManifest
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.MotionTokens
import com.pmcl.ui.navigation.PmclIcons
import com.pmcl.ui.theme.PmclCard
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.widget.DangerConfirmDialog
import com.pmcl.ui.widget.PmclLazyColumn
import com.pmcl.ui.widget.SlideToConfirm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.JFileChooser

private const val DOTNET_MAJOR = 11

/** ECXP：装在 PMCL 里的 .NET 应用。点一下应用，它在自己的窗口里打开。 */
@Composable
fun EcxpPage(vm: LauncherViewModel) {
    val manager = vm.core.ecxp()
    val scope = rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<EcxpAppManager.Installed>>(emptyList()) }
    var runtime by remember { mutableStateOf<DotnetRuntime?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Pair<Path, EcxpManifest>?>(null) }
    var removing by remember { mutableStateOf<EcxpManifest?>(null) }

    DisposableEffect(manager) {
        val listener = Runnable { tick++ }
        manager.addListener(listener)
        onDispose { manager.removeListener(listener) }
    }
    LaunchedEffect(tick) {
        val (list, rt) = withContext(Dispatchers.IO) { manager.list() to manager.runtime() }
        apps = list
        runtime = rt
    }

    val colors = MaterialTheme.colorScheme
    val launchEnv = mapOf(
        "PMCL_ECXP_THEME" to if (colors.surface.luminance() < 0.5f) "dark" else "light",
        "PMCL_ECXP_ACCENT" to "#%06X".format(colors.primary.toArgb() and 0xFFFFFF),
        "PMCL_ECXP_LANG" to I18n.getCurrentLocale().toLanguageTag(),
    )

    fun work(block: () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: EcxpException) {
                    ecxpError(e)
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            }
            message = text
            busy = false
            tick++
        }
    }

    fun inspect(path: Path) = work {
        val manifest = manager.inspect(path)
        pending = path to manifest
        null
    }

    fun open(app: EcxpAppManager.Installed) = work {
        val fresh = manager.launch(app.manifest.id, launchEnv)
        I18n.t(if (fresh) "ecxp.opened" else "ecxp.focused", app.manifest.name)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("ECXP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Button(
                enabled = !busy,
                onClick = { pickPackage(::inspect) },
                shape = RectangleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("ecxp.install"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    val chooser = JFileChooser()
                    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    chooser.dialogTitle = I18n.t("ecxp.install_dir")
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        chooser.selectedFile?.toPath()?.let(::inspect)
                    }
                },
                shape = RectangleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("ecxp.install_dir"))
            }
            IconButton(onClick = {
                runCatching {
                    Files.createDirectories(manager.appsRoot())
                    Desktop.getDesktop().open(manager.appsRoot().toFile())
                }
            }) {
                Icon(Icons.Filled.FolderOpen, contentDescription = I18n.t("ecxp.open_folder"))
            }
            IconButton(onClick = { work { manager.refreshRuntime(); null } }) {
                Icon(Icons.Filled.Refresh, contentDescription = I18n.t("common.refresh"))
            }
        }
        Text(
            I18n.t("ecxp.subtitle"),
            style = MaterialTheme.typography.labelSmall,
            color = colors.outline
        )
        Spacer(Modifier.height(8.dp))
        RuntimeLine(runtime, onDownload = {
            vm.enqueueEcxpDotnet(DOTNET_MAJOR)
            message = I18n.t("ecxp.dotnet_queued", DOTNET_MAJOR.toString())
        })
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.primary)
        }
        Spacer(Modifier.height(10.dp))

        if (apps.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Icon(PmclIcons.Ecxp, contentDescription = null, modifier = Modifier.size(40.dp), tint = colors.outline)
                    Spacer(Modifier.height(12.dp))
                    Text(I18n.t("ecxp.empty"), style = MaterialTheme.typography.bodyMedium, color = colors.outline)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        I18n.t("ecxp.empty_hint"),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.outline,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            PmclLazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
            ) {
                items(apps, key = { it.manifest.id }) { app ->
                    AppCard(
                        app = app,
                        runtime = runtime,
                        busy = busy,
                        modifier = Modifier.animateItem(
                            fadeInSpec = tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.EasingEmphasizedDecelerate),
                            placementSpec = tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.EasingEmphasized)
                        ),
                        onOpen = { open(app) },
                        onGetRuntime = {
                            vm.enqueueEcxpDotnet(app.manifest.frameworkMajor())
                            message = I18n.t("ecxp.dotnet_queued", app.manifest.frameworkMajor().toString())
                        },
                        onStop = { work { manager.stop(app.manifest.id); I18n.t("ecxp.stopped", app.manifest.name) } },
                        onLog = {
                            runCatching {
                                val log = manager.logFile(app.manifest.id)
                                if (Files.isRegularFile(log)) Desktop.getDesktop().open(log.toFile())
                                else Desktop.getDesktop().open(manager.dataDir(app.manifest.id).toFile())
                            }
                        },
                        onRemove = { removing = app.manifest },
                    )
                }
            }
        }
    }

    pending?.let { (path, manifest) ->
        InstallDialog(
            manifest = manifest,
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                work {
                    val installed = manager.install(path)
                    I18n.t("ecxp.installed", installed.manifest.name)
                }
            }
        )
    }

    removing?.let { manifest ->
        DangerConfirmDialog(
            title = I18n.t("ecxp.remove_title"),
            message = I18n.t("ecxp.remove_body", manifest.name),
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                work {
                    manager.remove(manifest.id)
                    I18n.t("ecxp.removed", manifest.name)
                }
            }
        )
    }
}

@Composable
private fun RuntimeLine(runtime: DotnetRuntime?, onDownload: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val ready = runtime != null && runtime.found() && runtime.versions().isNotEmpty() && runtime.hasMajor(11)
    val missing = runtime != null && (!runtime.found() || !runtime.hasMajor(11))
    val dot = when {
        runtime == null -> colors.outline
        ready -> colors.primary
        else -> colors.error
    }
    val text = when {
        runtime == null -> I18n.t("ecxp.runtime_probing")
        !runtime.found() -> I18n.t("ecxp.runtime_missing")
        runtime.versions().isEmpty() -> I18n.t("ecxp.runtime_none")
        else -> I18n.t("ecxp.runtime_found", runtime.versions().joinToString(", "))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = colors.outline,
            modifier = Modifier.weight(1f)
        )
        if (missing) {
            TextButton(onClick = onDownload) {
                Text(I18n.t("ecxp.get_dotnet"))
            }
        }
    }
}

@Composable
private fun AppCard(
    app: EcxpAppManager.Installed,
    runtime: DotnetRuntime?,
    busy: Boolean,
    onOpen: () -> Unit,
    onGetRuntime: () -> Unit,
    onStop: () -> Unit,
    onLog: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val m = app.manifest
    val colors = MaterialTheme.colorScheme
    val missingRuntime = m.needsDotnetHost() && runtime != null && !runtime.hasMajor(m.frameworkMajor())
    PmclCard(
        modifier = modifier.fillMaxWidth(),
        onClick = { if (!busy) onOpen() },
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (app.running) {
                            Spacer(Modifier.width(8.dp))
                            Text(I18n.t("ecxp.running"), style = MaterialTheme.typography.labelSmall, color = colors.primary)
                        }
                    }
                    Text(
                        listOf(m.version, ".NET " + m.frameworkMajor(), m.author).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(enabled = !busy, onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = I18n.t(if (app.running) "ecxp.focus" else "ecxp.open"), modifier = Modifier.size(18.dp), tint = colors.primary)
                }
                if (app.running) {
                    IconButton(enabled = !busy, onClick = onStop) {
                        Icon(Icons.Filled.Stop, contentDescription = I18n.t("ecxp.stop"), modifier = Modifier.size(18.dp), tint = colors.primary)
                    }
                }
                IconButton(onClick = onLog) {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = I18n.t("ecxp.log"), modifier = Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                }
                IconButton(enabled = !busy, onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = I18n.t("ecxp.remove"), modifier = Modifier.size(18.dp), tint = colors.error)
                }
            }
            if (m.description.isNotBlank() || missingRuntime) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }
            if (m.description.isNotBlank()) {
                Text(m.description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (missingRuntime) {
                if (m.description.isNotBlank()) Spacer(Modifier.height(4.dp))
                TextButton(onClick = onGetRuntime, contentPadding = PaddingValues(0.dp)) {
                    Text(I18n.t("ecxp.needs_runtime", m.frameworkMajor().toString()), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun AppIcon(app: EcxpAppManager.Installed) {
    val colors = MaterialTheme.colorScheme
    val bitmap by produceIcon(app.icon)
    Box(Modifier.size(40.dp).clip(RectangleShape).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                app.manifest.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private fun pickPackage(onPick: (Path) -> Unit) {
    val fd = FileDialog(null as Frame?, I18n.t("ecxp.install"), FileDialog.LOAD)
    fd.setFilenameFilter { _, name ->
        val n = name.lowercase()
        n.endsWith(EcxpAppManager.PACKAGE_EXTENSION) || n.endsWith(".zip")
    }
    fd.isVisible = true
    val name = fd.file ?: return
    val dir = fd.directory ?: return
    onPick(Paths.get(dir, name))
}

@Composable
private fun produceIcon(path: Path?) = androidx.compose.runtime.produceState<ImageBitmap?>(null, path) {
    value = if (path == null) null else withContext(Dispatchers.IO) { loadPathImageBitmap(path.toString()) }
}

@Composable
private fun InstallDialog(manifest: EcxpManifest, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("ecxp.install_title", manifest.name)) },
        text = {
            Column {
                Text(
                    listOf(manifest.version, ".NET " + manifest.frameworkMajor(), manifest.author).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.outline,
                )
                if (manifest.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(manifest.description, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("ecxp.permissions"), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                val perms = if (manifest.requestsAllPermissions()) {
                    I18n.t("ecxp.permissions_all")
                } else {
                    manifest.grantedPermissions().sorted().joinToString("、") { permissionLabel(it) }
                }
                Text(perms, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("ecxp.trust_hint"), style = MaterialTheme.typography.bodySmall, color = colors.error)
                Spacer(Modifier.height(16.dp))
                SlideToConfirm(
                    onConfirmed = onConfirm,
                    label = I18n.t("ecxp.slide_install"),
                    square = true,
                    trackColor = colors.primaryContainer,
                    knobColor = colors.primary,
                    labelColor = colors.onPrimaryContainer,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) } },
    )
}

private fun permissionLabel(name: String): String {
    val key = "ecxp.perm." + name.lowercase()
    val text = I18n.t(key)
    return if (text == key) name else text
}

private fun ecxpError(error: EcxpException): String {
    val key = "ecxp.error." + error.code()
    val text = I18n.t(key, error.detail())
    return if (text == key) listOf(error.code(), error.detail()).filter { it.isNotBlank() }.joinToString(": ") else text
}
