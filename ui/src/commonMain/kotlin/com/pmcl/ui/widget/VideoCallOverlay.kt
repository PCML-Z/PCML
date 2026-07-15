package com.pmcl.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.video.VideoCallSession
import kotlinx.coroutines.delay
import org.jetbrains.skia.Image as SkiaImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** 将 BufferedImage 转为 Compose ImageBitmap */
private fun BufferedImage.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap {
    val baos = ByteArrayOutputStream()
    ImageIO.write(this, "png", baos)
    return SkiaImage.makeFromEncoded(baos.toByteArray()).toComposeImageBitmap()
}

/**
 * 视频通话浮层：显示远端/本地视频画面、通话状态、控制按钮。
 * 视频渲染使用 Compose Image 直接绘制 BufferedImage。
 */
@Composable
fun VideoCallOverlay(
    session: VideoCallSession,
    onEndCall: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onToggleCamera: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var callDuration by remember { mutableStateOf(0L) }
    var isMuted by remember { mutableStateOf(false) }
    var isCameraOn by remember { mutableStateOf(true) }

    // 视频帧状态（通过 CallListener 回调更新）
    var remoteFrame by remember { mutableStateOf<BufferedImage?>(null) }
    var localFrame by remember { mutableStateOf<BufferedImage?>(null) }

    // 注册 CallListener
    DisposableEffect(session) {
        val listener = object : VideoCallSession.CallListener {
            override fun onStateChanged(state: VideoCallSession.State) {}
            override fun onLocalCandidate(candidateSdp: String, ufrag: String, pwd: String) {}
            override fun onRemoteFrame(frame: BufferedImage?) {
                remoteFrame = frame
            }
            override fun onLocalFrame(frame: BufferedImage?) {
                localFrame = frame
            }
            override fun onVideoPortReady(port: Int) {}
            override fun onError(message: String) {}
        }
        session.addListener(listener)
        onDispose { session.removeListener(listener) }
    }

    // 通话计时
    LaunchedEffect(session.state) {
        if (session.state == VideoCallSession.State.IN_CALL) {
            callDuration = 0
            while (session.state == VideoCallSession.State.IN_CALL) {
                delay(1000)
                callDuration++
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 远端视频画面（全屏）
        remoteFrame?.let { img ->
            val bitmap = remember(img) { img.toImageBitmap() }
            androidx.compose.foundation.Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        } ?: run {
            // 无视频时显示等待提示
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.VideocamOff, null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        when (session.state) {
                            VideoCallSession.State.RINGING -> if (session.isInitiator) "等待对方接听..." else "来电中..."
                            VideoCallSession.State.NEGOTIATING -> "正在建立连接..."
                            VideoCallSession.State.IN_CALL -> "等待视频画面..."
                            VideoCallSession.State.ENDED -> "通话结束"
                        },
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        // 本地视频预览（右上角小窗）
        if (isCameraOn) {
            localFrame?.let { img ->
                val bitmap = remember(img) { img.toImageBitmap() }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(width = 160.dp, height = 120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                }
            }
        }

        // 顶部：通话信息
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = session.remoteName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when (session.state) {
                        VideoCallSession.State.RINGING -> if (session.isInitiator) "正在呼叫..." else "来电中..."
                        VideoCallSession.State.NEGOTIATING -> "连接中..."
                        VideoCallSession.State.IN_CALL -> formatDuration(callDuration)
                        VideoCallSession.State.ENDED -> "通话结束"
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // 底部：控制按钮栏
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)
        ) {
            // 静音按钮
            if (session.state == VideoCallSession.State.IN_CALL) {
                CallControlButton(
                    icon = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = if (isMuted) "取消静音" else "静音",
                    isActive = isMuted,
                    onClick = {
                        isMuted = !isMuted
                        onToggleMute(isMuted)
                    }
                )

                // 摄像头开关
                CallControlButton(
                    icon = if (isCameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    label = if (isCameraOn) "关闭摄像头" else "开启摄像头",
                    isActive = !isCameraOn,
                    onClick = {
                        isCameraOn = !isCameraOn
                        onToggleCamera(isCameraOn)
                    }
                )
            }

            // 挂断按钮
            FloatingActionButton(
                onClick = onEndCall,
                containerColor = Color.Red,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                Icon(Icons.Filled.CallEnd, "挂断", tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingActionButton(
            onClick = onClick,
            containerColor = if (isActive) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
            shape = CircleShape,
            modifier = Modifier.size(52.dp)
        ) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}

/**
 * 来电提示卡片：显示来电者信息 + 接听/拒绝按钮。
 */
@Composable
fun IncomingCallCard(
    callerName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 头像
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    callerName.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(callerName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("来电", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                // 拒绝
                FloatingActionButton(
                    onClick = onReject,
                    containerColor = Color.Red,
                    shape = CircleShape,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Filled.CallEnd, "拒绝", tint = Color.White)
                }

                // 接听
                FloatingActionButton(
                    onClick = onAccept,
                    containerColor = Color(0xFF4CAF50),
                    shape = CircleShape,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(Icons.Filled.Call, "接听", tint = Color.White)
                }
            }
        }
    }
}
