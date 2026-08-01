package com.lash.pmcl.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.auth.Account
import com.lash.pmcl.core.auth.AuthService
import com.lash.pmcl.core.launch.LaunchManager
import com.lash.pmcl.core.preferences.Preferences
import com.lash.pmcl.core.version.VersionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LaunchScreen(
    authService: AuthService,
    launchManager: LaunchManager,
    versionManager: VersionManager,
    preferences: Preferences,
) {
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf<Account?>(null) }
    var accountType by remember { mutableStateOf("") }
    var versions by remember { mutableStateOf<List<VersionManager.LocalVersionInfo>>(emptyList()) }
    var loadingVersions by remember { mutableStateOf(true) }
    var selectedVersion by remember { mutableStateOf("") }
    var launching by remember { mutableStateOf(false) }
    var launchStatus by remember { mutableStateOf("") }
    val logs = remember { androidx.compose.runtime.mutableStateListOf<String>() }

    // 加载账号 + 版本列表
    LaunchedEffect(Unit) {
        scope.launch {
            account = withContext(Dispatchers.IO) {
                try {
                    val store = authService.loadStore()
                    val selected = store.accounts.firstOrNull { it.uuid == store.selectedUuid }
                        ?: store.accounts.firstOrNull()
                    accountType = if (selected != null) {
                        when (selected.type) {
                            Account.AccountType.MICROSOFT -> "微软账号"
                            Account.AccountType.OFFLINE -> "离线账号"
                            Account.AccountType.GITHUB -> "GitHub"
                            Account.AccountType.YGGDRASIL -> "皮肤站"
                        }
                    } else ""
                    selected
                } catch (_: Exception) { null }
            }
        }
        scope.launch {
            loadingVersions = true
            val result = withContext(Dispatchers.IO) {
                try { versionManager.scanAllLocalVersions() } catch (_: Exception) { emptyList() }
            }
            versions = result
            val last = preferences.getLastSelectedVersion()
            selectedVersion = if (last.isNotEmpty() && result.any { it.id == last }) {
                last
            } else {
                result.firstOrNull { it.isLaunchable }?.id ?: ""
            }
            loadingVersions = false
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("启动") }) },
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 左侧：账号 + 版本选择 + 启动按钮
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AccountCard(account, accountType)

                VersionSelector(
                    versions = versions,
                    selected = selectedVersion,
                    loading = loadingVersions,
                    onSelect = {
                        selectedVersion = it
                        preferences.setLastSelectedVersion(it)
                    },
                    onRefresh = {
                        scope.launch {
                            loadingVersions = true
                            versions = withContext(Dispatchers.IO) {
                                try { versionManager.scanAllLocalVersions() } catch (_: Exception) { emptyList() }
                            }
                            loadingVersions = false
                        }
                    },
                )

                Spacer(Modifier.weight(1f))

                Button(
                    onClick = {
                        if (selectedVersion.isEmpty()) {
                            launchStatus = "请先选择版本"
                            return@Button
                        }
                        launching = true
                        launchStatus = "正在构造启动配置..."
                        logs.clear()
                        scope.launch {
                            try {
                                val profile = withContext(Dispatchers.IO) {
                                    launchManager.buildProfile(selectedVersion, account)
                                }
                                val deny = launchManager.verifyBeforeLaunch(profile)
                                if (deny != null) {
                                    launchStatus = deny
                                    logs.add(deny)
                                    launching = false
                                    return@launch
                                }
                                logs.add("[PMCL] 启动 ${selectedVersion}（玩家: ${account?.username ?: "Player"}）")
                                launchStatus = "启动中..."
                                launchManager.launchAsync(
                                    profile,
                                    "java",
                                ) { line -> logs.add(line) }
                                    .whenComplete { _, err ->
                                        launching = false
                                        if (err != null) {
                                            launchStatus = "启动失败: ${err.message}"
                                            logs.add("[PMCL] 启动失败: ${err.message}")
                                        } else {
                                            launchStatus = "游戏进程已启动"
                                        }
                                    }
                            } catch (e: Exception) {
                                launching = false
                                launchStatus = "构造启动配置失败: ${e.message}"
                                logs.add("[PMCL] 错误: ${e.message}")
                            }
                        }
                    },
                    enabled = !launching && selectedVersion.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    if (launching) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("启动中...")
                    } else {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("启动游戏")
                    }
                }
                if (launchStatus.isNotEmpty()) {
                    Text(
                        text = launchStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // 右侧：启动日志
            Card(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "启动日志",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "暂无日志",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(logs) { line ->
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountCard(account: Account?, accountType: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                if (account != null) {
                    Text(
                        text = account.username,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = accountType,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    if (account.uuid.isNotEmpty()) {
                        Text(
                            text = "UUID: ${account.uuid.take(13)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                } else {
                    Text(
                        text = "未选择账号",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "请在「账号」页添加账号",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionSelector(
    versions: List<VersionManager.LocalVersionInfo>,
    selected: String,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                label = { Text("选择版本") },
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (versions.isEmpty()) {
                    DropdownMenuItem(
                        text = { Text("暂无已安装版本") },
                        onClick = { expanded = false },
                    )
                } else {
                    versions.forEach { v ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(v.id, style = MaterialTheme.typography.bodyMedium)
                                        if (v.lastModified > 0) {
                                            Text(
                                                dateFmt.format(Date(v.lastModified)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                            )
                                        }
                                    }
                                    if (!v.isLaunchable) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.errorContainer,
                                        ) {
                                            Text(
                                                "不可用",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            onClick = {
                                onSelect(v.id)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新版本列表")
        }
    }
}
