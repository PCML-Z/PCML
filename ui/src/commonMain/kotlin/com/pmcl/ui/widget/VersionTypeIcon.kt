package com.pmcl.ui.widget

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource

/** 正式版、快照、旧版 Beta、旧版 Alpha 使用网上的方块图标。 */
@Composable
fun VersionTypeIcon(type: String, modifier: Modifier = Modifier) {
    val name = when (type) {
        "release" -> "version/release.png"
        "snapshot" -> "version/snapshot.png"
        "old_alpha" -> "version/alpha.png"
        "old_beta" -> "version/beta.png"
        else -> return
    }
    val bitmap = remember(name) { useResource(name) { loadImageBitmap(it) } }
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.None
    )
}
