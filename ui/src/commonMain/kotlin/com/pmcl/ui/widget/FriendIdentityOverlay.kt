package com.pmcl.ui.widget

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import com.pmcl.ui.animation.MotionTokens
import androidx.compose.foundation.*
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

    Card(
        modifier = modifier.clipToBounds(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(Modifier.fillMaxSize()) {
            // 背景层
            if (backgroundBitmap != null) {
                Image(
                    bitmap = backgroundBitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // 半透明遮罩确保内容可读
                Box(
                    Modifier.fillMaxSize()
                        .background(Color.White.copy(alpha = 0.4f))
                )
            } else {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            // 内容层：展开/收起时内容横向滑动切换（AnimatedContent 自带 SizeTransform 平滑过渡尺寸）
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
                modifier = Modifier.padding(16.dp),
                contentAlignment = Alignment.Center
            ) { isExpanded ->
                Column(
                    Modifier.fillMaxWidth().wrapContentHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (isExpanded) {
                        // ===== 展开布局 =====
                        // QR 码
                        if (qrBitmap != null) {
                            Surface(
                                modifier = Modifier.size(200.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = "好友二维码",
                                        modifier = Modifier.size(180.dp)
                                    )
                                }
                            }
                        } else {
                            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                                Text("QR 生成中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            }
                        }

                        Spacer(Modifier.height(20.dp))

                        // 头像
                        Surface(
                            modifier = Modifier.size(56.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    identityManager.displayName.take(1).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 名称
                        Text(
                            identityManager.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(4.dp))

                        // 身份 ID
                        Text(
                            identityManager.identity.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(12.dp))

                        // 操作按钮
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilledTonalIconButton(
                                onClick = onPickBackground,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(Icons.Filled.Image, "更换背景", modifier = Modifier.size(20.dp))
                            }
                            FilledTonalIconButton(
                                onClick = onToggleExpand,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(Icons.Filled.FullscreenExit, "收起", modifier = Modifier.size(20.dp))
                            }
                        }
                    } else {
                        // ===== 收起布局 =====
                        // QR 码
                        if (qrBitmap != null) {
                            Surface(
                                modifier = Modifier.size(140.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = Color.White
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Image(
                                        bitmap = qrBitmap,
                                        contentDescription = "好友二维码",
                                        modifier = Modifier.size(120.dp)
                                    )
                                }
                            }
                        } else {
                            Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
                                Text("QR 生成中...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // 头像
                        Surface(
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    identityManager.displayName.take(1).uppercase(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

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
                            style = MaterialTheme.typography.labelSmall,
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
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Filled.Image, "更换背景", modifier = Modifier.size(16.dp))
                            }
                            FilledTonalIconButton(
                                onClick = onToggleExpand,
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Filled.Fullscreen, "展开", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
