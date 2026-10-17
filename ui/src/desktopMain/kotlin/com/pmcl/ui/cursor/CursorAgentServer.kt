package com.pmcl.ui.cursor

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.pmcl.core.cursor.CursorAgentException
import com.pmcl.core.cursor.CursorAgentHttp
import com.pmcl.core.cursor.CursorAgentSession
import com.pmcl.core.cursor.CursorAgentStore
import com.pmcl.core.i18n.I18n
import com.pmcl.ui.navigation.SecondaryNavRegistry
import com.pmcl.ui.navigation.allDestinations
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.launch
import java.nio.file.Path

/**
 * 本机 Cursor 智能体操作面。默认关闭，只听 127.0.0.1。
 * 账号令牌、密码和游戏日志不放进任何工具结果。
 */
class CursorAgentServer(
    private val vm: LauncherViewModel,
    private val pmclHome: Path,
) : CursorAgentHost {
    private val session = VmSession(vm)
    private val life = Any()
    private var http: CursorAgentHttp? = null

    @Volatile
    private var lastError: String = ""

    fun startIfEnabled() {
        synchronized(life) {
            val cfg = CursorAgentStore.load(pmclHome)
            if (!cfg.enabled || !CursorAgentStore.validToken(cfg.token)) return
            startLocked(cfg)
        }
    }

    fun stop() {
        synchronized(life) { stopLocked() }
    }

    override fun state(): CursorAgentUiState {
        synchronized(life) {
            val cfg = CursorAgentStore.load(pmclHome)
            val live = http
            val port = if (live != null && live.port() > 0) live.port() else cfg.port
            val tail = if (cfg.token.length >= 4) cfg.token.takeLast(4) else ""
            return CursorAgentUiState(
                available = true,
                enabled = cfg.enabled,
                running = live != null && live.port() > 0,
                port = port,
                tokenTail = tail,
                error = lastError,
            )
        }
    }

    override fun setEnabled(enabled: Boolean) {
        synchronized(life) {
            val cfg = CursorAgentStore.setEnabled(pmclHome, enabled)
            if (!enabled) {
                stopLocked()
                lastError = ""
                return
            }
            val live = http
            if (live != null) {
                live.setToken(cfg.token)
                return
            }
            startLocked(cfg)
        }
    }

    override fun rotateToken() {
        synchronized(life) {
            val cfg = CursorAgentStore.rotateToken(pmclHome)
            http?.setToken(cfg.token)
        }
    }

    override fun writeCursorConfig(): String {
        synchronized(life) {
            val live = http ?: throw java.io.IOException(I18n.t("settings.cursor.need_listen"))
            val cfg = CursorAgentStore.load(pmclHome)
            CursorAgentStore.writeMcpConfig(CursorAgentStore.cursorMcpFile(), live.port(), cfg.token)
            return I18n.t("settings.cursor.written")
        }
    }

    override fun mcpSnippet(): String {
        synchronized(life) {
            val live = http ?: throw java.io.IOException(I18n.t("settings.cursor.need_listen"))
            val cfg = CursorAgentStore.load(pmclHome)
            return try {
                CursorAgentStore.snippet(live.port(), cfg.token)
            } catch (e: IllegalArgumentException) {
                throw java.io.IOException(I18n.t("settings.cursor.need_enable"))
            }
        }
    }

    private fun startLocked(cfg: CursorAgentStore.Config) {
        stopLocked()
        if (!cfg.enabled || !CursorAgentStore.validToken(cfg.token)) return
        val created = CursorAgentHttp(session, cfg.token)
        try {
            val bound = created.start(cfg.port)
            http = created
            lastError = ""
            if (bound != cfg.port) {
                try {
                    CursorAgentStore.savePort(pmclHome, bound)
                } catch (e: Exception) {
                    lastError = I18n.t("settings.cursor.bind_failed", e.message ?: "")
                }
            }
        } catch (e: Exception) {
            created.stop()
            http = null
            lastError = I18n.t("settings.cursor.bind_failed", e.message ?: "")
        }
    }

    private fun stopLocked() {
        http?.stop()
        http = null
    }
}

private class VmSession(private val vm: LauncherViewModel) : CursorAgentSession {
    override fun launcherVersion(): String {
        return try {
            vm.core.launcherVersion()
        } catch (e: Exception) {
            "pmcl"
        }
    }

    override fun status(): JsonObject = guard {
        val obj = JsonObject()
        obj.addProperty("version", launcherVersion())
        obj.addProperty("status", clip(vm.status.value, 300))
        val selected = vm.selectedVersion.value
        if (selected.isNullOrBlank()) obj.add("selectedVersion", JsonNull.INSTANCE)
        else obj.addProperty("selectedVersion", selected)
        val instance = vm.selectedInstanceId.value
        if (instance.isNullOrBlank()) obj.add("selectedInstanceId", JsonNull.INSTANCE)
        else obj.addProperty("selectedInstanceId", instance)
        val account = vm.account.value
        if (account == null) {
            obj.add("account", JsonNull.INSTANCE)
        } else {
            val row = JsonObject()
            row.addProperty("username", clip(account.username, 32))
            row.addProperty("type", account.type?.name ?: "")
            obj.add("account", row)
        }
        obj.addProperty("gameRunning", vm.gameRunning.value)
        obj.addProperty("launchBusy", vm.isDesktopLaunchBusy())
        val running = JsonArray()
        for (item in vm.runningInstances.value) {
            val row = JsonObject()
            row.addProperty("id", item.id)
            row.addProperty("versionId", item.versionId)
            row.addProperty("accountName", clip(item.accountName, 32))
            row.addProperty("active", item.active)
            running.add(row)
        }
        obj.add("running", running)
        obj
    }

    override fun versions(): JsonObject = guard {
        val ids = vm.core.versions().listLocalVersions().sorted()
        val arr = JsonArray()
        ids.forEach { arr.add(it) }
        val obj = JsonObject()
        obj.add("versions", arr)
        obj
    }

    override fun instances(): JsonObject = guard {
        val arr = JsonArray()
        for (info in vm.syncInstancesForAgent().sortedBy { it.name ?: "" }) {
            val row = JsonObject()
            row.addProperty("id", info.instanceId)
            row.addProperty("name", clip(info.name, 64))
            row.addProperty("version", clip(info.baseVersionId, 80))
            row.addProperty("loader", clip(info.loader, 32))
            row.addProperty("loaderVersion", clip(info.loaderVersion, 64))
            row.addProperty("nativeClientId", clip(info.nativeClientId, 64))
            row.addProperty("launchable", info.isLaunchable)
            row.addProperty("description", clip(info.description, 120))
            arr.add(row)
        }
        val obj = JsonObject()
        obj.add("instances", arr)
        obj
    }

    override fun accounts(): JsonObject = guard {
        val current = vm.account.value
        val arr = JsonArray()
        for (account in vm.accounts.value) {
            val row = JsonObject()
            row.addProperty("username", clip(account.username, 32))
            row.addProperty("type", account.type?.name ?: "")
            row.addProperty("current", account === current)
            arr.add(row)
        }
        val obj = JsonObject()
        obj.add("accounts", arr)
        obj
    }

    override fun pages(): JsonObject = guard {
        val arr = JsonArray()
        for (dest in allDestinations) {
            val row = JsonObject()
            row.addProperty("route", dest.route)
            val sections = JsonArray()
            SecondaryNavRegistry.specFor(dest)?.sections?.forEach { sections.add(it.id) }
            row.add("sections", sections)
            arr.add(row)
        }
        val obj = JsonObject()
        obj.add("pages", arr)
        obj
    }

    override fun openPage(route: String, section: String): JsonObject = guard {
        val dest = allDestinations.firstOrNull { it.route == route }
            ?: throw CursorAgentException("没有这个页面")
        if (section.isNotEmpty()) {
            val spec = SecondaryNavRegistry.specFor(dest)
            if (spec == null || spec.sections.none { it.id == section }) {
                throw CursorAgentException("这个页面没有该分区")
            }
            vm.requestSecondaryNav(route, section)
        } else {
            vm.requestNavigation(route)
        }
        val obj = JsonObject()
        obj.addProperty("ok", true)
        obj.addProperty("route", route)
        if (section.isNotEmpty()) obj.addProperty("section", section)
        obj
    }

    override fun selectVersion(versionId: String): JsonObject = guard {
        ensureLocal(versionId)
        vm.selectVersion(versionId)
        val obj = JsonObject()
        obj.addProperty("ok", true)
        obj.addProperty("versionId", versionId)
        obj
    }

    override fun launchVersion(versionId: String): JsonObject = guard {
        ensureLocal(versionId)
        ensureAccount()
        vm.selectVersion(versionId)
        vm.launch(versionId)
        started("versionId", versionId)
    }

    override fun launchInstance(instanceId: String): JsonObject = guard {
        val info = vm.syncInstancesForAgent().firstOrNull { it.instanceId == instanceId }
            ?: throw CursorAgentException("没有这个实例")
        if (!info.isLaunchable) throw CursorAgentException("这个实例还不能启动")
        if (info.nativeClientId.isNullOrBlank() && vm.account.value == null) {
            throw CursorAgentException("还没有登录账号")
        }
        vm.launchInstance(instanceId)
        started("instanceId", instanceId)
    }

    override fun launchSelected(): JsonObject = guard {
        val versionId = vm.selectedVersion.value
        if (versionId.isNullOrBlank()) throw CursorAgentException("还没有选择版本")
        ensureLocal(versionId)
        ensureAccount()
        vm.launch()
        started("versionId", versionId)
    }

    override fun refresh(): JsonObject = guard {
        vm.refreshLocalVersions()
        val instances = vm.syncInstancesForAgent().size
        val versions = vm.core.versions().listLocalVersions().size
        val obj = JsonObject()
        obj.addProperty("versions", versions)
        obj.addProperty("instances", instances)
        obj
    }

    private fun ensureLocal(versionId: String) {
        if (versionId !in vm.core.versions().listLocalVersions()) {
            throw CursorAgentException("没有这个本地版本")
        }
    }

    private fun ensureAccount() {
        if (vm.account.value == null) throw CursorAgentException("还没有登录账号")
    }

    private fun started(key: String, value: String): JsonObject {
        val obj = JsonObject()
        obj.addProperty("ok", true)
        obj.addProperty(key, value)
        obj.addProperty("message", "已交给启动器。再用 pmcl_status 确认游戏有没有跑起来。")
        return obj
    }

    private inline fun guard(block: () -> JsonObject): JsonObject {
        try {
            return block()
        } catch (e: CursorAgentException) {
            throw e
        } catch (e: Exception) {
            throw CursorAgentException(clip(e.message, 200).ifBlank { "failed" })
        }
    }
}

private fun clip(value: String?, max: Int): String {
    if (value.isNullOrBlank()) return ""
    val flat = value.replace('\r', ' ').replace('\n', ' ')
    return if (flat.length <= max) flat else flat.substring(0, max)
}
