package com.pmcl.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import java.awt.GraphicsEnvironment
import java.io.File

@OptIn(ExperimentalTextApi::class)
actual fun launcherFontFamily(requested: String): FontFamily {
    if (requested.isNotBlank() && requested in installedNames()) {
        return FontFamily(requested)
    }
    return bundledDin() ?: FontFamily(autoFamilyName())
}

actual fun installedLauncherFonts(): List<String> {
    val names = installedNames()
    val preferred = listOf("DIN Pro", "DINPro", "PingFang SC").filter { it in names }
    return preferred + (names - preferred.toSet()).sorted()
}

@OptIn(ExperimentalTextApi::class)
private fun bundledDin(): FontFamily? {
    val names = listOf(
        "Assets/Font/DINPro.otf",
        "Assets/Font/DINPro.ttf",
        "Assets/Font/DINPro-Regular.otf",
        "Assets/Font/DINPro-Regular.ttf"
    )
    val loader = Thread.currentThread().contextClassLoader
    val resource = names.firstOrNull { loader?.getResource(it) != null }
    if (resource != null) {
        return FontFamily(Font(resource, FontWeight.Normal))
    }
    val file = names.map { File(it) }.firstOrNull { it.isFile }
    return file?.let { FontFamily(Font(it, FontWeight.Normal)) }
}

@OptIn(ExperimentalTextApi::class)
actual fun splashPingFangFamily(): FontFamily {
    val installed = installedNames()
    val name = listOf("PingFang SC", "PingFang HK", "PingFang TC", "Heiti SC")
        .firstOrNull { it in installed }
        ?: "PingFang SC"
    return FontFamily(name)
}

private fun autoFamilyName(): String {
    val installed = installedNames()
    return listOf("DIN Pro", "DINPro").firstOrNull { want ->
        installed.any { it.equals(want, ignoreCase = true) }
    } ?: "PingFang SC"
}

private fun installedNames(): Set<String> =
    GraphicsEnvironment.getLocalGraphicsEnvironment()
        .availableFontFamilyNames
        .filter { it.isNotBlank() && !it.startsWith(".") }
        .toSet()
