package com.pmcl.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.animation.MotionTokens
import com.pmcl.ui.animation.StaggerTokens
import kotlinx.coroutines.delay

/** 二级侧栏宽度：比一级 NavigationRail 更宽，便于展示长标签 */
val SecondaryNavRailWidth = 232.dp

/**
 * 钻入式二级侧边栏：顶部返回 + 父级标题 + 宽文字列表。
 * 入场交错、选中色过渡、按压缩放（通过 graphicsLayer，避免每帧 layout）。
 */
@Composable
fun SecondaryNavRail(
    spec: SecondaryNavSpec,
    selectedSectionId: String,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
    badges: Map<String, String> = emptyMap(),
    hiddenSectionIds: Set<String> = emptySet(),
    railModifier: Modifier = Modifier,
) {
    val sections = spec.sections.filter { it.id !in hiddenSectionIds }
    val gap8 = Modifier.height(8.dp)
    val gapW8 = Modifier.width(8.dp)
    val density = LocalDensity.current
    val railSlidePx = with(density) { 16.dp.toPx() }

    // 整栏内容入场：轻微右移 + 渐显（draw 阶段，不触发布局）
    val railEnter = remember { Animatable(0f) }
    LaunchedEffect(spec.parentRoute) {
        // 已入场完成则跳过，避免退出过渡重组时再次打开放入动画抢帧
        if (railEnter.value >= 0.999f) return@LaunchedEffect
        railEnter.snapTo(0f)
        railEnter.animateTo(
            1f,
            animationSpec = tween(
                durationMillis = MotionTokens.DURATION_MEDIUM,
                easing = MotionTokens.EasingEmphasizedDecelerate
            )
        )
    }
    val railProgress = railEnter.value

    Surface(
        modifier = railModifier
            .width(SecondaryNavRailWidth)
            .fillMaxHeight()
            .graphicsLayer {
                alpha = 0.35f + 0.65f * railProgress
                translationX = (1f - railProgress) * railSlidePx
            }
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            val backInteraction = remember { MutableInteractionSource() }
            val backPressed by backInteraction.collectIsPressedAsState()
            val backScale by animateFloatAsState(
                targetValue = if (backPressed) 0.96f else 1f,
                animationSpec = tween(MotionTokens.DURATION_SHORT, easing = FastOutSlowInEasing),
                label = "backScale"
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = backScale
                        scaleY = backScale
                    }
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(
                        interactionSource = backInteraction,
                        indication = ripple(),
                        onClick = onBack
                    )
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = I18n.t("nav.secondary.back"),
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(gapW8)
                Text(
                    text = I18n.t(spec.parentLabelKey),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(gap8)
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )
            Spacer(gap8)
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                sections.forEachIndexed { index, section ->
                    SecondaryNavItem(
                        label = I18n.t(section.labelKey),
                        icon = section.icon,
                        badge = badges[section.id],
                        selected = section.id == selectedSectionId,
                        staggerIndex = index,
                        parentKey = spec.parentRoute,
                        onClick = { onSelect(section.id) },
                        gapW8 = gapW8
                    )
                }
            }
        }
    }
}

@Composable
private fun SecondaryNavItem(
    label: String,
    icon: ImageVector?,
    badge: String?,
    selected: Boolean,
    staggerIndex: Int,
    parentKey: String,
    onClick: () -> Unit,
    gapW8: Modifier,
) {
    val density = LocalDensity.current
    val itemSlidePx = with(density) { 12.dp.toPx() }
    val enter: Animatable<Float, AnimationVector1D> =
        remember(parentKey, staggerIndex) { Animatable(0f) }
    LaunchedEffect(parentKey, staggerIndex) {
        // 已完成入场则不再重播，减轻退出侧栏时子项重组负担
        if (enter.value >= 0.999f) return@LaunchedEffect
        enter.snapTo(0f)
        val delayMs = (staggerIndex.coerceAtMost(StaggerTokens.MAX_ITEMS_ANIMATED) *
            StaggerTokens.ITEM_DELAY_MS).toLong()
        delay(delayMs)
        enter.animateTo(
            1f,
            animationSpec = tween(
                durationMillis = MotionTokens.DURATION_MEDIUM,
                easing = MotionTokens.EasingEmphasizedDecelerate
            )
        )
    }
    val enterProgress = enter.value

    // 未选中直接用静态色，避免 N 项同时跑 color AsState
    val selectedBg = MaterialTheme.colorScheme.secondaryContainer
    val selectedFg = MaterialTheme.colorScheme.onSecondaryContainer
    val unselectedFg = MaterialTheme.colorScheme.onSurface
    val bg by animateColorAsState(
        targetValue = if (selected) selectedBg else selectedBg.copy(alpha = 0f),
        animationSpec = if (selected) {
            tween(MotionTokens.DURATION_MEDIUM, easing = MotionTokens.EasingEmphasized)
        } else {
            tween(MotionTokens.DURATION_SHORT, easing = MotionTokens.EasingEmphasizedAccelerate)
        },
        label = "navItemBg"
    )
    val fg = if (selected) selectedFg else unselectedFg
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = tween(MotionTokens.DURATION_SHORT),
        label = "navItemPress"
    )
    val shape = RoundedCornerShape(12.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enterProgress
                translationX = (1f - enterProgress) * itemSlidePx
                scaleX = pressScale
                scaleY = pressScale
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bg)
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(bounded = true),
                    onClick = onClick
                )
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            if (icon != null) {
                if (!badge.isNullOrBlank()) {
                    BadgedBox(badge = { Badge { Text(badge.take(4)) } }) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = fg
                        )
                    }
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = fg
                    )
                }
                Spacer(gapW8)
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = fg,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
