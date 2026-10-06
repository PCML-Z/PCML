package com.pmcl.ui.page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pmcl.core.gamecontent.OfflineSkinLibrary
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.TypewriterTitle
import com.pmcl.ui.navigation.PmclIcons
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.theme.glassSurfaceVariantColor
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.applyOfflineSkin
import com.pmcl.ui.viewmodel.deleteOfflineSkin
import com.pmcl.ui.viewmodel.deployOfflineSkins
import com.pmcl.ui.viewmodel.importOfflineSkin
import com.pmcl.ui.viewmodel.offlineSkinFile
import com.pmcl.ui.viewmodel.openOfflineSkinsDir
import com.pmcl.ui.viewmodel.refreshOfflineSkins
import com.pmcl.ui.viewmodel.updateOfflineSkin
import com.pmcl.ui.widget.PmclLazyColumn
import java.awt.FileDialog
import java.awt.Frame

@Composable
fun OfflineSkinsPage(vm: LauncherViewModel) {
    val rows by vm.offlineSkins.collectAsState()
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<OfflineSkinLibrary.Skin?>(null) }

    LaunchedEffect(Unit) { vm.refreshOfflineSkins() }

    val filtered = remember(rows, query) {
        if (query.isBlank()) rows
        else rows.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.player.contains(query, ignoreCase = true)
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypewriterTitle(
                I18n.t("content.offline_skin.title"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                I18n.t("content.offline_skin.count", rows.size.toString()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            I18n.t("content.offline_skin.hint"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(I18n.t("content.offline_skin.search")) }
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.openOfflineSkinsDir() }) {
                Text(I18n.t("common.open_dir"))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { vm.deployOfflineSkins() }) {
                Text(I18n.t("content.offline_skin.deploy"))
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                val fd = FileDialog(null as Frame?, I18n.t("common.import"), FileDialog.LOAD)
                fd.filenameFilter = java.io.FilenameFilter { _, name: String? ->
                    name?.lowercase(java.util.Locale.ROOT)?.endsWith(".png") == true
                }
                fd.isVisible = true
                val file = fd.file ?: return@Button
                val dir = fd.directory ?: return@Button
                vm.importOfflineSkin(java.nio.file.Path.of(dir, file))
            }) {
                Text(I18n.t("common.import"))
            }
        }
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty()) {
            Surface(
                color = glassSurfaceVariantColor(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (rows.isEmpty()) I18n.t("content.offline_skin.empty")
                        else I18n.t("content.offline_skin.no_match"),
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            PmclLazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filtered, key = { it.id }) { skin ->
                    OfflineSkinCard(
                        skin = skin,
                        previewPath = vm.offlineSkinFile(skin),
                        onSave = { name, player, model -> vm.updateOfflineSkin(skin.id, name, player, model) },
                        onApply = { name, _, model -> vm.applyOfflineSkin(skin.id, name, model) },
                        onDelete = { pendingDelete = skin }
                    )
                }
            }
        }
    }

    val deleting = pendingDelete
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(I18n.t("content.offline_skin.delete_title")) },
            text = { Text(I18n.t("content.offline_skin.delete_body", deleting.name)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteOfflineSkin(deleting.id)
                    pendingDelete = null
                }) { Text(I18n.t("common.delete")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
}

@Composable
private fun OfflineSkinCard(
    skin: OfflineSkinLibrary.Skin,
    previewPath: String,
    onSave: (String, String, String) -> Unit,
    onApply: (String, String, String) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(skin.id, skin.name) { mutableStateOf(skin.name) }
    var player by remember(skin.id, skin.player) { mutableStateOf(skin.player) }
    var model by remember(skin.id, skin.model) { mutableStateOf(skin.model) }

    Card(
        modifier = Modifier.fillMaxWidth().glassCardBorder(),
        shape = RoundedCornerShape(12.dp),
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SkinFace(previewPath)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(I18n.t("content.offline_skin.name")) }
                )
                OutlinedTextField(
                    value = player,
                    onValueChange = { player = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(I18n.t("content.offline_skin.player")) },
                    placeholder = { Text(I18n.t("content.offline_skin.unbound")) }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (model == "slim") {
                        OutlinedButton(onClick = { model = "classic" }) {
                            Text(I18n.t("content.offline_skin.model_classic"))
                        }
                    } else {
                        Button(onClick = { model = "classic" }) {
                            Text(I18n.t("content.offline_skin.model_classic"))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (model == "slim") {
                        Button(onClick = { model = "slim" }) {
                            Text(I18n.t("content.offline_skin.model_slim"))
                        }
                    } else {
                        OutlinedButton(onClick = { model = "slim" }) {
                            Text(I18n.t("content.offline_skin.model_slim"))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onSave(name, player, model) }) {
                        Text(I18n.t("common.save"))
                    }
                    TextButton(onClick = { onApply(name, player, model) }) {
                        Text(I18n.t("content.offline_skin.apply"))
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, I18n.t("common.delete"))
            }
        }
    }
}

@Composable
private fun SkinFace(path: String) {
    val bitmap = remember(path) { loadSkinBitmap(path) }
    if (bitmap == null) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            Icon(PmclIcons.Image, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.outline)
        }
    } else {
        Canvas(Modifier.size(64.dp)) {
            val unit = bitmap.width / 64f
            val face = (8f * unit).toInt().coerceAtLeast(1)
            val origin = (8f * unit).toInt()
            val dst = IntSize(size.width.toInt(), size.height.toInt())
            drawImage(
                bitmap,
                srcOffset = IntOffset(origin, origin),
                srcSize = IntSize(face, face),
                dstSize = dst,
                filterQuality = FilterQuality.None
            )
            val hatX = (40f * unit).toInt()
            if (hatX + face <= bitmap.width) {
                drawImage(
                    bitmap,
                    srcOffset = IntOffset(hatX, origin),
                    srcSize = IntSize(face, face),
                    dstSize = dst,
                    filterQuality = FilterQuality.None
                )
            }
        }
    }
}

private fun loadSkinBitmap(path: String): ImageBitmap? {
    if (path.isBlank()) return null
    return runCatching {
        java.io.File(path).inputStream().use { loadImageBitmap(it) }
    }.getOrNull()
}
