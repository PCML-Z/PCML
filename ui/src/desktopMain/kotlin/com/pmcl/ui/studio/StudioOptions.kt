package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.math.roundToInt

/** options.txt 里不参与结构化编辑的行（空行、异常行），原样保留在文件头部。 */
private data class OptionsDoc(
    val passthrough: List<String>,
    val values: LinkedHashMap<String, String>,
)

private fun parseOptions(text: String): OptionsDoc {
    val passthrough = ArrayList<String>()
    val values = LinkedHashMap<String, String>()
    for (raw in text.split('\n')) {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) continue
        val idx = line.indexOf(':')
        if (idx <= 0) passthrough += line
        else values[line.substring(0, idx)] = line.substring(idx + 1)
    }
    return OptionsDoc(passthrough, values)
}

private fun serializeOptions(doc: OptionsDoc): String {
    val sb = StringBuilder()
    doc.passthrough.forEach { sb.append(it).append('\n') }
    doc.values.forEach { (k, v) -> sb.append(k).append(':').append(v).append('\n') }
    return sb.toString()
}

/** 结构化控件占用过的键，剩下的进「其他」列表。 */
private val KNOWN_KEYS = setOf(
    "renderDistance", "simulationDistance", "fov", "gamma", "maxFps", "guiScale",
    "graphics", "renderClouds", "particles", "mipmapLevels", "biomeBlendRadius",
    "smoothLighting", "enableVsync", "fullscreen", "entityShadows", "showSubtitles",
    "fovEffectScale", "darknessEffectScale", "glintIntensity", "autoJump",
    "soundCategory_master", "soundCategory_music", "soundCategory_record", "soundCategory_weather",
    "soundCategory_block", "soundCategory_hostile", "soundCategory_neutral", "soundCategory_player",
    "soundCategory_ambient", "soundCategory_voice",
)

private fun optFloat(doc: OptionsDoc, key: String, fallback: Float): Float =
    doc.values[key]?.toFloatOrNull() ?: fallback

private fun optInt(doc: OptionsDoc, key: String, fallback: Int): Int =
    doc.values[key]?.toIntOrNull() ?: fallback

private fun optBool(doc: OptionsDoc, key: String, fallback: Boolean): Boolean =
    doc.values[key]?.toBooleanStrictOrNull() ?: fallback

private fun fmtFloat(v: Float): String =
    if (v == v.roundToInt().toFloat()) v.roundToInt().toString() + ".0" else String.format(Locale.ROOT, "%.2f", v)

@Composable
private fun OptRow(label: String, index: Int, valueText: String, control: @Composable () -> Unit) {
    val p = LocalStudio.current
    Row(
        Modifier.fillMaxWidth().height(44.dp).background(if (index % 2 == 0) p.editor else p.panel).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(220.dp)) {
            FlatText(label, weight = androidx.compose.ui.text.font.FontWeight.Medium)
            if (valueText.isNotEmpty()) FlatText(valueText, color = p.muted, size = 11)
        }
        Spacer(Modifier.weight(1f))
        control()
    }
}

/** 几个固定取值之间的切换块。 */
@Composable
private fun OptBlocks(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    Row {
        options.forEach { (value, label) ->
            Block(label, onClick = { onPick(value) }, selected = current == value, height = 30.dp)
        }
    }
}

/**
 * options.txt 图形化编辑：常用项（视距、画质、FOV、音量）用滑条/切换块，
 * 其余键保留原始键值编辑。保存时原子写回，未知行原样保留。
 */
@Composable
internal fun OptionsEditor(vm: LauncherViewModel, target: StudioTarget) {
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    val dir by produceState<Path?>(null, target) {
        value = withContext(Dispatchers.IO) { vm.gameDirOf(target) }
    }
    val file = dir?.resolve("options.txt")
    var reload by remember { mutableIntStateOf(0) }
    val loaded by produceState<OptionsDoc?>(null, file, reload) {
        val f = file ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            runCatching { parseOptions(String(Files.readAllBytes(f), Charsets.UTF_8)) }.getOrNull()
        }
    }
    var doc by remember(file) { mutableStateOf<OptionsDoc?>(null) }
    var dirty by remember(file) { mutableStateOf(false) }
    // 加载完成后填进编辑器；用户改过以后不再覆盖
    androidx.compose.runtime.LaunchedEffect(loaded) {
        if (!dirty) doc = loaded
    }

    fun edit(key: String, value: String) {
        val d = doc ?: return
        val copy = LinkedHashMap(d.values)
        copy[key] = value
        doc = d.copy(values = copy)
        dirty = true
    }

    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(36.dp).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.width(36.dp).fillMaxHeight().background(Tiles.Sky),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(Icons.Filled.Tune, contentDescription = null, tint = Color.White, modifier = Modifier.padding(10.dp))
            }
            FlatText("options.txt · " + target.name, weight = androidx.compose.ui.text.font.FontWeight.Medium, modifier = Modifier.padding(horizontal = 10.dp))
            if (dirty) FlatText("●", color = Tiles.Orange)
            Spacer(Modifier.weight(1f))
            Block(I18n.t("studio.opt_revert"), onClick = { dirty = false; doc = loaded }, icon = Icons.Filled.Refresh, enabled = dirty, height = 36.dp)
            Block(I18n.t("studio.opt_save"), onClick = {
                val d = doc ?: return@Block
                val f = file ?: return@Block
                val r = dir ?: return@Block
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { saveFile(r, f, serializeOptions(d)) }
                    }
                    dirty = false
                    reload++
                }
            }, icon = Icons.Filled.Save, fill = Tiles.Navy, enabled = dirty, height = 36.dp)
            Block("", onClick = { dir?.let { vm.openDir(it.toFile()) } }, icon = Icons.Filled.FolderOpen, enabled = dir != null, height = 36.dp)
        }
        val d = doc
        when {
            dir == null || (doc == null && loaded == null) -> CenterNote(I18n.t("studio.loading"))
            d == null -> CenterNote(I18n.t("studio.opt_missing"))
            else -> {
                var section by remember { mutableIntStateOf(0) }
                val other = d.values.keys.filter { it !in KNOWN_KEYS }.sorted()
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().height(34.dp).background(p.panelAlt)) {
                        Block(I18n.t("studio.opt_section_video"), onClick = { section = 0 }, selected = section == 0, height = 34.dp)
                        Block(I18n.t("studio.opt_section_audio"), onClick = { section = 1 }, selected = section == 1, height = 34.dp)
                        Block(I18n.t("studio.opt_section_other") + " (" + other.size + ")", onClick = { section = 2 }, selected = section == 2, height = 34.dp)
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        when (section) {
                            0 -> {
                                item {
                                    var n = 0
                                    Column {
                                        OptRow(I18n.t("studio.opt_render_distance"), n++, optInt(d, "renderDistance", 12).toString() + " chunks") {
                                            FlatSlider(
                                                optInt(d, "renderDistance", 12).toFloat(), 2f..32f, 29,
                                                onChange = { edit("renderDistance", it.roundToInt().toString()) }, onCommit = { edit("renderDistance", it.roundToInt().toString()) }
                                            )
                                        }
                                        OptRow(I18n.t("studio.opt_simulation_distance"), n++, optInt(d, "simulationDistance", 12).toString() + " chunks") {
                                            FlatSlider(
                                                optInt(d, "simulationDistance", 12).toFloat(), 2f..32f, 29,
                                                onChange = { edit("simulationDistance", it.roundToInt().toString()) }, onCommit = { edit("simulationDistance", it.roundToInt().toString()) }
                                            )
                                        }
                                        val fov = optFloat(d, "fov", 0.5f).coerceIn(0f, 1f)
                                        OptRow(I18n.t("studio.opt_fov"), n++, (30 + (fov * 80).roundToInt()).toString() + "°") {
                                            FlatSlider(fov, 0f..1f, 0, onChange = { edit("fov", fmtFloat(it)) }, onCommit = { edit("fov", fmtFloat(it)) })
                                        }
                                        val gamma = optFloat(d, "gamma", 0.5f).coerceIn(0f, 1f)
                                        OptRow(I18n.t("studio.opt_gamma"), n++, (gamma * 100).roundToInt().toString() + "%") {
                                            FlatSlider(gamma, 0f..1f, 0, onChange = { edit("gamma", fmtFloat(it)) }, onCommit = { edit("gamma", fmtFloat(it)) })
                                        }
                                        val fps = optInt(d, "maxFps", 120).coerceIn(10, 260)
                                        OptRow(I18n.t("studio.opt_max_fps"), n++, if (fps >= 260) I18n.t("studio.opt_unlimited") else fps.toString()) {
                                            FlatSlider(fps.toFloat(), 10f..260f, 24, onChange = { edit("maxFps", it.roundToInt().toString()) }, onCommit = { edit("maxFps", it.roundToInt().toString()) })
                                        }
                                        OptRow(I18n.t("studio.opt_gui_scale"), n++, if (optInt(d, "guiScale", 0) == 0) I18n.t("studio.opt_auto") else optInt(d, "guiScale", 0).toString()) {
                                            OptBlocks(listOf("0" to I18n.t("studio.opt_auto"), "1" to "1", "2" to "2", "3" to "3", "4" to "4"), optInt(d, "guiScale", 0).toString()) { edit("guiScale", it) }
                                        }
                                        OptRow(I18n.t("studio.opt_graphics"), n++, "") {
                                            OptBlocks(
                                                listOf(
                                                    "0" to I18n.t("studio.opt_graphics_fast"),
                                                    "1" to I18n.t("studio.opt_graphics_fancy"),
                                                    "2" to I18n.t("studio.opt_graphics_fabulous"),
                                                ),
                                                d.values["graphics"] ?: "1"
                                            ) { edit("graphics", it) }
                                        }
                                        OptRow(I18n.t("studio.opt_clouds"), n++, "") {
                                            OptBlocks(
                                                listOf(
                                                    "false" to I18n.t("studio.opt_off"),
                                                    "fast" to I18n.t("studio.opt_clouds_fast"),
                                                    "true" to I18n.t("studio.opt_graphics_fancy"),
                                                ),
                                                d.values["renderClouds"] ?: "true"
                                            ) { edit("renderClouds", it) }
                                        }
                                        OptRow(I18n.t("studio.opt_particles"), n++, "") {
                                            OptBlocks(
                                                listOf(
                                                    "0" to I18n.t("studio.opt_particles_all"),
                                                    "1" to I18n.t("studio.opt_particles_decreased"),
                                                    "2" to I18n.t("studio.opt_particles_minimal"),
                                                ),
                                                d.values["particles"] ?: "0"
                                            ) { edit("particles", it) }
                                        }
                                        OptRow(I18n.t("studio.opt_mipmap"), n++, optInt(d, "mipmapLevels", 4).toString()) {
                                            OptBlocks(listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3", "4" to "4"), optInt(d, "mipmapLevels", 4).toString()) { edit("mipmapLevels", it) }
                                        }
                                        OptRow(I18n.t("studio.opt_smooth_lighting"), n++, "") {
                                            FlatSwitch(optBool(d, "smoothLighting", true), true) { edit("smoothLighting", it.toString()) }
                                        }
                                        OptRow(I18n.t("studio.opt_vsync"), n++, "") {
                                            FlatSwitch(optBool(d, "enableVsync", true), true) { edit("enableVsync", it.toString()) }
                                        }
                                        OptRow(I18n.t("studio.opt_fullscreen"), n++, "") {
                                            FlatSwitch(optBool(d, "fullscreen", false), true) { edit("fullscreen", it.toString()) }
                                        }
                                        OptRow(I18n.t("studio.opt_entity_shadows"), n++, "") {
                                            FlatSwitch(optBool(d, "entityShadows", true), true) { edit("entityShadows", it.toString()) }
                                        }
                                        OptRow(I18n.t("studio.opt_subtitles"), n++, "") {
                                            FlatSwitch(optBool(d, "showSubtitles", false), true) { edit("showSubtitles", it.toString()) }
                                        }
                                        OptRow(I18n.t("studio.opt_auto_jump"), n++, "") {
                                            FlatSwitch(optBool(d, "autoJump", false), true) { edit("autoJump", it.toString()) }
                                        }
                                        val fovFx = optFloat(d, "fovEffectScale", 1f).coerceIn(0f, 1f)
                                        OptRow(I18n.t("studio.opt_fov_effect"), n++, (fovFx * 100).roundToInt().toString() + "%") {
                                            FlatSlider(fovFx, 0f..1f, 0, onChange = { edit("fovEffectScale", fmtFloat(it)) }, onCommit = { edit("fovEffectScale", fmtFloat(it)) })
                                        }
                                        val darkFx = optFloat(d, "darknessEffectScale", 1f).coerceIn(0f, 1f)
                                        OptRow(I18n.t("studio.opt_darkness_effect"), n++, (darkFx * 100).roundToInt().toString() + "%") {
                                            FlatSlider(darkFx, 0f..1f, 0, onChange = { edit("darknessEffectScale", fmtFloat(it)) }, onCommit = { edit("darknessEffectScale", fmtFloat(it)) })
                                        }
                                    }
                                }
                            }
                            1 -> {
                                val cats = listOf(
                                    "master" to I18n.t("studio.opt_sound_master"),
                                    "music" to I18n.t("studio.opt_sound_music"),
                                    "record" to I18n.t("studio.opt_sound_record"),
                                    "weather" to I18n.t("studio.opt_sound_weather"),
                                    "block" to I18n.t("studio.opt_sound_block"),
                                    "hostile" to I18n.t("studio.opt_sound_hostile"),
                                    "neutral" to I18n.t("studio.opt_sound_neutral"),
                                    "player" to I18n.t("studio.opt_sound_player"),
                                    "ambient" to I18n.t("studio.opt_sound_ambient"),
                                    "voice" to I18n.t("studio.opt_sound_voice"),
                                )
                                itemsIndexed(cats, key = { _, c -> c.first }) { i, (id, label) ->
                                    val key = "soundCategory_$id"
                                    val v = optFloat(d, key, 1f).coerceIn(0f, 1f)
                                    OptRow(label, i, (v * 100).roundToInt().toString() + "%") {
                                        FlatSlider(v, 0f..1f, 0, onChange = { edit(key, fmtFloat(it)) }, onCommit = { edit(key, fmtFloat(it)) })
                                    }
                                }
                            }
                            else -> {
                                itemsIndexed(other, key = { _, k -> k }) { i, key ->
                                    OptRow(key, i, "") {
                                        FlatField(
                                            d.values[key].orEmpty(),
                                            { edit(key, it) },
                                            "",
                                            Modifier.width(280.dp)
                                        )
                                    }
                                }
                                if (other.isEmpty()) {
                                    item { CenterNote(I18n.t("studio.opt_other_empty")) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
