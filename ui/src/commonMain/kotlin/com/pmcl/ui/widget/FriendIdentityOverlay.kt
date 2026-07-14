package com.pmcl.ui.widget

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.pmcl.core.friend.FriendIdentityManager
import com.pmcl.core.friend.FriendManager
import org.jetbrains.skia.Image as SkiaImage

/**
 * 身份卡片浮层：窗口右侧显示 QR 码 + 身份 ID。
 *
 * 三种模式：
 * - HIDDEN   — 完全隐藏，仅显示触发浮钮
 * - CARD     — 右侧紧凑卡片（默认 280dp 宽）
 * - FULL     — 全窗口居中展示
 *
 * 支持自定义背景颜色（内置 8 套预设）。
 */
@Composable
fun FriendIdentityOverlay(
    friendManager: FriendManager?,
    modifier: Modifier = Modifier
) {
    if (friendManager == null) return

    val identityManager = remember(friendManager) { friendManager.identityManager }

    // 显示模式
    var mode by remember { mutableStateOf(Mode.HIDDEN) }

    // 背景颜色索引（持久化到 FriendManager 的状态中）
    var bgColorIndex by remember { mutableIntStateOf(0) }

    // 动画
    val expandedWidth = 320.dp
    val cardWidth by animateDpAsState(
        targetValue = when (mode) {
            Mode.HIDDEN -> 0.dp
            Mode.CARD -> expandedWidth
            Mode.FULL -> 0.dp
        },
        animationSpec = tween(300)
    )

    val cardAlpha by animateFloatAsState(
        targetValue = when (mode) {
            Mode.HIDDEN -> 0f
            Mode.CARD -> 1f
            Mode.FULL -> 0f
        },
        animationSpec = tween(250)
    )

    val fullScreenAlpha by animateFloatAsState(
        targetValue = if (mode == Mode.FULL) 1f else 0f,
        animationSpec = tween(300)
    )

    val qrBytes = remember { identityManager.qrCodeBytes }
    val qrBitmap = remember(qrBytes) {
        if (qrBytes != null && qrBytes.isNotEmpty()) {
            try { SkiaImage.makeFromEncoded(qrBytes).toComposeImageBitmap() }
            catch (_: Exception) { null }
        } else null
    }

    val bgColor = BackgroundPresets.colors[bgColorIndex]

    Box(modifier) {
        // =====================================================================
        // 触发浮钮（悬浮在右下角）
        // =====================================================================
        if (mode == Mode.HIDDEN) {
            FloatingActionButton(
                onClick = { mode = Mode.CARD },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(48.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.QrCode, "身份卡片", modifier = Modifier.size(22.dp))
            }
        }

        // =====================================================================
        // 全屏模式
        // =====================================================================
        AnimatedVisibility(
            visible = mode == Mode.FULL,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f, animationSpec = tween(250)),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.95f, animationSpec = tween(150))
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(100f)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { mode = Mode.CARD },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.widthIn(max = 420.dp).padding(32.dp)) {
                    FullScreenCard(
                        identityManager = identityManager,
                        qrBitmap = qrBitmap,
                        bgColor = bgColor,
                        bgColorIndex = bgColorIndex,
                        onBgColorChange = { bgColorIndex = it },
                        onDismiss = { mode = Mode.CARD }
                    )
                }
            }
        }

        // =====================================================================
        // 右侧紧凑卡片
        // =====================================================================
        AnimatedVisibility(
            visible = mode == Mode.CARD,
            enter = slideInHorizontally(tween(300)) { it },
            exit = slideOutHorizontally(tween(250)) { it }
        ) {
            Card(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(expandedWidth)
                    .fillMaxHeight()
                    .zIndex(99f)
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
                shape = RoundedCornerShape(16.dp, 0.dp, 16.dp, 0.dp),
                colors = CardDefaults.cardColors(containerColor = bgColor)
            ) {
                Column(Modifier.fillMaxSize()) {
                    // 标题栏
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("我的身份",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { mode = Mode.HIDDEN }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Close, "关闭", modifier = Modifier.size(18.dp))
                        }
                    }

                    HorizontalDivider(color = bgColor.copy(alpha = 0.1f))

                    Column(
                        Modifier.weight(1f).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        // QR 码
                        if (qrBitmap != null) {
                            Surface(
                                modifier = Modifier.size(180.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = "好友二维码",
                                        modifier = Modifier.size(160.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // 身份 ID
                        Text(identityManager.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            identityManager.identity.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )

                        Spacer(Modifier.height(20.dp))

                        // 操作按钮
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // 全屏按钮
                            FilledTonalIconButton(
                                onClick = { mode = Mode.FULL },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Filled.Fullscreen, "全屏展示",
                                    modifier = Modifier.size(18.dp))
                            }
                            // 复制 ID
                            FilledTonalIconButton(
                                onClick = { /* TODO: clipboard */ },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(Icons.Filled.ContentCopy, "复制身份 ID",
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // 底部操作栏
                    HorizontalDivider(color = bgColor.copy(alpha = 0.1f))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 背景颜色选择
                        BackgroundPresets.colors.forEachIndexed { index, color ->
                            val selected = index == bgColorIndex
                            Box(
                                modifier = Modifier
                                    .size(if (selected) 28.dp else 24.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .then(
                                        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        else Modifier.border(0.5.dp, color.copy(alpha = 0.5f), CircleShape)
                                    )
                                    .clickable { bgColorIndex = index }
                            )
                        }
                    }
                }
            }
        }
    }
}

// =============================================================================
// 全屏卡片
// =============================================================================

@Composable
private fun FullScreenCard(
    identityManager: FriendIdentityManager,
    qrBitmap: ImageBitmap?,
    bgColor: Color,
    bgColorIndex: Int,
    onBgColorChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = bgColor,
        shadowElevation = 8.dp
    ) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 标题
            Text("分享我的身份",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("让好友扫描二维码即可添加你",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))

            Spacer(Modifier.height(24.dp))

            // QR 码（放大）
            if (qrBitmap != null) {
                Surface(
                    modifier = Modifier.size(260.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "好友二维码",
                            modifier = Modifier.size(230.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // 用户信息
            Surface(
                color = bgColor.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(48.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(identityManager.displayName.take(1).uppercase(),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(identityManager.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(identityManager.identity.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // 背景颜色选择器
            Text("卡片背景",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackgroundPresets.colors.forEachIndexed { index, color ->
                    val selected = index == bgColorIndex
                    Box(
                        modifier = Modifier
                            .size(if (selected) 38.dp else 32.dp)
                            .clip(CircleShape)
                            .background(color)
                            .then(
                                if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                else Modifier.border(0.5.dp, color.copy(alpha = 0.5f), CircleShape)
                            )
                            .clickable { onBgColorChange(index) }
                    )
                }
            }
        }
    }
}

// =============================================================================
// 背景预设
// =============================================================================

private object BackgroundPresets {
    val colors = listOf(
        Color(0xFFF5F5F5), // 浅灰
        Color(0xFFFFFFFF), // 纯白
        Color(0xFFE3F2FD), // 浅蓝
        Color(0xFFE8F5E9), // 浅绿
        Color(0xFFFFF3E0), // 浅橙
        Color(0xFFFCE4EC), // 浅粉
        Color(0xFFF3E5F5), // 浅紫
        Color(0xFFECEFF1), // 灰蓝
    )
}

// =============================================================================
// 显示模式
// =============================================================================

private enum class Mode { HIDDEN, CARD, FULL }
