package com.pmcl.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.cursor.CursorAgentHosts
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation

@Composable
fun CursorAgentSettings() {
    val clipboard = LocalClipboardManager.current
    var message by remember { mutableStateOf("") }
    var messageError by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf(CursorAgentHosts.host?.state()) }

    fun reload() {
        state = CursorAgentHosts.host?.state()
    }

    Card(
        Modifier.fillMaxWidth().glassCardBorder(),
        colors = glassCardColors(),
        elevation = glassCardElevation(),
    ) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(
                I18n.t("settings.section.cursor"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                I18n.t("settings.cursor.hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            val host = CursorAgentHosts.host
            val current = state
            if (host == null || current == null || !current.available) {
                Spacer(Modifier.height(8.dp))
                Text(
                    I18n.t("settings.cursor.unavailable"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                return@Column
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = current.enabled,
                    onCheckedChange = { on ->
                        try {
                            host.setEnabled(on)
                            message = ""
                            messageError = false
                        } catch (e: Exception) {
                            message = e.message ?: ""
                            messageError = true
                        }
                        reload()
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(I18n.t("settings.cursor.enable"), fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (current.running) I18n.t("settings.cursor.listening", current.port.toString())
                else I18n.t("settings.cursor.stopped_port", current.port.toString()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (current.tokenTail.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    I18n.t("settings.cursor.token_tail", current.tokenTail),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (current.error.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(current.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    try {
                        message = host.writeCursorConfig()
                        messageError = false
                    } catch (e: Exception) {
                        message = I18n.t("settings.cursor.write_failed", e.message ?: "")
                        messageError = true
                    }
                    reload()
                },
                enabled = current.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(I18n.t("settings.cursor.write")) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    try {
                        clipboard.setText(AnnotatedString(host.mcpSnippet()))
                        message = I18n.t("settings.cursor.copied")
                        messageError = false
                    } catch (e: Exception) {
                        message = I18n.t("settings.cursor.copy_failed", e.message ?: "")
                        messageError = true
                    }
                },
                enabled = current.running,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(I18n.t("settings.cursor.copy")) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    try {
                        host.rotateToken()
                        message = I18n.t("settings.cursor.rotated")
                        messageError = false
                    } catch (e: Exception) {
                        message = I18n.t("settings.cursor.write_failed", e.message ?: "")
                        messageError = true
                    }
                    reload()
                },
                enabled = current.enabled,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(I18n.t("settings.cursor.rotate")) }
            if (message.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (messageError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
