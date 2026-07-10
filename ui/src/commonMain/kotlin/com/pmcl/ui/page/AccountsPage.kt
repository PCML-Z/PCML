package com.pmcl.ui.page

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.auth.Account
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

@Composable
fun AccountsPage(vm: LauncherViewModel) {
    val account by vm.account.collectAsState()
    val status by vm.status.collectAsState()
    val deviceCode by vm.deviceCode.collectAsState()
    val loggingIn by vm.loggingIn.collectAsState()

    var username by remember { mutableStateOf("Steve") }
    var customSkinUrl by remember { mutableStateOf("") }
    var skinModel by remember { mutableStateOf("classic") }
    val scroll = rememberScrollState()

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll)) {
        Text("账号", style = MaterialTheme.typography.headlineSmall,
             fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // 当前账号 + 皮肤预览
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("当前账号", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                if (account != null) {
                    val acc = account!!
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
                        Text("退出登录")
                    }
                } else {
                    Text("未登录", color = MaterialTheme.colorScheme.outline)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 自定义皮肤（仅离线账号）
        if (account != null && account!!.getType() == Account.AccountType.OFFLINE) {
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
                Text("离线账号", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("用户名") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.loginOffline(username) },
                       enabled = username.isNotBlank() && !loggingIn) {
                    Text("登录")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 微软登录卡片
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("微软账号", style = MaterialTheme.typography.titleSmall,
                     fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("使用设备码流程登录，无需输入密码。\n微软账号自动同步 Mojang 服务器皮肤。",
                     style = MaterialTheme.typography.bodySmall,
                     color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(12.dp))

                if (deviceCode != null) {
                    val dc = deviceCode!!
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
                        Text(if (loggingIn) "登录中…" else "开始微软登录")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("状态：$status",
             style = MaterialTheme.typography.labelSmall,
             color = MaterialTheme.colorScheme.outline)
    }
}

// ============ 皮肤图片加载（带内存缓存） ============

private val skinImageCache = ConcurrentHashMap<String, ImageBitmap?>()

/** 异步加载皮肤图片，带内存缓存 */
@Composable
private fun SkinImage(url: String, sizePx: Int) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(skinImageCache[url]) }
    LaunchedEffect(url) {
        if (url.isEmpty()) { image = null; return@LaunchedEffect }
        if (skinImageCache.containsKey(url)) { image = skinImageCache[url]; return@LaunchedEffect }
        withContext(Dispatchers.IO) {
            try {
                val bytes = URL(url).readBytes()
                val bmp = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                skinImageCache[url] = bmp
                if (skinImageCache.size > 50) skinImageCache.clear()
                image = bmp
            } catch (_: Throwable) {
                skinImageCache[url] = null
                image = null
            }
        }
    }
    if (image != null) {
        Image(
            bitmap = image!!,
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
