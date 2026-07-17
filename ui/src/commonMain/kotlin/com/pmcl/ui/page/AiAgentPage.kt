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
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.ai.AiConfig
import com.pmcl.core.ai.AiManager
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.launch

/**
 * AI 智能助手界面。
 *
 * 设计参考 VS Code Continue 插件：
 * - 空状态居中显示模型选择卡片
 * - 输入框上方显示当前模型，点击可切换
 * - 消息气泡：半透明主色（我方）+ surfaceVariant（对方）
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    // 当前选中的服务商（用于模型选择器显示）
    var currentProvider by remember { mutableStateOf(ai?.config?.provider ?: AiConfig.Provider.DEEPSEEK) }

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

    // 切换服务商：应用默认配置（保留 apiKey），重建 AiManager
    fun switchProvider(p: AiConfig.Provider) {
        currentProvider = p
        val cfg = ai?.config ?: AiConfig.deepseekDefault()
        when (p) {
            AiConfig.Provider.DEEPSEEK -> cfg.applyDeepseekDefaults()
            AiConfig.Provider.OPENAI -> cfg.applyOpenaiDefaults()
            AiConfig.Provider.CUSTOM -> {}
        }
        ai?.configure(cfg)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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

        // 设置面板（可折叠）
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

        // 消息列表 / 空状态
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (messages.isEmpty()) {
                EmptyState(
                    provider = currentProvider,
                    configured = ai?.isConfigured == true,
                    onSwitchProvider = { switchProvider(it) }
                )
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

        // 输入区：模型选择行 + 胶囊输入框
        ChatInputSection(
            text = input,
            onTextChange = { input = it },
            sending = sending,
            enabled = ai?.isConfigured == true && !sending,
            provider = currentProvider,
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
    showSettings: Boolean,
    onToggleSettings: () -> Unit,
    onClear: () -> Unit
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

/** 消息气泡 */
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
            modifier = Modifier.widthIn(max = 360.dp)
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

/**
 * 空状态：居中显示模型选择卡片（VS Code Continue 风格）。
 * 点击 DeepSeek / GPT 卡片即可切换服务商。
 */
@Composable
private fun EmptyState(
    provider: AiConfig.Provider,
    configured: Boolean,
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
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
            if (!configured) {
                Text("点击右上角设置按钮配置 API Key",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
        }
    }
}

/** 空状态中的模型选择卡片 */
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

/**
 * 输入区：上方模型选择行 + 胶囊输入框。
 */
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 模型选择行
        ModelSelectorBar(provider = provider, onSwitchProvider = onSwitchProvider)
        // 胶囊输入框
        Surface(
            modifier = Modifier.fillMaxWidth(),
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
}

/**
 * 输入框上方的模型选择行：显示当前模型，点击下拉切换。
 */
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
                onClick = {
                    onSwitchProvider(AiConfig.Provider.DEEPSEEK)
                    expanded = false
                }
            )
            DropdownMenuItem(
                text = { Text("GPT (OpenAI)") },
                onClick = {
                    onSwitchProvider(AiConfig.Provider.OPENAI)
                    expanded = false
                }
            )
        }
    }
}

/** 圆形发送按钮 */
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
    ai: AiManager?,
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
