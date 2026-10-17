package com.pmcl.ui.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.GsonBuilder
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.Executors

internal fun sv(key: String, vararg args: Any?): String = I18n.t("studio.survey.$key", *args)

private const val ISSUES_NEW = "https://github.com/PCML-Z/PCML/issues/new"
private const val MAX_ISSUE_URL = 7000
private const val SNOOZE_MS = 3L * 24 * 60 * 60 * 1000
internal const val SURVEY_PROMPT_DELAY_MS = 5L * 60 * 1000
private const val PAGE_COUNT = 5

internal val surveyAspects = listOf("looks", "speed", "features", "efficiency", "stability", "learnability")
internal val surveyFeatures = listOf(
    "targets", "files", "content", "diagnose", "code", "docs", "terminal", "modules", "palette", "settings"
)
private val optContinue = listOf("definitely", "probably", "unsure", "unlikely", "no")
private val optExpectation = listOf("above", "met", "below")
private val optExperience = listOf("new", "some", "veteran", "developer")
private val optPurposes = listOf("play", "modpack", "moddev", "server", "content", "explore")
private val optFrequency = listOf("daily", "weekly", "sometimes", "first")
private val optCompare = listOf("studio", "same", "classic", "never")
private val optIssues = listOf("lag", "crash", "layout", "overlap", "findability", "motion", "colors", "memory", "none")
private val optWishes = listOf("market", "git", "conflicts", "server", "ai", "shortcuts", "themes", "sync", "windows", "screenshots")
private val optColors = listOf("fewer", "right", "more")
private val optMotion = listOf("less", "right", "more")
private val optDensity = listOf("compact", "right", "roomy")
private val optStartup = listOf("studio", "classic", "ask")
private val optBeta = listOf("yes", "no")

/** 一份已提交的问卷。system 只在用户勾选时填写，contact 只在愿意参加测试时保留。 */
internal data class SurveyAnswer(
    val at: Long = 0,
    val version: String = "",
    val overall: Int = 0,
    val recommend: Int = -1,
    val continueUse: String = "",
    val expectation: String = "",
    val experience: String = "",
    val purposes: List<String> = emptyList(),
    val frequency: String = "",
    val compare: String = "",
    val ease: Int = 0,
    val aspects: Map<String, Int> = emptyMap(),
    val features: List<String> = emptyList(),
    val featureRatings: Map<String, Int> = emptyMap(),
    val issues: List<String> = emptyList(),
    val wishes: List<String> = emptyList(),
    val colors: String = "",
    val motion: String = "",
    val density: String = "",
    val startup: String = "",
    val liked: String = "",
    val improve: String = "",
    val bugs: String = "",
    val beta: String = "",
    val contact: String = "",
    val system: String = "",
)

/** 写在 studio-survey.json，只存在本机。 */
internal data class SurveyState(
    val answers: List<SurveyAnswer> = emptyList(),
    val snoozeUntil: Long = 0,
    val never: Boolean = false,
)

internal object SurveyStore {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    private val file: Path by lazy {
        val dir = System.getProperty("pmcl.workdir")?.takeIf { it.isNotEmpty() }?.let { Paths.get(it) }
            ?: Paths.get(System.getProperty("user.home"), ".pmcl")
        dir.resolve("studio-survey.json")
    }

    private fun one(value: String?, allowed: List<String>): String = value?.takeIf { it in allowed } ?: ""

    private fun many(values: List<String?>?, allowed: List<String>): List<String> =
        values.orEmpty().filterNotNull().filter { it in allowed }.distinct()

    private fun ratings(values: Map<String?, Int?>?, allowed: List<String>): Map<String, Int> =
        values.orEmpty().entries.mapNotNull { (k, v) -> if (k != null && v != null && k in allowed) k to v.coerceIn(1, 5) else null }.toMap()

    // Gson 不走 Kotlin 默认值，旧文件缺字段时会留下 null
    @Suppress("USELESS_ELVIS", "UNCHECKED_CAST")
    fun load(): SurveyState = try {
        if (!Files.isRegularFile(file)) SurveyState()
        else {
            val raw = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), SurveyState::class.java)
            SurveyState(
                answers = (raw?.answers ?: emptyList()).filterNotNull().map { a ->
                    SurveyAnswer(
                        at = a.at,
                        version = a.version ?: "",
                        overall = a.overall.coerceIn(0, 5),
                        recommend = a.recommend.coerceIn(-1, 10),
                        continueUse = one(a.continueUse, optContinue),
                        expectation = one(a.expectation, optExpectation),
                        experience = one(a.experience, optExperience),
                        purposes = many(a.purposes as List<String?>?, optPurposes),
                        frequency = one(a.frequency, optFrequency),
                        compare = one(a.compare, optCompare),
                        ease = a.ease.coerceIn(0, 5),
                        aspects = ratings(a.aspects as Map<String?, Int?>?, surveyAspects),
                        features = many(a.features as List<String?>?, surveyFeatures),
                        featureRatings = ratings(a.featureRatings as Map<String?, Int?>?, surveyFeatures),
                        issues = many(a.issues as List<String?>?, optIssues),
                        wishes = many(a.wishes as List<String?>?, optWishes),
                        colors = one(a.colors, optColors),
                        motion = one(a.motion, optMotion),
                        density = one(a.density, optDensity),
                        startup = one(a.startup, optStartup),
                        liked = a.liked ?: "",
                        improve = a.improve ?: "",
                        bugs = a.bugs ?: "",
                        beta = one(a.beta, optBeta),
                        contact = a.contact ?: "",
                        system = a.system ?: "",
                    )
                }.filter { it.overall > 0 }.sortedByDescending { it.at },
                snoozeUntil = raw?.snoozeUntil ?: 0,
                never = raw?.never ?: false,
            )
        }
    } catch (_: Throwable) {
        SurveyState()
    }

    fun save(state: SurveyState) {
        try {
            Files.createDirectories(file.parent)
            writeAtomically(file, gson.toJson(state))
        } catch (_: Throwable) {
        }
    }
}

/** 正在填写的问卷，切走标签、关掉窗口再打开都还在。 */
internal class SurveyDraft {
    var page by mutableIntStateOf(0)
    var overall by mutableIntStateOf(0)
    var recommend by mutableIntStateOf(-1)
    var continueUse by mutableStateOf("")
    var expectation by mutableStateOf("")
    var experience by mutableStateOf("")
    val purposes = mutableStateListOf<String>()
    var frequency by mutableStateOf("")
    var compare by mutableStateOf("")
    var ease by mutableIntStateOf(0)
    val aspects = mutableStateMapOf<String, Int>()
    val features = mutableStateListOf<String>()
    val featureRatings = mutableStateMapOf<String, Int>()
    val issues = mutableStateListOf<String>()
    val wishes = mutableStateListOf<String>()
    var colors by mutableStateOf("")
    var motion by mutableStateOf("")
    var density by mutableStateOf("")
    var startup by mutableStateOf("")
    var liked by mutableStateOf("")
    var improve by mutableStateOf("")
    var bugs by mutableStateOf("")
    var beta by mutableStateOf("")
    var contact by mutableStateOf("")
    var includeSystem by mutableStateOf(true)

    /** 每页里选择题的完成情况；开放题不计入进度。 */
    fun pageDone(page: Int): List<Boolean> = when (page) {
        0 -> listOf(overall > 0, recommend >= 0, continueUse.isNotEmpty(), expectation.isNotEmpty())
        1 -> listOf(experience.isNotEmpty(), purposes.isNotEmpty(), frequency.isNotEmpty(), compare.isNotEmpty(), ease > 0)
        2 -> listOf(
            aspects.size == surveyAspects.size,
            features.isNotEmpty(),
            features.isNotEmpty() && features.all { it in featureRatings },
            issues.isNotEmpty(),
            wishes.isNotEmpty()
        )
        3 -> listOf(colors.isNotEmpty(), motion.isNotEmpty(), density.isNotEmpty(), startup.isNotEmpty())
        else -> listOf(beta.isNotEmpty())
    }

    val answered: Int get() = (0 until PAGE_COUNT).sumOf { p -> pageDone(p).count { it } }

    val total: Int get() = (0 until PAGE_COUNT).sumOf { pageDone(it).size }

    fun reset() {
        page = 0
        overall = 0
        recommend = -1
        continueUse = ""
        expectation = ""
        experience = ""
        purposes.clear()
        frequency = ""
        compare = ""
        ease = 0
        aspects.clear()
        features.clear()
        featureRatings.clear()
        issues.clear()
        wishes.clear()
        colors = ""
        motion = ""
        density = ""
        startup = ""
        liked = ""
        improve = ""
        bugs = ""
        beta = ""
        contact = ""
        includeSystem = true
    }

    fun toAnswer(version: String): SurveyAnswer = SurveyAnswer(
        at = System.currentTimeMillis(),
        version = version,
        overall = overall,
        recommend = recommend,
        continueUse = continueUse,
        expectation = expectation,
        experience = experience,
        purposes = optPurposes.filter { it in purposes },
        frequency = frequency,
        compare = compare,
        ease = ease,
        aspects = aspects.toMap(),
        features = surveyFeatures.filter { it in features },
        featureRatings = featureRatings.filterKeys { it in features },
        issues = optIssues.filter { it in issues },
        wishes = optWishes.filter { it in wishes },
        colors = colors,
        motion = motion,
        density = density,
        startup = startup,
        liked = liked.trim(),
        improve = improve.trim(),
        bugs = bugs.trim(),
        beta = beta,
        contact = if (beta == "yes") contact.trim() else "",
        system = if (includeSystem) systemLine(version) else "",
    )
}

internal object SurveyHub {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "studio-survey").apply { isDaemon = true } }

    var state by mutableStateOf(SurveyStore.load())
        private set
    val draft = SurveyDraft()

    fun update(change: (SurveyState) -> SurveyState) {
        val next = change(state)
        state = next
        io.execute { SurveyStore.save(next) }
    }

    fun shouldPrompt(version: String, now: Long): Boolean =
        !state.never && now >= state.snoozeUntil && state.answers.none { it.version == version }

    fun snooze() = update { it.copy(snoozeUntil = System.currentTimeMillis() + SNOOZE_MS) }

    fun never() = update { it.copy(never = true) }

    private val promptClaimed = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 多个 Studio 窗口时，每次运行只让第一个到点的窗口弹提示卡 */
    fun claimPrompt(): Boolean = promptClaimed.compareAndSet(false, true)
}

private fun systemLine(version: String): String {
    val os = listOf(System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"))
        .filterNotNull().joinToString(" ")
    return "PMCL $version · $os · Java ${System.getProperty("java.version").orEmpty()}"
}

private fun overallLabel(n: Int): String = if (n in 1..5) sv("overall_$n") else sv("overall_0")

private fun scoreLabel(n: Int): String = if (n in 1..5) sv("score_$n") else ""

private fun opt(prefix: String, id: String): String = if (id.isEmpty()) "" else sv("${prefix}_$id")

private fun opts(prefix: String, ids: List<String>): String = ids.joinToString(" · ") { sv("${prefix}_$it") }

private fun rated(n: Int?): String = if (n != null && n in 1..5) "$n/5 · ${scoreLabel(n)}" else ""

/** 答卷按题目顺序摊成“题目：回答”，详情页和报告共用；没答的题不出现。 */
private fun answerRows(a: SurveyAnswer): List<Pair<String, String>> = buildList {
    add(sv("q_overall") to "${a.overall}/5 · ${overallLabel(a.overall)}")
    if (a.recommend >= 0) add(sv("q_recommend") to "${a.recommend}/10")
    add(sv("q_continue") to opt("continue", a.continueUse))
    add(sv("q_expectation") to opt("expect", a.expectation))
    add(sv("q_experience") to opt("exp", a.experience))
    add(sv("q_purposes") to opts("purpose", a.purposes))
    add(sv("q_frequency") to opt("frequency", a.frequency))
    add(sv("q_compare") to opt("compare", a.compare))
    if (a.ease > 0) add(sv("q_ease") to "${a.ease}/5 · ${sv("ease_${a.ease}")}")
    surveyAspects.forEach { k -> add(sv("aspect_$k") to rated(a.aspects[k])) }
    add(sv("q_features") to opts("feature", a.features))
    a.features.forEach { f -> add(sv("feature_rating", sv("feature_$f")) to rated(a.featureRatings[f])) }
    add(sv("q_issues") to opts("issue", a.issues))
    add(sv("q_wishes") to opts("wish", a.wishes))
    add(sv("q_colors") to opt("colors", a.colors))
    add(sv("q_motion") to opt("motion", a.motion))
    add(sv("q_density") to opt("density", a.density))
    add(sv("q_startup") to opt("startup", a.startup))
    add(sv("q_liked") to a.liked)
    add(sv("q_improve") to a.improve)
    add(sv("q_bugs") to a.bugs)
    add(sv("q_beta") to listOf(opt("beta", a.beta), a.contact).filter { it.isNotEmpty() }.joinToString(" · "))
    add(sv("report_system") to a.system)
}.filter { it.second.isNotBlank() }

internal fun surveyReport(a: SurveyAnswer): String = buildString {
    appendLine("## " + sv("report_title"))
    appendLine()
    answerRows(a).forEach { (q, v) ->
        if ('\n' in v) {
            appendLine("**$q**")
            v.lines().forEach { appendLine("> $it") }
            appendLine()
        } else {
            appendLine("- **$q**: $v")
        }
    }
}

private fun issueUrl(a: SurveyAnswer, body: String): String {
    fun enc(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
    return ISSUES_NEW + "?title=" + enc(sv("issue_title", a.overall)) + "&body=" + enc(body)
}

/** 报告太长塞不进链接时，先把全文放进剪贴板，链接里只留一句提示；返回是否走了剪贴板。 */
private fun openIssue(a: SurveyAnswer): Boolean {
    val report = surveyReport(a)
    val full = issueUrl(a, report)
    if (full.length <= MAX_ISSUE_URL) {
        browse(full)
        return false
    }
    copyText(report)
    browse(issueUrl(a, sv("issue_paste")))
    return true
}

/** 满意度调查标签页：左边问卷或结果，右边本机保存的历史。 */
@Composable
internal fun SurveyEditor(vm: LauncherViewModel, onConfirm: (String, String, () -> Unit) -> Unit) {
    val p = LocalStudio.current
    val state = SurveyHub.state
    val draft = SurveyHub.draft
    val version = remember { vm.core.launcherVersion() }
    var shownAt by rememberSaveable { mutableLongStateOf(0L) }
    val shown = state.answers.firstOrNull { it.at == shownAt }

    Column(Modifier.fillMaxSize().background(p.editor)) {
        ModuleHero(sv("title"), sv("subtitle", draft.total), Tiles.Blue, Icons.Filled.Poll) {
            if (shown != null) HeroButton(sv("again"), Icons.Filled.Refresh, Tiles.Blue, { shownAt = 0L })
        }
        ProgressLine(if (shown != null) 1f else draft.answered / draft.total.toFloat(), Tiles.Sky)
        Row(Modifier.weight(1f).fillMaxWidth()) {
            AnimatedContent(
                shown,
                Modifier.weight(1f).fillMaxHeight(),
                transitionSpec = {
                    (fadeIn(tween(240)) + slideInVertically(tween(280, easing = FastOutSlowInEasing)) { it / 12 }) togetherWith fadeOut(tween(160))
                },
                contentKey = { it?.at ?: 0L },
                label = "survey"
            ) { answer ->
                if (answer == null) {
                    SurveyForm(draft, version) {
                        val submitted = draft.toAnswer(version)
                        SurveyHub.update { s -> s.copy(answers = listOf(submitted) + s.answers) }
                        draft.reset()
                        shownAt = submitted.at
                    }
                } else {
                    SurveyThanks(answer)
                }
            }
            if (state.answers.isNotEmpty()) {
                SurveyHistory(state.answers, shownAt, onShow = { shownAt = it.at }) { a ->
                    onConfirm(sv("delete_title"), sv("delete_body", fmtDate(a.at))) {
                        SurveyHub.update { s -> s.copy(answers = s.answers.filterNot { it.at == a.at }) }
                        if (shownAt == a.at) shownAt = 0L
                    }
                }
            }
        }
    }
}

@Composable
private fun SurveyForm(draft: SurveyDraft, version: String, onSubmit: () -> Unit) {
    val p = LocalStudio.current
    val last = draft.page == PAGE_COUNT - 1
    Column(Modifier.fillMaxSize()) {
        SurveySteps(draft)
        AnimatedContent(
            draft.page,
            Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally(tween(280, easing = FastOutSlowInEasing)) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(200)) { if (forward) -it / 8 else it / 8 } + fadeOut(tween(160)))
            },
            label = "surveyPage"
        ) { page ->
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
                    when (page) {
                        0 -> PageOverall(draft)
                        1 -> PageAboutYou(draft)
                        2 -> PageFeatures(draft)
                        3 -> PagePreferences(draft)
                        else -> PageComments(draft, version)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(52.dp).background(p.panel).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FlatText(sv("progress", draft.answered, draft.total), color = p.muted, modifier = Modifier.weight(1f))
            if (draft.overall == 0) {
                FlatText(sv("need_overall"), color = p.muted, size = 11, modifier = Modifier.padding(end = 12.dp))
            }
            Block(sv("reset"), { draft.reset() }, icon = Icons.Filled.Refresh, height = 36.dp)
            Spacer(Modifier.width(8.dp))
            Block(sv("prev"), { draft.page-- }, icon = Icons.AutoMirrored.Filled.ArrowBack, fill = p.field, enabled = draft.page > 0, height = 36.dp)
            Spacer(Modifier.width(8.dp))
            if (last) {
                Block(sv("submit"), onSubmit, icon = Icons.Filled.Check, fill = Tiles.Blue, enabled = draft.overall > 0, height = 36.dp)
            } else {
                if (draft.overall > 0) {
                    Block(sv("submit_now"), onSubmit, height = 36.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Block(sv("next"), { draft.page++ }, icon = Icons.AutoMirrored.Filled.ArrowForward, fill = Tiles.Blue, height = 36.dp)
            }
        }
    }
}

/** 顶部分页条：当前页铺主色，答完的页显示对勾，点哪页跳哪页。 */
@Composable
private fun SurveySteps(draft: SurveyDraft) {
    val p = LocalStudio.current
    Row(Modifier.fillMaxWidth().height(54.dp).background(p.panelAlt)) {
        (0 until PAGE_COUNT).forEach { i ->
            val done = draft.pageDone(i)
            val complete = done.all { it }
            val current = draft.page == i
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            val bg by animateColorAsState(
                when {
                    current -> Tiles.Blue
                    hovered -> p.hover
                    else -> Color.Transparent
                },
                tween(200),
                label = "step"
            )
            val ink = if (current) Color.White else p.text
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .entrance(i)
                    .background(bg)
                    .hoverable(source)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = source, indication = null) { draft.page = i }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(24.dp).background(if (current) Color.White.copy(alpha = 0.18f) else if (complete) Tiles.Sky else p.field),
                    contentAlignment = Alignment.Center
                ) {
                    if (complete) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    else FlatText((i + 1).toString(), color = ink, weight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    FlatText(sv("page_${i + 1}"), color = ink, weight = FontWeight.SemiBold)
                    FlatText("${done.count { it }} / ${done.size}", color = if (current) Color.White.copy(alpha = 0.75f) else p.muted, size = 11)
                }
            }
        }
    }
}

@Composable
private fun PageOverall(draft: SurveyDraft) {
    val p = LocalStudio.current
    FlatText(sv("intro"), color = p.muted, size = 12, lines = 3, modifier = Modifier.entrance(0))
    Spacer(Modifier.height(16.dp))
    Question(1, sv("q_overall"), true, draft.overall > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StarRow(draft.overall, 46.dp) { draft.overall = it }
            Spacer(Modifier.width(16.dp))
            FlatText(overallLabel(draft.overall), color = if (draft.overall > 0) p.text else p.muted, size = 15, weight = FontWeight.SemiBold)
        }
    }
    Question(2, sv("q_recommend"), false, draft.recommend >= 0) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                (0..10).forEach { n ->
                    ScoreBox(n.toString(), draft.recommend == n, 40.dp) { draft.recommend = if (draft.recommend == n) -1 else n }
                }
            }
            Row(Modifier.width(40.dp * 11 + 4.dp * 10).padding(top = 6.dp)) {
                FlatText(sv("recommend_low"), color = p.muted, size = 11, modifier = Modifier.weight(1f))
                FlatText(sv("recommend_high"), color = p.muted, size = 11)
            }
        }
    }
    Question(3, sv("q_continue"), false, draft.continueUse.isNotEmpty()) {
        SingleChoice("continue", optContinue, draft.continueUse) { draft.continueUse = it }
    }
    Question(4, sv("q_expectation"), false, draft.expectation.isNotEmpty()) {
        SingleChoice("expect", optExpectation, draft.expectation) { draft.expectation = it }
    }
}

@Composable
private fun PageAboutYou(draft: SurveyDraft) {
    val p = LocalStudio.current
    Question(5, sv("q_experience"), false, draft.experience.isNotEmpty()) {
        SingleChoice("exp", optExperience, draft.experience) { draft.experience = it }
    }
    Question(6, sv("q_purposes"), false, draft.purposes.isNotEmpty()) {
        MultiChoice("purpose", optPurposes, draft.purposes)
    }
    Question(7, sv("q_frequency"), false, draft.frequency.isNotEmpty()) {
        SingleChoice("frequency", optFrequency, draft.frequency) { draft.frequency = it }
    }
    Question(8, sv("q_compare"), false, draft.compare.isNotEmpty()) {
        SingleChoice("compare", optCompare, draft.compare) { draft.compare = it }
    }
    Question(9, sv("q_ease"), false, draft.ease > 0) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1..5).forEach { n -> ScoreBox(n.toString(), draft.ease == n, 40.dp) { draft.ease = if (draft.ease == n) 0 else n } }
                }
                Spacer(Modifier.width(12.dp))
                FlatText(if (draft.ease > 0) sv("ease_${draft.ease}") else "", color = p.muted, size = 11)
            }
        }
    }
}

@Composable
private fun PageFeatures(draft: SurveyDraft) {
    val p = LocalStudio.current
    Question(10, sv("q_aspects"), false, draft.aspects.size == surveyAspects.size) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            surveyAspects.forEach { k -> RatingRow(sv("aspect_$k"), draft.aspects, k) }
        }
    }
    Question(11, sv("q_features"), false, draft.features.isNotEmpty()) {
        MultiChoice("feature", surveyFeatures, draft.features)
    }
    Question(12, sv("q_feature_ratings"), false, draft.features.isNotEmpty() && draft.features.all { it in draft.featureRatings }) {
        val picked = surveyFeatures.filter { it in draft.features }
        AnimatedContent(picked.isEmpty(), label = "featureRatings") { empty ->
            if (empty) {
                FlatText(sv("feature_ratings_empty"), color = p.muted, size = 12)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    picked.forEach { f -> RatingRow(sv("feature_$f"), draft.featureRatings, f) }
                }
            }
        }
    }
    Question(13, sv("q_issues"), false, draft.issues.isNotEmpty()) {
        MultiChoice("issue", optIssues, draft.issues, exclusive = "none")
    }
    Question(14, sv("q_wishes"), false, draft.wishes.isNotEmpty()) {
        MultiChoice("wish", optWishes, draft.wishes)
    }
}

@Composable
private fun PagePreferences(draft: SurveyDraft) {
    val p = LocalStudio.current
    FlatText(sv("prefs_intro"), color = p.muted, size = 12, lines = 2, modifier = Modifier.entrance(0).padding(bottom = 16.dp))
    Question(15, sv("q_colors"), false, draft.colors.isNotEmpty()) {
        SingleChoice("colors", optColors, draft.colors) { draft.colors = it }
    }
    Question(16, sv("q_motion"), false, draft.motion.isNotEmpty()) {
        SingleChoice("motion", optMotion, draft.motion) { draft.motion = it }
    }
    Question(17, sv("q_density"), false, draft.density.isNotEmpty()) {
        SingleChoice("density", optDensity, draft.density) { draft.density = it }
    }
    Question(18, sv("q_startup"), false, draft.startup.isNotEmpty()) {
        SingleChoice("startup", optStartup, draft.startup) { draft.startup = it }
    }
}

@Composable
private fun PageComments(draft: SurveyDraft, version: String) {
    val p = LocalStudio.current
    Question(19, sv("q_liked"), false, null) {
        TextArea(draft.liked, { draft.liked = it }, sv("liked_hint"))
    }
    Question(20, sv("q_improve"), false, null) {
        TextArea(draft.improve, { draft.improve = it }, sv("improve_hint"))
    }
    Question(21, sv("q_bugs"), false, null) {
        TextArea(draft.bugs, { draft.bugs = it }, sv("bugs_hint"))
    }
    Question(22, sv("q_beta"), false, draft.beta.isNotEmpty()) {
        Column {
            SingleChoice("beta", optBeta, draft.beta) { draft.beta = it }
            AnimatedVisibility(
                draft.beta == "yes",
                enter = expandVertically(tween(220)) + fadeIn(tween(220)),
                exit = shrinkVertically(tween(180)) + fadeOut(tween(160))
            ) {
                Column(Modifier.padding(top = 10.dp)) {
                    InputBox(draft.contact, { draft.contact = it.take(200) }, sv("contact_hint"), Modifier.widthIn(max = 420.dp).fillMaxWidth())
                    FlatText(sv("contact_note"), color = p.muted, size = 11, lines = 2, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }

    val toggle = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .entrance(23)
            .padding(top = 6.dp)
            .background(p.panel)
            .clickable(interactionSource = toggle, indication = null) { draft.includeSystem = !draft.includeSystem }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (draft.includeSystem) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (draft.includeSystem) Tiles.Sky else p.muted,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            FlatText(sv("include_system"), color = p.text, weight = FontWeight.Medium)
            FlatText(systemLine(version), color = p.muted, size = 11)
        }
    }
    Spacer(Modifier.height(10.dp))
    FlatText(sv("privacy"), color = p.muted, size = 11, lines = 3)
}

/** 单选：再点一次已选项取消。 */
@Composable
private fun SingleChoice(prefix: String, options: List<String>, selected: String, onPick: (String) -> Unit) {
    Chips(options.map { it to sv("${prefix}_$it") }, selected, { onPick(if (selected == it) "" else it) }, Tiles.Sky)
}

/** 多选；exclusive 选项和其他选项互斥（例如“没遇到问题”）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiChoice(prefix: String, options: List<String>, picked: SnapshotStateList<String>, exclusive: String? = null) {
    val p = LocalStudio.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { id ->
            val on = id in picked
            Block(
                sv("${prefix}_$id"),
                {
                    when {
                        on -> picked.remove(id)
                        id == exclusive -> { picked.clear(); picked.add(id) }
                        else -> { if (exclusive != null) picked.remove(exclusive); picked.add(id) }
                    }
                },
                icon = if (on) Icons.Filled.Check else null,
                fill = if (on) Tiles.Sky else p.field,
                height = 32.dp
            )
        }
    }
}

/** 一行 1–5 分：左边名称，右边分数块和文字说明。 */
@Composable
private fun RatingRow(label: String, scores: SnapshotStateMap<String, Int>, key: String) {
    val p = LocalStudio.current
    val v = scores[key] ?: 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        FlatText(label, color = p.text, weight = FontWeight.Medium, modifier = Modifier.width(150.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            (1..5).forEach { n ->
                ScoreBox(n.toString(), v == n, 34.dp) {
                    if (v == n) scores.remove(key) else scores[key] = n
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        FlatText(scoreLabel(v), color = p.muted, size = 11)
    }
}

/** 题号色块 + 题目；done 为空表示选填的开放题。 */
@Composable
private fun Question(number: Int, title: String, required: Boolean, done: Boolean?, content: @Composable () -> Unit) {
    val p = LocalStudio.current
    val badge by animateColorAsState(if (done == true) Tiles.Blue else Tiles.Slate, tween(220), label = "qBadge")
    Column(Modifier.fillMaxWidth().entrance(number % 6 + 1).padding(bottom = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).background(badge), contentAlignment = Alignment.Center) {
                if (done == true) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                else FlatText(number.toString(), color = Color.White, weight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(10.dp))
            FlatText(title, color = p.text, size = 14, weight = FontWeight.SemiBold, lines = 2, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            FlatText(if (required) sv("required") else sv("optional"), color = if (required) Tiles.Red else p.muted, size = 11)
        }
        Box(Modifier.padding(start = 36.dp, top = 10.dp)) { content() }
    }
}

/** 五颗方块星：悬停预览，点同一颗取消。 */
@Composable
private fun StarRow(value: Int, size: Dp, onPick: (Int) -> Unit) {
    val p = LocalStudio.current
    var hover by remember { mutableIntStateOf(0) }
    val shown = if (hover > 0) hover else value
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..5).forEach { i ->
            val source = remember { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            LaunchedEffect(hovered) {
                if (hovered) hover = i else if (hover == i) hover = 0
            }
            val on = i <= shown
            val fill by animateColorAsState(if (on) Tiles.Blue else p.field, tween(160), label = "star")
            val scale by animateFloatAsState(
                when {
                    hovered -> 1.14f
                    on -> 1.04f
                    else -> 1f
                },
                spring(dampingRatio = 0.5f, stiffness = 500f),
                label = "starScale"
            )
            Box(
                Modifier
                    .size(size)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .background(fill)
                    .hoverable(source)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = source, indication = null) { onPick(if (value == i) 0 else i) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Star, contentDescription = overallLabel(i), tint = if (on) Color.White else p.muted, modifier = Modifier.size(size * 0.52f))
            }
        }
    }
}

@Composable
private fun ScoreBox(label: String, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val p = LocalStudio.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill by animateColorAsState(
        when {
            selected -> Tiles.Sky
            hovered -> p.hover
            else -> p.field
        },
        tween(150),
        label = "score"
    )
    val scale by animateFloatAsState(if (selected) 1.06f else 1f, spring(dampingRatio = 0.55f, stiffness = 600f), label = "scoreScale")
    Box(
        Modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(fill)
            .hoverable(source)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        FlatText(label, color = if (selected) Color.White else p.text, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun TextArea(value: String, onChange: (String) -> Unit, placeholder: String) {
    val p = LocalStudio.current
    Box(Modifier.fillMaxWidth().heightIn(min = 76.dp).background(p.field).padding(10.dp)) {
        if (value.isEmpty()) FlatText(placeholder, color = p.muted, lines = 3)
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.take(2000)) },
            textStyle = TextStyle(color = p.text, fontSize = 13.sp, lineHeight = 18.sp),
            cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SurveyThanks(a: SurveyAnswer) {
    val p = LocalStudio.current
    var copied by remember(a.at) { mutableStateOf(false) }
    var pasteHint by remember(a.at) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.entrance(0)) {
                Box(Modifier.size(52.dp).background(Tiles.Blue), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    FlatText(sv("thanks_title"), color = p.text, size = 20, weight = FontWeight.SemiBold)
                    FlatText(sv("thanks_body", fmtDate(a.at), a.version), color = p.muted, size = 12, lines = 2)
                }
            }
            Spacer(Modifier.height(18.dp))
            TileRow(90.dp) {
                ColorTile(sv("tile_overall"), "${a.overall} / 5", Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Star, index = 1)
                ColorTile(sv("tile_recommend"), if (a.recommend >= 0) "${a.recommend} / 10" else "—", Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), index = 2)
                val avg = a.aspects.values.takeIf { it.isNotEmpty() }?.average()
                ColorTile(sv("tile_aspects"), avg?.let { String.format(Locale.ROOT, "%.1f / 5", it) } ?: "—", Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), index = 3)
                ColorTile(sv("tile_continue"), opt("continue", a.continueUse).ifEmpty { "—" }, Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), valueSize = 15, index = 4)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.entrance(5), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Block(sv("submit_github"), { pasteHint = openIssue(a) }, icon = Icons.AutoMirrored.Filled.OpenInNew, fill = Tiles.Blue, height = 36.dp)
                Block(if (copied) sv("copied") else sv("copy_report"), { copyText(surveyReport(a)); copied = true }, icon = Icons.Filled.ContentCopy, fill = p.field, height = 36.dp)
            }
            Spacer(Modifier.height(8.dp))
            FlatText(if (pasteHint) sv("paste_hint") else sv("github_hint"), color = if (pasteHint) p.text else p.muted, size = 11, lines = 3, modifier = Modifier.entrance(6))
            Spacer(Modifier.height(16.dp))
            LabelBar(sv("details"), Tiles.Blue)
            answerRows(a).forEachIndexed { i, (q, v) -> FactRow(q, v, i, lines = if ('\n' in v || v.length > 60) 6 else 2) }
        }
    }
}

@Composable
private fun SurveyHistory(answers: List<SurveyAnswer>, shownAt: Long, onShow: (SurveyAnswer) -> Unit, onDelete: (SurveyAnswer) -> Unit) {
    val p = LocalStudio.current
    Inspector(sv("history"), Tiles.Navy, null, Modifier.width(300.dp)) {
        val avg = answers.map { it.overall }.average()
        Row(Modifier.fillMaxWidth().height(76.dp)) {
            ColorTile(sv("tile_count"), answers.size.toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), index = 0)
            ColorTile(sv("tile_average"), String.format(Locale.ROOT, "%.1f", avg), Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Star, index = 1)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            answers.forEachIndexed { i, a ->
                ItemRow(
                    "★".repeat(a.overall) + "☆".repeat(5 - a.overall) + "  " + overallLabel(a.overall),
                    fmtDate(a.at) + " · " + a.version.ifBlank { "—" },
                    Tiles.Blue,
                    i + 2,
                    badge = a.overall.toString(),
                    selected = a.at == shownAt,
                    onClick = { onShow(a) }
                ) {
                    RowButton(Icons.Filled.Delete, { onDelete(a) })
                }
            }
        }
        FlatText(sv("history_hint"), color = p.muted, size = 11, lines = 3, modifier = Modifier.padding(12.dp))
    }
}

/** 右下角的轻提示：直接点星就打开问卷并带上分数。 */
@Composable
internal fun SurveyPrompt(
    visible: Boolean,
    modifier: Modifier = Modifier,
    onRate: (Int) -> Unit,
    onLater: () -> Unit,
    onNever: () -> Unit,
) {
    val p = LocalStudio.current
    AnimatedVisibility(
        visible,
        modifier,
        enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 2 } + fadeIn(tween(260)),
        exit = slideOutHorizontally(tween(220)) { it / 2 } + fadeOut(tween(180))
    ) {
        Row(Modifier.width(360.dp).height(IntrinsicSize.Min).background(p.panel).border(1.dp, p.strip)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(Tiles.Blue))
            Column(Modifier.weight(1f).padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Poll, contentDescription = null, tint = Tiles.Blue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    FlatText(sv("prompt_title"), color = p.text, size = 13, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Block("", onLater, icon = Icons.Filled.Close, height = 28.dp)
                }
                FlatText(sv("prompt_body"), color = p.muted, size = 11, lines = 2, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                StarRow(0, 34.dp, onRate)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Block(sv("prompt_later"), onLater, fill = p.field, height = 28.dp)
                    Block(sv("prompt_never"), onNever, height = 28.dp)
                }
            }
        }
    }
}
