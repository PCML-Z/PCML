package com.pmcl.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Update
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.checkModUpdates
import com.pmcl.ui.viewmodel.clearModUpdates
import com.pmcl.ui.viewmodel.refreshInstalledMods
import com.pmcl.ui.viewmodel.selectInstance
import com.pmcl.ui.viewmodel.updateAllMods
import com.pmcl.ui.viewmodel.updateMod

/**
 * 模组更新检查：把当前目标选为活动版本/实例后复用 VM 的检测流程
 * （Modrinth / CurseForge 查新、版本号比对、下载替换带备份回滚）。
 */
@Composable
internal fun ModUpdatesEditor(vm: LauncherViewModel, target: StudioTarget) {
    val p = LocalStudio.current
    val updates by vm.modUpdates.collectAsState()
    val checking by vm.checkingUpdates.collectAsState()
    val progress by vm.updateCheckProgress.collectAsState()
    val updating by vm.updatingMod.collectAsState()
    val gameVersion by vm.updateGameVersion.collectAsState()

    LaunchedEffect(target) {
        when (target.kind) {
            TargetKind.VERSION -> vm.selectVersion(target.id)
            TargetKind.INSTANCE -> vm.selectInstance(target.id)
        }
        vm.refreshInstalledMods()
        vm.clearModUpdates()
        vm.checkModUpdates()
    }

    val available = updates.count { it.hasUpdate() }
    Column(Modifier.fillMaxSize().background(p.editor)) {
        ModuleHero(
            I18n.t("studio.mod_updates"),
            I18n.t("studio.updates_sub", target.name, if (gameVersion.isBlank()) "—" else gameVersion),
            Tiles.Teal,
            Icons.Filled.SystemUpdateAlt
        ) {
            HeroButton(I18n.t("studio.updates_check"), Icons.Filled.Refresh, Tiles.Teal, { vm.checkModUpdates() }, enabled = !checking && !updating)
            HeroButton(
                I18n.t("studio.updates_update_all", available.toString()),
                Icons.Filled.Update,
                Tiles.Forest,
                { vm.updateAllMods() },
                enabled = available > 0 && !checking && !updating
            )
        }
        TileRow {
            ColorTile(I18n.t("studio.updates_total"), updates.size.toString(), Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.CheckCircle, index = 0)
            ColorTile(I18n.t("studio.updates_available"), available.toString(), if (available > 0) Tiles.Orange else Tiles.Slate, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Update, index = 1)
            ColorTile(I18n.t("studio.updates_uptodate"), (updates.size - available).toString(), Tiles.Forest, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.CheckCircle, index = 2)
        }
        if (checking || updating) {
            ProgressLine(
                if (progress.second > 0) progress.first.toFloat() / progress.second else null,
                Tiles.Teal
            )
        }
        if (!checking && updates.isEmpty()) {
            EmptyState(I18n.t("studio.updates_empty"), Icons.Filled.SystemUpdateAlt, color = Tiles.Teal)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(
                updates.sortedByDescending { it.hasUpdate() },
                key = { _, u -> u.installed.jarFile.orEmpty().ifBlank { u.displayName() } }
            ) { i, info ->
                ItemRow(
                    info.displayName(),
                    buildString {
                        append(info.installed.version.orEmpty().ifBlank { "?" })
                        append(" · ")
                        append(info.reason.orEmpty())
                        info.source?.let { append(" · ").append(it) }
                    },
                    if (info.hasUpdate()) Tiles.Orange else Tiles.Slate,
                    i,
                    badge = info.displayName().take(1),
                    height = 44.dp
                ) {
                    if (info.hasUpdate()) {
                        RowButton(Icons.Filled.Update, { vm.updateMod(info) }, Tiles.Orange, enabled = !updating && !checking, label = I18n.t("studio.updates_update_one"))
                    }
                }
            }
        }
    }
}
