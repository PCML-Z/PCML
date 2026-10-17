package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.TimeUnit

internal enum class CodeKind { GRADLE, MAVEN, JAVA, UNKNOWN }

internal data class CodeTask(val id: String, val label: String, val args: List<String>)

internal data class CodeInfo(
    val root: Path,
    val kind: CodeKind,
    val name: String,
    val tool: String,
    val tasks: List<CodeTask>,
    val javaFiles: Int,
    val hasWrapper: Boolean,
)

internal class CodeLine(val text: String, val err: Boolean)

internal class CodeRunner {
    val lines = mutableStateListOf<CodeLine>()
    var busy by mutableStateOf(false)
    var lastExit by mutableStateOf<Int?>(null)
    var lastCommand by mutableStateOf("")
    var job: Job? = null
    @Volatile var process: Process? = null

    fun clear() {
        lines.clear()
        lastExit = null
        lastCommand = ""
    }

    fun cancel() {
        job?.cancel()
        process?.destroyForcibly()
        process = null
    }

    fun append(text: String, err: Boolean = false) {
        lines += CodeLine(text, err)
        while (lines.size > 8000) lines.removeAt(0)
    }
}

private val skipDirs = setOf(
    ".git", ".gradle", ".idea", ".svn", "node_modules", "build", "out", "bin",
    "target", ".kotlin", "caches", "native-image"
)

internal fun pickDirectory(title: String = ""): Path? = try {
    val chooser = javax.swing.JFileChooser()
    chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    chooser.dialogTitle = title.ifBlank { I18n.t("studio.code_open") }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.toPath()?.toAbsolutePath()?.normalize()
    } else null
} catch (_: Throwable) {
    null
}

internal fun detectCodeProject(root: Path): CodeInfo {
    val abs = root.toAbsolutePath().normalize()
    val name = abs.fileName?.toString().orEmpty().ifBlank { abs.toString() }
    val win = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
    val hasGradlew = Files.isRegularFile(abs.resolve(if (win) "gradlew.bat" else "gradlew"))
    val hasGradle = hasGradlew ||
        Files.isRegularFile(abs.resolve("settings.gradle")) ||
        Files.isRegularFile(abs.resolve("settings.gradle.kts")) ||
        Files.isRegularFile(abs.resolve("build.gradle")) ||
        Files.isRegularFile(abs.resolve("build.gradle.kts"))
    val hasMvnw = Files.isRegularFile(abs.resolve(if (win) "mvnw.cmd" else "mvnw"))
    val hasMaven = hasMvnw || Files.isRegularFile(abs.resolve("pom.xml"))
    val javaFiles = countJavaFiles(abs)
    return when {
        hasGradle -> {
            val tool = if (hasGradlew) (if (win) "gradlew.bat" else "./gradlew") else "gradle"
            CodeInfo(
                abs, CodeKind.GRADLE, name, tool,
                listOf(
                    CodeTask("build", I18n.t("studio.code_task_build"), listOf("build")),
                    CodeTask("clean", I18n.t("studio.code_task_clean"), listOf("clean")),
                    CodeTask("classes", I18n.t("studio.code_task_classes"), listOf("classes")),
                    CodeTask("test", I18n.t("studio.code_task_test"), listOf("test")),
                    CodeTask("assemble", I18n.t("studio.code_task_assemble"), listOf("assemble")),
                    CodeTask("run", I18n.t("studio.code_task_run"), listOf("run")),
                    CodeTask("dependencies", I18n.t("studio.code_task_deps"), listOf("dependencies")),
                    CodeTask("tasks", I18n.t("studio.code_task_list"), listOf("tasks", "--all")),
                ),
                javaFiles, hasGradlew
            )
        }
        hasMaven -> {
            val tool = if (hasMvnw) (if (win) "mvnw.cmd" else "./mvnw") else "mvn"
            CodeInfo(
                abs, CodeKind.MAVEN, name, tool,
                listOf(
                    CodeTask("compile", I18n.t("studio.code_task_compile"), listOf("compile")),
                    CodeTask("clean", I18n.t("studio.code_task_clean"), listOf("clean")),
                    CodeTask("test", I18n.t("studio.code_task_test"), listOf("test")),
                    CodeTask("package", I18n.t("studio.code_task_package"), listOf("package")),
                    CodeTask("verify", I18n.t("studio.code_task_verify"), listOf("verify")),
                ),
                javaFiles, hasMvnw
            )
        }
        javaFiles > 0 -> CodeInfo(
            abs, CodeKind.JAVA, name, "javac/java",
            listOf(
                CodeTask("compile", I18n.t("studio.code_task_compile"), listOf("compile")),
                CodeTask("run", I18n.t("studio.code_task_run"), listOf("run")),
                CodeTask("clean", I18n.t("studio.code_task_clean"), listOf("clean")),
            ),
            javaFiles, false
        )
        else -> CodeInfo(abs, CodeKind.UNKNOWN, name, "—", emptyList(), 0, false)
    }
}

private fun countJavaFiles(root: Path): Int {
    var count = 0
    try {
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
                .filter { it.fileName.toString().endsWith(".java", true) }
                .filter { path ->
                    val rel = root.relativize(path)
                    rel.none { it.toString().lowercase(Locale.ROOT) in skipDirs }
                }
                .limit(5000)
                .forEach { count++ }
        }
    } catch (_: Throwable) {
    }
    return count
}

internal fun findMainClasses(root: Path): List<String> {
    val out = ArrayList<String>()
    try {
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java", true) }
                .filter { path ->
                    val rel = root.relativize(path)
                    rel.none { it.toString().lowercase(Locale.ROOT) in skipDirs }
                }
                .limit(400)
                .forEach { file ->
                    try {
                        val text = Files.readString(file, StandardCharsets.UTF_8)
                        if (!text.contains("public static void main") && !text.contains("static void main")) return@forEach
                        val pkg = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
                        val cls = file.fileName.toString().removeSuffix(".java")
                        out += if (pkg.isNullOrBlank()) cls else "$pkg.$cls"
                    } catch (_: Throwable) {
                    }
                }
        }
    } catch (_: Throwable) {
    }
    return out.distinct().sorted()
}

private fun sourceRoots(root: Path): List<Path> {
    val candidates = listOf(
        root.resolve("src/main/java"),
        root.resolve("src"),
        root
    )
    return candidates.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }.distinct()
}

private fun collectJavaSources(root: Path): List<Path> {
    val out = ArrayList<Path>()
    for (src in sourceRoots(root)) {
        try {
            Files.walk(src).use { stream ->
                stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".java", true) }
                    .filter { !Files.isSymbolicLink(it) }
                    .filter { path ->
                        val rel = root.relativize(path)
                        rel.none { it.toString().lowercase(Locale.ROOT) in skipDirs }
                    }
                    .limit(2000)
                    .forEach { out.add(it) }
            }
        } catch (_: Throwable) {
        }
    }
    return out.distinct()
}

private fun resolveJavaHome(): Path? {
    val prop = System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
    if (prop != null && Files.isDirectory(prop)) return prop
    val env = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
    return env?.takeIf { Files.isDirectory(it) }
}

private fun javaBin(name: String): String {
    val home = resolveJavaHome()
    val win = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
    val exe = if (win) "$name.exe" else name
    val candidate = home?.resolve("bin")?.resolve(exe)
    return if (candidate != null && Files.isRegularFile(candidate)) candidate.toString() else name
}

internal fun buildCommand(info: CodeInfo, task: CodeTask, custom: String, mainClass: String?): List<String>? {
    val win = System.getProperty("os.name", "").lowercase(Locale.ROOT).contains("win")
    val customArgs = tokenize(custom)
    return when (info.kind) {
        CodeKind.GRADLE -> {
            val wrapper = if (win) info.root.resolve("gradlew.bat") else info.root.resolve("gradlew")
            val base = if (info.hasWrapper && Files.isRegularFile(wrapper)) {
                listOf(wrapper.toAbsolutePath().toString())
            } else {
                listOf(if (win) "gradle.bat" else "gradle")
            }
            val args = if (customArgs.isNotEmpty()) customArgs else task.args
            base + args
        }
        CodeKind.MAVEN -> {
            val wrapper = if (win) info.root.resolve("mvnw.cmd") else info.root.resolve("mvnw")
            val base = if (info.hasWrapper && Files.isRegularFile(wrapper)) {
                listOf(wrapper.toAbsolutePath().toString())
            } else {
                listOf(if (win) "mvn.cmd" else "mvn")
            }
            val args = if (customArgs.isNotEmpty()) customArgs else task.args
            base + args
        }
        CodeKind.JAVA -> {
            when (task.id) {
                "clean" -> listOf("__clean__")
                "compile" -> listOf("__compile__")
                "run" -> listOf("__run__", mainClass.orEmpty())
                else -> if (customArgs.isNotEmpty()) customArgs else null
            }
        }
        CodeKind.UNKNOWN -> if (customArgs.isNotEmpty()) customArgs else null
    }
}

private fun tokenize(raw: String): List<String> {
    val out = ArrayList<String>()
    val cur = StringBuilder()
    var quote: Char? = null
    for (ch in raw.trim()) {
        when {
            quote != null -> {
                if (ch == quote) quote = null else cur.append(ch)
            }
            ch == '"' || ch == '\'' -> quote = ch
            ch.isWhitespace() -> {
                if (cur.isNotEmpty()) { out += cur.toString(); cur.clear() }
            }
            else -> cur.append(ch)
        }
    }
    if (cur.isNotEmpty()) out += cur.toString()
    return out
}

internal suspend fun runCodeTask(
    runner: CodeRunner,
    info: CodeInfo,
    command: List<String>,
    mainClass: String?,
) {
    runner.busy = true
    runner.lastExit = null
    runner.lastCommand = command.joinToString(" ")
    runner.append("> " + runner.lastCommand)
    try {
        when (command.firstOrNull()) {
            "__clean__" -> {
                val out = info.root.resolve("out")
                withContext(Dispatchers.IO) {
                    if (Files.isDirectory(out)) deleteRecursively(out)
                }
                runner.append(I18n.t("studio.code_cleaned"))
                runner.lastExit = 0
            }
            "__compile__" -> {
                val sources = withContext(Dispatchers.IO) { collectJavaSources(info.root) }
                if (sources.isEmpty()) {
                    runner.append(I18n.t("studio.code_no_sources"), true)
                    runner.lastExit = 1
                } else {
                    val outDir = info.root.resolve("out")
                    withContext(Dispatchers.IO) { Files.createDirectories(outDir) }
                    val args = listOf(javaBin("javac"), "-encoding", "UTF-8", "-d", outDir.toString()) +
                        sources.map { it.toString() }
                    runner.lastCommand = "javac … (${sources.size} files)"
                    runner.append("> javac -d out  (${sources.size} .java)")
                    runner.lastExit = execProcess(runner, info.root, args)
                }
            }
            "__run__" -> {
                val cls = mainClass?.takeIf { it.isNotBlank() }
                    ?: withContext(Dispatchers.IO) { findMainClasses(info.root).firstOrNull() }
                if (cls.isNullOrBlank()) {
                    runner.append(I18n.t("studio.code_no_main"), true)
                    runner.lastExit = 1
                } else {
                    val outDir = info.root.resolve("out")
                    if (!Files.isDirectory(outDir)) {
                        runner.append(I18n.t("studio.code_need_compile"), true)
                        runner.lastExit = 1
                    } else {
                        val args = listOf(javaBin("java"), "-cp", outDir.toString(), cls)
                        runner.lastExit = execProcess(runner, info.root, args)
                    }
                }
            }
            else -> runner.lastExit = execProcess(runner, info.root, command)
        }
    } catch (e: CancellationException) {
        runner.append(I18n.t("studio.code_cancelled"), true)
        runner.lastExit = -1
        throw e
    } catch (e: Throwable) {
        runner.append(I18n.t("studio.code_failed", e.message ?: e.javaClass.simpleName), true)
        runner.lastExit = 1
    } finally {
        runner.busy = false
        runner.process = null
    }
}

private fun deleteRecursively(path: Path) {
    if (!Files.exists(path)) return
    Files.walk(path).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}

private suspend fun execProcess(runner: CodeRunner, cwd: Path, command: List<String>): Int =
    withContext(Dispatchers.IO) {
        coroutineScope {
            val pb = ProcessBuilder(command)
            pb.directory(cwd.toFile())
            pb.redirectErrorStream(false)
            val env = pb.environment()
            resolveJavaHome()?.let { env["JAVA_HOME"] = it.toString() }
            env["LANG"] = env["LANG"] ?: "en_US.UTF-8"
            val proc = try {
                pb.start()
            } catch (e: Throwable) {
                runner.append(I18n.t("studio.code_failed", e.message ?: e.javaClass.simpleName), true)
                return@coroutineScope 1
            }
            runner.process = proc
            val outJob = launch { readStream(proc.inputStream, runner, false) }
            val errJob = launch { readStream(proc.errorStream, runner, true) }
            try {
                while (proc.isAlive) {
                    ensureActive()
                    proc.waitFor(200, TimeUnit.MILLISECONDS)
                }
                outJob.join()
                errJob.join()
                val code = proc.exitValue()
                runner.append(I18n.t("studio.code_exit", code.toString()), code != 0)
                code
            } catch (e: CancellationException) {
                proc.destroyForcibly()
                outJob.cancel()
                errJob.cancel()
                throw e
            }
        }
    }

private fun readStream(stream: java.io.InputStream, runner: CodeRunner, err: Boolean) {
    BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            runner.append(line, err)
        }
    }
}

@Composable
internal fun CodeProjectsPanel(
    projects: List<String>,
    onOpen: (Path) -> Unit,
    onPick: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val p = LocalStudio.current
    Column(Modifier.fillMaxSize().background(p.panel)) {
        PanelHeader(I18n.t("studio.code")) {
            Block("", onClick = onPick, icon = Icons.Filled.CreateNewFolder, height = 32.dp)
        }
        Block(
            I18n.t("studio.code_open"),
            onClick = onPick,
            icon = Icons.Filled.FolderOpen,
            fill = Tiles.Navy,
            modifier = Modifier.fillMaxWidth(),
            height = 40.dp
        )
        if (projects.isEmpty()) {
            FlatText(I18n.t("studio.code_empty"), color = p.muted, lines = 4, modifier = Modifier.padding(12.dp))
            return@Column
        }
        FlatText(I18n.t("studio.code_recent"), color = p.muted, size = 11, weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(projects, key = { it }) { path ->
                val name = Paths.get(path).fileName?.toString() ?: path
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clickable {
                        runCatching { Paths.get(path) }.getOrNull()?.let(onOpen)
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(44.dp).background(Tiles.Navy), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Coffee, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        FlatText(name, weight = FontWeight.Medium)
                        FlatText(path, color = p.muted, size = 10, lines = 1)
                    }
                    Block("", onClick = { onRemove(path) }, icon = Icons.Filled.Close, height = 44.dp)
                }
            }
        }
    }
}

@Composable
internal fun CodeProjectEditor(
    rootPath: String,
    onOpenFile: (Path, Path) -> Unit,
    onReveal: (Path) -> Unit,
    onOpenTerminal: () -> Unit,
) {
    val p = LocalStudio.current
    val scope = rememberCoroutineScope()
    val root = remember(rootPath) { runCatching { Paths.get(rootPath).toAbsolutePath().normalize() }.getOrNull() }
    var reload by remember { mutableIntStateOf(0) }
    val info by produceState<CodeInfo?>(null, root, reload) {
        value = null
        value = if (root == null) null else withContext(Dispatchers.IO) { detectCodeProject(root) }
    }
    val mains by produceState(emptyList<String>(), root, reload) {
        value = if (root == null) emptyList() else withContext(Dispatchers.IO) { findMainClasses(root) }
    }
    val runner = remember(rootPath) { CodeRunner() }
    var custom by remember { mutableStateOf("") }
    var mainClass by remember { mutableStateOf("") }
    var selectedTask by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    DisposableEffect(rootPath) {
        onDispose { runner.cancel() }
    }
    LaunchedEffect(mains) {
        if (mainClass.isBlank() && mains.isNotEmpty()) mainClass = mains.first()
    }
    if (root == null || info == null) {
        CenterNote(I18n.t("studio.code_missing"))
        return
    }
    val project = info!!
    val kindColor = when (project.kind) {
        CodeKind.GRADLE -> Tiles.Navy
        CodeKind.MAVEN -> Tiles.Orange
        CodeKind.JAVA -> Tiles.Teal
        CodeKind.UNKNOWN -> Tiles.Slate
    }
    val kindLabel = when (project.kind) {
        CodeKind.GRADLE -> "Gradle"
        CodeKind.MAVEN -> "Maven"
        CodeKind.JAVA -> "Java"
        CodeKind.UNKNOWN -> I18n.t("studio.code_unknown")
    }
    fun start(task: CodeTask) {
        if (runner.busy) return
        selectedTask = task.id
        val cmd = buildCommand(project, task, custom, mainClass.ifBlank { null }) ?: return
        runner.job?.cancel()
        runner.job = scope.launch {
            try {
                runCodeTask(runner, project, cmd, mainClass.ifBlank { null })
            } catch (_: CancellationException) {
            }
        }
    }
    Column(Modifier.fillMaxSize().background(p.editor)) {
        Row(Modifier.fillMaxWidth().height(96.dp)) {
            ColorTile(project.name, kindLabel, kindColor, Modifier.weight(2f).fillMaxHeight(), Icons.Filled.Coffee, valueSize = 22, index = 0)
            ColorTile(I18n.t("studio.code_files"), project.javaFiles.toString(), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Build, index = 1)
            ColorTile(I18n.t("studio.code_tool"), project.tool, Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Terminal, index = 2)
            ColorTile(
                if (runner.busy) I18n.t("studio.code_running") else I18n.t("studio.code_idle"),
                runner.lastExit?.toString() ?: "—",
                when {
                    runner.busy -> Tiles.Orange
                    runner.lastExit == 0 -> Tiles.Forest
                    runner.lastExit != null -> Tiles.Red
                    else -> Tiles.Graphite
                },
                Modifier.weight(1f).fillMaxHeight(),
                if (runner.busy) Icons.Filled.PlayArrow else Icons.Filled.Build,
                index = 3
            )
        }
        Row(Modifier.fillMaxWidth().height(36.dp).background(p.panel).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
            project.tasks.forEach { task ->
                Block(
                    task.label,
                    onClick = { start(task) },
                    fill = when {
                        runner.busy && selectedTask == task.id -> Tiles.Orange
                        task.id == "build" || task.id == "compile" -> Tiles.Blue
                        task.id == "run" -> Tiles.Forest
                        else -> null
                    },
                    enabled = !runner.busy || selectedTask == task.id,
                    selected = selectedTask == task.id,
                    height = 36.dp
                )
            }
            Spacer(Modifier.width(8.dp))
            FlatField(custom, { custom = it }, I18n.t("studio.code_custom"), Modifier.width(220.dp), onKey = { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) {
                    val task = project.tasks.firstOrNull { it.id == selectedTask } ?: project.tasks.firstOrNull()
                    if (task != null) start(task)
                    true
                } else false
            })
            if (project.kind == CodeKind.JAVA || project.kind == CodeKind.GRADLE) {
                FlatField(mainClass, { mainClass = it }, I18n.t("studio.code_main"), Modifier.width(200.dp))
            }
            Spacer(Modifier.weight(1f))
            Block("", onClick = { runner.cancel() }, icon = Icons.Filled.Stop, enabled = runner.busy, fill = if (runner.busy) Tiles.Red else null, height = 36.dp)
            Block("", onClick = { runner.clear() }, icon = Icons.Filled.Refresh, height = 36.dp)
            Block("", onClick = { onReveal(root) }, icon = Icons.Filled.FolderOpen, height = 36.dp)
            Block("", onClick = onOpenTerminal, icon = Icons.Filled.Terminal, height = 36.dp)
            Block("", onClick = { reload++ }, icon = Icons.Filled.Refresh, height = 36.dp)
        }
        if (mains.size > 1 && project.kind == CodeKind.JAVA) {
            Row(Modifier.fillMaxWidth().height(30.dp).background(p.panelAlt).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                FlatText(I18n.t("studio.code_mains"), color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 10.dp))
                mains.take(12).forEach { cls ->
                    Block(cls.substringAfterLast('.'), onClick = { mainClass = cls }, selected = mainClass == cls, height = 30.dp)
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(260.dp).fillMaxHeight().background(p.panel)) {
                FlatText(I18n.t("studio.code_tree"), color = Color.White, weight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().background(kindColor).padding(horizontal = 12.dp, vertical = 8.dp))
                CodeTree(root, onOpen = { onOpenFile(root, it) })
            }
            Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1B1F24))) {
                Row(Modifier.fillMaxWidth().height(30.dp).background(Color(0xFF2B313A)), verticalAlignment = Alignment.CenterVertically) {
                    FlatText(
                        runner.lastCommand.ifBlank { I18n.t("studio.code_output") },
                        color = Color(0xFFD7DCE2),
                        size = 11,
                        modifier = Modifier.padding(horizontal = 12.dp).weight(1f)
                    )
                    Block(
                        "",
                        onClick = { clipboard.setText(AnnotatedString(runner.lines.joinToString("\n") { it.text })) },
                        icon = Icons.Filled.Terminal,
                        height = 30.dp
                    )
                }
                val list = rememberLazyListState()
                LaunchedEffect(runner.lines.size) {
                    if (runner.lines.isNotEmpty()) list.scrollToItem(runner.lines.size - 1)
                }
                if (runner.lines.isEmpty()) {
                    FlatText(I18n.t("studio.code_output_hint"), color = Color(0xFF8B949E), modifier = Modifier.padding(16.dp), lines = 4)
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), state = list) {
                        items(runner.lines.size) { i ->
                            val line = runner.lines[i]
                            Text(
                                line.text,
                                color = if (line.err) Color(0xFFF28B82) else Color(0xFFD7DCE2),
                                fontFamily = mono.fontFamily,
                                fontSize = 12.sp,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeTree(root: Path, onOpen: (Path) -> Unit) {
    val p = LocalStudio.current
    val expanded = remember(root) { mutableStateMapOf<Path, Boolean>() }
    val children = remember(root) { mutableStateMapOf<Path, List<Path>>() }
    LaunchedEffect(root) {
        children[root] = withContext(Dispatchers.IO) { listCodeChildren(root) }
    }
    val rows = ArrayList<Pair<Path, Int>>()
    fun walk(dir: Path, depth: Int) {
        for (child in children[dir].orEmpty()) {
            rows += child to depth
            if (expanded[child] == true) walk(child, depth + 1)
        }
    }
    walk(root, 0)
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows, key = { it.first.toString() }) { (path, depth) ->
            val isDir = Files.isDirectory(path)
            val open = expanded[path] == true
            if (isDir && open && children[path] == null) {
                LaunchedEffect(path) {
                    children[path] = withContext(Dispatchers.IO) { listCodeChildren(path) }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(26.dp)
                    .clickable { if (isDir) expanded[path] = !open else onOpen(path) }
                    .padding(start = (6 + depth * 12).dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isDir) {
                    Icon(if (open) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null, tint = p.muted, modifier = Modifier.size(14.dp))
                    Icon(Icons.Filled.Folder, null, tint = Tiles.Gold, modifier = Modifier.size(14.dp))
                } else {
                    Spacer(Modifier.width(14.dp))
                    Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = fileTint(path), modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(6.dp))
                FlatText(path.fileName?.toString().orEmpty(), modifier = Modifier.weight(1f), size = 12)
            }
        }
    }
}

private fun fileTint(path: Path): Color {
    val name = path.fileName?.toString()?.lowercase(Locale.ROOT).orEmpty()
    return when {
        name.endsWith(".java") || name.endsWith(".kt") -> Tiles.Teal
        name.endsWith(".gradle") || name.endsWith(".kts") -> Tiles.Navy
        name == "pom.xml" -> Tiles.Orange
        name.endsWith(".xml") || name.endsWith(".properties") -> Tiles.Sky
        else -> LocalStudioFallback
    }
}

private val LocalStudioFallback = Color(0xFF8B949E)

private fun listCodeChildren(dir: Path): List<Path> = try {
    Files.list(dir).use { stream ->
        stream.filter { !Files.isSymbolicLink(it) }
            .filter { path ->
                val name = path.fileName.toString().lowercase(Locale.ROOT)
                if (Files.isDirectory(path)) name !in skipDirs else true
            }
            .limit(MAX_CHILDREN.toLong())
            .toList()
            .sortedWith(compareBy({ !Files.isDirectory(it) }, { it.fileName.toString().lowercase(Locale.ROOT) }))
    }
} catch (_: Throwable) {
    emptyList()
}
