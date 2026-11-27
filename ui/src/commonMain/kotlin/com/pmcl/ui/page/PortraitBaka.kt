package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pmcl.core.i18n.I18n
import com.pmcl.core.instance.InstanceInfo
import com.pmcl.core.preferences.Preferences
import com.pmcl.ui.navigation.NavDestination
import com.pmcl.ui.navigation.PmclIcons
import com.pmcl.ui.navigation.SecondarySection
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.refreshNews
import com.pmcl.ui.widget.pmclVerticalScroll

internal val PortraitInk = Color(0xFF1C1C1E)
internal val PortraitMuted = Color(0xFF8B919A)
internal val PortraitLine = Color(0xFFE3E6EB)
internal val PortraitCard = Color(0xFFFFFFFF)
internal val PortraitPressed = Color(0xFFE6E8EC)
internal val PortraitBlue = Color(0xFF3B82C4)
private val CardShape = RoundedCornerShape(10.dp)

@Composable
internal fun PortraitGroupedIndex(
    title: String,
    subtitle: String?,
    groups: List<Pair<String, List<SecondarySection>>>,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().pmclVerticalScroll(rememberScrollState())) {
        PortraitBigHeader(title, subtitle, onBack)
        groups.forEach { (label, rows) ->
            if (rows.isEmpty()) return@forEach
            Text(
                label,
                color = PortraitInk,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                rows.forEach { section ->
                    PortraitLinkRow(
                        icon = section.icon ?: PmclIcons.Settings,
                        title = I18n.t(section.labelKey),
                        hint = I18n.t(portraitHint(section.id)),
                        onClick = { onOpen(section.id) }
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
internal fun PortraitResourceHub(
    vm: LauncherViewModel,
    title: String,
    onBack: () -> Unit,
    onOpen: (NavDestination, String) -> Unit,
    onSearch: () -> Unit,
) {
    val news by vm.newsItems.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var hero by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { vm.refreshNews() }
    val feature = news.getOrNull(hero.coerceIn(0, (news.size - 1).coerceAtLeast(0)))
    Column(Modifier.fillMaxSize().pmclVerticalScroll(rememberScrollState())) {
        PortraitHubHeader(title, onBack, menu, { menu = it }, onSearch, onOpen)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(196.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF8FB4D4), Color(0xFF1E3348))))
        ) {
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp).padding(end = 72.dp)) {
                Text(
                    feature?.getTitle()?.ifBlank { null } ?: I18n.t("portrait.no_version"),
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val line = feature?.let { plainNews(it.getDescription()) }.orEmpty()
                if (line.isNotEmpty()) {
                    Text(line, color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (news.size > 1) {
                Row(Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                    PortraitPagerButton { hero = (hero - 1 + news.size) % news.size }
                    Spacer(Modifier.width(6.dp))
                    PortraitPagerButton(forward = true) { hero = (hero + 1) % news.size }
                }
            }
        }
        val picks = news.take(2)
        if (picks.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            PortraitOutline {
                Text(
                    I18n.t("portrait.hub.recommend"),
                    color = PortraitMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                picks.forEachIndexed { index, item ->
                    if (index > 0) Box(Modifier.padding(start = 58.dp).fillMaxWidth().height(1.dp).background(PortraitLine))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFD7E4F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(item.getTitle().take(1), color = PortraitInk, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.getTitle(), color = PortraitInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val line = plainNews(item.getDescription())
                            if (line.isNotEmpty()) {
                                Text(line, color = PortraitMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        val tiles = listOf(
            "market-modpack" to "nav.modpacks",
            "market-mod" to "nav.mods",
            "market-resourcepack" to "nav.resourcepacks",
            "market-shader" to "nav.shaders"
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (section, key) ->
                        PortraitTile(I18n.t(key), Modifier.weight(1f)) {
                            onOpen(NavDestination.Download, section)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        PortraitLinkRow(
            icon = PmclIcons.Dataset,
            title = I18n.t("download.local_games"),
            hint = I18n.t("download.games_hint"),
            onClick = { onOpen(NavDestination.Download, "games") }
        )
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
internal fun PortraitDrawerSheet(vm: LauncherViewModel, onDismiss: () -> Unit) {
    val instances by vm.instances.collectAsState()
    val versions by vm.localVersionInfos.collectAsState()
    var byRecent by remember { mutableStateOf(false) }
    var grid by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadInstances() }
    val shown = if (byRecent) {
        instances.sortedByDescending { it.getLastPlayedAt() }
    } else {
        instances.sortedBy { it.getName().lowercase() }
    }
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                clip = true
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            }
            .background(Color(0xF7F7FAFD))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(40.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFFD5D8DE))
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(PmclIcons.Grid, contentDescription = null, tint = PortraitInk, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                I18n.t("portrait.dock.drawer"),
                color = PortraitInk,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            PortraitIconTap(Icons.Filled.Refresh, I18n.t("common.refresh")) { vm.loadInstances() }
            PortraitIconTap(Icons.Filled.Add, I18n.t("portrait.drawer.create")) { creating = true }
            PortraitIconTap(Icons.Filled.KeyboardArrowDown, I18n.t("common.close"), onDismiss)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
            Text(I18n.t("portrait.drawer.pinned"), color = PortraitInk, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            PortraitIconTap(Icons.Filled.Add, I18n.t("portrait.drawer.create")) { creating = true }
        }
        Text(
            I18n.t("portrait.drawer.pinned_empty"),
            color = PortraitMuted,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text(I18n.t("portrait.drawer.all"), color = PortraitInk, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { byRecent = !byRecent }
            ) {
                Text(
                    I18n.t(if (byRecent) "portrait.drawer.sort_recent" else "portrait.drawer.sort_name"),
                    color = PortraitMuted,
                    fontSize = 13.sp
                )
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = PortraitMuted, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            PortraitIconTap(Icons.Filled.Menu, I18n.t("portrait.drawer.all")) { grid = false }
            PortraitIconTap(PmclIcons.Grid, I18n.t("portrait.dock.drawer")) { grid = true }
        }
        Column(Modifier.weight(1f).fillMaxWidth().pmclVerticalScroll(rememberScrollState())) {
            if (shown.isEmpty()) {
                Text(
                    I18n.t("portrait.drawer.none"),
                    color = PortraitMuted,
                    fontSize = 14.sp,
                    modifier = Modifier.fillMaxWidth().padding(top = 36.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else if (grid) {
                shown.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        row.forEach { info ->
                            PortraitInstanceCell(info, Modifier.weight(1f)) {
                                if (info.isLaunchable()) vm.launchInstance(info.getInstanceId())
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                shown.forEach { info ->
                    PortraitInstanceLine(info) {
                        if (info.isLaunchable()) vm.launchInstance(info.getInstanceId())
                    }
                }
            }
        }
    }
    if (creating) {
        PortraitCreateDialog(
            versions = versions.map { it.getId() },
            onDismiss = { creating = false },
            onCreate = { name, versionId ->
                vm.createInstance(name, versionId, null, null)
                creating = false
            },
            onNeedVersions = { vm.refreshLocalVersions() }
        )
    }
}

@Composable
private fun PortraitCreateDialog(
    versions: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit,
    onNeedVersions: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var version by remember(versions) { mutableStateOf(versions.firstOrNull().orEmpty()) }
    LaunchedEffect(Unit) { if (versions.isEmpty()) onNeedVersions() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("portrait.drawer.create")) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(I18n.t("portrait.drawer.name")) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("portrait.drawer.version"), color = PortraitMuted, fontSize = 12.sp)
                if (versions.isEmpty()) {
                    Text(I18n.t("portrait.no_version_hint"), color = PortraitMuted, fontSize = 13.sp)
                } else {
                    Column(Modifier.height(160.dp).pmclVerticalScroll(rememberScrollState())) {
                        versions.forEach { id ->
                            Text(
                                id,
                                color = if (id == version) PortraitBlue else PortraitInk,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth().clickable { version = id }.padding(vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim(), version) },
                enabled = name.isNotBlank() && version.isNotBlank()
            ) { Text(I18n.t("common.confirm")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(I18n.t("common.cancel")) }
        }
    )
}

@Composable
internal fun PortraitBigHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = title,
            tint = PortraitInk,
            modifier = Modifier.size(22.dp).clickable(onClick = onBack)
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = PortraitInk, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, color = PortraitMuted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PortraitLinkRow(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .border(1.dp, PortraitLine, CardShape)
            .background(if (pressed) PortraitPressed else PortraitCard)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = PortraitInk, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = PortraitInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (hint.isNotBlank()) {
                Text(hint, color = PortraitMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFFC5C8CE), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun PortraitTile(title: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(72.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, PortraitLine, RoundedCornerShape(14.dp))
            .background(PortraitCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = PortraitInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color(0xFFC5C8CE), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PortraitInstanceCell(info: InstanceInfo, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(72.dp)
            .clip(CardShape)
            .background(PortraitCard)
            .clickable(enabled = info.isLaunchable(), onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(info.getName(), color = PortraitInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(instanceVersion(info), color = PortraitMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PortraitInstanceLine(info: InstanceInfo, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = info.isLaunchable(), onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Text(info.getName(), color = PortraitInk, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(instanceVersion(info), color = PortraitMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PortraitHubHeader(
    title: String,
    onBack: () -> Unit,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
    onSearch: () -> Unit,
    onOpen: (NavDestination, String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = title,
            tint = PortraitInk,
            modifier = Modifier.size(22.dp).clickable(onClick = onBack)
        )
        Spacer(Modifier.width(10.dp))
        Box {
            Icon(
                Icons.Filled.Menu,
                contentDescription = I18n.t("portrait.menu"),
                tint = PortraitInk,
                modifier = Modifier.size(22.dp).clickable { onMenu(true) }
            )
            DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
                PortraitMenuRow("download.local_versions") { onMenu(false); onOpen(NavDestination.Download, "versions") }
                PortraitMenuRow("download.local_games") { onMenu(false); onOpen(NavDestination.Download, "games") }
                PortraitMenuRow("nav.market") { onMenu(false); onOpen(NavDestination.Download, "market") }
                PortraitMenuRow("nav.queue") { onMenu(false); onOpen(NavDestination.Download, "queue") }
                PortraitMenuRow("nav.wiki") { onMenu(false); onOpen(NavDestination.Download, "wiki") }
            }
        }
        Text(
            title,
            color = PortraitInk,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
        )
        Icon(
            Icons.Filled.Search,
            contentDescription = I18n.t("search.placeholder"),
            tint = PortraitInk,
            modifier = Modifier.size(22.dp).clickable(onClick = onSearch)
        )
    }
}

@Composable
private fun PortraitMenuRow(key: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(I18n.t(key)) }, onClick = onClick)
}

@Composable
private fun PortraitPagerButton(forward: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.88f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (forward) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = null,
            tint = PortraitInk,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun PortraitOutline(
    color: Color = Color.White.copy(alpha = 0.92f),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, PortraitLine, RoundedCornerShape(12.dp))
            .background(color),
        content = content
    )
}

@Composable
private fun PortraitIconTap(icon: ImageVector, description: String, onClick: () -> Unit) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Icon(
            icon,
            contentDescription = description,
            tint = PortraitInk,
            modifier = Modifier.padding(horizontal = 5.dp).size(22.dp).clickable(onClick = onClick)
        )
    }
}

private fun instanceVersion(info: InstanceInfo): String {
    val id = info.getBaseVersionId()
    return if (id.isNullOrBlank()) I18n.t("portrait.no_version") else id
}

@Composable
internal fun PortraitJavaPage(vm: LauncherViewModel, onBack: () -> Unit) {
    val pref = vm.preferences
    val pulse by vm.settingsPulse.collectAsState()
    var totalMb by remember { mutableLongStateOf(0L) }
    var availableMb by remember { mutableLongStateOf(0L) }
    var recommended by remember { mutableLongStateOf(0L) }
    var maxMb by remember(pulse) { mutableStateOf(pref.getMaxMemoryMb()) }
    var auto by remember { mutableStateOf(false) }
    var pathOpen by remember { mutableStateOf(false) }
    var advancedOpen by remember { mutableStateOf(false) }
    var path by remember { mutableStateOf(pref.getJavaPath()) }
    var forceG1 by remember(pulse) { mutableStateOf(pref.isForceG1Gc()) }
    var aikar by remember(pulse) { mutableStateOf(pref.isUseAikarFlags()) }
    LaunchedEffect(Unit) {
        val runtime = vm.core.runtime()
        val read = withContext(Dispatchers.IO) {
            Triple(runtime.getTotalMemoryMb(), runtime.getAvailableMemoryMb(), runtime.getRecommendedMaxMemoryMb())
        }
        totalMb = read.first
        availableMb = read.second
        recommended = read.third
        auto = recommended > 0 && pref.getMaxMemoryMb().toLong() == recommended
    }
    val ceiling = (if (totalMb > 0) totalMb else 16384L).coerceAtMost(PreferencesMemoryCap)
    val sliderHi = ceiling.toInt().coerceAtLeast(Preferences.MAX_MEMORY_FLOOR + 256).coerceAtLeast(maxMb)
    Column(Modifier.fillMaxSize().pmclVerticalScroll(rememberScrollState())) {
        PortraitBigHeader(I18n.t("settings.section.java"), null, onBack)
        Spacer(Modifier.height(10.dp))
        PortraitOutline {
            Text(I18n.t("portrait.java.note"), color = PortraitMuted, fontSize = 13.sp, modifier = Modifier.padding(14.dp))
        }
        Spacer(Modifier.height(8.dp))
        PortraitFold(
            title = I18n.t("portrait.java.path"),
            hint = path.ifBlank { I18n.t("portrait.java.path_auto") },
            open = pathOpen,
            onToggle = { pathOpen = !pathOpen }
        ) {
            Text(I18n.t("portrait.java.path_hint"), color = PortraitMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = path,
                onValueChange = {
                    path = it
                    pref.setJavaPath(it)
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        PortraitFold(
            title = I18n.t("portrait.java.advanced"),
            hint = I18n.t("settings.gc_type"),
            open = advancedOpen,
            onToggle = { advancedOpen = !advancedOpen }
        ) {
            PortraitSwitchRow(I18n.t("settings.force_g1"), I18n.t("settings.force_g1_desc"), forceG1) {
                forceG1 = it
                pref.setForceG1Gc(it)
                vm.noteSettingsChanged("java")
            }
            PortraitSwitchRow(I18n.t("settings.aikar"), I18n.t("settings.aikar_desc"), aikar) {
                aikar = it
                pref.setUseAikarFlags(it)
                vm.noteSettingsChanged("java")
            }
        }
        Text(
            I18n.t("portrait.java.memory"),
            color = PortraitInk,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
        )
        PortraitOutline(PortraitPressed) {
            Column(Modifier.padding(14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(I18n.t("portrait.java.installed"), color = PortraitMuted, fontSize = 12.sp)
                        val used = (totalMb - availableMb).coerceAtLeast(0L)
                        Text(
                            if (totalMb > 0) "${portraitGb(used)} / ${portraitGb(totalMb)}" else "—",
                            color = PortraitInk,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(I18n.t("portrait.java.will_use"), color = PortraitMuted, fontSize = 12.sp)
                        Text(portraitGb(maxMb.toLong()), color = PortraitBlue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Slider(
                    value = maxMb.toFloat().coerceIn(Preferences.MAX_MEMORY_FLOOR.toFloat(), sliderHi.toFloat()),
                    onValueChange = {
                        auto = false
                        maxMb = it.toInt().coerceIn(Preferences.MAX_MEMORY_FLOOR, sliderHi)
                    },
                    onValueChangeFinished = {
                        pref.setMaxMemoryMb(maxMb)
                        vm.noteSettingsChanged("java")
                    },
                    valueRange = Preferences.MAX_MEMORY_FLOOR.toFloat()..sliderHi.toFloat(),
                    enabled = !auto,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(I18n.t("portrait.java.allocated_hint"), color = PortraitMuted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(PortraitLine))
                PortraitSwitchRow(I18n.t("portrait.java.auto"), I18n.t("portrait.java.auto_hint"), auto && recommended > 0) {
                    auto = it && recommended > 0
                    if (auto) {
                        val next = recommended.toInt().coerceIn(Preferences.MAX_MEMORY_FLOOR, sliderHi)
                        maxMb = next
                        pref.setMaxMemoryMb(next)
                        vm.noteSettingsChanged("java")
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
internal fun PortraitLauncherPage(vm: LauncherViewModel, onBack: () -> Unit) {
    val pref = vm.preferences
    val pulse by vm.settingsPulse.collectAsState()
    val portraitMode by vm.portraitModeEnabled.collectAsState()
    var borderless by remember(pulse) { mutableStateOf(pref.isBorderlessWindow()) }
    var segmented by remember(pulse) { mutableStateOf(pref.isUseSegmentedLaunchLayout()) }
    Column(Modifier.fillMaxSize().pmclVerticalScroll(rememberScrollState())) {
        PortraitBigHeader(I18n.t("settings.section.launcher"), null, onBack)
        Spacer(Modifier.height(10.dp))
        PortraitOutline {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                PortraitSwitchRow(I18n.t("settings.portrait_layout"), I18n.t("settings.portrait_layout_desc"), portraitMode) {
                    vm.setPortraitModeEnabled(it)
                }
                PortraitSwitchRow(I18n.t("settings.borderless_window"), I18n.t("settings.borderless_window_desc"), borderless) {
                    borderless = it
                    pref.setBorderlessWindow(it)
                    vm.noteSettingsChanged("launcher")
                }
                PortraitSwitchRow(I18n.t("settings.segmented_launch"), I18n.t("settings.segmented_launch_desc"), segmented) {
                    segmented = it
                    pref.setUseSegmentedLaunchLayout(it)
                    vm.noteSettingsChanged("launcher")
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun PortraitFold(
    title: String,
    hint: String,
    open: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .border(1.dp, PortraitLine, CardShape)
            .background(if (open) PortraitPressed else PortraitCard)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = PortraitInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (hint.isNotBlank()) {
                    Text(hint, color = PortraitMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(
                if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = PortraitMuted,
                modifier = Modifier.size(18.dp)
            )
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun PortraitSwitchRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = PortraitInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (hint.isNotBlank()) {
                Text(hint, color = PortraitMuted, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        PortraitSwitch(checked) { onChange(!checked) }
    }
}

@Composable
private fun PortraitSwitch(checked: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(46.dp)
            .height(28.dp)
            .clip(CircleShape)
            .background(if (checked) PortraitBlue else Color(0xFFE5E7EB))
            .clickable(onClick = onClick)
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White))
    }
}

private fun portraitGb(mb: Long): String {
    val tenths = ((mb / 1024.0) * 10.0).toInt()
    return if (tenths >= 100) "${tenths / 10} GB" else "${tenths / 10}.${tenths % 10} GB"
}

private const val PreferencesMemoryCap = 131072L

internal fun portraitGroups(sections: List<SecondarySection>): List<Pair<String, List<SecondarySection>>> {
    val byId = sections.associateBy { it.id }
    val used = LinkedHashSet<String>()
    fun take(ids: List<String>): List<SecondarySection> =
        ids.mapNotNull { id -> byId[id]?.also { used += id } }
    val security = take(listOf("accounts", "device", "network"))
    val game = take(listOf("java", "game", "launcher", "automation"))
    val personal = take(listOf("theme", "updates", "about"))
    val more = sections.filter { it.id !in used }
    return listOf(
        I18n.t("portrait.group.security") to security,
        I18n.t("portrait.group.game") to game,
        I18n.t("portrait.group.personal") to personal,
        I18n.t("portrait.group.more") to more
    )
}

private fun portraitHint(id: String): String = when (id) {
    "accounts", "list" -> "portrait.hint.accounts"
    "device" -> "portrait.hint.device"
    "network" -> "portrait.hint.network"
    "java" -> "portrait.hint.java"
    "game" -> "portrait.hint.game"
    "launcher" -> "portrait.hint.launcher"
    "automation" -> "portrait.hint.automation"
    "theme" -> "portrait.hint.theme"
    "updates" -> "portrait.hint.updates"
    "about" -> "portrait.hint.about"
    else -> "portrait.hint.default"
}

private fun plainNews(raw: String): String =
    raw.replace(Regex("<[^>]*>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("\\s+"), " ")
        .trim()
