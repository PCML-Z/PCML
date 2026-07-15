package com.pmcl.ui.widget

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.pmcl.ui.animation.MotionTokens
import androidx.compose.foundation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.friend.FriendIdentityManager
import org.jetbrains.skia.Image as SkiaImage

/**
 * 身份卡片：嵌入好友界面右侧，展示 QR 码 + 身份 ID。
 *
 * 支持自定义背景图片（本地 PNG/JPG 加载）和拉伸全屏。
 *
 * @param identityManager   身份管理器
 * @param expanded          是否展开（全屏拉伸）
 * @param onToggleExpand    展开/收起回调
 * @param backgroundBitmap  自定义背景图（null 则使用默认颜色）
 * @param onPickBackground  选择背景图片回调
 * @param modifier          修饰符
 */
@Composable
fun IdentityCard(
    identityManager: FriendIdentityManager,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    backgroundBitmap: ImageBitmap?,
    onPickBackground: () -> Unit,
    modifier: Modifier = Modifier
) {
    val qrBytes = remember { identityManager.qrCodeBytes }
    val qrBitmap = remember(qrBytes) {
        if (qrBytes != null && qrBytes.isNotEmpty()) {
            try { SkiaImage.makeFromEncoded(qrBytes).toComposeImageBitmap() }
            catch (_: Exception) { null }
        } else null
    }

    // 统一的滑动动画时长与缓动
    val slideDuration = MotionTokens.DURATION_MEDIUM
    val slideEasing = MotionTokens.EasingEmphasized

    // 追踪动画进行状态：expanded 变化时立即置 true，动画时长后置 false
    var isAnimating by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        isAnimating = true
        kotlinx.coroutines.delay(slideDuration.toLong())
        isAnimating = false
    }
    // 模糊半径：动画进行时 12dp，静止时 0dp
    val blurRadius by animateFloatAsState(
        targetValue = if (isAnimating) 12f else 0f,
        animationSpec = tween(slideDuration, easing = slideEasing),
        label = "blurRadius"
    )

    Card(
        modifier = modifier
            .clipToBounds()
            .clip(RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(Modifier.wrapContentSize()) {
            // 背景层（使用 matchParentSize 跟随 Box 实际尺寸，不影响测量）
            if (backgroundBitmap != null) {
                Image(
                    bitmap = backgroundBitmap,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop
                )
                // 半透明遮罩确保内容可读
                Box(
                    Modifier.matchParentSize()
                        .background(Color.White.copy(alpha = 0.4f))
                )
            } else {
                Box(
                    Modifier.matchParentSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            // 内容层：展开/收起时内容横向滑动切换（AnimatedContent 自带 SizeTransform 平滑过渡尺寸）
            // 整个内容层在动画进行时应用高斯模糊
            AnimatedContent(
                targetState = expanded,
                transitionSpec = {
                    if (targetState) {
                        // 展开：新内容从右侧滑入，旧内容向左滑出
                        slideInHorizontally(tween(slideDuration, easing = slideEasing)) { fullWidth -> fullWidth } togetherWith
                        slideOutHorizontally(tween(slideDuration, easing = slideEasing)) { fullWidth -> -fullWidth }
                    } else {
                        // 收起：新内容从左侧滑入，旧内容向右滑出
                        slideInHorizontally(tween(slideDuration, easing = slideEasing)) { fullWidth -> -fullWidth } togetherWith
                        slideOutHorizontally(tween(slideDuration, easing = slideEasing)) { fullWidth -> fullWidth }
                    }
                },
                contentKey = { it },
                modifier = Modifier
                    .padding(16.dp)
                    .blur(blurRadius.dp),
                contentAlignment = Alignment.TopCenter
            ) { isExpanded ->
                Column(
                    Modifier.wrapContentWidth().wrapContentHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
                ) {
                    if (isExpanded) {
                        // ===== 展开布局 =====
                        // QR 码
                        if (qrBitmap != null) {
                            Surface(
                                modifier = Modifier.size(160.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White,
                                border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = "好友二维码",
                                        modifier = Modifier.size(144.dp)
                                    )
                                }
                            }
                        } else {
                            Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
                                Text("QR 生成中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // 头像
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    identityManager.displayName.take(1).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 20.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 名称
                        Text(
                            identityManager.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(4.dp))

                        // 身份 ID
                        Text(
                            identityManager.identity.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(10.dp))

                        // 操作按钮
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalIconButton(
                                onClick = onPickBackground,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(Icons.Filled.Image, "更换背景", modifier = Modifier.size(18.dp))
                            }
                            FilledTonalIconButton(
                                onClick = onToggleExpand,
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(Icons.Filled.FullscreenExit, "收起", modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        // ===== 收起布局 =====
                        // QR 码
                        if (qrBitmap != null) {
                            Surface(
                                modifier = Modifier.size(100.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = Color.White,
                                border = BorderStroke(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = "好友二维码",
                                        modifier = Modifier.size(86.dp)
                                    )
                                }
                            }
                        } else {
                            Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
                                Text("QR 生成中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 头像
                        Surface(
                            modifier = Modifier.size(32.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    identityManager.displayName.take(1).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        // 名称
                        Text(
                            identityManager.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(2.dp))

                        // 身份 ID
                        Text(
                            identityManager.identity.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(8.dp))

                        // 操作按钮
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalIconButton(
                                onClick = onPickBackground,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Filled.Image, "更换背景", modifier = Modifier.size(14.dp))
                            }
                            FilledTonalIconButton(
                                onClick = onToggleExpand,
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Filled.Fullscreen, "展开", modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
