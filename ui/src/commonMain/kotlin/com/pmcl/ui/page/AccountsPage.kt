package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.auth.Account
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.theme.LocalThemeState
import com.pmcl.ui.theme.glassCardBorder
import com.pmcl.ui.theme.glassCardColors
import com.pmcl.ui.theme.glassCardElevation
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

@Composable
fun AccountsPage(vm: LauncherViewModel) {
    val account by vm.account.collectAsState()
    val accounts by vm.accounts.collectAsState()
    val status by vm.status.collectAsState()
    val deviceCode by vm.deviceCode.collectAsState()
    val loggingIn by vm.loggingIn.collectAsState()

    // 当前登录流程类型：区分微软/GitHub，避免两卡片共用 deviceCode 状态导致同时显示
    var loginMode by remember { mutableStateOf<String?>(null) } // "ms" | "github" | null
    // 用户手动关闭设备码弹窗后隐藏；切换 loginMode 或点「查看设备码」时重置
    var hideDeviceCodeDialog by remember { mutableStateOf(false) }
    LaunchedEffect(loginMode) { hideDeviceCodeDialog = false }

    var username by remember { mutableStateOf("Steve") }
    var customSkinUrl by remember { mutableStateOf("") }
    var skinModel by remember { mutableStateOf("classic") }
    var deleteTarget by remember { mutableStateOf<Account?>(null) }
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll)) {
        Text(I18n.t("accounts.title"), style = MaterialTheme.typography.headlineSmall,
             fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // ===== 多账号列表 =====
        if (accounts.isNotEmpty()) {
            Text(I18n.t("accounts.list"), style = MaterialTheme.typography.titleSmall,
                 fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            accounts.forEach { acc ->
                AccountRow(
                    acc = acc,
                    isSelected = acc.getUuid() == account?.getUuid(),
                    onSwitch = { vm.switchAccount(acc.getUuid()) },
                    onDelete = { deleteTarget = acc }
                )
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(16.dp))
        }

        // 当前账号 + 皮肤预览
        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp)) {
                Text(I18n.t("accounts.current"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                if (account != null) {
                    val acc = account
                    if (acc == null) return@Column
                    // 皮肤预览区
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // 头像
                        val avatarUrl = acc.getAvatarUrl() ?: ""
                        if (avatarUrl.isNotEmpty()) {
                            SkinImage(avatarUrl, 64)
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(Icons.Filled.Person, I18n.t("accounts.default_avatar"),
                                 modifier = Modifier.padding(16.dp))
                            }
                        }
                        // 全身渲染（微软账号 + 皮肤站账号）
                        if (acc.getType() == Account.AccountType.MICROSOFT ||
                            acc.getType() == Account.AccountType.YGGDRASIL) {
                            SkinImage(acc.getBodyRenderUrl() ?: "", 128)
                        }
                        // 信息列
                        Column(Modifier.weight(1f)) {
                            Text(I18n.t("accounts.username_value", acc.getUsername()), fontWeight = FontWeight.SemiBold)
                            Text(I18n.t("accounts.uuid", acc.getUuid()), style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                            Text(I18n.t("accounts.type", acc.getType()))
                            if ((acc.getSkinUrl() ?: "").isNotEmpty()) {
                                Text(I18n.t("accounts.skin_url", (acc.getSkinUrl() ?: "").take(40)),
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.outline)
                                Text(I18n.t("accounts.skin_model_value", acc.getSkinModel()),
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = vm::logout) {
                        Text(I18n.t("accounts.logout"))
                    }
                } else {
                    Text(I18n.t("accounts.not_logged_in"), color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 自定义皮肤（仅离线账号）
        if (account?.getType() == Account.AccountType.OFFLINE) {
            Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
                Column(Modifier.padding(16.dp)) {
                    Text(I18n.t("accounts.custom_skin"), style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("accounts.custom_skin_hint"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customSkinUrl, onValueChange = { customSkinUrl = it },
                        label = { Text(I18n.t("accounts.skin_image_url")) },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(I18n.t("accounts.skin_model"), style = MaterialTheme.typography.labelMedium,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(4.dp))
                    com.pmcl.ui.animation.AnimatedSegmentedSelector(
                        items = listOf("Classic", "Slim"),
                        selectedIndex = if (skinModel == "slim") 1 else 0,
                        onSelect = { skinModel = if (it == 1) "slim" else "classic" }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.setOfflineSkin(customSkinUrl, skinModel) }) {
                            Text(I18n.t("accounts.apply_skin"))
                        }
                        OutlinedButton(onClick = {
                            customSkinUrl = ""
                            vm.setOfflineSkin("", "classic")
                        }) {
                            Text(I18n.t("accounts.clear_skin"))
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 离线登录卡片
        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp)) {
                Text(I18n.t("accounts.offline"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text(I18n.t("accounts.username")) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.loginOffline(username) },
                       enabled = username.isNotBlank() && !loggingIn) {
                    Text(I18n.t("accounts.login"))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 微软登录卡片
        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp)) {
                Text(I18n.t("accounts.microsoft"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                val usingBrowserFlow = vm.core.auth().hasCustomClientId()
                Text(
                    if (usingBrowserFlow)
                        I18n.t("accounts.ms_browser_flow_hint")
                    else
                        I18n.t("accounts.ms_device_flow_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.height(12.dp))

                if (loggingIn && loginMode == "ms") {
                    // 登录中：显示简短进度。设备码详情在独立弹窗展示
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (usingBrowserFlow) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Filled.Key, null, Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (deviceCode != null) I18n.t("accounts.device_code_ready")
                                else status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            if (deviceCode != null && hideDeviceCodeDialog) {
                                TextButton(onClick = { hideDeviceCodeDialog = false }) {
                                    Text(I18n.t("accounts.view_device_code"))
                                }
                            }
                        }
                    }
                } else {
                    Button(onClick = {
                        loginMode = "ms"
                        vm.startMicrosoftLogin()
                    }, enabled = !loggingIn) {
                        Icon(Icons.Filled.OpenInBrowser, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (loggingIn) I18n.t("accounts.logging_in") else I18n.t("accounts.start_ms_login"))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // GitHub 登录卡片
        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp)) {
                Text(I18n.t("accounts.github"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(I18n.t("accounts.github_hint"),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))

                if (loggingIn && loginMode == "github") {
                    // 登录中：设备码详情在独立弹窗展示
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Key, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (deviceCode != null) I18n.t("accounts.device_code_ready")
                                else status,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.weight(1f)
                            )
                            if (deviceCode != null && hideDeviceCodeDialog) {
                                TextButton(onClick = { hideDeviceCodeDialog = false }) {
                                    Text(I18n.t("accounts.view_device_code"))
                                }
                            }
                        }
                    }
                } else {
                    Button(onClick = {
                        loginMode = "github"
                        vm.startGitHubLogin()
                    }, enabled = !loggingIn) {
                        Text(if (loggingIn) I18n.t("accounts.logging_in") else I18n.t("accounts.github"))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 皮肤站登录卡片（authlib-injector / Yggdrasil）
        var yggdrasilApiUrl by remember { mutableStateOf("https://littleskin.cn") }
        var yggdrasilUsername by remember { mutableStateOf("") }
        var yggdrasilPassword by remember { mutableStateOf("") }
        var yggdrasilPasswordVisible by remember { mutableStateOf(false) }

        Card(Modifier.fillMaxWidth().glassCardBorder(), colors = glassCardColors(), elevation = glassCardElevation()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Palette, null, Modifier.size(18.dp),
                         tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(I18n.t("accounts.yggdrasil"), style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(4.dp))
                Text(I18n.t("accounts.yggdrasil_hint"),
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = yggdrasilApiUrl, onValueChange = { yggdrasilApiUrl = it },
                    label = { Text(I18n.t("accounts.yggdrasil_api_url")) },
                    placeholder = { Text("https://littleskin.cn") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = yggdrasilUsername, onValueChange = { yggdrasilUsername = it },
                    label = { Text(I18n.t("accounts.yggdrasil_username")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = yggdrasilPassword, onValueChange = { yggdrasilPassword = it },
                    label = { Text(I18n.t("accounts.yggdrasil_password")) },
                    singleLine = true,
                    visualTransformation = if (yggdrasilPasswordVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { yggdrasilPasswordVisible = !yggdrasilPasswordVisible }) {
                            Icon(
                                if (yggdrasilPasswordVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = if (yggdrasilPasswordVisible)
                                    I18n.t("accounts.hide_password")
                                else I18n.t("accounts.show_password"),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))

                if (loggingIn && loginMode == "yggdrasil") {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                            Text(status, style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onPrimaryContainer,
                                 modifier = Modifier.weight(1f))
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            loginMode = "yggdrasil"
                            vm.startYggdrasilLogin(yggdrasilApiUrl, yggdrasilUsername, yggdrasilPassword)
                        },
                        enabled = !loggingIn && yggdrasilApiUrl.isNotBlank()
                                  && yggdrasilUsername.isNotBlank() && yggdrasilPassword.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Palette, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(I18n.t("accounts.yggdrasil_login"))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("${I18n.t("common.status")}: $status",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }

    // 设备码独立弹窗（微软 + GitHub 共用）
    val dc = deviceCode
    if (dc != null && !hideDeviceCodeDialog) {
        DeviceCodeDialog(
            verificationUri = dc.getVerificationUri(),
            userCode = dc.getUserCode(),
            status = status,
            onOpenBrowser = {
                try {
                    com.pmcl.core.web.WikiBrowser.open(dc.getVerificationUri())
                } catch (t: Throwable) {
                    vm.updateStatus(I18n.t("accounts.open_browser_failed", (t.message ?: t.toString())))
                }
            },
            onClose = { hideDeviceCodeDialog = true }
        )
    }

    // 删除账号确认对话框
    deleteTarget?.let { acc ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(I18n.t("accounts.remove_confirm_title")) },
            text = { Text(I18n.t("accounts.remove_confirm", acc.getUsername())) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeAccount(acc.getUuid())
                    deleteTarget = null
                }) { Text(I18n.t("common.delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(I18n.t("common.cancel")) }
            }
        )
    }
}

/**
 * 设备码登录独立弹窗。微软 device code flow 和 GitHub device flow 共用。
 * 登录完成/失败时 ViewModel 会清空 deviceCode，弹窗自动消失。
 */
@Composable
private fun DeviceCodeDialog(
    verificationUri: String,
    userCode: String,
    status: String,
    onOpenBrowser: () -> Unit,
    onClose: () -> Unit
) {
    var copied by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(20.dp).widthIn(min = 320.dp, max = 420.dp)) {
                // 标题
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Key, null, Modifier.size(22.dp),
                         tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(I18n.t("accounts.device_code_title"), style = MaterialTheme.typography.titleMedium,
                         fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(16.dp))

                // 步骤 1：打开浏览器
                Text(I18n.t("accounts.device_code_step1"),
                     style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Button(onClick = onOpenBrowser, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.OpenInBrowser, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(verificationUri)
                }

                Spacer(Modifier.height(16.dp))

                // 步骤 2：输入代码
                Text(I18n.t("accounts.device_code_step2"),
                     style = MaterialTheme.typography.labelMedium,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = {
                            try {
                                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                                clipboard.setContents(
                                    java.awt.datatransfer.StringSelection(userCode), null
                                )
                                copied = true
                            } catch (_: Throwable) {}
                        }) {
                            Icon(
                                if (copied) Icons.Filled.Check else Icons.Filled.ContentCopy,
                                if (copied) I18n.t("common.copied") else I18n.t("common.copy"),
                                Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (copied) {
                    Spacer(Modifier.height(4.dp))
                    Text(I18n.t("accounts.code_copied"),
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.primary)
                }

                Spacer(Modifier.height(16.dp))

                // 状态
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(I18n.t("accounts.device_code_wait_hint"),
                     style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))

                Spacer(Modifier.height(16.dp))

                // 关闭按钮（仅关闭弹窗，登录继续后台进行）
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onClose) { Text(I18n.t("common.close")) }
                }
            }
        }
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
    onDelete: () -> Unit
) {
    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            val avatarUrl = acc.getAvatarUrl() ?: ""
            if (avatarUrl.isNotEmpty()) {
                SkinImage(avatarUrl, 36)
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Filled.Person, contentDescription = null,
                         modifier = Modifier.padding(8.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            // 信息
            Column(Modifier.weight(1f)) {
                Text(acc.getUsername(), fontWeight = FontWeight.SemiBold, maxLines = 1,
                     overflow = TextOverflow.Ellipsis)
                Text(acc.getType().name, style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.outline)
            }
            // 选中标记
            if (isSelected) {
                Icon(Icons.Filled.Check, contentDescription = I18n.t("accounts.active"),
                     tint = MaterialTheme.colorScheme.primary,
                     modifier = Modifier.size(18.dp))
            } else {
                // 切换按钮
                IconButton(onClick = onSwitch) {
                    Icon(Icons.Filled.SwapHoriz, contentDescription = I18n.t("accounts.switch"),
                         modifier = Modifier.size(18.dp))
                }
            }
            // 删除按钮
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = I18n.t("accounts.remove"),
                     tint = MaterialTheme.colorScheme.error,
                     modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ============ 皮肤图片加载（带内存缓存） ============
// M32 修复：复用全局 LruImageCache
private val skinImageCache = com.pmcl.ui.util.LruImageCache(64)

/** 异步加载皮肤图片，带内存缓存 */
@Composable
private fun SkinImage(url: String, sizePx: Int) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(skinImageCache.get(url)) }
    LaunchedEffect(url) {
        if (url.isEmpty()) { image = null; return@LaunchedEffect }
        if (skinImageCache.isKnownFailed(url)) { image = null; return@LaunchedEffect }
        val existing = skinImageCache.get(url)
        if (existing != null) { image = existing; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                if (url.isNullOrBlank()) return@withContext
                val bytes = URL(url).readBytes()
                val bmp = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                skinImageCache.put(url, bmp)
                image = bmp
            } catch (_: Throwable) {
                skinImageCache.markFailed(url)
                image = null
            }
        }
    }
    if (image != null) {
        val bmp = image
        if (bmp == null) return
        Image(
            bitmap = bmp,
            contentDescription = I18n.t("accounts.skin_preview"),
            modifier = Modifier.size(sizePx.dp).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(sizePx.dp)
        ) {
            Icon(Icons.Filled.Person, I18n.t("common.loading"),
                 modifier = Modifier.padding(sizePx.dp / 4))
        }
    }
}
