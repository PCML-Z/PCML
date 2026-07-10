package com.pmcl.hmcl

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingPanel
import com.pmcl.plugin.ComposableContent
import kotlinx.coroutines.delay

/**
 * Compose page that embeds HMCL's JavaFX UI via SwingPanel + JFXPanel.
 *
 * Shows a control bar (Start/Stop buttons + status) and the embedded JavaFX
 * panel below it.
 */
class HmclPageContent : ComposableContent {
    @Composable
    override fun invoke() {
        HmclPage()
    }
}

@Composable
private fun HmclPage() {
    var started by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(HmclEmbedder.getStatus()) }
    var showDisclaimer by remember { mutableStateOf(false) }
    var disclaimerAccepted by remember { mutableStateOf(false) }

    // Poll status from the embedder (updated on JavaFX thread)
    LaunchedEffect(started) {
        while (started) {
            status = HmclEmbedder.getStatus()
            delay(200)
        }
    }

    // Disclaimer dialog — shown before first HMCL launch
    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            icon = {
                Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text("Disclaimer / 免责声明", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "HMCL (Hello Minecraft! Launcher) is a third-party open-source software " +
                        "developed by huanghongxun and contributors. All copyrights belong to their respective owners.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "HMCL（Hello Minecraft! Launcher）是由 huanghongxun 及贡献者开发的第三方开源软件，" +
                        "其版权归原作者所有。PMCL 仅作为嵌入式宿主运行 HMCL，不对 HMCL 的功能、行为或其 " +
                        "下载/启动的任何内容承担责任。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Divider()
                    Text(
                        "• PMCL is not affiliated with HMCL or Mojang Studios.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• Use at your own risk. PMCL is not responsible for any damage to your " +
                        "game files, accounts, or system.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "• Minecraft is a trademark of Mojang Studios. Ensure you own a valid " +
                        "copy before playing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Divider()
                    Text(
                        "Click \"Agree\" to proceed and embed HMCL. Click \"Disagree\" to cancel.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        disclaimerAccepted = true
                        showDisclaimer = false
                        started = true
                        status = "Starting..."
                    }
                ) {
                    Text("Agree / 同意")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDisclaimer = false }
                ) {
                    Text("Disagree / 不同意")
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Control bar
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!started) {
                    Button(
                        onClick = {
                            if (disclaimerAccepted) {
                                started = true
                                status = "Starting..."
                            } else {
                                showDisclaimer = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Start HMCL")
                    }
                } else {
                    Button(
                        onClick = {
                            HmclEmbedder.shutdown()
                            started = false
                            status = "Stopped"
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Stop")
                    }
                    Button(
                        onClick = {
                            HmclEmbedder.shutdown()
                            started = false
                            status = "Restarting..."
                        }
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Restart")
                    }
                }

                Spacer(Modifier.weight(1f))

                // Status indicator
                val isError = status.startsWith("Error") || status.startsWith("Failed")
                val isReady = status.contains("successfully")
                AssistChip(
                    onClick = {},
                    label = { Text(status, style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = when {
                            isError -> MaterialTheme.colorScheme.error
                            isReady -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }

        Divider()

        // Embedded JavaFX panel or placeholder
        if (started) {
            SwingPanel(
                factory = { HmclEmbedder.getOrCreatePanel() },
                modifier = Modifier.fillMaxSize(),
                background = Color.White
            )
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "HMCL JavaFX Embedding",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Click \"Start HMCL\" to embed HMCL 3.15.2's JavaFX UI\n" +
                        "directly into this Compose Desktop window.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Tech: JFXPanel (JavaFX-Swing bridge) → SwingPanel (Compose interop)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}
