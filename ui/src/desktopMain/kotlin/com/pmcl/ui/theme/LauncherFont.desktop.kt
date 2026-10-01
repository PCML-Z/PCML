package com.pmcl.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import java.awt.GraphicsEnvironment
import java.io.File

@OptIn(ExperimentalTextApi::class)
actual fun launcherFontFamily(): FontFamily = LauncherFonts.family

@OptIn(ExperimentalTextApi::class)
private object LauncherFonts {
    val family: FontFamily = resolve()

    private fun resolve(): FontFamily {
        bundledDin()?.let { return it }
        val installed = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .availableFontFamilyNames
            .toSet()
        val din = listOf("DIN Pro", "DINPro").firstOrNull { name ->
            installed.any { it.equals(name, ignoreCase = true) }
        }
        return FontFamily(din ?: "PingFang SC")
    }

    /** 把字体文件放在 Assets/Font/ 下，文件名含 DINPro 就会优先用它。 */
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
}
