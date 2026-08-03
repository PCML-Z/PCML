package com.lash.pmcl.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.lash.pmcl.core.auth.Account
import com.lash.pmcl.core.auth.AccountStore
import com.lash.pmcl.core.auth.AuthService
import com.lash.pmcl.core.auth.DeviceCode
import com.lash.pmcl.core.auth.GitHubAuthFlow
import com.lash.pmcl.core.auth.SkinManager
import com.lash.pmcl.core.auth.YggdrasilAuthFlow
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

    // 全局登录/状态
    var status by remember { mutableStateOf("") }
    var loggingIn by remember { mutableStateOf(false) }
    // "ms" | "github" | "yggdrasil" | null —— 区分设备码弹窗归属，避免两卡片共用 deviceCode
    var loginMode by remember { mutableStateOf<String?>(null) }
    var deviceCode by remember { mutableStateOf<DeviceCode?>(null) }
    // 用户手动关闭设备码弹窗后隐藏；切换 loginMode 时重置
    var hideDeviceCodeDialog by remember { mutableStateOf(false) }
    LaunchedEffect(loginMode) { hideDeviceCodeDialog = false }
    // 皮肤上传/重置进行中
    var skinBusy by remember { mutableStateOf(false) }

    // 离线登录
    var offlineUsername by remember {
        mutableStateOf(preferences.getLastOfflineUsername().ifBlank { "Steve" })
    }

    // 自定义皮肤（离线）
    var customSkinUrl by remember { mutableStateOf("") }
    var skinModel by remember { mutableStateOf("classic") }

    // 皮肤站登录
    var yggdrasilApiUrl by remember { mutableStateOf("https://littleskin.cn") }
    var yggdrasilUsername by remember { mutableStateOf("") }
    var yggdrasilPassword by remember { mutableStateOf("") }
    var yggdrasilPasswordVisible by remember { mutableStateOf(false) }

    var deleteTarget by remember { mutableStateOf<Account?>(null) }
    val scroll = rememberScrollState()

    val skinManager = remember { SkinManager() }
    val githubFlow = remember { GitHubAuthFlow() }
    val yggdrasilFlow = remember { YggdrasilAuthFlow() }

    val currentAccount: Account? = accounts.firstOrNull { it.uuid == selectedUuid }

    fun saveStore() {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    authService.saveStore(AccountStore(accounts.toList(), selectedUuid))
                }
            } catch (e: Exception) {
                status = "保存失败: ${e.message}"
            }
        }
    }

    fun reload() {
        loading = true
        loadError = null
        scope.launch {
            try {
                val store = withContext(Dispatchers.IO) { authService.loadStore() }
                accounts.clear()
                accounts.addAll(store.accounts)
                selectedUuid = store.selectedUuid ?: store.accounts.firstOrNull()?.uuid
                if (store.hasCorruptedAccounts()) {
                    status = "检测到 ${store.corruptedAccounts.size} 个账号无法解密，可能需要重新登录"
                }
            } catch (e: Exception) {
                loadError = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    fun upsertAccount(account: Account) {
        val idx = accounts.indexOfFirst { it.uuid == account.uuid }
        if (idx >= 0) accounts[idx] = account else accounts.add(account)
        selectedUuid = account.uuid
        saveStore()
    }

    fun switchAccount(uuid: String) {
        selectedUuid = uuid
        saveStore()
    }

    fun removeAccount(uuid: String) {
        accounts.removeIf { it.uuid == uuid }
        if (selectedUuid == uuid) selectedUuid = accounts.firstOrNull()?.uuid
        saveStore()
    }

    fun logoutCurrent() {
        val acc = currentAccount ?: return
        removeAccount(acc.uuid)
        status = "已注销: ${acc.username}"
    }

    // ============ 登录动作 ============
    fun startOfflineLogin() {
        if (offlineUsername.isBlank() || loggingIn) return
        scope.launch {
            try {
                val account = authService.offline(offlineUsername.trim())
                preferences.setLastOfflineUsername(offlineUsername.trim())
                upsertAccount(account)
                status = "已添加离线账号: ${account.username}"
            } catch (e: Exception) {
                status = "添加失败: ${e.message ?: e.toString()}"
            }
        }
    }

    fun startMicrosoftLogin() {
        if (loggingIn) return
        loginMode = "ms"
        loggingIn = true
        deviceCode = null
        status = "正在请求设备码..."
        scope.launch {
            try {
                val dc = withContext(Dispatchers.IO) { authService.requestDeviceCode() }
                deviceCode = dc
                status = "请在浏览器中完成登录"
                val account = withContext(Dispatchers.IO) {
                    authService.loginMicrosoftAsync(dc) { msg -> status = msg }.join()
                }
                deviceCode = null
                status = "登录成功: ${account.username}"
                upsertAccount(account)
            } catch (e: Exception) {
                status = "登录失败: ${e.message ?: e.toString()}"
            } finally {
                loggingIn = false
            }
        }
    }

    fun startGitHubLogin() {
        if (loggingIn) return
        loginMode = "github"
        loggingIn = true
        deviceCode = null
        status = "正在请求 GitHub 设备码..."
        scope.launch {
            try {
                val dc = withContext(Dispatchers.IO) { githubFlow.requestDeviceCode() }
                deviceCode = dc
                status = "请在浏览器中完成授权"
                val token = withContext(Dispatchers.IO) {
                    githubFlow.pollForAccessToken(dc) { msg -> status = msg }.join()
                }
                val account = withContext(Dispatchers.IO) { githubFlow.completeLogin(token) }
                deviceCode = null
                status = "登录成功: ${account.username}"
                upsertAccount(account)
            } catch (e: Exception) {
                status = "登录失败: ${e.message ?: e.toString()}"
            } finally {
                loggingIn = false
            }
        }
    }

    fun startYggdrasilLogin() {
        if (loggingIn) return
        if (yggdrasilApiUrl.isBlank() || yggdrasilUsername.isBlank() || yggdrasilPassword.isBlank()) return
        loginMode = "yggdrasil"
        loggingIn = true
        status = "正在登录皮肤站..."
        scope.launch {
            try {
                val account = withContext(Dispatchers.IO) {
                    yggdrasilFlow.login(yggdrasilApiUrl, yggdrasilUsername, yggdrasilPassword)
                }
                status = "登录成功: ${account.username}"
                upsertAccount(account)
            } catch (e: Exception) {
                status = "登录失败: ${e.message ?: e.toString()}"
            } finally {
                loggingIn = false
            }
        }
    }

    // ============ 皮肤操作 ============
    fun applyOfflineSkin() {
        val acc = currentAccount ?: return
        scope.launch {
            try {
                val idx = accounts.indexOfFirst { it.uuid == acc.uuid }
                if (idx >= 0) {
                    accounts[idx] = acc.copy(skinUrl = customSkinUrl.trim(), skinModel = skinModel)
                    saveStore()
                    status = "已应用自定义皮肤"
                }
            } catch (e: Exception) {
                status = "应用皮肤失败: ${e.message}"
            }
        }
    }

    fun clearOfflineSkin() {
        customSkinUrl = ""
        skinModel = "classic"
        val acc = currentAccount ?: return
        scope.launch {
            try {
                val idx = accounts.indexOfFirst { it.uuid == acc.uuid }
                if (idx >= 0) {
                    accounts[idx] = acc.copy(skinUrl = "", skinModel = "classic")
                    saveStore()
                    status = "已清除自定义皮肤"
                }
            } catch (e: Exception) {
                status = "清除皮肤失败: ${e.message}"
            }
        }
    }

    fun uploadMicrosoftSkin(uri: Uri, model: String) {
        val acc = currentAccount ?: return
        if (skinBusy) return
        skinBusy = true
        status = "正在上传皮肤..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        skinManager.uploadMicrosoftSkin(acc.accessToken, stream, model)
                    } ?: throw IOException("无法打开皮肤文件")
                }
                status = "皮肤上传成功"
                reload()
            } catch (e: Exception) {
                status = "皮肤上传失败: ${e.message ?: e.toString()}"
            } finally {
                skinBusy = false
            }
        }
    }

    fun resetMicrosoftSkin() {
        val acc = currentAccount ?: return
        if (skinBusy) return
        skinBusy = true
        status = "正在重置皮肤..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    skinManager.resetMicrosoftSkin(acc.accessToken)
                }
                status = "皮肤已重置为默认"
                reload()
            } catch (e: Exception) {
                status = "重置皮肤失败: ${e.message ?: e.toString()}"
            } finally {
                skinBusy = false
            }
        }
    }

    fun uploadYggdrasilSkin(uri: Uri, model: String, password: String) {
        val acc = currentAccount ?: return
        if (skinBusy) return
        skinBusy = true
        status = "正在上传皮肤到皮肤站..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val playerId = acc.uuid.replace("-", "")
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        skinManager.uploadYggdrasilSkin(
                            acc.authServerUrl, acc.username, password, playerId, stream, model
                        )
                    } ?: throw IOException("无法打开皮肤文件")
                }
                status = "皮肤上传成功"
                reload()
            } catch (e: Exception) {
                status = "皮肤上传失败: ${e.message ?: e.toString()}"
            } finally {
                skinBusy = false
            }
        }
    }

    fun resetYggdrasilSkin(password: String) {
        val acc = currentAccount ?: return
        if (skinBusy) return
        skinBusy = true
        status = "正在重置皮肤站皮肤..."
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val playerId = acc.uuid.replace("-", "")
                    skinManager.resetYggdrasilSkin(acc.authServerUrl, acc.username, password, playerId)
                }
                status = "皮肤站皮肤已重置"
                reload()
            } catch (e: Exception) {
                status = "重置皮肤失败: ${e.message ?: e.toString()}"
            } finally {
                skinBusy = false
            }
        }
    }

    fun openBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (t: Throwable) {
            status = "打开浏览器失败: ${t.message ?: t.toString()}"
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("账号") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scroll),
        ) {
            Text("账号", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))

            if (loading && accounts.isEmpty()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
            if (loadError != null && accounts.isEmpty()) {
                Text(
                    loadError!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(16.dp))
            }

            // ===== 多账号列表 =====
            if (accounts.isNotEmpty()) {
                Text(
                    "账号列表",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                accounts.forEach { acc ->
                    AccountRow(
                        acc = acc,
                        isSelected = acc.uuid == selectedUuid,
                        onSwitch = { switchAccount(acc.uuid) },
                        onDelete = { deleteTarget = acc },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(16.dp))
            }

            // ===== 当前账号卡片 =====
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("当前账号", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    val acc = currentAccount
                    if (acc != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // 头像占位（Android 无 Skia Image，统一用图标）
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(64.dp),
                            ) {
                                Icon(Icons.Filled.Person, "头像", modifier = Modifier.padding(16.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(acc.username, fontWeight = FontWeight.SemiBold)
                                Text("UUID: ${acc.uuid}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                Text("类型: ${typeText(acc.type)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                if (acc.skinUrl.isNotEmpty()) {
                                    Text(
                                        "皮肤: ${acc.skinUrl.take(40)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text("模型: ${acc.skinModel}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { logoutCurrent() }) { Text("注销") }
                    } else {
                        Text("未登录", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ===== 自定义皮肤（仅离线账号） =====
            if (currentAccount?.type == Account.AccountType.OFFLINE) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("自定义皮肤", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "为离线账号设置自定义皮肤 URL（启动时使用）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = customSkinUrl,
                            onValueChange = { customSkinUrl = it },
                            label = { Text("皮肤图片 URL") },
                            placeholder = { Text("https://…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("皮肤模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = skinModel == "classic",
                                onClick = { skinModel = "classic" },
                                label = { Text("Classic") },
                            )
                            FilterChip(
                                selected = skinModel == "slim",
                                onClick = { skinModel = "slim" },
                                label = { Text("Slim") },
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { applyOfflineSkin() }) { Text("应用") }
                            OutlinedButton(onClick = { clearOfflineSkin() }) { Text("清除") }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ===== 皮肤上传（微软账号） =====
            if (currentAccount?.type == Account.AccountType.MICROSOFT) {
                SkinUploadCard(
                    title = "皮肤上传",
                    hint = "上传 PNG 皮肤到微软账号（尺寸 64×32 / 64×64 / 128×128，≤1MB）",
                    modelOptions = listOf("Classic", "Slim"),
                    modelValues = listOf("classic", "slim"),
                    requirePassword = false,
                    busy = skinBusy,
                    onUpload = { uri, model, _ -> uploadMicrosoftSkin(uri, model) },
                    onReset = { resetMicrosoftSkin() },
                )
                Spacer(Modifier.height(16.dp))
            }

            // ===== 皮肤上传（皮肤站账号） =====
            if (currentAccount?.type == Account.AccountType.YGGDRASIL) {
                SkinUploadCard(
                    title = "皮肤上传",
                    hint = "上传 PNG 皮肤到皮肤站（需输入密码重新登录获取会话）",
                    modelOptions = listOf("Steve", "Slim"),
                    modelValues = listOf("steve", "slim"),
                    requirePassword = true,
                    busy = skinBusy,
                    onUpload = { uri, model, pwd -> uploadYggdrasilSkin(uri, model, pwd) },
                    onReset = { pwd -> resetYggdrasilSkin(pwd) },
                )
                Spacer(Modifier.height(16.dp))
            }

            // ===== 离线登录卡片 =====
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("离线登录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = offlineUsername,
                        onValueChange = { offlineUsername = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { startOfflineLogin() },
                        enabled = offlineUsername.isNotBlank() && !loggingIn,
                    ) { Text("登录") }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ===== 微软登录卡片 =====
            val usingBrowserFlow = authService.hasCustomClientId()
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("微软登录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (usingBrowserFlow) "将使用浏览器授权码流程登录"
                        else "将使用设备码流程登录，请在浏览器中输入代码完成登录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (loggingIn && loginMode == "ms") {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (usingBrowserFlow) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Filled.Key, null, Modifier.size(20.dp))
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (deviceCode != null) "设备码已就绪" else status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                if (deviceCode != null && hideDeviceCodeDialog) {
                                    TextButton(onClick = { hideDeviceCodeDialog = false }) { Text("查看设备码") }
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = { startMicrosoftLogin() },
                            enabled = !loggingIn,
                        ) {
                            Icon(Icons.Filled.OpenInBrowser, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (loggingIn) "登录中..." else "微软登录")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ===== GitHub 登录卡片 =====
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("GitHub 登录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "使用 GitHub 设备码流程登录（OAuth），授权后可获得 GitHub 身份",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(12.dp))
                    if (loggingIn && loginMode == "github") {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Key, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (deviceCode != null) "设备码已就绪" else status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                if (deviceCode != null && hideDeviceCodeDialog) {
                                    TextButton(onClick = { hideDeviceCodeDialog = false }) { Text("查看设备码") }
                                }
                            }
                        }
                    } else {
                        Button(
                            onClick = { startGitHubLogin() },
                            enabled = !loggingIn,
                        ) {
                            Icon(Icons.Filled.Key, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (loggingIn) "登录中..." else "GitHub 登录")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // ===== 皮肤站登录卡片 =====
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Palette, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("皮肤站登录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "authlib-injector 皮肤站账号登录（如 LittleSkin）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = yggdrasilApiUrl,
                        onValueChange = { yggdrasilApiUrl = it },
                        label = { Text("API 地址") },
                        placeholder = { Text("https://littleskin.cn") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = yggdrasilUsername,
                        onValueChange = { yggdrasilUsername = it },
                        label = { Text("用户名 / 邮箱") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = yggdrasilPassword,
                        onValueChange = { yggdrasilPassword = it },
                        label = { Text("密码") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (yggdrasilPasswordVisible) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { yggdrasilPasswordVisible = !yggdrasilPasswordVisible }) {
                                Icon(
                                    if (yggdrasilPasswordVisible) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = if (yggdrasilPasswordVisible) "隐藏密码" else "显示密码",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (loggingIn && loginMode == "yggdrasil") {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = { startYggdrasilLogin() },
                            enabled = !loggingIn && yggdrasilApiUrl.isNotBlank()
                                      && yggdrasilUsername.isNotBlank() && yggdrasilPassword.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.Palette, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("皮肤站登录")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text(
                "状态: $status",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }

    // ===== 设备码独立弹窗（微软 + GitHub 共用） =====
    val dc = deviceCode
    if (dc != null && !hideDeviceCodeDialog) {
        DeviceCodeDialog(
            verificationUri = dc.verificationUri,
            userCode = dc.userCode,
            status = status,
            onOpenBrowser = { openBrowser(dc.verificationUri) },
            onClose = { hideDeviceCodeDialog = true },
        )
    }

    // ===== 删除账号确认对话框 =====
    deleteTarget?.let { acc ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除账号") },
            text = { Text("确认删除账号「${acc.username}」？") },
            confirmButton = {
                TextButton(onClick = {
                    removeAccount(acc.uuid)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/**
 * 账号列表行：头像 + 用户名 + 类型，选中态高亮，切换/删除按钮。
 */
@Composable
private fun AccountRow(
    acc: Account,
    isSelected: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
) {
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 头像占位
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.size(36.dp),
            ) {
                Icon(Icons.Filled.Person, contentDescription = null, modifier = Modifier.padding(8.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(acc.username, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    typeText(acc.type),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.outline,
                )
            }
            // 选中标记 / 切换按钮
            if (isSelected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "当前账号",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                IconButton(onClick = onSwitch) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = "切换", modifier = Modifier.size(18.dp))
                }
            }
            // 删除按钮
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * 设备码登录独立弹窗。微软 device code flow 与 GitHub device flow 共用。
 * 登录完成/失败时 deviceCode 会被清空，弹窗自动消失。
 */
@Composable
private fun DeviceCodeDialog(
    verificationUri: String,
    userCode: String,
    status: String,
    onOpenBrowser: () -> Unit,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(20.dp).widthIn(min = 280.dp, max = 360.dp)) {
                // 标题
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Key, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("设备码登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(16.dp))

                // 步骤 1：打开浏览器
                Text("步骤 1：打开浏览器", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Button(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.OpenInBrowser, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(verificationUri, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(16.dp))

                // 步骤 2：输入代码
                Text("步骤 2：输入代码", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace,
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = {
                            clipboard.setText(AnnotatedString(userCode))
                            copied = true
                        }) {
                            Icon(
                                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                contentDescription = if (copied) "已复制" else "复制",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                if (copied) {
                    Spacer(Modifier.height(4.dp))
                    Text("代码已复制到剪贴板", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(16.dp))

                // 状态
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "关闭后登录将在后台继续，完成后自动刷新账号列表。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(16.dp))

                // 关闭按钮（仅关闭弹窗，登录继续后台进行）
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("关闭") }
                }
            }
        }
    }
}

/**
 * 皮肤上传与管理卡片。
 * - requirePassword = false：微软账号（无需密码，使用 accessToken）
 * - requirePassword = true：皮肤站账号（需输入密码重新登录以获取 session）
 *
 * onUpload 统一签名为 (uri, model, password) -> Unit，微软模式忽略 password。
 * onReset 统一签名为 (password) -> Unit，微软模式忽略 password。
 */
@Composable
private fun SkinUploadCard(
    title: String,
    hint: String,
    modelOptions: List<String>,
    modelValues: List<String>,
    requirePassword: Boolean,
    busy: Boolean,
    onUpload: (Uri, String, String) -> Unit,
    onReset: (String) -> Unit,
) {
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedModel by remember { mutableStateOf(modelValues.firstOrNull() ?: "classic") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) selectedUri = uri
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))

            // 文件选择
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { launcher.launch(arrayOf("image/png")) },
                    enabled = !busy,
                ) {
                    Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("选择皮肤文件")
                }
                selectedUri?.let {
                    Text(
                        it.lastPathSegment ?: "skin.png",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // 模型选择
            Text("皮肤模型", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                modelOptions.forEachIndexed { i, label ->
                    FilterChip(
                        selected = selectedModel == modelValues[i],
                        onClick = { selectedModel = modelValues[i] },
                        label = { Text(label) },
                    )
                }
            }

            // 密码输入（仅皮肤站模式）
            if (requirePassword) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("皮肤站密码") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "切换密码可见",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))

            // 操作按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { selectedUri?.let { onUpload(it, selectedModel, password) } },
                    enabled = !busy && selectedUri != null && (!requirePassword || password.isNotBlank()),
                ) { Text(if (busy) "处理中..." else "上传皮肤") }
                OutlinedButton(
                    onClick = { onReset(password) },
                    enabled = !busy && (!requirePassword || password.isNotBlank()),
                ) { Text("重置皮肤") }
            }
        }
    }
}

private fun typeText(type: Account.AccountType): String = when (type) {
    Account.AccountType.MICROSOFT -> "微软账号"
    Account.AccountType.OFFLINE -> "离线账号"
    Account.AccountType.GITHUB -> "GitHub"
    Account.AccountType.YGGDRASIL -> "皮肤站"
}
