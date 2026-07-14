package com.pmcl.ui.widget

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
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

    Card(
        modifier = modifier,
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
                        .background(Color.White.copy(alpha = 0.75f))
                )
            } else {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }

            // 内容层
            Column(
                Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // QR 码
                if (qrBitmap != null) {
                    Surface(
                        modifier = Modifier.size(if (expanded) 200.dp else 140.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "好友二维码",
                                modifier = Modifier.size(if (expanded) 180.dp else 120.dp)
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier.size(if (expanded) 200.dp else 140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("QR 生成中...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }

                Spacer(Modifier.height(if (expanded) 20.dp else 12.dp))

                // 头像
                Surface(
                    modifier = Modifier.size(if (expanded) 56.dp else 40.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            identityManager.displayName.take(1).uppercase(),
                            fontWeight = FontWeight.Bold,
                            fontSize = if (expanded) 22.sp else 16.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(if (expanded) 12.dp else 8.dp))

                // 名称
                Text(
                    identityManager.displayName,
                    style = if (expanded) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                // 身份 ID
                Text(
                    identityManager.identity.toString(),
                    style = if (expanded) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.labelSmall,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (expanded) {
                    Spacer(Modifier.height(16.dp))

                    // 展开额外信息
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("P2P 加密聊天",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Text("基于联机网络",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("分享方式",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                                Text("入房自动发现",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    // 分享文本
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
                    ) {
                        Text(
                            identityManager.shareText,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(if (expanded) 16.dp else 10.dp))

                // 操作按钮
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 自定义背景
                    FilledTonalIconButton(
                        onClick = onPickBackground,
                        modifier = Modifier.size(if (expanded) 42.dp else 34.dp)
                    ) {
                        Icon(
                            Icons.Filled.Image,
                            "更换背景",
                            modifier = Modifier.size(if (expanded) 20.dp else 16.dp)
                        )
                    }

                    // 展开/收起
                    FilledTonalIconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(if (expanded) 42.dp else 34.dp)
                    ) {
                        Icon(
                            if (expanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            if (expanded) "收起" else "展开",
                            modifier = Modifier.size(if (expanded) 20.dp else 16.dp)
                        )
                    }
                }
            }
        }
    }
}
