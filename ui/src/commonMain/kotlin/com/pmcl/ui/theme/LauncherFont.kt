package com.pmcl.ui.theme

import androidx.compose.ui.text.font.FontFamily

/** requested 为空时用 DIN Pro，系统没有则用 PingFang SC。 */
expect fun launcherFontFamily(requested: String): FontFamily

/** 本机可选的界面字体，不含点开头的系统内部字体。 */
expect fun installedLauncherFonts(): List<String>

/** 启动图文字用苹方，不跟随界面字体设置。 */
expect fun splashPingFangFamily(): FontFamily

/** 新闻页像素字体。Ark Pixel 12px，覆盖汉字、假名和拉丁字母，授权见 Assets/Font/OFL.txt。 */
expect fun newsPixelFamily(): FontFamily
