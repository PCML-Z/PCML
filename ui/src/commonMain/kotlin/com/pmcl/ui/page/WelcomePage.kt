package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.migration.MigrationManager
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置里的数据导入：从其他启动器拷 versions / libraries / assets。
 */
@Composable
fun DataImportSection(vm: LauncherViewModel) {
    val sources by vm.migrationSources.collectAsState()
    val scanning by vm.migrationScanning.collectAsState()
    val migrating by vm.migrating.collectAsState()
    val progress by vm.migrationProgress.collectAsState()
    val outcome by vm.migrationOutcome.collectAsState()
    val activeKey by vm.activeMigrationKey.collectAsState()

    LaunchedEffect(Unit) {
        if (sources.isEmpty() && !scanning) vm.detectMigrationSources()
    }

    Card(
        modifier = Modifier.fillMaxWidth().glassCardBorder(),
        shape = RoundedCornerShape(8.dp),
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Column(Modifier.padding(16.dp)) {
        Text(
            I18n.t("migration.heading"),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(12.dp))

        when {
            scanning && sources.isEmpty() -> {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            sources.isEmpty() -> {
                Text(
                    I18n.t("migration.no_source"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { src ->
                        val key = src.getGameRoot()?.toString() ?: src.getName()
                        MigrationCard(
                            source = src,
                            migrating = migrating,
                            isActive = activeKey == key,
                            progress = progress,
                            imported = outcome[key],
                            onImport = { vm.migrateFrom(src) }
                        )
                    }
                }
            }
        }

        }
    }
}

@Composable
private fun MigrationCard(
    source: MigrationManager.Source,
    migrating: Boolean,
    isActive: Boolean,
    progress: String,
    imported: Boolean?,
    onImport: () -> Unit
) {
    val sizeBytes by produceState(source.getEstimatedSize(), source) {
        value = source.getEstimatedSize()
        if (value <= 0L) {
            runCatching {
                value = withContext(Dispatchers.IO) { source.getEstimatedSizeFuture().get() }
            }
        }
    }
    val path = source.getGameRoot()?.toString().orEmpty()

    Card(
        modifier = Modifier.fillMaxWidth().glassCardBorder(),
        shape = RoundedCornerShape(8.dp),
        colors = glassCardColors(),
        elevation = glassCardElevation()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        source.getName(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (path.isNotEmpty()) {
                        Text(
                            path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (sizeBytes > 0L) {
                        Text(
                            MigrationManager.formatSize(sizeBytes),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                when {
                    imported == true -> {
                        Text(
                            I18n.t("migration.done"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    imported == false && !isActive -> {
                        TextButton(
                            onClick = onImport,
                            enabled = !migrating,
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(I18n.t("common.retry"))
                        }
                    }
                    isActive -> {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    else -> {
                        OutlinedButton(
                            onClick = onImport,
                            enabled = !migrating,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(I18n.t("common.import"))
                        }
                    }
                }
            }
            if (isActive && progress.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    progress,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
