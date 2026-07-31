package com.pmcl.ui.modloader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.modloader.ModLoader
import com.pmcl.ui.viewmodel.LauncherViewModel

/** 安装弹窗左侧加载器条目（含品牌色）。 */
data class LoaderUiEntry(
    val loader: ModLoader?,
    val label: String,
    val color: Color,
    val kind: LoaderIconKind,
    val primary: Boolean
)

enum class LoaderIconKind {
    VANILLA, FABRIC, FORGE, NEOFORGE, QUILT, LITELOADER, BABRIC, BTA,
    LEGACY_FABRIC, ORNITHE, RIFT, JAVA_AGENT, RISUGAMI, NILLOADER, OPTIFINE
}

fun installPromptLoaderEntries(): List<LoaderUiEntry> = listOf(
    LoaderUiEntry(null, I18n.t("launch.vanilla_only"), Color(0xFF6B7280), LoaderIconKind.VANILLA, true),
    LoaderUiEntry(ModLoader.FABRIC, "Fabric", Color(0xFF8B909A), LoaderIconKind.FABRIC, true),
    LoaderUiEntry(ModLoader.FORGE, "Forge", Color(0xFF1E4B8C), LoaderIconKind.FORGE, true),
    LoaderUiEntry(ModLoader.NEOFORGE, "NeoForge", Color(0xFFE36A1E), LoaderIconKind.NEOFORGE, true),
    LoaderUiEntry(ModLoader.BABRIC, "Babric", Color(0xFF6B7280), LoaderIconKind.BABRIC, false),
    LoaderUiEntry(ModLoader.BTA_BABRIC, "BTA (Babric)", Color(0xFF2F9E44), LoaderIconKind.BTA, false),
    LoaderUiEntry(ModLoader.JAVA_AGENT, "Java Agent", Color(0xFF6B7280), LoaderIconKind.JAVA_AGENT, false),
    LoaderUiEntry(ModLoader.LEGACY_FABRIC, "Legacy Fabric", Color(0xFF6B7280), LoaderIconKind.LEGACY_FABRIC, false),
    LoaderUiEntry(ModLoader.LITELOADER, "LiteLoader", Color(0xFF4AA3DF), LoaderIconKind.LITELOADER, true),
    LoaderUiEntry(ModLoader.RISUGAMI, "Risugami's ModLoader", Color(0xFF6B7280), LoaderIconKind.RISUGAMI, false),
    LoaderUiEntry(ModLoader.NILLOADER, "NilLoader", Color(0xFFD63384), LoaderIconKind.NILLOADER, false),
    LoaderUiEntry(ModLoader.ORNITHE, "Ornithe", Color(0xFF3BA7E0), LoaderIconKind.ORNITHE, false),
    LoaderUiEntry(ModLoader.QUILT, "Quilt", Color(0xFF8B5CF6), LoaderIconKind.QUILT, true),
    LoaderUiEntry(ModLoader.RIFT, "Rift", Color(0xFF6B7280), LoaderIconKind.RIFT, false),
    LoaderUiEntry(ModLoader.OPTIFINE, "OptiFine", Color(0xFF7C9A3E), LoaderIconKind.OPTIFINE, false)
)

/**
 * 安装 Minecraft 前：左右布局选择模组加载器与版本。
 * 左：加载器列表（Prism 风格）；右：对应版本列表。
 */
@Composable
fun ModLoaderInstallPromptDialog(
    versionId: String,
    vm: LauncherViewModel,
    onDismiss: () -> Unit
) {
    val gameVersion = remember(versionId) {
        val dashIdx = versionId.indexOf('-')
        if (dashIdx > 0) versionId.substring(0, dashIdx) else versionId
    }

    val modLoaderVersions by vm.modLoaderVersions.collectAsState()
    val modLoaderVersionsLoading by vm.modLoaderVersionsLoading.collectAsState()
    val installing by vm.installing.collectAsState()

    var selectedLoader by remember { mutableStateOf<ModLoader?>(null) }
    var vanillaOnlySelected by remember { mutableStateOf(false) }
    var selectedLoaderVersion by remember { mutableStateOf<String?>(null) }
    var showAllLoaders by remember { mutableStateOf(true) }

    val allEntries = remember { installPromptLoaderEntries() }
    val visibleEntries = remember(showAllLoaders, allEntries) {
        if (showAllLoaders) allEntries else allEntries.filter { it.primary }
    }

    LaunchedEffect(selectedLoader) {
        val loader = selectedLoader
        selectedLoaderVersion = null
        if (loader != null && loader.isInstallable()) {
            vm.listModLoaderVersions(loader, gameVersion)
        } else {
            vm.clearModLoaderVersions()
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!installing) onDismiss() }
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                Modifier
                    .width(720.dp)
                    .heightIn(min = 420.dp, max = 560.dp)
                    .padding(16.dp)
            ) {
                Text(
                    I18n.t("launch.install_modloader_prompt"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    I18n.t("launch.install_modloader_hint", gameVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )

                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 左侧：加载器
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.width(230.dp).fillMaxHeight()
                    ) {
                        Column(Modifier.fillMaxSize().padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    I18n.t("launch.loaders"),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    if (showAllLoaders) Icons.Filled.KeyboardArrowUp
                                    else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(visibleEntries, key = { it.label }) { entry ->
                                    val selected = if (entry.loader == null) {
                                        vanillaOnlySelected && selectedLoader == null
                                    } else {
                                        selectedLoader == entry.loader
                                    }
                                    val enabled = !installing
                                    val installable = entry.loader == null || entry.loader.isInstallable()
                                    LoaderListRow(
                                        entry = entry,
                                        selected = selected,
                                        enabled = enabled,
                                        dimmed = !installable,
                                        onClick = {
                                            if (!enabled) return@LoaderListRow
                                            if (entry.loader == null) {
                                                vanillaOnlySelected = true
                                                selectedLoader = null
                                                selectedLoaderVersion = null
                                            } else {
                                                vanillaOnlySelected = false
                                                selectedLoader = entry.loader
                                            }
                                        }
                                    )
                                }
                                item {
                                    TextButton(
                                        onClick = { showAllLoaders = !showAllLoaders },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            if (showAllLoaders) Icons.Filled.KeyboardArrowUp
                                            else Icons.Filled.KeyboardArrowDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            if (showAllLoaders) I18n.t("launch.show_less")
                                            else I18n.t("launch.show_more")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 右侧：版本
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) {
                        Column(Modifier.fillMaxSize().padding(12.dp)) {
                            Text(
                                when {
                                    vanillaOnlySelected -> I18n.t("launch.vanilla_only")
                                    selectedLoader != null -> I18n.t(
                                        "launch.select_loader_version",
                                        selectedLoader!!.getDisplayName()
                                    )
                                    else -> I18n.t("launch.pick_loader_first")
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))

                            when {
                                selectedLoader == null && !vanillaOnlySelected -> {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            I18n.t("launch.pick_loader_first"),
                                            color = MaterialTheme.colorScheme.outline,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                selectedLoader == null && vanillaOnlySelected -> {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            I18n.t("launch.vanilla_only_hint"),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                selectedLoader != null && !selectedLoader!!.isInstallable() -> {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            I18n.t("launch.loader_not_supported"),
                                            color = MaterialTheme.colorScheme.outline,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                modLoaderVersionsLoading -> {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                                    }
                                }
                                modLoaderVersions.isEmpty() -> {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(
                                            I18n.t("launch.no_loader_versions", gameVersion),
                                            color = MaterialTheme.colorScheme.outline,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                                else -> {
                                    Text(
                                        I18n.t("launch.available_versions", modLoaderVersions.size),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        items(modLoaderVersions, key = { it.getLoaderVersion() }) { lv ->
                                            val isSelected = selectedLoaderVersion == lv.getLoaderVersion()
                                            val displayVersion = remember(lv) {
                                                val raw = lv.getLoaderVersion()
                                                when {
                                                    lv.getLoader() == ModLoader.OPTIFINE -> {
                                                        val parts = raw.split("|")
                                                        if (parts.size >= 2) {
                                                            val forge = parts.size >= 3 && parts[2] == "forge"
                                                            (if (forge) "[Forge] " else "") + parts[0] + " " + parts[1]
                                                        } else raw
                                                    }
                                                    lv.getLoader() == ModLoader.JAVA_AGENT && raw == "blank" ->
                                                        I18n.t("launch.java_agent_blank")
                                                    lv.getLoader() == ModLoader.JAVA_AGENT && raw.startsWith("nilloader-") ->
                                                        "NilLoader " + raw.removePrefix("nilloader-")
                                                    else -> raw
                                                }
                                            }
                                            Surface(
                                                onClick = {
                                                    if (!installing) selectedLoaderVersion = lv.getLoaderVersion()
                                                },
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                                else MaterialTheme.colorScheme.surface,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(Modifier.weight(1f)) {
                                                        Text(
                                                            displayVersion,
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        Text(
                                                            "MC ${lv.getGameVersion()} · " +
                                                                if (lv.isStable()) I18n.t("launch.stable")
                                                                else I18n.t("launch.unstable"),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.outline
                                                        )
                                                    }
                                                    if (isSelected) {
                                                        Icon(
                                                            Icons.Filled.Check,
                                                            contentDescription = I18n.t("launch.selected_badge"),
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onDismiss, enabled = !installing) {
                        Text(I18n.t("common.cancel"))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (installing) return@Button
                            val loader = selectedLoader
                            val lv = selectedLoaderVersion
                            if (loader != null && lv != null) {
                                vm.proceedInstall(versionId, loader, lv)
                            } else {
                                vm.proceedInstall(versionId, null, null)
                            }
                            onDismiss()
                        },
                        enabled = !installing && (
                            (selectedLoader == null && vanillaOnlySelected) ||
                                (selectedLoader != null && selectedLoaderVersion != null)
                            )
                    ) {
                        Text(if (installing) I18n.t("launch.installing") else I18n.t("launch.start_install"))
                    }
                }
            }
        }
    }
}

@Composable
private fun LoaderListRow(
    entry: LoaderUiEntry,
    selected: Boolean,
    enabled: Boolean,
    dimmed: Boolean = false,
    onClick: () -> Unit
) {
    val bg = when {
        selected -> entry.color.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val contentAlpha = if (!enabled || dimmed) 0.45f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LoaderBrandIcon(
            kind = entry.kind,
            color = entry.color.copy(alpha = contentAlpha),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            entry.label,
            color = entry.color.copy(alpha = contentAlpha),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun LoaderBrandIcon(
    kind: LoaderIconKind,
    color: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val stroke = Stroke(width = size.minDimension * 0.12f, cap = StrokeCap.Round)
        when (kind) {
            LoaderIconKind.FABRIC, LoaderIconKind.LEGACY_FABRIC -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.18f, size.height * 0.22f),
                    size = Size(size.width * 0.64f, size.height * 0.56f),
                    cornerRadius = CornerRadius(size.minDimension * 0.12f),
                    style = stroke
                )
                drawLine(
                    color, Offset(size.width * 0.30f, size.height * 0.42f),
                    Offset(size.width * 0.70f, size.height * 0.42f), stroke.width
                )
                drawLine(
                    color, Offset(size.width * 0.30f, size.height * 0.58f),
                    Offset(size.width * 0.62f, size.height * 0.58f), stroke.width
                )
            }
            LoaderIconKind.FORGE -> {
                // 简易铁砧
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.22f, size.height * 0.18f),
                    size = Size(size.width * 0.56f, size.height * 0.22f),
                    cornerRadius = CornerRadius(2f)
                )
                drawRect(
                    color = color,
                    topLeft = Offset(size.width * 0.38f, size.height * 0.40f),
                    size = Size(size.width * 0.24f, size.height * 0.28f)
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.26f, size.height * 0.68f),
                    size = Size(size.width * 0.48f, size.height * 0.16f),
                    cornerRadius = CornerRadius(2f)
                )
            }
            LoaderIconKind.NEOFORGE -> {
                val path = Path().apply {
                    moveTo(size.width * 0.50f, size.height * 0.12f)
                    lineTo(size.width * 0.78f, size.height * 0.42f)
                    lineTo(size.width * 0.68f, size.height * 0.88f)
                    lineTo(size.width * 0.32f, size.height * 0.88f)
                    lineTo(size.width * 0.22f, size.height * 0.42f)
                    close()
                }
                drawPath(path, color = color, style = stroke)
            }
            LoaderIconKind.QUILT -> {
                val s = size.minDimension * 0.28f
                val gap = size.minDimension * 0.08f
                val ox = (size.width - s * 2 - gap) / 2
                val oy = (size.height - s * 2 - gap) / 2
                drawRoundRect(color, Offset(ox, oy), Size(s, s), CornerRadius(2f))
                drawRoundRect(color, Offset(ox + s + gap, oy), Size(s, s), CornerRadius(2f))
                drawRoundRect(color, Offset(ox, oy + s + gap), Size(s, s), CornerRadius(2f))
                drawRoundRect(color, Offset(ox + s + gap, oy + s + gap), Size(s, s), CornerRadius(2f))
            }
            LoaderIconKind.LITELOADER -> {
                val path = Path().apply {
                    moveTo(size.width * 0.30f, size.height * 0.18f)
                    quadraticBezierTo(
                        size.width * 0.55f, size.height * 0.10f,
                        size.width * 0.72f, size.height * 0.35f
                    )
                    quadraticBezierTo(
                        size.width * 0.55f, size.height * 0.55f,
                        size.width * 0.35f, size.height * 0.82f
                    )
                    quadraticBezierTo(
                        size.width * 0.28f, size.height * 0.55f,
                        size.width * 0.30f, size.height * 0.18f
                    )
                }
                drawPath(path, color = color, style = stroke)
            }
            LoaderIconKind.BABRIC, LoaderIconKind.BTA -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.18f, size.height * 0.22f),
                    size = Size(size.width * 0.64f, size.height * 0.56f),
                    cornerRadius = CornerRadius(size.minDimension * 0.12f),
                    style = stroke
                )
            }
            LoaderIconKind.ORNITHE -> {
                val path = Path().apply {
                    moveTo(size.width * 0.22f, size.height * 0.55f)
                    quadraticBezierTo(
                        size.width * 0.40f, size.height * 0.18f,
                        size.width * 0.62f, size.height * 0.35f
                    )
                    quadraticBezierTo(
                        size.width * 0.82f, size.height * 0.28f,
                        size.width * 0.78f, size.height * 0.48f
                    )
                    quadraticBezierTo(
                        size.width * 0.70f, size.height * 0.72f,
                        size.width * 0.38f, size.height * 0.78f
                    )
                    close()
                }
                drawPath(path, color = color, style = stroke)
            }
            LoaderIconKind.RIFT -> {
                drawLine(color, Offset(size.width * 0.25f, size.height * 0.30f), Offset(size.width * 0.75f, size.height * 0.30f), stroke.width)
                drawLine(color, Offset(size.width * 0.25f, size.height * 0.30f), Offset(size.width * 0.50f, size.height * 0.18f), stroke.width)
                drawLine(color, Offset(size.width * 0.75f, size.height * 0.30f), Offset(size.width * 0.50f, size.height * 0.18f), stroke.width)
                drawLine(color, Offset(size.width * 0.25f, size.height * 0.30f), Offset(size.width * 0.25f, size.height * 0.70f), stroke.width)
                drawLine(color, Offset(size.width * 0.75f, size.height * 0.30f), Offset(size.width * 0.75f, size.height * 0.70f), stroke.width)
                drawLine(color, Offset(size.width * 0.25f, size.height * 0.70f), Offset(size.width * 0.75f, size.height * 0.70f), stroke.width)
            }
            LoaderIconKind.JAVA_AGENT -> {
                drawLine(color, Offset(size.width * 0.50f, size.height * 0.12f), Offset(size.width * 0.50f, size.height * 0.55f), stroke.width * 1.2f)
                drawCircle(color, radius = size.minDimension * 0.18f, center = Offset(size.width * 0.50f, size.height * 0.72f), style = stroke)
            }
            LoaderIconKind.RISUGAMI -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(size.width * 0.18f, size.height * 0.22f),
                    size = Size(size.width * 0.64f, size.height * 0.56f),
                    cornerRadius = CornerRadius(3f),
                    style = stroke
                )
            }
            LoaderIconKind.NILLOADER -> {
                drawCircle(color, radius = size.minDimension * 0.32f, center = center, style = stroke)
                drawLine(
                    color,
                    Offset(size.width * 0.28f, size.height * 0.72f),
                    Offset(size.width * 0.72f, size.height * 0.28f),
                    stroke.width
                )
            }
            LoaderIconKind.VANILLA, LoaderIconKind.OPTIFINE -> {
                drawCircle(color, radius = size.minDimension * 0.30f, center = center, style = stroke)
            }
        }
    }
}
