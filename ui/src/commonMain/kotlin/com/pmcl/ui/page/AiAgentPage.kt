package com.pmcl.ui.page

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.ai.AiConfig
import com.pmcl.core.ai.AiManager
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.launch

/**
 * AI 智能体界面（参考 GPTAssistantDesktop 设计，适配桌面宽屏）。
 *
 * 布局：左侧会话历史栏 + 右侧聊天区
 * - 会话栏：新建对话按钮 + 会话列表（标题 + 消息数 + 删除）
 * - 聊天区：消息列表（用户右对齐紫蓝 / AI 左对齐卡片色 + Copy 按钮）+ 加载计时器 + 输入框
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAgentPage(vm: LauncherViewModel) {
    val ai = remember { vm.core.ai() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    data class ChatMsg(val text: String, val isUser: Boolean, val time: Long = System.currentTimeMillis())
    // 当前会话的消息列表（UI 层维护，发送/接收时追加）
    val messages = remember { mutableStateListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var toolStatus by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var loadingSeconds by remember { mutableStateOf(0.0) }
    var currentSessionId by remember { mutableStateOf(ai?.currentSessionId) }
    // 会话列表版本号，切换/创建/删除时递增以触发刷新
    var sessionVersion by remember { mutableStateOf(0) }

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

    // 加载计时器：发送中时每 70ms 递增
    LaunchedEffect(sending) {
        if (sending) {
            loadingSeconds = 0.0
            val startTime = System.currentTimeMillis()
            while (sending) {
                loadingSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                kotlinx.coroutines.delay(70)
            }
        }
    }

    // 切换会话时清空消息列表（实际消息由 ChatMemory 持有，UI 层只显示当前会话）
    // 这里简化处理：切换会话时清空 UI 消息（因为 LangChain4j 的 ChatMemory 不直接暴露消息内容给 UI）
    // 用户继续对话时新消息会追加显示
    fun refreshSessionList() { sessionVersion++ }

    fun switchProvider(p: AiConfig.Provider) {
        val cfg = ai?.config ?: AiConfig.deepseekDefault()
        when (p) {
            AiConfig.Provider.DEEPSEEK -> cfg.applyDeepseekDefaults()
            AiConfig.Provider.OPENAI -> cfg.applyOpenaiDefaults()
            AiConfig.Provider.CUSTOM -> {}
        }
        ai?.configure(cfg)
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 左侧会话历史栏
        SessionSidebar(
            ai = ai,
            currentSessionId = currentSessionId,
            sessionVersion = sessionVersion,
            onNewSession = {
                ai?.createSession()
                currentSessionId = ai?.currentSessionId
                messages.clear()
                refreshSessionList()
            },
            onSwitchSession = { sid ->
                ai?.switchSession(sid)
                currentSessionId = sid
                messages.clear()
            },
            onDeleteSession = { sid ->
                ai?.deleteSession(sid)
                currentSessionId = ai?.currentSessionId
                messages.clear()
                refreshSessionList()
            }
        )

        // 右侧聊天区
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // 顶部栏
            ChatHeader(
                showSettings = showSettings,
                onToggleSettings = { showSettings = !showSettings },
                onClear = {
                    ai?.clearMemory()
                    messages.clear()
                    toolStatus = null
                }
            )

            // 设置面板
            Surface(
                color = Color.Transparent,
                tonalElevation = 0.dp,
                modifier = Modifier.animateContentSize(tween(300, easing = FastOutSlowInEasing))
            ) {
                if (showSettings) {
                    AiSettingsPanel(
                        ai = ai,
                        onConfigured = { ai?.setStatusCallback { status -> toolStatus = status } }
                    )
                }
            }

            // 消息列表
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyState(
                        provider = ai?.config?.provider ?: AiConfig.Provider.DEEPSEEK,
                        onSwitchProvider = { switchProvider(it) }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom)
                    ) {
                        items(
                            items = messages,
                            key = { it.time }
                        ) { msg ->
                            MessageBubble(
                                text = msg.text,
                                isUser = msg.isUser,
                                clipboard = clipboard
                            )
                        }
                        if (sending) {
                            item(key = "loading") {
                                LoadingTimerRow(loadingSeconds)
                            }
                        }
                        if (toolStatus != null && sending) {
                            item(key = "tool_status") {
                                ToolStatusRow(toolStatus!!)
                            }
                        }
                    }
                }
            }

            // 输入区
            ChatInputSection(
                text = input,
                onTextChange = { input = it },
                sending = sending,
                enabled = ai?.isConfigured == true && !sending,
                provider = ai?.config?.provider ?: AiConfig.Provider.DEEPSEEK,
                onSwitchProvider = { switchProvider(it) },
                onSend = {
                    val text = input.trim()
                    if (text.isEmpty() || sending || ai == null || !ai.isConfigured) return@ChatInputSection
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
                                refreshSessionList()
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
}

// ============================================================
// 左侧会话历史栏
// ============================================================

@Composable
private fun SessionSidebar(
    ai: AiManager?,
    currentSessionId: String?,
    sessionVersion: Int,
    onNewSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit
) {
    // sessionVersion 用于触发重组读取最新列表
    val sessions = remember(sessionVersion) { ai?.listSessions() ?: emptyList() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.width(220.dp).fillMaxHeight()
    ) {
        Column {
            // 新建对话按钮
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNewSession
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Add, "新建对话",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(6.dp))
                    Text("新建对话",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))

            // 会话列表
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(items = sessions, key = { it.id }) { sess ->
                    SessionItem(
                        title = sess.title,
                        messageCount = sess.messageCount,
                        isActive = sess.id == currentSessionId,
                        onClick = { onSwitchSession(sess.id) },
                        onDelete = { onDeleteSession(sess.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionItem(
    title: String,
    messageCount: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text("$messageCount 条消息",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
            Icon(Icons.AutoMirrored.Outlined.Backspace, "删除",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

// ============================================================
// 顶部栏
// ============================================================

@Composable
private fun ChatHeader(
    showSettings: Boolean,
    onToggleSettings: () -> Unit,
    onClear: () -> Unit
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onToggleSettings, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Tune, "设置",
                    modifier = Modifier.size(18.dp),
                    tint = if (showSettings) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.CleaningServices, "清空对话",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ============================================================
// 消息气泡
// ============================================================

@Composable
private fun MessageBubble(
    text: String,
    isUser: Boolean,
    clipboard: androidx.compose.ui.platform.ClipboardManager
) {
    val bgColor = if (isUser) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
    val alignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(bgColor, RoundedCornerShape(8.dp))
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = Modifier.padding(16.dp)
            )
            // AI 消息底部 Copy 按钮
            if (!isUser) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .clickable { clipboard.setText(AnnotatedString(text)) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ContentCopy, "复制",
                        modifier = Modifier.size(14.dp),
                        tint = textColor.copy(alpha = 0.6f))
                    Spacer(Modifier.width(4.dp))
                    Text("复制",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.6f))
                }
            }
        }
    }
}

/** 加载计时器行 */
@Composable
private fun LoadingTimerRow(seconds: Double) {
    val formatted = "%.2f".format(seconds)
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                formatted,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.padding(16.dp)
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

// ============================================================
// 空状态
// ============================================================

@Composable
private fun EmptyState(
    provider: AiConfig.Provider,
    onSwitchProvider: (AiConfig.Provider) -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text("选择模型开始对话",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelCard(
                    name = "DeepSeek",
                    desc = "deepseek-chat",
                    selected = provider == AiConfig.Provider.DEEPSEEK,
                    onClick = { onSwitchProvider(AiConfig.Provider.DEEPSEEK) }
                )
                ModelCard(
                    name = "GPT",
                    desc = "gpt-4o-mini",
                    selected = provider == AiConfig.Provider.OPENAI,
                    onClick = { onSwitchProvider(AiConfig.Provider.OPENAI) }
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    name: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .width(130.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                shape = RoundedCornerShape(12.dp)
            )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface)
            Text(desc,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

// ============================================================
// 输入区
// ============================================================

@Composable
private fun ChatInputSection(
    text: String,
    onTextChange: (String) -> Unit,
    sending: Boolean,
    enabled: Boolean,
    provider: AiConfig.Provider,
    onSwitchProvider: (AiConfig.Provider) -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 模型选择行
        ModelSelectorBar(provider = provider, onSwitchProvider = onSwitchProvider)
        // 输入框
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
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
                    placeholder = { Text("输入消息…", style = MaterialTheme.typography.bodyMedium) },
                    enabled = enabled,
                    singleLine = false,
                    maxLines = 4,
                    shape = RoundedCornerShape(10.dp),
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
}

@Composable
private fun ModelSelectorBar(
    provider: AiConfig.Provider,
    onSwitchProvider: (AiConfig.Provider) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val modelName = when (provider) {
        AiConfig.Provider.DEEPSEEK -> "DeepSeek"
        AiConfig.Provider.OPENAI -> "GPT"
        AiConfig.Provider.CUSTOM -> "自定义"
    }

    Box {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { expanded = true }
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(modelName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Filled.ExpandMore, "切换模型",
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("DeepSeek") },
                onClick = { onSwitchProvider(AiConfig.Provider.DEEPSEEK); expanded = false }
            )
            DropdownMenuItem(
                text = { Text("GPT (OpenAI)") },
                onClick = { onSwitchProvider(AiConfig.Provider.OPENAI); expanded = false }
            )
        }
    }
}

@Composable
private fun SendButton(enabled: Boolean, sending: Boolean, onClick: () -> Unit) {
    val scale = if (enabled) 1f else 0.95f
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface,
        shape = CircleShape,
        modifier = Modifier.size(36.dp).scale(scale),
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
                    Icons.AutoMirrored.Filled.Send, "发送",
                    modifier = Modifier.size(18.dp),
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                )
            }
        }
    }
}

// ============================================================
// 设置面板
// ============================================================

@Composable
private fun AiSettingsPanel(ai: AiManager?, onConfigured: () -> Unit) {
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
