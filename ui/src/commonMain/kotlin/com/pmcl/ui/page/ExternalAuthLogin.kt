package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.addExternalAuthServer
import com.pmcl.ui.viewmodel.listExternalAuthServers
import com.pmcl.ui.viewmodel.removeExternalAuthServer
import com.pmcl.ui.viewmodel.startYggdrasilLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 选择已保存的外置验证服务器，或添加一个新地址，然后用该服务器的账号登录。
 */
@Composable
fun ExternalAuthServerForm(vm: LauncherViewModel) {
    val scope = rememberCoroutineScope()
    val loggingIn by vm.loggingIn.collectAsState()
    var servers by remember { mutableStateOf(emptyList<com.pmcl.core.auth.ExternalAuthServerStore.ExternalAuthServer>()) }
    var selectedUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var addUrl by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    var ownLogin by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) { vm.listExternalAuthServers() }
        servers = loaded
        if (selectedUrl.isBlank()) selectedUrl = loaded.firstOrNull()?.url ?: ""
    }
    LaunchedEffect(loggingIn) {
        if (!loggingIn) ownLogin = false
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                I18n.t("accounts.auth_server"),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showAdd = true; addError = null }) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(I18n.t("accounts.auth_server_add"))
            }
        }
        if (servers.isEmpty()) {
            Text(
                I18n.t("accounts.auth_server_empty"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(Modifier.heightIn(max = 160.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
                servers.forEach { server ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = selectedUrl == server.url,
                            onClick = { selectedUrl = server.url }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(server.name ?: server.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                server.url ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { vm.removeExternalAuthServer(server.url) }
                                servers = withContext(Dispatchers.IO) { vm.listExternalAuthServers() }
                                if (selectedUrl == server.url) selectedUrl = servers.firstOrNull()?.url ?: ""
                            }
                        }) {
                            Icon(Icons.Filled.Delete, I18n.t("accounts.auth_server_remove"), Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text(I18n.t("accounts.yggdrasil_username")) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(I18n.t("accounts.yggdrasil_password")) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { passwordVisible = !passwordVisible }) {
                    Text(if (passwordVisible) I18n.t("accounts.hide_password") else I18n.t("accounts.show_password"))
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        if (ownLogin && loggingIn) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(I18n.t("accounts.logging_in"), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Button(
                onClick = {
                    ownLogin = true
                    vm.startYggdrasilLogin(selectedUrl, username.trim(), password)
                },
                enabled = !loggingIn && selectedUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
            ) {
                Text(I18n.t("accounts.auth_server_login"))
            }
        }
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { if (!probing) showAdd = false },
            title = { Text(I18n.t("accounts.auth_server_add")) },
            text = {
                Column {
                    Text(
                        I18n.t("accounts.auth_server_add_hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = addUrl,
                        onValueChange = { addUrl = it; addError = null },
                        label = { Text(I18n.t("accounts.auth_server_url")) },
                        placeholder = { Text("https://example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (addError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(addError ?: "", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !probing && addUrl.isNotBlank(),
                    onClick = {
                        scope.launch {
                            probing = true
                            addError = null
                            try {
                                val added = withContext(Dispatchers.IO) { vm.addExternalAuthServer(addUrl.trim()) }
                                servers = withContext(Dispatchers.IO) { vm.listExternalAuthServers() }
                                selectedUrl = added.url ?: selectedUrl
                                addUrl = ""
                                showAdd = false
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                addError = e.message ?: I18n.t("common.unknown")
                            } finally {
                                probing = false
                            }
                        }
                    }
                ) {
                    Text(if (probing) I18n.t("accounts.auth_server_probing") else I18n.t("accounts.auth_server_add"))
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!probing) showAdd = false }, enabled = !probing) {
                    Text(I18n.t("common.cancel"))
                }
            }
        )
    }
}
