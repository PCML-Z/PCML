package com.pmcl.ui.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.agent.AgentModel
import com.pmcl.core.agent.MacDictation
import com.pmcl.core.i18n.I18n
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.pmcl.ui.animation.MotionTokens
import com.pmcl.ui.animation.hoverPressScale
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Bar = Color(0xF21C1C1E)
private val PlusCircle = Color(0xFF3A3A3C)
private val Ink = Color(0xFFF2F2F7)
private val Muted = Color(0xFF8E8E93)
private val ModelInk = Color(0xFFD1D1D6)
private val Hairline = Color(0x29FFFFFF)
private val BarShape = RoundedCornerShape(28.dp)
private val BubbleShape = RoundedCornerShape(20.dp)

/**
 * 调度中心底部的输入条。模型名称只显示，接入在设置里改。
 * 点麦克风听写，文字进输入框，回车才发送。
 * 一次只走一步：想法单独一行，工具跑完再想下一步，最后一句才是结果。
 */
@Composable
fun AgentPanel(vm: LauncherViewModel, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    var endpoint by remember { mutableStateOf(AgentModel.Endpoint("", "", "")) }
    var field by remember { mutableStateOf(TextFieldValue("")) }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var doing by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf("") }
    val turns = remember { mutableStateListOf<AgentModel.Turn>() }
    val scope = rememberCoroutineScope()
    val voiceGen = remember { AtomicInteger() }
    val voiceBase = remember { AtomicReference("") }
    val voiceTail = remember { AtomicReference("") }
    val voiceAlive = remember { AtomicReference(AtomicBoolean(false)) }
    val voiceSession = remember { AtomicReference<MacDictation.Session?>(null) }

    LaunchedEffect(vm.config.workDir) {
        endpoint = withContext(Dispatchers.IO) { AgentModel.load(vm.config.workDir) }
    }

    fun applySpoken(text: String) {
        val base = voiceBase.get()
        val tail = voiceTail.get()
        val merged = MacDictation.join(base, text, tail)
        val caret = MacDictation.caret(base, text).coerceIn(0, merged.length)
        field = TextFieldValue(merged, TextRange(caret))
    }

    fun send() {
        val text = field.text.trim()
        if (busy || text.isEmpty()) return
        val setting = LauncherAgent.directSetting(text)
        if (setting != null) {
            field = TextFieldValue("")
            error = ""
            turns.add(AgentModel.Turn("user", text))
            busy = true
            scope.launch {
                try {
                    val thought = if (setting.enabled == false) "关掉这项设置" else "打开这项设置"
                    turns.add(AgentModel.Turn("thought", thought))
                    var leave = false
                    val answer = LauncherAgent.applySetting(vm, setting) { leave = true }
                    if (answer.isNotBlank()) turns.add(AgentModel.Turn("assistant", answer))
                    if (leave) onDismiss()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: I18n.t("launch.agent_failed")
                } finally {
                    busy = false
                    doing = ""
                }
            }
            return
        }
        if (!endpoint.ready()) {
            error = I18n.t("launch.agent_need")
            return
        }
        field = TextFieldValue("")
        error = ""
        turns.add(AgentModel.Turn("user", text))
        val history = mutableListOf(AgentModel.Exchange("user", text, null, null))
        busy = true
        scope.launch {
            try {
                var answer = ""
                var leave = false
                for (round in 0 until 8) {
                    doing = ""
                    val step = withContext(Dispatchers.IO) {
                        AgentModel.step(endpoint, history, LauncherAgent.TOOLS)
                    }
                    if (step.calls.isEmpty()) {
                        val spoken = step.content.ifBlank { step.thought }
                        if (step.thought.isNotBlank() && spoken != step.thought) {
                            turns.add(AgentModel.Turn("thought", step.thought))
                        }
                        answer = spoken
                        break
                    }
                    val note = step.thought.ifBlank {
                        step.calls.joinToString("，") { LauncherAgent.label(it.name) }
                    }
                    history.add(AgentModel.Exchange("assistant", note, null, step.calls))
                    if (note.isNotBlank()) turns.add(AgentModel.Turn("thought", note))
                    for (call in step.calls) {
                        doing = LauncherAgent.label(call.name)
                        val result = LauncherAgent.run(call, vm) { leave = true }
                        history.add(AgentModel.Exchange("tool", result, call.id, null))
                    }
                }
                if (answer.isNotBlank()) turns.add(AgentModel.Turn("assistant", answer))
                if (leave) onDismiss()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: I18n.t("launch.agent_failed")
            } finally {
                busy = false
                doing = ""
            }
        }
    }

    fun stopVoice(dropResult: Boolean) {
        if (dropResult) voiceGen.incrementAndGet()
        voiceAlive.get().set(false)
        voice = ""
        voiceSession.getAndSet(null)?.stop()
    }

    fun startVoice() {
        stopVoice(true)
        val alive = AtomicBoolean(true)
        voiceAlive.set(alive)
        val gen = voiceGen.incrementAndGet()
        val text = field.text
        val min = minOf(field.selection.start, field.selection.end).coerceIn(0, text.length)
        val max = maxOf(field.selection.start, field.selection.end).coerceIn(0, text.length)
        voiceBase.set(text.substring(0, min))
        voiceTail.set(text.substring(max))
        voice = "preparing"
        error = ""
        scope.launch {
            try {
                val created = withContext(Dispatchers.IO) {
                    MacDictation.start(object : MacDictation.Listener {
                        override fun onPartial(text: String) {
                            if (text.isBlank() || !alive.get()) return
                            scope.launch {
                                if (gen != voiceGen.get() || !alive.get()) return@launch
                                voice = "listening"
                                applySpoken(text)
                            }
                        }

                        override fun onFinal(text: String) {
                            scope.launch {
                                if (!voiceGen.compareAndSet(gen, gen + 1)) return@launch
                                if (text.isNotBlank()) applySpoken(text)
                                voice = ""
                                voiceSession.getAndSet(null)?.stop()
                            }
                        }

                        override fun onError(code: String) {
                            scope.launch {
                                if (!voiceGen.compareAndSet(gen, gen + 1)) return@launch
                                voice = ""
                                voiceSession.getAndSet(null)?.stop()
                                error = voiceMessage(code)
                            }
                        }

                        override fun onStatus(status: String) {
                            if (!alive.get()) return
                            scope.launch {
                                if (gen != voiceGen.get() || !alive.get()) return@launch
                                if (status == "listening") voice = "listening"
                            }
                        }
                    })
                }
                voiceSession.set(created)
                if (!alive.get() || gen != voiceGen.get()) {
                    if (voiceSession.get() === created) voiceSession.compareAndSet(created, null)
                    created.stop()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == voiceGen.get()) {
                    voice = ""
                    error = voiceMessage(e.message)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopVoice(true) }
    }

    val transcript = rememberScrollState()
    val showTranscript = turns.isNotEmpty() || busy || error.isNotEmpty()
    LaunchedEffect(turns.size, busy, error) {
        if (!showTranscript) return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        transcript.animateScrollTo(transcript.maxValue)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = tween(
                    durationMillis = MotionTokens.DURATION_MEDIUM,
                    easing = MotionTokens.EasingEmphasizedDecelerate
                )
            )
    ) {
        if (showTranscript) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(transcript)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                turns.forEachIndexed { index, turn ->
                    key(index) {
                        if (turn.role == "thought") {
                            RisingBubble(mine = false) { ThoughtStep(turn.text) }
                        } else {
                            RisingBubble(mine = turn.role == "user") {
                                ReplyBubble(turn.text, mine = turn.role == "user")
                            }
                        }
                    }
                }
                if (busy) {
                    RisingBubble(mine = false) {
                        ThinkingBubble(doing)
                    }
                }
                if (error.isNotEmpty()) {
                    RisingBubble(mine = false) {
                        ReplyBubble(error, mine = false, problem = true)
                    }
                }
            }
        }
        ComposerBar(
            field = field,
            onField = { field = it },
            voice = voice,
            hasHistory = turns.isNotEmpty(),
            model = if (endpoint.ready()) endpoint.model else I18n.t("launch.agent_unset"),
            menu = menu,
            onMenu = { menu = it },
            endpointReady = endpoint.ready(),
            endpointModel = endpoint.model,
            onOpenSettings = onOpenSettings,
            onClear = {
                stopVoice(true)
                turns.clear()
                field = TextFieldValue("")
                error = ""
            },
            onMic = {
                if (voice.isNotEmpty()) stopVoice(false) else startVoice()
            },
            onSend = {
                if (voice.isNotEmpty()) stopVoice(true)
                send()
            }
        )
    }
}

private fun voiceMessage(code: String?): String {
    val key = when (code) {
        "speech_denied", "mic_denied" -> "launch.agent_voice_denied"
        "speech_unavailable", "not_mac" -> "launch.agent_voice_unavailable"
        else -> "launch.agent_voice_failed"
    }
    return I18n.t(key)
}

@Composable
private fun ComposerBar(
    field: TextFieldValue,
    onField: (TextFieldValue) -> Unit,
    voice: String,
    hasHistory: Boolean,
    model: String,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
    endpointReady: Boolean,
    endpointModel: String,
    onOpenSettings: () -> Unit,
    onClear: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit
) {
    val plus = remember { MutableInteractionSource() }
    val plusHovered by plus.collectIsHoveredAsState()
    val plusPressed by plus.collectIsPressedAsState()
    val modelChip = remember { MutableInteractionSource() }
    val modelHovered by modelChip.collectIsHoveredAsState()
    val chipColor by animateColorAsState(
        targetValue = if (modelHovered) Color.White.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = tween(MotionTokens.DURATION_SHORT, easing = MotionTokens.EasingStandard),
        label = "modelChip"
    )
    val listening = voice.isNotEmpty()
    val mic = remember { MutableInteractionSource() }
    val micHovered by mic.collectIsHoveredAsState()
    val micPressed by mic.collectIsPressedAsState()
    val pulse = rememberInfiniteTransition(label = "micPulse")
    val wave by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = MotionTokens.EasingStandard),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micWave"
    )
    val micTint by animateColorAsState(
        targetValue = if (listening) Color(0xFFE23B34) else Color(0xFF1C1C1E),
        animationSpec = tween(MotionTokens.DURATION_SHORT, easing = MotionTokens.EasingEmphasized),
        label = "micTint"
    )
    val chevron by animateFloatAsState(
        targetValue = if (menu) 180f else 0f,
        animationSpec = tween(MotionTokens.DURATION_SHORT, easing = MotionTokens.EasingEmphasized),
        label = "modelChevron"
    )
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            // 桌面端 elevation 按矩形绘制，圆角下面会露出方块。
            .border(1.dp, Hairline, BarShape)
            .background(Bar, BarShape)
            .clip(BarShape)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .hoverPressScale(hovered = plusHovered, pressed = plusPressed, hoverScale = 1.06f, pressScale = 0.92f)
                .clip(CircleShape)
                .background(PlusCircle)
                .hoverable(plus)
                .clickable(
                    interactionSource = plus,
                    indication = null,
                    onClick = onClear
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = I18n.t("launch.agent_new"),
                tint = Ink,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.size(10.dp))
        BasicTextField(
            value = field,
            onValueChange = onField,
            readOnly = listening,
            singleLine = true,
            textStyle = TextStyle(color = Ink, fontSize = 16.sp),
            cursorBrush = SolidColor(Ink),
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                        onSend()
                        true
                    } else {
                        false
                    }
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (field.text.isEmpty()) {
                        Text(
                            when (voice) {
                                "listening" -> I18n.t("launch.agent_listening")
                                "preparing" -> I18n.t("launch.agent_voice_preparing")
                                else -> if (hasHistory) I18n.t("launch.agent_follow") else I18n.t("launch.agent_ask")
                            },
                            color = Muted,
                            fontSize = 16.sp
                        )
                    }
                    inner()
                }
            }
        )
        Spacer(Modifier.size(8.dp))
        Box {
            Row(
                Modifier
                    .widthIn(max = 180.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(chipColor)
                    .hoverable(modelChip)
                    .clickable(
                        interactionSource = modelChip,
                        indication = null
                    ) { onMenu(true) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    model,
                    color = ModelInk,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = ModelInk,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevron }
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
                if (endpointReady) {
                    DropdownMenuItem(
                        text = { Text(endpointModel) },
                        onClick = { onMenu(false) }
                    )
                }
                DropdownMenuItem(
                    text = { Text(I18n.t("launch.agent_manage")) },
                    onClick = {
                        onMenu(false)
                        onOpenSettings()
                    }
                )
            }
        }
        Spacer(Modifier.size(10.dp))
        Box(
            Modifier
                .size(36.dp)
                .hoverPressScale(hovered = micHovered, pressed = micPressed, hoverScale = 1.06f, pressScale = 0.92f)
                .border(
                    2.dp,
                    if (listening) Color(0xFFE23B34).copy(alpha = wave) else Color.Transparent,
                    CircleShape
                )
                .clip(CircleShape)
                .background(Color.White)
                .hoverable(mic)
                .clickable(
                    interactionSource = mic,
                    indication = null,
                    onClick = onMic
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = if (listening) I18n.t("launch.agent_voice_stop") else I18n.t("launch.agent_voice"),
                tint = micTint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 回复只在第一次进入组合时从输入条方向升起。清掉对话时直接离开。 */
@Composable
private fun RisingBubble(mine: Boolean, content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    val ease = MotionTokens.EasingEmphasizedDecelerate
    AnimatedVisibility(
        visibleState = shown,
        enter = fadeIn(tween(MotionTokens.DURATION_MEDIUM, easing = ease)) +
            slideInVertically(
                animationSpec = tween(MotionTokens.DURATION_MEDIUM, easing = ease),
                initialOffsetY = { it / 4 }
            ) +
            scaleIn(
                animationSpec = tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.EasingEmphasized),
                initialScale = 0.94f,
                transformOrigin = if (mine) TransformOrigin(1f, 1f) else TransformOrigin(0f, 1f)
            ),
        exit = fadeOut(tween(MotionTokens.DURATION_SHORT, easing = MotionTokens.EasingEmphasizedAccelerate))
    ) {
        content()
    }
}

/** 一步的想法。只有圆点和一行字，不做成对话气泡。 */
@Composable
private fun ThoughtStep(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Muted)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text,
            color = Muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ReplyBubble(text: String, mine: Boolean, problem: Boolean = false) {
    BubbleShell(mine = mine, problem = problem) {
        Text(
            text,
            color = if (problem) Color(0xFFFFB4AB) else Ink,
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
    }
}

@Composable
private fun ThinkingBubble(label: String) {
    BubbleShell(mine = false) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ThinkingDots()
            if (label.isNotBlank()) {
                Spacer(Modifier.size(10.dp))
                Text(
                    label,
                    color = ModelInk,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ThinkingDots() {
    val transition = rememberInfiniteTransition(label = "agentThink")
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.28f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 520, easing = MotionTokens.EasingStandard),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 160)
                ),
                label = "dot$index"
            )
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .clip(CircleShape)
                    .background(Ink)
            )
        }
    }
}

@Composable
private fun BubbleShell(mine: Boolean, problem: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxWidth(),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        val fill = when {
            problem -> Color(0xFF3A2426)
            mine -> Color(0xFF3A3A3C)
            else -> Color(0xF01C1C1E)
        }
        Box(
            Modifier
                .widthIn(max = if (mine) 420.dp else 520.dp)
                .border(1.dp, Hairline, BubbleShape)
                .background(fill, BubbleShape)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            content()
        }
    }
}
