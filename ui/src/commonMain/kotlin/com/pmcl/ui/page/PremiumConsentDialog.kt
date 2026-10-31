package com.pmcl.ui.page

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n

/**
 * 正版账户验证前的知情同意弹窗，三步走：
 * 1. 《免责协议》——滚到末尾并勾选；
 * 2. 《自愿授权协议》——滚到末尾并勾选；
 * 3. 确认授权——再次逐项确认授权范围，勾选后才能继续。
 *
 * 全程不做默认勾选，也不提供「一键全部同意」。
 */
private const val STEP_COUNT = 3

@Composable
fun PremiumConsentDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    var step by remember { mutableStateOf(0) }
    var disclaimerChecked by remember { mutableStateOf(false) }
    var authorizationChecked by remember { mutableStateOf(false) }
    var confirmChecked by remember { mutableStateOf(false) }
    val disclaimerScroll = rememberScrollState()
    val authorizationScroll = rememberScrollState()
    // 未展示过的步骤，其 ScrollState 还没挂到可滚动容器上，canScrollForward 恒为 false，
    // 会被误判成「已读完」。等该步真正布局过一帧后才承认它的读数。
    var disclaimerLaidOut by remember { mutableStateOf(false) }
    var authorizationLaidOut by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        withFrameNanos { }
        if (step == 0) disclaimerLaidOut = true
        if (step == 1) authorizationLaidOut = true
    }

    // 滚动到末尾才允许勾选：canScrollForward 为 false 表示后面已无可滚动内容
    val disclaimerRead = disclaimerLaidOut && !disclaimerScroll.canScrollForward
    val authorizationRead = authorizationLaidOut && !authorizationScroll.canScrollForward

    // 正文按段存放在 consent.*.body.NN，逐段拼接；缺号即结束
    val disclaimerText = remember { agreementText("consent.disclaimer.body.") }
    val authorizationText = remember { agreementText("consent.authorization.body.") }
    val stepProgress by animateFloatAsState(
        targetValue = (step + 1f) / STEP_COUNT,
        animationSpec = tween(280),
        label = "consentStepProgress"
    )

    val canContinue = when (step) {
        0 -> disclaimerRead && disclaimerChecked
        1 -> authorizationRead && authorizationChecked
        else -> confirmChecked
    }
    val title = when (step) {
        0 -> I18n.t("consent.tab.disclaimer")
        1 -> I18n.t("consent.tab.authorization")
        else -> I18n.t("consent.confirm.title")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            var appeared by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { appeared = true }
            val enterAlpha by animateFloatAsState(
                if (appeared) 1f else 0f,
                tween(240),
                label = "consentEnterAlpha"
            )
            val enterShift by animateFloatAsState(
                if (appeared) 0f else 16f,
                tween(240),
                label = "consentEnterShift"
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = enterAlpha
                        translationY = enterShift
                    }
            ) {
                Text(
                    if (step == 0) I18n.t("consent.step", step + 1, STEP_COUNT) + " · " + I18n.t("consent.subtitle")
                    else I18n.t("consent.step", step + 1, STEP_COUNT),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { stepProgress }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))

                // 步骤切换：按前进方向左右滑动 + 淡入淡出
                AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(180)) { -it / 4 } + fadeOut(tween(140)))
                        } else {
                            (slideInHorizontally(tween(220)) { -it / 4 } + fadeIn(tween(180))) togetherWith
                                (slideOutHorizontally(tween(180)) { it / 4 } + fadeOut(tween(140)))
                        }
                    },
                    label = "consentStep"
                ) { current ->
                    when (current) {
                        0 -> AgreementStep(
                            text = disclaimerText,
                            scroll = disclaimerScroll,
                            checked = disclaimerChecked,
                            read = disclaimerRead,
                            checkLabel = I18n.t("consent.check.disclaimer"),
                            onChecked = { disclaimerChecked = it }
                        )
                        1 -> AgreementStep(
                            text = authorizationText,
                            scroll = authorizationScroll,
                            checked = authorizationChecked,
                            read = authorizationRead,
                            checkLabel = I18n.t("consent.check.authorization"),
                            onChecked = { authorizationChecked = it }
                        )
                        else -> ConfirmStep(
                            checked = confirmChecked,
                            onChecked = { confirmChecked = it }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = canContinue,
                onClick = { if (step < STEP_COUNT - 1) step++ else onAccept() }
            ) {
                Text(if (step < STEP_COUNT - 1) I18n.t("consent.next") else I18n.t("consent.confirm"))
            }
        },
        dismissButton = {
            TextButton(onClick = { if (step > 0) step-- else onDismiss() }) {
                Text(if (step > 0) I18n.t("consent.back") else I18n.t("consent.cancel"))
            }
        }
    )
}

/** 单个协议步骤：滚动区 + 滚到底提示 + 勾选。 */
@Composable
private fun AgreementStep(
    text: String,
    scroll: ScrollState,
    checked: Boolean,
    read: Boolean,
    checkLabel: String,
    onChecked: (Boolean) -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Column {
        Column(
            Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .verticalScroll(scroll)
                .padding(12.dp)
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AnimatedVisibility(
            visible = !read,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120))
        ) {
            Text(
                I18n.t("consent.scroll_hint"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().clickable(enabled = read) { onChecked(!checked) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onChecked, enabled = read)
            Text(checkLabel, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 第三步：逐项列出授权范围，要求再次确认。 */
@Composable
private fun ConfirmStep(checked: Boolean, onChecked: (Boolean) -> Unit) {
    Column {
        Text(
            I18n.t("consent.confirm.intro"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        listOf(
            "consent.confirm.item.login",
            "consent.confirm.item.ownership",
            "consent.confirm.item.download",
            "consent.confirm.item.record",
            "consent.confirm.item.local"
        ).forEach { key ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text(
                    "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    I18n.t(key),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            I18n.t("consent.confirm.statement"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clickable { onChecked(!checked) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onChecked)
            Text(I18n.t("consent.check.confirm"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 把分段存放的协议正文按序号拼成完整文本；遇到缺号即视为结束。 */
private fun agreementText(prefix: String): String = buildString {
    var index = 1
    while (true) {
        val key = prefix + index.toString().padStart(2, '0')
        val part = I18n.t(key)
        if (part == key) break
        if (isNotEmpty()) append("\n\n")
        append(part)
        index++
    }
}
