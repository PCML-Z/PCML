package com.pmcl.ui

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * 桌面端入口。
 *
 * 运行方式：./gradlew :ui:run
 */
fun main() = application {
    val state = rememberWindowState(
        width = 1100.dp,
        height = 700.dp,
        position = WindowPosition.Aligned(Alignment.Center)
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "PMCL — Minecraft Launcher",
        state = state
    ) {
        App()
    }
}
