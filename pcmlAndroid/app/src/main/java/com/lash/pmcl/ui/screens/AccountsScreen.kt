package com.lash.pmcl.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lash.pmcl.core.auth.Account
import com.lash.pmcl.core.auth.AuthService
import com.lash.pmcl.core.auth.AccountStore
import com.lash.pmcl.core.auth.DeviceCode
import com.lash.pmcl.core.preferences.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    authService: AuthService,
    preferences: Preferences,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val accounts = remember { mutableStateListOf<Account>() }
    var selectedUuid by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }

    // 离线账号对话框
    var showOfflineDialog by remember { mutableStateOf(false) }
    var offlineUsername by remember { mutableStateOf("") }

    // 微软登录状态
    var msDeviceCode by remember { mutableStateOf<DeviceCode?>(null) }
    var msLoggingIn by remember { mutableStateOf(false) }
    var msStatus by remember { mutableStateOf("") }
    var msError by remember { mutableStateOf<String?>(null) }

    fun reload() {
        loading = true
        loadError = null
        scope.launch {
            try {
                val store = withContext(Dispatchers.IO) { authService.loadStore() }
                accounts.clear()
                accounts.addAll(store.accounts)
                selectedUuid = store.selectedUuid ?: store.accounts.firstOrNull()?.uuid
            } catch (e: Exception) {
                loadError = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun saveStore() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    authService.saveStore(AccountStore(accounts.toList(), selectedUuid))
                }
            } catch (e: IOException) {
                loadError = "保存失败: ${e.message}"
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("账号") }) },
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 左侧：账号列表
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { showOfflineDialog = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Person, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("添加离线账号")
                    }
                    Button(
                        onClick = {
                            msLoggingIn = true
                            msError = null
                            msStatus = "正在请求设备码..."
                            scope.launch {
                                try {
                                    val dc = withContext(Dispatchers.IO) { authService.requestDeviceCode() }
                                    msDeviceCode = dc
                                    msStatus = "请在浏览器中完成登录"
                                    // 自动打开浏览器
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(dc.verificationUri))
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    try { context.startActivity(intent) } catch (_: Exception) {}
                                    // 开始轮询
                                    authService.loginMicrosoftAsync(dc) { msg ->
                                        msStatus = msg
                                    }.whenComplete { account, err ->
                                        msLoggingIn = false
                                        if (err != null) {
                                            msError = err.message ?: err.toString()
                                            msStatus = ""
                                        } else {
                                            msDeviceCode = null
                                            msStatus = "登录成功: ${account.username}"
                                            reload()
                                        }
                                    }
                                } catch (e: Exception) {
                                    msLoggingIn = false
                                    msError = e.message ?: e.toString()
                                    msStatus = ""
                                }
                            }
                        },
                        enabled = !msLoggingIn,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Outlined.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("微软登录")
                    }
                }

                Spacer(Modifier.height(12.dp))

                when {
                    loading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    loadError != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = loadError!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    accounts.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.AccountCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "暂无账号，请点击上方按钮添加",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(accounts, key = { it.uuid }) { acc ->
                                AccountRow(
                                    account = acc,
                                    selected = acc.uuid == selectedUuid,
                                    onSelect = {
                                        selectedUuid = acc.uuid
                                        saveStore()
                                    },
                                    onDelete = {
                                        accounts.removeIf { it.uuid == acc.uuid }
                                        if (selectedUuid == acc.uuid) {
                                            selectedUuid = accounts.firstOrNull()?.uuid
                                        }
                                        saveStore()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // 右侧：微软登录状态 / 设备码显示
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (msLoggingIn || msDeviceCode != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "微软账号登录",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            msDeviceCode?.let { dc ->
                                Text(
                                    text = "请访问:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Text(
                                    text = dc.verificationUri,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = "输入代码:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                ) {
                                    Text(
                                        text = dc.userCode,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = dc.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline,
                                )
                            }
                            if (msLoggingIn && msDeviceCode == null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(msStatus, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (msLoggingIn && msStatus.isNotEmpty() && msDeviceCode != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(msStatus, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (msError != null) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    text = msError!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = {
                                msLoggingIn = false
                                msDeviceCode = null
                                msStatus = ""
                                msError = null
                            }) { Text("取消") }
                        }
                    }
                } else if (msStatus.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "登录结果",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = msStatus,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (msError != null) MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.primary,
                            )
                            if (msError != null) {
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { msStatus = ""; msError = null }) { Text("清除") }
                            }
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Add,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "点击「微软登录」开始设备码登录流程",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }
    }

    // 离线账号对话框
    if (showOfflineDialog) {
        AlertDialog(
            onDismissRequest = { showOfflineDialog = false; offlineUsername = "" },
            title = { Text("添加离线账号") },
            text = {
                OutlinedTextField(
                    value = offlineUsername,
                    onValueChange = { offlineUsername = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = offlineUsername.isNotBlank(),
                    onClick = {
                        val acc = authService.offline(offlineUsername.trim())
                        accounts.add(acc)
                        if (selectedUuid == null) selectedUuid = acc.uuid
                        saveStore()
                        preferences.setLastOfflineUsername(offlineUsername.trim())
                        showOfflineDialog = false
                        offlineUsername = ""
                    },
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { showOfflineDialog = false; offlineUsername = "" }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun AccountRow(
    account: Account,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceVariant,
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.username,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (account.type) {
                        Account.AccountType.MICROSOFT -> "微软账号"
                        Account.AccountType.OFFLINE -> "离线账号"
                        Account.AccountType.GITHUB -> "GitHub"
                        Account.AccountType.YGGDRASIL -> "皮肤站"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.outline,
                )
                if (account.uuid.isNotEmpty()) {
                    Text(
                        text = account.uuid.take(18),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Checkbox(
                checked = selected,
                onCheckedChange = { if (it) onSelect() },
            )
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "删除账号",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
