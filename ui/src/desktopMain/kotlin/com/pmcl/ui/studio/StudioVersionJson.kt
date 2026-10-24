package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** version.json 超过这个大小就不做结构化解析，直接给只读原文。 */
private const val MAX_VERSION_JSON_BYTES = 4L * 1024 * 1024

/**
 * 可编辑字段。base 是原始解析结果，保存时以它为基底只覆盖改过的键，
 * 这样 libraries / downloads / logging 等没动的部分原样保留。
 */
private data class VersionEdit(
    val base: JsonObject,
    val type: String,
    val inheritsFrom: String,
    val jar: String,
    val mainClass: String,
    val assets: String,
    val assetIndexId: String,
    val javaMajor: String,
    val jvmArgs: List<String>,
    val minecraftArguments: String,
)

/** 只读事实，加载时算一次。 */
private data class VersionFacts(
    val id: String,
    val libraries: Int,
    val hasDownloads: Boolean,
    val minimumLauncherVersion: Int,
    val time: String,
    val releaseTime: String,
    val hasArgumentsJvm: Boolean,
    val jvmRuleEntries: Int,
    val hasMinecraftArguments: Boolean,
)

private fun obj(o: JsonObject?, key: String): JsonObject? =
    o?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun str(o: JsonObject?, key: String): String =
    o?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: ""

private fun intOf(o: JsonObject?, key: String): Int =
    o?.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 0

private fun parseVersion(text: String): Pair<VersionEdit, VersionFacts>? {
    val root = runCatching { JsonParser.parseString(text) }.getOrNull()
    if (root == null || !root.isJsonObject) return null
    val base = root.asJsonObject
    val arguments = obj(base, "arguments")
    val jvm = arguments?.get("jvm")?.takeIf { it.isJsonArray }?.asJsonArray
    val strings = ArrayList<String>()
    var ruleEntries = 0
    jvm?.forEach { el ->
        if (el.isJsonPrimitive && el.asJsonPrimitive.isString) strings.add(el.asString) else ruleEntries++
    }
    val assetIndex = obj(base, "assetIndex")
    val javaVersion = obj(base, "javaVersion")
    val edit = VersionEdit(
        base = base,
        type = str(base, "type"),
        inheritsFrom = str(base, "inheritsFrom"),
        jar = str(base, "jar"),
        mainClass = str(base, "mainClass"),
        assets = str(base, "assets"),
        assetIndexId = str(assetIndex, "id"),
        javaMajor = intOf(javaVersion, "majorVersion").takeIf { it > 0 }?.toString() ?: "",
        jvmArgs = strings,
        minecraftArguments = str(base, "minecraftArguments"),
    )
    val facts = VersionFacts(
        id = str(base, "id"),
        libraries = base.get("libraries")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0,
        hasDownloads = base.get("downloads")?.isJsonObject == true,
        minimumLauncherVersion = intOf(base, "minimumLauncherVersion"),
        time = str(base, "time"),
        releaseTime = str(base, "releaseTime"),
        hasArgumentsJvm = jvm != null,
        jvmRuleEntries = ruleEntries,
        hasMinecraftArguments = base.has("minecraftArguments"),
    )
    return edit to facts
}

/** 把编辑结果写回 base 的副本；空白值对可选字段意味着删除该键。 */
private fun applyEdit(edit: VersionEdit): JsonObject {
    val out = edit.base.deepCopy().asJsonObject
    fun putOrRemove(key: String, value: String) {
        if (value.isBlank()) out.remove(key) else out.addProperty(key, value)
    }
    putOrRemove("type", edit.type)
    putOrRemove("inheritsFrom", edit.inheritsFrom)
    putOrRemove("jar", edit.jar)
    putOrRemove("mainClass", edit.mainClass)
    putOrRemove("assets", edit.assets)
    putOrRemove("minecraftArguments", edit.minecraftArguments)

    val assetIndex = obj(out, "assetIndex")
    if (assetIndex != null) {
        if (edit.assetIndexId.isBlank()) assetIndex.remove("id") else assetIndex.addProperty("id", edit.assetIndexId)
    }
    val major = edit.javaMajor.trim().toIntOrNull()
    if (major != null && major > 0) {
        val javaVersion = obj(out, "javaVersion") ?: JsonObject().also { out.add("javaVersion", it) }
        javaVersion.addProperty("majorVersion", major)
    }

    val arguments = obj(out, "arguments")
    val jvm = arguments?.get("jvm")?.takeIf { it.isJsonArray }?.asJsonArray
    if (jvm != null) {
        // 按位置替换字符串项，带 rules 的对象项原位保留，多出来的追加到末尾
        val rebuilt = JsonArray()
        var k = 0
        for (el in jvm) {
            if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
                if (k < edit.jvmArgs.size) rebuilt.add(JsonPrimitive(edit.jvmArgs[k]))
                k++
            } else {
                rebuilt.add(el)
            }
        }
        for (i in k until edit.jvmArgs.size) rebuilt.add(JsonPrimitive(edit.jvmArgs[i]))
        arguments?.add("jvm", rebuilt)
    }
    return out
}

/** 保存前体检。error 挡住保存，warn 只提示。 */
private data class VersionIssue(val error: Boolean, val text: String)

private fun validateVersion(edit: VersionEdit, versionsDir: Path?): List<VersionIssue> {
    val out = ArrayList<VersionIssue>()
    if (edit.mainClass.isBlank()) out += VersionIssue(true, I18n.t("studio.vjson_err_mainclass"))
    if (edit.inheritsFrom.isNotBlank()) {
        val parent = versionsDir?.resolve(edit.inheritsFrom)?.resolve(edit.inheritsFrom + ".json")
        if (parent == null || !Files.isRegularFile(parent)) {
            out += VersionIssue(true, I18n.t("studio.vjson_err_inherits", edit.inheritsFrom))
        }
    }
    if (edit.type.isNotBlank() && edit.type !in setOf("release", "snapshot", "old_beta", "old_alpha")) {
        out += VersionIssue(false, I18n.t("studio.vjson_warn_type", edit.type))
    }
    if (edit.javaMajor.isNotBlank() && (edit.javaMajor.toIntOrNull() ?: 0) <= 0) {
        out += VersionIssue(false, I18n.t("studio.vjson_warn_java"))
    }
    if (edit.assets.isBlank()) out += VersionIssue(false, I18n.t("studio.vjson_warn_assets"))
    if (edit.jvmArgs.any { it.count { c -> c == '"' } % 2 != 0 }) {
        out += VersionIssue(false, I18n.t("studio.vjson_warn_quote"))
    }
    return out
}

/**
 * version.json 结构化编辑器：分区表单编辑常用字段，其余键（libraries/downloads/logging…）
 * 保存时原样保留。保存会按 Gson 的两格缩进重排整个文件，键顺序不变。
 */
@Composable
internal fun VersionJsonStructured(vm: LauncherViewModel, versionId: String) {
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var rawMode by rememberSaveable(versionId) { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<VersionIssue?>(null) }

    val loaded by produceState<Triple<Path, String, Pair<VersionEdit, VersionFacts>?>?>(null, versionId, reload) {
        value = withContext(Dispatchers.IO) {
            val file = vm.versionJsonOf(versionId) ?: return@withContext null
            val text = try {
                if (Files.size(file) > MAX_VERSION_JSON_BYTES) null
                else String(Files.readAllBytes(file), StandardCharsets.UTF_8)
            } catch (_: Throwable) {
                null
            } ?: return@withContext null
            Triple(file, text, parseVersion(text))
        }
    }

    var edit by remember { mutableStateOf<VersionEdit?>(null) }
    // 加载完成后填进编辑器；用户改过以后不再覆盖，避免打字打到一半被冲掉
    androidx.compose.runtime.LaunchedEffect(loaded) {
        if (!dirty) {
            edit = loaded?.third?.first
            message = null
        }
    }

    val data = loaded
    if (data == null) {
        Box(Modifier.fillMaxSize().background(p.editor)) {
            CenterNote(I18n.t("studio.loading"))
        }
        return
    }
    val (file, text, parsed) = data
    if (parsed == null) {
        // 解析不了就退回只读原文，至少还能看
        Column(Modifier.fillMaxSize().background(p.editor)) {
            Row(
                Modifier.fillMaxWidth().height(40.dp).background(p.panelAlt),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.ErrorOutline, null, tint = p.error, modifier = Modifier.size(15.dp).padding(start = 0.dp))
                Spacer(Modifier.width(8.dp))
                FlatText(I18n.t("studio.vjson_parse_failed"), color = p.error, modifier = Modifier.weight(1f))
                Block("", onClick = { reload++ }, icon = Icons.Filled.Refresh, height = 40.dp)
            }
            LineViewer(text, Syntax.JSON, Modifier.weight(1f).fillMaxWidth(), banner = I18n.t("studio.read_only"))
        }
        return
    }
    val facts = parsed.second
    val current = edit ?: parsed.first

    fun mutate(block: (VersionEdit) -> VersionEdit) {
        edit = block(current)
        dirty = true
    }

    fun save() {
        val issues = validateVersion(current, vm.config.versionsDir)
        val blocking = issues.firstOrNull { it.error }
        if (blocking != null) {
            message = blocking
            return
        }
        message = issues.firstOrNull()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val json = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                    writeAtomically(file, json.toJson(applyEdit(current)) + "\n")
                }
            }
            if (result.isSuccess) {
                dirty = false
                reload++
            } else {
                message = VersionIssue(true, I18n.t("studio.vjson_save_failed", result.exceptionOrNull()?.message ?: ""))
            }
        }
    }

    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(40.dp).background(p.panelAlt), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(12.dp))
            FlatText("version.json · " + versionId, weight = FontWeight.Medium)
            if (dirty) FlatText("  ●", color = p.warn)
            Spacer(Modifier.weight(1f))
            Block(
                if (rawMode) I18n.t("studio.vjson_form") else I18n.t("studio.vjson_raw"),
                onClick = { rawMode = !rawMode },
                icon = Icons.Filled.Code,
                height = 40.dp
            )
            Block(I18n.t("studio.vjson_validate"), onClick = {
                val issues = validateVersion(current, vm.config.versionsDir)
                message = issues.firstOrNull() ?: VersionIssue(false, I18n.t("studio.vjson_ok"))
            }, icon = Icons.Filled.Check, height = 40.dp)
            Block("", onClick = {
                edit = parsed.first
                dirty = false
                message = null
            }, icon = Icons.Filled.Refresh, enabled = dirty, height = 40.dp)
            Block(I18n.t("studio.save"), onClick = { save() }, icon = Icons.Filled.Save, fill = Tiles.Navy, enabled = dirty, height = 40.dp)
            Block("", onClick = { vm.openDir(file.parent?.toFile() ?: file.toFile()) }, icon = Icons.Filled.FolderOpen, height = 40.dp)
        }

        message?.let { issue ->
            Row(
                Modifier.fillMaxWidth().background(if (issue.error) p.error.copy(alpha = 0.14f) else p.warn.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (issue.error) Icons.Filled.ErrorOutline else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (issue.error) p.error else p.warn,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(8.dp))
                FlatText(issue.text, color = if (issue.error) p.error else p.warn, size = 11, lines = 3, modifier = Modifier.weight(1f))
                Block("", onClick = { message = null }, icon = Icons.Filled.Close, height = 24.dp)
            }
        }

        if (rawMode) {
            // 每次重组都重排整个 JSON 会很卡，按当前编辑值缓存
            val preview = remember(current, dirty, text) {
                if (dirty) GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(applyEdit(current)) else text
            }
            LineViewer(
                preview,
                Syntax.JSON,
                Modifier.weight(1f).fillMaxWidth(),
                banner = (if (dirty) I18n.t("studio.vjson_raw_dirty") else I18n.t("studio.read_only")) + " · " + file.fileName
            )
            return@Column
        }

        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            LabelBar(I18n.t("studio.vjson_section_basic"), Tiles.Navy)
            VersionFieldRow(I18n.t("studio.vjson_id"), facts.id, editable = false, index = 0)
            VersionFieldRow(I18n.t("studio.vjson_type"), current.type, true, 1, hint = "release / snapshot / old_beta / old_alpha") {
                mutate { e -> e.copy(type = it) }
            }
            VersionFieldRow(I18n.t("studio.vjson_inherits"), current.inheritsFrom, true, 2, hint = I18n.t("studio.vjson_inherits_hint")) {
                mutate { e -> e.copy(inheritsFrom = it) }
            }
            VersionFieldRow(I18n.t("studio.vjson_jar"), current.jar, true, 3, hint = I18n.t("studio.vjson_jar_hint")) {
                mutate { e -> e.copy(jar = it) }
            }
            VersionFieldRow(I18n.t("studio.vjson_mainclass"), current.mainClass, true, 4) {
                mutate { e -> e.copy(mainClass = it) }
            }

            LabelBar(I18n.t("studio.vjson_section_assets"), Tiles.Sky)
            VersionFieldRow(I18n.t("studio.vjson_assets"), current.assets, true, 5) { mutate { e -> e.copy(assets = it) } }
            VersionFieldRow(I18n.t("studio.vjson_assetindex"), current.assetIndexId, true, 6) { mutate { e -> e.copy(assetIndexId = it) } }
            VersionFieldRow(I18n.t("studio.vjson_java"), current.javaMajor, true, 7, hint = I18n.t("studio.vjson_java_hint")) {
                mutate { e -> e.copy(javaMajor = it) }
            }
            VersionFieldRow(
                I18n.t("studio.vjson_min_launcher"),
                if (facts.minimumLauncherVersion > 0) facts.minimumLauncherVersion.toString() else "—",
                editable = false,
                index = 8
            )

            LabelBar(I18n.t("studio.vjson_section_jvm"), Tiles.Teal)
            if (!facts.hasArgumentsJvm) {
                FlatText(
                    I18n.t("studio.vjson_no_arguments_jvm"),
                    color = p.muted,
                    size = 11,
                    lines = 3,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            } else {
                if (facts.jvmRuleEntries > 0) {
                    FlatText(
                        I18n.t("studio.vjson_jvm_rules", facts.jvmRuleEntries.toString()),
                        color = p.muted,
                        size = 11,
                        lines = 3,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                current.jvmArgs.forEachIndexed { i, arg ->
                    Row(
                        Modifier.fillMaxWidth().background(if (i % 2 == 0) p.editor else p.panel)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FlatText((i + 1).toString(), color = p.gutter, size = 11, modifier = Modifier.width(26.dp))
                        FlatField(
                            arg,
                            { value -> mutate { e -> e.copy(jvmArgs = e.jvmArgs.toMutableList().also { it[i] = value }) } },
                            "",
                            Modifier.weight(1f),
                            color = p.field
                        )
                        Spacer(Modifier.width(6.dp))
                        Block(
                            "",
                            onClick = { mutate { e -> e.copy(jvmArgs = e.jvmArgs.filterIndexed { k, _ -> k != i }) } },
                            icon = Icons.Filled.Delete,
                            fill = Tiles.Red,
                            height = 28.dp
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Block(
                        I18n.t("studio.vjson_add_arg"),
                        onClick = { mutate { e -> e.copy(jvmArgs = e.jvmArgs + "") } },
                        icon = Icons.Filled.Add,
                        fill = Tiles.Teal,
                        height = 28.dp
                    )
                }
            }

            if (facts.hasMinecraftArguments) {
                LabelBar(I18n.t("studio.vjson_section_game_args"), Tiles.Plum)
                FlatText(
                    I18n.t("studio.vjson_legacy_hint"),
                    color = p.muted,
                    size = 11,
                    lines = 3,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlatField(
                        current.minecraftArguments,
                        { value -> mutate { e -> e.copy(minecraftArguments = value) } },
                        "",
                        Modifier.weight(1f),
                        color = p.field
                    )
                }
            }

            LabelBar(I18n.t("studio.vjson_section_facts"), Tiles.Slate)
            VersionFieldRow(I18n.t("studio.vjson_libraries"), facts.libraries.toString(), editable = false, index = 0)
            VersionFieldRow(
                I18n.t("studio.vjson_downloads"),
                if (facts.hasDownloads) I18n.t("studio.vjson_yes") else I18n.t("studio.vjson_no"),
                editable = false,
                index = 1
            )
            VersionFieldRow(I18n.t("studio.vjson_time"), facts.time.ifBlank { "—" }, editable = false, index = 2)
            VersionFieldRow(I18n.t("studio.vjson_release_time"), facts.releaseTime.ifBlank { "—" }, editable = false, index = 3)

            FlatText(
                I18n.t("studio.vjson_save_note"),
                color = p.muted,
                size = 11,
                lines = 4,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp)
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** 一行「标签 + 输入框」，只读时右边显示灰色文本。 */
@Composable
private fun VersionFieldRow(
    label: String,
    value: String,
    editable: Boolean,
    index: Int,
    hint: String = "",
    onChange: (String) -> Unit = {},
) {
    val p = LocalStudio.current
    Column(
        Modifier.fillMaxWidth().entrance(index).background(if (index % 2 == 0) p.editor else p.panel)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlatText(label, color = p.muted, size = 11, modifier = Modifier.width(132.dp))
            if (editable) {
                FlatField(value, onChange, "", Modifier.weight(1f), color = p.field)
            } else {
                FlatText(value, color = p.text, size = 12, lines = 2, modifier = Modifier.weight(1f))
            }
        }
        if (hint.isNotEmpty()) {
            FlatText(hint, color = p.muted.copy(alpha = 0.8f), size = 10, lines = 2, modifier = Modifier.padding(start = 132.dp, top = 3.dp))
        }
    }
}
