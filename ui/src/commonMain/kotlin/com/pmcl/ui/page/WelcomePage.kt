package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.i18n.I18n
import com.pmcl.core.migration.MigrationManager
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 首次启动欢迎页：展示欢迎信息 + 从 HMCL / Launcher X 迁移游戏数据。
 * 完成后调用 [LauncherViewModel.completeFirstLaunch] 进入主界面。
 */
@Composable
fun WelcomePage(vm: LauncherViewModel) {
    val sources by vm.migrationSources.collectAsState()
    val migrating by vm.migrating.collectAsState()
    val progress by vm.migrationProgress.collectAsState()

    // 进入页面时自动扫描一次
    LaunchedEffect(Unit) {
        if (sources.isEmpty()) vm.detectMigrationSources()
    }

    Box(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
    ) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // === 标题 ===
            Text(I18n.t("launch.welcome"),
                 style = MaterialTheme.typography.headlineMedium,
                 fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(I18n.t("launch.subtitle"),
                 style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.outline)

            Spacer(Modifier.height(32.dp))

            // === 迁移控件 ===
            // 仅显示 HMCL 与 Launcher X 来源
            val targetSources = sources.filter { s ->
                (s.getName() ?: "").contains("HMCL") || (s.getName() ?: "").contains("Launcher X")
            }

            if (targetSources.isEmpty()) {
                Text(I18n.t("migration.no_source"),
                     style = MaterialTheme.typography.bodyMedium,
                     color = MaterialTheme.colorScheme.outline)
            } else {
                targetSources.forEach { src ->
                    MigrationCard(
                        source = src,
                        migrating = migrating,
                        progress = progress,
                        onMigrate = { vm.migrateFrom(src) }
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }

            Spacer(Modifier.height(32.dp))

            // === 进入按钮 ===
            Button(
                onClick = { vm.completeFirstLaunch() },
                enabled = !migrating,
                modifier = Modifier.fillMaxWidth(0.5f).height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(I18n.t("launch.enter"),
                     style = MaterialTheme.typography.titleMedium, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun MigrationCard(
    source: MigrationManager.Source,
    migrating: Boolean,
    progress: String,
    onMigrate: () -> Unit
) {
    var migrated by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().glassCardBorder(12.dp), shape = RoundedCornerShape(12.dp), colors = glassCardColors(), elevation = glassCardElevation()) {
        Column(Modifier.padding(16.dp)) {
            Text(I18n.t("migration.from", source.getName()),
                 style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("${source.getGameRoot()}",
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(2.dp))
            Text(I18n.t("migration.size", MigrationManager.formatSize(source.getEstimatedSize())),
                 style = MaterialTheme.typography.labelSmall,
                 color = MaterialTheme.colorScheme.primary,
                 fontWeight = FontWeight.Medium)

            Spacer(Modifier.height(12.dp))

            if (migrated) {
                Text("✓ 已完成迁移",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.primary,
                     fontWeight = FontWeight.Medium)
            } else if (migrating) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(progress.ifEmpty { I18n.t("migration.processing") },
                         style = MaterialTheme.typography.bodySmall,
                         modifier = Modifier.weight(1f))
                }
            } else {
                Button(onClick = { onMigrate(); migrated = true }) {
                    Icon(Icons.Filled.Refresh, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(I18n.t("migration.title"))
                }
            }
        }
    }
}
