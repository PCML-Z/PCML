package com.pmcl.ui.page

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.LauncherTheme
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.ThemeState
import com.pmcl.ui.viewmodel.LauncherViewModel

/** 独立终端窗口。每次打开都是新会话，关掉不影响主界面里的终端。 */
@androidx.compose.runtime.Composable
fun TerminalWindow(
    vm: LauncherViewModel,
    windowId: Int,
    themeState: ThemeState,
    onClose: () -> Unit
) {
    val shift = ((windowId - 1).coerceAtLeast(0) % 8) * 28
    val state = rememberWindowState(
        width = 760.dp,
        height = 480.dp,
        position = WindowPosition((72 + shift).dp, (72 + shift).dp)
    )
    val scheme = if (themeState.dynamicColor || themeState.customAccentColor != -1) {
        themeState.dynamicColorScheme
    } else {
        null
    }
    Window(
        onCloseRequest = onClose,
        title = "PMCL ${I18n.t("terminal.title")}",
        state = state
    ) {
        LauncherTheme(
            useDarkTheme = themeState.useDark,
            dynamicColorScheme = scheme,
            uiScale = themeState.uiScale,
            themePreset = themeState.themePreset,
            colorMode = themeState.colorMode,
            customThemePack = themeState.customThemePack
        ) {
            CompositionLocalProvider(LocalThemeState provides themeState) {
                TerminalPage(vm, Modifier.fillMaxSize())
            }
        }
    }
}
