package com.pmcl.ui.viewmodel

import com.pmcl.core.auth.Account
import com.pmcl.core.auth.AccountStore
import com.pmcl.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * M29 拆分：账号 / 登录 / 皮肤域。
 *
 * 状态字段保留在 LauncherViewModel（@PublishedApi internal），
 * UI 调用签名不变（需 import 扩展函数）。
 */

/** 从磁盘加载已保存账号集合（多账号） */
@PublishedApi
internal fun LauncherViewModel.loadSavedAccount() {
    scope.launch {
        try {
            val store = withContext(Dispatchers.IO) {
                core.auth().loadStore(accountFile)
            }
            _accounts.value = store.getAccounts()
            val sel = store.getSelected().orElse(null)
            _account.value = sel
            if (sel != null) {
                // 基于账户 UUID 派生好友身份
                withContext(Dispatchers.IO) {
                    core.friend()?.switchAccount(sel.getUuid(), sel.getUsername())
                }
                _status.value = I18n.t("status.account_loaded", sel.getUsername(), sel.getType())
            }
        } catch (e: Throwable) {
            _status.value = I18n.t("status.account_load_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 持久化整个 AccountStore 到磁盘 */
@PublishedApi
internal fun LauncherViewModel.saveStore(store: AccountStore) {
    _accounts.value = store.getAccounts()
    _account.value = store.getSelected().orElse(null)
    // 同步当前账户到好友身份系统（基于 UUID 派生身份，切换数据集）
    store.getSelected().ifPresent { acc ->
        scope.launch {
            withContext(Dispatchers.IO) {
                core.friend()?.switchAccount(acc.getUuid(), acc.getUsername())
            }
        }
    }
    // 串行落盘，且在锁内读取最新内存快照，避免快速切换时旧快照覆盖新状态
    scope.launch {
        accountSaveMutex.withLock {
            try {
                val latest = synchronized(accountLock) {
                    AccountStore(_accounts.value, _account.value?.getUuid())
                }
                withContext(Dispatchers.IO) {
                    core.auth().saveStore(latest, accountFile)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                _status.value = I18n.t("status.account_save_failed", e.message ?: I18n.t("common.unknown"))
            }
        }
    }
}

/** 可取消等待 CompletableFuture（设备码轮询）；取消时 complete 掉 future 以停止调度 */
private suspend fun <T> awaitCancellableFuture(future: java.util.concurrent.CompletableFuture<T>): T {
    return withContext(Dispatchers.IO) {
        try {
            while (!future.isDone) {
                try {
                    return@withContext future.get(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (_: java.util.concurrent.TimeoutException) {
                    ensureActive()
                }
            }
            future.get()
        } catch (e: kotlinx.coroutines.CancellationException) {
            future.cancel(true)
            future.completeExceptionally(e)
            throw e
        } catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            if (cause is kotlinx.coroutines.CancellationException) throw cause
            throw (cause ?: e)
        }
    }
}

/** 向账号集合添加新账号（或更新已有），并设为选中 */
@PublishedApi
internal fun LauncherViewModel.upsertAccount(acc: Account) = synchronized(accountLock) {
    val current = AccountStore(_accounts.value, _account.value?.getUuid())
    saveStore(current.upsert(acc))
    invalidatePreheatForAccountChange()
}

/** 切换当前选中账号 */
fun LauncherViewModel.switchAccount(uuid: String) = synchronized(accountLock) {
    val current = AccountStore(_accounts.value, _account.value?.getUuid())
    saveStore(current.select(uuid))
    invalidatePreheatForAccountChange()
    _status.value = I18n.t("status.account_switched", _account.value?.getUsername() ?: "")
    val selected = _account.value
    if (selected != null) {
        try {
            core.plugins().fireEvent(
                com.pmcl.plugin.AccountSelectedEvent(selected.getUuid(), selected.getUsername())
            )
        } catch (_: Throwable) {
        }
    }
}

/** 删除指定账号 */
fun LauncherViewModel.removeAccount(uuid: String) = synchronized(accountLock) {
    val current = AccountStore(_accounts.value, _account.value?.getUuid())
    saveStore(current.remove(uuid))
    _status.value = I18n.t("status.account_removed")
}

/** 退出当前账号（等同于删除当前选中账号） */
fun LauncherViewModel.logout() {
    val cur = _account.value ?: return
    removeAccount(cur.getUuid())
}

fun LauncherViewModel.loginOffline(username: String) {
    if (username.isBlank()) {
        _status.value = I18n.t("status.username_required")
        return
    }
    val acc = core.auth().offline(username)
    upsertAccount(acc)
    // 持久化用户名，下次启动时恢复，避免每次重置为 Steve
    preferences.setLastOfflineUsername(username)
    _status.value = I18n.t("status.logged_in_offline", username)
}

/** 上次离线登录用户名（启动时恢复） */
fun LauncherViewModel.lastOfflineUsername(): String = preferences.getLastOfflineUsername()

/** 为当前离线账号设置自定义皮肤 URL（如 Crafatar 头像 URL 或其他皮肤图） */
fun LauncherViewModel.setOfflineSkin(skinUrl: String, skinModel: String = "classic") {
    val current = _account.value ?: run {
        _status.value = I18n.t("status.login_first")
        return
    }
    if (current.getType() != Account.AccountType.OFFLINE) {
        _status.value = I18n.t("status.offline_skin_microsoft_unsupported")
        return
    }
    val updated = Account(
        current.getUsername(), current.getUuid(), current.getAccessToken(),
        current.getType(), skinUrl, skinModel
    )
    upsertAccount(updated)
    _status.value = if (skinUrl.isEmpty()) I18n.t("status.skin_cleared") else I18n.t("status.skin_set")
}


/** 上传皮肤到微软账号 */
fun LauncherViewModel.uploadMicrosoftSkin(skinFile: java.nio.file.Path, model: String) {
    val current = _account.value ?: run {
        _status.value = I18n.t("status.login_first")
        return
    }
    if (current.getType() != Account.AccountType.MICROSOFT) {
        _status.value = I18n.t("status.skin_upload_microsoft_only")
        return
    }
    scope.launch {
        _status.value = I18n.t("status.skin_uploading")
        try {
            withContext(Dispatchers.IO) {
                skinManager.uploadMicrosoftSkin(current.getAccessToken(), skinFile, model)
            }
            _status.value = I18n.t("status.skin_uploaded")
        } catch (e: Throwable) {
            _status.value = I18n.t("status.skin_upload_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 重置微软账号皮肤 */
fun LauncherViewModel.resetMicrosoftSkin() {
    val current = _account.value ?: run {
        _status.value = I18n.t("status.login_first")
        return
    }
    if (current.getType() != Account.AccountType.MICROSOFT) {
        _status.value = I18n.t("status.skin_upload_microsoft_only")
        return
    }
    scope.launch {
        _status.value = I18n.t("status.skin_resetting")
        try {
            withContext(Dispatchers.IO) {
                skinManager.resetMicrosoftSkin(current.getAccessToken())
            }
            _status.value = I18n.t("status.skin_reset")
        } catch (e: Throwable) {
            _status.value = I18n.t("status.skin_reset_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 上传皮肤到皮肤站账号 */
fun LauncherViewModel.uploadYggdrasilSkin(skinFile: java.nio.file.Path, model: String, password: String) {
    val current = _account.value ?: run {
        _status.value = I18n.t("status.login_first")
        return
    }
    if (current.getType() != Account.AccountType.YGGDRASIL) {
        _status.value = I18n.t("status.skin_upload_yggdrasil_only")
        return
    }
    val apiUrl = current.getAuthServerUrl()
    if (apiUrl.isEmpty()) {
        _status.value = I18n.t("status.skin_upload_no_api_url")
        return
    }
    scope.launch {
        _status.value = I18n.t("status.skin_uploading")
        try {
            val playerId = current.getUuid().replace("-", "")
            withContext(Dispatchers.IO) {
                skinManager.uploadYggdrasilSkin(
                    apiUrl, current.getUsername(), password, playerId, skinFile, model
                )
            }
            _status.value = I18n.t("status.skin_uploaded")
        } catch (e: Throwable) {
            _status.value = I18n.t("status.skin_upload_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

/** 重置皮肤站账号皮肤 */
fun LauncherViewModel.resetYggdrasilSkin(password: String) {
    val current = _account.value ?: run {
        _status.value = I18n.t("status.login_first")
        return
    }
    if (current.getType() != Account.AccountType.YGGDRASIL) {
        _status.value = I18n.t("status.skin_upload_yggdrasil_only")
        return
    }
    val apiUrl = current.getAuthServerUrl()
    if (apiUrl.isEmpty()) {
        _status.value = I18n.t("status.skin_upload_no_api_url")
        return
    }
    scope.launch {
        _status.value = I18n.t("status.skin_resetting")
        try {
            val playerId = current.getUuid().replace("-", "")
            withContext(Dispatchers.IO) {
                skinManager.resetYggdrasilSkin(apiUrl, current.getUsername(), password, playerId)
            }
            _status.value = I18n.t("status.skin_reset")
        } catch (e: Throwable) {
            _status.value = I18n.t("status.skin_reset_failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.startMicrosoftLogin() {
    scope.launch {
        _loggingIn.value = true
        try {
            // 统一使用 device code flow：
            // - 无需用户注册 Azure 应用 / 配置 redirect_uri
            // - LEGACY_CLIENT_ID 即可工作
            // - 返回的 MBI_SSL compact token 能被 Xbox Live 正确认证
            //   （login.live.com 旧端点的授权码流程返回的 token 缺少 audience claim，
            //    v2.0 端点返回的 JWT 需要 Azure 应用显式添加 XboxLive.signin API 权限，
            //    对普通用户门槛过高且易出错，故统一用 device code flow）
            _status.value = I18n.t("status.requesting_device_code")
            val dc = withContext(Dispatchers.IO) { core.auth().requestDeviceCode() }
            _deviceCode.value = dc
            _status.value = I18n.t("status.open_verification_url", dc.getVerificationUri(), dc.getUserCode())
            val future = core.auth().loginMicrosoftAsync(dc) { msg -> _status.value = msg }
            val account = try {
                awaitCancellableFuture(future)
            } catch (e: kotlinx.coroutines.CancellationException) {
                future.cancel(true)
                future.completeExceptionally(e)
                throw e
            }
            _account.value = account
            upsertAccount(account)
            _status.value = I18n.t("status.logged_in_microsoft", account.getUsername())
            _deviceCode.value = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            _deviceCode.value = null
            throw e
        } catch (e: Throwable) {
            _deviceCode.value = null
            val msg = e.message ?: e.toString()
            _status.value = if (msg.contains("SSL", ignoreCase = true) ||
                msg.contains("TLS", ignoreCase = true) ||
                msg.contains("handshake", ignoreCase = true) ||
                msg.contains("SYSCALL", ignoreCase = true) ||
                msg.contains("reset", ignoreCase = true) ||
                msg.contains("网络错误", ignoreCase = true)) {
                I18n.t("status.microsoft_login_failed_network", msg)
            } else {
                I18n.t("status.microsoft_login_failed", msg)
            }
        } finally {
            _loggingIn.value = false
        }
    }
}

/** GitHub 设备码登录 */
fun LauncherViewModel.startGitHubLogin() {
    scope.launch {
        _loggingIn.value = true
        _status.value = I18n.t("status.requesting_github_device_code")
        try {
            val dc = withContext(Dispatchers.IO) { core.auth().requestGitHubDeviceCode() }
            _deviceCode.value = dc
            _status.value = I18n.t("status.open_verification_url", dc.getVerificationUri(), dc.getUserCode())

            val future = core.auth().loginGitHubAsync(dc) { msg ->
                _status.value = msg
            }
            val account = try {
                awaitCancellableFuture(future)
            } catch (e: kotlinx.coroutines.CancellationException) {
                future.cancel(true)
                future.completeExceptionally(e)
                throw e
            }
            _account.value = account
            upsertAccount(account)
            _status.value = I18n.t("status.logged_in_github", account.getUsername())
            _deviceCode.value = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            _deviceCode.value = null
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.github_login_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _loggingIn.value = false
        }
    }
}

/** 皮肤站（Yggdrasil / authlib-injector）登录 */
fun LauncherViewModel.startYggdrasilLogin(apiUrl: String, username: String, password: String) {
    if (apiUrl.isBlank() || username.isBlank() || password.isBlank()) {
        _status.value = I18n.t("status.yggdrasil_fields_required")
        return
    }
    scope.launch {
        _loggingIn.value = true
        _status.value = I18n.t("status.yggdrasil_logging_in")
        try {
            val account = withContext(Dispatchers.IO) {
                core.auth().yggdrasilLogin(apiUrl, username, password)
            }
            _account.value = account
            upsertAccount(account)
            _status.value = I18n.t("status.logged_in_yggdrasil", account.getUsername())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.yggdrasil_login_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _loggingIn.value = false
        }
    }
}

