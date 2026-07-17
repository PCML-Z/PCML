package com.pmcl.ui.page

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.ai.AiConfig
import com.pmcl.core.ai.AiManager
import com.pmcl.core.ai.knowledge.KnowledgeEntry
import com.pmcl.core.ai.role.AgentRole
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.util.Base64
import java.util.function.Consumer

/**
 * AI 智能体界面 v2
 *
 * 功能：
 * - 角色系统（多角色切换 + 自定义系统提示词）
 * - 流式输出（逐 token 显示，优化交互体验）
 * - 多模态输入（文本 + 图片，需 vision 模型）
 * - 知识库 RAG（本地文档存储 + 关键词检索增强）
 * - 可扩展工具插件（ToolRegistry + @Tool 注解）
 * - 任务规划与执行（系统提示词内置规划指令）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAgentPage(vm: LauncherViewModel) {
    val ai = remember { vm.core.ai() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    data class ChatMsg(
        val text: String,
        val isUser: Boolean,
        val time: Long = System.currentTimeMillis(),
        val images: List<String> = emptyList()
    )

    val messages = remember { mutableStateListOf<ChatMsg>() }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var streamingText by remember { mutableStateOf("") }
    var toolStatus by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var loadingSeconds by remember { mutableStateOf(0.0) }
    var currentSessionId by remember { mutableStateOf(ai?.currentSessionId) }
    var sessionVersion by remember { mutableStateOf(0) }
    var currentProvider by remember { mutableStateOf(ai?.config?.provider ?: AiConfig.Provider.DEEPSEEK) }
    var currentRoleId by remember { mutableStateOf(ai?.currentRole?.id ?: "assistant") }
    var attachedImages by remember { mutableStateOf<List<String>>(emptyList()) }
    var roleVersion by remember { mutableStateOf(0) }
    var knowledgeVersion by remember { mutableStateOf(0) }
    var showRoleMenu by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val roles = remember(roleVersion) { ai?.roleManager?.roles ?: emptyList() }
    val currentRole = roles.find { it.id == currentRoleId }

    LaunchedEffect(Unit) {
        ai?.setStatusCallback { status -> toolStatus = status }
    }

    LaunchedEffect(messages.size, streamingText, toolStatus, sending) {
        if (messages.isNotEmpty() || streamingText.isNotEmpty()) {
            val totalItems = messages.size + (if (sending) 1 else 0)
            listState.animateScrollToItem(totalItems - 1)
        }
    }

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

    fun refreshSessionList() { sessionVersion++ }

    fun switchProvider(p: AiConfig.Provider) {
        val cfg = ai?.config ?: AiConfig.deepseekDefault()
        when (p) {
            AiConfig.Provider.DEEPSEEK -> cfg.applyDeepseekDefaults()
            AiConfig.Provider.OPENAI -> cfg.applyOpenaiDefaults()
            AiConfig.Provider.CUSTOM -> {}
        }
        ai?.configure(cfg)
        currentProvider = p
    }

    fun switchRole(role: AgentRole) {
        ai?.switchRole(role)
        currentRoleId = role.id
    }

    fun pickImages() {
        Thread {
            try {
                val dialog = java.awt.FileDialog(java.awt.Frame(), "选择图片", java.awt.FileDialog.LOAD)
                dialog.isMultipleMode = true
                dialog.setFilenameFilter { _, name ->
                    name.lowercase().matches(Regex(".*\\.(png|jpg|jpeg|gif|webp)"))
                }
                dialog.isVisible = true
                val files = dialog.files?.map { it.absolutePath } ?: emptyList()
                if (files.isNotEmpty()) {
                    attachedImages = (attachedImages + files).take(4)
                }
            } catch (e: Exception) {
                // ignore
            }
        }.apply { isDaemon = true; start() }
    }

    fun sendMessage() {
        val text = input.trim()
        if (text.isEmpty() || sending || ai == null || !ai.isConfigured) return

        messages.add(ChatMsg(text, true, images = attachedImages))
        val imageBase64 = attachedImages.map { path ->
            Base64.getEncoder().encodeToString(File(path).readBytes())
        }
        input = ""
        attachedImages = emptyList()
        sending = true
        streamingText = ""
        toolStatus = null

        ai.chatStream(text, imageBase64,
            Consumer { token -> streamingText += token },
            Consumer { fullText ->
                messages.add(ChatMsg(fullText, false))
                sending = false
                streamingText = ""
                toolStatus = null
                refreshSessionList()
            },
            Consumer { err ->
                messages.add(ChatMsg("出错了: $err", false))
                sending = false
                streamingText = ""
                toolStatus = null
            }
        )
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
            // 顶部栏（含角色选择器）
            ChatHeader(
                showSettings = showSettings,
                onToggleSettings = { showSettings = !showSettings },
                onClear = {
                    ai?.clearMemory()
                    messages.clear()
                    streamingText = ""
                    toolStatus = null
                },
                currentRole = currentRole,
                roles = roles,
                showRoleMenu = showRoleMenu,
                onShowRoleMenu = { showRoleMenu = it },
                onSwitchRole = { switchRole(it) }
            )

            // 设置面板
            Surface(
                color = Color.Transparent,
                tonalElevation = 0.dp,
                modifier = Modifier.animateContentSize(tween(300, easing = FastOutSlowInEasing))
            ) {
                if (showSettings) {
                    SettingsPanel(
                        ai = ai,
                        currentProvider = currentProvider,
                        onProviderChanged = { currentProvider = it },
                        roleVersion = roleVersion,
                        onRoleVersionChanged = { roleVersion++ },
                        knowledgeVersion = knowledgeVersion,
                        onKnowledgeVersionChanged = { knowledgeVersion++ }
                    )
                }
            }

            // 消息列表
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty() && streamingText.isEmpty()) {
                    EmptyState(
                        provider = currentProvider,
                        onSwitchProvider = { switchProvider(it) }
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.Bottom)
                    ) {
                        items(items = messages, key = { it.time }) { msg ->
                            MessageBubble(
                                text = msg.text,
                                isUser = msg.isUser,
                                images = msg.images,
                                clipboard = clipboard
                            )
                        }
                        // 流式输出气泡
                        if (sending && streamingText.isNotEmpty()) {
                            item(key = "streaming") {
                                StreamingBubble(streamingText)
                            }
                        }
                        if (sending && streamingText.isEmpty()) {
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
                enabled = !sending,
                provider = currentProvider,
                onSwitchProvider = { switchProvider(it) },
                attachedImages = attachedImages,
                onPickImages = { pickImages() },
                onRemoveImage = { idx ->
                    attachedImages = attachedImages.toMutableList().also { it.removeAt(idx) }
                },
                onSend = { sendMessage() }
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
    val sessions = remember(sessionVersion) { ai?.listSessions() ?: emptyList() }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.width(220.dp).fillMaxHeight()
    ) {
        Column {
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
// 顶部栏（含角色选择器）
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHeader(
    showSettings: Boolean,
    onToggleSettings: () -> Unit,
    onClear: () -> Unit,
    currentRole: AgentRole?,
    roles: List<AgentRole>,
    showRoleMenu: Boolean,
    onShowRoleMenu: (Boolean) -> Unit,
    onSwitchRole: (AgentRole) -> Unit
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 角色选择器
            Box {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onShowRoleMenu(true) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(getRoleIcon(currentRole?.icon), "角色",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(currentRole?.name ?: "助手",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium)
                        Icon(Icons.Filled.ArrowDropDown, "选择角色",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DropdownMenu(
                    expanded = showRoleMenu,
                    onDismissRequest = { onShowRoleMenu(false) }
                ) {
                    roles.forEach { role ->
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(getRoleIcon(role.icon), role.name,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Column {
                                        Text(role.name, style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium)
                                        Text(role.description, style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            },
                            onClick = { onSwitchRole(role); onShowRoleMenu(false) }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

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
// 设置面板（分页：模型 / 角色 / 知识库）
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsPanel(
    ai: AiManager?,
    currentProvider: AiConfig.Provider,
    onProviderChanged: (AiConfig.Provider) -> Unit,
    roleVersion: Int,
    onRoleVersionChanged: () -> Unit,
    knowledgeVersion: Int,
    onKnowledgeVersionChanged: () -> Unit
) {
    var tab by remember { mutableStateOf(0) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        tonalElevation = 1.dp
    ) {
        Column(Modifier.fillMaxWidth()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 },
                    text = { Text("模型配置", fontSize = 12.sp) })
                Tab(selected = tab == 1, onClick = { tab = 1 },
                    text = { Text("角色", fontSize = 12.sp) })
                Tab(selected = tab == 2, onClick = { tab = 2 },
                    text = { Text("知识库", fontSize = 12.sp) })
            }
            when (tab) {
                0 -> ModelConfigPanel(ai, currentProvider, onProviderChanged)
                1 -> RolePanel(ai, roleVersion, onRoleVersionChanged)
                2 -> KnowledgePanel(ai, knowledgeVersion, onKnowledgeVersionChanged)
            }
        }
    }
}

// ============================================================
// 模型配置面板
// ============================================================

@Composable
private fun ModelConfigPanel(
    ai: AiManager?,
    currentProvider: AiConfig.Provider,
    onProviderChanged: (AiConfig.Provider) -> Unit
) {
    val currentConfig = remember { ai?.config ?: AiConfig.deepseekDefault() }
    var apiKey by remember { mutableStateOf(currentConfig.apiKey) }
    var modelName by remember { mutableStateOf(currentConfig.modelName) }
    var baseUrl by remember { mutableStateOf(currentConfig.baseUrl) }
    var temperature by remember { mutableStateOf(currentConfig.temperature) }
    var maxTokens by remember { mutableStateOf(currentConfig.maxTokens) }
    var streamingEnabled by remember { mutableStateOf(currentConfig.isStreamingEnabled) }
    var visionEnabled by remember { mutableStateOf(currentConfig.isVisionEnabled) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Provider 选择
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AiConfig.Provider.values().forEach { p ->
                FilterChip(
                    selected = currentProvider == p,
                    onClick = {
                        when (p) {
                            AiConfig.Provider.DEEPSEEK -> {
                                currentConfig.applyDeepseekDefaults()
                                modelName = currentConfig.modelName
                                baseUrl = currentConfig.baseUrl
                                visionEnabled = currentConfig.isVisionEnabled
                            }
                            AiConfig.Provider.OPENAI -> {
                                currentConfig.applyOpenaiDefaults()
                                modelName = currentConfig.modelName
                                baseUrl = currentConfig.baseUrl
                                visionEnabled = currentConfig.isVisionEnabled
                            }
                            AiConfig.Provider.CUSTOM -> {}
                        }
                        onProviderChanged(p)
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

        // 高级参数
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Temperature", style = MaterialTheme.typography.labelSmall)
            Slider(
                value = temperature.toFloat(),
                onValueChange = { temperature = it.toDouble() },
                valueRange = 0f..2f,
                steps = 19,
                modifier = Modifier.weight(1f)
            )
            Text("%.1f".format(temperature),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.width(32.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = maxTokens.toString(),
                onValueChange = { maxTokens = it.toIntOrNull() ?: 2048 },
                label = { Text("最大 Token") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Switch(checked = streamingEnabled, onCheckedChange = { streamingEnabled = it })
                Text("流式输出", style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = visionEnabled, onCheckedChange = { visionEnabled = it })
            Spacer(Modifier.width(6.dp))
            Text("视觉模型（支持图片输入）", style = MaterialTheme.typography.labelSmall)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                currentConfig.provider = currentProvider
                currentConfig.apiKey = apiKey
                currentConfig.modelName = modelName
                currentConfig.baseUrl = baseUrl
                currentConfig.temperature = temperature
                currentConfig.maxTokens = maxTokens
                currentConfig.setStreamingEnabled(streamingEnabled)
                currentConfig.setVisionEnabled(visionEnabled)
                ai?.configure(currentConfig)
                savedMsg = "已保存"
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
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

// ============================================================
// 角色面板
// ============================================================

@Composable
private fun RolePanel(
    ai: AiManager?,
    roleVersion: Int,
    onRoleVersionChanged: () -> Unit
) {
    val roles = remember(roleVersion) { ai?.roleManager?.roles ?: emptyList() }
    val currentRoleId = remember(roleVersion) { ai?.currentRole?.id }
    var editingRole by remember { mutableStateOf<AgentRole?>(null) }
    var editPrompt by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("智能体角色", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)

        roles.forEach { role ->
            Surface(
                color = if (role.id == currentRoleId) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().clickable {
                    ai?.switchRole(role)
                    onRoleVersionChanged()
                }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(role.icon, fontSize = 20.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(role.name, style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium)
                        Text(role.description, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                    if (!role.isBuiltIn) {
                        IconButton(onClick = {
                            ai?.roleManager?.removeCustomRole(role.id)
                            onRoleVersionChanged()
                        }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // 自定义角色编辑
        if (editingRole != null) {
            HorizontalDivider()
            Text("编辑角色提示词", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
            OutlinedTextField(
                value = editPrompt,
                onValueChange = { editPrompt = it },
                label = { Text("系统提示词") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3, maxLines = 6,
                shape = RoundedCornerShape(10.dp)
            )
            Row {
                Button(onClick = {
                    editingRole?.let { role ->
                        role.systemPrompt = editPrompt
                        ai?.switchRole(role)
                        onRoleVersionChanged()
                    }
                    editingRole = null
                }) { Text("应用") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { editingRole = null }) { Text("取消") }
            }
        } else {
            // 显示当前角色的提示词
            currentRoleId?.let { cid ->
                val role = roles.find { it.id == cid }
                if (role != null) {
                    OutlinedButton(onClick = {
                        editingRole = role
                        editPrompt = role.systemPrompt
                    }) { Text("编辑当前角色提示词") }
                }
            }
        }
    }
}

// ============================================================
// 知识库面板
// ============================================================

@Composable
private fun KnowledgePanel(
    ai: AiManager?,
    knowledgeVersion: Int,
    onKnowledgeVersionChanged: () -> Unit
) {
    val entries = remember(knowledgeVersion) { ai?.knowledgeBase?.listAll() ?: emptyList() }
    var newTitle by remember { mutableStateOf("") }
    var newContent by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("知识库管理", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text("添加文档后，AI 会自动检索相关内容作为回答参考（RAG）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))

        // 添加表单
        OutlinedTextField(
            value = newTitle,
            onValueChange = { newTitle = it },
            label = { Text("文档标题") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        OutlinedTextField(
            value = newContent,
            onValueChange = { newContent = it },
            label = { Text("文档内容") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3, maxLines = 6,
            shape = RoundedCornerShape(10.dp)
        )
        Button(onClick = {
            if (newTitle.isNotBlank() && newContent.isNotBlank()) {
                ai?.knowledgeBase?.addDocument(newTitle, newContent, emptyList())
                newTitle = ""
                newContent = ""
                onKnowledgeVersionChanged()
            }
        }) { Text("添加文档") }

        HorizontalDivider()

        // 文档列表
        Text("已有文档 (${entries.size})",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium)
        entries.forEach { entry ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium, maxLines = 1)
                        Text(entry.content.take(60) + if (entry.content.length > 60) "…" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = {
                        ai?.knowledgeBase?.removeDocument(entry.id)
                        onKnowledgeVersionChanged()
                    }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.Delete, "删除", modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    }
                }
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
    images: List<String> = emptyList(),
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
            // 用户消息显示图片
            if (isUser && images.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    images.forEach { path ->
                        ImageThumbnailSmall(path)
                    }
                }
            }
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

/** 流式输出气泡：实时显示逐 token 到达的文本 */
@Composable
private fun StreamingBubble(text: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Text(
                text + "\u2588",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

/** 加载计时器行 */
@Composable
private fun LoadingTimerRow(seconds: Double) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                "%.2f".format(seconds),
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
// 输入区（含图片上传）
// ============================================================

@Composable
private fun ChatInputSection(
    text: String,
    onTextChange: (String) -> Unit,
    sending: Boolean,
    enabled: Boolean,
    provider: AiConfig.Provider,
    onSwitchProvider: (AiConfig.Provider) -> Unit,
    attachedImages: List<String>,
    onPickImages: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 模型选择行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ModelSelectorBar(provider = provider, onSwitchProvider = onSwitchProvider)
            Spacer(Modifier.weight(1f))
            // 图片上传按钮
            IconButton(onClick = onPickImages, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.AttachFile, "上传图片",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // 图片预览
        if (attachedImages.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                attachedImages.forEachIndexed { idx, path ->
                    ImageThumbnail(path) { onRemoveImage(idx) }
                }
            }
        }

        // 输入框 + 发送按钮
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("输入消息…", style = MaterialTheme.typography.bodyMedium) },
            enabled = enabled,
            singleLine = false,
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
            trailingIcon = {
                SendButton(
                    enabled = enabled && text.isNotBlank(),
                    sending = sending,
                    onClick = onSend
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun ModelSelectorBar(
    provider: AiConfig.Provider,
    onSwitchProvider: (AiConfig.Provider) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilterChip(
            selected = provider == AiConfig.Provider.DEEPSEEK,
            onClick = { onSwitchProvider(AiConfig.Provider.DEEPSEEK) },
            label = { Text("DeepSeek", style = MaterialTheme.typography.labelSmall) }
        )
        FilterChip(
            selected = provider == AiConfig.Provider.OPENAI,
            onClick = { onSwitchProvider(AiConfig.Provider.OPENAI) },
            label = { Text("GPT", style = MaterialTheme.typography.labelSmall) }
        )
    }
}

@Composable
private fun SendButton(enabled: Boolean, sending: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled && !sending,
        modifier = Modifier.size(36.dp)
    ) {
        if (sending) {
            CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            Icon(
                Icons.AutoMirrored.Filled.Send, "发送",
                modifier = Modifier.size(18.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

// ============================================================
// 图片缩略图
// ============================================================

@Composable
private fun ImageThumbnail(path: String, onRemove: () -> Unit) {
    val bitmap = remember(path) {
        try {
            val bytes = File(path).readBytes()
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Box(modifier = Modifier.size(64.dp)) {
            Image(
                bitmap = bitmap,
                contentDescription = "图片",
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            )
            Surface(
                color = MaterialTheme.colorScheme.error,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clickable(onClick = onRemove)
            ) {
                Icon(Icons.Filled.Close, "移除",
                    modifier = Modifier.size(12.dp).align(Alignment.Center),
                    tint = MaterialTheme.colorScheme.onError)
            }
        }
    }
}

@Composable
private fun ImageThumbnailSmall(path: String) {
    val bitmap = remember(path) {
        try {
            val bytes = File(path).readBytes()
            SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = "图片",
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
        )
    }
}

// ============================================================
// 角色图标映射
// ============================================================

private fun getRoleIcon(iconName: String?): ImageVector {
    return when (iconName) {
        "smart_toy" -> Icons.Filled.SmartToy
        "inventory_2" -> Icons.Filled.Inventory2
        "build" -> Icons.Filled.Build
        "palette" -> Icons.Filled.Palette
        else -> Icons.Filled.SmartToy
    }
}
