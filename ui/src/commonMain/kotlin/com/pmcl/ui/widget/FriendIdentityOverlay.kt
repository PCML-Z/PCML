package com.pmcl.ui.widget

import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
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

    // 展开/收起动画值（统一使用 MotionTokens 缓动曲线）
    val spec = MotionTokens.tweenEmphasized<Dp>()
    val specF = MotionTokens.tweenEmphasized<Float>()
    val qrSize by animateDpAsState(if (expanded) 200.dp else 140.dp, spec, label = "qrSize")
    val qrImgSize by animateDpAsState(if (expanded) 180.dp else 120.dp, spec, label = "qrImgSize")
    val qrSpacer by animateDpAsState(if (expanded) 20.dp else 12.dp, spec, label = "qrSpacer")
    val avatarSize by animateDpAsState(if (expanded) 56.dp else 40.dp, spec, label = "avatarSize")
    val avatarFont by animateFloatAsState(if (expanded) 22f else 16f, specF, label = "avatarFont")
    val avatarSpacer by animateDpAsState(if (expanded) 12.dp else 8.dp, spec, label = "avatarSpacer")
    val nameFont by animateFloatAsState(if (expanded) 22f else 16f, specF, label = "nameFont")
    val idFont by animateFloatAsState(if (expanded) 14f else 11f, specF, label = "idFont")
    val idSpacer by animateDpAsState(if (expanded) 12.dp else 10.dp, spec, label = "idSpacer")
    val btnSize by animateDpAsState(if (expanded) 42.dp else 34.dp, spec, label = "btnSize")
    val btnIconSize by animateDpAsState(if (expanded) 20.dp else 16.dp, spec, label = "btnIconSize")

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
                        .background(Color.White.copy(alpha = 0.4f))
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
                        modifier = Modifier.size(qrSize),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "好友二维码",
                                modifier = Modifier.size(qrImgSize)
                            )
                        }
                    }
                } else {
                    Box(
                        Modifier.size(qrSize),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("QR 生成中...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }

                Spacer(Modifier.height(qrSpacer))

                // 头像
                Surface(
                    modifier = Modifier.size(avatarSize),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            identityManager.displayName.take(1).uppercase(),
                            fontWeight = FontWeight.Bold,
                            fontSize = avatarFont.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(avatarSpacer))

                // 名称
                Text(
                    identityManager.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = nameFont.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                // 身份 ID
                Text(
                    identityManager.identity.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = idFont.sp,
                    letterSpacing = 1.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(idSpacer))

                // 操作按钮
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // 自定义背景
                    FilledTonalIconButton(
                        onClick = onPickBackground,
                        modifier = Modifier.size(btnSize)
                    ) {
                        Icon(
                            Icons.Filled.Image,
                            "更换背景",
                            modifier = Modifier.size(btnIconSize)
                        )
                    }

                    // 展开/收起
                    FilledTonalIconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(btnSize)
                    ) {
                        Icon(
                            if (expanded) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            if (expanded) "收起" else "展开",
                            modifier = Modifier.size(btnIconSize)
                        )
                    }
                }
            }
        }
    }
}
