package com.pmcl.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.version.LocalGameStore
import com.pmcl.ui.animation.LocalPortrait
import com.pmcl.ui.animation.TypewriterTitle
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.widget.DangerConfirmDialog

@Composable
fun LocalGamesPage(vm: LauncherViewModel) {
    val games by vm.localGameSpaces.collectAsState()
    val measuring by vm.localGamesMeasuring.collectAsState()
    val running by vm.runningInstances.collectAsState()
    var pending by remember { mutableStateOf<LocalGameStore.GameSpace?>(null) }
    val portrait = LocalPortrait.current
    LaunchedEffect(Unit) { vm.refreshLocalGames() }

    pending?.let { game ->
        DangerConfirmDialog(
            title = I18n.t("download.games_uninstall_title", game.title),
            message = uninstallMessage(game),
            onDismiss = { pending = null },
            onConfirm = {
                pending = null
                vm.uninstallLocalGame(game)
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = if (portrait) 0.dp else 16.dp, vertical = 8.dp)
    ) {
        if (!portrait) {
            TypewriterTitle(I18n.t("download.local_games"))
            Spacer(Modifier.height(8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (measuring && games.isEmpty()) I18n.t("download.games_measuring")
                else I18n.t("download.games_total", formatGameBytes(games.sumOf { it.totalBytes() })),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.refreshLocalGames() }, enabled = !measuring) {
                Icon(Icons.Filled.Refresh, contentDescription = I18n.t("download.games_refresh"))
            }
        }
        Text(
            I18n.t("download.games_shared_kept"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        if (measuring) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        if (games.isEmpty() && !measuring) {
            Text(
                I18n.t("download.games_empty"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 28.dp)
            )
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(games, key = { it.kind.name + ":" + it.id }) { game ->
                    val busy = gameInUse(game, running)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    game.title,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    sizeLine(game),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (busy) {
                                    Text(
                                        I18n.t("download.games_running"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            TextButton(
                                onClick = { pending = game },
                                enabled = !busy && !measuring
                            ) {
                                Text(I18n.t("common.uninstall"))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sizeLine(game: LocalGameStore.GameSpace): String {
    val where = if (game.isExternal) " · " + I18n.t("download.games_external") else ""
    return if (game.isInstanceOnly) {
        I18n.t("download.games_data_size", formatGameBytes(game.dataBytes)) + where
    } else {
        I18n.t("download.games_version_size", formatGameBytes(game.versionBytes)) +
            " · " + I18n.t("download.games_data_size", formatGameBytes(game.dataBytes)) + where
    }
}

private fun uninstallMessage(game: LocalGameStore.GameSpace): String {
    return if (game.isInstanceOnly || game.dataDirCount == 0) {
        I18n.t("download.games_uninstall_version_only", formatGameBytes(game.totalBytes()))
    } else {
        I18n.t(
            "download.games_uninstall_body",
            formatGameBytes(game.versionBytes),
            game.dataDirCount,
            formatGameBytes(game.dataBytes)
        )
    }
}

private fun gameInUse(
    game: LocalGameStore.GameSpace,
    running: List<LauncherViewModel.RunningInstance>
): Boolean {
    if (running.any { it.versionId == game.id || it.id == game.id }) return true
    return game.dataDirNames.any { name -> running.any { it.id == name } }
}

private fun formatGameBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val kb = n / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
