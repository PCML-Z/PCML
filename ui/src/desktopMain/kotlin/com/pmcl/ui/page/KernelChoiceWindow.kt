package com.pmcl.ui.page

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.navigation.PmclIcons
import com.pmcl.ui.theme.LauncherTheme
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.MaterialField
import com.pmcl.ui.theme.ThemeState
import com.pmcl.ui.theme.WindowBackdrop
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.KernelLaunchRequest
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.cancelKernelLaunch
import com.pmcl.ui.viewmodel.confirmKernelLaunch
import com.pmcl.ui.windowDragModifier

/** 启动游戏前的内核选择弹窗。外观与主窗口同一套，选项是启动页风格的大卡片。空 id 是默认内核 HCS X Pro 2。 */
@Composable
fun KernelChoiceWindow(vm: LauncherViewModel, request: KernelLaunchRequest, themeState: ThemeState) {
    val optionCount = 1 + request.kernels.size
    val state = rememberWindowState(
        width = 500.dp,
        height = (196 + optionCount * 124).dp.coerceIn(480.dp, 720.dp),
        position = WindowPosition(Alignment.Center)
    )
    val scheme = if (themeState.dynamicColor || themeState.customAccentColor != -1) {
        themeState.dynamicColorScheme
    } else {
        null
    }
    val parallax by vm.parallaxBackground.collectAsState()
    val bgType by vm.launcherBgType.collectAsState()
    val bgImage by vm.launcherBgImagePath.collectAsState()
    val bgVideo by vm.launcherBgVideoPath.collectAsState()
    val materialOn by vm.materialTheme.collectAsState()
    val liveGlassOn by vm.liveWallpaperGlass.collectAsState()
    val customBgOn = (bgType == "image" && bgImage.isNotBlank()) ||
        (bgType == "video" && bgVideo.isNotBlank())
    val backdropOn = parallax || customBgOn
    val borderless = remember { vm.preferences.isBorderlessWindow() }
    val isDragging = remember { mutableStateOf(false) }
    var selected by remember(request) { mutableStateOf("") }
    val selectedName = if (selected.isEmpty()) {
        I18n.t("kernel.builtin")
    } else {
        request.kernels.firstOrNull { it.manifest.id == selected }?.manifest?.name ?: I18n.t("kernel.builtin")
    }

    Window(
        onCloseRequest = { vm.cancelKernelLaunch() },
        title = "PMCL ${I18n.t("kernel.choose_title")}",
        state = state,
        undecorated = borderless,
        transparent = borderless
    ) {
        if (borderless) {
            LaunchedEffect(Unit) {
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
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = if (backdropOn || materialOn || liveGlassOn) Color.Transparent
                        else MaterialTheme.colorScheme.surface
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            if (materialOn) MaterialField(Modifier.fillMaxSize(), linesOnly = liveGlassOn)
                            Column(Modifier.fillMaxSize()) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 22.dp, end = 8.dp, top = 16.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).then(if (borderless) windowDragModifier(isDragging) else Modifier)) {
                                        Text(
                                            I18n.t("kernel.choose_title"),
                                            style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        ) {
                                            Text(
                                                request.versionId,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (borderless) {
                                        IconButton(onClick = { vm.cancelKernelLaunch() }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = I18n.t("common.close"),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                                Column(
                                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    KernelOption(
                                        icon = PmclIcons.Launch,
                                        title = I18n.t("kernel.builtin"),
                                        detail = I18n.t("kernel.builtin_hint"),
                                        tags = listOf(I18n.t("kernel.builtin_tag")),
                                        selected = selected.isEmpty(),
                                        onClick = { selected = "" }
                                    )
                                    request.kernels.forEach { kernel ->
                                        val manifest = kernel.manifest
                                        KernelOption(
                                            icon = PmclIcons.Kernel,
                                            title = manifest.name,
                                            detail = manifest.description.ifBlank { manifest.author },
                                            tags = listOf(manifest.version, runtimeName(manifest.runtime))
                                                .filter { it.isNotBlank() },
                                            selected = selected == manifest.id,
                                            onClick = { selected = manifest.id }
                                        )
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                                Column(
                                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Button(
                                        onClick = { vm.confirmKernelLaunch(selected) },
                                        modifier = Modifier.fillMaxWidth().height(52.dp),
                                        shape = RoundedCornerShape(14.dp)
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(22.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            I18n.t("kernel.launch_with", selectedName),
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    TextButton(onClick = { vm.cancelKernelLaunch() }) {
                                        Text(I18n.t("common.cancel"))
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

@Composable
private fun KernelOption(
    icon: ImageVector,
    title: String,
    detail: String,
    tags: List<String>,
    selected: Boolean,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        animationSpec = tween(160),
        label = "kernelOptionBorder"
    )
    Card(
        modifier = Modifier.fillMaxWidth().glassCardBorder(16.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = glassCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        ),
        elevation = glassCardElevation(),
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor)
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tags.forEach { KernelTag(it, selected) }
                    }
                }
            }
            if (selected) {
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun KernelTag(text: String, selected: Boolean) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else glassSurfaceVariantColor()
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

private fun runtimeName(runtime: String): String = when (runtime) {
    "csharp" -> I18n.t("kernel.runtime.csharp")
    "rust" -> I18n.t("kernel.runtime.rust")
    else -> I18n.t("kernel.runtime.java")
}
