package com.pmcl.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.LauncherTheme
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.MaterialField
import com.pmcl.ui.theme.ThemeState
import com.pmcl.ui.theme.WindowBackdrop
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.PolicyChange
import com.pmcl.ui.viewmodel.PolicyChangelog
import com.pmcl.ui.windowDragModifier

/** 独立公告窗口：任务中心的「公告栏」点开后在主窗口之外弹出。 */
@Composable
fun AnnouncementWindow(
    vm: LauncherViewModel,
    themeState: ThemeState,
    onClose: () -> Unit
) {
    val state = rememberWindowState(width = 720.dp, height = 640.dp, position = WindowPosition(Alignment.Center))
    val scheme = if (themeState.dynamicColor || themeState.customAccentColor != -1) {
        themeState.dynamicColorScheme
    } else {
        null
    }
    // 与主窗口同一套外观：自定义背景 / 视差背景 / 壁纸毛玻璃 / 材质霜面
    val parallax by vm.parallaxBackground.collectAsState()
    val bgType by vm.launcherBgType.collectAsState()
    val bgImage by vm.launcherBgImagePath.collectAsState()
    val bgVideo by vm.launcherBgVideoPath.collectAsState()
    val materialOn by vm.materialTheme.collectAsState()
    val liveGlassOn by vm.liveWallpaperGlass.collectAsState()
    val customBgOn = (bgType == "image" && bgImage.isNotBlank()) ||
        (bgType == "video" && bgVideo.isNotBlank())
    val backdropOn = parallax || customBgOn
    // 跟随主窗口的无边框设置：主窗口无边框时这里也用自绘标题栏，否则两套窗口风格割裂
    val borderless = remember { vm.preferences.isBorderlessWindow() }
    val isDragging = remember { mutableStateOf(false) }
    Window(
        onCloseRequest = onClose,
        title = "PMCL ${I18n.t("announcement.window_title")}",
        state = state,
        undecorated = borderless,
        transparent = borderless
    ) {
        if (borderless) {
            LaunchedEffect(Unit) {
                // AWT 背景必须透明，否则圆角外会露出一圈系统底色
                window.background = java.awt.Color(0, 0, 0, 0)
            }
        }
        Box(
            Modifier.fillMaxSize().then(
                if (borderless) Modifier.clip(RoundedCornerShape(14.dp)) else Modifier
            )
        ) {
            WindowBackdrop(
                themeState = themeState,
                parallax = parallax,
                customType = bgType,
                customImage = bgImage,
                customVideo = bgVideo,
                materialOn = materialOn,
                liveGlassOn = liveGlassOn
            )
            LauncherTheme(
                useDarkTheme = themeState.useDark,
                dynamicColorScheme = scheme,
                uiScale = themeState.uiScale,
                launcherFont = themeState.launcherFont,
                themePreset = themeState.themePreset,
                colorMode = themeState.colorMode,
                customThemePack = themeState.customThemePack
            ) {
                CompositionLocalProvider(LocalThemeState provides themeState) {
                    // 有背景层时 Surface 必须透明，否则整层不透明色会把壁纸盖掉
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (backdropOn || materialOn || liveGlassOn) Color.Transparent
                        else MaterialTheme.colorScheme.surface
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (materialOn) MaterialField(Modifier.fillMaxSize(), linesOnly = liveGlassOn)
                            AnnouncementPage(
                                borderless = borderless,
                                onClose = onClose,
                                dragArea = { windowDragModifier(isDragging) },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnnouncementPage(
    borderless: Boolean,
    onClose: () -> Unit,
    dragArea: () -> Modifier,
    modifier: Modifier = Modifier
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> PolicyChangelog.entries.firstOrNull { it.id == id } }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected != null) {
                IconButton(onClick = { selectedId = null }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = I18n.t("common.back"),
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.size(6.dp))
            } else {
                Icon(
                    Icons.Filled.Campaign,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.size(10.dp))
            }
            // 无边框时标题区兼作拖拽把手；按钮不挂拖拽，否则会整窗被拖走
            Column(Modifier.weight(1f).then(if (borderless) dragArea() else Modifier)) {
                Text(
                    if (selected != null) I18n.t("policy.entry.${selected.id}.title")
                    else I18n.t("announcement.window_title"),
                    style = if (selected != null) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (selected != null) "${selected.date} · v${selected.version}"
                    else I18n.t("policy.subtitle"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (borderless) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = I18n.t("common.close"),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (selected != null) {
            PolicyDetail(selected)
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(PolicyChangelog.entries, key = { it.id }) { entry ->
                    PolicyEntryCard(entry, onClick = { selectedId = entry.id })
                }
            }
        }
    }
}

/** 列表里的单条政策：只给摘要，点开看全文。 */
@Composable
private fun PolicyEntryCard(entry: PolicyChange, onClick: () -> Unit) {
    val body = I18n.t("policy.entry.${entry.id}.body")
    // 正文是多段的，摘要把段落折成一行，避免前几行只有一句「适用地区」
    val preview = remember(body) {
        body.lineSequence().filter { it.isNotBlank() }.joinToString(" ")
    }
    Card(
        Modifier.fillMaxWidth().glassCardBorder().clickable(onClick = onClick),
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    I18n.t("policy.entry.${entry.id}.title"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (entry.important) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        I18n.t("policy.badge.important"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.size(4.dp))
            Text(
                "${entry.date} · v${entry.version}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Spacer(Modifier.size(8.dp))
            Text(
                preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    I18n.t("common.detail"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/** 详情：完整正文，可滚动。 */
@Composable
private fun PolicyDetail(entry: PolicyChange) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
    ) {
        if (entry.important) {
            Text(
                I18n.t("policy.badge.important"),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.size(8.dp))
        }
        Text(
            I18n.t("policy.entry.${entry.id}.body"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
