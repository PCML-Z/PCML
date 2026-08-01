package com.lash.pmcl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.download.DownloadManager
import com.lash.pmcl.core.preferences.Preferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    downloadManager: DownloadManager,
    preferences: Preferences,
    appVersion: String,
) {
    var darkTheme by remember { mutableStateOf(preferences.isUseDarkTheme()) }
    var dynamicColor by remember { mutableStateOf(preferences.isDynamicColor()) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        ) {
            item {
                SettingsSectionHeader("启动器")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column {
                        SettingItem(title = "启动器版本", value = appVersion)
                        HorizontalDivider()
                        SettingItem(title = "关于", value = "PMCL Android")
                    }
                }
            }

            item {
                SettingsSectionHeader("主题")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column {
                        SwitchItem(
                            title = "深色主题",
                            checked = darkTheme,
                            onCheckedChange = {
                                darkTheme = it
                                preferences.setUseDarkTheme(it)
                            },
                        )
                        HorizontalDivider()
                        SwitchItem(
                            title = "动态取色 (Material You)",
                            checked = dynamicColor,
                            onCheckedChange = {
                                dynamicColor = it
                                preferences.setDynamicColor(it)
                            },
                        )
                    }
                }
            }

            item {
                SettingsSectionHeader("下载 / 网络")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column {
                        SettingItem(title = "分片下载线程数", value = "${downloadManager.getChunkedDownloadThreads()}")
                        HorizontalDivider()
                        SettingItem(title = "镜像源", value = preferences.getMirrorType())
                    }
                }
            }

            item {
                SettingsSectionHeader("JVM 启动参数")
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column {
                        SettingItem(title = "GC 类型", value = preferences.getGcType())
                        HorizontalDivider()
                        SettingItem(title = "最小内存", value = "${preferences.getMinMemoryMb()} MB")
                        HorizontalDivider()
                        SettingItem(title = "最大内存", value = "${preferences.getMaxMemoryMb()} MB")
                        HorizontalDivider()
                        SwitchItem(
                            title = "Aikar's Flags",
                            checked = preferences.isUseAikarFlags(),
                            onCheckedChange = { preferences.setUseAikarFlags(it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Spacer(Modifier.height(8.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun SettingItem(title: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun SwitchItem(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
