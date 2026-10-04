package com.pmcl.ui.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.pmcl.core.launch.JavaRuntimeFinder

/** 从本机已扫描到的 Java 里选一个。空路径表示继续自动选择。 */
@Composable
fun InstalledJavaPicker(
    selectedPath: String,
    installations: List<JavaRuntimeFinder.JavaInstallation>,
    scanning: Boolean,
    autoLabel: String,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = installations.firstOrNull { it.path == selectedPath }
    Column(modifier) {
        Text(
            I18n.t("version_settings.java_installed"),
            style = MaterialTheme.typography.labelLarge
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(
                        selected?.let { javaInstallationLabel(it) }
                            ?: if (selectedPath.isBlank()) autoLabel else selectedPath,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (selected != null) {
                        Text(
                            selected.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = 380.dp)
            ) {
                DropdownMenuItem(
                    text = { Text(autoLabel) },
                    onClick = {
                        onSelect("")
                        expanded = false
                    }
                )
                if (installations.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (scanning) I18n.t("settings.java_scanning")
                                else I18n.t("settings.java_none_found")
                            )
                        },
                        onClick = {},
                        enabled = false
                    )
                } else {
                    installations.forEach { installation ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(javaInstallationLabel(installation), fontWeight = FontWeight.Medium)
                                    Text(
                                        installation.path,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            onClick = {
                                onSelect(installation.path)
                                expanded = false
                            }
                        )
                    }
                }
            }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onRefresh, enabled = !scanning) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = I18n.t("common.refresh"))
                }
            }
        }
    }
}

fun javaInstallationLabel(installation: JavaRuntimeFinder.JavaInstallation): String =
    "Java ${installation.majorVersion} · ${installation.architecture} · ${installation.source}"
