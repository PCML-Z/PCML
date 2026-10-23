package com.pmcl.ui.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Coffee
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.ThemeState
import com.pmcl.ui.theme.installedLauncherFonts
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.setVersionIsolation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

private enum class Browse { IMAGE, VIDEO, DIRECTORY }

/** 一条设置。读值都是 lambda，改完后外面递增 tick 触发重读。 */
private sealed class SettingItem(
    val title: String,
    val desc: String,
    val warn: String,
    val visible: () -> Boolean,
) {
    open val wide: Boolean get() = false

    fun matches(q: String): Boolean = title.contains(q, true) || desc.contains(q, true)

    class Toggle(
        title: String, desc: String = "", warn: String = "",
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
        val enabled: () -> Boolean = { true },
        val confirmOn: String? = null,
        visible: () -> Boolean = { true },
    ) : SettingItem(title, desc, warn, visible)

    class Choice(
        title: String, desc: String = "", warn: String = "",
        val options: List<Pair<String, String>>,
        val get: () -> String,
        val set: (String) -> Unit,
        val enabled: () -> Boolean = { true },
        visible: () -> Boolean = { true },
    ) : SettingItem(title, desc, warn, visible) {
        override val wide: Boolean get() = options.size > 4
    }

    class Multi(
        title: String, desc: String = "",
        val options: List<Pair<String, String>>,
        val get: () -> Set<String>,
        val set: (Set<String>) -> Unit,
        visible: () -> Boolean = { true },
    ) : SettingItem(title, desc, "", visible)

    class Number(
        title: String, desc: String = "",
        val get: () -> Int,
        val set: (Int) -> Unit,
        val range: IntRange,
        val suffix: String = "",
        val note: () -> String = { "" },
        visible: () -> Boolean = { true },
    ) : SettingItem(title, desc, "", visible)

    class Text(
        title: String, desc: String = "",
        val get: () -> String,
        val set: (String) -> Unit,
        val placeholder: String = "",
        val secret: Boolean = false,
        val browse: Browse? = null,
        visible: () -> Boolean = { true },
    ) : SettingItem(title, desc, "", visible) {
        override val wide: Boolean get() = true
    }

    class Slider(
        title: String, desc: String = "",
        val get: () -> Float,
        val set: (Float) -> Unit,
        val range: ClosedFloatingPointRange<Float>,
        val steps: Int,
        val format: (Float) -> String,
        visible: () -> Boolean = { true },
    ) : SettingItem(title, desc, "", visible)

    class Swatches(
        title: String, desc: String = "",
        val options: List<Triple<String, String, Color?>>,
        val get: () -> String,
        val set: (String) -> Unit,
        val enabled: () -> Boolean = { true },
        visible: () -> Boolean = { true },
    ) : SettingItem(title, desc, "", visible) {
        override val wide: Boolean get() = true
    }

    class Picker(
        title: String, desc: String = "",
        val options: () -> List<Pair<String, String>>,
        val get: () -> String,
        val set: (String) -> Unit,
        visible: () -> Boolean = { true },
    ) : SettingItem(title, desc, "", visible) {
        override val wide: Boolean get() = true
    }

    class Custom(
        title: String, desc: String = "",
        val content: @Composable (tick: Int, bump: () -> Unit) -> Unit,
        visible: () -> Boolean = { true },
    ) : SettingItem(title, desc, "", visible) {
        override val wide: Boolean get() = true
    }
}

private class SettingsCategory(
    val id: String,
    val labelKey: String,
    val icon: ImageVector,
    val color: Color,
    val items: List<SettingItem>,
) {
    val label: String get() = I18n.t(labelKey)
}

/** 原生设置能覆盖的分区；其余分区（账户登录、设备绑定、许可证等流程较复杂）仍走经典页面。 */
internal val nativeSettingSections = mapOf(
    "launcher" to "launcher",
    "theme" to "theme",
    "java" to "java",
    "game" to "game",
    "network" to "network",
    "mio" to "mio",
    "system" to "system",
)

private val classicSections = listOf(
    "accounts", "automation", "compile", "cursor", "updates", "git-tree", "device", "about", "feedback", "licenses", "extensions"
)

private val isMacOs = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("mac")
private val isWindowsOs = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("windows")
private val isLinuxOs = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("linux")

private fun t(key: String) = I18n.t(key)

private fun buildCategories(vm: LauncherViewModel, theme: ThemeState, recommendedMax: () -> Long): List<SettingsCategory> {
    val pref = vm.preferences
    fun net(block: () -> Unit): () -> Unit = { block(); vm.applyNetworkPreferences() }

    val launcher = listOf(
        SettingItem.Choice(
            t("settings.language_label"), t("settings.language_hint"),
            options = listOf("zh_CN" to "简体中文", "zh_TW" to "繁體中文", "en_US" to "English", "ja_JP" to "日本語", "ud_EN" to "uʍop-ǝpᴉsdn"),
            get = { pref.getLanguage() }, set = { vm.setLanguage(it) }
        ),
        SettingItem.Toggle(
            t("settings.always_scrollbars"), t("settings.always_scrollbars_desc"),
            get = { pref.isAlwaysShowScrollbars() },
            set = { v ->
                pref.setAlwaysShowScrollbars(v); theme.applyAlwaysShowScrollbars(v)
                if (v) { pref.setShowScrollbarsOnScroll(false); theme.applyShowScrollbarsOnScroll(false) }
            }
        ),
        SettingItem.Toggle(
            t("settings.scrollbars_on_scroll"), t("settings.scrollbars_on_scroll_desc"),
            get = { pref.isShowScrollbarsOnScroll() },
            set = { v ->
                pref.setShowScrollbarsOnScroll(v); theme.applyShowScrollbarsOnScroll(v)
                if (v) { pref.setAlwaysShowScrollbars(false); theme.applyAlwaysShowScrollbars(false) }
            }
        ),
        SettingItem.Toggle(t("settings.borderless_window"), t("settings.borderless_window_desc"), get = { pref.isBorderlessWindow() }, set = { pref.setBorderlessWindow(it) }),
        SettingItem.Toggle(t("settings.segmented_launch"), t("settings.segmented_launch_desc"), get = { pref.isUseSegmentedLaunchLayout() }, set = { pref.setUseSegmentedLaunchLayout(it) }),
        SettingItem.Toggle(t("settings.predictive_launch"), t("settings.predictive_launch_desc"), get = { pref.isPredictiveLaunch() }, set = { pref.setPredictiveLaunch(it) }),
        SettingItem.Toggle(t("settings.perf_hud"), t("settings.perf_hud_desc"), get = { pref.isShowPerfHud() }, set = { vm.setPerfHudVisible(it) }),
        SettingItem.Multi(
            t("settings.perf_hud_metrics"),
            options = listOf("CPU" to "CPU", "MEM" to t("settings.memory"), "GPU" to "GPU", "FPS" to "FPS"),
            get = { pref.getPerfHudMetrics().split(",").map { it.trim().uppercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet() },
            set = { vm.setPerfHudMetrics(it.joinToString(",")) },
            visible = { pref.isShowPerfHud() }
        ),
        SettingItem.Toggle(t("settings.music_overlay_video"), t("settings.music_overlay_video_desc"), get = { pref.isMusicOverlayVideo() }, set = { vm.setMusicOverlayVideoEnabled(it) }),
        SettingItem.Toggle(t("settings.parse_kuaishou"), t("settings.music_links_desc"), get = { pref.isParseKuaishou() }, set = { pref.setParseKuaishou(it) }),
        SettingItem.Toggle(t("settings.parse_douyin"), t("settings.music_links_desc"), get = { pref.isParseDouyin() }, set = { pref.setParseDouyin(it) }),
        SettingItem.Toggle(t("settings.parse_youtube"), t("settings.music_links_desc"), get = { pref.isParseYoutube() }, set = { pref.setParseYoutube(it) }),
    )

    val presets = listOf(
        "default" to 0x3D8BFF, "ocean" to 0x0277BD, "forest" to 0x2E7D32, "sunset" to 0xE65100, "lavender" to 0x6A1B9A,
        "sakura" to 0xD81B60, "midnight" to 0x263238, "ember" to 0xC62828, "teal" to 0x00897B, "sand" to 0xF9A825,
    )
    val accents = listOf(0x3D8BFF, 0x55C57A, 0xFA8C16, 0xE91E63, 0x9C27B0, 0xF44336, 0x00BCD4, 0x8BC34A, 0xFFC107, 0x795548, 0x607D8B)
    val appearance = listOf(
        SettingItem.Toggle(t("settings.follow_system"), t("settings.follow_system_desc"), get = { theme.followSystem }, set = { vm.setFollowSystemTheme(it, theme) }),
        SettingItem.Toggle(
            t("settings.dark_theme"), t("settings.theme_persisted"),
            get = { theme.useDark }, set = { vm.onThemeModeChanged(it, theme) }, enabled = { !theme.followSystem }
        ),
        SettingItem.Swatches(
            t("settings.theme_preset"), t("settings.theme_preset_desc"),
            options = presets.map { (id, rgb) -> Triple(id, t("settings.theme_preset.$id"), Color(rgb or 0xFF000000.toInt())) },
            get = { theme.themePreset },
            set = { id ->
                if (theme.customThemePackId.isNotEmpty()) vm.applyCustomThemePack("", theme)
                vm.applyThemePreset(id, theme)
            },
            enabled = { !theme.dynamicColor && theme.customAccentColor == -1 && theme.customThemePackId.isEmpty() }
        ),
        SettingItem.Toggle(
            t("settings.monet_color"), t("settings.monet_color_desc"),
            get = { theme.dynamicColor },
            set = { v ->
                theme.enableDynamicColor(v)
                pref.setDynamicColor(v)
                if (v) {
                    vm.clearCustomAccentColor(theme)
                    vm.forceRefreshWallpaperColor(theme)
                } else {
                    theme.updateDynamicColorScheme(null)
                }
            }
        ),
        SettingItem.Swatches(
            t("settings.custom_accent"), t("settings.custom_accent_desc"),
            options = listOf(Triple("", t("studio.settings_accent_none"), null)) +
                accents.map { rgb -> Triple(rgb.toString(), String.format("#%06X", rgb), Color(rgb or 0xFF000000.toInt())) },
            get = { if (theme.customAccentColor == -1) "" else (theme.customAccentColor and 0x00FFFFFF).toString() },
            set = { id -> if (id.isEmpty()) vm.clearCustomAccentColor(theme) else vm.applyCustomAccentColor(id.toInt(), theme) },
            enabled = { !theme.dynamicColor }
        ),
        SettingItem.Choice(
            t("settings.color_mode"), t("settings.color_mode_desc"),
            options = listOf("normal", "amoled", "high_contrast", "soft").map { it to t("settings.color_mode.$it") },
            get = { theme.colorMode }, set = { vm.applyColorMode(it, theme) }
        ),
        SettingItem.Toggle(t("settings.glass_theme"), t("settings.glass_theme_desc"), get = { pref.isGlassTheme() }, set = { vm.setGlassTheme(it) }),
        SettingItem.Toggle(t("settings.material_theme"), t("settings.material_theme_desc"), get = { pref.isMaterialTheme() }, set = { vm.setMaterialTheme(it) }),
        SettingItem.Toggle(t("settings.live_wallpaper_glass"), t("settings.live_wallpaper_glass_desc"), get = { pref.isLiveWallpaperGlass() }, set = { vm.setLiveWallpaperGlass(it) }),
        SettingItem.Toggle(t("settings.lockscreen_launch"), t("settings.lockscreen_launch_desc"), get = { pref.isLockscreenLaunchTheme() }, set = { vm.setLockscreenLaunchTheme(it) }),
        SettingItem.Toggle(t("settings.parallax_background"), t("settings.parallax_background_desc"), get = { pref.isParallaxBackground() }, set = { vm.setParallaxBackground(it) }),
        SettingItem.Choice(
            t("settings.custom_bg"), t("settings.custom_bg_desc"),
            options = listOf("none" to t("settings.custom_bg_off"), "image" to t("settings.custom_bg_image"), "video" to t("settings.custom_bg_video")),
            get = { pref.getLauncherBgType().takeIf { it == "image" || it == "video" } ?: "none" },
            set = { vm.setLauncherBgType(it) }
        ),
        SettingItem.Text(
            t("settings.custom_bg_image"), get = { pref.getLauncherBgImagePath() }, set = { vm.setLauncherBgImagePath(it) },
            placeholder = t("settings.custom_bg_image_empty"), browse = Browse.IMAGE, visible = { pref.getLauncherBgType() == "image" }
        ),
        SettingItem.Text(
            t("settings.custom_bg_video"), get = { pref.getLauncherBgVideoPath() }, set = { vm.setLauncherBgVideoPath(it) },
            placeholder = t("settings.custom_bg_video_empty"), browse = Browse.VIDEO, visible = { pref.getLauncherBgType() == "video" }
        ),
        SettingItem.Choice(
            t("settings.field_geometry"), t("settings.field_geometry_desc"),
            options = listOf("LINES", "CIRCLES", "TRIANGLES", "DIAMONDS", "GRID", "ARCS", "CROSS").map { it to t("settings.field_geo_" + it.lowercase(Locale.ROOT)) },
            get = { pref.getFieldGeometry() }, set = { vm.setFieldGeometry(it) }
        ),
        SettingItem.Choice(
            t("settings.field_line"),
            options = listOf("SOLID", "DASHED", "DOTTED", "DOUBLE").map { it to t("settings.field_line_" + it.lowercase(Locale.ROOT)) },
            get = { pref.getFieldLineStyle() }, set = { vm.setFieldLineStyle(it) }
        ),
        SettingItem.Slider(
            t("settings.ui_scale"), t("settings.ui_scale_desc"),
            get = { pref.getUiScale() },
            set = { v -> theme.applyUiScale(v); pref.setUiScale(v) },
            range = 0.8f..1.5f, steps = 13,
            format = { "${(it * 100).roundToInt()}%" }
        ),
        SettingItem.Picker(
            t("settings.font"), t("settings.font_desc"),
            options = { listOf("" to t("settings.font_default")) + installedLauncherFonts().map { it to it } },
            get = { theme.launcherFont },
            set = { name -> theme.applyLauncherFont(name); pref.setLauncherFont(name) }
        ),
    )

    val java = listOf(
        SettingItem.Number(t("settings.min_memory"), t("settings.memory"), get = { pref.getMinMemoryMb() }, set = { pref.setMinMemoryMb(it) }, range = 0..262_144, suffix = "MB"),
        SettingItem.Number(
            t("settings.max_memory"), t("settings.memory"), get = { pref.getMaxMemoryMb() }, set = { pref.setMaxMemoryMb(it) }, range = 0..262_144, suffix = "MB",
            note = { recommendedMax().takeIf { it > 0 }?.let { I18n.t("settings.recommended_max", it) } ?: "" }
        ),
        SettingItem.Choice(
            t("settings.gc_type"),
            options = listOf("G1GC", "ZGC", "ShenandoahGC", "ParallelGC").map { it to it },
            get = { pref.getGcType() }, set = { pref.setGcType(it) }
        ),
        SettingItem.Toggle(t("settings.force_g1"), t("settings.force_g1_desc"), get = { pref.isForceG1Gc() }, set = { pref.setForceG1Gc(it) }),
        SettingItem.Toggle(t("settings.aikar"), t("settings.aikar_desc"), get = { pref.isUseAikarFlags() }, set = { pref.setUseAikarFlags(it) }),
        SettingItem.Text(t("settings.custom_args"), t("settings.custom_args_hint"), get = { pref.getCustomJvmArgs() }, set = { pref.setCustomJvmArgs(it) }),
        SettingItem.Toggle(t("settings.java_downgrade"), t("settings.java_downgrade_desc"), get = { pref.isJavaDowngradeFallback() }, set = { pref.setJavaDowngradeFallback(it) }),
        SettingItem.Toggle(t("settings.java27_compat"), t("settings.java27_compat_desc"), get = { pref.isJava27Compat() }, set = { pref.setJava27Compat(it) }),
        SettingItem.Custom(t("studio.settings_java_runtime"), t("studio.settings_java_runtime_desc"), content = { tick, bump -> JavaRuntimePicker(vm, tick, bump) }),
    )

    val game = buildList {
        add(SettingItem.Toggle(t("settings.version_isolation"), t("settings.version_isolation_full_desc"), get = { pref.isVersionIsolation() }, set = { vm.setVersionIsolation(it) }))
        add(SettingItem.Number(t("settings.window_resolution") + " · " + t("settings.width"), t("settings.window_resolution_hint"),
            get = { pref.getGameWindowWidth() }, set = { pref.setGameWindowWidth(it) }, range = 0..16_384, suffix = "px"))
        add(SettingItem.Number(t("settings.window_resolution") + " · " + t("settings.height"), t("settings.window_resolution_hint"),
            get = { pref.getGameWindowHeight() }, set = { pref.setGameWindowHeight(it) }, range = 0..16_384, suffix = "px"))
        add(SettingItem.Toggle(t("settings.fullscreen_launch"), t("settings.fullscreen_launch_desc"), get = { pref.isGameFullscreen() }, set = { pref.setGameFullscreen(it) }))
        add(SettingItem.Toggle(t("settings.demo_mode"), t("settings.demo_mode_desc"), get = { pref.isGameDemo() }, set = { pref.setGameDemo(it) }))
        add(SettingItem.Choice(
            t("settings.renderer"), t("settings.renderer_hint"),
            options = buildList {
                add("AUTO" to t("settings.renderer_auto")); add("OPENGL" to "OpenGL"); add("VULKAN" to "Vulkan")
                if (isWindowsOs) add("DIRECTX" to t("settings.renderer_directx"))
            },
            get = { pref.getGameRenderer() }, set = { pref.setGameRenderer(it) }
        ))
        if (isLinuxOs) add(SettingItem.Toggle(t("settings.linux_zink"), t("settings.linux_zink_desc"), get = { pref.isLinuxZink() }, set = { pref.setLinuxZink(it) }))
        if (isMacOs) {
            add(SettingItem.Toggle(t("settings.mac_microphone"), t("settings.mac_microphone_desc"), get = { pref.isMacMicrophoneRequest() }, set = { pref.setMacMicrophoneRequest(it) }))
            add(SettingItem.Toggle(t("settings.mac_game_mode"), t("settings.mac_game_mode_desc"), get = { pref.isMacGameMode() }, set = { pref.setMacGameMode(it) }))
        }
        add(SettingItem.Toggle(t("settings.prefer_utf8"), t("settings.prefer_utf8_desc"), get = { pref.isPreferUtf8() }, set = { pref.setPreferUtf8(it) }))
        add(SettingItem.Toggle(t("settings.prefer_ipv4"), t("settings.prefer_ipv4_desc"), get = { pref.isPreferIPv4() }, set = { pref.setPreferIPv4(it) }))
        add(SettingItem.Choice(
            t("settings.after_game"), t("settings.after_game_desc"),
            options = listOf("KEEP" to t("settings.after_game_keep"), "MINIMIZE" to t("settings.after_game_minimize"), "HIDE" to t("settings.after_game_hide"), "CLOSE" to t("settings.after_game_close")),
            get = { pref.getLauncherAfterGame() }, set = { pref.setLauncherAfterGame(it) }
        ))
        add(SettingItem.Toggle(t("settings.show_game_output"), t("settings.show_game_output_desc"), get = { pref.isShowGameOutput() }, set = { pref.setShowGameOutput(it) }))
        add(SettingItem.Choice(
            t("settings.launcher_log_level"), t("settings.launcher_log_level_desc"),
            options = listOf("DEBUG", "INFO", "WARN", "ERROR", "OFF").map { it to t("settings.log_level_" + it.lowercase(Locale.ROOT)) },
            get = { pref.getLauncherLogLevel() }, set = { pref.setLauncherLogLevel(it) }
        ))
        add(SettingItem.Toggle(t("settings.fast_resource_check"), t("settings.fast_resource_check_desc"), get = { pref.isFastResourceCheck() }, set = { pref.setFastResourceCheck(it) }))
        add(SettingItem.Number(t("settings.resource_check_parallel"), t("settings.resource_check_parallel_desc"),
            get = { pref.getResourceCheckParallelism() }, set = { pref.setResourceCheckParallelism(it) }, range = 1..99))
        add(SettingItem.Number(t("settings.resource_repair_retries"), t("settings.resource_repair_retries_desc"),
            get = { pref.getResourceRepairRetries() }, set = { pref.setResourceRepairRetries(it) }, range = 0..99))
        add(SettingItem.Text(t("settings.server_address"), t("settings.server_connect_hint"),
            get = { pref.getGameServerHost() }, set = { pref.setGameServerHost(it) }, placeholder = t("settings.server_address_placeholder")))
        add(SettingItem.Number(t("settings.server_port"), get = { pref.getGameServerPort() }, set = { pref.setGameServerPort(it) }, range = 0..65_535))
        add(SettingItem.Text(t("settings.server_token"), t("settings.server_token_hint"),
            get = { pref.getGameServerToken() }, set = { pref.setGameServerToken(it) }, placeholder = t("settings.server_token_placeholder"), secret = true))
        add(SettingItem.Text(t("settings.window_icon"), t("settings.window_icon_hint"),
            get = { pref.getWindowIconPath() }, set = { pref.setWindowIconPath(it) }, placeholder = t("settings.window_icon_empty"), browse = Browse.IMAGE))
        add(SettingItem.Text(t("settings.menu_bg_video"), t("settings.menu_bg_video_hint"),
            get = { pref.getCustomMenuBackgroundVideo() }, set = { pref.setCustomMenuBackgroundVideo(it) }, placeholder = t("settings.menu_bg_video_empty"), browse = Browse.VIDEO))
        add(SettingItem.Choice(
            t("settings.natives_strategy"), t("settings.natives_strategy_desc"),
            options = listOf("AUTO", "ALWAYS", "NEVER").map { it to t("settings.natives_strategy_" + it.lowercase(Locale.ROOT)) },
            get = { pref.getNativesReplaceStrategy() }, set = { pref.setNativesReplaceStrategy(it) }
        ))
        add(SettingItem.Text(t("settings.custom_natives"), t("settings.custom_natives_hint"),
            get = { pref.getCustomNativesPath() }, set = { pref.setCustomNativesPath(it) }, placeholder = t("settings.custom_natives_empty"), browse = Browse.DIRECTORY))
    }

    val network = listOf(
        SettingItem.Choice(
            t("settings.mirror"),
            options = listOf("OFFICIAL" to t("settings.mirror_official"), "BMCLAPI" to "BMCLAPI", "CUSTOM" to t("settings.mirror_custom")),
            get = { pref.getMirrorType() }, set = { v -> net { pref.setMirrorType(v) }() }
        ),
        SettingItem.Text(t("settings.custom_mirror_full"), get = { pref.getCustomMirrorBase() }, set = { v -> net { pref.setCustomMirrorBase(v) }() }, visible = { pref.getMirrorType() == "CUSTOM" }),
        SettingItem.Toggle(t("settings.http_proxy"), get = { pref.isUseProxy() }, set = { v -> net { pref.setUseProxy(v) }() }),
        SettingItem.Choice(
            t("settings.proxy_type"), t("settings.proxy_socks_hint"),
            options = listOf("HTTP" to t("settings.proxy_type_http"), "SOCKS5" to t("settings.proxy_type_socks5")),
            get = { pref.getProxyType() }, set = { v -> net { pref.setProxyType(v) }() }, visible = { pref.isUseProxy() }
        ),
        SettingItem.Text(t("settings.proxy_host"), get = { pref.getProxyHost() }, set = { v -> net { pref.setProxyHost(v) }() }, visible = { pref.isUseProxy() }),
        SettingItem.Number(t("settings.proxy_port"), get = { pref.getProxyPort() }, set = { v -> net { pref.setProxyPort(v) }() }, range = 0..65_535, visible = { pref.isUseProxy() }),
        SettingItem.Toggle(t("settings.proxy_auth"), get = { pref.isUseHttpAuth() }, set = { v -> net { pref.setUseHttpAuth(v) }() },
            visible = { pref.isUseProxy() && pref.getProxyType() != "SOCKS5" }),
        SettingItem.Text(t("settings.proxy_username"), get = { pref.getProxyUsername() }, set = { v -> net { pref.setProxyUsername(v) }() },
            visible = { pref.isUseProxy() && pref.isUseHttpAuth() && pref.getProxyType() != "SOCKS5" }),
        SettingItem.Text(t("settings.proxy_password"), get = { pref.getProxyPassword() }, set = { v -> net { pref.setProxyPassword(v) }() }, secret = true,
            visible = { pref.isUseProxy() && pref.isUseHttpAuth() && pref.getProxyType() != "SOCKS5" }),
        SettingItem.Number(t("settings.speed_limit"), get = { pref.getDownloadSpeedLimitKb() }, set = { v -> net { pref.setDownloadSpeedLimitKb(v) }() }, range = 0..1_000_000, suffix = "KB/s"),
        SettingItem.Number(t("settings.retry_count"), get = { pref.getDownloadRetryCount() }, set = { v -> net { pref.setDownloadRetryCount(v) }() }, range = 0..20),
        SettingItem.Number(t("settings.chunked_threads"), t("settings.chunked_download_hint"),
            get = { pref.getChunkedDownloadThreads() }, set = { v -> net { pref.setChunkedDownloadThreads(v) }() }, range = 1..64),
        SettingItem.Toggle(t("settings.enable_resume"), get = { pref.isEnableResume() }, set = { v -> net { pref.setEnableResume(v) }() }),
    )

    val mioOn = { pref.isMioModeEnabled() }
    fun mio(key: String, get: () -> Boolean, set: (Boolean) -> Unit, hint: String? = null, warn: String? = null, confirm: Boolean = false) =
        SettingItem.Toggle(
            t("settings.perf.$key"),
            t("settings.perf.${key}_desc") + (hint?.let { "\n" + t("settings.perf.$it") } ?: ""),
            warn = warn?.let { t("settings.perf.$it") } ?: "",
            get = get, set = set,
            confirmOn = if (confirm) t("settings.perf.admin_auth_confirm") else null,
            visible = mioOn
        )
    val mio = listOf(
        SettingItem.Toggle(t("settings.perf.mei_mode"), t("settings.perf.mei_mode_desc"), t("settings.perf.mei_mode_warning"),
            get = { pref.isMioModeEnabled() }, set = { pref.setMioModeEnabled(it) }),
        mio("l1_jvm_aggressive", { pref.isMioModeJvm() }, { pref.setMioModeJvm(it) }, hint = "l1_jvm_aggressive_hint"),
        mio("l1_large_pages", { pref.isMioModeLargePages() }, { pref.setMioModeLargePages(it) }, hint = "l1_large_pages_hint"),
        mio("l1_experimental_zgc", { pref.isMioModeZgc() }, { pref.setMioModeZgc(it) }, warn = "l1_experimental_zgc_warning"),
        mio("l1_lwjgl_render", { pref.isMioModeRenderOpt() }, { pref.setMioModeRenderOpt(it) }),
        mio("l1_jit_aggressive", { pref.isMioModeJitAggressive() }, { pref.setMioModeJitAggressive(it) }),
        mio("l1_network_opt", { pref.isMioModeNetworkOpt() }, { pref.setMioModeNetworkOpt(it) }),
        mio("l1_metaspace", { pref.isMioModeMetaspace() }, { pref.setMioModeMetaspace(it) }),
        mio("l1_pretouch", { pref.isMioModePretouch() }, { pref.setMioModePretouch(it) }, hint = "l1_pretouch_hint"),
        mio("l1_string_dedup", { pref.isMioModeStringDedup() }, { pref.setMioModeStringDedup(it) }, hint = "l1_string_dedup_hint"),
        mio("l1_idle_gc", { pref.isMioModeIdleGc() }, { pref.setMioModeIdleGc(it) }, hint = "l1_idle_gc_hint"),
        mio("l2_process", { pref.isMioModeProcess() }, { pref.setMioModeProcess(it) }, hint = "l2_process_hint"),
        mio("l2_keep_awake", { pref.isMioModeKeepAwake() }, { pref.setMioModeKeepAwake(it) }, hint = "l2_keep_awake_hint"),
        mio("l2_discrete_gpu", { pref.isMioModeDiscreteGpu() }, { pref.setMioModeDiscreteGpu(it) }, hint = "l2_discrete_gpu_hint"),
        mio("l2_gamemode", { pref.isMioModeGameMode() }, { pref.setMioModeGameMode(it) }, hint = "l2_gamemode_hint"),
        mio("l2_crazy_priority", { pref.isMioModeCrazyPriority() }, { pref.setMioModeCrazyPriority(it) }, warn = "l2_crazy_priority_warning", confirm = true),
        mio("l3_system_power", { pref.isMioModeSystemPower() }, { pref.setMioModeSystemPower(it) }, warn = "l3_system_power_warning", confirm = true),
    )

    val system = listOf(
        SettingItem.Custom(t("settings.system_info"), content = { _, _ -> SystemInfoBlock(vm) }),
    )

    return listOf(
        SettingsCategory("launcher", "settings.section.launcher", Icons.Filled.Settings, Tiles.Blue, launcher),
        SettingsCategory("theme", "settings.section.theme", Icons.Filled.Palette, Tiles.Navy, appearance),
        SettingsCategory("java", "settings.section.java", Icons.Filled.Coffee, Tiles.Sky, java),
        SettingsCategory("game", "settings.section.game", Icons.Filled.SportsEsports, Tiles.Teal, game),
        SettingsCategory("network", "settings.section.network", Icons.Filled.Public, Tiles.Plum, network),
        SettingsCategory("mio", "settings.section.mio", Icons.Filled.Speed, Tiles.Slate, mio),
        SettingsCategory("system", "settings.section.system", Icons.Filled.Computer, Tiles.Graphite, system),
    )
}

/** IDEA 式设置：左边分类和搜索，右边一行一条设置，控件全部是平切色块。 */
@Composable
internal fun SettingsEditor(
    vm: LauncherViewModel,
    themeState: ThemeState,
    category: String?,
    onCategory: (String) -> Unit,
    onClassic: (String) -> Unit,
    onConfirm: (String, String, () -> Unit) -> Unit,
) {
    val p = LocalStudio.current
    val recommended by produceState(0L) {
        value = withContext(Dispatchers.IO) { runCatching { vm.core.runtime().getRecommendedMaxMemoryMb() }.getOrDefault(0L) }
    }
    val latestRecommended by rememberUpdatedState(recommended)
    val categories = remember(vm, themeState) { buildCategories(vm, themeState) { latestRecommended } }
    var tick by remember { mutableIntStateOf(0) }
    val bump: () -> Unit = { tick++ }
    var query by remember { mutableStateOf("") }
    val current = categories.firstOrNull { it.id == category } ?: categories.first()
    // 主启动器那边改了主题也要跟着刷新
    val themeKey = listOf(themeState.useDark, themeState.followSystem, themeState.dynamicColor, themeState.customAccentColor, themeState.colorMode, themeState.themePreset)
    val glass by vm.glassTheme.collectAsState()
    val hud by vm.perfHudVisible.collectAsState()
    val readKey = Triple(tick, themeKey, glass to hud)
    // 控件按它重读：Studio 内改动和主启动器那边的改动都会让它变
    val version = readKey.hashCode()
    val headerColor by animateColorAsState(current.color, tween(220), label = "settingsHeader")

    Row(Modifier.fillMaxSize().background(p.editor)) {
        Column(Modifier.width(232.dp).fillMaxHeight().background(p.panel)) {
            FlatField(query, { query = it }, I18n.t("studio.settings_search"), Modifier.fillMaxWidth().height(36.dp), icon = Icons.Filled.Search)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                categories.forEachIndexed { i, c ->
                    val count = remember(query, readKey) {
                        val q = query.trim()
                        c.items.count { it.visible() && (q.isEmpty() || it.matches(q)) }
                    }
                    NavRow(c.label, c.icon, c.color, query.isBlank() && c.id == current.id, count.toString(), i) {
                        query = ""
                        onCategory(c.id)
                    }
                }
                FlatText(
                    I18n.t("studio.settings_classic"),
                    color = p.muted,
                    size = 11,
                    weight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 12.dp, vertical = 8.dp)
                )
                classicSections.forEachIndexed { i, id ->
                    NavRow(I18n.t("settings.section." + id.replace('-', '_')), Icons.AutoMirrored.Filled.OpenInNew, Tiles.Slate, false, "", categories.size + i) {
                        onClassic(id)
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            val q = query.trim()
            if (q.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().height(84.dp).background(headerColor).padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(current.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        AnimatedContent(
                            current.label,
                            transitionSpec = { (fadeIn(tween(180)) + slideInVertically(tween(220)) { it / 3 }) togetherWith fadeOut(tween(120)) },
                            label = "settingsTitle"
                        ) { FlatText(it, color = Color.White, size = 22, weight = FontWeight.Light) }
                        FlatText(I18n.t("studio.settings_saved_hint"), color = Color.White.copy(alpha = 0.75f), size = 11)
                    }
                }
                val shown = current.items.filter { it.visible() }
                // 每个分类各自一份滚动位置，切分类时回到顶部
                key(current.id) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown.size, key = { current.id + ":" + shown[it].title }) { i ->
                            SettingRow(shown[i], i, version, bump, onConfirm)
                        }
                    }
                }
            } else {
                val hits = categories.map { c -> c to c.items.filter { it.visible() && it.matches(q) } }.filter { it.second.isNotEmpty() }
                Row(Modifier.fillMaxWidth().height(40.dp).background(p.accent).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlatText(I18n.t("studio.settings_results", hits.sumOf { it.second.size }.toString(), q), color = Color.White, weight = FontWeight.SemiBold)
                }
                if (hits.isEmpty()) {
                    CenterNote(I18n.t("studio.settings_no_results"))
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        hits.forEach { (c, list) ->
                            item(key = "h:" + c.id) {
                                Row(
                                    Modifier.fillMaxWidth().height(30.dp).background(p.panelAlt).clickable { query = ""; onCategory(c.id) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(Modifier.width(4.dp).fillMaxHeight().background(c.color))
                                    Icon(c.icon, contentDescription = null, tint = c.color, modifier = Modifier.padding(horizontal = 10.dp).size(14.dp))
                                    FlatText(c.label, weight = FontWeight.SemiBold)
                                }
                            }
                            items(list.size, key = { "r:" + c.id + ":" + list[it].title }) { i ->
                                SettingRow(list[i], i, version, bump, onConfirm)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRow(label: String, icon: ImageVector, color: Color, selected: Boolean, count: String, index: Int, onClick: () -> Unit) {
    val p = LocalStudio.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            selected -> color
            hovered -> p.hover
            else -> Color.Transparent
        },
        tween(160),
        label = "settingsNav"
    )
    val square by animateDpAsState(if (selected || hovered) 40.dp else 36.dp, spring(0.6f, 600f), label = "navSquare")
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .entrance(index)
            .background(bg)
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(square).fillMaxHeight().background(if (selected) color.lift(0.14f) else color), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        FlatText(label, color = if (selected) Color.White else p.text, weight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (count.isNotEmpty()) FlatText(count, color = if (selected) Color.White.copy(alpha = 0.8f) else p.muted, size = 11, modifier = Modifier.padding(end = 12.dp))
    }
}

@Composable
private fun SettingRow(item: SettingItem, index: Int, tick: Int, bump: () -> Unit, onConfirm: (String, String, () -> Unit) -> Unit) {
    val p = LocalStudio.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val bg by animateColorAsState(if (hovered) p.hover else if (index % 2 == 0) p.editor else p.panel, tween(140), label = "settingRow")
    val bar by animateDpAsState(if (hovered) 4.dp else 0.dp, tween(160), label = "settingBar")
    val accent = p.accent
    Box(
        Modifier
            .fillMaxWidth()
            .entrance(index)
            .background(bg)
            .drawBehind { drawRect(accent, size = Size(bar.toPx(), size.height)) }
            .hoverable(source)
    ) {
        val label: @Composable () -> Unit = {
            Column {
                FlatText(item.title, weight = FontWeight.Medium, size = 13)
                if (item.desc.isNotBlank()) FlatText(item.desc, color = p.muted, size = 11, lines = 4)
                if (item.warn.isNotBlank()) FlatText(item.warn, color = Tiles.Red, size = 11, lines = 3)
                if (item is SettingItem.Number) {
                    val note = remember(tick) { item.note() }
                    if (note.isNotBlank()) FlatText(note, color = p.accent, size = 11)
                }
            }
        }
        if (item.wide) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                label()
                Spacer(Modifier.height(8.dp))
                Control(item, tick, bump, onConfirm)
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { label() }
                Spacer(Modifier.width(16.dp))
                Control(item, tick, bump, onConfirm)
            }
        }
    }
}

@Composable
private fun Control(item: SettingItem, tick: Int, bump: () -> Unit, onConfirm: (String, String, () -> Unit) -> Unit) {
    val p = LocalStudio.current
    when (item) {
        is SettingItem.Toggle -> {
            val on = remember(tick) { item.get() }
            val enabled = remember(tick) { item.enabled() }
            FlatSwitch(on, enabled) { v ->
                val confirm = item.confirmOn
                if (v && confirm != null) onConfirm(item.title, confirm) { item.set(true); bump() }
                else { item.set(v); bump() }
            }
        }
        is SettingItem.Choice -> {
            val value = remember(tick) { item.get() }
            val enabled = remember(tick) { item.enabled() }
            Row(Modifier.horizontalScroll(rememberScrollState()).alpha(if (enabled) 1f else 0.45f)) {
                item.options.forEach { (id, label) ->
                    Block(label, onClick = { item.set(id); bump() }, fill = if (id.equals(value, true)) p.accent else p.field, enabled = enabled, height = 30.dp)
                }
            }
        }
        is SettingItem.Multi -> {
            val value = remember(tick) { item.get() }
            Row {
                item.options.forEach { (id, label) ->
                    val on = id in value
                    Block(
                        label,
                        onClick = {
                            val next = if (on) value - id else value + id
                            if (next.isNotEmpty()) { item.set(next); bump() }
                        },
                        icon = if (on) Icons.Filled.Check else null,
                        fill = if (on) p.accent else p.field,
                        height = 30.dp
                    )
                }
            }
        }
        is SettingItem.Number -> {
            val value = remember(tick) { item.get() }
            NumberField(value, item.range, item.suffix) { v -> item.set(v); bump() }
        }
        is SettingItem.Text -> {
            val value = remember(tick) { item.get() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextBox(value, item.placeholder, item.secret, Modifier.width(460.dp)) { v -> item.set(v); bump() }
                val browse = item.browse
                if (browse != null) {
                    Block("", onClick = { pickPath(browse)?.let { item.set(it); bump() } }, icon = Icons.Filled.FolderOpen, fill = Tiles.Slate, height = 30.dp)
                }
            }
        }
        is SettingItem.Slider -> {
            val value = remember(tick) { item.get() }
            var shown by remember(tick) { mutableStateOf(value) }
            // 拖动时只预览数值；界面缩放这类会重排窗口的设置松手才写入，否则滑块跟着窗口一起抖
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlatSlider(shown, item.range, item.steps, onChange = { v -> shown = v }, onCommit = { v -> shown = v; item.set(v); bump() })
                FlatText(item.format(shown), color = Color.White, weight = FontWeight.SemiBold, modifier = Modifier.width(56.dp).background(p.accent).padding(horizontal = 8.dp, vertical = 6.dp))
            }
        }
        is SettingItem.Swatches -> {
            val value = remember(tick) { item.get() }
            val enabled = remember(tick) { item.enabled() }
            Row(Modifier.horizontalScroll(rememberScrollState()).alpha(if (enabled) 1f else 0.45f)) {
                item.options.forEachIndexed { i, (id, label, color) ->
                    Swatch(label, color, id == value, enabled, i) { item.set(id); bump() }
                }
            }
        }
        is SettingItem.Picker -> {
            val value = remember(tick) { item.get() }
            PickerControl(item, value) { v -> item.set(v); bump() }
        }
        is SettingItem.Custom -> item.content(tick, bump)
    }
}

@Composable
internal fun FlatSwitch(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalStudio.current
    val track by animateColorAsState(if (checked) p.accent else p.field, tween(160), label = "switchTrack")
    val knob by animateDpAsState(if (checked) 22.dp else 0.dp, spring(0.62f, 700f), label = "switchKnob")
    Box(
        Modifier
            .width(46.dp)
            .height(24.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .background(track)
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(3.dp)
    ) {
        Box(Modifier.offset(x = knob).size(18.dp).background(if (checked) Color.White else p.muted))
    }
}

@Composable
internal fun FlatSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit, onCommit: (Float) -> Unit) {
    val p = LocalStudio.current
    var width by remember { mutableIntStateOf(1) }
    val latest by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    var dragged by remember { mutableStateOf(value) }
    fun pick(x: Float): Float {
        val f = (x / width).coerceIn(0f, 1f)
        var v = range.start + f * (range.endInclusive - range.start)
        if (steps > 0) {
            val step = (range.endInclusive - range.start) / (steps + 1)
            v = range.start + ((v - range.start) / step).roundToInt() * step
        }
        val picked = v.coerceIn(range.start, range.endInclusive)
        latest(picked)
        return picked
    }
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val shown by animateFloatAsState(fraction, tween(90), label = "slider")
    Box(
        Modifier
            .width(260.dp)
            .height(30.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { commit(pick(it.x)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { commit(dragged) },
                    onDragCancel = { commit(dragged) }
                ) { change, _ -> change.consume(); dragged = pick(change.position.x) }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(p.field))
        Box(Modifier.fillMaxWidth(shown).height(6.dp).background(p.accent))
        Box(
            Modifier
                .offset { IntOffset(((width - 10.dp.roundToPx()) * shown).roundToInt(), 0) }
                .width(10.dp)
                .height(22.dp)
                .background(p.accentDeep)
        )
    }
}

@Composable
private fun Swatch(label: String, color: Color?, selected: Boolean, enabled: Boolean, index: Int, onClick: () -> Unit) {
    val p = LocalStudio.current
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val fill = color ?: p.field
    val height by animateDpAsState(if (selected) 64.dp else if (hovered) 58.dp else 54.dp, spring(0.6f, 600f), label = "swatch")
    Column(
        Modifier
            .width(76.dp)
            .entrance(index)
            .hoverable(source)
            .clickable(enabled = enabled, interactionSource = source, indication = null, onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().height(height).background(fill), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = inkOn(fill), modifier = Modifier.size(18.dp))
        }
        FlatText(
            label,
            color = if (selected) Color.White else p.muted,
            size = 10,
            modifier = Modifier.fillMaxWidth().background(if (selected) p.accent else Color.Transparent).padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun NumberField(value: Int, range: IntRange, suffix: String, onCommit: (Int) -> Unit) {
    val p = LocalStudio.current
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in range
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(120.dp).height(30.dp).background(if (valid || text.isEmpty()) p.field else Tiles.Red.copy(alpha = 0.18f)).padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = text,
                onValueChange = { v ->
                    val digits = v.filter { it.isDigit() }.take(9)
                    text = digits
                    digits.toIntOrNull()?.takeIf { it in range }?.let(onCommit)
                },
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 13.sp, fontFamily = mono.fontFamily),
                cursorBrush = SolidColor(p.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (suffix.isNotEmpty()) {
            FlatText(suffix, color = Color.White, size = 11, modifier = Modifier.height(30.dp).background(Tiles.Slate).padding(horizontal = 8.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun TextBox(value: String, placeholder: String, secret: Boolean, modifier: Modifier, onCommit: (String) -> Unit) {
    val p = LocalStudio.current
    var text by remember(value) { mutableStateOf(value) }
    var reveal by remember { mutableStateOf(false) }
    Row(modifier.height(30.dp).background(p.field), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) FlatText(placeholder, color = p.muted)
            BasicTextField(
                value = text,
                onValueChange = { text = it; onCommit(it) },
                singleLine = true,
                textStyle = TextStyle(color = p.text, fontSize = 12.sp),
                cursorBrush = SolidColor(p.accent),
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (secret) {
            Block(if (reveal) I18n.t("studio.settings_hide") else I18n.t("studio.settings_show"), onClick = { reveal = !reveal }, height = 30.dp)
        }
    }
}

@Composable
private fun PickerControl(item: SettingItem.Picker, current: String, onPick: (String) -> Unit) {
    val p = LocalStudio.current
    var open by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val options by produceState(emptyList<Pair<String, String>>(), open) {
        if (open) value = withContext(Dispatchers.IO) { item.options() }
    }
    val label = options.firstOrNull { it.first == current }?.second ?: current.ifEmpty { I18n.t("settings.font_default") }
    Column(Modifier.width(460.dp)) {
        Block(label, onClick = { open = !open }, fill = if (open) p.accent else p.field, modifier = Modifier.fillMaxWidth(), height = 30.dp)
        AnimatedVisibility(open, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().background(p.panelAlt)) {
                FlatField(filter, { filter = it }, I18n.t("studio.filter"), Modifier.fillMaxWidth(), icon = Icons.Filled.Search)
                val shown = options.filter { filter.isBlank() || it.second.contains(filter.trim(), true) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    items(shown, key = { it.first }) { (id, name) ->
                        val on = id == current
                        FlatText(
                            name,
                            color = if (on) Color.White else p.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (on) p.accent else Color.Transparent)
                                .clickable { onPick(id); open = false }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun pickPath(kind: Browse): String? = try {
    when (kind) {
        Browse.DIRECTORY -> {
            val chooser = javax.swing.JFileChooser()
            chooser.fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
        }
        else -> {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, "", java.awt.FileDialog.LOAD)
            val exts = if (kind == Browse.IMAGE) setOf("png", "jpg", "jpeg", "webp", "bmp", "gif", "ico") else setOf("mp4", "webm", "mov", "mkv", "avi")
            dialog.filenameFilter = java.io.FilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase(Locale.ROOT) in exts }
            dialog.isVisible = true
            if (dialog.file != null) File(dialog.directory, dialog.file).absolutePath else null
        }
    }
} catch (_: Throwable) {
    null
}

@Composable
private fun JavaRuntimePicker(vm: LauncherViewModel, tick: Int, bump: () -> Unit) {
    val p = LocalStudio.current
    val javas by vm.javaInstallations.collectAsState()
    val mode by vm.javaSelectionMode.collectAsState()
    val scanning by vm.javaScanning.collectAsState()
    val selected = remember(tick, mode) { vm.preferences.getJavaPath() }
    Column(Modifier.fillMaxWidth()) {
        Row {
            Block(I18n.t("studio.settings_java_auto"), onClick = { vm.setJavaSelectionMode("AUTO"); bump() }, fill = if (mode != "SELECTED") p.accent else p.field, height = 30.dp)
            Block(
                if (scanning) I18n.t("studio.loading") else I18n.t("studio.settings_java_scan"),
                onClick = { vm.scanJavaInstallations() },
                icon = Icons.Filled.Refresh,
                fill = Tiles.Slate,
                enabled = !scanning,
                height = 30.dp
            )
        }
        javas.sortedByDescending { it.majorVersion }.forEachIndexed { i, java ->
            val on = mode == "SELECTED" && java.path == selected
            val source = remember(java.path) { MutableInteractionSource() }
            val hovered by source.collectIsHoveredAsState()
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .entrance(i)
                    .background(if (on) p.accent else if (hovered) p.hover else if (i % 2 == 0) p.panel else p.panelAlt)
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = null) { vm.selectJavaInstallation(java.path); bump() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlatText(
                    java.majorVersion.toString(),
                    color = Color.White,
                    weight = FontWeight.SemiBold,
                    modifier = Modifier.width(44.dp).fillMaxHeight().background(if (on) p.accentDeep else Tiles.Slate).padding(horizontal = 10.dp, vertical = 10.dp)
                )
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    FlatText(java.path, color = if (on) Color.White else p.text, size = 11)
                    FlatText(
                        listOf(java.architecture, java.source).filter { !it.isNullOrBlank() }.joinToString(" · "),
                        color = if (on) Color.White.copy(alpha = 0.75f) else p.muted,
                        size = 10
                    )
                }
                if (on) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.padding(end = 12.dp).size(16.dp))
            }
        }
    }
}

@Composable
private fun SystemInfoBlock(vm: LauncherViewModel) {
    val p = LocalStudio.current
    val info = remember { vm.systemInfo }
    val workDir = remember { vm.config.getWorkDir() }
    val rt = Runtime.getRuntime()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(84.dp), horizontalArrangement = Arrangement.Start) {
            ColorTile("JVM", System.getProperty("java.version").orEmpty(), Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Coffee, index = 0)
            ColorTile(I18n.t("studio.settings_cpus"), rt.availableProcessors().toString(), Tiles.Navy, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Speed, index = 1)
            ColorTile(I18n.t("studio.settings_heap"), formatSize(rt.maxMemory()), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Computer, index = 2)
            ColorTile(I18n.t("studio.settings_workdir"), File(workDir.toString()).name, Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.FolderOpen,
                { vm.openDir(File(workDir.toString())) }, index = 3)
        }
        FlatText(info, color = p.text, lines = 30, modifier = Modifier.fillMaxWidth().background(p.panel).padding(14.dp))
        FlatText(I18n.t("settings.work_dir_value", workDir), color = p.muted, size = 11, lines = 2, modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 14.dp, vertical = 8.dp))
    }
}
