package com.pmcl.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.update
import com.pmcl.core.i18n.I18n

/**
 * M29 拆分：多人联机 / 收藏服务器域。
 *
 * 状态字段与 FavoriteServer 保留在 LauncherViewModel（@PublishedApi）。
 */

/** 将偏好中的 ConnectX 配置同步到 MultiplayerManager（启动与设置保存时调用） */
fun LauncherViewModel.syncConnectXConfig() {
    core.multiplayer().configureConnectX(
        preferences.getConnectxBinaryPath(),
        preferences.getConnectxServerAddress(),
        preferences.getConnectxServerPort()
    )
}

fun LauncherViewModel.setMpBackend(b: com.pmcl.core.multiplayer.MultiplayerManager.Backend) {
    if (_mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTING ||
        _mpState.value == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
        _status.value = I18n.t("status.leave_room_before_switch_backend")
        return
    }
    if (_mpBackend.value == b) return
    val name = when (b) {
        com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> "CONNECTX"
        com.pmcl.core.multiplayer.MultiplayerManager.Backend.EASYTIER -> "EASYTIER"
        com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA -> "TERRACOTTA"
    }
    preferences.setMpBackend(name)
    core.multiplayer().setBackend(b)
    _mpBackend.value = b
    val label = when (b) {
        com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> "ConnectX"
        com.pmcl.core.multiplayer.MultiplayerManager.Backend.EASYTIER -> "EasyTier"
        com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA -> "Terracotta 陶瓦联机"
    }
    _status.value = I18n.t("status.mp_backend_switched", label)
}

fun LauncherViewModel.createRoom() {
    val s = _mpState.value
    if (s == com.pmcl.core.multiplayer.MultiplayerManager.State.DOWNLOADING ||
        s == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTING ||
        s == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
        _status.value = I18n.t("status.already_in_room")
        return
    }
    val backend = mpBackend
    _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.DOWNLOADING
    _mpProgress.value = I18n.t("mp.progress.preparing")
    _status.value = when (backend) {
        com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> I18n.t("status.creating_connectx_room")
        com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA -> I18n.t("status.creating_terracotta_room")
        com.pmcl.core.multiplayer.MultiplayerManager.Backend.EASYTIER -> I18n.t("status.creating_mp_room")
    }
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                when (backend) {
                    com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX -> {
                        val binPath = preferences.getConnectxBinaryPath()
                        val serverAddr = preferences.getConnectxServerAddress()
                        val serverPort = preferences.getConnectxServerPort()
                        core.multiplayer().createRoomConnectX(
                            { msg -> _mpProgress.value = msg },
                            binPath, serverAddr, serverPort
                        ).join()
                    }
                    else -> {
                        // Terracotta / EasyTier 都走 createRoom
                        core.multiplayer().createRoom { msg ->
                            _mpProgress.value = msg
                        }.join()
                    }
                }
            }
            refreshMpState()
            _status.value = if (core.multiplayer().state ==
                com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
                val friendWarn = startFriendSubsystemQuiet()
                val base = when (backend) {
                    com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA ->
                        I18n.t("status.room_created_with_code", core.multiplayer().currentRoomCode)
                    com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX ->
                        I18n.t("status.connectx_room_created")
                    else ->
                        I18n.t("status.room_created_with_vip", core.multiplayer().virtualIp)
                }
                if (friendWarn != null) "$base · $friendWarn" else base
            } else {
                I18n.t("status.room_started_waiting")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            try { withContext(Dispatchers.IO) { core.multiplayer().leaveRoom() } } catch (_: Throwable) {}
            refreshMpState()
            throw e
        } catch (e: Throwable) {
            _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.FAILED
            _status.value = I18n.t("status.create_room_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _mpProgress.value = ""
        }
    }
}

/** 通过邀请码/房间码加入房间 */
fun LauncherViewModel.joinRoom(invitation: String) {
    if (invitation.isBlank()) {
        _status.value = I18n.t("status.enter_room_code_or_invitation")
        return
    }
    val joinState = _mpState.value
    if (joinState == com.pmcl.core.multiplayer.MultiplayerManager.State.DOWNLOADING ||
        joinState == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTING ||
        joinState == com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
        _status.value = I18n.t("status.already_in_room")
        return
    }
    val isConnectX = invitation.trim().startsWith("connectx-")
    val isTerracotta = invitation.trim().startsWith("U/") ||
        mpBackend == com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA
    _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.DOWNLOADING
    _mpProgress.value = I18n.t("mp.progress.parsing_code")
    _status.value = I18n.t("status.joining_room")
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                if (isConnectX) {
                    val binPath = preferences.getConnectxBinaryPath()
                    val serverAddr = preferences.getConnectxServerAddress()
                    val serverPort = preferences.getConnectxServerPort()
                    core.multiplayer().joinRoomConnectX(invitation, { msg ->
                        _mpProgress.value = msg
                    }, binPath, serverAddr, serverPort).join()
                } else {
                    core.multiplayer().joinRoom(invitation) { msg ->
                        _mpProgress.value = msg
                    }.join()
                }
            }
            refreshMpState()
            _status.value = if (core.multiplayer().state ==
                com.pmcl.core.multiplayer.MultiplayerManager.State.CONNECTED) {
                val friendWarn = startFriendSubsystemQuiet()
                val base = if (isTerracotta && core.multiplayer().localMcAddr.isNotEmpty()) {
                    I18n.t("status.joined_room_mc_addr", core.multiplayer().localMcAddr)
                } else {
                    I18n.t("status.joined_room_vip", core.multiplayer().virtualIp)
                }
                if (friendWarn != null) "$base · $friendWarn" else base
            } else {
                I18n.t("status.connecting_room")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            try { withContext(Dispatchers.IO) { core.multiplayer().leaveRoom() } } catch (_: Throwable) {}
            refreshMpState()
            throw e
        } catch (e: Throwable) {
            _mpState.value = com.pmcl.core.multiplayer.MultiplayerManager.State.FAILED
            _status.value = I18n.t("status.join_room_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _mpProgress.value = ""
        }
    }
}

/**
 * 启动好友子系统；失败时返回用户可见警告文案（房间连接本身仍视为成功）。
 */
private suspend fun LauncherViewModel.startFriendSubsystemQuiet(): String? {
    return withContext(Dispatchers.IO) {
        try {
            core.friend()?.start()
            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            System.err.println("[LauncherVM] 启动好友系统失败: ${e.message}")
            I18n.t("status.friend_start_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 离开当前房间 */
fun LauncherViewModel.leaveRoom() {
    scope.launch {
        try {
            // 停止好友系统网络服务
            withContext(Dispatchers.IO) {
                try { core.friend()?.stop() } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    System.err.println("[LauncherVM] 停止好友系统失败: ${e.message}")
                }
            }
            withContext(Dispatchers.IO) { core.multiplayer().leaveRoom() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            refreshMpState()
            _status.value = I18n.t("status.leave_room_failed", e.message ?: I18n.t("common.unknown"))
            return@launch
        }
        refreshMpState()
        // core leaveRoom 清理失败时设 FAILED 且不抛异常——不得伪装成「已离开」
        if (core.multiplayer().state == com.pmcl.core.multiplayer.MultiplayerManager.State.FAILED) {
            val err = core.multiplayer().lastError.ifBlank { I18n.t("common.unknown") }
            _status.value = I18n.t("status.leave_room_failed", err)
            return@launch
        }
        _mpInvitation.value = ""
        _mpVirtualIp.value = ""
        _mpLocalMcAddr.value = ""
        val backend = mpBackend
        _status.value = when (backend) {
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.CONNECTX ->
                I18n.t("status.left_connectx_room")
            com.pmcl.core.multiplayer.MultiplayerManager.Backend.TERRACOTTA ->
                I18n.t("status.left_terracotta_room")
            else -> I18n.t("status.left_terracotta_room")
        }
    }
}

/** 刷新当前房间状态 / 邀请码 / 虚拟 IP 到 StateFlow */
fun LauncherViewModel.refreshMpState() {
    val mgr = core.multiplayer()
    _mpState.value = mgr.state
    _mpVirtualIp.value = mgr.virtualIp
    _mpInvitation.value = if (mgr.isInRoom) mgr.generateInvitation() else ""
    _mpLocalMcAddr.value = mgr.localMcAddr
}

/** 复制邀请码到系统剪贴板 */
fun LauncherViewModel.copyInvitation() {
    val code = core.multiplayer().generateInvitation()
    if (code.isEmpty()) {
        _status.value = I18n.t("status.no_invitation_to_share")
        return
    }
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                java.awt.Toolkit.getDefaultToolkit()
                    .systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(code), null
                    )
            }
            _status.value = I18n.t("status.invitation_copied")
        } catch (e: Throwable) {
            _status.value = I18n.t("status.copy_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 复制任意文本到系统剪贴板 */
fun LauncherViewModel.copyToClipboard(text: String) {
    if (text.isBlank()) return
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                java.awt.Toolkit.getDefaultToolkit()
                    .systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(text), null
                    )
            }
            _status.value = I18n.t("status.copied", text)
        } catch (e: Throwable) {
            _status.value = I18n.t("status.copy_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

// ============ 收藏服务器列表 + ping 延迟 ============

/** 加载收藏服务器列表 */
fun LauncherViewModel.loadFavoriteServers() {
    _favoriteServers.value = preferences.getFavoriteServers().map {
        LauncherViewModel.FavoriteServer(it[0], it[1], it[2].toIntOrNull() ?: 25565)
    }
}

/** 添加收藏服务器 */
fun LauncherViewModel.addFavoriteServer(name: String, host: String, port: Int) {
    preferences.addFavoriteServer(name, host, port)
    loadFavoriteServers()
}

/** 删除收藏服务器 */
fun LauncherViewModel.removeFavoriteServer(index: Int) {
    preferences.removeFavoriteServer(index)
    loadFavoriteServers()
}

/** 将服务器设为直连目标（写入 gameServerHost/Port） */
fun LauncherViewModel.setDirectConnectServer(host: String, port: Int) {
    preferences.setGameServerHost(host)
    preferences.setGameServerPort(port)
    _status.value = I18n.t("status.direct_connect_server_set", "$host:$port")
}

/** ping 单个服务器 */
fun LauncherViewModel.pingServer(host: String, port: Int) {
    val key = "$host:$port"
    scope.launch {
        try {
            val latency = withContext(Dispatchers.IO) {
                com.pmcl.core.multiplayer.ServerPinger.ping(host, port)
            }
            // 使用 update 原子更新，避免并发 ping 完成时读-改-写丢失更新
            _serverPings.update { it + (key to latency) }
        } catch (e: Throwable) {
            _serverPings.update { it + (key to com.pmcl.core.multiplayer.ServerPinger.UNREACHABLE) }
        }
    }
}

/** 批量 ping 所有收藏服务器 */
fun LauncherViewModel.pingAllServers() {
    val servers = _favoriteServers.value
    servers.forEach { s -> pingServer(s.host, s.port) }
}

// ===== 服务器完整状态 ping（MOTD/在线人数/版本） =====

/** 完整 ping 单个服务器，返回 MOTD/在线人数/版本等完整信息 */
fun LauncherViewModel.pingServerFull(host: String, port: Int) {
    val key = "$host:$port"
    _pingingServers.update { it + key }
    scope.launch {
        try {
            val status = withContext(Dispatchers.IO) {
                com.pmcl.core.multiplayer.ServerPinger.pingFull(host, port)
            }
            _serverStatuses.update { it + (key to status) }
            // 同步更新延迟 Map，保持与旧 API 兼容
            _serverPings.update { it + (key to status.latency) }
        } catch (e: Throwable) {
            val err = com.pmcl.core.multiplayer.ServerPinger.ServerStatus(
                com.pmcl.core.multiplayer.ServerPinger.UNREACHABLE, "", 0, 0, "", 0, null, e.message)
            _serverStatuses.update { it + (key to err) }
        } finally {
            _pingingServers.update { it - key }
        }
    }
}

/** 批量完整 ping 所有收藏服务器 */
fun LauncherViewModel.pingAllServersFull() {
    val servers = _favoriteServers.value
    servers.forEach { s -> pingServerFull(s.host, s.port) }
}

/** 更新收藏服务器（名称/地址/端口） */
fun LauncherViewModel.updateFavoriteServer(index: Int, name: String, host: String, port: Int) {
    preferences.updateFavoriteServer(index, name, host, port)
    loadFavoriteServers()
}

