package com.pmcl.ui.viewmodel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.pmcl.core.i18n.I18n
import com.pmcl.core.mods.ModUpdateChecker


/**
 * M29 拆分：模组更新检测域。
 */

// ============ 模组更新检测 ============

/**
 * 检测已安装模组的更新。
 * 自动从当前选中版本推断 gameVersion。
 */
fun LauncherViewModel.checkModUpdates() {
    val mods = _installedMods.value
    if (mods.isEmpty()) {
        _status.value = I18n.t("status.no_installed_mods")
        return
    }
    // 从选中版本推断 gameVersion
    val versionId = _selectedVersion.value
    val gameVersion = inferGameVersion(versionId)
    _updateGameVersion.value = gameVersion

    if (_checkingUpdates.value) return // 防止重复检测
    _checkingUpdates.value = true
    _updateCheckProgress.value = 0 to mods.size
    _status.value = I18n.t("status.checking_mod_updates")

    scope.launch {
        try {
            val results = core.modUpdateChecker().checkUpdates(
                mods, gameVersion
            ) { progress ->
                _updateCheckProgress.value = progress[0] to progress[1]
            }.join()
            _modUpdates.value = results
            val updateCount = results.count { it.hasUpdate() }
            _status.value = if (updateCount > 0) {
                I18n.t("status.mod_updates_found", updateCount)
            } else {
                I18n.t("status.mod_updates_all_latest")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.check_mod_updates_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _checkingUpdates.value = false
        }
    }
}

/**
 * 从版本 ID 推断 gameVersion（如 "1.20.4-OptiFine_HD_U_I7" → "1.20.4"）。
 */
@PublishedApi
internal fun LauncherViewModel.inferGameVersion(versionId: String?): String {
    if (versionId == null || versionId.isEmpty()) return ""
    // 取第一个分隔符前的部分（Forge/Fabric/OptiFine 版本通常用 - 分隔）
    val idx = versionId.indexOfAny(charArrayOf('-', '+'))
    return if (idx > 0) versionId.substring(0, idx) else versionId
}

/**
 * 更新单个模组。
 */
fun LauncherViewModel.updateMod(info: ModUpdateChecker.UpdateInfo) {
    if (_updatingMod.value) return
    _updatingMod.value = true
    val versionId = _selectedVersion.value
    val gameVersion = _updateGameVersion.value

    scope.launch {
        try {
            core.modUpdateChecker().updateMod(info, gameVersion, versionId) { status ->
                _status.value = status
            }.join()
            _status.value = I18n.t("status.mod_update_complete", info.displayName())
            // 刷新已安装模组列表
            refreshInstalledMods()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.mod_update_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _updatingMod.value = false
        }
    }
}

/**
 * 一键更新所有有更新的模组。
 */
fun LauncherViewModel.updateAllMods() {
    val updates = _modUpdates.value.filter { it.hasUpdate() }
    if (updates.isEmpty()) {
        _status.value = I18n.t("status.no_mods_to_update")
        return
    }
    if (_updatingMod.value) return
    _updatingMod.value = true
    val versionId = _selectedVersion.value
    val gameVersion = _updateGameVersion.value
    _status.value = I18n.t("status.batch_updating_mods", updates.size)

    scope.launch {
        try {
            core.modUpdateChecker().updateAll(updates, gameVersion, versionId) { progress ->
                _updateCheckProgress.value = progress[0] to progress[1]
                _status.value = I18n.t("status.batch_updating_progress", progress[0], progress[1])
            }.join()
            _status.value = I18n.t("status.batch_update_complete")
            refreshInstalledMods()
            // 重新检测一次
            checkModUpdates()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("status.batch_update_failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            _updatingMod.value = false
        }
    }
}

/** 清空更新检测结果 */
fun LauncherViewModel.clearModUpdates() {
    _modUpdates.value = emptyList()
}

