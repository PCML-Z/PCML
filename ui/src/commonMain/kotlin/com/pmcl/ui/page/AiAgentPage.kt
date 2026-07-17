package com.pmcl.ui.page

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.ai.AiConfig
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.launch

/**
 * AI 智能助手界面。
 *
 * 设计语言遵循 PMCL 规范：
 * - 消息气泡：半透明主色（我方）+ surfaceVariant（对方），14.dp 尾巴圆角
 * - 输入框：胶囊容器（20.dp 圆角 + surfaceVariant alpha 0.5）
 * - 顶部栏：tonalElevation = 2.dp，状态徽章 10.dp 圆角
 * - 空状态：大图标 + 透明度递减三段文字
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun AiAgentPage(vm: LauncherViewModel) {
    val ai = remember { vm.core.ai() }
    val scope = rememberCoroutineScope()

    data class ChatMsg(val text: String, val isUser: Boolean, val time: Long = System.currentTimeMillis())
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var toolStatus by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // 启动时绑定状态回调
    LaunchedEffect(Unit) {
        ai?.setStatusCallback { status -> toolStatus = status }
    }

    // 新消息或工具状态变化时滚动到底部
    LaunchedEffect(messages.size, toolStatus, sending) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 顶部栏
        ChatHeader(
            configured = ai?.isConfigured == true,
            showSettings = showSettings,
            onToggleSettings = { showSettings = !showSettings },
            onClear = {
                ai?.clearMemory()
                messages.clear()
                toolStatus = null
            }
        )

        // 设置面板（可折叠）
        AnimatedVisibility(
            visible = showSettings,
            enter = expandVertically(tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(300)),
            exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing)) + fadeOut(tween(200))
        ) {
            AiSettingsPanel(
                ai = ai,
                onConfigured = { ai?.setStatusCallback { status -> toolStatus = status } }
            )
        }

        // 消息列表
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(configured = ai?.isConfigured == true)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        items = messages,
                        key = { it.time }
                    ) { msg ->
                        MessageBubble(msg.text, msg.isUser)
                    }
                    if (toolStatus != null && sending) {
                        item(key = "tool_status") {
                            ToolStatusRow(toolStatus!!)
                        }
                    }
                }
            }
        }

        // 输入区（胶囊容器）
        ChatInputBar(
            text = input,
            onTextChange = { input = it },
            sending = sending,
            enabled = ai?.isConfigured == true && !sending,
            onSend = {
                val text = input.trim()
                if (text.isEmpty() || sending || ai == null || !ai.isConfigured) return@ChatInputBar
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
            }
        )
    }
}

/** 顶部栏 */
@Composable
private fun ChatHeader(
    configured: Boolean,
    showSettings: Boolean,
    onToggleSettings: () -> Unit,
    onClear: () -> Unit
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态点（在线/离线）
            Surface(
                color = if (configured) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                shape = CircleShape,
                modifier = Modifier.size(6.dp)
            ) {}
            Spacer(Modifier.width(8.dp))
            Column {
                Text("助手",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                Text(
                    if (configured) "在线" else "未配置",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (configured) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onToggleSettings,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.Tune, "设置",
                    modifier = Modifier.size(18.dp),
                    tint = if (showSettings) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Filled.CleaningServices, "清空对话",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 消息气泡（参考 FriendPage 设计） */
@Composable
private fun MessageBubble(text: String, isUser: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp
            ),
            modifier = Modifier.widthIn(max = 340.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** 工具执行状态行 */
@Composable
private fun ToolStatusRow(status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            strokeWidth = 2.dp,
            modifier = Modifier.size(12.dp)
        )
        Spacer(Modifier.width(6.dp))
        Surface(
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

/** 空状态 */
@Composable
private fun EmptyState(configured: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "有什么可以帮忙的？",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (configured) "试试：帮我找一下 1.20.1 的 Sodium"
                else "点击右上角设置按钮，配置 API Key 后即可开始使用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
    }
}

/** 胶囊输入栏（参考 FriendPage） */
@Composable
private fun ChatInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    sending: Boolean,
    enabled: Boolean,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("输入消息…", style = MaterialTheme.typography.bodyMedium)
                },
                enabled = enabled,
                singleLine = false,
                maxLines = 4,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )
            Spacer(Modifier.width(4.dp))
            SendButton(
                enabled = enabled && text.isNotBlank(),
                sending = sending,
                onClick = onSend
            )
        }
    }
}

/** 圆形发送按钮（带按压缩放反馈） */
@Composable
private fun SendButton(enabled: Boolean, sending: Boolean, onClick: () -> Unit) {
    val scale = if (enabled) 1f else 0.95f
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
        shape = CircleShape,
        modifier = Modifier
            .size(36.dp)
            .scale(scale),
        onClick = onClick,
        enabled = enabled && !sending
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (sending) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    "发送",
                    modifier = Modifier.size(18.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}

/** AI 设置面板 */
@Composable
private fun AiSettingsPanel(
    ai: com.pmcl.core.ai.AiManager?,
    onConfigured: () -> Unit
) {
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
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("模型配置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

            // 服务商选择
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
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
                                AiConfig.Provider.CUSTOM -> {}
                            }
                        },
                        label = { Text(when (p) {
                            AiConfig.Provider.DEEPSEEK -> "DeepSeek"
                            AiConfig.Provider.OPENAI -> "OpenAI"
                            AiConfig.Provider.CUSTOM -> "自定义"
                        }, fontSize = 11.sp) }
                    )
                }
            }

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
                }) { Text("保存并应用") }

                Spacer(Modifier.width(8.dp))
                if (ai?.isConfigured == true) {
                    OutlinedButton(onClick = {
                        ai.clearMemory()
                        savedMsg = "已清空记忆"
                    }) { Text("清空记忆") }
                }
                Spacer(Modifier.width(8.dp))
                savedMsg?.let {
                    Text(it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
