package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.auth.Account
import com.pmcl.core.i18n.I18n
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
        Card(Modifier.fillMaxWidth()) {
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
                                Icon(Icons.Filled.Person, "默认头像",
                                     modifier = Modifier.padding(16.dp))
                            }
                        }
                        // 全身渲染（微软账号）
                        if (acc.getType() == Account.AccountType.MICROSOFT) {
                            SkinImage(acc.getBodyRenderUrl() ?: "", 128)
                        }
                        // 信息列
                        Column(Modifier.weight(1f)) {
                            Text("用户名：${acc.getUsername()}", fontWeight = FontWeight.SemiBold)
                            Text("UUID：${acc.getUuid()}", style = MaterialTheme.typography.labelSmall,
                                 color = MaterialTheme.colorScheme.outline)
                            Text("类型：${acc.getType()}")
                            if ((acc.getSkinUrl() ?: "").isNotEmpty()) {
                                Text("皮肤：${(acc.getSkinUrl() ?: "").take(40)}…",
                                     style = MaterialTheme.typography.labelSmall,
                                     color = MaterialTheme.colorScheme.outline)
                                Text("模型：${acc.getSkinModel()}",
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
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("自定义皮肤", style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text("输入皮肤图片 URL（如 https://crafatar.com/avatars/<UUID>）或留空清除",
                         style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = customSkinUrl, onValueChange = { customSkinUrl = it },
                        label = { Text("皮肤图片 URL") },
                        placeholder = { Text("https://…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("皮肤模型", style = MaterialTheme.typography.labelMedium,
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
                            Text("应用皮肤")
                        }
                        OutlinedButton(onClick = {
                            customSkinUrl = ""
                            vm.setOfflineSkin("", "classic")
                        }) {
                            Text("清除")
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 离线登录卡片
        Card(Modifier.fillMaxWidth()) {
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
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(I18n.t("accounts.microsoft"), style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("使用设备码流程登录，无需输入密码。\n微软账号自动同步 Mojang 服务器皮肤。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))

                if (deviceCode != null) {
                    val dc = deviceCode
                    if (dc == null) return@Column
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("请打开浏览器访问：")
                            Text(dc.getVerificationUri(),
                                 fontWeight = FontWeight.Bold,
                                 color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text("输入代码：")
                            Text(dc.getUserCode(),
                                 style = MaterialTheme.typography.headlineMedium,
                                 color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    Button(onClick = vm::startMicrosoftLogin, enabled = !loggingIn) {
                        Text(if (loggingIn) I18n.t("accounts.logging_in") else I18n.t("accounts.start_ms_login"))
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // GitHub 登录卡片
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("GitHub 登录", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("使用设备码流程登录，无需输入密码。\n登录后可用 GitHub 头像和用户名。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))

                if (deviceCode != null) {
                    val dc = deviceCode
                    if (dc == null) return@Column
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text("请打开浏览器访问：")
                            Text(dc.getVerificationUri(),
                                 fontWeight = FontWeight.Bold,
                                 color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(4.dp))
                            Text("输入代码：")
                            Text(dc.getUserCode(),
                                 style = MaterialTheme.typography.headlineMedium,
                                 color = MaterialTheme.colorScheme.primary)
                        }
                    }
                } else {
                    Button(onClick = vm::startGitHubLogin, enabled = !loggingIn) {
                        Text(if (loggingIn) I18n.t("accounts.logging_in") else "GitHub 登录")
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

private val skinImageCache = java.util.Collections.synchronizedMap(
    object : LinkedHashMap<String, ImageBitmap?>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap?>): Boolean {
            return size > 50
        }
    })

/** 异步加载皮肤图片，带内存缓存 */
@Composable
private fun SkinImage(url: String, sizePx: Int) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(skinImageCache[url]) }
    LaunchedEffect(url) {
        if (url.isEmpty()) { image = null; return@LaunchedEffect }
        if (skinImageCache.containsKey(url)) { image = skinImageCache[url]; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                if (url.isNullOrBlank()) return@withContext
                val bytes = URL(url).readBytes()
                val bmp = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                skinImageCache[url] = bmp
                image = bmp
            } catch (_: Throwable) {
                skinImageCache[url] = null
                image = null
            }
        }
    }
    if (image != null) {
        val bmp = image
        if (bmp == null) return
        Image(
            bitmap = bmp,
            contentDescription = "皮肤预览",
            modifier = Modifier.size(sizePx.dp).clip(RoundedCornerShape(8.dp))
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.size(sizePx.dp)
        ) {
            Icon(Icons.Filled.Person, "加载中",
                 modifier = Modifier.padding(sizePx.dp / 4))
        }
    }
}
