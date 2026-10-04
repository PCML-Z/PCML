package com.pmcl.ui.page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pmcl.core.LauncherConfig
import com.pmcl.core.i18n.I18n
import com.pmcl.core.opanel.OPanelClient
import com.pmcl.core.opanel.OPanelException
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OPanelManagePage(vm: LauncherViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val saved = remember {
        OPanelClient(vm.core.downloads().httpClient(), LauncherConfig.pmclHome()).load()
    }
    var baseUrl by remember { mutableStateOf(saved.baseUrl) }
    var token by remember { mutableStateOf(saved.token) }
    var showToken by remember { mutableStateOf(false) }
    var command by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<OPanelClient.Snapshot?>(null) }
    var message by remember { mutableStateOf("") }
    var messageIsError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pending?>(null) }

    fun client() = OPanelClient(vm.core.downloads().httpClient(), LauncherConfig.pmclHome())

    fun run(block: (OPanelClient) -> Unit, after: String = "") {
        if (busy) return
        busy = true
        message = ""
        messageIsError = false
        scope.launch {
            try {
                val next = withContext(Dispatchers.IO) {
                    val panel = client()
                    block(panel)
                    panel.overview()
                }
                snapshot = next
                if (after.isNotEmpty()) message = after
            } catch (e: Exception) {
                message = explain(e)
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (saved.baseUrl.isNotEmpty() && saved.token.isNotEmpty()) {
            run({})
        }
    }

    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text(I18n.t("servers.opanel.url")) },
                    placeholder = { Text("http://127.0.0.1:3000") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text(I18n.t("servers.opanel.token")) },
                    placeholder = { Text(I18n.t("servers.opanel.token_hint")) },
                    singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            run({ it.save(baseUrl, token) }, I18n.t("servers.opanel.saved"))
                        },
                        enabled = !busy
                    ) { Text(I18n.t("servers.opanel.save")) }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { run({}) }, enabled = !busy) {
                        Text(I18n.t("servers.opanel.refresh"))
                    }
                    if (busy) {
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        if (message.isNotEmpty()) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }

        val current = snapshot
        if (current != null) {
            Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        I18n.t("servers.players_count", current.onlineCount(), current.maxPlayers),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (current.motd.isNotEmpty()) {
                        Text(current.motd, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        listOf(
                            I18n.t("servers.opanel.tps", formatNumber(current.tps)),
                            I18n.t("servers.opanel.mspt", formatNumber(current.mspt)),
                            I18n.t(if (current.whitelist) "servers.opanel.whitelist_on" else "servers.opanel.whitelist_off")
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { run({ it.control("reload") }, I18n.t("servers.opanel.sent")) }, enabled = !busy) {
                            Text(I18n.t("servers.opanel.reload"))
                        }
                        OutlinedButton(onClick = { pending = Pending("restart") }, enabled = !busy) {
                            Text(I18n.t("servers.opanel.restart"))
                        }
                        OutlinedButton(onClick = { pending = Pending("stop") }, enabled = !busy) {
                            Text(I18n.t("servers.opanel.stop"))
                        }
                        OutlinedButton(
                            onClick = {
                                run({ it.whitelist(!current.whitelist) }, I18n.t("servers.opanel.sent"))
                            },
                            enabled = !busy
                        ) {
                            Text(I18n.t(if (current.whitelist) "servers.opanel.whitelist_disable" else "servers.opanel.whitelist_enable"))
                        }
                    }
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text(I18n.t("servers.opanel.command")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            val text = command
                            run({ it.command(text) }, I18n.t("servers.opanel.sent"))
                            command = ""
                        },
                        enabled = !busy && command.isNotBlank()
                    ) { Text(I18n.t("servers.opanel.send")) }
                }
            }

            Text(I18n.t("servers.opanel.players"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            val online = current.players.filter { it.online }
            if (online.isEmpty()) {
                Text(
                    I18n.t("servers.opanel.empty_players"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            online.take(80).forEach { player ->
                Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(player.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOfNotNull(
                                    player.gamemode.ifBlank { null },
                                    if (player.op) I18n.t("servers.opanel.op") else null,
                                    if (player.ping >= 0) "${player.ping} ms" else null
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        TextButton(onClick = { pending = Pending("kick", player.uuid, player.name) }, enabled = !busy) {
                            Text(I18n.t("servers.opanel.kick"))
                        }
                        TextButton(onClick = { pending = Pending("ban", player.uuid, player.name) }, enabled = !busy) {
                            Text(I18n.t("servers.opanel.ban"))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    val action = pending
    if (action != null) {
        val text = when (action.kind) {
            "stop" -> I18n.t("servers.opanel.confirm_stop")
            "restart" -> I18n.t("servers.opanel.confirm_restart")
            "ban" -> I18n.t("servers.opanel.confirm_ban", action.name)
            else -> I18n.t("servers.opanel.confirm_kick", action.name)
        }
        AlertDialog(
            onDismissRequest = { pending = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    when (action.kind) {
                        "stop" -> run({ it.control("stop") }, I18n.t("servers.opanel.sent"))
                        "restart" -> run({ it.control("restart") }, I18n.t("servers.opanel.sent"))
                        "ban" -> run({ it.ban(action.uuid) }, I18n.t("servers.opanel.sent"))
                        else -> run({ it.kick(action.uuid) }, I18n.t("servers.opanel.sent"))
                    }
                }) { Text(I18n.t("common.confirm")) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
}

private data class Pending(val kind: String, val uuid: String = "", val name: String = "")

private fun explain(error: Throwable): String {
    val panel = error as? OPanelException ?: return I18n.t("servers.opanel.unreachable")
    return if (panel.code == "rejected" && panel.detail.isNotBlank()) {
        I18n.t("servers.opanel.rejected", panel.detail)
    } else {
        I18n.t("servers.opanel." + panel.code)
    }
}

private fun formatNumber(value: Double): String {
    if (value < 0) return "—"
    return String.format("%.1f", value)
}
