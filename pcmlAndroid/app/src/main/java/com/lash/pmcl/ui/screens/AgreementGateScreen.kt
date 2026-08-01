package com.lash.pmcl.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun AgreementGateScreen(onAgreed: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    var dialogContent by remember { mutableStateOf<AgreementContent?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 480.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.Security,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "欢迎使用 PMCL",
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "PMCL 是一款开源的 Minecraft 启动器,提供版本管理、模组管理、实例隔离与多账号支持。请在继续前阅读并同意以下协议。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    AgreementLink(
                        icon = Icons.Outlined.Article,
                        title = "用户协议",
                        onClick = { dialogContent = AgreementContent.USER_AGREEMENT },
                    )
                    AgreementLink(
                        icon = Icons.Outlined.Security,
                        title = "隐私政策",
                        onClick = { dialogContent = AgreementContent.PRIVACY_POLICY },
                    )
                    AgreementLink(
                        icon = Icons.Outlined.Gavel,
                        title = "第三方组件许可",
                        onClick = { dialogContent = AgreementContent.THIRD_PARTY },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { agreed = !agreed }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = agreed,
                    onCheckedChange = { agreed = it },
                )
                Text(
                    text = "我已阅读并同意以上协议",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onAgreed,
                enabled = agreed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("继续")
            }
        }
    }

    dialogContent?.let { content ->
        AlertDialog(
            onDismissRequest = { dialogContent = null },
            title = { Text(content.title) },
            text = {
                Text(
                    text = content.body,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { dialogContent = null }) {
                    Text("关闭")
                }
            },
        )
    }
}

@Composable
private fun AgreementLink(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "查看",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private enum class AgreementContent(val title: String, val body: String) {
    USER_AGREEMENT(
        "用户协议",
        """欢迎使用 PMCL 启动器。使用本应用即表示您同意以下条款:

1. PMCL 仅供个人学习与娱乐使用,不得用于任何商业用途。
2. 启动器提供 Minecraft 版本的下载、管理与启动功能,游戏本体版权归 Mojang AB 所有。
3. 用户应自行确保拥有合法的 Minecraft 账号,不得用于盗版或绕过正版验证。
4. 因使用本启动器产生的任何直接或间接损失,作者不承担任何责任。
5. 请勿将本启动器用于违反当地法律法规的用途。""",
    ),
    PRIVACY_POLICY(
        "隐私政策",
        """PMCL 隐私政策:

1. PMCL 在本地保存您的配置、账号信息与游戏数据,不会上传至任何服务器。
2. 启动器需要联网访问 Mojang 与官方源以下载游戏资源,这些请求仅包含必要的认证信息。
3. 启动器不会收集您的个人隐私数据,不会向第三方共享您的信息。
4. 您可随时在设置中清除本地缓存与账号数据。""",
    ),
    THIRD_PARTY(
        "第三方组件许可",
        """PMCL 使用了以下开源组件:

1. Kotlin - Apache License 2.0
2. Jetpack Compose - Apache License 2.0
3. Kotlinx Coroutines - Apache License 2.0
4. Material Components for Android - Apache License 2.0
5. Minecraft 启动相关资源 - 遵循 Mojang 最终用户许可协议

所有组件的版权归原作者所有。""",
    ),
}
