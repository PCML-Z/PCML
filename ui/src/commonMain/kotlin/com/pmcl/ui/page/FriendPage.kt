package com.pmcl.ui.page

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pmcl.core.friend.FriendIdentity
import com.pmcl.core.friend.FriendIdentityManager
import com.pmcl.core.friend.FriendManager
import com.pmcl.core.friend.FriendStore
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.viewmodel.LauncherViewModel
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image as SkiaImage
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun FriendPage(vm: LauncherViewModel) {
    val friendManager = remember { vm.core.friend() }
    if (friendManager == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("好友系统暂不可用", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var friends by remember { mutableStateOf(friendManager.getFriends()) }
    var selectedFriendId by remember { mutableStateOf<String?>(null) }
    var messages by remember { mutableStateOf<List<FriendStore.StoredMessage>>(emptyList()) }
    var inputText by remember { mutableStateOf("") }
    var showQrDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addFriendCode by remember { mutableStateOf("") }
    var showIdentityCard by remember { mutableStateOf(false) }
    val pendingRequests = remember { mutableStateListOf<FriendManager.PendingRequest>() }

    // 刷新回调
    val refresh = {
        friends = friendManager.getFriends()
        selectedFriendId?.let { id ->
            messages = friendManager.getMessages(id)
        }
    }

    // 监听事件
    DisposableEffect(friendManager) {
        val listener: (FriendManager.FriendEvent) -> Unit = {
            scope.launch { refresh() }
        }
        friendManager.addListener(listener)
        onDispose { friendManager.removeListener(listener) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // 标题栏
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.People, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(I18n.t("nav.friends"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Spacer(Modifier.weight(1f))

            // 身份卡片按钮
            IconButton(onClick = { showIdentityCard = true }) {
                Icon(Icons.Filled.Badge, "身份", tint = MaterialTheme.colorScheme.primary)
            }

            // 二维码按钮
            IconButton(onClick = { showQrDialog = true }) {
                Icon(Icons.Filled.QrCode, "二维码", tint = MaterialTheme.colorScheme.primary)
            }

            // 添加好友按钮
            IconButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.PersonAdd, "添加好友", tint = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(12.dp))

        // 在线/离线状态提示
        if (friendManager.getState() != FriendManager.State.RUNNING) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WifiOff, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(8.dp))
                    Text("加入联机房间后可开始聊天", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Row(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    Icon(Icons.Filled.Link, null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(4.dp))
                    Text("P2P 聊天基于联机网络，无需服务器", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        if (friends.isEmpty() && pendingRequests.isEmpty()) {
            // 空状态
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.PeopleOutline, null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(8.dp))
                    Text("还没有好友", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    Spacer(Modifier.height(4.dp))
                    Text("加入联机房间后，可识别附近玩家并互加好友",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                // 左侧：好友列表
                Column(Modifier.width(200.dp).fillMaxHeight()) {
                    // 待处理请求
                    if (pendingRequests.isNotEmpty()) {
                        Text("好友请求", style = MaterialTheme.typography.labelMedium,
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

                    Text("好友", style = MaterialTheme.typography.labelMedium,
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

                VerticalDivider(Modifier.padding(horizontal = 8.dp))

                // 右侧：聊天区
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
                            }
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.AutoMirrored.Filled.Chat, null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                                Spacer(Modifier.height(8.dp))
                                Text("选择一个好友开始聊天",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }

    // 二维码弹窗
    if (showQrDialog) {
        QrCodeDialog(
            identityManager = friendManager.getIdentityManager(),
            onDismiss = { showQrDialog = false }
        )
    }

    // 添加好友弹窗
    if (showAddDialog) {
        AddFriendDialog(
            code = addFriendCode,
            onCodeChange = { addFriendCode = it },
            onDismiss = { showAddDialog = false },
            onAdd = {
                val info = FriendIdentityManager.parseInvite(addFriendCode)
                if (info != null) {
                    friendManager.sendFriendRequest(
                        info.identity.toString(),
                        info.displayName,
                        "",   // 需要知道 IP，留空让发现机制补充
                        0
                    )
                    addFriendCode = ""
                }
            },
            onScanStart = {
                // TODO: 扫描二维码（可后续集成摄像头或文件选择）
            }
        )
    }

    // 身份卡片弹窗
    if (showIdentityCard) {
        IdentityCardDialog(
            identityManager = friendManager.getIdentityManager(),
            onDismiss = { showIdentityCard = false }
        )
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
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 在线状态指示器
            Box(Modifier.size(10.dp).padding(1.dp)) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = CircleShape,
                    color = if (friend.online) Color(0xFF4CAF50) else Color(0xFFBDBDBD)
                ) {}
            }
            Spacer(Modifier.width(8.dp))

            // 昵称首字母头像
            Surface(
                modifier = Modifier.size(36.dp),
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

            Spacer(Modifier.width(8.dp))

            Column(Modifier.weight(1f)) {
                Text(friend.displayName, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (friend.online) "在线" else "离线",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (friend.online) Color(0xFF4CAF50)
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(Modifier.padding(8.dp)) {
            Text(request.displayName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            Text(request.identity.toString(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onAccept, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("接受", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onReject, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("拒绝", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
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
    onDeleteFriend: () -> Unit
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        // 聊天标题
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(friendName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(8.dp))
            Surface(
                modifier = Modifier.size(8.dp),
                shape = CircleShape,
                color = if (friendOnline) Color(0xFF4CAF50) else Color(0xFFBDBDBD)
            ) {}
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDeleteFriend, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.PersonRemove, "删除好友",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // 消息列表
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(messages.toList(), key = { it.id }) { msg ->
                MessageBubble(
                    text = msg.text,
                    time = timeFormat.format(Date(msg.timestamp)),
                    fromMe = msg.fromMe
                )
            }
        }

        // 滚动到底部
        LaunchedEffect(messages.size) {
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }

        // 输入框
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息...", style = MaterialTheme.typography.bodySmall) },
                singleLine = false,
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = inputText.isNotBlank(),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, "发送",
                    tint = if (inputText.isNotBlank()) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
            }
        }
    }
}

// =============================================================================
// 消息气泡
// =============================================================================

@Composable
private fun MessageBubble(text: String, time: String, fromMe: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(
                topStart = 12.dp, topEnd = 12.dp,
                bottomStart = if (fromMe) 12.dp else 4.dp,
                bottomEnd = if (fromMe) 4.dp else 12.dp
            ),
            color = if (fromMe) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(time, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}

// =============================================================================
// 二维码弹窗
// =============================================================================

@Composable
private fun QrCodeDialog(
    identityManager: FriendIdentityManager,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我的二维码", fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val qrBytes = remember { identityManager.qrCodeBytes }
                val bitmap = remember(qrBytes) {
                    if (qrBytes != null && qrBytes.isNotEmpty()) {
                        try {
                            SkiaImage.makeFromEncoded(qrBytes).toComposeImageBitmap()
                        } catch (e: Exception) {
                            null as ImageBitmap?
                        }
                    } else null
                }

                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = "好友二维码",
                        modifier = Modifier.size(200.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
                        Text("二维码生成失败", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }

                Spacer(Modifier.height(12.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = identityManager.identity.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = identityManager.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

// =============================================================================
// 添加好友弹窗
// =============================================================================

@Composable
private fun AddFriendDialog(
    code: String,
    onCodeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onScanStart: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加好友", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("请输入好友的邀请码或粘贴分享文本",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = code,
                    onValueChange = onCodeChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("粘贴邀请码或扫描二维码") },
                    singleLine = false,
                    minLines = 2
                )

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onScanStart,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.QrCodeScanner, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("扫描二维码")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAdd,
                enabled = code.isNotBlank()
            ) {
                Text("添加")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// =============================================================================
// 身份卡片弹窗
// =============================================================================

@Composable
private fun IdentityCardDialog(
    identityManager: FriendIdentityManager,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我的身份", fontWeight = FontWeight.Bold) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 头像区域
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = identityManager.displayName.take(1).uppercase(),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(identityManager.displayName,
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                Spacer(Modifier.height(8.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = identityManager.identity.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "P2P 加密聊天 · 基于联机网络",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
