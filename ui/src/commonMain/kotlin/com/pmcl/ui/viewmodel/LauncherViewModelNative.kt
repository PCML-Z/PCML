package com.pmcl.ui.viewmodel

import com.pmcl.core.LauncherConfig
import com.pmcl.core.i18n.I18n
import com.pmcl.core.nativeclient.NativeClientInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun LauncherViewModel.refreshNativeClients() {
    _nativeClients.value = NativeClientInstaller.status(LauncherConfig.pmclHome())
}

fun LauncherViewModel.saveNativeCompileRuntime(tool: String, path: String, onSaved: () -> Unit = {}) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                com.pmcl.core.nativeclient.NativeClientCompiler.save(LauncherConfig.pmclHome(), tool, path)
            }
            onSaved()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("native.failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.installNativeClient(id: String, compileRuntime: String = "") {
    initDownloadQueue()
    val name = _nativeClients.value.firstOrNull { it.id == id }?.name ?: id
    core.downloadQueue().submitNativeClient(LauncherConfig.pmclHome(), id, name, compileRuntime) {
        refreshNativeClients()
    }
    _status.value = I18n.t("status.queued_native", name)
    refreshQueue()
}

fun LauncherViewModel.launchNativeClient(id: String) {
    if (!nativeClientGate.compareAndSet(false, true)) return
    scope.launch {
        try {
            val account = _account.value
            withContext(Dispatchers.IO) {
                NativeClientInstaller.launch(
                    LauncherConfig.pmclHome(),
                    id,
                    account?.username ?: "",
                    account?.uuid ?: "",
                    account?.accessToken ?: ""
                )
            }
            _status.value = I18n.t("native.launched")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("native.failed", e.message ?: I18n.t("common.unknown"))
        } finally {
            nativeClientGate.set(false)
        }
    }
}
