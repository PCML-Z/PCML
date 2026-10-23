package com.pmcl.ui.studio

import com.pmcl.core.i18n.I18n
import com.pmcl.core.instance.InstanceInfo
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

internal enum class TargetKind { VERSION, INSTANCE }

internal data class StudioTarget(
    val kind: TargetKind,
    val id: String,
    val name: String,
    val versionId: String,
    val detail: String,
)

internal sealed interface StudioTab {
    val key: String

    data class Overview(val target: StudioTarget) : StudioTab {
        override val key: String get() = "overview:${target.kind}:${target.id}"
    }

    data class VersionJson(val versionId: String) : StudioTab {
        override val key: String get() = "json:$versionId"
    }

    data class Content(val target: StudioTarget, val kind: ContentKind) : StudioTab {
        override val key: String get() = "content:${target.kind}:${target.id}"
    }

    data class FileTab(val root: Path, val file: Path, val readOnly: Boolean = false) : StudioTab {
        override val key: String get() = "file:$file"
    }

    data class Storage(val target: StudioTarget) : StudioTab {
        override val key: String get() = "storage:${target.kind}:${target.id}"
    }

    data class Settings(val category: String?) : StudioTab {
        override val key: String get() = "settings"
    }

    data object Terminal : StudioTab {
        override val key: String get() = "terminal"
    }

    /** Studio 满意度调查。 */
    data object Survey : StudioTab {
        override val key: String get() = "survey"
    }

    /** 对当前目标做健康检查（Java、完整性、崩溃、模组、配置）。 */
    data class Diagnose(val target: StudioTarget) : StudioTab {
        override val key: String get() = "diagnose:${target.kind}:${target.id}"
    }

    /** 对比两个目标的模组列表，并可单向复制。 */
    data class Compare(val target: StudioTarget, val otherKey: String? = null) : StudioTab {
        override val key: String get() = "compare:${target.kind}:${target.id}"
    }

    /** 游戏目录里的配置文件入口。 */
    data class Configs(val target: StudioTarget) : StudioTab {
        override val key: String get() = "configs:${target.kind}:${target.id}"
    }

    /** 打开的本地 Java / Gradle / Maven 工程。 */
    data class CodeProject(val root: String) : StudioTab {
        override val key: String get() = "code:$root"
    }

    /** Word / Excel / PPT / 思维导图。 */
    data class Document(val file: String) : StudioTab {
        override val key: String get() = "doc:$file"
    }

    data class Module(val moduleId: String, val sectionId: String?) : StudioTab {
        override val key: String get() = "module:$moduleId"
    }

    /** 实时日志查看器：tail 目标游戏目录的 logs/latest.log。 */
    data class LiveLog(val target: StudioTarget) : StudioTab {
        override val key: String get() = "livelog:${target.kind}:${target.id}"
    }

    /** 崩溃分析器：扫描 crash-reports/ 并给出修复建议。 */
    data class Crash(val target: StudioTarget) : StudioTab {
        override val key: String get() = "crash:${target.kind}:${target.id}"
    }

    /** 模组更新检查（Modrinth / CurseForge）。 */
    data class ModUpdates(val target: StudioTarget) : StudioTab {
        override val key: String get() = "updates:${target.kind}:${target.id}"
    }

    /** options.txt 图形化编辑。 */
    data class Options(val target: StudioTarget) : StudioTab {
        override val key: String get() = "options:${target.kind}:${target.id}"
    }

    /** 把目标实例/版本导出成可分享的压缩包。 */
    data class Export(val target: StudioTarget) : StudioTab {
        override val key: String get() = "export:${target.kind}:${target.id}"
    }
}

// —— 标签页会话恢复编解码 ——
// 每个字段 URL 编码后用 | 连接；目标对象展开成 kind/id/name/versionId/detail 五个字段。
// 返回 null 表示这种标签不恢复（问卷等一次性的）。

private fun enc(s: String): String = java.net.URLEncoder.encode(s, StandardCharsets.UTF_8)
private fun dec(s: String): String = java.net.URLDecoder.decode(s, StandardCharsets.UTF_8)

private fun encTarget(t: StudioTarget): String =
    listOf(t.kind.name, t.id, t.name, t.versionId, t.detail).joinToString("|") { enc(it) }

private fun decTarget(fields: List<String>, from: Int): StudioTarget? {
    if (fields.size < from + 5) return null
    val kind = runCatching { TargetKind.valueOf(dec(fields[from])) }.getOrNull() ?: return null
    return StudioTarget(kind, dec(fields[from + 1]), dec(fields[from + 2]), dec(fields[from + 3]), dec(fields[from + 4]))
}

internal fun encodeTab(tab: StudioTab): String? = when (tab) {
    is StudioTab.Overview -> "overview|" + encTarget(tab.target)
    is StudioTab.VersionJson -> "json|" + enc(tab.versionId)
    is StudioTab.Content -> "content|" + encTarget(tab.target) + "|" + tab.kind.name
    is StudioTab.FileTab -> "file|" + enc(tab.root.toString()) + "|" + enc(tab.file.toString()) + "|" + (if (tab.readOnly) "1" else "0")
    is StudioTab.Storage -> "storage|" + encTarget(tab.target)
    is StudioTab.Settings -> "settings|" + enc(tab.category.orEmpty())
    is StudioTab.Terminal -> "terminal"
    is StudioTab.Survey -> null
    is StudioTab.Diagnose -> "diagnose|" + encTarget(tab.target)
    is StudioTab.Compare -> "compare|" + encTarget(tab.target) + "|" + enc(tab.otherKey.orEmpty())
    is StudioTab.Configs -> "configs|" + encTarget(tab.target)
    is StudioTab.CodeProject -> "code|" + enc(tab.root)
    is StudioTab.Document -> "doc|" + enc(tab.file)
    is StudioTab.Module -> "module|" + enc(tab.moduleId) + "|" + enc(tab.sectionId.orEmpty())
    is StudioTab.LiveLog -> "livelog|" + encTarget(tab.target)
    is StudioTab.Crash -> "crash|" + encTarget(tab.target)
    is StudioTab.ModUpdates -> "updates|" + encTarget(tab.target)
    is StudioTab.Options -> "options|" + encTarget(tab.target)
    // 导出是一次性流程（进度、结果都不该跨会话复原），和问卷一样不恢复
    is StudioTab.Export -> null
}

internal fun decodeTab(line: String): StudioTab? {
    val f = line.split("|")
    if (f.isEmpty()) return null
    return runCatching {
        when (f[0]) {
            "overview" -> decTarget(f, 1)?.let { StudioTab.Overview(it) }
            "json" -> StudioTab.VersionJson(dec(f[1]))
            "content" -> decTarget(f, 1)?.let { t ->
                StudioTab.Content(t, runCatching { ContentKind.valueOf(dec(f[6])) }.getOrNull() ?: ContentKind.MODS)
            }
            "file" -> StudioTab.FileTab(Path.of(dec(f[1])), Path.of(dec(f[2])), f.getOrNull(3) == "1")
            "storage" -> decTarget(f, 1)?.let { StudioTab.Storage(it) }
            "settings" -> StudioTab.Settings(dec(f[1]).ifBlank { null })
            "terminal" -> StudioTab.Terminal
            "diagnose" -> decTarget(f, 1)?.let { StudioTab.Diagnose(it) }
            "compare" -> decTarget(f, 1)?.let { StudioTab.Compare(it, dec(f.getOrNull(6).orEmpty()).ifBlank { null }) }
            "configs" -> decTarget(f, 1)?.let { StudioTab.Configs(it) }
            "code" -> StudioTab.CodeProject(dec(f[1]))
            "doc" -> StudioTab.Document(dec(f[1]))
            "module" -> StudioTab.Module(dec(f[1]), dec(f.getOrNull(2).orEmpty()).ifBlank { null })
            "livelog" -> decTarget(f, 1)?.let { StudioTab.LiveLog(it) }
            "crash" -> decTarget(f, 1)?.let { StudioTab.Crash(it) }
            "updates" -> decTarget(f, 1)?.let { StudioTab.ModUpdates(it) }
            "options" -> decTarget(f, 1)?.let { StudioTab.Options(it) }
            else -> null
        }
    }.getOrNull()
}

internal enum class ContentKind { MODS, RESOURCE_PACKS, SHADERS, WORLDS, SCREENSHOTS }

internal enum class LeftTool { PROJECT, FILES, CODE, DOCS, MODULES }

internal enum class BottomTool { RUN, SEARCH, TERMINAL, PROBLEMS }

internal data class ModFile(val path: Path, val name: String, val size: Long, val enabled: Boolean)

internal data class TargetFacts(
    val gameDir: Path?,
    val jsonPath: Path?,
    val inheritsFrom: String,
    val mainClass: String,
    val assets: String,
    val requiredJava: Int,
    val mods: Int,
    val worlds: Int,
    val resourcePacks: Int,
    val shaderPacks: Int,
    val screenshots: Int,
    val lastPlayed: Long,
    val loader: String,
    val playSeconds: Long = 0,
    val latestLog: Path? = null,
    val latestLogSize: Long = 0,
)

internal const val MAX_EDIT_BYTES = 512 * 1024
internal const val MAX_VIEW_BYTES = 2 * 1024 * 1024
internal const val MAX_CHILDREN = 600

private val textExtensions = setOf(
    "txt", "json", "json5", "toml", "cfg", "conf", "properties", "yml", "yaml", "ini",
    "snbt", "mcmeta", "js", "zs", "lang", "log", "md", "csv", "xml", "mcfunction",
    "java", "kt", "kts", "gradle", "groovy", "pom"
)

private val refusedNames = setOf(
    "accounts.json", "identity.json", "friends.json", "cursor-agent.json", "preferences.json",
    "launcher_accounts.json", "launcher_profiles.json"
)

internal fun targetsOf(
    versions: List<com.pmcl.core.version.VersionManager.LocalVersionInfo>,
    instances: List<InstanceInfo>,
): Pair<List<StudioTarget>, List<StudioTarget>> {
    val v = versions.sortedBy { it.id.lowercase(Locale.ROOT) }.map { info ->
        StudioTarget(TargetKind.VERSION, info.id, info.id, info.id, info.inheritsFrom ?: "")
    }
    val i = instances.sortedBy { it.name.lowercase(Locale.ROOT) }.map { info ->
        StudioTarget(TargetKind.INSTANCE, info.instanceId, info.name, info.baseVersionId ?: "", info.loader ?: "")
    }
    return v to i
}

internal fun LauncherViewModel.gameDirOf(target: StudioTarget): Path? = try {
    when (target.kind) {
        TargetKind.INSTANCE -> instances.value.firstOrNull { it.instanceId == target.id }?.instanceDir
        TargetKind.VERSION -> core.profileBuilder().resolveGameDirectory(target.versionId)
    }?.toAbsolutePath()?.normalize()
} catch (_: Throwable) {
    null
}

internal fun LauncherViewModel.versionJsonOf(versionId: String): Path? {
    if (versionId.isBlank() || versionId.contains('/') || versionId.contains('\\') || versionId.contains("..")) return null
    val file = config.versionsDir.resolve(versionId).resolve("$versionId.json")
    return if (Files.isRegularFile(file)) file else null
}

internal fun LauncherViewModel.factsOf(target: StudioTarget): TargetFacts {
    val dir = gameDirOf(target)
    val info = localVersionInfos.value.firstOrNull { it.id == target.versionId }
    val instance = if (target.kind == TargetKind.INSTANCE) instances.value.firstOrNull { it.instanceId == target.id } else null
    val required = try { core.profileBuilder().getRequiredJavaVersion(target.versionId) } catch (_: Throwable) { 0 }
    val latestLog = dir?.resolve("logs")?.resolve("latest.log")?.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
    return TargetFacts(
        gameDir = dir,
        jsonPath = versionJsonOf(target.versionId),
        inheritsFrom = info?.inheritsFrom ?: "",
        mainClass = info?.mainClass ?: "",
        assets = info?.assets ?: "",
        requiredJava = required,
        mods = countChildren(dir?.resolve("mods")) { it.endsWith(".jar") },
        worlds = countChildren(dir?.resolve("saves")) { true },
        resourcePacks = countChildren(dir?.resolve("resourcepacks")) { true },
        shaderPacks = countChildren(dir?.resolve("shaderpacks")) { true },
        screenshots = countChildren(dir?.resolve("screenshots")) { it.endsWith(".png") },
        lastPlayed = instance?.lastPlayedAt?.takeIf { it > 0 } ?: (lastPlayedTimes.value[target.versionId] ?: 0L),
        loader = instance?.loader?.takeIf { it.isNotBlank() } ?: guessLoader(target.versionId, info?.inheritsFrom),
        playSeconds = instance?.totalPlayTimeSeconds ?: 0L,
        latestLog = latestLog,
        latestLogSize = latestLog?.let { try { Files.size(it) } catch (_: Throwable) { 0L } } ?: 0L,
    )
}

private fun guessLoader(versionId: String, inherits: String?): String {
    val id = versionId.lowercase(Locale.ROOT)
    return when {
        id.contains("neoforge") -> "NeoForge"
        id.contains("forge") -> "Forge"
        id.contains("quilt") -> "Quilt"
        id.contains("fabric") -> "Fabric"
        id.contains("forbric") -> "Forbric"
        !inherits.isNullOrBlank() -> inherits
        else -> "Vanilla"
    }
}

private fun countChildren(dir: Path?, accept: (String) -> Boolean): Int {
    if (dir == null || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return 0
    return try {
        Files.list(dir).use { stream ->
            stream.filter { accept(it.fileName.toString().lowercase(Locale.ROOT)) }.count().toInt()
        }
    } catch (_: Throwable) {
        0
    }
}

internal fun listChildren(dir: Path): List<Path> = try {
    Files.list(dir).use { stream ->
        stream.filter { !Files.isSymbolicLink(it) }
            .limit(MAX_CHILDREN.toLong())
            .toList()
            .sortedWith(compareBy<Path>({ !Files.isDirectory(it) }, { it.fileName.toString().lowercase(Locale.ROOT) }))
    }
} catch (_: Throwable) {
    emptyList()
}

internal fun listMods(dir: Path?): List<ModFile> {
    if (dir == null) return emptyList()
    val mods = dir.resolve("mods")
    if (!Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    return listChildren(mods).mapNotNull { path ->
        val name = path.fileName.toString()
        val lower = name.lowercase(Locale.ROOT)
        val enabled = lower.endsWith(".jar")
        if (!enabled && !lower.endsWith(".jar.disabled")) return@mapNotNull null
        val size = try { Files.size(path) } catch (_: Throwable) { 0L }
        ModFile(path, name, size, enabled)
    }
}

internal fun toggleMod(mod: ModFile): Path {
    val dir = mod.path.parent ?: throw IllegalStateException("no parent")
    if (Files.isSymbolicLink(mod.path)) throw IllegalStateException("symlink")
    val target = if (mod.enabled) dir.resolve(mod.name + ".disabled") else dir.resolve(mod.name.removeSuffix(".disabled"))
    if (Files.exists(target)) throw IllegalStateException("exists")
    return Files.move(mod.path, target)
}

internal fun setModEnabled(mod: ModFile, enabled: Boolean): Path? {
    if (mod.enabled == enabled) return null
    return toggleMod(mod)
}

internal fun deleteMod(mod: ModFile) {
    if (Files.isSymbolicLink(mod.path)) throw IllegalStateException("symlink")
    Files.deleteIfExists(mod.path)
}

/** 把源 jar 复制到目标 mods 目录；同名已存在则跳过。返回是否写入了新文件。 */
internal fun copyModInto(source: Path, destGameDir: Path): Boolean {
    if (!Files.isRegularFile(source) || Files.isSymbolicLink(source)) return false
    val mods = destGameDir.resolve("mods")
    Files.createDirectories(mods)
    val name = source.fileName.toString()
    val dest = mods.resolve(name)
    val base = name.removeSuffix(".disabled")
    if (Files.exists(dest) || Files.exists(mods.resolve(base)) || Files.exists(mods.resolve("$base.disabled"))) return false
    Files.copy(source, dest)
    return true
}

internal fun modBaseName(name: String): String = name.removeSuffix(".disabled")

internal data class ModCompareRow(
    val key: String,
    val left: ModFile?,
    val right: ModFile?,
) {
    val onlyLeft: Boolean get() = left != null && right == null
    val onlyRight: Boolean get() = left == null && right != null
    val both: Boolean get() = left != null && right != null
    val sizeDiff: Boolean get() = both && left!!.size != right!!.size
}

internal fun compareModLists(left: List<ModFile>, right: List<ModFile>): List<ModCompareRow> {
    val l = left.associateBy { modBaseName(it.name).lowercase(Locale.ROOT) }
    val r = right.associateBy { modBaseName(it.name).lowercase(Locale.ROOT) }
    return (l.keys + r.keys).sorted().map { key ->
        ModCompareRow(key, l[key], r[key])
    }
}

internal data class ConfigEntry(
    val path: Path,
    val label: String,
    val group: String,
    val size: Long,
    val modified: Long,
    val editable: Boolean,
)

internal fun listConfigEntries(gameDir: Path?): List<ConfigEntry> {
    if (gameDir == null || !Files.isDirectory(gameDir, LinkOption.NOFOLLOW_LINKS)) return emptyList()
    val out = ArrayList<ConfigEntry>()
    fun add(rel: String, group: String, label: String? = null) {
        val file = gameDir.resolve(rel)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return
        val size = try { Files.size(file) } catch (_: Throwable) { 0L }
        val modified = try { Files.getLastModifiedTime(file).toMillis() } catch (_: Throwable) { 0L }
        out += ConfigEntry(file, label ?: file.fileName.toString(), group, size, modified, isTextFile(file) && !isRefused(file))
    }
    add("options.txt", "game")
    add("optionsof.txt", "game")
    add("optionsshaders.txt", "game")
    add("servers.dat", "game")
    add("usercache.json", "game")
    add("usernamecache.json", "game")
    add("launcher_profiles.json", "game")
    add("config/iris.properties", "shaders")
    add("config/oculus.properties", "shaders")
    val config = gameDir.resolve("config")
    if (Files.isDirectory(config, LinkOption.NOFOLLOW_LINKS)) {
        try {
            Files.list(config).use { stream ->
                stream.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
                    .limit(200)
                    .forEach { file ->
                        val name = file.fileName.toString().lowercase(Locale.ROOT)
                        if (name.endsWith(".toml") || name.endsWith(".json") || name.endsWith(".cfg") ||
                            name.endsWith(".properties") || name.endsWith(".txt") || name.endsWith(".yml") ||
                            name.endsWith(".yaml") || name.endsWith(".conf")
                        ) {
                            val size = try { Files.size(file) } catch (_: Throwable) { 0L }
                            val modified = try { Files.getLastModifiedTime(file).toMillis() } catch (_: Throwable) { 0L }
                            out += ConfigEntry(file, file.fileName.toString(), "mods", size, modified, isTextFile(file) && !isRefused(file))
                        }
                    }
            }
        } catch (_: Throwable) {
        }
    }
    return out.distinctBy { it.path }.sortedWith(compareBy({ it.group }, { it.label.lowercase(Locale.ROOT) }))
}

internal enum class DiagnoseLevel { OK, WARN, ERROR, INFO }

internal data class DiagnoseItem(
    val level: DiagnoseLevel,
    val title: String,
    val detail: String,
    val action: String? = null,
)

internal fun LauncherViewModel.diagnoseOf(target: StudioTarget): List<DiagnoseItem> {
    val items = ArrayList<DiagnoseItem>()
    val facts = factsOf(target)
    val dir = facts.gameDir
    val javas = javaInstallations.value
    val required = facts.requiredJava
    if (required > 0) {
        val match = javas.count { it.majorVersion == required }
        items += if (match > 0) DiagnoseItem(
            DiagnoseLevel.OK,
            I18n.t("studio.diag_java_ok"),
            I18n.t("studio.diag_java_ok_detail", required.toString(), match.toString()),
            "java"
        ) else DiagnoseItem(
            DiagnoseLevel.ERROR,
            I18n.t("studio.diag_java_missing"),
            I18n.t("studio.diag_java_missing_detail", required.toString()),
            "java"
        )
    } else {
        items += DiagnoseItem(DiagnoseLevel.INFO, I18n.t("studio.diag_java_unknown"), I18n.t("studio.diag_java_unknown_detail"), "java")
    }
    if (facts.jsonPath == null) {
        items += DiagnoseItem(DiagnoseLevel.ERROR, I18n.t("studio.diag_json_missing"), target.versionId, "json")
    } else {
        items += DiagnoseItem(DiagnoseLevel.OK, I18n.t("studio.diag_json_ok"), facts.jsonPath.toString(), "json")
    }
    if (dir == null) {
        items += DiagnoseItem(DiagnoseLevel.ERROR, I18n.t("studio.diag_dir_missing"), "", "reveal")
    } else {
        items += DiagnoseItem(DiagnoseLevel.OK, I18n.t("studio.diag_dir_ok"), dir.toString(), "reveal")
        if (!Files.isRegularFile(dir.resolve("options.txt"))) {
            items += DiagnoseItem(DiagnoseLevel.WARN, I18n.t("studio.diag_options_missing"), I18n.t("studio.diag_options_missing_detail"), "configs")
        }
        val crashes = try {
            val crashDir = dir.resolve("crash-reports")
            if (Files.isDirectory(crashDir)) Files.list(crashDir).use { it.count().toInt() } else 0
        } catch (_: Throwable) { 0 }
        items += if (crashes == 0) DiagnoseItem(DiagnoseLevel.OK, I18n.t("studio.diag_crash_none"), "", "problems")
        else DiagnoseItem(DiagnoseLevel.WARN, I18n.t("studio.diag_crash_n", crashes.toString()), I18n.t("studio.diag_crash_detail"), "problems")
        val mods = listMods(dir)
        val disabled = mods.count { !it.enabled }
        items += DiagnoseItem(
            if (disabled > 0) DiagnoseLevel.INFO else DiagnoseLevel.OK,
            I18n.t("studio.diag_mods_n", mods.size.toString(), disabled.toString()),
            "",
            "mods"
        )
        val metas = mods.associate { modBaseName(it.name) to readModMeta(it.path) }
        val issues = inspectMods(mods, metas)
        val errors = issues.count { it.error }
        items += when {
            issues.isEmpty() -> DiagnoseItem(DiagnoseLevel.OK, I18n.t("studio.diag_mod_ok"), "", "mods")
            errors > 0 -> DiagnoseItem(DiagnoseLevel.ERROR, I18n.t("studio.diag_mod_issues", issues.size.toString()), issues.first().text, "mods")
            else -> DiagnoseItem(DiagnoseLevel.WARN, I18n.t("studio.diag_mod_issues", issues.size.toString()), issues.first().text, "mods")
        }
        val oldLogs = countOldLogs(dir)
        if (oldLogs.first > 0) {
            items += DiagnoseItem(
                DiagnoseLevel.INFO,
                I18n.t("studio.diag_old_logs", oldLogs.first.toString()),
                I18n.t("studio.diag_old_logs_detail"),
                "storage"
            )
        }
    }
    return items
}

internal fun isTextFile(path: Path): Boolean {
    val name = path.fileName?.toString()?.lowercase(Locale.ROOT) ?: return false
    val ext = name.substringAfterLast('.', "")
    return ext in textExtensions
}

internal fun isRefused(path: Path): Boolean {
    val name = path.fileName?.toString()?.lowercase(Locale.ROOT) ?: return true
    return name in refusedNames
}

internal fun insideRoot(root: Path, file: Path): Boolean {
    val r = root.toAbsolutePath().normalize()
    val f = file.toAbsolutePath().normalize()
    return f.startsWith(r) && f != r
}

internal fun readOnlyPath(root: Path, file: Path): Boolean {
    val rel = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/')
    val top = rel.substringBefore('/')
    return top == "logs" || top == "crash-reports" || rel.lowercase(Locale.ROOT).endsWith(".log")
}

internal sealed interface FileLoad {
    data class Text(val text: String, val editable: Boolean) : FileLoad
    data class TooLarge(val size: Long) : FileLoad
    data class Binary(val size: Long) : FileLoad
    data object Refused : FileLoad
    data class Failed(val message: String) : FileLoad
}

internal fun loadFile(root: Path, file: Path): FileLoad {
    if (!insideRoot(root, file) || isRefused(file) || Files.isSymbolicLink(file)) return FileLoad.Refused
    return try {
        val size = Files.size(file)
        if (!isTextFile(file)) return FileLoad.Binary(size)
        if (size > MAX_VIEW_BYTES) return FileLoad.TooLarge(size)
        val text = String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        FileLoad.Text(text, size <= MAX_EDIT_BYTES && !readOnlyPath(root, file))
    } catch (e: Throwable) {
        FileLoad.Failed(e.message ?: e.javaClass.simpleName)
    }
}

internal fun saveFile(root: Path, file: Path, text: String) {
    if (!insideRoot(root, file) || isRefused(file) || Files.isSymbolicLink(file)) throw IllegalStateException("refused")
    if (readOnlyPath(root, file) || !isTextFile(file)) throw IllegalStateException("read only")
    val bytes = text.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size > MAX_EDIT_BYTES) throw IllegalStateException("too large")
    val dir = file.parent ?: throw IllegalStateException("no parent")
    val tmp = dir.resolve(file.fileName.toString() + ".pmcl-studio.tmp")
    try {
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(tmp)
    }
}

internal fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${seconds}s"
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
}
