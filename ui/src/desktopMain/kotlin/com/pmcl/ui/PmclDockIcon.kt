package com.pmcl.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.pmcl.ui.navigation.PmclIconStyles
import java.awt.Image
import javax.imageio.ImageIO

/** macOS 的 Dock 图标固定用 pmcl.icns。这里只换 Windows 和 Linux 的任务栏图标。 */
@Composable
fun PmclDockIconEffect() {
    val asset = PmclIconStyles.asset()
    LaunchedEffect(asset) {
        if (System.getProperty("os.name", "").contains("mac", ignoreCase = true)) return@LaunchedEffect
        val image = loadIcon(asset) ?: return@LaunchedEffect
        runCatching { java.awt.Taskbar.getTaskbar().setIconImage(image) }
    }
}

private fun loadIcon(asset: String): Image? {
    val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream(asset)
        ?: ClassLoader.getSystemResourceAsStream(asset)
        ?: return null
    return stream.use { runCatching { ImageIO.read(it) }.getOrNull() }
}
