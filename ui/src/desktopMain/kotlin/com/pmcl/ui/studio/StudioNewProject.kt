package com.pmcl.ui.studio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.dev.DevProjects
import com.pmcl.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.nio.file.Paths

/** 新建 ECXP 应用或 PMCL 插件。项目写到用户选的目录，不改启动器偏好。 */
@Composable
internal fun NewProjectDialog(
    initial: DevProjects.Kind,
    parentDir: String,
    onDismiss: () -> Unit,
    onOpenInStudio: (Path) -> Unit,
) {
    val p = LocalStudio.current
    var kind by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var idTouched by remember { mutableStateOf(false) }
    var parent by remember { mutableStateOf(parentDir) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val plugin = kind == DevProjects.Kind.PLUGIN_IDEA
    LaunchedEffect(name, kind) {
        if (!idTouched) id = DevProjects.slug(name, plugin)
    }
    val ready = !busy && DevProjects.validName(name) && DevProjects.validId(id, plugin) && parent.isNotBlank()
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(200)) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f * appear.value))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { if (!busy) onDismiss() }),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(560.dp)
                .graphicsLayer {
                    alpha = appear.value
                    val sc = 0.94f + 0.06f * appear.value
                    scaleX = sc
                    scaleY = sc
                }
                .background(p.panel)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        ) {
            FlatText(
                I18n.t(if (plugin) "studio.new_plugin_title" else "studio.new_ecxp_title"),
                color = Color.White,
                size = 15,
                weight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(if (plugin) Tiles.Navy else Tiles.Teal).padding(horizontal = 18.dp, vertical = 14.dp)
            )
            Row(Modifier.fillMaxWidth().height(48.dp)) {
                KindBlock(DevProjects.Kind.ECXP_STUDIO, kind, Icons.Filled.Code, Tiles.Teal, "studio.new_in_studio") { kind = it }
                KindBlock(DevProjects.Kind.ECXP_VSCODE, kind, Icons.Filled.Terminal, Tiles.Blue, "studio.new_in_vscode") { kind = it }
                KindBlock(DevProjects.Kind.PLUGIN_IDEA, kind, Icons.Filled.AccountTree, Tiles.Navy, "studio.new_in_idea") { kind = it }
            }
            FormRow(I18n.t("studio.new_name"), 0) {
                InputBox(name, { name = it }, I18n.t("studio.new_name"), Modifier.weight(1f))
            }
            FormRow(I18n.t("studio.new_id"), 1) {
                InputBox(id, { id = it; idTouched = true }, I18n.t("studio.new_id"), Modifier.weight(1f), mono = true)
            }
            FormRow(I18n.t("studio.new_folder"), 2) {
                InputBox(parent, { parent = it }, I18n.t("studio.new_folder"), Modifier.weight(1f), mono = true)
                Block("", onClick = {
                    pickDirectory(I18n.t("studio.new_pick"))?.let { parent = it.toString() }
                }, icon = Icons.Filled.FolderOpen, height = 30.dp)
            }
            val dest = if (id.isBlank()) parent else parent.trimEnd('/', '\\') + "/" + id
            FlatText(dest, color = p.muted, size = 11, lines = 2, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            if (error.isNotEmpty()) {
                FlatText(error, color = Tiles.Red, lines = 3, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
            }
            Row(Modifier.fillMaxWidth().height(42.dp)) {
                Block(I18n.t("studio.cancel"), onClick = onDismiss, enabled = !busy, fill = p.strip, modifier = Modifier.weight(1f), height = 42.dp)
                Block(I18n.t("studio.new_create"), onClick = {
                    if (!ready) {
                        error = I18n.t("studio.new_invalid")
                        return@Block
                    }
                    error = ""
                    busy = true
                    val chosen = kind
                    scope.launch {
                        val made = try {
                            withContext(Dispatchers.IO) {
                                DevProjects.create(Paths.get(parent), chosen, name.trim(), id.trim())
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = when (e.message) {
                                "exists" -> I18n.t("studio.new_exists")
                                "invalid" -> I18n.t("studio.new_invalid")
                                else -> I18n.t("studio.new_failed")
                            }
                            busy = false
                            null
                        }
                        if (made == null) return@launch
                        if (chosen == DevProjects.Kind.ECXP_STUDIO) {
                            onOpenInStudio(made)
                            return@launch
                        }
                        val opened = withContext(Dispatchers.IO) { DevProjects.openExternal(chosen, made) }
                        if (opened) onDismiss()
                        else {
                            error = I18n.t("studio.new_no_ide", made.toString())
                            busy = false
                        }
                    }
                }, enabled = ready, fill = if (plugin) Tiles.Navy else Tiles.Teal, modifier = Modifier.weight(1f), height = 42.dp)
            }
        }
    }
}

@Composable
private fun RowScope.KindBlock(
    value: DevProjects.Kind,
    current: DevProjects.Kind,
    icon: ImageVector,
    color: Color,
    labelKey: String,
    onPick: (DevProjects.Kind) -> Unit,
) {
    val selected = value == current
    Block(
        I18n.t(labelKey),
        onClick = { onPick(value) },
        icon = icon,
        fill = if (selected) color else null,
        selected = selected,
        modifier = Modifier.weight(1f),
        height = 48.dp
    )
}
