package com.pmcl.ui.page

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 从文件路径加载图片为 ImageBitmap（跨平台 expect）。
 * desktopMain 实现使用 ImageIO + toComposeImageBitmap。
 */
internal expect fun loadPathImageBitmap(path: String): ImageBitmap?

/**
 * 从文件路径加载缩略图（流式解码，不将完整文件读入内存）。
 * @param path 图片文件路径
 * @param maxDimension 目标最大边长（px）
 */
internal expect fun decodeThumbnailFromPath(path: String, maxDimension: Int): ImageBitmap?

/** 解码视频中靠近开头的一帧作为录屏缩略图。 */
internal expect fun decodeVideoThumbnailFromPath(path: String, maxWidth: Int): ImageBitmap?
