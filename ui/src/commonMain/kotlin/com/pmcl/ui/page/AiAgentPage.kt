package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pmcl.core.ai.AiConfig
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.launch

/**
 * AI 智能体聊天界面。
 *
 * 由标题栏按钮触发的独立窗口承载。包含：
 * - 消息列表（用户 + AI 对话气泡）
 * - 输入框与发送按钮
 * - 工具执行状态提示
 * - 可展开的设置面板（API Key / 模型 / 服务商）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun AiAgentPage(vm: LauncherViewModel) {
    val ai = remember { vm.core.ai() }
    val scope = rememberCoroutineScope()

    // 对话消息：true=用户，false=AI
    data class ChatMsg(val text: String, val isUser: Boolean, val time: Long = System.currentTimeMillis())
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var toolStatus by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    // 启动时绑定状态回调
    LaunchedEffect(Unit) {
        ai?.setStatusCallback { status ->
            toolStatus = status
        }
    }

    val scrollState = rememberScrollState()
    LaunchedEffect(messages.size, toolStatus) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部栏
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.AutoAwesome, "AI",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI 智能助手",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (ai != null && ai.isConfigured) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("已连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("未配置",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { showSettings = !showSettings }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Settings, "设置",
                        modifier = Modifier.size(16.dp),
                        tint = if (showSettings) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    ai?.clearMemory()
                    messages.clear()
                    toolStatus = null
                }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Clear, "清空对话", modifier = Modifier.size(16.dp))
                }
            }
        }

        // 设置面板（可折叠）
        AnimatedVisibility(
            visible = showSettings,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            AiSettingsPanel(vm = vm, onConfigured = {
                // 重新绑定状态回调（rebuild 后 tools 引用未变，但保险起见）
                ai?.setStatusCallback { status -> toolStatus = status }
            })
        }

        // 消息列表
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyConversationHint(ai?.isConfigured ?: false)
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    messages.forEach { msg ->
                        MessageBubble(msg.text, msg.isUser)
                    }
                    // 工具执行状态
                    if (toolStatus != null && sending) {
                        ToolStatusChip(toolStatus!!)
                    }
                }
            }
        }

        // 输入区
        Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息，例如：帮我安装 Sodium 优化模组") },
                    enabled = !sending && ai?.isConfigured == true,
                    singleLine = false,
                    maxLines = 4,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = null
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        val text = input.trim()
                        if (text.isEmpty() || sending || ai == null || !ai.isConfigured) return@FilledIconButton
                        messages.add(ChatMsg(text, true))
                        input = ""
                        sending = true
                        toolStatus = null
                        scope.launch {
                            ai.chatAsync(
                                text,
                                { reply ->
                                    messages.add(ChatMsg(reply, false))
                                    sending = false
                                    toolStatus = null
                                },
                                { err ->
                                    messages.add(ChatMsg("出错了: $err", false))
                                    sending = false
                                    toolStatus = null
                                }
                            )
                        }
                    },
                    enabled = !sending && ai?.isConfigured == true && input.isNotBlank(),
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (sending) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, "发送", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** 消息气泡 */
@Composable
private fun MessageBubble(text: String, isUser: Boolean) {
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primary
                      else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
    val icon = if (isUser) Icons.Outlined.Person else Icons.Outlined.SmartToy

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isUser) {
                Icon(icon, "AI",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
            }
            Surface(
                color = bubbleColor,
                shape = RoundedCornerShape(
                    topStart = 14.dp,
                    topEnd = 14.dp,
                    bottomStart = if (isUser) 14.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 14.dp
                ),
                modifier = Modifier.widthIn(max = 360.dp)
            ) {
                Text(text,
                    color = textColor,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
            }
            if (isUser) {
                Spacer(Modifier.width(4.dp))
                Icon(icon, "我",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** 工具执行状态提示 */
@Composable
private fun ToolStatusChip(status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

/** 空对话引导提示 */
@Composable
private fun EmptyConversationHint(configured: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.AutoAwesome, "AI",
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
            modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(12.dp))
        Text("PMCL AI 智能助手", style = MaterialTheme.typography.titleMedium,
             fontWeight = FontWeight.SemiBold,
             color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(
            if (configured) "我可以帮你搜索、下载、安装模组和加载器。\n试试说：帮我找一下 1.20.1 的 Sodium"
            else "请先点击右上角设置按钮配置 API Key",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

/** AI 设置面板 */
@Composable
private fun AiSettingsPanel(vm: LauncherViewModel, onConfigured: () -> Unit) {
    val ai = remember { vm.core.ai() }
    val currentConfig = remember { ai?.config ?: AiConfig.deepseekDefault() }

    var provider by remember { mutableStateOf(currentConfig.provider) }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var modelName by remember { mutableStateOf(currentConfig.modelName) }
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 模型配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            // 服务商选择
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("服务商", style = MaterialTheme.typography.labelMedium,
                     modifier = Modifier.width(70.dp))
                Spacer(Modifier.width(8.dp))
                AiConfig.Provider.values().forEach { p ->
                    FilterChip(
                        selected = provider == p,
                        onClick = {
                            provider = p
                            when (p) {
                                AiConfig.Provider.DEEPSEEK -> {
                                    modelName = "deepseek-chat"
                                    baseUrl = "https://api.deepseek.com/v1"
                                }
                                AiConfig.Provider.OPENAI -> {
                                    modelName = "gpt-4o-mini"
                                    baseUrl = "https://api.openai.com/v1"
                                }
                                AiConfig.Provider.CUSTOM -> { /* 用户自定义 */
                                }
                            }
                        },
                        label = { Text(when (p) {
                            AiConfig.Provider.DEEPSEEK -> "DeepSeek"
                            AiConfig.Provider.OPENAI -> "OpenAI"
                            AiConfig.Provider.CUSTOM -> "自定义"
                        }) },
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
            }

            // API Key
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = modelName,
                    onValueChange = { modelName = it },
                    label = { Text("模型名称") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    val cfg = currentConfig
                    cfg.provider = provider
                    cfg.apiKey = apiKey
                    cfg.modelName = modelName
                    cfg.baseUrl = baseUrl
                    ai?.configure(cfg)
                    savedMsg = "已保存"
                    onConfigured()
                }) {
                    Text("保存并应用")
                }
                Spacer(Modifier.width(8.dp))
                if (ai?.isConfigured == true) {
                    OutlinedButton(onClick = {
                        ai.clearMemory()
                        savedMsg = "已清空会话"
                    }) { Text("清空记忆") }
                }
                Spacer(Modifier.width(8.dp))
                savedMsg?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
