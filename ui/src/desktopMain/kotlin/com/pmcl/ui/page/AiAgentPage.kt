package com.pmcl.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.ui.ai.EmbeddedTerminal
import com.pmcl.ui.ai.OpenCodeDetector
import com.pmcl.ui.viewmodel.LauncherViewModel

/**
 * AI 智能体界面 —— 内嵌 OpenCode TUI。
 *
 * 启动时检测 PATH 中的 opencode 可执行文件：
 * - 已安装：直接进入内嵌终端运行 `opencode`
 * - 未安装：显示安装引导界面，提供平台对应的安装命令
 *
 * 工作目录默认为 Minecraft 实例目录（让 OpenCode 直接操作当前项目）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAgentPage(vm: LauncherViewModel) {
    val clipboard = LocalClipboardManager.current
    var installed by remember { mutableStateOf(OpenCodeDetector.isInstalled()) }
    var retryKey by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("OpenCode 智能体", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    if (!installed) {
                        // 安装完成后点击重新检测
                        TextButton(onClick = {
                            installed = OpenCodeDetector.isInstalled()
                            if (installed) retryKey++
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("重新检测")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (installed) {
                // 已安装：内嵌终端运行 opencode
                // 工作目录使用 PMCL 工作目录（Minecraft 实例根目录）
                EmbeddedTerminal(
                    command = "opencode",
                    args = emptyList(),
                    workingDirectory = vm.config.workDir.toAbsolutePath().toString(),
                    modifier = Modifier.fillMaxSize(),
                    key = retryKey
                )
            } else {
                // 未安装：引导界面
                InstallGuide(
                    onCopy = { cmd -> clipboard.setText(AnnotatedString(cmd)) }
                )
            }
        }
    }
}

/**
 * 安装引导界面：展示平台对应的安装命令，可一键复制。
 */
@Composable
private fun InstallGuide(onCopy: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        Icon(
            Icons.Filled.Terminal,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "未检测到 OpenCode",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "OpenCode 是开源 AI 编码代理，请选择以下方式之一安装后点击「重新检测」",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))

        OpenCodeDetector.installCommands().forEach { (title, cmd) ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onCopy(cmd) }) {
                            Icon(Icons.Filled.ContentCopy, "复制命令", modifier = Modifier.size(18.dp))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            cmd,
                            modifier = Modifier.padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text(
            "安装完成后，OpenCode 会要求配置 LLM 提供商 API 密钥。首次运行时输入 /connect 命令配置即可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
