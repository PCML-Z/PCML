package com.pmcl.ui.studio

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pmcl.core.friend.FriendIdentityManager
import com.pmcl.core.friend.FriendManager
import com.pmcl.core.friend.FriendStore
import com.pmcl.core.i18n.I18n
import com.pmcl.core.mail.MailAccount
import com.pmcl.core.mail.MailAccountStore
import com.pmcl.core.mail.MailClient
import com.pmcl.core.mail.MailCodec
import com.pmcl.core.mail.MailException
import com.pmcl.core.multiplayer.MultiplayerManager
import com.pmcl.core.nbt.NbtTag
import com.pmcl.music.player.PlaybackState
import com.pmcl.ui.navigation.PmclIcons
import com.pmcl.ui.page.MusicTrack
import com.pmcl.ui.viewmodel.addLocalMusicFiles
import com.pmcl.ui.viewmodel.addLocalMusicFolder
import com.pmcl.ui.viewmodel.addNbtChild
import com.pmcl.ui.viewmodel.clearMusicHistory
import com.pmcl.ui.viewmodel.clearMusicPlaylist
import com.pmcl.ui.viewmodel.closeNbtFile
import com.pmcl.ui.viewmodel.copyInvitation
import com.pmcl.ui.viewmodel.createMusicPlaylist
import com.pmcl.ui.viewmodel.createRoom
import com.pmcl.ui.viewmodel.currentMusicTrack
import com.pmcl.ui.viewmodel.cycleMusicRepeatMode
import com.pmcl.ui.viewmodel.deleteMusicPlaylist
import com.pmcl.ui.viewmodel.exportNbtSnbt
import com.pmcl.ui.viewmodel.joinFriendMultiplayer
import com.pmcl.ui.viewmodel.joinRoom
import com.pmcl.ui.viewmodel.leaveRoom
import com.pmcl.ui.viewmodel.openNbtFile
import com.pmcl.ui.viewmodel.playMusicAt
import com.pmcl.ui.viewmodel.playMusicFromHistory
import com.pmcl.ui.viewmodel.playNextMusic
import com.pmcl.ui.viewmodel.playPreviousMusic
import com.pmcl.ui.viewmodel.publishFriendMpSession
import com.pmcl.ui.viewmodel.redoNbt
import com.pmcl.ui.viewmodel.refreshMpState
import com.pmcl.ui.viewmodel.refreshWorlds
import com.pmcl.ui.viewmodel.removeMusicHistoryAt
import com.pmcl.ui.viewmodel.removeMusicTrack
import com.pmcl.ui.viewmodel.removeNbtChild
import com.pmcl.ui.viewmodel.removeNbtListItem
import com.pmcl.ui.viewmodel.renameNbtChild
import com.pmcl.ui.viewmodel.resolveAndAddMusicTrack
import com.pmcl.ui.viewmodel.saveNbtFile
import com.pmcl.ui.viewmodel.seekMusicTo
import com.pmcl.ui.viewmodel.setMpBackend
import com.pmcl.ui.viewmodel.setMusicVolume
import com.pmcl.ui.viewmodel.setNbtLeafValue
import com.pmcl.ui.viewmodel.stopMusic
import com.pmcl.ui.viewmodel.switchMusicPlaylist
import com.pmcl.ui.viewmodel.syncConnectXConfig
import com.pmcl.ui.viewmodel.syncEasyTierConfig
import com.pmcl.ui.viewmodel.syncRedstoneConfig
import com.pmcl.ui.viewmodel.toggleMusicMute
import com.pmcl.ui.viewmodel.toggleMusicPlayPause
import com.pmcl.ui.viewmodel.toggleMusicShuffle
import com.pmcl.ui.viewmodel.undoNbt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.function.Consumer

// ───────────────────────────── 多人联机 ─────────────────────────────

@Composable
internal fun MultiplayerModule(ctx: ModuleCtx) {
    when (ctx.section) {
        "settings" -> MpSettingsSection(ctx)
        "help" -> MpHelpSection(ctx)
        else -> MpRoomSection(ctx)
    }
}

private fun mpStateColor(s: MultiplayerManager.State): Color = when (s) {
    MultiplayerManager.State.CONNECTED -> Tiles.Forest
    MultiplayerManager.State.CONNECTING, MultiplayerManager.State.DOWNLOADING -> Tiles.Orange
    MultiplayerManager.State.FAILED -> Tiles.Red
    else -> Tiles.Slate
}

private fun backendLabel(b: MultiplayerManager.Backend): String = when (b) {
    MultiplayerManager.Backend.EASYTIER -> "EasyTier"
    MultiplayerManager.Backend.CONNECTX -> "ConnectX"
    MultiplayerManager.Backend.TERRACOTTA -> "Terracotta"
    MultiplayerManager.Backend.REDSTONE -> "Redstone"
}

@Composable
private fun MpRoomSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val state by vm.mpState.collectAsState()
    val progress by vm.mpProgress.collectAsState()
    val ip by vm.mpVirtualIp.collectAsState()
    val invitation by vm.mpInvitation.collectAsState()
    val localAddr by vm.mpLocalMcAddr.collectAsState()
    val backend by vm.mpBackendState.collectAsState()
    LaunchedEffect(Unit) { vm.refreshMpState() }
    var code by rememberSaveable { mutableStateOf("") }
    val active = state == MultiplayerManager.State.CONNECTING || state == MultiplayerManager.State.CONNECTED || state == MultiplayerManager.State.DOWNLOADING
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ModuleHero(mt("mp_title"), mt("mp_sub", backendLabel(backend)), ctx.color, PmclIcons.Multiplayer) {
            if (active) HeroButton(mt("leave_room"), Icons.AutoMirrored.Filled.Logout, ctx.color, { vm.leaveRoom() })
            else HeroButton(mt("create_room"), Icons.Filled.Add, ctx.color, { vm.createRoom() })
        }
        TileRow(96.dp) {
            ColorTile(mt("status"), mt("mp_state_" + state.name.lowercase()), mpStateColor(state), Modifier.weight(1f).fillMaxHeight(), Icons.Filled.Wifi, valueSize = 18, index = 0)
            ColorTile(mt("invitation"), invitation.ifBlank { "—" }, Tiles.Navy, Modifier.weight(1.6f).fillMaxHeight(), Icons.Filled.ContentCopy,
                if (invitation.isNotBlank()) ({ vm.copyInvitation() }) else null, valueSize = 14, index = 1)
            ColorTile(mt("virtual_ip"), ip.ifBlank { "—" }, Tiles.Sky, Modifier.weight(1f).fillMaxHeight(), valueSize = 15, index = 2)
            ColorTile(mt("local_addr"), localAddr.ifBlank { "—" }, Tiles.Blue, Modifier.weight(1f).fillMaxHeight(), Icons.Filled.ContentCopy,
                if (localAddr.isNotBlank()) ({ copyText(localAddr) }) else null, valueSize = 15, index = 3)
        }
        if (progress.isNotBlank() || state == MultiplayerManager.State.FAILED) {
            Column(Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 16.dp, vertical = 8.dp)) {
                FlatText(if (state == MultiplayerManager.State.FAILED) vm.mpLastError.ifBlank { progress } else progress,
                    color = if (state == MultiplayerManager.State.FAILED) Tiles.Red else p.text, size = 11, lines = 4)
                if (state == MultiplayerManager.State.CONNECTING || state == MultiplayerManager.State.DOWNLOADING) {
                    Spacer(Modifier.height(6.dp)); ProgressLine(null, Tiles.Orange)
                }
            }
        }
        LabelBar(mt("backend"), ctx.color)
        Box(Modifier.padding(10.dp)) {
            Chips(MultiplayerManager.Backend.entries.map { it.name to backendLabel(it) }, backend.name, { vm.setMpBackend(MultiplayerManager.Backend.valueOf(it)) }, ctx.color, enabled = !active)
        }
        LabelBar(mt("join_room"), ctx.color)
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            InputBox(code, { code = it.trim() }, mt("invitation_hint"), Modifier.weight(1f), mono = true, onSubmit = { if (code.isNotBlank() && !active) vm.joinRoom(code) })
            Block(mt("join"), { vm.joinRoom(code) }, icon = Icons.Filled.Link, fill = ctx.color, enabled = code.isNotBlank() && !active, height = 30.dp)
        }
        LabelBar(mt("share"), ctx.color)
        Row(Modifier.padding(10.dp)) {
            Block(mt("copy_invitation"), { vm.copyInvitation() }, icon = Icons.Filled.ContentCopy, fill = Tiles.Navy, enabled = invitation.isNotBlank(), height = 32.dp)
            Block(mt("publish_friends"), { vm.publishFriendMpSession() }, icon = Icons.Filled.Share, fill = Tiles.Purple, enabled = state == MultiplayerManager.State.CONNECTED, height = 32.dp)
            Block(mt("refresh"), { vm.refreshMpState() }, icon = Icons.Filled.Refresh, height = 32.dp)
        }
        FlatText(mt("mp_hint"), color = p.muted, size = 11, lines = 5, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
private fun MpSettingsSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val pref = vm.preferences
    val backend by vm.mpBackendState.collectAsState()
    var peer by remember { mutableStateOf(pref.getEasytierPeer().orEmpty()) }
    var cxHost by remember { mutableStateOf(pref.getConnectxServerAddress().orEmpty()) }
    var cxPort by remember { mutableStateOf(pref.getConnectxServerPort().toString()) }
    var cxBin by remember { mutableStateOf(pref.getConnectxBinaryPath().orEmpty()) }
    var relay by remember { mutableStateOf(pref.getRedstoneRelay().orEmpty()) }
    var rsPort by remember { mutableStateOf(pref.getRedstoneLocalPort().toString()) }
    var rsMax by remember { mutableStateOf(pref.getRedstoneMaxPlayers().toString()) }
    var saved by remember { mutableStateOf(false) }
    fun digits(v: String) = v.filter { it.isDigit() }.take(5)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ModuleHero(mt("mp_settings_title"), backendLabel(backend), ctx.color, PmclIcons.Multiplayer) {
            HeroButton(if (saved) mt("saved") else mt("save"), Icons.Filled.Save, ctx.color, {
                pref.setEasytierPeer(peer.trim())
                pref.setConnectxServerAddress(cxHost.trim())
                cxPort.toIntOrNull()?.let { pref.setConnectxServerPort(it) }
                pref.setConnectxBinaryPath(cxBin.trim())
                pref.setRedstoneRelay(relay.trim())
                rsPort.toIntOrNull()?.let { pref.setRedstoneLocalPort(it) }
                rsMax.toIntOrNull()?.let { pref.setRedstoneMaxPlayers(it) }
                vm.syncEasyTierConfig(); vm.syncConnectXConfig(); vm.syncRedstoneConfig()
                saved = true
            })
        }
        LabelBar("EasyTier", if (backend == MultiplayerManager.Backend.EASYTIER) ctx.color else Tiles.Slate)
        FormRow(mt("easytier_peer"), 0) { InputBox(peer, { peer = it; saved = false }, "tcp://public.easytier.top:11010", Modifier.weight(1f), mono = true) }
        LabelBar("ConnectX", if (backend == MultiplayerManager.Backend.CONNECTX) ctx.color else Tiles.Slate)
        FormRow(mt("server_address"), 0) { InputBox(cxHost, { cxHost = it; saved = false }, "example.com", Modifier.weight(1f), mono = true) }
        FormRow(mt("port"), 1) { InputBox(cxPort, { cxPort = digits(it); saved = false }, "0", Modifier.width(120.dp), mono = true) }
        FormRow(mt("binary_path"), 2) {
            InputBox(cxBin, { cxBin = it; saved = false }, mt("auto"), Modifier.weight(1f), mono = true)
            Block("", { pickFile(mt("binary_path")).firstOrNull()?.let { cxBin = it.toString(); saved = false } }, icon = Icons.Filled.FileOpen, fill = Tiles.Slate, height = 30.dp)
        }
        LabelBar("Redstone", if (backend == MultiplayerManager.Backend.REDSTONE) ctx.color else Tiles.Slate)
        FormRow(mt("relay"), 0) { InputBox(relay, { relay = it; saved = false }, "relay.example.com:7000", Modifier.weight(1f), mono = true) }
        FormRow(mt("local_port"), 1) { InputBox(rsPort, { rsPort = digits(it); saved = false }, "0", Modifier.width(120.dp), mono = true) }
        FormRow(mt("max_players"), 2) { InputBox(rsMax, { rsMax = digits(it); saved = false }, "8", Modifier.width(120.dp), mono = true) }
    }
}

@Composable
private fun MpHelpSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val backend by vm.mpBackendState.collectAsState()
    val blocks: List<Pair<String, List<String>>> = when (backend) {
        MultiplayerManager.Backend.TERRACOTTA -> listOf(
            I18n.t("mp.host_label") to (1..3).map { I18n.t("mp.usage.terracotta.host.$it") },
            I18n.t("mp.guest_label") to (1..3).map { I18n.t("mp.usage.terracotta.guest.$it") },
            "" to listOf(I18n.t("mp.usage.terracotta.note")),
        )
        MultiplayerManager.Backend.REDSTONE -> listOf(
            I18n.t("mp.host_label") to (1..3).map { I18n.t("mp.usage.redstone.host.$it") },
            I18n.t("mp.guest_label") to listOf(I18n.t("mp.usage.redstone.guest.1")),
            "" to listOf(I18n.t("mp.usage.redstone.note")),
        )
        MultiplayerManager.Backend.CONNECTX -> listOf(
            "ConnectX" to (1..5).map { I18n.t("mp.usage.connectx.$it") },
            "" to listOf(I18n.t("mp.usage.connectx.note")),
        )
        else -> listOf(
            "EasyTier" to (1..5).map { I18n.t("mp.usage.easytier.$it") },
            "" to listOf(I18n.t("mp.usage.easytier.warning"), I18n.t("mp.usage.easytier.note")),
        )
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ModuleHero(I18n.t("mp.section.help"), backendLabel(backend), ctx.color, PmclIcons.Multiplayer)
        var n = 0
        blocks.forEach { (title, lines) ->
            if (title.isNotEmpty()) LabelBar(title, ctx.color)
            lines.forEachIndexed { i, line ->
                Row(Modifier.fillMaxWidth().entrance(n++).background(if (i % 2 == 0) p.editor else p.panel).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (title.isNotEmpty()) {
                        FlatText((i + 1).toString(), color = Color.White, weight = FontWeight.SemiBold, modifier = Modifier.width(26.dp).background(ctx.color).padding(horizontal = 8.dp, vertical = 3.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    FlatText(line, size = 12, lines = 6, color = if (title.isEmpty()) p.muted else p.text, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ───────────────────────────── 好友 ─────────────────────────────

@Composable
internal fun FriendsModule(ctx: ModuleCtx) {
    when (ctx.section) {
        "mail" -> MailSection(ctx)
        "rooms" -> FriendRoomsSection(ctx)
        else -> FriendChatSection(ctx)
    }
}

/** 好友网络：启动、监听事件，事件到达时让 tick 自增，调用方据此重读。 */
@Composable
private fun rememberFriendTick(fm: FriendManager): Pair<Int, MutableList<FriendManager.PendingRequest>> {
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val pending = remember { mutableStateListOf<FriendManager.PendingRequest>() }
    DisposableEffect(fm) {
        val listener = Consumer<FriendManager.FriendEvent> { event ->
            scope.launch {
                if (event.type == FriendManager.FriendEvent.Type.FRIEND_REQUEST_RECEIVED) {
                    val r = event.data as? FriendManager.PendingRequest
                    if (r != null && pending.none { it.identity == r.identity }) pending.add(r)
                }
                tick++
            }
        }
        fm.addListener(listener)
        onDispose { fm.removeListener(listener) }
    }
    LaunchedEffect(fm) {
        if (fm.state != FriendManager.State.RUNNING) {
            withContext(Dispatchers.IO) { runCatching { fm.start() } }
            tick++
        }
    }
    return tick to pending
}

@Composable
private fun FriendChatSection(ctx: ModuleCtx) {
    val fm = remember { ctx.vm.core.friend() }
    if (fm == null) {
        EmptyState(mt("friend_unavailable"), PmclIcons.Friends, color = ctx.color)
        return
    }
    val p = LocalStudio.current
    val (tick, pending) = rememberFriendTick(fm)
    val friends = remember(tick) { runCatching { fm.getFriends().toList() }.getOrDefault(emptyList()) }
    val groups = remember(tick) { runCatching { fm.groups.toList() }.getOrDefault(emptyList()) }
    val identity = remember(tick) { runCatching { fm.identityManager }.getOrNull() }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var bump by remember { mutableIntStateOf(0) }
    val messages = remember(tick, bump, selected) { selected?.let { k -> runCatching { fm.getMessages(k).toList() }.getOrDefault(emptyList()) }.orEmpty() }
    var input by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var inviteError by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, selected) { if (messages.isNotEmpty()) listState.scrollToItem(messages.size - 1) }
    val isGroup = selected?.startsWith("g:") == true
    val activeFriend = friends.firstOrNull { it.identity == selected }
    val activeGroup = groups.firstOrNull { FriendStore.groupKey(it.id) == selected }

    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("friends_title"), mt("friends_sub", friends.size, friends.count { it.online }, mt("mp_state_" + fm.state.name.lowercase())), ctx.color, PmclIcons.Friends) {
            HeroButton(mt("copy_my_code"), Icons.Filled.ContentCopy, ctx.color, { identity?.shareText?.let { copyText(it) } }, enabled = identity != null)
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(300.dp).fillMaxHeight().background(p.panel)) {
                if (identity != null) {
                    ItemRow(identity.displayName.orEmpty(), mt("me"), ctx.color, 0, icon = Icons.Filled.Person, height = 48.dp)
                }
                LabelBar(mt("add_friend"), ctx.color)
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    InputBox(invite, { invite = it.trim(); inviteError = "" }, mt("invite_hint"), Modifier.weight(1f), mono = true)
                    Block("", {
                        val info = FriendIdentityManager.parseInvite(invite)
                        if (info == null) inviteError = I18n.t("friend.invalid_invite_code")
                        else {
                            runCatching { fm.sendFriendRequest(info.identity.toString(), info.displayName, "", 0, info.ed25519Public, info.x25519Public) }
                            invite = ""
                        }
                    }, icon = Icons.Filled.PersonAdd, fill = ctx.color, enabled = invite.isNotBlank(), height = 30.dp)
                }
                if (inviteError.isNotBlank()) FlatText(inviteError, color = Tiles.Red, size = 11, modifier = Modifier.padding(horizontal = 10.dp))
                LazyColumn(Modifier.weight(1f)) {
                    if (pending.isNotEmpty()) {
                        item { LabelBar(mt("requests_n", pending.size), Tiles.Orange) }
                        itemsIndexed(pending.toList(), key = { _, r -> "req:" + r.identity }) { i, r ->
                            ItemRow(r.displayName.orEmpty(), r.ip.orEmpty(), Tiles.Orange, i, icon = Icons.Filled.PersonAdd, height = 44.dp) {
                                RowButton(Icons.Filled.Check, { runCatching { fm.acceptFriendRequest(r) }; pending.remove(r) }, ctx.color)
                                RowButton(Icons.Filled.Close, { runCatching { fm.rejectFriendRequest(r) }; pending.remove(r) })
                            }
                        }
                    }
                    if (groups.isNotEmpty()) item { LabelBar(mt("groups_n", groups.size), Tiles.Purple) }
                    itemsIndexed(groups, key = { _, g -> "g:" + g.id }) { i, g ->
                        ItemRow(g.name.orEmpty(), mt("members_n", g.members?.size ?: 0), Tiles.Purple, i, icon = Icons.Filled.Group, height = 44.dp,
                            selected = selected == FriendStore.groupKey(g.id), onClick = { selected = FriendStore.groupKey(g.id) })
                    }
                    item { LabelBar(mt("friends_n", friends.size), ctx.color) }
                    itemsIndexed(friends.sortedByDescending { it.online }, key = { _, f -> "f:" + f.identity }) { i, f ->
                        ItemRow(f.displayName.orEmpty().ifBlank { f.identity }, if (f.online) mt("online") else mt("last_seen", fmtDate(f.lastSeen)),
                            if (f.online) ctx.color else Tiles.Slate, i, badge = f.displayName.orEmpty().ifBlank { "?" }.take(1).uppercase(), dim = !f.online, height = 44.dp,
                            selected = selected == f.identity, onClick = { selected = f.identity })
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (selected == null || (activeFriend == null && activeGroup == null)) {
                    EmptyState(mt("pick_chat"), PmclIcons.Friends, color = ctx.color)
                } else {
                    Toolbar {
                        FlatText(activeGroup?.name ?: activeFriend?.displayName.orEmpty(), weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
                        if (activeFriend != null) FlatText(if (activeFriend.online) mt("online") else mt("offline"), color = p.muted, size = 11)
                        Spacer(Modifier.width(12.dp))
                        Block(if (isGroup) mt("leave_group") else mt("remove_friend"), {
                            val name = activeGroup?.name ?: activeFriend?.displayName.orEmpty()
                            ctx.confirm(if (isGroup) mt("leave_group") else mt("remove_friend"), mt("remove_friend_body", name)) {
                                runCatching { if (activeGroup != null) fm.leaveGroup(activeGroup.id) else fm.removeFriend(activeFriend!!.identity) }
                                selected = null
                            }
                        }, icon = Icons.Filled.Delete, height = 36.dp)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth().background(p.editor).padding(horizontal = 12.dp), listState) {
                        itemsIndexed(messages, key = { i, m -> "$i:${m.id}" }) { i, m ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (m.fromMe) Arrangement.End else Arrangement.Start) {
                                Column(Modifier.widthIn(max = 520.dp).entrance(i.coerceAtMost(30)).background(if (m.fromMe) ctx.color else p.panelAlt).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                    if (isGroup && !m.fromMe) FlatText(m.senderName.orEmpty(), color = p.muted, size = 10)
                                    FlatText(m.text.orEmpty(), color = if (m.fromMe) Color.White else p.text, size = 13, lines = 40)
                                    FlatText(fmtDate(m.timestamp), color = if (m.fromMe) Color.White.copy(alpha = 0.7f) else p.muted, size = 9)
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().background(p.panelAlt).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        fun send() {
                            val text = input.trim()
                            if (text.isEmpty()) return
                            runCatching { if (activeGroup != null) fm.sendGroupMessage(activeGroup.id, text) else fm.sendMessage(activeFriend!!.identity, text) }
                            input = ""
                            bump++
                        }
                        InputBox(input, { input = it }, mt("message_hint"), Modifier.weight(1f), onSubmit = { send() })
                        Block(mt("send"), { send() }, icon = Icons.AutoMirrored.Filled.Send, fill = ctx.color, enabled = input.isNotBlank(), height = 30.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRoomsSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val fm = remember { vm.core.friend() }
    if (fm == null) {
        EmptyState(mt("friend_unavailable"), PmclIcons.Friends, color = ctx.color)
        return
    }
    val (tick, _) = rememberFriendTick(fm)
    val offers = remember(tick) { runCatching { fm.multiplayerSessions.toList() }.getOrDefault(emptyList()) }
    val friends = remember(tick) { runCatching { fm.getFriends().toList() }.getOrDefault(emptyList()) }
    val state by vm.mpState.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("friend_rooms_title"), mt("items_n", offers.size), ctx.color, PmclIcons.Multiplayer) {
            HeroButton(mt("publish_friends"), Icons.Filled.Share, ctx.color, { vm.publishFriendMpSession() }, enabled = state == MultiplayerManager.State.CONNECTED)
        }
        if (offers.isEmpty()) EmptyState(mt("no_rooms"), PmclIcons.Multiplayer, color = ctx.color)
        else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(offers, key = { i, o -> "$i:${o.from}:${o.ts}" }) { i, o ->
                val who = friends.firstOrNull { it.identity == o.from }?.displayName ?: o.from.orEmpty()
                ItemRow(who, listOf(o.backend.orEmpty(), o.mcHost?.takeIf { it.isNotBlank() }?.let { "$it:${o.mcPort}" }.orEmpty(), fmtDate(o.ts)).filter { it.isNotBlank() }.joinToString(" · "),
                    ctx.color, i, icon = PmclIcons.Multiplayer) {
                    RowButton(Icons.Filled.PlayArrow, { vm.joinFriendMultiplayer(o.invitation.orEmpty(), o.backend.orEmpty(), o.mcHost.orEmpty(), o.mcPort) }, Tiles.Forest, label = mt("join"))
                }
            }
        }
    }
}

// ───────────────────────────── 邮件 ─────────────────────────────

private class MailHold {
    val mutex = Mutex()
    var session: MailClient.Session? = null
    var accountId: String? = null
}

private fun mailError(e: MailException): String {
    val base = I18n.t(e.key)
    return if (e.detail.isNullOrBlank()) base else base + "\n" + e.detail
}

private fun folderLabel(f: MailClient.Folder): String = when (f.role) {
    "inbox" -> I18n.t("friend.mail.inbox")
    "sent" -> I18n.t("friend.mail.sent")
    "drafts" -> I18n.t("friend.mail.drafts")
    "trash" -> I18n.t("friend.mail.trash")
    "junk" -> I18n.t("friend.mail.junk")
    else -> f.name
}

private fun folderIcon(f: MailClient.Folder): ImageVector = when (f.role) {
    "inbox" -> Icons.Filled.Inbox
    "sent" -> Icons.AutoMirrored.Filled.Send
    "drafts" -> Icons.Filled.Drafts
    "trash" -> Icons.Filled.Delete
    else -> Icons.Filled.Folder
}

@Composable
private fun MailSection(ctx: ModuleCtx) {
    val p = LocalStudio.current
    val store = remember { MailAccountStore.open() }
    val hold = remember { MailHold() }
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf(store.accounts()) }
    var accountId by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    var folders by remember { mutableStateOf<List<MailClient.Folder>>(emptyList()) }
    var folderName by remember { mutableStateOf<String?>(null) }
    var headers by remember { mutableStateOf<List<MailClient.Header>>(emptyList()) }
    var letter by remember { mutableStateOf<MailClient.Letter?>(null) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var composing by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<MailAccount?>(null) }
    var to by remember { mutableStateOf("") }
    var cc by remember { mutableStateOf("") }
    var subject by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var inReplyTo by remember { mutableStateOf("") }
    val account = accounts.firstOrNull { it.id == accountId }

    suspend fun <T> use(id: String, block: (MailClient.Session) -> T): T = withContext(Dispatchers.IO) {
        hold.mutex.withLock {
            val current = store.find(id) ?: throw MailException("friend.mail.err_account", "")
            if (hold.session == null || hold.accountId != current.id) {
                hold.session?.close()
                hold.session = MailClient.Session.open(current)
                hold.accountId = current.id
            }
            try {
                block(hold.session!!)
            } catch (e: MailException) {
                hold.session?.close()
                hold.session = null
                hold.accountId = null
                throw e
            }
        }
    }
    DisposableEffect(Unit) { onDispose { hold.session?.close(); hold.session = null } }
    LaunchedEffect(accountId, generation) {
        val id = accountId ?: return@LaunchedEffect
        busy = true
        error = ""
        try {
            val loaded = use(id) { it.folders() }
            folders = loaded
            if (folderName == null || loaded.none { it.name == folderName }) folderName = loaded.firstOrNull { it.role == "inbox" }?.name ?: "INBOX"
        } catch (e: MailException) {
            error = mailError(e)
        } finally {
            busy = false
        }
    }
    LaunchedEffect(accountId, folderName, generation) {
        val id = accountId ?: return@LaunchedEffect
        val name = folderName ?: return@LaunchedEffect
        busy = true
        try {
            headers = use(id) { it.messages(name) }
            letter = null
        } catch (e: MailException) {
            error = mailError(e)
        } finally {
            busy = false
        }
    }
    fun compose(reply: MailClient.Letter?) {
        composing = true
        notice = ""
        if (reply == null) {
            to = ""; cc = ""; subject = ""; body = ""; inReplyTo = ""
        } else {
            to = MailCodec.addressOnly(reply.from)
            cc = ""
            subject = when {
                reply.subject.isBlank() -> "Re:"
                reply.subject.startsWith("Re:") -> reply.subject
                else -> MailCodec.clip("Re: " + reply.subject, MailCodec.SUBJECT_MAX)
            }
            body = MailCodec.clip("\n\n" + reply.text.lineSequence().joinToString("\n") { "> $it" }, MailCodec.BODY_MAX)
            inReplyTo = reply.messageId.orEmpty()
        }
    }

    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("mail_title"), account?.address ?: I18n.t("friend.mail.no_account"), ctx.color, Icons.Filled.Mail) {
            HeroButton(I18n.t("friend.mail.compose"), Icons.Filled.Edit, ctx.color, { compose(null) }, enabled = account != null)
            HeroButton(I18n.t("friend.mail.refresh"), Icons.Filled.Refresh, ctx.color, { generation++ }, enabled = account != null && !busy)
            HeroButton(I18n.t("friend.mail.add_account"), Icons.Filled.Add, ctx.color, { editTarget = null; editingAccount = true })
        }
        if (busy) ProgressLine(null, ctx.color, height = 3.dp)
        if (error.isNotBlank()) FlatText(error, color = Tiles.Red, size = 11, lines = 3, modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 14.dp, vertical = 6.dp))
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.width(200.dp).fillMaxHeight().background(p.panel)) {
                LabelBar(mt("mail_accounts"), ctx.color)
                accounts.forEachIndexed { i, a ->
                    ItemRow(a.address, "", ctx.color, i, icon = Icons.Filled.Mail, height = 36.dp, selected = a.id == accountId,
                        onClick = { if (a.id != accountId) { accountId = a.id; folderName = null; letter = null; composing = false } })
                }
                if (account != null) {
                    Row {
                        Block(I18n.t("friend.mail.edit"), { editTarget = account; editingAccount = true }, Modifier.weight(1f), Icons.Filled.Edit, height = 30.dp)
                        Block("", {
                            ctx.confirm(I18n.t("friend.mail.remove_account"), account.address) {
                                store.delete(account.id)
                                hold.session?.close(); hold.session = null
                                accounts = store.accounts()
                                accountId = accounts.firstOrNull()?.id
                                folderName = null; letter = null; generation++
                            }
                        }, icon = Icons.Filled.Delete, height = 30.dp)
                    }
                    LabelBar(mt("folders"), ctx.color)
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        folders.forEachIndexed { i, f ->
                            ItemRow(folderLabel(f), "", Tiles.Navy, i, icon = folderIcon(f), height = 34.dp, selected = f.name == folderName && !composing,
                                onClick = { folderName = f.name; composing = false; letter = null })
                        }
                    }
                }
            }
            Column(Modifier.width(300.dp).fillMaxHeight().background(p.panelAlt)) {
                FlatText(I18n.t("friend.mail.recent", MailClient.LIST_LIMIT), color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                if (account == null) CenterNote(I18n.t("friend.mail.no_account"))
                else if (headers.isEmpty()) CenterNote(if (busy) I18n.t("studio.loading") else I18n.t("friend.mail.empty"))
                else LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(headers, key = { _, h -> h.uid }) { i, h ->
                        ItemRow(h.subject.ifBlank { I18n.t("friend.mail.no_subject") }, listOf(h.from, h.date).filter { it.isNotBlank() }.joinToString(" · "),
                            if (h.unread) ctx.color else Tiles.Slate, i, icon = Icons.Filled.Mail, height = 50.dp, selected = letter?.uid == h.uid && !composing,
                            onClick = {
                                val id = accountId ?: return@ItemRow
                                val name = folderName ?: return@ItemRow
                                composing = false
                                scope.launch {
                                    busy = true
                                    try {
                                        letter = use(id) { it.read(name, h.uid) }
                                        headers = headers.map { if (it.uid == h.uid) MailClient.Header(it.uid, it.from, it.subject, it.date, false) else it }
                                    } catch (e: MailException) {
                                        error = mailError(e)
                                    } finally {
                                        busy = false
                                    }
                                }
                            })
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().background(p.editor)) {
                val l = letter
                when {
                    editingAccount -> MailAccountForm(ctx, editTarget, busy) { input ->
                        if (input == null) {
                            editingAccount = false
                        } else scope.launch {
                            busy = true
                            error = ""
                            try {
                                val saved = withContext(Dispatchers.IO) {
                                    val prepared = store.prepare(input)
                                    MailClient.Session.open(prepared).use { it.folders() }
                                    store.save(prepared)
                                }
                                accounts = store.accounts()
                                accountId = saved.id
                                folderName = null
                                editingAccount = false
                                generation++
                            } catch (e: MailException) {
                                error = mailError(e)
                            } finally {
                                busy = false
                            }
                        }
                    }
                    composing -> MailComposer(ctx, to, cc, subject, body, notice, { to = it }, { cc = it },
                        { subject = MailCodec.clip(it, MailCodec.SUBJECT_MAX) }, { body = MailCodec.clip(it, MailCodec.BODY_MAX) },
                        onSend = {
                            val id = accountId ?: return@MailComposer
                            scope.launch {
                                busy = true
                                notice = ""
                                try {
                                    withContext(Dispatchers.IO) {
                                        val current = store.find(id) ?: throw MailException("friend.mail.err_account", "")
                                        MailClient.send(current, to, cc, subject, body, inReplyTo)
                                    }
                                    notice = I18n.t("friend.mail.sent_ok")
                                    composing = false
                                    generation++
                                } catch (e: MailException) {
                                    notice = mailError(e)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        onSave = {
                            val id = accountId ?: return@MailComposer
                            scope.launch {
                                busy = true
                                try {
                                    use(id) { it.saveDraft(to, cc, subject, body) }
                                    notice = I18n.t("friend.mail.draft_saved")
                                } catch (e: MailException) {
                                    val kept = runCatching { store.saveDraft(null, id, to, cc, subject, body) }.getOrNull()
                                    notice = if (kept != null) I18n.t("friend.mail.draft_kept") else mailError(e)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        onDiscard = { composing = false; notice = "" })
                    l == null -> EmptyState(I18n.t("friend.mail.reading_empty"), Icons.Filled.Mail, if (account != null) I18n.t("friend.mail.compose") else I18n.t("friend.mail.add_account"), ctx.color) {
                        if (account != null) compose(null) else { editTarget = null; editingAccount = true }
                    }
                    else -> {
                        Toolbar {
                            Block(I18n.t("friend.mail.reply"), { compose(l) }, icon = Icons.Filled.Reply, fill = ctx.color, height = 36.dp)
                            Block(I18n.t("friend.mail.delete"), {
                                val id = accountId ?: return@Block
                                val name = folderName ?: return@Block
                                scope.launch {
                                    busy = true
                                    try {
                                        use(id) { it.moveToTrash(name, l.uid) }
                                        letter = null
                                        generation++
                                    } catch (e: MailException) {
                                        error = mailError(e)
                                    } finally {
                                        busy = false
                                    }
                                }
                            }, icon = Icons.Filled.Delete, height = 36.dp)
                        }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp)) {
                            FlatText(l.subject.ifBlank { I18n.t("friend.mail.no_subject") }, size = 20, weight = FontWeight.Light, lines = 3)
                            Spacer(Modifier.height(8.dp))
                            FactRow(I18n.t("friend.mail.from"), l.from, 0)
                            if (l.to.isNotBlank()) FactRow(I18n.t("friend.mail.to"), l.to, 1, lines = 3)
                            if (l.cc.isNotBlank()) FactRow(I18n.t("friend.mail.cc"), l.cc, 2, lines = 3)
                            if (l.date.isNotBlank()) FactRow(mt("date"), l.date, 3)
                            if (l.fromHtml) FlatText(I18n.t("friend.mail.html_note"), color = p.muted, size = 11, modifier = Modifier.padding(vertical = 6.dp))
                            Spacer(Modifier.height(12.dp))
                            FlatText(l.text, size = 13, lines = 2000)
                            if (l.attachments.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                LabelBar(I18n.t("friend.mail.attachments"), ctx.color)
                                l.attachments.forEachIndexed { i, a ->
                                    ItemRow(a.name, if (a.data == null) I18n.t("friend.mail.attachment_big") else formatSize(a.data.size.toLong()), Tiles.Slate, i, icon = Icons.Filled.FileOpen, height = 40.dp) {
                                        val data = a.data
                                        if (data != null) RowButton(Icons.Filled.Save, { pickSaveFile(I18n.t("friend.mail.save_attachment"), a.name)?.let { runCatching { it.toFile().writeBytes(data) } } })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailComposer(
    ctx: ModuleCtx,
    to: String, cc: String, subject: String, body: String, notice: String,
    onTo: (String) -> Unit, onCc: (String) -> Unit, onSubject: (String) -> Unit, onBody: (String) -> Unit,
    onSend: () -> Unit, onSave: () -> Unit, onDiscard: () -> Unit,
) {
    val p = LocalStudio.current
    Column(Modifier.fillMaxSize()) {
        Toolbar {
            Block(I18n.t("friend.mail.send"), onSend, icon = Icons.AutoMirrored.Filled.Send, fill = ctx.color, enabled = to.isNotBlank(), height = 36.dp)
            Block(I18n.t("friend.mail.save_draft"), onSave, icon = Icons.Filled.Save, height = 36.dp)
            Block(I18n.t("friend.mail.discard"), onDiscard, icon = Icons.Filled.Close, height = 36.dp)
        }
        FormRow(I18n.t("friend.mail.to"), 0) { InputBox(to, onTo, "name@example.com", Modifier.weight(1f)) }
        FormRow(I18n.t("friend.mail.cc"), 1) { InputBox(cc, onCc, "", Modifier.weight(1f)) }
        FormRow(I18n.t("friend.mail.subject"), 2) { InputBox(subject, onSubject, "", Modifier.weight(1f)) }
        Box(Modifier.weight(1f).fillMaxWidth().background(p.panel).verticalScroll(rememberScrollState()).padding(14.dp)) {
            if (body.isEmpty()) FlatText(I18n.t("friend.mail.body"), color = p.muted)
            BasicTextField(body, onBody, textStyle = TextStyle(color = p.text, fontSize = 13.sp), cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
        }
        if (notice.isNotBlank()) FlatText(notice, color = p.accent, size = 11, lines = 3, modifier = Modifier.padding(10.dp))
    }
}

@Composable
private fun MailAccountForm(ctx: ModuleCtx, initial: MailAccount?, busy: Boolean, onDone: (MailAccount?) -> Unit) {
    val p = LocalStudio.current
    val starting = initial?.let { MailAccount.Preset.forAddress(it.address) } ?: MailAccount.Preset.CUSTOM
    var presetId by remember(initial) { mutableStateOf(starting.id) }
    var address by remember(initial) { mutableStateOf(initial?.address.orEmpty()) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf("") }
    var imapHost by remember(initial) { mutableStateOf(initial?.imapHost.orEmpty()) }
    var imapPort by remember(initial) { mutableStateOf((initial?.imapPort ?: 993).toString()) }
    var imapSecurity by remember(initial) { mutableStateOf(initial?.imapSecurity ?: "ssl") }
    var smtpHost by remember(initial) { mutableStateOf(initial?.smtpHost.orEmpty()) }
    var smtpPort by remember(initial) { mutableStateOf((initial?.smtpPort ?: 465).toString()) }
    var smtpSecurity by remember(initial) { mutableStateOf(initial?.smtpSecurity ?: "ssl") }
    var hostsEdited by remember(initial) { mutableStateOf(initial != null) }
    fun apply(next: MailAccount.Preset) {
        presetId = next.id
        if (next.id != "custom") {
            imapHost = next.imapHost; imapPort = next.imapPort.toString(); imapSecurity = next.imapSecurity
            smtpHost = next.smtpHost; smtpPort = next.smtpPort.toString(); smtpSecurity = next.smtpSecurity
            hostsEdited = false
        }
    }
    val security = listOf("ssl" to I18n.t("friend.mail.security_ssl"), "starttls" to I18n.t("friend.mail.security_starttls"))
    Column(Modifier.fillMaxSize()) {
        Toolbar {
            FlatText(if (initial == null) I18n.t("friend.mail.add_account") else I18n.t("friend.mail.edit"), weight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 12.dp))
            Block(I18n.t("friend.mail.save_connect"), {
                val account = MailAccount()
                account.id = initial?.id ?: ""
                account.address = address.trim()
                account.username = username.trim()
                account.password = password
                account.imapHost = imapHost.trim()
                account.imapPort = imapPort.toIntOrNull() ?: 0
                account.imapSecurity = imapSecurity
                account.smtpHost = smtpHost.trim()
                account.smtpPort = smtpPort.toIntOrNull() ?: 0
                account.smtpSecurity = smtpSecurity
                password = ""
                onDone(account)
            }, icon = Icons.Filled.Check, fill = ctx.color, enabled = !busy && address.isNotBlank(), height = 36.dp)
            Block(I18n.t("friend.mail.discard"), { onDone(null) }, icon = Icons.Filled.Close, enabled = !busy, height = 36.dp)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(10.dp)) {
                Chips(MailAccount.Preset.all().map { it.id to I18n.t(it.labelKey) }, presetId, { id -> apply(MailAccount.Preset.byId(id)) }, ctx.color)
            }
            FormRow(I18n.t("friend.mail.address"), 0) {
                InputBox(address, {
                    address = it
                    if (!hostsEdited) MailAccount.Preset.forAddress(it)?.let { m -> apply(m) }
                }, "name@example.com", Modifier.weight(1f))
            }
            FormRow(I18n.t("friend.mail.username"), 1) { InputBox(username, { username = it }, "", Modifier.weight(1f)) }
            FormRow(I18n.t("friend.mail.password"), 2) { InputBox(password, { password = it }, "", Modifier.weight(1f), secret = true) }
            FlatText(I18n.t("friend.mail.password_hint"), color = p.muted, size = 11, lines = 3, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            LabelBar(I18n.t("friend.mail.imap"), ctx.color)
            FormRow(mt("host"), 0) { InputBox(imapHost, { imapHost = it; hostsEdited = true }, "imap.example.com", Modifier.weight(1f), mono = true) }
            FormRow(I18n.t("friend.mail.port"), 1) {
                InputBox(imapPort, { imapPort = it.filter { c -> c.isDigit() }.take(5); hostsEdited = true }, "993", Modifier.width(100.dp), mono = true)
                Spacer(Modifier.width(8.dp))
                Chips(security, imapSecurity, { imapSecurity = it; hostsEdited = true }, ctx.color)
            }
            LabelBar(I18n.t("friend.mail.smtp"), ctx.color)
            FormRow(mt("host"), 0) { InputBox(smtpHost, { smtpHost = it; hostsEdited = true }, "smtp.example.com", Modifier.weight(1f), mono = true) }
            FormRow(I18n.t("friend.mail.port"), 1) {
                InputBox(smtpPort, { smtpPort = it.filter { c -> c.isDigit() }.take(5); hostsEdited = true }, "465", Modifier.width(100.dp), mono = true)
                Spacer(Modifier.width(8.dp))
                Chips(security, smtpSecurity, { smtpSecurity = it; hostsEdited = true }, ctx.color)
            }
            FlatText(I18n.t("friend.mail.hint"), color = p.muted, size = 11, lines = 6, modifier = Modifier.padding(20.dp))
        }
    }
}

// ───────────────────────────── 音乐 ─────────────────────────────

@Composable
internal fun MusicModule(ctx: ModuleCtx) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (ctx.section) {
                "playlist" -> PlaylistSection(ctx)
                "history" -> HistorySection(ctx)
                else -> PlayerSection(ctx)
            }
        }
        if (ctx.section == "playlist" || ctx.section == "history") MiniPlayer(ctx)
    }
}

@Composable
private fun PlayerControls(ctx: ModuleCtx, big: Boolean) {
    val vm = ctx.vm
    val state by vm.musicPlaybackState.collectAsState()
    val repeat by vm.musicRepeatMode.collectAsState()
    val shuffle by vm.musicShuffle.collectAsState()
    val h = if (big) 44.dp else 36.dp
    val playing = state == PlaybackState.PLAYING
    Row(verticalAlignment = Alignment.CenterVertically) {
        Block("", { vm.toggleMusicShuffle() }, icon = Icons.Filled.Shuffle, fill = if (shuffle) ctx.color else null, height = h)
        Block("", { vm.playPreviousMusic() }, icon = Icons.Filled.SkipPrevious, height = h)
        Block("", { vm.toggleMusicPlayPause() }, icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, fill = ctx.color, height = h)
        Block("", { vm.playNextMusic() }, icon = Icons.Filled.SkipNext, height = h)
        Block("", { vm.stopMusic() }, icon = Icons.Filled.Stop, height = h)
        Block("", { vm.cycleMusicRepeatMode() }, icon = if (repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat, fill = if (repeat != 0) ctx.color else null, height = h)
    }
}

@Composable
private fun VolumeControl(ctx: ModuleCtx) {
    val vm = ctx.vm
    val volume by vm.musicVolume.collectAsState()
    val muted by vm.musicMuted.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Block("", { vm.toggleMusicMute() }, icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, height = 36.dp)
        SeekBar(if (muted) 0f else volume / 100f, ctx.color, Modifier.width(140.dp), height = 6.dp) { vm.setMusicVolume((it * 100).toInt()) }
        FlatText(volume.toString(), color = LocalStudio.current.muted, size = 11, modifier = Modifier.width(34.dp).padding(start = 8.dp))
    }
}

@Composable
private fun PlayerSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val playlist by vm.musicPlaylist.collectAsState()
    val index by vm.musicCurrentIndex.collectAsState()
    val state by vm.musicPlaybackState.collectAsState()
    val pos by vm.musicCurrentMs.collectAsState()
    val dur by vm.musicDurationMs.collectAsState()
    val lyrics by vm.musicLyrics.collectAsState()
    val loadingUrl by vm.musicLoadingUrl.collectAsState()
    val track = remember(playlist, index) { vm.currentMusicTrack }
    val cover = rememberUrlImage(track?.coverUrl)
    var url by remember { mutableStateOf("") }
    val lineIndex = lyrics.indexOfLast { it.timeMs <= pos }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Box(Modifier.width(320.dp).fillMaxHeight().background(ctx.color), contentAlignment = Alignment.Center) {
                if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Icon(Icons.Filled.MusicNote, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(96.dp))
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                FlatText(track?.title ?: mt("nothing_playing"), size = 26, weight = FontWeight.Light, lines = 2)
                FlatText(listOfNotNull(track?.uploader?.ifBlank { null }, track?.sourceType?.ifBlank { null }).joinToString(" · "), color = p.muted, size = 12)
                Spacer(Modifier.height(10.dp))
                FlatText(mt("music_state_" + state.name.lowercase()) + if (loadingUrl != null) " · " + I18n.t("studio.loading") else "", color = ctx.color, size = 11, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (lyrics.isEmpty()) CenterNote(mt("no_lyrics"))
                    else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                        val from = (lineIndex - 3).coerceAtLeast(0)
                        lyrics.subList(from, (from + 7).coerceAtMost(lyrics.size)).forEachIndexed { i, line ->
                            val on = from + i == lineIndex
                            FlatText(line.text, color = if (on) p.text else p.muted, size = if (on) 18 else 13, weight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.padding(vertical = 3.dp))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            FlatText(fmtMs(pos), color = p.muted, size = 11, modifier = Modifier.width(52.dp))
            SeekBar(if (dur > 0) pos.toFloat() / dur else 0f, ctx.color, Modifier.weight(1f)) { if (dur > 0) vm.seekMusicTo((it * dur).toLong()) }
            FlatText(fmtMs(dur), color = p.muted, size = 11, modifier = Modifier.width(52.dp).padding(start = 8.dp))
        }
        Row(Modifier.fillMaxWidth().background(p.panel).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerControls(ctx, big = true)
            Spacer(Modifier.weight(1f))
            VolumeControl(ctx)
        }
        Row(Modifier.fillMaxWidth().background(p.panelAlt).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InputBox(url, { url = it }, mt("music_url_hint"), Modifier.weight(1f), onSubmit = { if (url.isNotBlank()) { vm.resolveAndAddMusicTrack(url.trim()); url = "" } })
            Block(mt("add"), { vm.resolveAndAddMusicTrack(url.trim()); url = "" }, icon = Icons.Filled.Add, fill = ctx.color, enabled = url.isNotBlank(), height = 30.dp)
        }
    }
}

@Composable
private fun MiniPlayer(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val playlist by vm.musicPlaylist.collectAsState()
    val index by vm.musicCurrentIndex.collectAsState()
    val pos by vm.musicCurrentMs.collectAsState()
    val dur by vm.musicDurationMs.collectAsState()
    val track = remember(playlist, index) { vm.currentMusicTrack }
    Column(Modifier.fillMaxWidth().background(p.panel)) {
        SeekBar(if (dur > 0) pos.toFloat() / dur else 0f, ctx.color, Modifier.fillMaxWidth(), height = 3.dp) { if (dur > 0) vm.seekMusicTo((it * dur).toLong()) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FlatText(track?.title ?: mt("nothing_playing"), weight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
            FlatText("${fmtMs(pos)} / ${fmtMs(dur)}", color = p.muted, size = 11, modifier = Modifier.padding(horizontal = 8.dp))
            PlayerControls(ctx, big = false)
            VolumeControl(ctx)
        }
    }
}

@Composable
private fun TrackRow(ctx: ModuleCtx, t: MusicTrack, i: Int, playing: Boolean, onPlay: () -> Unit, onRemove: () -> Unit) {
    val cover = rememberUrlImage(t.coverUrl)
    ItemRow(t.title, listOf(t.uploader, if (t.durationMs > 0) fmtMs(t.durationMs) else "", t.sourceType).filter { it.isNotBlank() }.joinToString(" · "),
        if (playing) Tiles.Forest else ctx.color, i, icon = if (playing) Icons.Filled.PlayArrow else Icons.Filled.MusicNote, image = cover, selected = playing, onClick = onPlay) {
        RowButton(Icons.Filled.PlayArrow, onPlay)
        RowButton(Icons.Filled.Delete, onRemove)
    }
}

@Composable
private fun PlaylistSection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val playlist by vm.musicPlaylist.collectAsState()
    val index by vm.musicCurrentIndex.collectAsState()
    val lists by vm.musicPlaylistIndex.collectAsState()
    val activeId by vm.musicActivePlaylistId.collectAsState()
    var newName by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        ModuleHero(lists.firstOrNull { it.id == activeId }?.name ?: mt("playlist"), mt("tracks_n", playlist.size, fmtMs(playlist.sumOf { it.durationMs })), ctx.color, PmclIcons.Music) {
            HeroButton(mt("add_files"), Icons.Filled.FileOpen, ctx.color, {
                val files = pickFile(mt("add_files"), setOf("mp3", "flac", "wav", "ogg", "m4a", "aac", "opus"), multiple = true)
                if (files.isNotEmpty()) vm.addLocalMusicFiles(files.map { it.toString() })
            })
            HeroButton(mt("add_folder"), Icons.Filled.Folder, ctx.color, { pickDirectory(mt("add_folder"))?.let { vm.addLocalMusicFolder(it.toString()) } })
            HeroButton(mt("clear"), Icons.Filled.Delete, ctx.color, { ctx.confirm(mt("clear"), mt("clear_playlist_body")) { vm.clearMusicPlaylist() } }, enabled = playlist.isNotEmpty())
        }
        Toolbar {
            lists.forEach { l -> Block(l.name, { vm.switchMusicPlaylist(l.id) }, fill = if (l.id == activeId) ctx.color else null, height = 36.dp) }
            Spacer(Modifier.width(8.dp))
            InputBox(newName, { newName = it }, mt("new_playlist"), Modifier.width(160.dp), onSubmit = { if (newName.isNotBlank()) { vm.createMusicPlaylist(newName.trim()); newName = "" } })
            Block("", { vm.createMusicPlaylist(newName.trim()); newName = "" }, icon = Icons.Filled.Add, enabled = newName.isNotBlank(), height = 36.dp)
            if (lists.size > 1) Block("", { ctx.confirm(mt("delete_playlist"), lists.firstOrNull { it.id == activeId }?.name.orEmpty()) { vm.deleteMusicPlaylist(activeId) } },
                icon = Icons.Filled.Delete, height = 36.dp)
        }
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InputBox(url, { url = it }, mt("music_url_hint"), Modifier.weight(1f), onSubmit = { if (url.isNotBlank()) { vm.resolveAndAddMusicTrack(url.trim()); url = "" } })
            Block(mt("add"), { vm.resolveAndAddMusicTrack(url.trim()); url = "" }, icon = Icons.Filled.Link, fill = ctx.color, enabled = url.isNotBlank(), height = 30.dp)
        }
        if (playlist.isEmpty()) EmptyState(mt("playlist_empty"), PmclIcons.Music, color = ctx.color)
        else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(playlist, key = { i, t -> "$i:${t.sourceUrl}" }) { i, t ->
                TrackRow(ctx, t, i, i == index, { vm.playMusicAt(i) }, { vm.removeMusicTrack(i) })
            }
        }
    }
}

@Composable
private fun HistorySection(ctx: ModuleCtx) {
    val vm = ctx.vm
    val history by vm.musicHistory.collectAsState()
    Column(Modifier.fillMaxSize()) {
        ModuleHero(mt("music_history"), mt("items_n", history.size), ctx.color, PmclIcons.History) {
            HeroButton(mt("clear"), Icons.Filled.Delete, ctx.color, { ctx.confirm(mt("clear"), mt("clear_history_body")) { vm.clearMusicHistory() } }, enabled = history.isNotEmpty())
        }
        if (history.isEmpty()) EmptyState(mt("history_empty"), PmclIcons.History, color = ctx.color)
        else LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(history, key = { i, t -> "$i:${t.sourceUrl}" }) { i, t ->
                TrackRow(ctx, t, i, false, { vm.playMusicFromHistory(t) }, { vm.removeMusicHistoryAt(i) })
            }
        }
    }
}

// ───────────────────────────── NBT ─────────────────────────────

private class NbtRow(val path: String, val depth: Int, val key: String, val tag: NbtTag, val parent: NbtTag?, val index: Int)

private fun nbtChildren(tag: NbtTag): List<Pair<String, NbtTag>> = when (tag) {
    is NbtTag.CompoundTag -> tag.children.entries.map { it.key to it.value }
    is NbtTag.ListTag -> tag.items.mapIndexed { i, t -> "[$i]" to t }
    else -> emptyList()
}

private fun flattenNbt(root: NbtTag, expanded: Set<String>, query: String, limit: Int = 4000): List<NbtRow> {
    val out = ArrayList<NbtRow>()
    fun matches(key: String, tag: NbtTag): Boolean =
        key.contains(query, true) || (tag !is NbtTag.CompoundTag && tag !is NbtTag.ListTag && tag.valueString.orEmpty().contains(query, true))
    fun subtreeMatches(key: String, tag: NbtTag, depth: Int): Boolean =
        matches(key, tag) || (depth < 12 && nbtChildren(tag).any { (k, t) -> subtreeMatches(k, t, depth + 1) })
    fun walk(path: String, depth: Int, key: String, tag: NbtTag, parent: NbtTag?, index: Int) {
        if (out.size >= limit) return
        if (query.isNotEmpty() && !subtreeMatches(key, tag, depth)) return
        out.add(NbtRow(path, depth, key, tag, parent, index))
        val open = path in expanded || (query.isNotEmpty() && depth < 12)
        if (!open) return
        nbtChildren(tag).forEachIndexed { i, (k, t) -> walk("$path/$k", depth + 1, k, t, tag, if (tag is NbtTag.ListTag) i else -1) }
    }
    walk("root", 0, root.name.orEmpty().ifBlank { "root" }, root, null, -1)
    return out
}

private fun nbtColor(type: Int): Color = when (type) {
    NbtTag.TYPE_COMPOUND -> Tiles.Navy
    NbtTag.TYPE_LIST -> Tiles.Purple
    NbtTag.TYPE_STRING -> Tiles.Teal
    NbtTag.TYPE_BYTE_ARRAY, NbtTag.TYPE_INT_ARRAY, NbtTag.TYPE_LONG_ARRAY -> Tiles.Slate
    else -> Tiles.Sky
}

private fun nbtBadge(type: Int): String = when (type) {
    NbtTag.TYPE_COMPOUND -> "{}"
    NbtTag.TYPE_LIST -> "[]"
    NbtTag.TYPE_STRING -> "S"
    NbtTag.TYPE_BYTE -> "B"
    NbtTag.TYPE_SHORT -> "Sh"
    NbtTag.TYPE_INT -> "I"
    NbtTag.TYPE_LONG -> "L"
    NbtTag.TYPE_FLOAT -> "F"
    NbtTag.TYPE_DOUBLE -> "D"
    NbtTag.TYPE_BYTE_ARRAY -> "B[]"
    NbtTag.TYPE_INT_ARRAY -> "I[]"
    NbtTag.TYPE_LONG_ARRAY -> "L[]"
    else -> "?"
}

private val leafTypes = listOf(
    NbtTag.TYPE_STRING, NbtTag.TYPE_INT, NbtTag.TYPE_LONG, NbtTag.TYPE_DOUBLE, NbtTag.TYPE_FLOAT,
    NbtTag.TYPE_BYTE, NbtTag.TYPE_SHORT, NbtTag.TYPE_COMPOUND, NbtTag.TYPE_LIST,
)

@Composable
internal fun NbtModule(ctx: ModuleCtx) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val root by vm.nbtRoot.collectAsState()
    val file by vm.nbtFilePath.collectAsState()
    val dirty by vm.nbtDirty.collectAsState()
    val error by vm.nbtError.collectAsState()
    val revision by vm.nbtRevision.collectAsState()
    val canUndo by vm.nbtCanUndo.collectAsState()
    val canRedo by vm.nbtCanRedo.collectAsState()
    val recent by vm.recentNbtFiles.collectAsState()
    val worlds by vm.worlds.collectAsState()
    LaunchedEffect(Unit) { vm.refreshWorlds() }
    val expanded = remember { mutableStateMapOf("root" to true) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    val r = root
    val rows = remember(r, revision, expanded.toMap(), query) { if (r == null) emptyList() else flattenNbt(r, expanded.keys, query.trim()) }
    val selected = rows.firstOrNull { it.path == selectedPath }
    fun open(path: String) {
        expanded.clear(); expanded["root"] = true; selectedPath = null
        vm.openNbtFile(path)
    }

    Column(Modifier.fillMaxSize()) {
        ModuleHero(file?.let { File(it).name } ?: mt("nbt_title"), file ?: mt("nbt_sub"), ctx.color, PmclIcons.Nbt) {
            HeroButton(mt("open"), Icons.Filled.FileOpen, ctx.color, {
                pickFile(mt("open"), setOf("dat", "dat_old", "nbt", "schematic", "litematic", "schem", "mcstructure")).firstOrNull()?.let { open(it.toString()) }
            })
            HeroButton(if (dirty) mt("save") + " ●" else mt("save"), Icons.Filled.Save, ctx.color, { vm.saveNbtFile() }, enabled = r != null && dirty)
            HeroButton(if (copied) mt("copied") else "SNBT", Icons.Filled.ContentCopy, ctx.color, { copyText(vm.exportNbtSnbt()); copied = true }, enabled = r != null)
        }
        Toolbar {
            Block("", { vm.undoNbt() }, icon = Icons.AutoMirrored.Filled.Undo, enabled = canUndo, height = 36.dp)
            Block("", { vm.redoNbt() }, icon = Icons.AutoMirrored.Filled.Redo, enabled = canRedo, height = 36.dp)
            FlatField(query, { query = it }, mt("nbt_search"), Modifier.width(240.dp), icon = Icons.Filled.Search)
            Block(mt("collapse_all"), { expanded.clear(); expanded["root"] = true }, height = 36.dp)
            if (r != null) Block("", { vm.closeNbtFile(); selectedPath = null }, icon = Icons.Filled.Close, height = 36.dp)
        }
        if (!error.isNullOrBlank()) FlatText(error.orEmpty(), color = Tiles.Red, size = 11, lines = 3, modifier = Modifier.fillMaxWidth().background(p.panelAlt).padding(horizontal = 14.dp, vertical = 6.dp))
        if (r == null) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (worlds.isNotEmpty()) {
                    LabelBar(mt("quick_open_level"), ctx.color)
                    worlds.forEachIndexed { i, w ->
                        ItemRow(w.name, w.dir.resolve("level.dat").toString(), Tiles.Teal, i, icon = PmclIcons.Saves, height = 40.dp, onClick = { open(w.dir.resolve("level.dat").toString()) })
                    }
                }
                if (recent.isNotEmpty()) {
                    LabelBar(mt("recent_files"), ctx.color)
                    recent.forEachIndexed { i, f ->
                        ItemRow(File(f).name, f, ctx.color, i, icon = PmclIcons.Nbt, height = 40.dp, onClick = { open(f) })
                    }
                }
                if (worlds.isEmpty() && recent.isEmpty()) EmptyState(mt("nbt_empty"), PmclIcons.Nbt, mt("open"), ctx.color) {
                    pickFile(mt("open")).firstOrNull()?.let { open(it.toString()) }
                }
            }
            return@Column
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight().background(p.editor)) {
                itemsIndexed(rows, key = { _, row -> row.path }) { i, row ->
                    val container = row.tag is NbtTag.CompoundTag || row.tag is NbtTag.ListTag
                    val open = expanded[row.path] == true || query.isNotBlank()
                    val on = row.path == selectedPath
                    Row(
                        Modifier.fillMaxWidth().height(28.dp)
                            .background(if (on) p.selected else if (i % 2 == 0) p.editor else p.panel)
                            .clickable {
                                selectedPath = row.path
                                if (container && query.isBlank()) { if (open) expanded.remove(row.path) else expanded[row.path] = true }
                            }
                            .padding(start = (row.depth * 16).dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.width(20.dp), contentAlignment = Alignment.Center) {
                            if (container) Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null, tint = p.muted, modifier = Modifier.size(14.dp))
                        }
                        FlatText(nbtBadge(row.tag.type), color = Color.White, size = 10, weight = FontWeight.SemiBold,
                            modifier = Modifier.width(30.dp).background(nbtColor(row.tag.type)).padding(horizontal = 4.dp, vertical = 2.dp))
                        Spacer(Modifier.width(8.dp))
                        FlatText(row.key, weight = FontWeight.Medium, modifier = Modifier.widthIn(max = 280.dp))
                        Spacer(Modifier.width(10.dp))
                        val preview = when (val t = row.tag) {
                            is NbtTag.CompoundTag -> mt("entries_n", t.children.size)
                            is NbtTag.ListTag -> mt("entries_n", t.items.size)
                            else -> t.valueString.orEmpty()
                        }
                        FlatText(preview, color = if (container) p.muted else p.synString, size = 12, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (selected != null) NbtInspector(ctx, selected) { selectedPath = null }
        }
    }
}

@Composable
private fun NbtInspector(ctx: ModuleCtx, row: NbtRow, onClose: () -> Unit) {
    val vm = ctx.vm
    val p = LocalStudio.current
    val tag = row.tag
    val container = tag is NbtTag.CompoundTag || tag is NbtTag.ListTag
    var value by remember(row.path, tag) { mutableStateOf(if (container) "" else tag.valueString.orEmpty()) }
    var name by remember(row.path) { mutableStateOf(row.key) }
    var invalid by remember(row.path) { mutableStateOf(false) }
    var childName by remember(row.path) { mutableStateOf("") }
    var childType by remember(row.path) { mutableIntStateOf(NbtTag.TYPE_STRING) }
    val parent = row.parent
    Inspector(row.key, nbtColor(tag.type), onClose, Modifier.width(360.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FactRow(mt("type"), tag.typeName.orEmpty(), 0)
            FactRow(mt("path"), row.path.removePrefix("root"), 1, lines = 4)
            if (parent is NbtTag.CompoundTag) {
                LabelBar(mt("rename"), ctx.color)
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    InputBox(name, { name = it }, mt("name"), Modifier.weight(1f), mono = true)
                    Block("", { vm.renameNbtChild(parent, row.key, name.trim()); onClose() }, icon = Icons.Filled.Check, fill = ctx.color,
                        enabled = name.isNotBlank() && name != row.key && !parent.contains(name.trim()), height = 30.dp)
                }
            }
            if (!container) {
                LabelBar(mt("value"), ctx.color)
                Column(Modifier.padding(10.dp)) {
                    Box(Modifier.fillMaxWidth().background(if (invalid) Tiles.Red.copy(alpha = 0.18f) else p.field).padding(8.dp)) {
                        BasicTextField(value, { value = it; invalid = false }, textStyle = TextStyle(color = p.text, fontSize = 12.sp, fontFamily = mono.fontFamily),
                            cursorBrush = SolidColor(p.accent), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(6.dp))
                    Block(mt("apply"), { invalid = !vm.setNbtLeafValue(tag, value) }, icon = Icons.Filled.Check, fill = ctx.color, enabled = value != tag.valueString, height = 30.dp)
                    if (invalid) FlatText(mt("invalid_value"), color = Tiles.Red, size = 11)
                }
            }
            if (tag is NbtTag.CompoundTag) {
                LabelBar(mt("add_child"), ctx.color)
                Column(Modifier.padding(10.dp)) {
                    InputBox(childName, { childName = it }, mt("name"), Modifier.fillMaxWidth(), mono = true)
                    Spacer(Modifier.height(6.dp))
                    Chips(leafTypes.map { it.toString() to nbtBadge(it) }, childType.toString(), { childType = it.toInt() }, ctx.color)
                    Spacer(Modifier.height(6.dp))
                    Block(mt("add"), { vm.addNbtChild(tag, childName.trim(), childType); childName = "" }, icon = Icons.Filled.Add, fill = ctx.color,
                        enabled = childName.isNotBlank() && !tag.contains(childName.trim()), height = 30.dp)
                }
            }
        }
        if (parent != null) {
            Block(mt("delete"), {
                ctx.confirm(mt("delete_tag_title"), mt("delete_tag_body", row.key)) {
                    when (parent) {
                        is NbtTag.CompoundTag -> vm.removeNbtChild(parent, row.key)
                        is NbtTag.ListTag -> vm.removeNbtListItem(parent, row.index)
                        else -> {}
                    }
                    onClose()
                }
            }, Modifier.fillMaxWidth(), Icons.Filled.Delete, fill = Tiles.Red, height = 36.dp)
        }
    }
}