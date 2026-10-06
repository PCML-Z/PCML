package com.pmcl.ui.page
import com.pmcl.ui.widget.PmclLazyColumn
import com.pmcl.ui.widget.pmclVerticalScroll
import com.pmcl.ui.widget.pmclHorizontalScroll

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.instance.InstanceInfo
import com.pmcl.core.market.McmodCatalog
import com.pmcl.core.market.ModFile
import com.pmcl.core.market.ModProject
import com.pmcl.core.version.MinecraftVersionIds
import com.pmcl.core.version.ModLoaders
import com.pmcl.core.version.ShaderLoaders
import com.pmcl.ui.animation.AnimatedSegmentedSelector
import com.pmcl.ui.animation.MotionTokens
import com.pmcl.ui.theme.glassContainerColor
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.searchMods
import com.pmcl.ui.viewmodel.searchMcmod
import com.pmcl.ui.viewmodel.openModDetail
import com.pmcl.ui.viewmodel.openMcmodEntry
import com.pmcl.ui.viewmodel.openMcmodHost
import com.pmcl.ui.viewmodel.dismissMcmodChoices
import com.pmcl.ui.viewmodel.closeModDetail
import com.pmcl.ui.viewmodel.listProjectFiles
import com.pmcl.ui.viewmodel.refreshInstalledMods
import com.pmcl.ui.viewmodel.installModWithDeps
import com.pmcl.ui.viewmodel.clearDepInstallResult
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pmcl.ui.util.decodeSampledBitmap
import java.awt.Desktop
import java.net.URI
import kotlin.math.ceil
import kotlin.math.max

private const val MARKET_PAGE_SIZE = 20
private const val TAB_GAME = 0
private const val TAB_AGGREGATE = 1
private const val TAB_MCMOD = 2
private const val TAB_CURSEFORGE = 3
private const val TAB_MODRINTH = 4
private const val TAB_PLUGINS = 5
private val ModrinthGreen = Color(0xFF1BD96A)
private val CurseForgeOrange = Color(0xFFF16436)
private val MarketFilterHeight = 36.dp
private val MarketFilterShape = RoundedCornerShape(8.dp)

@Composable
fun ModsMarketPage(vm: LauncherViewModel) {
    val results by vm.marketResults.collectAsState()
    val total by vm.marketTotal.collectAsState()
    val loading by vm.marketLoading.collectAsState()
    val mcmodResults by vm.mcmodResults.collectAsState()
    val mcmodPageCount by vm.mcmodPageCount.collectAsState()
    val mcmodChoices by vm.mcmodChoices.collectAsState()
    val mcmodOpeningId by vm.mcmodOpeningId.collectAsState()
    val detailProject by vm.detailProject.collectAsState()
    val translationCache by vm.translationCache.collectAsState()
    val depResult by vm.depInstallResult.collectAsState()
    val localVersionInfos by vm.localVersionInfos.collectAsState()
    val instances by vm.instances.collectAsState()

    val knownVersions = remember(localVersionInfos) { vm.knownMarketGameVersions() }
    val seeded = remember { vm.resolveMarketFilters() }

    var query by remember { mutableStateOf("") }
    var gameVersion by remember { mutableStateOf(seeded.gameVersion) }
    var loader by remember { mutableStateOf(seeded.loader) }
    var projectType by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("default") }
    var sourceTab by remember { mutableStateOf(TAB_AGGREGATE) }
    var pageIndex by remember { mutableStateOf(0) }
    var filesOnly by remember { mutableStateOf(false) }

    fun sourceFilter(): String? = when (sourceTab) {
        TAB_CURSEFORGE -> "curseforge"
        TAB_MODRINTH -> "modrinth"
        else -> null
    }

    fun runSearch(page: Int) {
        pageIndex = page
        if (sourceTab == TAB_MCMOD) {
            vm.searchMcmod(query, projectType.ifBlank { null }, page)
            return
        }
        val effectiveSort = if (sourceTab == TAB_CURSEFORGE && sort == "newest") "updated" else sort
        vm.searchMods(
            query = query,
            gameVersion = gameVersion.ifBlank { null },
            loader = loader.ifBlank { null },
            sort = effectiveSort,
            projectType = projectType.ifBlank { null },
            source = sourceFilter(),
            offset = page * MARKET_PAGE_SIZE,
            limit = MARKET_PAGE_SIZE,
        )
    }

    LaunchedEffect(sourceTab) {
        if (sourceTab != TAB_PLUGINS && sourceTab != TAB_MCMOD) runSearch(0)
    }

    val pageCount = if (sourceTab == TAB_MCMOD) max(1, mcmodPageCount)
        else max(1, ceil(total / MARKET_PAGE_SIZE.toDouble()).toInt())

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (detailProject != null) {
            val dp = detailProject
            if (dp != null) {
                ModDetailView(
                    project = dp,
                    vm = vm,
                    searchGameVersion = gameVersion,
                    searchLoader = if (sourceTab == TAB_MCMOD) "" else loader,
                    searchProjectType = projectType,
                    knownVersions = knownVersions,
                    localVersionInfos = localVersionInfos,
                    instances = instances,
                    translateEnabled = false,
                    translationCache = translationCache,
                    filesOnly = filesOnly,
                    onBack = { vm.closeModDetail() },
                    onFiltersChanged = { gv, ld ->
                        gameVersion = gv
                        loader = ld
                    }
                )
            }
        } else {
            MarketTabRow(
                selectedTab = sourceTab,
                onSelectGame = { vm.requestSecondaryNav("download", "versions") },
                onSelectSource = { tab ->
                    if (tab == TAB_CURSEFORGE && sort == "newest") sort = "updated"
                    if (tab == TAB_MCMOD) {
                        pageIndex = 0
                        if (projectType == "resourcepack" || projectType == "shader") projectType = ""
                        if (ShaderLoaders.normalize(loader).isNotEmpty()) loader = ""
                    }
                    sourceTab = tab
                }
            )

            if (sourceTab == TAB_PLUGINS) {
                PmclPluginStorePage(vm, Modifier.fillMaxWidth().weight(1f))
            } else {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterBarLabel(I18n.t("market.name"))
                CompactSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = if (sourceTab == TAB_MCMOD) I18n.t("market.mcmod_search_hint")
                        else I18n.t("market.search_name_hint"),
                    onSearch = { if (!loading) runSearch(0) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().pmclHorizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterBarLabel(I18n.t("market.search_options"))
                CompactVersionField(
                    value = gameVersion,
                    knownVersions = knownVersions,
                    onValueChange = { gameVersion = it },
                    placeholder = I18n.t("market.game_version_hint"),
                    modifier = Modifier.width(200.dp)
                )
                CompactFilterDropdown(
                    label = I18n.t("market.type"),
                    selectedValue = projectType,
                    options = if (sourceTab == TAB_MCMOD) listOf(
                        "" to I18n.t("market.type.mod"),
                        "modpack" to I18n.t("market.type.modpack"),
                    ) else listOf(
                        "" to I18n.t("market.all"),
                        "mod" to I18n.t("market.type.mod"),
                        "modpack" to I18n.t("market.type.modpack"),
                        "resourcepack" to I18n.t("market.type.resourcepack"),
                        "shader" to I18n.t("market.type.shader"),
                    ),
                    onSelect = {
                        loader = loaderForMarketType(it, loader)
                        projectType = it
                    },
                    modifier = Modifier.width(132.dp)
                )
                if (sourceTab != TAB_MCMOD) {
                CompactFilterDropdown(
                    label = I18n.t("market.sort"),
                    selectedValue = sort,
                    options = buildList {
                        add("default" to I18n.t("market.sort.default"))
                        add("downloads" to I18n.t("market.sort.downloads"))
                        add("updated" to I18n.t("market.sort.updated"))
                        if (sourceTab != TAB_CURSEFORGE) add("newest" to I18n.t("market.sort.newest"))
                    },
                    onSelect = { sort = it },
                    modifier = Modifier.width(132.dp)
                )
                if (projectType != "resourcepack") {
                val shaderSearch = projectType == "shader"
                CompactFilterDropdown(
                    label = I18n.t(if (shaderSearch) "market.shader_loader" else "market.loader"),
                    selectedValue = loader,
                    options = if (shaderSearch) marketShaderLoaderOptions() else marketModLoaderOptions(),
                    onSelect = { loader = it },
                    modifier = Modifier.width(if (shaderSearch) 188.dp else 140.dp)
                )
                }
                }
                MarketPrimaryButton(
                    onClick = { if (!loading) runSearch(0) },
                    enabled = !loading
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (loading) I18n.t("market.searching") else I18n.t("market.search"))
                }
            }

            if (sourceTab == TAB_MCMOD) {
                Text(
                    I18n.t("market.mcmod_hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }

            val cfMissing = sourceTab == TAB_CURSEFORGE && !vm.core.modMarket().hasCurseForge()
            when {
                sourceTab == TAB_MCMOD && mcmodResults.isEmpty() && !loading -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            if (query.isBlank()) I18n.t("market.mcmod_hint") else I18n.t("market.empty"),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                sourceTab == TAB_MCMOD -> {
                    PmclLazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        itemsIndexed(mcmodResults, key = { _, entry -> entry.kind + "/" + entry.id }) { _, entry ->
                            McmodListRow(
                                entry = entry,
                                opening = mcmodOpeningId == entry.kind + "/" + entry.id,
                                onClick = {
                                    vm.openMcmodEntry(
                                        entry,
                                        gameVersion.ifBlank { null },
                                        loader.ifBlank { null }
                                    )
                                }
                            )
                        }
                    }
                }
                cfMissing -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(I18n.t("market.curseforge_disabled"), color = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { vm.requestSecondaryNav("settings", "network") }) {
                                Text(I18n.t("market.curseforge_setup"))
                            }
                        }
                    }
                }
                results.isEmpty() && !loading -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(I18n.t("market.empty"), color = MaterialTheme.colorScheme.outline)
                    }
                }
                else -> {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val columns = when {
                            maxWidth >= 1080.dp -> 3
                            maxWidth >= 680.dp -> 2
                            else -> 1
                        }
                        val rows = results.chunked(columns)
                        PmclLazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            itemsIndexed(rows, key = { _, row ->
                                row.joinToString("|") { it.getSource() + "/" + it.getId() }
                            }) { _, row ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    row.forEach { project ->
                                        MarketProjectCard(
                                            project = project,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                vm.openModDetail(
                                                    project,
                                                    gameVersion.ifBlank { null },
                                                    if (project.projectType == "shader") null else loader.ifBlank { null }
                                                )
                                            }
                                        )
                                    }
                                    repeat(columns - row.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MarketIconButton(
                    onClick = { if (pageIndex > 0 && !loading) runSearch(pageIndex - 1) },
                    enabled = pageIndex > 0 && !loading,
                    hoverNudgeX = -6
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                Text(
                    I18n.t("market.page_indicator", pageIndex + 1, pageCount),
                    style = MaterialTheme.typography.bodyMedium
                )
                MarketIconButton(
                    onClick = { if (pageIndex + 1 < pageCount && !loading) runSearch(pageIndex + 1) },
                    enabled = pageIndex + 1 < pageCount && !loading,
                    hoverNudgeX = 6
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                ) {
                    Checkbox(checked = filesOnly, onCheckedChange = { filesOnly = it })
                    Text(
                        I18n.t("market.files_only"),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.clickable { filesOnly = !filesOnly }.padding(end = 8.dp)
                    )
                }
            }
            }
        }
    }

    if (depResult != null) {
        DependencyResultDialog(
            result = depResult!!,
            onDismiss = { vm.clearDepInstallResult() }
        )
    }
    mcmodChoices?.let { pending ->
        McmodLinkDialog(
            links = pending.links,
            onDismiss = { vm.dismissMcmodChoices() },
            onPick = { link ->
                vm.openMcmodHost(link, pending.gameVersion, pending.loader)
            }
        )
    }
}

@Composable
private fun MarketTabRow(
    selectedTab: Int,
    onSelectGame: () -> Unit,
    onSelectSource: (Int) -> Unit
) {
    AnimatedSegmentedSelector(
        items = listOf(
            I18n.t("market.tab.game"),
            I18n.t("market.tab.aggregate"),
            I18n.t("market.tab.mcmod"),
            I18n.t("market.tab.curseforge"),
            I18n.t("market.tab.modrinth"),
            I18n.t("market.tab.plugins"),
        ),
        selectedIndex = selectedTab,
        onSelect = { index ->
            if (index == 0) onSelectGame() else onSelectSource(index)
        },
        modifier = Modifier.fillMaxWidth(),
        fillWidth = true,
        height = 36.dp
    )
}

@Composable
private fun MarketRowCard(
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    val wash by animateColorAsState(
        when {
            pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            hovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
            else -> Color.Transparent
        },
        tween(MotionTokens.DURATION_SHORT),
        label = "marketRowWash"
    )
    val border by animateColorAsState(
        if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        tween(MotionTokens.DURATION_SHORT),
        label = "marketRowBorder"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(glassContainerColor(MaterialTheme.colorScheme.surface))
            .background(wash)
            .border(BorderStroke(1.dp, border), shape)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

@Composable
private fun MarketIconBox(
    label: String,
    image: ImageBitmap?,
    crop: Boolean,
    opening: Boolean = false,
    boxSize: Dp = 64.dp
) {
    Box(
        modifier = Modifier
            .size(boxSize)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = label,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = if (crop) Modifier.fillMaxSize() else Modifier.fillMaxSize().padding(6.dp)
            )
        } else if (!opening) {
            Text(
                label.take(1).ifBlank { "?" },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        if (opening) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun McmodListRow(
    entry: McmodCatalog.Entry,
    opening: Boolean,
    onClick: () -> Unit
) {
    val image = rememberUrlImage(entry.coverUrl)
    MarketRowCard(onClick = onClick, enabled = !opening) {
        MarketIconBox(entry.name, image, crop = true, opening = opening)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (entry.summary.isNotBlank()) {
                Text(
                    entry.summary,
                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                if (entry.kind == "modpack") I18n.t("market.type.modpack") else I18n.t("market.type.mod"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun McmodLinkDialog(
    links: List<McmodCatalog.HostLink>,
    onDismiss: () -> Unit,
    onPick: (McmodCatalog.HostLink) -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            Column(Modifier.width(480.dp).padding(16.dp)) {
                Text(
                    I18n.t("market.mcmod_pick_title"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    I18n.t("market.mcmod_pick_body"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    links.forEach { link ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(
                                    interactionSource = MutableInteractionSource(),
                                    indication = null,
                                    onClick = { onPick(link) }
                                )
                                .padding(horizontal = 8.dp, vertical = 10.dp)
                        ) {
                            Text(
                                mcmodHostLabel(link),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!link.opensInMarket()) {
                                Text(
                                    mcmodManualHint(link),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
                }
            }
        }
    }
}

private fun mcmodManualHint(link: McmodCatalog.HostLink): String {
    val raw = link.url.orEmpty().removePrefix("https://").removePrefix("http://")
    val shown = if (raw.length > 72) raw.take(69) + "…" else raw
    return listOf(shown, I18n.t("market.mcmod_manual")).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun mcmodHostLabel(link: McmodCatalog.HostLink): String {
    if (link.label.isNotBlank()) return link.label
    val host = when (link.source) {
        "curseforge" -> "CurseForge"
        "github" -> "GitHub"
        "modrinth" -> "Modrinth"
        else -> link.url.orEmpty().substringAfter("://").substringBefore("/")
    }
    val type = when (link.projectType) {
        "modpack" -> I18n.t("market.type.modpack")
        "resourcepack" -> I18n.t("market.type.resourcepack")
        "shader" -> I18n.t("market.type.shader")
        "mod" -> I18n.t("market.type.mod")
        else -> ""
    }
    return listOf(host, type, link.slug).filter { it.isNotBlank() }.joinToString(" · ")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarketProjectCard(
    project: ModProject,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val name = project.getName() ?: ""
    val summary = project.getSummary() ?: ""
    val author = project.getAuthor() ?: ""
    val cover = rememberUrlImage(project.getCoverUrl(), 640)
    val icon = rememberUrlImage(project.getIconUrl() ?: "", 128)
    val categories = remember(project) { marketCardCategories(project) }
    val loaders = remember(project) { marketCardLoaders(project) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(12.dp)
    val wash by animateColorAsState(
        when {
            pressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            hovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
            else -> Color.Transparent
        },
        tween(MotionTokens.DURATION_SHORT),
        label = "marketCardWash"
    )
    val border by animateColorAsState(
        if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
        tween(MotionTokens.DURATION_SHORT),
        label = "marketCardBorder"
    )
    Column(
        modifier
            .clip(shape)
            .background(glassContainerColor(MaterialTheme.colorScheme.surface))
            .background(wash)
            .border(BorderStroke(1.dp, border), shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            contentAlignment = Alignment.Center
        ) {
            if (cover != null) {
                Image(
                    cover,
                    name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (icon != null) {
                Image(
                    icon,
                    name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(72.dp)
                )
            } else {
                Text(
                    name.take(1).ifBlank { "?" },
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                MarketIconBox(name, icon, crop = true, boxSize = 42.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (author.isNotBlank()) {
                            Text(
                                "  " + I18n.t("market.by_author", author),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (summary.isNotBlank()) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (categories.isNotEmpty() || loaders.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { tag -> MarketTagChip(tag) }
                    loaders.forEach { loader ->
                        MarketTagChip(loaderChipLabel(loader)) {
                            LoaderChipMark(loader)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Text(
                    "  " + compactCount(project.getDownloadCount()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Text(
                    "  " + compactCount(project.getFollowCount()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Filled.AccessTime,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Text(
                    "  " + relativeTimeLabel(project.getDateModified()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun MarketTagChip(text: String, leading: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        leading?.invoke()
        Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun LoaderChipMark(loader: String) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    when (loader.lowercase()) {
        "optifine" -> Text(
            "OF",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = tint
        )
        "iris" -> Icon(Icons.Filled.FilterVintage, null, Modifier.size(12.dp), tint)
        else -> {}
    }
}

private fun marketModLoaderOptions(): List<Pair<String, String>> = listOf(
    "" to I18n.t("market.all"),
    "fabric" to "Fabric",
    "forge" to "Forge",
    "quilt" to "Quilt",
    "neoforge" to "NeoForge",
    "forbric" to "Forbric",
    "ecxp-forbric" to "ECXP-Forbric+",
)

private fun marketShaderLoaderOptions(): List<Pair<String, String>> = listOf(
    "" to I18n.t("market.all"),
    ShaderLoaders.IRIS to "Iris",
    ShaderLoaders.OPTIFINE to "OptiFine",
    ShaderLoaders.CANVAS to "Canvas",
    ShaderLoaders.VANILLA to I18n.t("market.shader.vanilla"),
)

/** 市场文件进哪条安装路径。光影和材质不进 mods，整合包走队列导入。 */
private fun marketContentKind(projectType: String?): String = when (projectType) {
    "modpack" -> "modpack"
    "shader" -> "shader"
    "resourcepack" -> "resourcepack"
    else -> "mod"
}

/** 光影用 Iris / OptiFine，材质包不按模组加载器筛。换类型时丢掉对不上的选项。 */
private fun loaderForMarketType(projectType: String, loader: String): String {
    val shaderLoader = ShaderLoaders.normalize(loader)
    return when (projectType) {
        "shader" -> shaderLoader
        "resourcepack" -> ""
        else -> if (shaderLoader.isNotEmpty()) "" else loader
    }
}

private fun marketCardCategories(project: ModProject): List<String> {
    return project.getCategories()
        .filter { it.isNotBlank() && !isLoaderTag(it) }
        .map { categoryLabel(it) }
        .distinct()
        .take(4)
}

private fun marketCardLoaders(project: ModProject): List<String> {
    val fromDisplay = project.getCategories()
        .map { it.lowercase() }
        .filter { it.isNotBlank() && isLoaderTag(it) }
    val source = if (fromDisplay.isNotEmpty()) fromDisplay
    else project.getLoaders().map { it.lowercase() }.filter { it.isNotBlank() }
    return source
        .filter { it != "datapack" && it != "rift" && it != "liteloader" }
        .distinct()
        .take(4)
}

private fun loaderChipLabel(loader: String): String = when (loader.lowercase()) {
    "optifine" -> "OptiFine"
    "neoforge" -> "NeoForge"
    "iris" -> "Iris"
    "canvas" -> "Canvas"
    "vanilla" -> "Vanilla"
    "fabric" -> "Fabric"
    "forge" -> "Forge"
    "quilt" -> "Quilt"
    "forbric" -> "Forbric"
    "ecxp-forbric" -> "ECXP-Forbric+"
    else -> loader.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}

@Composable
private fun FilterBarLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(min = 48.dp)
    )
}

@Composable
private fun compactFieldBorder(focused: Boolean): Color {
    return if (focused) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
}

@Composable
private fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        compactFieldBorder(focused),
        tween(MotionTokens.DURATION_SHORT),
        label = "searchBorder"
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = modifier.height(MarketFilterHeight),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(MarketFilterShape)
                    .background(glassContainerColor(MaterialTheme.colorScheme.surface))
                    .border(1.dp, border, MarketFilterShape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                inner()
            }
        }
    )
}

@Composable
private fun CompactVersionField(
    value: String,
    knownVersions: List<String>,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border by animateColorAsState(
        compactFieldBorder(focused || expanded),
        tween(MotionTokens.DURATION_SHORT),
        label = "versionBorder"
    )
    Box(modifier.height(MarketFilterHeight)) {
        Row(
            Modifier
                .fillMaxSize()
                .clip(MarketFilterShape)
                .background(glassContainerColor(MaterialTheme.colorScheme.surface))
                .border(1.dp, border, MarketFilterShape)
                .padding(start = 10.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                interactionSource = interaction,
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                placeholder,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        inner()
                    }
                }
            )
            if (knownVersions.isNotEmpty()) {
                val iconInteraction = remember { MutableInteractionSource() }
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .size(28.dp)
                        .hoverable(iconInteraction)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(
                            interactionSource = iconInteraction,
                            indication = null
                        ) { expanded = true }
                        .padding(4.dp)
                )
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(I18n.t("market.all")) },
                onClick = { onValueChange(""); expanded = false }
            )
            knownVersions.forEach { ver ->
                DropdownMenuItem(
                    text = { Text(ver) },
                    onClick = { onValueChange(ver); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun CompactFilterDropdown(
    label: String,
    selectedValue: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedValue }?.second
        ?: options.firstOrNull()?.second
        ?: selectedValue
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(
        compactFieldBorder(expanded || hovered),
        tween(MotionTokens.DURATION_SHORT),
        label = "filterBorder"
    )
    Box(modifier.height(MarketFilterHeight)) {
        Row(
            Modifier
                .fillMaxSize()
                .hoverable(interaction)
                .clip(MarketFilterShape)
                .background(glassContainerColor(MaterialTheme.colorScheme.surface))
                .border(1.dp, border, MarketFilterShape)
                .clickable(
                    interactionSource = interaction,
                    indication = null
                ) { expanded = true }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$label: $selectedLabel",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun MarketPrimaryButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val primary = MaterialTheme.colorScheme.primary
    val fillAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.45f
            pressed -> 0.88f
            else -> 1f
        },
        animationSpec = tween(MotionTokens.DURATION_SHORT),
        label = "primaryBtn"
    )
    Row(
        modifier
            .height(MarketFilterHeight)
            .hoverable(interaction, enabled = enabled)
            .clip(MarketFilterShape)
            .background(primary.copy(alpha = fillAlpha))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
            content()
            HoverSlideArrow(visible = hovered && enabled)
        }
    }
}

@Composable
private fun MarketOutlinedButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val fillAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0f
            pressed -> 0.16f
            hovered -> 0.10f
            else -> 0f
        },
        animationSpec = tween(MotionTokens.DURATION_SHORT),
        label = "outlinedBtn"
    )
    val border by animateColorAsState(
        targetValue = when {
            pressed || hovered -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
        },
        animationSpec = tween(MotionTokens.DURATION_SHORT),
        label = "outlinedBorder"
    )
    Row(
        modifier
            .height(MarketFilterHeight)
            .hoverable(interaction, enabled = enabled)
            .clip(MarketFilterShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = fillAlpha))
            .border(1.dp, border, MarketFilterShape)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        content()
        HoverSlideArrow(visible = hovered && enabled)
    }
}

@Composable
private fun MarketIconButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    hoverNudgeX: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val fillAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0f
            pressed -> 0.18f
            hovered -> 0.12f
            else -> 0f
        },
        animationSpec = tween(MotionTokens.DURATION_SHORT),
        label = "iconBtnBg"
    )
    val nudge by animateFloatAsState(
        targetValue = if (hovered && enabled) hoverNudgeX.toFloat() else 0f,
        animationSpec = tween(MotionTokens.DURATION_SHORT, easing = MotionTokens.EasingEmphasized),
        label = "iconNudge"
    )
    Box(
        modifier
            .size(40.dp)
            .hoverable(interaction, enabled = enabled)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = fillAlpha))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.offset { IntOffset(nudge.roundToInt(), 0) }) {
            content()
        }
    }
}

@Composable
private fun MarketTextButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val fillAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0f
            pressed -> 0.14f
            hovered -> 0.08f
            else -> 0f
        },
        animationSpec = tween(MotionTokens.DURATION_SHORT),
        label = "textBtnBg"
    )
    Row(
        modifier
            .hoverable(interaction, enabled = enabled)
            .clip(MarketFilterShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = fillAlpha))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
            content()
            HoverSlideArrow(visible = hovered && enabled)
        }
    }
}

@Composable
private fun HoverSlideArrow(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val shown by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(MotionTokens.DURATION_SHORT, easing = MotionTokens.EasingEmphasized),
        label = "arrowShown"
    )
    val nudge by rememberInfiniteTransition(label = "arrowNudge").animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(480, easing = MotionTokens.EasingStandard),
            repeatMode = RepeatMode.Reverse
        ),
        label = "nudge"
    )
    val tint = LocalContentColor.current
    Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null,
        tint = tint.copy(alpha = tint.alpha * shown),
        modifier = modifier
            .padding(start = 4.dp)
            .size(16.dp)
            .offset {
                IntOffset(
                    (((1f - shown) * -8f) + shown * nudge).roundToInt(),
                    0
                )
            }
    )
}

@Composable
private fun ColumnScope.ModDetailView(
    project: ModProject,
    vm: LauncherViewModel,
    searchGameVersion: String,
    searchLoader: String = "",
    searchProjectType: String = "",
    knownVersions: List<String> = emptyList(),
    localVersionInfos: List<com.pmcl.core.version.VersionManager.LocalVersionInfo> = emptyList(),
    instances: List<com.pmcl.core.instance.InstanceInfo> = emptyList(),
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap(),
    filesOnly: Boolean = false,
    onBack: () -> Unit,
    onFiltersChanged: (gameVersion: String, loader: String) -> Unit = { _, _ -> }
) {
    val selectedVersion by vm.selectedVersion.collectAsState()
    var filterGameVersion by remember(project.getId()) { mutableStateOf(searchGameVersion) }
    var filterLoader by remember(project.getId()) {
        mutableStateOf(if (project.projectType == "shader" || project.projectType == "resourcepack") "" else searchLoader)
    }
    var targetGameVersion by remember(project.getId()) { mutableStateOf(searchGameVersion) }
    var showAllFiles by remember(project.getId()) { mutableStateOf(false) }
    var filterCompatible by remember(project.getId()) { mutableStateOf(true) }
    val files by vm.currentModFiles.collectAsState()
    val filesLoading by vm.marketFilesLoading.collectAsState()
    val filesError by vm.marketFilesError.collectAsState()
    val installedMods by vm.installedMods.collectAsState()
    val isModpack = project.projectType == "modpack"
    val shaderProject = project.projectType == "shader"
    val ignoreLoader = project.projectType == "resourcepack"
    val localGames = remember(localVersionInfos, instances, installedMods, shaderProject, ignoreLoader) {
        marketLocalGameChips(
            localVersionInfos, instances, vm::deriveGameVersion, vm::deriveLoader,
            ignoreLoader, shaderProject, installedMods
        )
    }
    val shaderOptions = marketShaderLoaderOptions()
    var shaderSeeded by remember(project.getId()) { mutableStateOf(false) }

    LaunchedEffect(project.getId()) {
        if (shaderProject) vm.refreshInstalledMods()
    }

    LaunchedEffect(searchGameVersion, searchLoader) {
        filterGameVersion = searchGameVersion
        if (!shaderProject && !ignoreLoader) filterLoader = searchLoader
        else if (!shaderProject) filterLoader = ""
        if (searchGameVersion.isNotBlank()) {
            targetGameVersion = searchGameVersion
        }
    }

    LaunchedEffect(filterGameVersion) {
        if (filterGameVersion.isNotBlank()) {
            targetGameVersion = filterGameVersion
        }
    }

    val compatibleFiles = remember(files, filterGameVersion, filterLoader, filterCompatible, shaderProject) {
        if (!filterCompatible) files
        else files.filter { fileMatchesMarketFilter(it, filterGameVersion, filterLoader, shaderProject) }
    }
    val displayFiles = if (showAllFiles) compatibleFiles else compatibleFiles.take(15)

    val displayName = if (translateEnabled) translationCache[project.getName()] ?: project.getName() else project.getName()
    val displaySummary = if (translateEnabled) translationCache[project.getSummary()] ?: project.getSummary() else project.getSummary()

    fun reportFilters(gameVersion: String, loader: String) {
        val reported = when {
            ignoreLoader -> ""
            shaderProject && searchProjectType != "shader" -> ""
            else -> loader
        }
        onFiltersChanged(gameVersion, reported)
    }

    fun publishFilters(gameVersion: String, loader: String) {
        reportFilters(gameVersion, loader)
        vm.listProjectFiles(
            project,
            gameVersion.ifBlank { null },
            if (ignoreLoader) null else loader.ifBlank { null }
        )
    }

    fun currentShaderLoader(gameVersion: String): String {
        val selected = vm.selectedVersion.value
        val info = localVersionInfos.firstOrNull {
            it.id == selected && (gameVersion.isBlank() || vm.deriveGameVersion(it).equals(gameVersion, ignoreCase = true))
        } ?: localVersionInfos.firstOrNull {
            gameVersion.isNotBlank() && vm.deriveGameVersion(it).equals(gameVersion, ignoreCase = true)
        }
        if (info == null) return preferredShaderLoader(localGames, gameVersion)
        return shaderLoadersForVersion(info.id, info.inheritsFrom, instances, installedMods).firstOrNull().orEmpty()
    }

    LaunchedEffect(shaderProject, installedMods, localGames) {
        if (!shaderProject || shaderSeeded) return@LaunchedEffect
        val detected = localGames.any { it.loader.isNotBlank() && it.loader != ShaderLoaders.VANILLA }
        if (installedMods.isEmpty() && !detected) return@LaunchedEffect
        shaderSeeded = true
        val version = filterGameVersion.ifBlank { searchGameVersion }
        val loader = currentShaderLoader(version)
        if (loader.isNotBlank()) {
            filterLoader = loader
            filterCompatible = true
        }
    }

    fun loaderForGame(gameVersion: String, modLoader: String): String = when {
        shaderProject -> currentShaderLoader(gameVersion)
        ignoreLoader -> ""
        else -> modLoader
    }

    fun applyLocalGame(game: LocalGameChip) {
        filterGameVersion = game.gameVersion
        filterLoader = if (ignoreLoader) "" else game.loader
        filterCompatible = true
        showAllFiles = false
        if (game.gameVersion.isNotBlank()) targetGameVersion = game.gameVersion
        publishFilters(filterGameVersion, filterLoader)
    }

    fun clearLocalGame() {
        filterGameVersion = ""
        filterLoader = ""
        filterCompatible = true
        showAllFiles = false
        publishFilters("", "")
    }

    if (filesOnly) {
        Column(Modifier.fillMaxWidth().weight(1f)) {
            MarketTextButton(onClick = onBack) { Text(I18n.t("market.back")) }
            Text(
                displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().pmclHorizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CompactVersionField(
                    value = filterGameVersion,
                    knownVersions = knownVersions,
                    onValueChange = {
                        filterGameVersion = it
                        filterCompatible = true
                        showAllFiles = false
                        reportFilters(it, filterLoader)
                    },
                    placeholder = I18n.t("market.game_version_hint"),
                    modifier = Modifier.width(200.dp)
                )
                if (shaderProject) {
                CompactFilterDropdown(
                    label = I18n.t("market.shader_loader"),
                    selectedValue = filterLoader,
                    options = shaderOptions,
                    onSelect = {
                        filterLoader = it
                        filterCompatible = true
                        showAllFiles = false
                        publishFilters(filterGameVersion, it)
                    },
                    modifier = Modifier.width(168.dp)
                )
                } else if (!ignoreLoader) {
                CompactFilterDropdown(
                    label = I18n.t("market.loader"),
                    selectedValue = filterLoader,
                    options = marketModLoaderOptions(),
                    onSelect = {
                        filterLoader = it
                        filterCompatible = true
                        showAllFiles = false
                        publishFilters(filterGameVersion, it)
                    },
                    modifier = Modifier.width(140.dp)
                )
                }
                if (!isModpack) {
                    CompactSearchField(
                        value = targetGameVersion,
                        onValueChange = { targetGameVersion = it },
                        placeholder = I18n.t("market.target_mc_version"),
                        onSearch = {},
                        modifier = Modifier.width(160.dp)
                    )
                }
                MarketOutlinedButton(
                    onClick = {
                        val f = vm.resolveMarketFilters()
                        filterGameVersion = f.gameVersion
                        filterLoader = loaderForGame(f.gameVersion, f.loader)
                        filterCompatible = true
                        showAllFiles = false
                        publishFilters(filterGameVersion, filterLoader)
                    },
                    enabled = true
                ) {
                    Text(I18n.t("market.use_current_instance"))
                }
            }
            if (isModpack) {
                Text(
                    I18n.t("market.modpack_hint"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            FileListPane(
                files = files,
                compatibleFiles = compatibleFiles,
                displayFiles = displayFiles,
                filterGameVersion = filterGameVersion,
                filterLoader = filterLoader,
                filterCompatible = filterCompatible,
                showAllFiles = showAllFiles,
                targetGameVersion = targetGameVersion,
                filesLoading = filesLoading,
                filesError = filesError,
                contentKind = marketContentKind(project.projectType),
                localGames = localGames,
                ignoreLoader = ignoreLoader,
                vm = vm,
                onPickLocalGame = ::applyLocalGame,
                onClearLocalFilter = ::clearLocalGame,
                onToggleCompatible = {
                    filterCompatible = !filterCompatible
                    showAllFiles = false
                },
                onShowAll = { filterCompatible = false },
                onToggleShowAll = { showAllFiles = !showAllFiles }
            )
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth().weight(1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight()
                .pmclVerticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MarketTextButton(onClick = onBack) { Text(I18n.t("market.back")) }

            Surface(
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
                color = glassSurfaceVariantColor(),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    val image = rememberUrlImage(project.getIconUrl() ?: "")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center
                    ) {
                        if (image != null) {
                            Image(
                                image,
                                project.getName(),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                displayName.take(1).ifBlank { "?" },
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        displaySummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${project.getAuthor()}  ·  ${formatCount(project.getDownloadCount())}  ·  ${project.getSource()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MarketTextButton(onClick = {
                            try {
                                val url = project.getWebsiteUrl()
                                if (!url.isNullOrBlank() && Desktop.isDesktopSupported()) {
                                    Desktop.getDesktop().browse(URI(url))
                                }
                            } catch (_: Throwable) {
                            }
                        }) { Text(I18n.t("market.open_web")) }
                        MarketTextButton(onClick = {
                            vm.listProjectFiles(
                                project,
                                filterGameVersion.ifBlank { null },
                                filterLoader.ifBlank { null }
                            )
                        }) {
                            Text(I18n.t("market.refresh_versions"))
                        }
                    }
                }
            }

            Surface(
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
                color = glassContainerColor(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        I18n.t(if (shaderProject) "market.shader_filter_title" else "market.detail_filter_title"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    GameVersionFilterField(
                        value = filterGameVersion,
                        knownVersions = knownVersions,
                        onValueChange = {
                            filterGameVersion = it
                            filterCompatible = true
                            showAllFiles = false
                            reportFilters(it, filterLoader)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (shaderProject) {
                    LoaderDropdown(
                        selected = filterLoader,
                        label = I18n.t("market.shader_loader"),
                        options = shaderOptions,
                        onSelect = {
                            filterLoader = it
                            filterCompatible = true
                            showAllFiles = false
                            publishFilters(filterGameVersion, it)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    } else if (!ignoreLoader) {
                    LoaderDropdown(
                        selected = filterLoader,
                        onSelect = {
                            filterLoader = it
                            filterCompatible = true
                            showAllFiles = false
                            publishFilters(filterGameVersion, it)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    }
                    MarketOutlinedButton(
                        onClick = {
                            val f = vm.resolveMarketFilters()
                            filterGameVersion = f.gameVersion
                            filterLoader = loaderForGame(f.gameVersion, f.loader)
                            filterCompatible = true
                            showAllFiles = false
                            publishFilters(filterGameVersion, filterLoader)
                        },
                        enabled = !selectedVersion.isNullOrBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(I18n.t("market.use_current_instance"))
                    }
                    if (filterGameVersion.isNotBlank() || filterLoader.isNotBlank()) {
                        Text(
                            buildString {
                                append(I18n.t("market.detail_filter_hint"))
                                if (filterGameVersion.isNotBlank()) append(" · MC $filterGameVersion")
                                if (filterLoader.isNotBlank()) {
                                    append(" · ")
                                    append(if (shaderProject) shaderLoaderLabel(filterLoader) else filterLoader)
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Surface(
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isModpack) {
                        Text(
                            I18n.t("market.modpack_install_title"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            I18n.t("market.modpack_hint"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        Text(
                            I18n.t("market.download_to_version"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        OutlinedTextField(
                            value = targetGameVersion,
                            onValueChange = { targetGameVersion = it },
                            label = { Text(I18n.t("market.target_mc_version")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            I18n.t("market.download_dir_hint"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight()
        ) {
            FileListPane(
                files = files,
                compatibleFiles = compatibleFiles,
                displayFiles = displayFiles,
                filterGameVersion = filterGameVersion,
                filterLoader = filterLoader,
                filterCompatible = filterCompatible,
                showAllFiles = showAllFiles,
                targetGameVersion = targetGameVersion,
                filesLoading = filesLoading,
                filesError = filesError,
                contentKind = marketContentKind(project.projectType),
                localGames = localGames,
                ignoreLoader = ignoreLoader,
                vm = vm,
                onPickLocalGame = ::applyLocalGame,
                onClearLocalFilter = ::clearLocalGame,
                onToggleCompatible = {
                    filterCompatible = !filterCompatible
                    showAllFiles = false
                },
                onShowAll = { filterCompatible = false },
                onToggleShowAll = { showAllFiles = !showAllFiles }
            )
        }
    }
}

@Composable
private fun ColumnScope.FileListPane(
    files: List<com.pmcl.core.market.ModFile>,
    compatibleFiles: List<com.pmcl.core.market.ModFile>,
    displayFiles: List<com.pmcl.core.market.ModFile>,
    filterGameVersion: String,
    filterLoader: String,
    filterCompatible: Boolean,
    showAllFiles: Boolean,
    targetGameVersion: String,
    filesLoading: Boolean,
    filesError: String?,
    contentKind: String = "mod",
    localGames: List<LocalGameChip> = emptyList(),
    ignoreLoader: Boolean = false,
    vm: LauncherViewModel,
    onPickLocalGame: (LocalGameChip) -> Unit = {},
    onClearLocalFilter: () -> Unit = {},
    onToggleCompatible: () -> Unit,
    onShowAll: () -> Unit,
    onToggleShowAll: () -> Unit
) {
    val installingDeps by vm.installingDeps.collectAsState()
    val modpackBusy by vm.modpackBusy.collectAsState()
    val modpack = contentKind == "modpack"
    var pendingDownload by remember { mutableStateOf<PendingModDownload?>(null) }
    pendingDownload?.let { pending ->
        ModDownloadTargetDialog(
            preferredGameVersion = pending.preferredGameVersion,
            withDeps = pending.withDeps,
            contentKind = contentKind,
            vm = vm,
            onDismiss = { pendingDownload = null },
            onConfirm = { versionId, gameVersion, instanceId ->
                val file = pending.file
                val rect = pending.flyRect
                val deps = pending.withDeps
                pendingDownload = null
                when (contentKind) {
                    "modpack" -> vm.enqueueMarketModpack(file, instanceId)
                    "shader", "resourcepack" -> {
                        val enqueue = { vm.enqueueMarketContent(file, contentKind, versionId, instanceId) }
                        if (rect != null) {
                            vm.triggerFlyAnimation(rect, file.getFileName() ?: I18n.t("market.download"), enqueue)
                        } else {
                            enqueue()
                        }
                    }
                    else -> if (!deps && rect != null) {
                        vm.triggerFlyAnimation(rect, file.getFileName() ?: I18n.t("market.download")) {
                            vm.enqueueModDownload(file, gameVersion, versionId, instanceId)
                        }
                    } else if (deps) {
                        vm.installModWithDeps(file, gameVersion, versionId, instanceId)
                    } else {
                        vm.enqueueModDownload(file, gameVersion, versionId, instanceId)
                    }
                }
            }
        )
    }
    LocalGameFilterRow(
        games = localGames,
        filterGameVersion = filterGameVersion,
        filterLoader = filterLoader,
        ignoreLoader = ignoreLoader,
        onPick = onPickLocalGame,
        onClear = onClearLocalFilter
    )
    Spacer(Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            if (filterCompatible && (filterGameVersion.isNotBlank() || filterLoader.isNotBlank()))
                I18n.t("market.compatible_files", compatibleFiles.size, files.size)
            else
                I18n.t("market.version_files", files.size),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (filterGameVersion.isNotBlank() || filterLoader.isNotBlank()) {
            MarketTextButton(onClick = onToggleCompatible) {
                Text(
                    if (filterCompatible) I18n.t("market.show_all_versions")
                    else I18n.t("market.filter_compatible")
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    when {
        filesLoading && files.isEmpty() -> {
            Surface(
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
                color = glassSurfaceVariantColor(),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(I18n.t("common.loading"), color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        !filesError.isNullOrBlank() && files.isEmpty() -> {
            Surface(
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
                color = glassSurfaceVariantColor(),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(filesError ?: I18n.t("status.fetch_failed", I18n.t("common.unknown")),
                        color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        files.isEmpty() -> {
            Surface(
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
                color = glassSurfaceVariantColor(),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(I18n.t("market.files_empty"), color = MaterialTheme.colorScheme.outline)
                }
            }
        }
        compatibleFiles.isEmpty() -> {
            Surface(
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
                color = glassSurfaceVariantColor(),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(I18n.t("market.no_compatible_files"), color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    MarketTextButton(onClick = onShowAll) {
                        Text(I18n.t("market.show_all_versions"))
                    }
                }
            }
        }
        else -> {
            PmclLazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(displayFiles.size, key = { i ->
                    val f = displayFiles[i]
                    f.getSource() + "/" + f.getFileId()
                }) { i ->
                    FileRow(
                        displayFiles[i],
                        installingDeps,
                        modpack = modpack,
                        modpackBusy = modpackBusy,
                        showDeps = contentKind == "mod"
                    ) { file, withDeps, rect ->
                        val preferred = targetGameVersion.ifBlank {
                            (file.getGameVersions() ?: emptyList()).firstOrNull().orEmpty()
                        }
                        pendingDownload = PendingModDownload(file, withDeps && !modpack, preferred, rect)
                    }
                }
                if (compatibleFiles.size > 15) {
                    item {
                        MarketTextButton(onClick = onToggleShowAll) {
                            Text(
                                if (showAllFiles) I18n.t("market.collapse_files", compatibleFiles.size)
                                else I18n.t("market.show_all_files", compatibleFiles.size)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DependencyResultDialog(
    result: com.pmcl.core.mods.ModDependencyResolver.DependencyResult,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("mods.dep_result_title")) },
        text = {
            Column {
                Text(I18n.t("mods.dep_mod_label", result.modName),
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                if (result.installedDependencies.isNotEmpty()) {
                    Text(I18n.t("mods.dep_installed", result.installedDependencies.size),
                         style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.primary,
                         fontWeight = FontWeight.SemiBold)
                    result.installedDependencies.forEach { dep ->
                        Text("  + $dep",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (result.skippedInstalled.isNotEmpty()) {
                    Text(I18n.t("mods.dep_skipped", result.skippedInstalled.size),
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    result.skippedInstalled.forEach { dep ->
                        Text(I18n.t("market.dep_already_installed", dep),
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (result.skippedSystem.isNotEmpty()) {
                    Text(I18n.t("mods.dep_system"),
                         style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  ${result.skippedSystem.joinToString(", ")}",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                }

                if (result.notFound.isNotEmpty()) {
                    Text(I18n.t("mods.dep_not_found", result.notFound.size),
                         style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.error,
                         fontWeight = FontWeight.SemiBold)
                    result.notFound.forEach { dep ->
                        Text("  ? $dep",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                    Spacer(Modifier.height(6.dp))
                }

                if (result.failed.isNotEmpty()) {
                    Text(I18n.t("mods.dep_failed", result.failed.size),
                         style = MaterialTheme.typography.labelLarge,
                         color = MaterialTheme.colorScheme.error,
                         fontWeight = FontWeight.SemiBold)
                    result.failed.forEach { dep ->
                        Text("  ! $dep",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.error)
                    }
                }

                if (!result.hasInstalled() && result.notFound.isEmpty() && result.failed.isEmpty()) {
                    Text(I18n.t("mods.dep_no_extra"),
                         style = MaterialTheme.typography.bodyMedium,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            MarketTextButton(onClick = onDismiss) { Text(I18n.t("common.ok")) }
        }
    )
}

private data class PendingModDownload(
    val file: ModFile,
    val withDeps: Boolean,
    val preferredGameVersion: String,
    val flyRect: com.pmcl.ui.animation.Rect?
)

@Composable
private fun ModDownloadTargetDialog(
    preferredGameVersion: String,
    withDeps: Boolean,
    contentKind: String,
    vm: LauncherViewModel,
    onDismiss: () -> Unit,
    onConfirm: (versionId: String, gameVersion: String, instanceId: String?) -> Unit
) {
    val localInfos by vm.localVersionInfos.collectAsState()
    val instances by vm.instances.collectAsState()
    val selectedNow by vm.selectedVersion.collectAsState()
    val selectedInstanceNow by vm.selectedInstanceId.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        if (localInfos.isEmpty()) vm.refreshLocalVersions()
        if (instances.isEmpty()) vm.loadInstances()
    }
    val installed = remember(localInfos) {
        localInfos.filter { it.isLaunchable && !it.id.isNullOrBlank() }
    }
    val needle = query.trim()
    val shown = remember(installed, needle) {
        installed.filter { info ->
            if (needle.isEmpty()) return@filter true
            val game = MinecraftVersionIds.gameVersion(info.id, info.inheritsFrom)
            info.id.contains(needle, ignoreCase = true) || game.contains(needle, ignoreCase = true)
        }
    }
    var selected by remember(installed, selectedNow, preferredGameVersion) {
        mutableStateOf(
            installed.firstOrNull { it.id == selectedNow }?.id
                ?: installed.firstOrNull {
                    MinecraftVersionIds.gameVersion(it.id, it.inheritsFrom) == preferredGameVersion
                }?.id
                ?: installed.firstOrNull()?.id
                ?: ""
        )
    }
    val selectedInfo = installed.firstOrNull { it.id == selected }
    val selectedGame = selectedInfo?.let {
        MinecraftVersionIds.gameVersion(it.id, it.inheritsFrom)
    }.orEmpty()
    val versionInstances = remember(instances, selectedInfo, selectedGame, contentKind) {
        if (selectedInfo == null) emptyList()
        else instances.filter { inst ->
            instanceBelongsToVersion(inst, selectedInfo, selectedGame, contentKind, installed, vm)
        }
    }
    var instancePick by remember(selected, versionInstances, selectedInstanceNow) {
        mutableStateOf(
            versionInstances.firstOrNull { it.instanceId == selectedInstanceNow }?.instanceId.orEmpty()
        )
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier
                    .width(460.dp)
                    .height(600.dp)
                    .padding(16.dp)
            ) {
                Text(
                    I18n.t("market.download_target_title"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    I18n.t(when (contentKind) {
                        "shader" -> "market.download_target_body.shader"
                        "resourcepack" -> "market.download_target_body.resourcepack"
                        "modpack" -> "market.download_target_body.modpack"
                        else -> "market.download_target_body"
                    }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(I18n.t("launch.loader_target_search")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (shown.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            I18n.t("launch.loader_target_empty"),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                } else {
                    PmclLazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(shown.size, key = { shown[it].id }) { index ->
                            val info = shown[index]
                            val game = MinecraftVersionIds.gameVersion(info.id, info.inheritsFrom)
                            val picked = info.id == selected
                            Surface(
                                onClick = { selected = info.id },
                                shape = RoundedCornerShape(8.dp),
                                color = if (picked) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                shadowElevation = 0.dp,
                                tonalElevation = 0.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        info.id,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (game.isNotBlank() && game != info.id) {
                                        Text(
                                            I18n.t("market.download_target_mc", game),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (selected.isNotBlank()) {
                    Text(
                        I18n.t("market.download_target_instance"),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                    )
                    if (versionInstances.isEmpty()) {
                        Text(
                            I18n.t("market.download_target_instance_empty"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    PmclLazyColumn(
                        modifier = Modifier.weight(0.85f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item(key = "install-target-default") {
                            MarketInstanceChoice(
                                title = I18n.t(
                                    if (contentKind == "modpack") "market.download_target_new_game"
                                    else "market.download_target_version_only"
                                ),
                                subtitle = "",
                                picked = instancePick.isBlank(),
                                onClick = { instancePick = "" }
                            )
                        }
                        items(versionInstances.size, key = { versionInstances[it].instanceId }) { index ->
                            val inst = versionInstances[index]
                            MarketInstanceChoice(
                                title = inst.name?.ifBlank { inst.instanceId } ?: inst.instanceId,
                                subtitle = inst.loader?.trim().orEmpty(),
                                picked = instancePick == inst.instanceId,
                                onClick = { instancePick = inst.instanceId }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val info = installed.firstOrNull { it.id == selected } ?: return@Button
                            onConfirm(
                                info.id,
                                MinecraftVersionIds.gameVersion(info.id, info.inheritsFrom),
                                instancePick.takeIf { it.isNotBlank() }
                            )
                        },
                        enabled = selected.isNotBlank()
                    ) {
                        Text(
                            when {
                                contentKind == "modpack" && instancePick.isNotBlank() ->
                                    I18n.t("market.modpack_install_into")
                                contentKind == "modpack" -> I18n.t("market.modpack_install")
                                withDeps -> I18n.t("market.download_target_confirm_deps")
                                else -> I18n.t("market.download_target_confirm")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketInstanceChoice(
    title: String,
    subtitle: String,
    picked: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (picked) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (picked) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun instanceBelongsToVersion(
    inst: InstanceInfo,
    version: com.pmcl.core.version.VersionManager.LocalVersionInfo,
    game: String,
    contentKind: String,
    installed: List<com.pmcl.core.version.VersionManager.LocalVersionInfo>,
    vm: LauncherViewModel
): Boolean {
    if (!inst.isLaunchable) return false
    val base = inst.baseVersionId ?: return false
    if (base == version.id) return true
    val baseInfo = installed.firstOrNull { it.id == base }
    val instGame = if (baseInfo != null) {
        MinecraftVersionIds.gameVersion(baseInfo.id, baseInfo.inheritsFrom)
    } else {
        MinecraftVersionIds.gameVersion(base)
    }
    val sameGame = game.isNotBlank() && (base == game || instGame.equals(game, ignoreCase = true))
    if (!sameGame) return false
    if (contentKind == "shader" || contentKind == "resourcepack" || contentKind == "modpack") return true
    val versionLoader = ModLoaders.normalize(vm.deriveLoader(version))
    val instLoader = ModLoaders.normalize(inst.loader)
    return versionLoader.isEmpty() || instLoader.isEmpty() || instLoader == versionLoader
}

@Composable
private fun FileRow(
    f: ModFile,
    installingDeps: Boolean,
    modpack: Boolean = false,
    modpackBusy: Boolean = false,
    showDeps: Boolean = true,
    onRequestDownload: (ModFile, Boolean, com.pmcl.ui.animation.Rect?) -> Unit
) {
    var cardRect by remember { mutableStateOf<com.pmcl.ui.animation.Rect?>(null) }
    Surface(
        shadowElevation = 0.dp,
        tonalElevation = 0.dp,
        color = glassContainerColor(MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth()
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                cardRect = com.pmcl.ui.animation.Rect(
                    pos.x.toInt(), pos.y.toInt(),
                    coords.size.width, coords.size.height
                )
            }
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(f.getFileName() ?: "",
                     style = MaterialTheme.typography.bodySmall,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${(f.getGameVersions() ?: emptyList()).joinToString(",")} · ${f.getLoaders().joinToString(",")} · ${f.getReleaseType()}" +
                    if (f.getFileSize() > 0) " · ${f.getFileSize() / 1024}KB" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (modpack) {
                MarketPrimaryButton(
                    onClick = { onRequestDownload(f, false, cardRect) },
                    enabled = !modpackBusy
                ) {
                    if (modpackBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(I18n.t("market.modpack_install"))
                }
            } else {
                MarketPrimaryButton(onClick = {
                    onRequestDownload(f, false, cardRect)
                }) { Text(I18n.t("market.download")) }
                if (showDeps) {
                    Spacer(Modifier.width(8.dp))
                    MarketOutlinedButton(
                        onClick = { onRequestDownload(f, true, cardRect) },
                        enabled = !installingDeps
                    ) {
                        if (installingDeps) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(I18n.t("mods.with_deps"))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameVersionFilterField(
    value: String,
    knownVersions: List<String>,
    modifier: Modifier = Modifier.width(130.dp),
    onValueChange: (String) -> Unit,
    showLabel: Boolean = true,
    placeholder: String = I18n.t("market.all")
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = if (showLabel) ({ Text(I18n.t("market.target_version")) }) else null,
            singleLine = true,
            trailingIcon = {
                if (knownVersions.isNotEmpty()) {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            placeholder = {
                Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        )
        if (knownVersions.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(I18n.t("market.all")) },
                    onClick = { onValueChange(""); expanded = false }
                )
                knownVersions.forEach { ver ->
                    DropdownMenuItem(
                        text = { Text(ver) },
                        onClick = { onValueChange(ver); expanded = false }
                    )
                }
            }
        }
    }
}

private data class LocalGameChip(
    val gameVersion: String,
    val loader: String,
    val label: String
)

private fun marketLocalGameChips(
    infos: List<com.pmcl.core.version.VersionManager.LocalVersionInfo>,
    instances: List<com.pmcl.core.instance.InstanceInfo>,
    deriveVersion: (com.pmcl.core.version.VersionManager.LocalVersionInfo) -> String,
    deriveLoader: (com.pmcl.core.version.VersionManager.LocalVersionInfo) -> String,
    ignoreLoader: Boolean,
    shader: Boolean,
    installedMods: List<com.pmcl.core.mods.ModMeta>
): List<LocalGameChip> {
    val namesByVersion = HashMap<String, MutableList<String>>()
    for (inst in instances) {
        val base = inst.baseVersionId ?: continue
        val name = inst.name?.trim().orEmpty()
        if (base.isBlank() || name.isBlank()) continue
        namesByVersion.getOrPut(base) { mutableListOf() }.add(name)
    }
    data class Draft(
        val gameVersion: String,
        val loader: String,
        val ids: MutableList<String> = mutableListOf(),
        val names: MutableList<String> = mutableListOf()
    )
    val drafts = LinkedHashMap<String, Draft>()
    fun add(gameVersion: String, loader: String, id: String, names: List<String>) {
        val key = gameVersion.lowercase() + "\u0000" + loader
        val draft = drafts.getOrPut(key) { Draft(gameVersion, loader) }
        if (id.isNotBlank() && id !in draft.ids) draft.ids.add(id)
        for (name in names) {
            if (name.isNotBlank() && name !in draft.names) draft.names.add(name)
        }
    }
    for (info in infos) {
        if (!info.isLaunchable || info.id.isNullOrBlank()) continue
        val gameVersion = deriveVersion(info).trim()
        if (gameVersion.isBlank()) continue
        if (ignoreLoader) {
            add(gameVersion, "", info.id, namesByVersion[info.id].orEmpty())
            continue
        }
        if (shader) {
            val names = namesByVersion[info.id].orEmpty()
            val loaders = shaderLoadersForVersion(info.id, info.inheritsFrom, instances, installedMods)
            for (loader in loaders) add(gameVersion, loader, info.id, names)
            continue
        }
        val versionLoader = ModLoaders.normalize(deriveLoader(info))
        val related = instances.filter { it.baseVersionId == info.id }
        val loaders = related.map { ModLoaders.normalize(it.loader) }.filter { it.isNotEmpty() }.distinct()
        if (loaders.isEmpty()) {
            add(gameVersion, versionLoader, info.id, namesByVersion[info.id].orEmpty())
        } else {
            if (versionLoader.isNotEmpty() && versionLoader !in loaders) {
                add(gameVersion, versionLoader, info.id, emptyList())
            }
            for (loader in loaders) {
                val names = related
                    .filter { ModLoaders.normalize(it.loader) == loader }
                    .mapNotNull { it.name?.trim() }
                add(gameVersion, loader, info.id, names)
            }
        }
    }
    return drafts.values.map { draft ->
        LocalGameChip(
            draft.gameVersion,
            draft.loader,
            localGameChipLabel(draft.ids, draft.names, draft.gameVersion, draft.loader)
        )
    }.sortedWith(Comparator { a, b ->
        val byVersion = compareMcVersion(b.gameVersion, a.gameVersion)
        if (byVersion != 0) byVersion else a.loader.compareTo(b.loader)
    })
}

private fun compareMcVersion(left: String, right: String): Int {
    val a = mcVersionRank(left)
    val b = mcVersionRank(right)
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val diff = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

private fun localGameChipLabel(
    versionIds: List<String>,
    instanceNames: List<String>,
    gameVersion: String,
    loader: String
): String {
    val loaderName = when (loader) {
        "fabric" -> "Fabric"
        "forbric" -> "Forbric"
        "ecxp-forbric" -> "ECXP-Forbric+"
        "forge" -> "Forge"
        "quilt" -> "Quilt"
        "neoforge" -> "NeoForge"
        "iris", "optifine", "canvas", "vanilla" -> shaderLoaderLabel(loader)
        else -> ""
    }
    val title = when {
        instanceNames.size == 1 -> instanceNames[0]
        versionIds.size == 1 -> versionIds[0]
        else -> gameVersion
    }
    val parts = mutableListOf(title)
    if (gameVersion.isNotBlank() && !title.contains(gameVersion, ignoreCase = true)) parts.add(gameVersion)
    if (loaderName.isNotEmpty() && !title.contains(loader, ignoreCase = true)) parts.add(loaderName)
    return parts.joinToString(" · ")
}

private fun mcVersionRank(version: String): List<Int> {
    val nums = Regex("""\d+""").findAll(version).map { it.value.toInt() }.take(4).toList()
    return if (nums.isEmpty()) listOf(0) else nums
}

@Composable
private fun LocalGameFilterRow(
    games: List<LocalGameChip>,
    filterGameVersion: String,
    filterLoader: String,
    ignoreLoader: Boolean,
    onPick: (LocalGameChip) -> Unit,
    onClear: () -> Unit
) {
    val allSelected = filterGameVersion.isBlank() && (ignoreLoader || filterLoader.isBlank())
    Column(Modifier.fillMaxWidth()) {
        Text(
            I18n.t("market.local_game_filter"),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        if (games.isEmpty()) {
            Text(
                I18n.t("market.local_game_empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().pmclHorizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LocalGameChipButton(I18n.t("market.local_game_all"), allSelected, onClear)
            games.forEach { game ->
                val selected = !allSelected &&
                    game.gameVersion.equals(filterGameVersion, ignoreCase = true) &&
                    (ignoreLoader || game.loader.equals(filterLoader, ignoreCase = true))
                LocalGameChipButton(game.label, selected) { onPick(game) }
            }
        }
    }
}

@Composable
private fun LocalGameChipButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border = when {
        selected -> MaterialTheme.colorScheme.primary
        hovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    }
    val fill = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    else glassContainerColor(MaterialTheme.colorScheme.surface)
    Box(
        Modifier
            .height(32.dp)
            .widthIn(max = 240.dp)
            .hoverable(interaction)
            .clip(RoundedCornerShape(8.dp))
            .background(fill)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun preferredShaderLoader(games: List<LocalGameChip>, version: String): String {
    val mine = if (version.isBlank()) games else games.filter { it.gameVersion.equals(version, ignoreCase = true) }
    for (id in listOf(ShaderLoaders.IRIS, ShaderLoaders.OPTIFINE, ShaderLoaders.CANVAS, ShaderLoaders.VANILLA)) {
        if (mine.any { it.loader == id }) return id
    }
    return ""
}

private fun shaderLoaderLabel(loader: String): String = when (loader) {
    ShaderLoaders.IRIS -> "Iris"
    ShaderLoaders.OPTIFINE -> "OptiFine"
    ShaderLoaders.CANVAS -> "Canvas"
    ShaderLoaders.VANILLA -> I18n.t("market.shader.vanilla")
    else -> loader
}

private fun shaderLoadersForVersion(
    versionId: String,
    inheritsFrom: String?,
    instances: List<com.pmcl.core.instance.InstanceInfo>,
    mods: List<com.pmcl.core.mods.ModMeta>
): List<String> {
    val related = instances.filter { it.baseVersionId == versionId }
    val names = related.mapNotNull { it.name?.trim() }.filter { it.isNotEmpty() }.toSet()
    val instanceIds = related.mapNotNull { it.instanceId }.filter { it.isNotBlank() }.toSet()
    fun belongs(mod: com.pmcl.core.mods.ModMeta): Boolean {
        if (mod.isDisabled) return false
        val source = mod.source ?: ""
        if (source == versionId || source in names) return true
        val path = (mod.jarPath ?: "").replace('\\', '/')
        if (path.contains("/versions/$versionId/mods/")) return true
        return instanceIds.any { path.contains("/instances/$it/mods/") }
    }
    val specific = mods.filter { belongs(it) }
    val fromId = ShaderLoaders.detect(versionId, inheritsFrom, emptyList(), emptyList())
    val chosen = when {
        specific.isNotEmpty() -> specific
        fromId.any { it != ShaderLoaders.VANILLA } -> emptyList()
        else -> mods.filter { !it.isDisabled && (it.source == "全局" || it.source == "系统") }
    }
    return ShaderLoaders.detect(
        versionId,
        inheritsFrom,
        chosen.mapNotNull { it.modId },
        chosen.mapNotNull { it.jarFile }
    )
}

private fun fileMatchesMarketFilter(
    file: com.pmcl.core.market.ModFile,
    gameVersion: String,
    loader: String,
    shader: Boolean = false
): Boolean {
    val gv = gameVersion.trim()
    val ld = loader.trim()
    if (gv.isNotEmpty()) {
        val versions = file.getGameVersions() ?: emptyList()
        if (versions.none { it.equals(gv, ignoreCase = true) }) return false
    }
    val wanted = if (shader) ShaderLoaders.normalize(ld) else ModLoaders.normalize(ld)
    if (wanted.isNotEmpty()) {
        val loaders = file.getLoaders() ?: emptyList()
        val matches = if (shader) loaders.any { ShaderLoaders.normalize(it) == wanted }
        else if (wanted == "forbric" || wanted == "ecxp-forbric") loaders.any {
            val name = ModLoaders.normalize(it)
            name == "fabric" || name == "forge" || name == "neoforge"
        }
        else loaders.any { ModLoaders.normalize(it) == wanted }
        if (!matches) return false
    }
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoaderDropdown(
    selected: String,
    modifier: Modifier = Modifier.width(120.dp),
    label: String = I18n.t("market.loader"),
    options: List<Pair<String, String>> = listOf(
        "fabric" to "Fabric",
        "forge" to "Forge",
        "quilt" to "Quilt",
        "neoforge" to "NeoForge",
        "forbric" to "Forbric",
        "ecxp-forbric" to "ECXP-Forbric+",
        "" to I18n.t("market.all"),
    ),
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second
        ?: if (selected.isEmpty()) I18n.t("market.all") else selected
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (opt, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}

private val MOD_CATEGORIES: List<Pair<String, String>> = listOf(
    "market.cat.all" to "",
    "market.cat.optimization" to "optimization",
    "market.cat.technology" to "technology",
    "market.cat.magic" to "magic",
    "market.cat.adventure" to "adventure",
    "market.cat.decoration" to "decoration",
    "market.cat.utility" to "utility",
    "market.cat.library" to "library",
    "market.cat.mobs" to "mobs",
    "market.cat.food" to "food",
    "market.cat.worldgen" to "worldgen",
    "market.cat.storage" to "storage",
    "market.cat.equipment" to "equipment",
    "market.cat.transportation" to "transportation",
    "market.cat.social" to "social",
    "market.cat.game_mechanics" to "game-mechanics",
    "market.cat.colored_lighting" to "colored-lighting",
    "market.cat.vanilla_like" to "vanilla-like",
    "market.cat.fantasy" to "fantasy",
    "market.cat.bloom" to "bloom",
    "market.cat.cartoon" to "cartoon",
    "market.cat.low" to "low",
    "market.cat.medium" to "medium",
    "market.cat.high" to "high",
    "market.cat.potato" to "potato",
    "market.cat.atmosphere" to "atmosphere",
    "market.cat.semi_realistic" to "semi-realistic",
    "market.cat.realistic" to "realistic",
    "market.cat.shadows" to "shadows",
    "market.cat.reflections" to "reflections",
    "market.cat.foliage" to "foliage",
    "market.cat.path_tracing" to "path-tracing",
    "market.cat.pbr" to "pbr"
)

private fun categoryLabel(slug: String): String {
    val key = when (slug.lowercase()) {
        "performance" -> "optimization"
        "game_mechanics" -> "game-mechanics"
        else -> slug.lowercase()
    }
    val entry = MOD_CATEGORIES.firstOrNull { it.second == key } ?: return slug
    return I18n.t(entry.first)
}

private fun isLoaderTag(tag: String): Boolean {
    val s = tag.lowercase()
    return s == "fabric" || s == "forge" || s == "quilt" || s == "neoforge" || s == "forbric"
            || s == "ecxp-forbric"
            || s == "rift" || s == "liteloader" || s == "datapack"
            || s == "iris" || s == "optifine" || s == "canvas" || s == "vanilla"
}

private fun formatDownloads(n: Long): String = "%,d".format(n)

private fun relativeTimeLabel(epochMs: Long): String {
    if (epochMs <= 0L) return I18n.t("market.rel_unknown")
    val diff = System.currentTimeMillis() - epochMs
    if (diff < 60_000L) return I18n.t("market.rel_just_now")
    val minutes = diff / 60_000L
    if (minutes < 60) return I18n.t("market.rel_minutes", minutes)
    val hours = minutes / 60
    if (hours < 24) return I18n.t("market.rel_hours", hours)
    val days = hours / 24
    if (days < 7) return I18n.t("market.rel_days", days)
    val weeks = days / 7
    if (weeks < 5) return I18n.t("market.rel_weeks", weeks)
    val months = days / 30
    if (months < 12) return I18n.t("market.rel_months", months)
    return I18n.t("market.rel_years", months / 12)
}

private val modImageCache = com.pmcl.ui.util.LruImageCache()

private fun compactCount(n: Long): String {
    if (n < 10_000) return "%,d".format(n)
    val lang = I18n.getCurrentLocale().language
    if (lang == "zh" || lang == "ja") {
        val wan = n / 10_000.0
        val digits = if (wan >= 10) "%.2f" else "%.1f"
        return String.format(java.util.Locale.US, digits, wan) + "万"
    }
    return if (n >= 1_000_000) String.format(java.util.Locale.US, "%.2fM", n / 1_000_000.0)
    else String.format(java.util.Locale.US, "%.1fK", n / 1_000.0)
}

@Composable
private fun rememberUrlImage(url: String, maxDimension: Int = 128): ImageBitmap? {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(modImageCache.get(url)) }
    LaunchedEffect(url) {
        if (url.isEmpty()) {
            image = null
            return@LaunchedEffect
        }
        if (modImageCache.isKnownFailed(url)) { image = null; return@LaunchedEffect }
        val existing = modImageCache.get(url)
        if (existing != null) { image = existing; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                val bytes = com.pmcl.ui.util.SafeUrlFetcher.fetchBytes(url)
                val bmp = decodeSampledBitmap(bytes, maxDimension) ?: throw IllegalStateException("decode failed")
                modImageCache.put(url, bmp)
                image = bmp
            } catch (_: Throwable) {
                modImageCache.markFailed(url)
                image = null
            }
        }
    }
    return image
}

private fun formatCount(n: Long): String {
    return when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
        else -> n.toString()
    }
}
