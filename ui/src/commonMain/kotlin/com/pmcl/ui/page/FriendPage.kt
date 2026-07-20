package com.pmcl.ui.page

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.friend.FriendIdentity
import com.pmcl.core.friend.FriendIdentityManager
import com.pmcl.core.friend.FriendManager
import com.pmcl.core.friend.FriendStore
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.widget.IdentityCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
fun FriendPage(vm: LauncherViewModel) {
    val friendManager = remember { vm.core.friend() }
    if (friendManager == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(I18n.t("friend.system_unavailable"), style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val scope = rememberCoroutineScope()
    var friendSystemState by remember { mutableStateOf(friendManager.state) }
    // 每次状态变化（含账户切换）时重新获取 identityManager 实例
    val identityManager = remember(friendSystemState) { friendManager.identityManager }
    // 账号登录状态：未登录时身份卡片数据留空
    val account by vm.account.collectAsState()
    val hasAccount = account != null

    var friends by remember { mutableStateOf(friendManager.getFriends()) }
    var selectedFriendId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<FriendStore.StoredMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    var addFriendCode by remember { mutableStateOf("") }
    var addFriendError by remember { mutableStateOf<String?>(null) }
    val pendingRequests = remember { mutableStateListOf<FriendManager.PendingRequest>() }

    // 视频通话状态
    var activeCallSession by remember { mutableStateOf<com.pmcl.video.VideoCallSession?>(null) }
    var incomingCall by remember { mutableStateOf<Pair<String, String>?>(null) } // (identity, name)
    var pendingCallerVideoPort by remember { mutableStateOf(0) } // 来电者的视频端口

    // 身份卡片状态
    var cardExpanded by remember { mutableStateOf(false) }
    // 用 identityManager.version 作为 key，账户切换/背景图变化时重新加载
    val identityVersion = identityManager.version
    var bgImagePath by remember(identityVersion) { mutableStateOf(identityManager.backgroundPath) }
    val bgBitmap by produceState<ImageBitmap?>(null, bgImagePath) {
        if (bgImagePath != null) {
            value = withContext(Dispatchers.IO) {
                try {
                    val file = File(bgImagePath)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
                    } else null
                } catch (_: Exception) { null }
            }
        }
    }

    // 选择背景图片（JFileChooser 必须在 EDT 上显示，否则会崩溃）
    val pickBackground: () -> Unit = {
        scope.launch(Dispatchers.IO) {
            var path: String? = null
            try {
                javax.swing.SwingUtilities.invokeAndWait {
                    val chooser = JFileChooser()
                    chooser.dialogTitle = "选择卡片背景图片"
                    chooser.fileFilter = FileNameExtensionFilter("图片文件 (*.png, *.jpg, *.jpeg)", "png", "jpg", "jpeg")
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        path = chooser.selectedFile.absolutePath
                    }
                }
            } catch (e: Exception) {
                System.err.println("[FriendPage] 选择背景图片失败: ${e.message}")
            }
            if (path != null) {
                identityManager.backgroundPath = path
                bgImagePath = path
            }
        }
    }

    // 刷新回调
    val refresh = {
        friends = friendManager.getFriends()
        selectedFriendId?.let { id ->
            messages = friendManager.getMessages(id)
        }
    }

    // 监听事件
    DisposableEffect(friendManager) {
        val listener: (FriendManager.FriendEvent) -> Unit = { event ->
            if (event.type == FriendManager.FriendEvent.Type.STATE_CHANGED) {
                friendSystemState = event.data as? FriendManager.State ?: friendManager.state
            }
            scope.launch {
                when (event.type) {
                    FriendManager.FriendEvent.Type.FRIEND_REQUEST_RECEIVED -> {
                        val request = event.data as? FriendManager.PendingRequest
                        if (request != null) {
                            // 去重：检查是否已存在相同 identity 的请求
                            if (pendingRequests.none { it.identity == request.identity }) {
                                pendingRequests.add(request)
                            }
                        }
                    }
                    FriendManager.FriendEvent.Type.CALL_INVITE_RECEIVED -> {
                        // 收到视频通话邀请，提取来电者和对方的视频端口
                        val data = event.data
                        if (data != null && activeCallSession == null) {
                            try {
                                val fields = data.javaClass.declaredFields
                                var fromId = ""
                                var fromName = ""
                                var callerVideoPort = 0
                                for (f in fields) {
                                    f.isAccessible = true
                                    when (f.name) {
                                        "from" -> fromId = f.get(data) as? String ?: ""
                                        "fromName" -> fromName = f.get(data) as? String ?: ""
                                        "videoPort" -> callerVideoPort = f.getInt(data)
                                    }
                                }
                                if (fromId.isNotEmpty()) {
                                    incomingCall = Pair(fromId, fromName.ifEmpty { fromId })
                                    // 保存来电者视频端口，以便在接听时使用
                                    pendingCallerVideoPort = callerVideoPort
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    FriendManager.FriendEvent.Type.CALL_ACCEPTED -> {
                        // 对方接听，提取视频端口并开始 ICE 协商
                        val data = event.data
                        if (data != null && activeCallSession != null) {
                            try {
                                val f = data.javaClass.getDeclaredField("videoPort")
                                f.isAccessible = true
                                val remotePort = f.getInt(data)
                                if (remotePort > 0) {
                                    activeCallSession?.onRemoteVideoPort(remotePort)
                                }
                            } catch (_: Exception) {}
                        }
                        activeCallSession?.startIceNegotiation()
                    }
                    FriendManager.FriendEvent.Type.CALL_REJECTED,
                    FriendManager.FriendEvent.Type.CALL_ENDED -> {
                        activeCallSession?.end()
                        activeCallSession = null
                    }
                    FriendManager.FriendEvent.Type.CALL_ICE_CANDIDATE -> {
                        // 收到远端 ICE 候选
                        val data = event.data
                        if (data != null && activeCallSession != null) {
                            try {
                                val fields = data.javaClass.declaredFields
                                var candidate = ""
                                var ufrag = ""
                                var pwd = ""
                                for (f in fields) {
                                    f.isAccessible = true
                                    when (f.name) {
                                        "candidate" -> candidate = f.get(data) as? String ?: ""
                                        "ufrag" -> ufrag = f.get(data) as? String ?: ""
                                        "pwd" -> pwd = f.get(data) as? String ?: ""
                                    }
                                }
                                if (candidate.isNotEmpty()) {
                                    activeCallSession?.addRemoteCandidate(candidate, ufrag, pwd)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    FriendManager.FriendEvent.Type.FRIEND_REMOVED -> {
                        refresh()
                    }
                    FriendManager.FriendEvent.Type.MESSAGE_RECEIVED -> {
                        // 收到新消息：刷新当前选中好友的消息列表。
                        // store.addMessage 已按正确 identity 存储，getMessages(currentId)
                        // 返回该好友最新消息；若消息来自其他好友则列表不变，无副作用。
                        val currentId = selectedFriendId
                        if (currentId != null) {
                            messages = friendManager.getMessages(currentId)
                        }
                    }
                    else -> {
                        refresh()
                    }
                }
            }
        }
        friendManager.addListener(listener)
        onDispose { friendManager.removeListener(listener) }
    }

    // 默认启动本地网络（进入好友页时自动启动，start() 幂等可安全重复调用）
    LaunchedEffect(Unit) {
        if (friendSystemState != FriendManager.State.RUNNING) {
            withContext(Dispatchers.IO) {
                try { friendManager.start() } catch (e: Exception) {
                    System.err.println("[FriendPage] 自动启动好友网络失败: ${e.message}")
                }
            }
            friendSystemState = friendManager.state
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 标题栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.People, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(I18n.t("nav.friends"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))

            // 清除背景
            if (bgImagePath != null) {
                IconButton(onClick = { bgImagePath = null; identityManager.backgroundPath = null }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.HideImage, I18n.t("friend.clear_background"),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 添加好友按钮
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.PersonAdd, I18n.t("friend.add_friend"), tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(12.dp))

        if (friends.isEmpty() && pendingRequests.isEmpty()) {
            // 空状态（带身份卡片）
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.PeopleOutline, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(8.dp))
                        Text(I18n.t("friend.no_friends"), style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                        Spacer(Modifier.height(4.dp))
                        Text(I18n.t("friend.no_friends_hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    }
                }

                Spacer(Modifier.width(12.dp))

                // 右侧身份卡片
                IdentityCard(
                    identityManager = identityManager,
                    hasAccount = hasAccount,
                    expanded = cardExpanded,
                    onToggleExpand = { cardExpanded = !cardExpanded },
                    backgroundBitmap = bgBitmap,
                    onPickBackground = pickBackground,
                    modifier = Modifier
                        .then(
                            if (cardExpanded) Modifier.weight(2f)
                            else Modifier.width(160.dp)
                        )
                        .fillMaxHeight()
                )
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                // 左侧：好友列表
                Column(Modifier.width(220.dp).fillMaxHeight()) {
                    if (pendingRequests.isNotEmpty()) {
                        Text(I18n.t("friend.requests"), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp))
                        pendingRequests.forEach { request ->
                            FriendRequestCard(request,
                                onAccept = {
                                    friendManager.acceptFriendRequest(request)
                                    pendingRequests.remove(request)
                                    refresh()
                                },
                                onReject = {
                                    friendManager.rejectFriendRequest(request)
                                    pendingRequests.remove(request)
                                }
                            )
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    }

                    Text(I18n.t("friend.friends"), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp))

                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(friends.toList(), key = { it.identity }) { friend ->
                            FriendListItem(
                                friend = friend,
                                selected = selectedFriendId == friend.identity,
                                onClick = {
                                    selectedFriendId = friend.identity
                                    messages = friendManager.getMessages(friend.identity)
                                }
                            )
                        }
                    }
                }

                VerticalDivider(Modifier.padding(horizontal = 10.dp))

                // 中间：聊天区（占据主要空间）
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (selectedFriendId != null) {
                        val selectedFriend = friends.find { it.identity == selectedFriendId }
                        ChatView(
                            friendName = selectedFriend?.displayName ?: selectedFriendId!!,
                            friendOnline = selectedFriend?.online ?: false,
                            messages = messages,
                            inputText = inputText,
                            onInputChange = { inputText = it },
                            onSend = {
                                if (inputText.isNotBlank()) {
                                    friendManager.sendMessage(selectedFriendId!!, inputText.trim())
                                    inputText = ""
                                    messages = friendManager.getMessages(selectedFriendId!!)
                                }
                            },
                            onDeleteFriend = {
                                friendManager.removeFriend(selectedFriendId!!)
                                selectedFriendId = null
                                refresh()
                            },
                            onVideoCall = {
                                // 发起视频通话
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        com.pmcl.video.VideoCallManager.init()
                                        val callId = java.util.UUID.randomUUID().toString()
                                        val friendEntry = selectedFriend
                                        
                                        // 创建视频 socket 并获取端口
                                        val videoSocket = java.net.DatagramSocket()
                                        val videoPort = videoSocket.localPort
                                        
                                        val session = com.pmcl.video.VideoCallSession(
                                            callId, selectedFriendId!!, friendEntry?.displayName ?: "",
                                            true, com.pmcl.video.VideoCallSession.MediaType.AUDIO_VIDEO
                                        )
                                        session.addListener(object : com.pmcl.video.VideoCallSession.CallListener {
                                            override fun onStateChanged(state: com.pmcl.video.VideoCallSession.State) {
                                                if (state == com.pmcl.video.VideoCallSession.State.ENDED) {
                                                    activeCallSession = null
                                                }
                                            }
                                            override fun onLocalCandidate(candidateSdp: String, ufrag: String, pwd: String) {
                                                friendManager.sendCallIceCandidate(
                                                    selectedFriendId!!, callId, candidateSdp, 0, "1", ufrag, pwd)
                                            }
                                            override fun onRemoteFrame(frame: java.awt.image.BufferedImage?) {}
                                            override fun onLocalFrame(frame: java.awt.image.BufferedImage?) {}
                                            override fun onVideoPortReady(port: Int) {}
                                            override fun onError(message: String) {
                                                System.err.println("[VideoCall] $message")
                                                activeCallSession = null
                                            }
                                        })
                                        activeCallSession = session
                                        // 将视频 socket 附加到 session（用反射设置 videoSocket 字段）
                                        try {
                                            val f = com.pmcl.video.VideoCallSession::class.java.getDeclaredField("videoSocket")
                                            f.isAccessible = true
                                            f.set(session, videoSocket)
                                        } catch (_: Exception) {}
                                        
                                        val inviteResult = friendManager.sendCallInvite(selectedFriendId!!, "video", videoPort)
                                        if (inviteResult == null) {
                                            System.err.println("[VideoCall] 无法发送通话邀请：好友无网络地址")
                                            videoSocket.close()
                                            session.end()
                                            activeCallSession = null
                                        } else {
                                            System.out.println("[VideoCall] 通话邀请已发送 callId=$inviteResult -> ${selectedFriendId}")
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.AutoMirrored.Filled.Chat, null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(12.dp))
                                Text(I18n.t("friend.select_friend_to_chat"),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }

                Spacer(Modifier.width(12.dp))

                // 右侧：身份卡片
                IdentityCard(
                    identityManager = identityManager,
                    hasAccount = hasAccount,
                    expanded = cardExpanded,
                    onToggleExpand = { cardExpanded = !cardExpanded },
                    backgroundBitmap = bgBitmap,
                    onPickBackground = pickBackground,
                    modifier = Modifier
                        .then(
                            if (cardExpanded) Modifier.weight(1.5f)
                            else Modifier.width(160.dp)
                        )
                        .fillMaxHeight()
                )
            }
        }
    }

    // 添加好友弹窗
    if (showAddDialog) {
        AddFriendDialog(
            code = addFriendCode,
            error = addFriendError,
            onCodeChange = { addFriendCode = it },
            onDismiss = { showAddDialog = false },
            onAdd = {
                val info = FriendIdentityManager.parseInvite(addFriendCode)
                if (info != null) {
                    friendManager.sendFriendRequest(
                        info.identity.toString(),
                        info.displayName,
                        "",
                        0
                    )
                    addFriendCode = ""
                    addFriendError = null
                    showAddDialog = false
                } else {
                    addFriendError = I18n.t("friend.invalid_invite_code")
                }
            },
            onScanStart = {
                scope.launch(Dispatchers.IO) {
                    var selectedFile: java.io.File? = null
                    try {
                        javax.swing.SwingUtilities.invokeAndWait {
                            val chooser = JFileChooser()
                            chooser.dialogTitle = "选择二维码图片"
                            chooser.fileFilter = FileNameExtensionFilter("图片文件 (*.png, *.jpg, *.jpeg, *.bmp, *.gif)", "png", "jpg", "jpeg", "bmp", "gif")
                            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                selectedFile = chooser.selectedFile
                            }
                        }
                    } catch (e: Exception) {
                        System.err.println("[FriendPage] 选择二维码图片失败: ${e.message}")
                    }
                    if (selectedFile != null) {
                        val decoded = FriendIdentityManager.decodeQrCode(selectedFile)
                        if (decoded != null) {
                            val info = FriendIdentityManager.parseInvite(decoded)
                            if (info != null) {
                                addFriendCode = decoded
                                addFriendError = null
                            } else {
                                addFriendError = I18n.t("friend.invalid_image_qr")
                            }
                        } else {
                            addFriendError = I18n.t("friend.no_qr_in_image")
                        }
                    }
                }
            },
            onScanClipboard = {
                scope.launch(Dispatchers.IO) {
                    try {
                        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                        val contents = clipboard.getContents(null)
                        if (contents != null && contents.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                            val img = contents.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as java.awt.Image
                            val bufferedImg = java.awt.image.BufferedImage(
                                img.getWidth(null), img.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_RGB
                            )
                            val g = bufferedImg.createGraphics()
                            g.drawImage(img, 0, 0, null)
                            g.dispose()
                            val decoded = FriendIdentityManager.decodeQrCode(bufferedImg)
                            if (decoded != null) {
                                val info = FriendIdentityManager.parseInvite(decoded)
                                if (info != null) {
                                    addFriendCode = decoded
                                    addFriendError = null
                                } else {
                                    addFriendError = I18n.t("friend.invalid_clipboard_qr")
                                }
                            } else {
                                addFriendError = I18n.t("friend.no_qr_in_clipboard")
                            }
                        } else {
                            addFriendError = I18n.t("friend.no_image_in_clipboard")
                        }
                    } catch (e: Exception) {
                        addFriendError = I18n.t("friend.read_clipboard_failed", e.message ?: "")
                    }
                }
            }
        )
    }

    // 视频通话浮层
    activeCallSession?.let { session ->
        com.pmcl.ui.widget.VideoCallOverlay(
            session = session,
            onEndCall = {
                scope.launch(Dispatchers.IO) {
                    session.end()
                    friendManager.sendCallEnd(session.remoteIdentity, session.callId, I18n.t("friend.user_hangup"))
                    activeCallSession = null
                }
            },
            onToggleMute = { muted -> session.setMute(muted) },
            onToggleCamera = { enabled -> session.setCameraEnabled(enabled) }
        )
    }

    // 来电提示
    incomingCall?.let { (callerId, callerName) ->
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            com.pmcl.ui.widget.IncomingCallCard(
                callerName = callerName,
                onAccept = {
                    scope.launch(Dispatchers.IO) {
                        com.pmcl.video.VideoCallManager.init()
                        val callId = java.util.UUID.randomUUID().toString()
                        val callerPort = pendingCallerVideoPort
                        
                        val session = com.pmcl.video.VideoCallSession(
                            callId, callerId, callerName,
                            false, com.pmcl.video.VideoCallSession.MediaType.AUDIO_VIDEO
                        )
                        
                        // 创建视频 socket
                        val videoSocket = java.net.DatagramSocket()
                        val videoPort = videoSocket.localPort
                        try {
                            val f = com.pmcl.video.VideoCallSession::class.java.getDeclaredField("videoSocket")
                            f.isAccessible = true
                            f.set(session, videoSocket)
                        } catch (_: Exception) {}
                        
                        // 设置远端视频端口
                        if (callerPort > 0) {
                            session.onRemoteVideoPort(callerPort)
                        }
                        
                        session.addListener(object : com.pmcl.video.VideoCallSession.CallListener {
                            override fun onStateChanged(state: com.pmcl.video.VideoCallSession.State) {
                                if (state == com.pmcl.video.VideoCallSession.State.ENDED) {
                                    activeCallSession = null
                                }
                            }
                            override fun onLocalCandidate(candidateSdp: String, ufrag: String, pwd: String) {
                                friendManager.sendCallIceCandidate(callerId, callId, candidateSdp, 0, "1", ufrag, pwd)
                            }
                            override fun onRemoteFrame(frame: java.awt.image.BufferedImage?) {}
                            override fun onLocalFrame(frame: java.awt.image.BufferedImage?) {}
                            override fun onVideoPortReady(port: Int) {}
                            override fun onError(message: String) {
                                System.err.println("[VideoCall] $message")
                                activeCallSession = null
                            }
                        })
                        activeCallSession = session
                        friendManager.sendCallAccept(callerId, callId, "", videoPort)
                        incomingCall = null
                        session.startIceNegotiation()
                    }
                },
                onReject = {
                    scope.launch(Dispatchers.IO) {
                        friendManager.sendCallReject(callerId, "", I18n.t("friend.call_rejected"))
                        incomingCall = null
                    }
                }
            )
        }
    }
}

// =============================================================================
// 好友列表项
// =============================================================================

@Composable
private fun FriendListItem(
    friend: FriendStore.FriendEntry,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 头像 + 在线状态角标
            Box(contentAlignment = Alignment.BottomEnd) {
                Surface(
                    modifier = Modifier.size(38.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = friend.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                // 在线状态小圆点叠加在头像右下角
                Surface(
                    modifier = Modifier.size(11.dp),
                    shape = CircleShape,
                    color = if (friend.online) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                    border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface)
                ) {}
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(friend.displayName, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (friend.online) I18n.t("friend.online") else I18n.t("friend.offline"),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (friend.online) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

// =============================================================================
// 好友请求卡片
// =============================================================================

@Composable
private fun FriendRequestCard(
    request: FriendManager.PendingRequest,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // 头像首字母
            Surface(
                modifier = Modifier.size(32.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        request.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(request.displayName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    request.identity.toString().take(13) + "...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f),
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onAccept, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Check, I18n.t("friend.accept"),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onReject, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, I18n.t("friend.reject"),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// =============================================================================
// 聊天视图
// =============================================================================

@Composable
private fun ChatView(
    friendName: String,
    friendOnline: Boolean,
    messages: List<FriendStore.StoredMessage>,
    inputText: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onDeleteFriend: () -> Unit,
    onVideoCall: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val canSend = inputText.isNotBlank()

    Column(Modifier.fillMaxSize()) {
        // 聊天头部：好友信息 + 操作
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        friendName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(friendName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(6.dp),
                        shape = CircleShape,
                        color = if (friendOnline) Color(0xFF4CAF50) else Color(0xFFBDBDBD)
                    ) {}
                    Spacer(Modifier.width(4.dp))
                    Text(
                        if (friendOnline) I18n.t("friend.online") else I18n.t("friend.offline"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (friendOnline) Color(0xFF4CAF50)
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
            // 视频通话按钮（仅在线时可点击）
            IconButton(onClick = onVideoCall, modifier = Modifier.size(32.dp), enabled = friendOnline) {
                Icon(Icons.Filled.Videocam, I18n.t("friend.video_call"),
                    modifier = Modifier.size(18.dp),
                    tint = if (friendOnline) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            }
            IconButton(onClick = onDeleteFriend, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.PersonRemove, I18n.t("friend.remove_friend"),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        // 消息列表
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
        ) {
            items(messages.toList(), key = { it.id }) { msg ->
                MessageBubble(
                    text = msg.text,
                    time = timeFormat.format(Date(msg.timestamp)),
                    fromMe = msg.fromMe,
                    modifier = Modifier.animateItem(
                        fadeInSpec = tween(250, easing = FastOutSlowInEasing),
                        placementSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
                )
            }
        }

        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        // 统一输入区：圆角容器包裹输入框 + 发送按钮
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(I18n.t("friend.input_message_placeholder"), style = MaterialTheme.typography.bodyMedium) },
                    singleLine = false,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )
                Spacer(Modifier.width(4.dp))
                SendButton(canSend = canSend, onClick = onSend)
            }
        }
    }
}

// =============================================================================
// 发送按钮（带按压缩放动画）
// =============================================================================

@Composable
private fun SendButton(canSend: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && canSend) 0.82f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "sendScale"
    )

    Surface(
        modifier = Modifier.size(38.dp).clip(CircleShape)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = canSend,
                onClick = onClick
            ),
        shape = CircleShape,
        color = if (canSend) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surface
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.AutoMirrored.Filled.Send, I18n.t("friend.send"),
                modifier = Modifier.size(18.dp),
                tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            )
        }
    }
}

// =============================================================================
// 消息气泡（带滑入动画）
// =============================================================================

@Composable
private fun MessageBubble(
    text: String,
    time: String,
    fromMe: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (fromMe) 14.dp else 4.dp,
                bottomEnd = if (fromMe) 4.dp else 14.dp
            ),
            color = if (fromMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

// =============================================================================
// 添加好友弹窗
// =============================================================================

@Composable
private fun AddFriendDialog(
    code: String,
    error: String?,
    onCodeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onScanStart: () -> Unit,
    onScanClipboard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(I18n.t("friend.add_friend"), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(I18n.t("friend.add_friend_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(I18n.t("friend.paste_or_scan")) },
                    singleLine = false,
                    minLines = 2
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onScanStart, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(Icons.Filled.Image, I18n.t("friend.scan_image"), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("friend.scan_image"), style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(onClick = onScanClipboard, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                        Icon(Icons.Filled.ContentPaste, I18n.t("friend.clipboard"), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(I18n.t("friend.clipboard"), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onAdd, enabled = code.isNotBlank()) {
                Text(I18n.t("friend.add"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(I18n.t("common.cancel"))
            }
        }
    )
}
