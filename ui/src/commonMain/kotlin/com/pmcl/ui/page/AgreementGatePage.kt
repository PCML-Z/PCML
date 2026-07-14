package com.pmcl.ui.page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * 协议同意门控页：首次打开 PMCL 时显示。
 *
 * 用户必须依次阅读并勾选同意《用户协议》《免责协议》《PMCL 软件技术许可证》后，
 * 才能点击"同意并继续"按钮进入主程序。不同意则无法使用。
 */
@Composable
fun AgreementGatePage(vm: LauncherViewModel) {
    val scrollState = rememberScrollState()

    // 三项协议的勾选状态
    var agreeUserAgreement by remember { mutableStateOf(false) }
    var agreeDisclaimer by remember { mutableStateOf(false) }
    var agreeLicense by remember { mutableStateOf(false) }

    // 当前展开查看的协议（null=未展开任何）
    var expandedDoc by remember { mutableStateOf<String?>(null) }

    val allAgreed = agreeUserAgreement && agreeDisclaimer && agreeLicense

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 标题
        Icon(
            Icons.Filled.Gavel,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "欢迎使用 PMCL",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "在开始使用之前，请您仔细阅读并同意以下协议。\n这些协议明确了您与开发者之间的权利与义务。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        // ===== 协议 1：用户协议 =====
        AgreementItem(
            icon = Icons.Filled.Gavel,
            title = "PMCL 用户协议",
            description = "规定了您使用本软件的权利、义务及行为规范",
            resourceName = "USER_AGREEMENT.txt",
            agreed = agreeUserAgreement,
            onAgreedChange = { agreeUserAgreement = it },
            expanded = expandedDoc == "user_agreement",
            onExpandToggle = {
                expandedDoc = if (expandedDoc == "user_agreement") null else "user_agreement"
            }
        )

        Spacer(Modifier.height(12.dp))

        // ===== 协议 2：免责协议 =====
        AgreementItem(
            icon = Icons.Filled.Shield,
            title = "PMCL 免责协议",
            description = "明确了开发者在各类情形下的责任范围与免责条款",
            resourceName = "DISCLAIMER.txt",
            agreed = agreeDisclaimer,
            onAgreedChange = { agreeDisclaimer = it },
            expanded = expandedDoc == "disclaimer",
            onExpandToggle = {
                expandedDoc = if (expandedDoc == "disclaimer") null else "disclaimer"
            }
        )

        Spacer(Modifier.height(12.dp))

        // ===== 协议 3：软件技术许可证 =====
        AgreementItem(
            icon = Icons.Filled.Article,
            title = "PMCL 软件技术许可证 v1.1",
            description = "本软件的版权授权条款（中文为权威版本）",
            resourceName = "LICENSE.zh.txt",
            agreed = agreeLicense,
            onAgreedChange = { agreeLicense = it },
            expanded = expandedDoc == "license",
            onExpandToggle = {
                expandedDoc = if (expandedDoc == "license") null else "license"
            }
        )

        Spacer(Modifier.height(24.dp))

        // ===== 操作按钮 =====
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    // 不同意则退出应用
                    kotlin.system.exitProcess(0)
                }
            ) { Text("不同意，退出") }

            Button(
                onClick = { vm.acceptAgreements() },
                enabled = allAgreed
            ) {
                Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("同意并继续")
            }
        }

        Spacer(Modifier.height(8.dp))
        if (!allAgreed) {
            Text(
                "请勾选同意全部三项协议后方可继续",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * 单个协议项：图标+标题+描述+查看全文按钮+同意勾选框，可展开查看全文。
 */
@Composable
private fun AgreementItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    resourceName: String,
    agreed: Boolean,
    onAgreedChange: (Boolean) -> Unit,
    expanded: Boolean,
    onExpandToggle: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current

    // 懒加载文档文本
    val docText by remember(resourceName) {
        mutableStateOf(
            runCatching {
                Thread.currentThread().contextClassLoader
                    ?.getResourceAsStream(resourceName)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "文档未找到：$resourceName"
            }.getOrElse { "加载失败：${it.message}" }
        )
    }

    val innerScroll = rememberScrollState()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (agreed) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            // 标题行：图标 + 标题 + 描述 + 勾选
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = agreed,
                    onClick = { onAgreedChange(!agreed) },
                    label = { Text(if (agreed) "已同意" else "未同意") },
                    leadingIcon = if (agreed) {
                        { Icon(Icons.Filled.CheckCircle, null, Modifier.size(16.dp)) }
                    } else null
                )
            }

            Spacer(Modifier.height(8.dp))

            // 展开/收起 + 复制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onExpandToggle) {
                    Text(if (expanded) "收起全文" else "查看全文")
                }
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(docText))
                }) { Text("复制全文") }
            }

            // 展开时显示全文
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
                ) {
                    Text(
                        text = docText,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .verticalScroll(innerScroll)
                            .padding(12.dp)
                    )
                }
            }
        }
    }
}
