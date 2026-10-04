package com.pmcl.ui.page

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.gamecontent.ResourcePackManager
import com.pmcl.core.gamecontent.ScreenshotManager
import com.pmcl.core.gamecontent.ShaderPackManager
import com.pmcl.core.gamecontent.VersionGameFiles
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.widget.InstalledJavaPicker
import com.pmcl.core.mods.ModManager
import com.pmcl.core.mods.ModScanner
import com.pmcl.core.preferences.Preferences
import com.pmcl.core.preferences.VersionSettings
import com.pmcl.ui.animation.AnimatedSegmentedSelector
import com.pmcl.ui.animation.MotionTokens
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.util.decodeSampledBitmap
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.widget.PmclLazyColumn
import com.pmcl.ui.widget.pmclVerticalScroll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date

private data class ModRow(
    val title: String,
    val version: String,
    val loader: String,
    val fileName: String,
    val jarPath: String,
    val disabled: Boolean
) {
    val toggleable: Boolean
        get() {
            val name = fileName.lowercase()
            return name.endsWith(".jar") || name.endsWith(".jar.disabled")
        }

    fun detail(): String {
        val bits = listOf(version, loader).filter { it.isNotBlank() && !it.equals("unknown", true) }
        return if (bits.isEmpty()) fileName else bits.joinToString(" · ")
    }

    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return title.lowercase().contains(q) || fileName.lowercase().contains(q) ||
            version.lowercase().contains(q) || loader.lowercase().contains(q)
    }
}

private data class ShotRow(val title: String, val path: String, val modified: Long) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        return title.lowercase().contains(query.trim().lowercase())
    }
}

private data class PackRow(
    val title: String,
    val fileName: String,
    val description: String,
    val format: Int,
    val disabled: Boolean
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return title.lowercase().contains(q) || fileName.lowercase().contains(q) ||
            description.lowercase().contains(q)
    }
}

private data class ShaderRow(
    val title: String,
    val fileName: String,
    val size: Long,
    val valid: Boolean,
    val active: Boolean,
    val disabled: Boolean
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return title.lowercase().contains(q) || fileName.lowercase().contains(q)
    }
}

private data class ProjectionBlock(
    val label: String,
    val count: Int,
    val image: ImageBitmap?
)

private data class ProjectionRow(
    val title: String,
    val fileName: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val summary: String,
    val blocks: List<ProjectionBlock>
) {
    fun matches(query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim().lowercase()
        return title.lowercase().contains(q)
            || fileName.lowercase().contains(q)
            || blocks.any { it.label.lowercase().contains(q) }
    }
}

private data class VersionLibrary(
    val mods: List<ModRow> = emptyList(),
    val shots: List<ShotRow> = emptyList(),
    val packs: List<PackRow> = emptyList(),
    val shaders: List<ShaderRow> = emptyList(),
    val projections: List<ProjectionRow> = emptyList()
)

@Composable
fun VersionSettingsPage(vm: LauncherViewModel, versionId: String, onBack: () -> Unit) {
    val saved = vm.preferences.getVersionSettings(versionId)
    val seeded = saved.filledFrom(vm.preferences, versionId, saved.gameDir)
    var name by remember(versionId) { mutableStateOf(seeded.displayName) }
    var gameDir by remember(versionId) { mutableStateOf(seeded.gameDir) }
    var icon by remember(versionId) { mutableStateOf(seeded.iconPath) }
    var minMemory by remember(versionId) { mutableStateOf(blankIfZero(seeded.minMemoryMb)) }
    var maxMemory by remember(versionId) { mutableStateOf(blankIfZero(seeded.maxMemoryMb)) }
    var javaPath by remember(versionId) { mutableStateOf(seeded.javaPath) }
    var extraArgs by remember(versionId) { mutableStateOf(saved.extraArgs) }
    var width by remember(versionId) { mutableStateOf(blankIfZero(seeded.windowWidth)) }
    var height by remember(versionId) { mutableStateOf(blankIfZero(seeded.windowHeight)) }
    var fullscreen by remember(versionId) { mutableIntStateOf(seeded.fullscreenMode) }
    val initialDebug = saved.debug
    var nativesDir by remember(versionId) { mutableStateOf(initialDebug.nativesDir) }
    var graphicsApi by remember(versionId) { mutableStateOf(initialDebug.graphicsApi) }
    var graphicsDriver by remember(versionId) { mutableStateOf(initialDebug.driver) }
    var skipDefaultJvm by remember(versionId) { mutableStateOf(initialDebug.isSkipDefaultJvmArgs) }
    var skipOptimizingJvm by remember(versionId) { mutableStateOf(initialDebug.isSkipOptimizingJvmArgs) }
    var skipGameCheck by remember(versionId) { mutableStateOf(initialDebug.isSkipGameCheck) }
    var skipJvmCompat by remember(versionId) { mutableStateOf(initialDebug.isSkipJvmCheck) }
    var skipNativesReplace by remember(versionId) { mutableStateOf(initialDebug.isSkipNativesReplace) }
    var useNativeGlfw by remember(versionId) { mutableStateOf(initialDebug.isUseNativeGlfw) }
    var useNativeOpenAl by remember(versionId) { mutableStateOf(initialDebug.isUseNativeOpenAl) }
    val defaultNatives = remember(versionId) {
        runCatching { vm.core.config.versionsDir.resolve(versionId).resolve("natives").toString() }
            .getOrDefault(versionId)
    }
    var committed by remember(versionId) { mutableStateOf(seeded.gameDir) }
    var resolveDone by remember(versionId) { mutableStateOf(seeded.gameDir.isNotBlank()) }
    var section by remember(versionId) { mutableIntStateOf(0) }
    var library by remember(versionId) { mutableStateOf(VersionLibrary()) }
    var managed by remember(versionId) { mutableStateOf<Path?>(null) }
    var filesNote by remember(versionId) { mutableStateOf("") }
    var actionError by remember(versionId) { mutableStateOf("") }
    var loading by remember(versionId) { mutableStateOf(true) }
    var loadedPath by remember(versionId) { mutableStateOf<String?>(null) }
    var tick by remember(versionId) { mutableIntStateOf(0) }
    var busy by remember(versionId) { mutableStateOf(false) }
    var modQuery by remember(versionId) { mutableStateOf("") }
    var shotQuery by remember(versionId) { mutableStateOf("") }
    var packQuery by remember(versionId) { mutableStateOf("") }
    var modFilter by remember(versionId) { mutableIntStateOf(0) }
    var packFilter by remember(versionId) { mutableIntStateOf(0) }
    var shaderQuery by remember(versionId) { mutableStateOf("") }
    var projectionQuery by remember(versionId) { mutableStateOf("") }
    var shaderFilter by remember(versionId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val javaInstallations by vm.javaInstallations.collectAsState()
    val javaScanning by vm.javaScanning.collectAsState()
    LaunchedEffect(versionId) { vm.scanJavaInstallations() }
    val title = name.ifBlank { versionId }
    val pending = gameDir != committed
    val sections = listOf(
        I18n.t("version_settings.basic"),
        I18n.t("version_settings.launch"),
        I18n.t("version_settings.mods"),
        I18n.t("version_settings.screenshots"),
        I18n.t("version_settings.resourcepacks"),
        I18n.t("version_settings.shaders"),
        I18n.t("version_settings.projections"),
        I18n.t("version_settings.debug")
    )

    fun currentSettings() = VersionSettings(
        name, gameDir, icon,
        minMemory.toIntOrNull() ?: 0,
        maxMemory.toIntOrNull() ?: 0,
        javaPath, extraArgs,
        width.toIntOrNull() ?: 0,
        height.toIntOrNull() ?: 0,
        fullscreen
    ).withDebug(
        VersionSettings.DebugOptions(
            nativesDir, graphicsApi, graphicsDriver,
            skipDefaultJvm, skipOptimizingJvm, skipGameCheck, skipJvmCompat, skipNativesReplace,
            useNativeGlfw, useNativeOpenAl
        )
    )

    fun runAction(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                block()
                if (!isActive) return@launch
                tick++
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                actionError = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                tick++
            } finally {
                if (isActive) busy = false
            }
        }
    }

    fun openFolder(folder: String) {
        val dir = managed ?: return
        scope.launch {
            try {
                val target = withContext(Dispatchers.IO) { VersionGameFiles.directory(dir, folder) }
                if (!isActive) return@launch
                java.awt.Desktop.getDesktop().open(target.toFile())
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                actionError = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            }
        }
    }

    fun importFile(folder: String, extensions: List<String>) {
        val picked = pickFile(extensions) ?: return
        val dir = managed ?: return
        runAction {
            withContext(Dispatchers.IO) {
                VersionGameFiles.importFile(dir, folder, Path.of(picked))
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = I18n.t("common.back"))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (title != versionId) {
                    Text(
                        versionId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Button(onClick = {
                vm.preferences.setVersionSettings(versionId, currentSettings())
                onBack()
            }) { Text(I18n.t("common.save")) }
        }
        Spacer(Modifier.size(12.dp))
        AnimatedSegmentedSelector(
            items = sections,
            selectedIndex = section,
            onSelect = { section = it },
            modifier = Modifier.fillMaxWidth(),
            scrollable = true,
            height = 40.dp
        )
        Spacer(Modifier.size(12.dp))
        AnimatedContent(
            targetState = section,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val forward = targetState > initialState
                val duration = MotionTokens.DURATION_MEDIUM
                val enter = slideInHorizontally(
                    animationSpec = tween(duration, easing = MotionTokens.EasingEmphasizedDecelerate),
                    initialOffsetX = { if (forward) it / 5 else -it / 5 }
                ) + fadeIn(tween(duration, easing = MotionTokens.EasingEmphasizedDecelerate))
                val exit = slideOutHorizontally(
                    animationSpec = tween(duration * 2 / 3, easing = MotionTokens.EasingEmphasizedAccelerate),
                    targetOffsetX = { if (forward) -it / 6 else it / 6 }
                ) + fadeOut(tween(duration / 2))
                (enter togetherWith exit).using(SizeTransform(clip = false) { _, _ -> snap() })
            },
            label = "versionSettingsSection"
        ) { index ->
            when (index) {
                0 -> BasicSettings(name, { name = it }, gameDir, { gameDir = it }, icon, { icon = it }) {
                    pickDirectory()?.let {
                        gameDir = it
                        committed = it
                    }
                }
                1 -> LaunchSettings(
                    minMemory, { minMemory = digits(it) },
                    maxMemory, { maxMemory = digits(it) },
                    javaPath, { javaPath = it },
                    javaInstallations, javaScanning, { vm.scanJavaInstallations() },
                    extraArgs, { extraArgs = it },
                    width, { width = digits(it) },
                    height, { height = digits(it) },
                    fullscreen, { fullscreen = it }
                )
                2 -> LibraryPane(
                    query = modQuery,
                    onQuery = { modQuery = it },
                    count = library.mods.size,
                    pending = pending,
                    note = filesNote,
                    error = actionError,
                    loading = loading,
                    empty = filteredMods(library.mods, modQuery, modFilter).isEmpty(),
                    filtering = modQuery.isNotBlank() || modFilter != 0,
                    ready = managed != null && !busy,
                    onRefresh = { if (!busy) tick++ },
                    onOpen = { openFolder(VersionGameFiles.MODS) },
                    onImport = { importFile(VersionGameFiles.MODS, listOf(".jar", ".zip", ".litemod")) },
                    filters = {
                        StatusFilters(modFilter) { modFilter = it }
                    }
                ) {
                    ModList(
                        rows = filteredMods(library.mods, modQuery, modFilter),
                        busy = busy,
                        onToggle = { row, enabled ->
                            val dir = managed ?: return@ModList
                            if (busy) return@ModList
                            library = library.copy(mods = library.mods.map {
                                if (it.fileName == row.fileName) it.copy(disabled = !enabled) else it
                            })
                            runAction { setModEnabled(dir, row.jarPath, enabled) }
                        },
                        onDelete = { row ->
                            val dir = managed ?: return@ModList
                            if (busy) return@ModList
                            library = library.copy(mods = library.mods.filter { it.fileName != row.fileName })
                            runAction { deleteMod(dir, row) }
                        }
                    )
                }
                3 -> LibraryPane(
                    query = shotQuery,
                    onQuery = { shotQuery = it },
                    count = library.shots.size,
                    pending = pending,
                    note = filesNote,
                    error = actionError,
                    loading = loading,
                    empty = library.shots.filter { it.matches(shotQuery) }.isEmpty(),
                    filtering = shotQuery.isNotBlank(),
                    ready = managed != null && !busy,
                    onRefresh = { if (!busy) tick++ },
                    onOpen = { openFolder(VersionGameFiles.SCREENSHOTS) },
                    onImport = { importFile(VersionGameFiles.SCREENSHOTS, listOf(".png", ".jpg", ".jpeg")) }
                ) {
                    ShotGrid(
                        rows = library.shots.filter { it.matches(shotQuery) },
                        onOpen = { path ->
                            try {
                                java.awt.Desktop.getDesktop().open(java.io.File(path))
                            } catch (t: Throwable) {
                                actionError = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                            }
                        },
                        onDelete = { row ->
                            val dir = managed ?: return@ShotGrid
                            if (busy) return@ShotGrid
                            library = library.copy(shots = library.shots.filter { it.path != row.path })
                            runAction {
                                withContext(Dispatchers.IO) {
                                    val fileName = Path.of(row.path).fileName?.toString() ?: row.title
                                    VersionGameFiles.delete(dir, VersionGameFiles.SCREENSHOTS, fileName)
                                }
                            }
                        }
                    )
                }
                4 -> LibraryPane(
                    query = packQuery,
                    onQuery = { packQuery = it },
                    count = library.packs.size,
                    pending = pending,
                    note = filesNote,
                    error = actionError,
                    loading = loading,
                    empty = filteredPacks(library.packs, packQuery, packFilter).isEmpty(),
                    filtering = packQuery.isNotBlank() || packFilter != 0,
                    ready = managed != null && !busy,
                    onRefresh = { if (!busy) tick++ },
                    onOpen = { openFolder(VersionGameFiles.RESOURCE_PACKS) },
                    onImport = { importFile(VersionGameFiles.RESOURCE_PACKS, listOf(".zip")) },
                    filters = {
                        StatusFilters(packFilter) { packFilter = it }
                    }
                ) {
                    PackList(
                        rows = filteredPacks(library.packs, packQuery, packFilter),
                        busy = busy,
                        onToggle = { row, enabled ->
                            val dir = managed ?: return@PackList
                            if (busy) return@PackList
                            library = library.copy(packs = library.packs.map {
                                if (it.fileName == row.fileName) it.copy(disabled = !enabled) else it
                            })
                            runAction { setPackEnabled(dir, row.fileName, enabled) }
                        },
                        onDelete = { row ->
                            val dir = managed ?: return@PackList
                            if (busy) return@PackList
                            library = library.copy(packs = library.packs.filter { it.fileName != row.fileName })
                            runAction {
                                withContext(Dispatchers.IO) {
                                    VersionGameFiles.delete(dir, VersionGameFiles.RESOURCE_PACKS, row.fileName)
                                }
                            }
                        }
                    )
                }
                5 -> LibraryPane(
                    query = shaderQuery,
                    onQuery = { shaderQuery = it },
                    count = library.shaders.size,
                    pending = pending,
                    note = filesNote,
                    error = actionError,
                    loading = loading,
                    empty = filteredShaders(library.shaders, shaderQuery, shaderFilter).isEmpty(),
                    filtering = shaderQuery.isNotBlank() || shaderFilter != 0,
                    ready = managed != null && !busy,
                    onRefresh = { if (!busy) tick++ },
                    onOpen = { openFolder(VersionGameFiles.SHADER_PACKS) },
                    onImport = { importFile(VersionGameFiles.SHADER_PACKS, listOf(".zip")) },
                    filters = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            StatusFilters(
                                shaderFilter,
                                activeLabel = I18n.t("version_settings.shader_active")
                            ) { shaderFilter = it }
                            TextButton(
                                onClick = {
                                    val dir = managed ?: return@TextButton
                                    if (busy) return@TextButton
                                    library = library.copy(shaders = library.shaders.map { it.copy(active = false) })
                                    runAction { setShaderActive(dir, null) }
                                },
                                enabled = managed != null && !busy && library.shaders.any { it.active }
                            ) { Text(I18n.t("shader.clear")) }
                        }
                    }
                ) {
                    ShaderList(
                        rows = filteredShaders(library.shaders, shaderQuery, shaderFilter),
                        busy = busy,
                        onApply = { row ->
                            val dir = managed ?: return@ShaderList
                            if (busy || row.disabled) return@ShaderList
                            library = library.copy(shaders = library.shaders.map {
                                it.copy(active = it.fileName == row.fileName)
                            })
                            runAction { setShaderActive(dir, row.fileName) }
                        },
                        onToggle = { row, enabled ->
                            val dir = managed ?: return@ShaderList
                            if (busy) return@ShaderList
                            library = library.copy(shaders = library.shaders.map {
                                if (it.fileName == row.fileName) it.copy(disabled = !enabled) else it
                            })
                            runAction { setShaderEnabled(dir, row.fileName, enabled) }
                        },
                        onDelete = { row ->
                            val dir = managed ?: return@ShaderList
                            if (busy) return@ShaderList
                            library = library.copy(shaders = library.shaders.filter { it.fileName != row.fileName })
                            runAction {
                                withContext(Dispatchers.IO) {
                                    VersionGameFiles.delete(dir, VersionGameFiles.SHADER_PACKS, row.fileName)
                                }
                            }
                        }
                    )
                }
                6 -> LibraryPane(
                    query = projectionQuery,
                    onQuery = { projectionQuery = it },
                    count = library.projections.size,
                    pending = pending,
                    note = filesNote,
                    error = actionError,
                    loading = loading,
                    empty = library.projections.filter { it.matches(projectionQuery) }.isEmpty(),
                    filtering = projectionQuery.isNotBlank(),
                    ready = managed != null && !busy,
                    onRefresh = { if (!busy) tick++ },
                    onOpen = { openFolder(VersionGameFiles.SCHEMATICS) },
                    onImport = {
                        importFile(
                            VersionGameFiles.SCHEMATICS,
                            listOf(".litematic", ".schem", ".schematic", ".nbt")
                        )
                    }
                ) {
                    ProjectionList(
                        rows = library.projections.filter { it.matches(projectionQuery) },
                        busy = busy,
                        onOpen = { path ->
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { revealFile(path) }
                                    if (!isActive) return@launch
                                    actionError = ""
                                } catch (t: CancellationException) {
                                    throw t
                                } catch (t: Throwable) {
                                    actionError = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
                                }
                            }
                        },
                        onDelete = { row ->
                            val dir = managed ?: return@ProjectionList
                            if (busy) return@ProjectionList
                            library = library.copy(projections = library.projections.filter { it.fileName != row.fileName })
                            runAction {
                                withContext(Dispatchers.IO) {
                                    VersionGameFiles.deleteSchematic(dir, row.fileName)
                                }
                            }
                        }
                    )
                }
                else -> DebugSettings(
                    defaultNatives = defaultNatives,
                    nativesDir = nativesDir,
                    onNativesDir = { nativesDir = it },
                    graphicsApi = graphicsApi,
                    onGraphicsApi = {
                        graphicsApi = it
                        if (it == VersionSettings.DebugOptions.API_DEFAULT) {
                            graphicsDriver = VersionSettings.DebugOptions.DRIVER_DEFAULT
                        }
                    },
                    driver = graphicsDriver,
                    onDriver = { graphicsDriver = it },
                    skipDefaultJvm = skipDefaultJvm,
                    onSkipDefaultJvm = { skipDefaultJvm = it },
                    skipOptimizingJvm = skipOptimizingJvm,
                    onSkipOptimizingJvm = { skipOptimizingJvm = it },
                    skipGameCheck = skipGameCheck,
                    onSkipGameCheck = { skipGameCheck = it },
                    skipJvmCompat = skipJvmCompat,
                    onSkipJvmCompat = { skipJvmCompat = it },
                    skipNativesReplace = skipNativesReplace,
                    onSkipNativesReplace = { skipNativesReplace = it },
                    useNativeGlfw = useNativeGlfw,
                    onUseNativeGlfw = { useNativeGlfw = it },
                    useNativeOpenAl = useNativeOpenAl,
                    onUseNativeOpenAl = { useNativeOpenAl = it }
                )
            }
        }
    }

    LaunchedEffect(versionId) {
        try {
            if (gameDir.isBlank()) {
                val dir = withContext(Dispatchers.IO) {
                    try {
                        vm.core.profileBuilder().resolveGameDirectory(versionId).toString()
                    } catch (t: CancellationException) {
                        throw t
                    } catch (_: Throwable) {
                        ""
                    }
                }
                coroutineContext.ensureActive()
                if (dir.isNotBlank() && gameDir.isBlank()) {
                    val filled = vm.preferences.getVersionSettings(versionId)
                        .filledFrom(vm.preferences, versionId, dir)
                    gameDir = filled.gameDir
                    committed = filled.gameDir
                }
            }
        } finally {
            if (isActive) resolveDone = true
        }
    }

    LaunchedEffect(versionId, committed, tick, resolveDone) {
        if (!resolveDone) return@LaunchedEffect
        val sameDir = loadedPath == committed && tick > 0
        if (!sameDir) {
            loading = true
            library = VersionLibrary()
            managed = null
        }
        val dir = resolveManagedDir(vm, versionId, committed)
        coroutineContext.ensureActive()
        managed = dir
        if (dir == null) {
            library = VersionLibrary()
            filesNote = I18n.t("version_settings.bad_path")
            loadedPath = committed
            loading = false
            return@LaunchedEffect
        }
        try {
            val loaded = loadLibrary(dir, versionId)
            coroutineContext.ensureActive()
            library = loaded
            filesNote = dir.toString()
            actionError = ""
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            coroutineContext.ensureActive()
            if (!sameDir) library = VersionLibrary()
            filesNote = dir.toString()
            actionError = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
        }
        coroutineContext.ensureActive()
        loadedPath = committed
        loading = false
    }
}

@Composable
private fun BasicSettings(
    name: String,
    onName: (String) -> Unit,
    gameDir: String,
    onGameDir: (String) -> Unit,
    icon: String,
    onIcon: (String) -> Unit,
    onPickDir: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().pmclVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard {
            Text(
                I18n.t("version_settings.defaults_note"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text(I18n.t("version_settings.name")) },
                placeholder = { Text(I18n.t("version_settings.name_hint")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            OutlinedTextField(
                value = gameDir,
                onValueChange = onGameDir,
                label = { Text(I18n.t("version_settings.path")) },
                placeholder = { Text(I18n.t("version_settings.path_hint")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    IconButton(onClick = onPickDir) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = I18n.t("common.browse"))
                    }
                }
            )
            OutlinedTextField(
                value = icon,
                onValueChange = onIcon,
                label = { Text(I18n.t("version_settings.icon")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    IconButton(onClick = { pickFile(listOf(".png"))?.let(onIcon) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = I18n.t("common.browse"))
                    }
                }
            )
        }
    }
}

@Composable
private fun LaunchSettings(
    minMemory: String,
    onMin: (String) -> Unit,
    maxMemory: String,
    onMax: (String) -> Unit,
    javaPath: String,
    onJava: (String) -> Unit,
    javaInstallations: List<com.pmcl.core.launch.JavaRuntimeFinder.JavaInstallation>,
    javaScanning: Boolean,
    onScanJava: () -> Unit,
    extraArgs: String,
    onArgs: (String) -> Unit,
    width: String,
    onWidth: (String) -> Unit,
    height: String,
    onHeight: (String) -> Unit,
    fullscreen: Int,
    onFullscreen: (Int) -> Unit
) {
    Column(
        Modifier.fillMaxSize().pmclVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassCard {
            Text(I18n.t("version_settings.memory"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = minMemory,
                    onValueChange = onMin,
                    label = { Text(I18n.t("version_settings.memory_min")) },
                    placeholder = { Text(I18n.t("version_settings.inherit")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = maxMemory,
                    onValueChange = onMax,
                    label = { Text(I18n.t("version_settings.memory_max")) },
                    placeholder = { Text(I18n.t("version_settings.inherit")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
            }
            Text(
                I18n.t("version_settings.memory_hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            InstalledJavaPicker(
                selectedPath = javaPath,
                installations = javaInstallations,
                scanning = javaScanning,
                autoLabel = I18n.t("version_settings.java_auto"),
                onSelect = onJava,
                onRefresh = onScanJava,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = javaPath,
                onValueChange = onJava,
                label = { Text(I18n.t("version_settings.java")) },
                placeholder = { Text(I18n.t("version_settings.java_hint")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    IconButton(onClick = { pickFile(emptyList())?.let(onJava) }) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = I18n.t("common.browse"))
                    }
                }
            )
            Text(I18n.t("version_settings.jvm"), style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = extraArgs,
                onValueChange = onArgs,
                label = { Text(I18n.t("version_settings.args")) },
                placeholder = { Text(I18n.t("version_settings.args_hint")) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
            Text(I18n.t("version_settings.window"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = width,
                    onValueChange = onWidth,
                    label = { Text(I18n.t("version_settings.width")) },
                    placeholder = { Text(I18n.t("version_settings.inherit")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                OutlinedTextField(
                    value = height,
                    onValueChange = onHeight,
                    label = { Text(I18n.t("version_settings.height")) },
                    placeholder = { Text(I18n.t("version_settings.inherit")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
            }
            Text(I18n.t("version_settings.fullscreen"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = fullscreen == VersionSettings.FULLSCREEN_WINDOW,
                    onClick = { onFullscreen(VersionSettings.FULLSCREEN_WINDOW) },
                    label = { Text(I18n.t("version_settings.fullscreen_off")) }
                )
                FilterChip(
                    selected = fullscreen == VersionSettings.FULLSCREEN_ON,
                    onClick = { onFullscreen(VersionSettings.FULLSCREEN_ON) },
                    label = { Text(I18n.t("version_settings.fullscreen_on")) }
                )
            }
        }
    }
}

@Composable
private fun LibraryPane(
    query: String,
    onQuery: (String) -> Unit,
    count: Int,
    pending: Boolean,
    note: String,
    error: String,
    loading: Boolean,
    empty: Boolean,
    filtering: Boolean,
    ready: Boolean,
    onRefresh: () -> Unit,
    onOpen: () -> Unit,
    onImport: () -> Unit,
    filters: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                placeholder = { Text(I18n.t("common.search"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Filled.Search, null, Modifier.size(18.dp)) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { onQuery("") }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, null, Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(Modifier.size(8.dp))
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                Text(
                    count.toString(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            IconButton(onClick = onImport, enabled = ready) {
                Icon(Icons.Filled.Download, contentDescription = I18n.t("common.import"), Modifier.size(20.dp))
            }
            IconButton(onClick = onOpen, enabled = ready) {
                Icon(Icons.Filled.Folder, contentDescription = I18n.t("common.open_dir"), Modifier.size(20.dp))
            }
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = I18n.t("common.refresh"), Modifier.size(20.dp))
            }
        }
        if (filters != null) filters()
        if (note.isNotBlank()) {
            Text(
                note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (pending) {
            Text(
                I18n.t("version_settings.path_pending"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (error.isNotBlank()) {
            Text(error, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> CircularProgressIndicator(
                    Modifier.size(32.dp).align(Alignment.Center),
                    strokeWidth = 2.dp
                )
                empty -> Text(
                    if (filtering) I18n.t("version_settings.no_match") else I18n.t("version_settings.empty_files"),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.outline
                )
                else -> content()
            }
        }
    }
}

@Composable
private fun StatusFilters(selected: Int, activeLabel: String? = null, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == 0,
            onClick = { onSelect(0) },
            label = { Text(I18n.t("mods.filter_all")) }
        )
        FilterChip(
            selected = selected == 1,
            onClick = { onSelect(1) },
            label = { Text(I18n.t("version_settings.enabled")) }
        )
        FilterChip(
            selected = selected == 2,
            onClick = { onSelect(2) },
            label = { Text(I18n.t("version_settings.disabled")) }
        )
        if (activeLabel != null) {
            FilterChip(
                selected = selected == 3,
                onClick = { onSelect(3) },
                label = { Text(activeLabel) }
            )
        }
    }
}

@Composable
private fun ModList(
    rows: List<ModRow>,
    busy: Boolean,
    onToggle: (ModRow, Boolean) -> Unit,
    onDelete: (ModRow) -> Unit
) {
    PmclLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(rows.size, key = { rows[it].fileName }) { index ->
            val row = rows[index]
            EntryCard {
                Column(Modifier.weight(1f).alpha(if (row.disabled) 0.55f else 1f)) {
                    Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        row.detail(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    if (row.disabled) I18n.t("version_settings.disabled") else I18n.t("version_settings.enabled"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                if (row.toggleable) {
                    Switch(
                        checked = !row.disabled,
                        onCheckedChange = { onToggle(row, it) },
                        enabled = !busy
                    )
                }
                IconButton(onClick = { onDelete(row) }, enabled = !busy) {
                    Icon(Icons.Filled.Delete, contentDescription = I18n.t("common.delete"))
                }
            }
        }
    }
}

@Composable
private fun PackList(
    rows: List<PackRow>,
    busy: Boolean,
    onToggle: (PackRow, Boolean) -> Unit,
    onDelete: (PackRow) -> Unit
) {
    PmclLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(rows.size, key = { rows[it].fileName }) { index ->
            val row = rows[index]
            EntryCard {
                Column(Modifier.weight(1f).alpha(if (row.disabled) 0.55f else 1f)) {
                    Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail = buildString {
                        if (row.format > 0) append(I18n.t("version_settings.pack_format", row.format))
                        if (row.description.isNotBlank()) {
                            if (isNotEmpty()) append(" · ")
                            append(row.description)
                        }
                        if (isEmpty()) append(row.fileName)
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    if (row.disabled) I18n.t("version_settings.disabled") else I18n.t("version_settings.enabled"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Switch(
                    checked = !row.disabled,
                    onCheckedChange = { onToggle(row, it) },
                    enabled = !busy
                )
                IconButton(onClick = { onDelete(row) }, enabled = !busy) {
                    Icon(Icons.Filled.Delete, contentDescription = I18n.t("common.delete"))
                }
            }
        }
    }
}

@Composable
private fun ShaderList(
    rows: List<ShaderRow>,
    busy: Boolean,
    onApply: (ShaderRow) -> Unit,
    onToggle: (ShaderRow, Boolean) -> Unit,
    onDelete: (ShaderRow) -> Unit
) {
    PmclLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(rows, key = { it.fileName }) { row ->
            EntryCard {
                Column(Modifier.weight(1f).alpha(if (row.disabled) 0.55f else 1f)) {
                    Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail = buildString {
                        append(formatSize(row.size))
                        if (!row.valid) {
                            append(" · ")
                            append(I18n.t("version_settings.shader_invalid"))
                        }
                    }
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (row.active) {
                    Text(
                        I18n.t("version_settings.shader_active"),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.labelLarge
                    )
                } else {
                    TextButton(onClick = { onApply(row) }, enabled = !busy && !row.disabled) {
                        Text(I18n.t("version_settings.shader_apply"))
                    }
                }
                Switch(
                    checked = !row.disabled,
                    onCheckedChange = { onToggle(row, it) },
                    enabled = !busy
                )
                IconButton(onClick = { onDelete(row) }, enabled = !busy) {
                    Icon(Icons.Filled.Delete, contentDescription = I18n.t("common.delete"))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectionList(
    rows: List<ProjectionRow>,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onDelete: (ProjectionRow) -> Unit
) {
    val clock = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    PmclLazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(rows, key = { it.fileName }) { row ->
            EntryCard {
                Column(
                    Modifier.weight(1f).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onOpen(row.path) }
                    )
                ) {
                    Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if ('/' in row.fileName) row.fileName + " · " else "") +
                            row.summary + " · " + clock.format(Date(row.modified)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (row.blocks.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            for (block in row.blocks) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val image = block.image
                                    if (image != null) {
                                        Image(
                                            image,
                                            contentDescription = block.label,
                                            modifier = Modifier.padding(end = 4.dp).size(16.dp),
                                            filterQuality = FilterQuality.None
                                        )
                                    }
                                    Text(
                                        block.label + " × " + block.count,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = { onDelete(row) }, enabled = !busy) {
                    Icon(Icons.Filled.Delete, contentDescription = I18n.t("common.delete"))
                }
            }
        }
    }
}

@Composable
private fun ShotGrid(
    rows: List<ShotRow>,
    onOpen: (String) -> Unit,
    onDelete: (ShotRow) -> Unit
) {
    val clock = remember { SimpleDateFormat("yyyy-MM-dd HH:mm") }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(196.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(rows, key = { it.path }) { row ->
            Card(
                modifier = Modifier.fillMaxWidth().glassCardBorder(),
                shape = RoundedCornerShape(12.dp),
                colors = glassCardColors(),
                elevation = glassCardElevation()
            ) {
                Column {
                    ShotThumb(row.path, row.title) { onOpen(row.path) }
                    Row(
                        Modifier.padding(start = 10.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                clock.format(Date(row.modified)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1
                            )
                        }
                        IconButton(onClick = { onDelete(row) }) {
                            Icon(Icons.Filled.Delete, contentDescription = I18n.t("common.delete"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShotThumb(path: String, title: String, onOpen: () -> Unit) {
    var bitmap by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    LaunchedEffect(path) {
        val loaded = withContext(Dispatchers.IO) {
            try {
                val file = Path.of(path)
                val size = Files.size(file)
                if (size <= 0L || size > 12L * 1024 * 1024) return@withContext null
                decodeSampledBitmap(Files.readAllBytes(file), 480)
            } catch (_: Throwable) {
                null
            }
        }
        if (loaded == null) failed = true else bitmap = loaded
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 10f)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpen
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap!!,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            failed -> Icon(
                Icons.Filled.Image,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            else -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun EntryCard(content: @Composable RowScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().glassCardBorder(),
        shape = RoundedCornerShape(12.dp),
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            content = content
        )
    }
}

@Composable
private fun DebugSettings(
    defaultNatives: String,
    nativesDir: String,
    onNativesDir: (String) -> Unit,
    graphicsApi: String,
    onGraphicsApi: (String) -> Unit,
    driver: String,
    onDriver: (String) -> Unit,
    skipDefaultJvm: Boolean,
    onSkipDefaultJvm: (Boolean) -> Unit,
    skipOptimizingJvm: Boolean,
    onSkipOptimizingJvm: (Boolean) -> Unit,
    skipGameCheck: Boolean,
    onSkipGameCheck: (Boolean) -> Unit,
    skipJvmCompat: Boolean,
    onSkipJvmCompat: (Boolean) -> Unit,
    skipNativesReplace: Boolean,
    onSkipNativesReplace: (Boolean) -> Unit,
    useNativeGlfw: Boolean,
    onUseNativeGlfw: (Boolean) -> Unit,
    useNativeOpenAl: Boolean,
    onUseNativeOpenAl: (Boolean) -> Unit
) {
    val os = System.getProperty("os.name").lowercase()
    val linux = os.contains("linux")
    val windows = os.contains("win")
    var unsupportedOpen by remember { mutableStateOf(false) }
    val shownDriver = if (driver in debugDrivers(graphicsApi, windows)) driver else VersionSettings.DebugOptions.DRIVER_DEFAULT
    Column(
        Modifier.fillMaxSize().pmclVerticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFF6B73C), RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFF3A2A00), modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Column {
                Text(I18n.t("version_settings.debug_warning_title"), fontWeight = FontWeight.Bold, color = Color(0xFF3A2A00))
                Text(I18n.t("version_settings.debug_warning"), style = MaterialTheme.typography.bodySmall, color = Color(0xFF3A2A00))
            }
        }
        GlassCard {
            DebugChoice(
                title = I18n.t("version_settings.debug_natives"),
                subtitle = nativesDir.ifBlank { defaultNatives },
                value = "",
                options = listOf(
                    "" to I18n.t("version_settings.debug_natives_default"),
                    "pick" to I18n.t("version_settings.debug_natives_pick")
                ),
                onSelect = { id ->
                    if (id == "pick") pickDirectory()?.let(onNativesDir) else onNativesDir("")
                }
            )
            HorizontalDivider()
            DebugChoice(
                title = I18n.t("version_settings.debug_api"),
                value = debugApiLabel(graphicsApi),
                options = listOf(
                    VersionSettings.DebugOptions.API_DEFAULT to I18n.t("version_settings.debug_api_default"),
                    VersionSettings.DebugOptions.API_OPENGL to I18n.t("version_settings.debug_api_opengl"),
                    VersionSettings.DebugOptions.API_VULKAN to I18n.t("version_settings.debug_api_vulkan")
                ),
                onSelect = onGraphicsApi
            )
            HorizontalDivider()
            DebugChoice(
                title = I18n.t("version_settings.debug_driver"),
                value = debugDriverLabel(shownDriver),
                options = debugDrivers(graphicsApi, windows).map { it to debugDriverLabel(it) },
                onSelect = onDriver
            )
            HorizontalDivider()
            DebugSwitch(I18n.t("version_settings.debug_no_jvm"), skipDefaultJvm, onSkipDefaultJvm)
            HorizontalDivider()
            DebugSwitch(
                I18n.t("version_settings.debug_no_opt"),
                skipOptimizingJvm || skipDefaultJvm,
                onSkipOptimizingJvm,
                enabled = !skipDefaultJvm
            )
            HorizontalDivider()
            DebugSwitch(I18n.t("version_settings.debug_no_game"), skipGameCheck, onSkipGameCheck)
            HorizontalDivider()
            DebugSwitch(I18n.t("version_settings.debug_no_jvm_check"), skipJvmCompat, onSkipJvmCompat)
            HorizontalDivider()
            DebugSwitch(I18n.t("version_settings.debug_no_natives"), skipNativesReplace, onSkipNativesReplace)
            if (linux) {
                HorizontalDivider()
                DebugSwitch(I18n.t("version_settings.debug_native_glfw"), useNativeGlfw, onUseNativeGlfw)
                HorizontalDivider()
                DebugSwitch(I18n.t("version_settings.debug_native_openal"), useNativeOpenAl, onUseNativeOpenAl)
            }
            HorizontalDivider()
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { unsupportedOpen = !unsupportedOpen }
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(I18n.t("version_settings.debug_unsupported"), modifier = Modifier.weight(1f))
                    Icon(
                        if (unsupportedOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null
                    )
                }
                if (unsupportedOpen) {
                    if (!linux) {
                        Text(
                            I18n.t("version_settings.debug_linux_only"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        DebugSwitch(I18n.t("version_settings.debug_native_glfw"), useNativeGlfw, onUseNativeGlfw)
                        DebugSwitch(I18n.t("version_settings.debug_native_openal"), useNativeOpenAl, onUseNativeOpenAl)
                    }
                    if (!windows) {
                        Text(
                            I18n.t("version_settings.debug_windows_only"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (linux && windows) {
                        Text(I18n.t("version_settings.debug_unsupported_empty"), color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

@Composable
private fun DebugChoice(
    title: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    subtitle: String? = null
) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { open = true }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (value.isNotEmpty()) {
                Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        open = false
                        onSelect(id)
                    }
                )
            }
        }
    }
}

@Composable
private fun DebugSwitch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun debugDrivers(api: String, windows: Boolean): List<String> {
    val options = when (api) {
        VersionSettings.DebugOptions.API_OPENGL -> listOf(
            VersionSettings.DebugOptions.DRIVER_DEFAULT,
            VersionSettings.DebugOptions.DRIVER_LLVMPIPE,
            VersionSettings.DebugOptions.DRIVER_ZINK,
            VersionSettings.DebugOptions.DRIVER_D3D12
        )
        VersionSettings.DebugOptions.API_VULKAN -> listOf(
            VersionSettings.DebugOptions.DRIVER_DEFAULT,
            VersionSettings.DebugOptions.DRIVER_LAVAPIPE,
            VersionSettings.DebugOptions.DRIVER_DOZEN
        )
        else -> listOf(VersionSettings.DebugOptions.DRIVER_DEFAULT)
    }
    return options.filter { id ->
        when (id) {
            VersionSettings.DebugOptions.DRIVER_D3D12,
            VersionSettings.DebugOptions.DRIVER_DOZEN -> windows
            else -> true
        }
    }
}

private fun debugApiLabel(api: String): String = when (api) {
    VersionSettings.DebugOptions.API_OPENGL -> I18n.t("version_settings.debug_api_opengl")
    VersionSettings.DebugOptions.API_VULKAN -> I18n.t("version_settings.debug_api_vulkan")
    else -> I18n.t("version_settings.debug_api_default")
}

private fun debugDriverLabel(driver: String): String = when (driver) {
    VersionSettings.DebugOptions.DRIVER_LLVMPIPE -> I18n.t("version_settings.debug_driver_llvmpipe")
    VersionSettings.DebugOptions.DRIVER_ZINK -> I18n.t("version_settings.debug_driver_zink")
    VersionSettings.DebugOptions.DRIVER_D3D12 -> I18n.t("version_settings.debug_driver_d3d12")
    VersionSettings.DebugOptions.DRIVER_LAVAPIPE -> I18n.t("version_settings.debug_driver_lavapipe")
    VersionSettings.DebugOptions.DRIVER_DOZEN -> I18n.t("version_settings.debug_driver_dozen")
    else -> I18n.t("version_settings.debug_driver_default")
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().glassCardBorder(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

private fun filteredMods(rows: List<ModRow>, query: String, filter: Int): List<ModRow> {
    return rows.filter { row ->
        val status = when (filter) {
            1 -> !row.disabled
            2 -> row.disabled
            else -> true
        }
        status && row.matches(query)
    }
}

private fun filteredPacks(rows: List<PackRow>, query: String, filter: Int): List<PackRow> {
    return rows.filter { row ->
        val status = when (filter) {
            1 -> !row.disabled
            2 -> row.disabled
            else -> true
        }
        status && row.matches(query)
    }
}

private fun filteredShaders(rows: List<ShaderRow>, query: String, filter: Int): List<ShaderRow> {
    return rows.filter { row ->
        val status = when (filter) {
            1 -> !row.disabled
            2 -> row.disabled
            3 -> row.active
            else -> true
        }
        status && row.matches(query)
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
}

private fun revealFile(path: String) {
    val file = java.io.File(path)
    if (!file.isFile) throw java.io.IOException(I18n.t("version_settings.open_failed"))
    val os = System.getProperty("os.name").lowercase()
    val cmd = when {
        os.contains("mac") -> listOf("open", "-R", file.absolutePath)
        os.contains("win") -> listOf("explorer", "/select,", file.absolutePath)
        else -> listOf("xdg-open", file.parentFile?.absolutePath ?: file.absolutePath)
    }
    val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
    if (os.contains("mac") && (!proc.waitFor(8, java.util.concurrent.TimeUnit.SECONDS) || proc.exitValue() != 0)) {
        throw java.io.IOException(I18n.t("version_settings.open_failed"))
    }
}

private fun VersionSettings.filledFrom(preferences: Preferences, versionId: String, gameDir: String): VersionSettings =
    fillDefaults(
        versionId,
        gameDir,
        preferences.minMemoryMb,
        preferences.maxMemoryMb,
        preferences.javaPath,
        preferences.customJvmArgs,
        preferences.gameWindowWidth,
        preferences.gameWindowHeight,
        preferences.isGameFullscreen(),
        preferences.windowIconPath
    )

private fun blankIfZero(value: Int) = if (value <= 0) "" else value.toString()

private fun digits(raw: String) = raw.filter { it.isDigit() }.take(6)

private suspend fun resolveManagedDir(vm: LauncherViewModel, versionId: String, committed: String): Path? {
    if (committed.isBlank()) {
        return withContext(Dispatchers.IO) {
            try {
                vm.core.profileBuilder().resolveGameDirectory(versionId)
            } catch (t: CancellationException) {
                throw t
            } catch (_: Throwable) {
                null
            }
        }
    }
    return VersionSettings.parseGameDir(committed)
}

private suspend fun loadLibrary(gameDir: Path, versionId: String): VersionLibrary = withContext(Dispatchers.IO) {
    val modsDir = VersionGameFiles.directory(gameDir, VersionGameFiles.MODS)
    val rows = linkedMapOf<String, ModRow>()
    for (meta in ModScanner.scanDirectory(modsDir)) {
        val fileName = meta.jarFile ?: continue
        val path = meta.jarPath?.takeIf { it.isNotBlank() } ?: modsDir.resolve(fileName).toString()
        val version = meta.version?.trim().orEmpty()
        val loader = meta.loader?.trim().orEmpty()
        val title = meta.name?.trim()?.takeIf { it.isNotEmpty() } ?: fileName
        rows[fileName] = ModRow(title, version, loader, fileName, path, meta.isDisabled)
    }
    for (fileName in VersionGameFiles.list(gameDir, VersionGameFiles.MODS)) {
        if (fileName in rows) continue
        val disabled = fileName.lowercase().endsWith(".disabled")
        rows[fileName] = ModRow(fileName, "", "", fileName, modsDir.resolve(fileName).toString(), disabled)
    }
    VersionGameFiles.directory(gameDir, VersionGameFiles.SCREENSHOTS)
    VersionGameFiles.directory(gameDir, VersionGameFiles.RESOURCE_PACKS)
    VersionGameFiles.directory(gameDir, VersionGameFiles.SHADER_PACKS)
    val schematicDir = VersionGameFiles.directory(gameDir, VersionGameFiles.SCHEMATICS)
    val shots = ScreenshotManager(gameDir).list().mapNotNull { shot ->
        val path = shot.path ?: return@mapNotNull null
        ShotRow(shot.name ?: path.fileName.toString(), path.toString(), shot.modified)
    }.take(240)
    val packs = ResourcePackManager(gameDir).list().mapNotNull { pack ->
        val path = pack.path ?: return@mapNotNull null
        val fileName = path.fileName?.toString() ?: return@mapNotNull null
        PackRow(
            pack.name?.takeIf { it.isNotBlank() } ?: fileName,
            fileName,
            plainText(pack.description),
            pack.packFormat,
            pack.isDisabled
        )
    }.sortedBy { it.title.lowercase() }
    val root = gameDir.toAbsolutePath().normalize()
    val shaders = ShaderPackManager(root).list().mapNotNull { pack ->
        val path = pack.path ?: return@mapNotNull null
        val fileName = path.fileName?.toString() ?: return@mapNotNull null
        val raw = pack.name?.takeIf { it.isNotBlank() } ?: fileName
        val title = if (raw.lowercase().endsWith(".zip")) raw.dropLast(4) else raw
        ShaderRow(title, fileName, pack.size, pack.isValid, pack.isActive, pack.isDisabled)
    }.sortedBy { it.title.lowercase() }
    val projections = ArrayList<ProjectionRow>()
    BlockCatalog.open(versionId, gameDir).use { catalog ->
        for (file in VersionGameFiles.schematicFiles(schematicDir)) {
            coroutineContext.ensureActive()
            if (projections.size >= 400) break
            val relative = VersionGameFiles.schematicRelative(schematicDir, file)
            val attrs = Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java)
            val path = file.toString()
            val info = readProjection(path)
            val title = info?.name?.takeIf { it.isNotBlank() } ?: file.fileName.toString()
            val summary = if (info != null) {
                I18n.t(
                    "version_settings.projection_summary",
                    info.width, info.height, info.length, info.blocks
                )
            } else {
                formatSize(attrs.size())
            }
            val blocks = info?.kinds?.map { kind ->
                ProjectionBlock(catalog.label(kind.name), kind.count, catalog.image(kind.name))
            }.orEmpty()
            projections.add(ProjectionRow(
                title, relative, path, attrs.size(), attrs.lastModifiedTime().toMillis(), summary, blocks
            ))
        }
    }
    projections.sortBy { it.title.lowercase() }
    VersionLibrary(
        rows.values.sortedBy { it.title.lowercase() },
        shots,
        packs,
        shaders,
        projections
    )
}

private suspend fun setModEnabled(gameDir: Path, jarPath: String, enabled: Boolean) {
    withContext(Dispatchers.IO) {
        val modsDir = VersionGameFiles.directory(gameDir, VersionGameFiles.MODS).toAbsolutePath().normalize()
        val jar = Path.of(jarPath).toAbsolutePath().normalize()
        if (!jar.startsWith(modsDir)) throw java.io.IOException("bad-path")
        val manager = ModManager(modsDir)
        if (enabled) manager.enableModAt(jar) else manager.disableModAt(jar)
    }
}

private suspend fun deleteMod(gameDir: Path, row: ModRow) {
    withContext(Dispatchers.IO) {
        val modsDir = VersionGameFiles.directory(gameDir, VersionGameFiles.MODS).toAbsolutePath().normalize()
        val target = Path.of(row.jarPath).toAbsolutePath().normalize()
        if (target.startsWith(modsDir) && Files.isRegularFile(target) && !Files.isSymbolicLink(target)) {
            Files.deleteIfExists(target)
        } else {
            VersionGameFiles.delete(gameDir, VersionGameFiles.MODS, row.fileName)
        }
    }
}

private suspend fun setShaderEnabled(gameDir: Path, fileName: String, enabled: Boolean) {
    withContext(Dispatchers.IO) {
        val root = gameDir.toAbsolutePath().normalize()
        VersionGameFiles.directory(root, VersionGameFiles.SHADER_PACKS)
        val manager = ShaderPackManager(root)
        if (enabled) manager.enable(fileName) else manager.disable(fileName)
    }
}

private suspend fun setShaderActive(gameDir: Path, fileName: String?) {
    withContext(Dispatchers.IO) {
        val root = gameDir.toAbsolutePath().normalize()
        VersionGameFiles.directory(root, VersionGameFiles.SHADER_PACKS)
        val manager = ShaderPackManager(root)
        if (fileName.isNullOrBlank()) {
            manager.clearActive()
            return@withContext
        }
        val pack = manager.list().firstOrNull { it.path?.fileName?.toString() == fileName }
            ?: throw java.io.IOException("bad-file")
        manager.setActive(pack)
    }
}

private suspend fun setPackEnabled(gameDir: Path, fileName: String, enabled: Boolean) {
    withContext(Dispatchers.IO) {
        val root = gameDir.toAbsolutePath().normalize()
        VersionGameFiles.directory(root, VersionGameFiles.RESOURCE_PACKS)
        val manager = ResourcePackManager(root)
        if (enabled) manager.enable(fileName) else manager.disable(fileName)
    }
}

private fun plainText(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    return raw.replace(Regex("§."), "").replace('\n', ' ').trim()
}

private fun pickFile(extensions: List<String>): String? {
    val dialog = FileDialog(null as Frame?, I18n.t("common.browse"), FileDialog.LOAD)
    if (extensions.isNotEmpty()) {
        dialog.filenameFilter = java.io.FilenameFilter { _, fileName ->
            val lower = fileName.lowercase()
            extensions.any { lower.endsWith(it) }
        }
    }
    dialog.isVisible = true
    if (dialog.file == null) return null
    return java.io.File(dialog.directory, dialog.file).absolutePath
}

private fun pickDirectory(): String? {
    val chooser = javax.swing.JFileChooser()
    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = I18n.t("version_settings.path")
    if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile?.absolutePath
}
