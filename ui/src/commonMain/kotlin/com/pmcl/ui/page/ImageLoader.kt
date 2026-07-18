package com.pmcl.ui.page

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 从文件路径加载图片为 ImageBitmap（跨平台 expect）。
 * desktopMain 实现使用 ImageIO + toComposeImageBitmap。
 */
internal expect fun loadPathImageBitmap(path: String): ImageBitmap?
