package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.plugin.PluginManager
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Paths

/**
 * Plugin management page: visually install, enable, disable, and uninstall plugins.
 * Shows all loaded plugins with their state, commands, and details.
 */
@Composable
fun PluginPage(vm: LauncherViewModel) {
    val scope = rememberCoroutineScope()
    val pm = vm.core.plugins()

    // Observable plugin list — refresh via revision polling
    var plugins by remember { mutableStateOf<List<PluginManager.PluginEntry>>(emptyList()) }
    var revision by remember { mutableStateOf(-1L) }
    var selectedPlugin by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var showInstallDialog by remember { mutableStateOf(false) }

    // Load plugins on startup
    LaunchedEffect(Unit) {
        try {
            pm.discoverAndLoadAll()
        } catch (e: Throwable) {
            // Non-fatal
        }
        plugins = pm.getLoadedPlugins()
        revision = pm.getRevision()
    }

    // Poll for changes
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val rev = pm.getRevision()
            if (rev != revision) {
                revision = rev
                plugins = pm.getLoadedPlugins()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Plugin Manager", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.weight(1f))
            Button(onClick = { showInstallDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Install")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        pm.discoverAndLoadAll()
                    }
                    plugins = pm.getLoadedPlugins()
                    statusMessage = "Scan complete: ${plugins.size} plugin(s) loaded"
                }
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scan")
            }
        }

        statusMessage?.let {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (plugins.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Extension,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No plugins installed", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Click 'Install' to add a plugin from a JAR file or URL,\n" +
                        "or 'Scan' to discover plugins in ~/.pmcl/plugins/",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Plugin list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(plugins, key = { it.info.id }) { entry ->
                    PluginCard(
                        entry = entry,
                        isSelected = selectedPlugin == entry.info.id,
                        onClick = { selectedPlugin = entry.info.id },
                        onEnable = {
                            scope.launch {
                                withContext(Dispatchers.IO) { pm.enablePlugin(entry.info.id) }
                                plugins = pm.getLoadedPlugins()
                                statusMessage = "Enabled: ${entry.info.name}"
                            }
                        },
                        onDisable = {
                            scope.launch {
                                withContext(Dispatchers.IO) { pm.disablePlugin(entry.info.id) }
                                plugins = pm.getLoadedPlugins()
                                statusMessage = "Disabled: ${entry.info.name}"
                            }
                        },
                        onReload = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { pm.reloadPlugin(entry.info.id) }
                                    plugins = pm.getLoadedPlugins()
                                    statusMessage = "Reloaded: ${entry.info.name}"
                                } catch (e: Throwable) {
                                    statusMessage = "Reload failed: ${e.message}"
                                }
                            }
                        },
                        onUninstall = {
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { pm.uninstallPlugin(entry.info.id) }
                                    plugins = pm.getLoadedPlugins()
                                    selectedPlugin = null
                                    statusMessage = "Uninstalled: ${entry.info.name}"
                                } catch (e: Throwable) {
                                    statusMessage = "Uninstall failed: ${e.message}"
                                }
                            }
                        },
                        pm = pm
                    )
                }
            }
        }
    }

    // Install dialog
    if (showInstallDialog) {
        InstallPluginDialog(
            onDismiss = { showInstallDialog = false },
            onInstall = { source ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (source.startsWith("http://") || source.startsWith("https://")) {
                                pm.installFromUrl(source)
                            } else {
                                pm.installFromPath(Paths.get(source))
                            }
                        }
                        plugins = pm.getLoadedPlugins()
                        statusMessage = "Plugin installed successfully"
                        showInstallDialog = false
                    } catch (e: Throwable) {
                        statusMessage = "Install failed: ${e.message}"
                        showInstallDialog = false
                    }
                }
            }
        )
    }
}

@Composable
private fun PluginCard(
    entry: PluginManager.PluginEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit,
    onReload: () -> Unit,
    onUninstall: () -> Unit,
    pm: PluginManager
) {
    val info = entry.info
    val isEnabled = entry.state == PluginManager.PluginState.ENABLED
    val stateColor = when (entry.state) {
        PluginManager.PluginState.ENABLED -> MaterialTheme.colorScheme.primary
        PluginManager.PluginState.DISABLED -> MaterialTheme.colorScheme.outline
        PluginManager.PluginState.FAILED -> MaterialTheme.colorScheme.error
        PluginManager.PluginState.LOADED -> MaterialTheme.colorScheme.tertiary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.Extension,
                    contentDescription = null,
                    tint = stateColor,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        info.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "v${info.version} by ${info.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = stateColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        entry.state.name,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = stateColor
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                info.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Show details if selected
            if (isSelected) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                Text("ID: ${info.id}", style = MaterialTheme.typography.bodySmall)
                Text("API: ${info.apiVersion}", style = MaterialTheme.typography.bodySmall)
                if (info.dependencies.isNotEmpty()) {
                    Text("Dependencies: ${info.dependencies.joinToString(", ")}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (info.license.isNotEmpty()) {
                    Text("License: ${info.license}", style = MaterialTheme.typography.bodySmall)
                }

                // Show registered commands
                val cmds = pm.getCustomCommands().filter { it.pluginId == info.id }
                if (cmds.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Commands:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    cmds.forEach { c ->
                        Text(
                            "  plugin:${c.pluginId}:${c.name} — ${c.description}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Show registered pages
                val pages = pm.getCustomPages().filter { it.pluginId == info.id }
                if (pages.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Pages:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    pages.forEach { p ->
                        Text(
                            "  [${p.id}] ${p.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isEnabled) {
                    OutlinedButton(onClick = onDisable) {
                        Icon(Icons.Filled.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Disable")
                    }
                } else {
                    Button(onClick = onEnable) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Enable")
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onReload) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reload")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onUninstall,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Uninstall")
                }
            }
        }
    }
}

@Composable
private fun InstallPluginDialog(
    onDismiss: () -> Unit,
    onInstall: (String) -> Unit
) {
    var source by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install Plugin") },
        text = {
            Column {
                Text(
                    "Enter a JAR file path or HTTP(S) URL:",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it; error = null },
                    label = { Text("File path or URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Examples:\n" +
                    "  /path/to/my-plugin.jar\n" +
                    "  https://example.com/plugins/my-plugin.jar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (source.isBlank()) {
                        error = "Please enter a file path or URL"
                    } else {
                        onInstall(source.trim())
                    }
                }
            ) {
                Text("Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
