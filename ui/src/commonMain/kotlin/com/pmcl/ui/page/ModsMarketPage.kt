package com.pmcl.ui.page

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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.Download
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.market.ModProject
import com.pmcl.ui.animation.AnimatedSegmentedSelector
import com.pmcl.ui.animation.MotionTokens
import com.pmcl.ui.theme.glassContainerColor
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.searchMods
import com.pmcl.ui.viewmodel.openModDetail
import com.pmcl.ui.viewmodel.closeModDetail
import com.pmcl.ui.viewmodel.listProjectFiles
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
private val ModrinthGreen = Color(0xFF1BD96A)
private val CurseForgeOrange = Color(0xFFF16436)
private val MarketFilterHeight = 36.dp
private val MarketFilterShape = RoundedCornerShape(8.dp)

@Composable
fun ModsMarketPage(vm: LauncherViewModel) {
    val results by vm.marketResults.collectAsState()
    val total by vm.marketTotal.collectAsState()
    val loading by vm.marketLoading.collectAsState()
    val detailProject by vm.detailProject.collectAsState()
    val translationCache by vm.translationCache.collectAsState()
    val depResult by vm.depInstallResult.collectAsState()
    val localVersionInfos by vm.localVersionInfos.collectAsState()

    val knownVersions = remember(localVersionInfos) { vm.knownMarketGameVersions() }
    val seeded = remember { vm.resolveMarketFilters() }

    var query by remember { mutableStateOf("") }
    var gameVersion by remember { mutableStateOf(seeded.gameVersion) }
    var loader by remember { mutableStateOf(seeded.loader) }
    var projectType by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("default") }
    var sourceTab by remember { mutableStateOf(1) } // 1 聚合 / 2 CF / 3 MR
    var pageIndex by remember { mutableStateOf(0) }
    var filesOnly by remember { mutableStateOf(false) }

    fun sourceFilter(): String? = when (sourceTab) {
        2 -> "curseforge"
        3 -> "modrinth"
        else -> null
    }

    fun runSearch(page: Int) {
        pageIndex = page
        val effectiveSort = if (sourceTab == 2 && sort == "newest") "updated" else sort
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

    LaunchedEffect(sourceTab) { runSearch(0) }

    val pageCount = max(1, ceil(total / MARKET_PAGE_SIZE.toDouble()).toInt())

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (detailProject != null) {
            val dp = detailProject
            if (dp != null) {
                ModDetailView(
                    project = dp,
                    vm = vm,
                    searchGameVersion = gameVersion,
                    searchLoader = loader,
                    knownVersions = knownVersions,
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
                    if (tab == 2 && sort == "newest") sort = "updated"
                    sourceTab = tab
                }
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterBarLabel(I18n.t("market.name"))
                CompactSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = I18n.t("market.search_name_hint"),
                    onSearch = { if (!loading) runSearch(0) },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                    options = listOf(
                        "" to I18n.t("market.all"),
                        "mod" to I18n.t("market.type.mod"),
                        "resourcepack" to I18n.t("market.type.resourcepack"),
                        "shader" to I18n.t("market.type.shader"),
                    ),
                    onSelect = { projectType = it },
                    modifier = Modifier.width(132.dp)
                )
                CompactFilterDropdown(
                    label = I18n.t("market.sort"),
                    selectedValue = sort,
                    options = buildList {
                        add("default" to I18n.t("market.sort.default"))
                        add("downloads" to I18n.t("market.sort.downloads"))
                        add("updated" to I18n.t("market.sort.updated"))
                        if (sourceTab != 2) add("newest" to I18n.t("market.sort.newest"))
                    },
                    onSelect = { sort = it },
                    modifier = Modifier.width(132.dp)
                )
                CompactFilterDropdown(
                    label = I18n.t("market.loader"),
                    selectedValue = loader,
                    options = listOf(
                        "" to I18n.t("market.all"),
                        "fabric" to "Fabric",
                        "forge" to "Forge",
                        "quilt" to "Quilt",
                        "neoforge" to "NeoForge",
                    ),
                    onSelect = { loader = it },
                    modifier = Modifier.width(140.dp)
                )
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

            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            } else {
                Spacer(Modifier.height(8.dp))
            }

            val cfMissing = sourceTab == 2 && !vm.core.modMarket().hasCurseForge()
            when {
                cfMissing -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(I18n.t("market.curseforge_disabled"), color = MaterialTheme.colorScheme.outline)
                    }
                }
                results.isEmpty() && !loading -> {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text(I18n.t("market.empty"), color = MaterialTheme.colorScheme.outline)
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(results, key = { _, p -> p.getSource() + "/" + p.getId() }) { index, project ->
                            MarketListRow(
                                project = project,
                                onClick = {
                                    vm.openModDetail(
                                        project,
                                        gameVersion.ifBlank { null },
                                        loader.ifBlank { null }
                                    )
                                }
                            )
                            if (index < results.lastIndex) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
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

    if (depResult != null) {
        DependencyResultDialog(
            result = depResult!!,
            onDismiss = { vm.clearDepInstallResult() }
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
            I18n.t("market.tab.curseforge"),
            I18n.t("market.tab.modrinth"),
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
private fun MarketListRow(project: ModProject, onClick: () -> Unit) {
    val tags = remember(project) { marketRowTags(project) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val bgAlpha by animateFloatAsState(
        targetValue = when {
            pressed -> 0.45f
            hovered -> 0.28f
            else -> 0f
        },
        animationSpec = tween(MotionTokens.DURATION_SHORT),
        label = "marketRowBg"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = bgAlpha))
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val image = rememberUrlImage(project.getIconUrl() ?: "")
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                    RoundedCornerShape(8.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = project.getName(),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp)
                )
            } else {
                Text(
                    project.getName().take(1).ifBlank { "?" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                project.getName() ?: "",
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                project.getSummary() ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
            ) {
                tags.take(8).forEach { tag ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                I18n.t(
                    "market.downloads_updated",
                    formatDownloads(project.getDownloadCount()),
                    relativeTimeLabel(project.getDateModified())
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TypeBadge(project.getProjectType())
            SourceBadge(project.getSource())
        }
        HoverSlideArrow(visible = hovered, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun TypeBadge(projectType: String) {
    val label = when (projectType) {
        "resourcepack" -> I18n.t("market.type.resourcepack")
        "shader" -> I18n.t("market.type.shader")
        else -> I18n.t("market.type.mod")
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.Download,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun SourceBadge(source: String) {
    val isMr = source.equals("modrinth", ignoreCase = true)
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isMr) ModrinthGreen else CurseForgeOrange)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            if (isMr) "Modrinth" else "CurseForge",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
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
    knownVersions: List<String> = emptyList(),
    translateEnabled: Boolean = false,
    translationCache: Map<String, String> = emptyMap(),
    filesOnly: Boolean = false,
    onBack: () -> Unit,
    onFiltersChanged: (gameVersion: String, loader: String) -> Unit = { _, _ -> }
) {
    val selectedVersion by vm.selectedVersion.collectAsState()
    var filterGameVersion by remember(project.getId()) { mutableStateOf(searchGameVersion) }
    var filterLoader by remember(project.getId()) { mutableStateOf(searchLoader) }
    var targetGameVersion by remember(project.getId()) { mutableStateOf(searchGameVersion) }
    var showAllFiles by remember(project.getId()) { mutableStateOf(false) }
    var filterCompatible by remember(project.getId()) { mutableStateOf(true) }
    val files by vm.currentModFiles.collectAsState()
    val filesLoading by vm.marketFilesLoading.collectAsState()
    val filesError by vm.marketFilesError.collectAsState()

    LaunchedEffect(searchGameVersion, searchLoader) {
        filterGameVersion = searchGameVersion
        filterLoader = searchLoader
        if (searchGameVersion.isNotBlank()) {
            targetGameVersion = searchGameVersion
        }
    }

    LaunchedEffect(filterGameVersion) {
        if (filterGameVersion.isNotBlank()) {
            targetGameVersion = filterGameVersion
        }
    }

    val compatibleFiles = remember(files, filterGameVersion, filterLoader, filterCompatible) {
        if (!filterCompatible) files
        else files.filter { fileMatchesMarketFilter(it, filterGameVersion, filterLoader) }
    }
    val displayFiles = if (showAllFiles) compatibleFiles else compatibleFiles.take(15)

    val displayName = if (translateEnabled) translationCache[project.getName()] ?: project.getName() else project.getName()
    val displaySummary = if (translateEnabled) translationCache[project.getSummary()] ?: project.getSummary() else project.getSummary()

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
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
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
                        onFiltersChanged(it, filterLoader)
                    },
                    placeholder = I18n.t("market.game_version_hint"),
                    modifier = Modifier.width(200.dp)
                )
                CompactFilterDropdown(
                    label = I18n.t("market.loader"),
                    selectedValue = filterLoader,
                    options = listOf(
                        "" to I18n.t("market.all"),
                        "fabric" to "Fabric",
                        "forge" to "Forge",
                        "quilt" to "Quilt",
                        "neoforge" to "NeoForge",
                    ),
                    onSelect = {
                        filterLoader = it
                        filterCompatible = true
                        showAllFiles = false
                        onFiltersChanged(filterGameVersion, it)
                    },
                    modifier = Modifier.width(140.dp)
                )
                CompactSearchField(
                    value = targetGameVersion,
                    onValueChange = { targetGameVersion = it },
                    placeholder = I18n.t("market.target_mc_version"),
                    onSearch = {},
                    modifier = Modifier.width(160.dp)
                )
                MarketOutlinedButton(
                    onClick = {
                        val f = vm.resolveMarketFilters()
                        filterGameVersion = f.gameVersion
                        filterLoader = f.loader
                        filterCompatible = true
                        showAllFiles = false
                        onFiltersChanged(f.gameVersion, f.loader)
                    },
                    enabled = true
                ) {
                    Text(I18n.t("market.use_current_instance"))
                }
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
                vm = vm,
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
                .verticalScroll(rememberScrollState()),
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
                        I18n.t("market.detail_filter_title"),
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
                            onFiltersChanged(it, filterLoader)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    LoaderDropdown(
                        selected = filterLoader,
                        onSelect = {
                            filterLoader = it
                            filterCompatible = true
                            showAllFiles = false
                            onFiltersChanged(filterGameVersion, it)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    MarketOutlinedButton(
                        onClick = {
                            val f = vm.resolveMarketFilters()
                            filterGameVersion = f.gameVersion
                            filterLoader = f.loader
                            filterCompatible = true
                            showAllFiles = false
                            onFiltersChanged(f.gameVersion, f.loader)
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
                                if (filterLoader.isNotBlank()) append(" · $filterLoader")
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
                        I18n.t("market.download_dir_hint", targetGameVersion),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
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
                vm = vm,
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
    vm: LauncherViewModel,
    onToggleCompatible: () -> Unit,
    onShowAll: () -> Unit,
    onToggleShowAll: () -> Unit
) {
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                items(displayFiles.size, key = { i ->
                    val f = displayFiles[i]
                    f.getSource() + "/" + f.getFileId()
                }) { i ->
                    FileRow(displayFiles[i], targetGameVersion, vm)
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

@Composable
private fun FileRow(
    f: com.pmcl.core.market.ModFile,
    targetGameVersion: String,
    vm: LauncherViewModel
) {
    val installingDeps by vm.installingDeps.collectAsState()
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
            MarketPrimaryButton(onClick = {
                val rect = cardRect
                val gv = targetGameVersion.ifBlank {
                    (f.getGameVersions() ?: emptyList()).firstOrNull() ?: ""
                }
                val title = f.getFileName() ?: I18n.t("market.download")
                if (rect != null) {
                    vm.triggerFlyAnimation(rect, title) {
                        vm.enqueueModDownload(f, gv)
                    }
                } else {
                    vm.enqueueModDownload(f, gv)
                }
            }) { Text(I18n.t("market.download")) }
            Spacer(Modifier.width(8.dp))
            MarketOutlinedButton(
                onClick = {
                    vm.installModWithDeps(f, targetGameVersion.ifBlank {
                        (f.getGameVersions() ?: emptyList()).firstOrNull() ?: ""
                    })
                },
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

private fun fileMatchesMarketFilter(
    file: com.pmcl.core.market.ModFile,
    gameVersion: String,
    loader: String
): Boolean {
    val gv = gameVersion.trim()
    val ld = loader.trim()
    if (gv.isNotEmpty()) {
        val versions = file.getGameVersions() ?: emptyList()
        if (versions.none { it.equals(gv, ignoreCase = true) }) return false
    }
    if (ld.isNotEmpty()) {
        val loaders = file.getLoaders() ?: emptyList()
        if (loaders.none { it.equals(ld, ignoreCase = true) }) return false
    }
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoaderDropdown(
    selected: String,
    modifier: Modifier = Modifier.width(120.dp),
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = listOf("fabric", "forge", "quilt", "neoforge", "")
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = if (selected.isEmpty()) I18n.t("market.all") else selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(I18n.t("market.loader")) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(if (opt.isEmpty()) I18n.t("market.all") else opt) },
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
    "market.cat.game_mechanics" to "game-mechanics"
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
    return s == "fabric" || s == "forge" || s == "quilt" || s == "neoforge"
            || s == "rift" || s == "liteloader" || s == "datapack"
}

private fun marketRowTags(project: ModProject): List<String> {
    val out = LinkedHashSet<String>()
    project.getLoaders().forEach { if (it.isNotBlank()) out.add(it.lowercase()) }
    project.getCategories().forEach { cat ->
        if (cat.isBlank() || isLoaderTag(cat)) return@forEach
        out.add(categoryLabel(cat))
    }
    return out.toList()
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
    if (days < 30) return I18n.t("market.rel_days", days)
    val months = days / 30
    if (months < 12) return I18n.t("market.rel_months", months)
    return I18n.t("market.rel_years", months / 12)
}

private val modImageCache = com.pmcl.ui.util.LruImageCache()

@Composable
private fun rememberUrlImage(url: String): ImageBitmap? {
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
                val bmp = decodeSampledBitmap(bytes, 128) ?: throw IllegalStateException("decode failed")
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
