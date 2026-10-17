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

fun LauncherViewModel.saveNativeCompileOptions(
    jobs: String,
    timeout: String,
    mode: String,
    extraArgs: Map<String, String>,
    onSaved: () -> Unit = {}
) {
    scope.launch {
        try {
            withContext(Dispatchers.IO) {
                com.pmcl.core.nativeclient.NativeClientCompiler.saveOptions(
                    LauncherConfig.pmclHome(), jobs, timeout, mode, extraArgs
                )
            }
            onSaved()
            _status.value = I18n.t("native.options_saved")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("native.failed", e.message ?: I18n.t("common.unknown"))
        }
    }
}

fun LauncherViewModel.saveNativeClientAsInstance(id: String) {
    scope.launch {
        try {
            val result = withContext(Dispatchers.IO) {
                val home = LauncherConfig.pmclHome()
                val card = NativeClientInstaller.status(home).firstOrNull { it.id == id }
                    ?: throw java.io.IOException(I18n.t("native.unknown"))
                if (!card.isInstalled) throw java.io.IOException(I18n.t("native.not_installed_yet"))
                val existed = core.instances().listInstances().any { it.nativeClientId == id }
                val info = core.instances().createCommunityInstance(
                    card.name, id, card.minecraftVersion, card.installedTag
                )
                existed to info
            }
            loadInstances()
            _status.value = if (result.first) {
                I18n.t("status.native_instance_exists", result.second.name)
            } else {
                I18n.t("status.native_instance_saved", result.second.name)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            _status.value = I18n.t("native.failed", e.message ?: I18n.t("common.unknown"))
        }
    }
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
