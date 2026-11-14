package com.pmcl.ui.desktop

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pmcl.core.agent.AgentApi
import com.pmcl.core.agent.AgentModel
import com.pmcl.core.agent.AgentOptions
import com.pmcl.core.agent.AgentRoutes
import com.pmcl.core.mods.ModMeta
import com.pmcl.plugin.PluginContext
import com.pmcl.ui.viewmodel.LauncherViewModel
import com.pmcl.ui.viewmodel.setVersionIsolation
import com.pmcl.ui.viewmodel.disableMod
import com.pmcl.ui.viewmodel.disableResourcePack
import com.pmcl.ui.viewmodel.disableShaderPack
import com.pmcl.ui.viewmodel.enableMod
import com.pmcl.ui.viewmodel.enableResourcePack
import com.pmcl.ui.viewmodel.enableShaderPack
import com.pmcl.ui.viewmodel.launch
import com.pmcl.ui.viewmodel.refreshInstalledMods
import com.pmcl.ui.viewmodel.refreshResourcePacks
import com.pmcl.ui.viewmodel.refreshShaderPacks
import com.pmcl.ui.viewmodel.refreshWorlds

/**
 * 智能体能实际调用的启动器操作。不读账号令牌，不删档，不改密钥。
 */
internal object LauncherAgent {
    const val TOOLS: String = """
        [
          {"type":"function","function":{"name":"get_launcher_state","description":"查看当前账号、选中的版本、已安装版本和实例。","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"select_version","description":"选中一个已安装的游戏版本，先不启动。","parameters":{"type":"object","properties":{"version_id":{"type":"string"}},"required":["version_id"]}}},
          {"type":"function","function":{"name":"install_version","description":"开始下载并安装一个 Minecraft 版本。","parameters":{"type":"object","properties":{"version_id":{"type":"string"}},"required":["version_id"]}}},
          {"type":"function","function":{"name":"launch_game","description":"启动已安装的版本。没有 version_id 时用当前选中的版本。","parameters":{"type":"object","properties":{"version_id":{"type":"string"}}}}},
          {"type":"function","function":{"name":"launch_instance","description":"启动一个整合包或游戏实例。","parameters":{"type":"object","properties":{"instance_id":{"type":"string"}},"required":["instance_id"]}}},
          {"type":"function","function":{"name":"open_page","description":"打开用户要去的页面，或打开、关闭用户点名的设置选项。page 填用户的原话，例如光影、模组、资源包、整合包、存档、备份、截图、账号、Java、游戏设置、使用 macOS Game Mode 启动游戏、下载队列、模组市场、实例、联机、音乐、插件、统计。section 留空。对不上就不要说已经打开。","parameters":{"type":"object","properties":{"page":{"type":"string"},"section":{"type":"string"}},"required":["page"]}}},
          {"type":"function","function":{"name":"list_api","description":"列出一组启动器接口，给 call_api 用。group 可以是 versions、instances、accounts、launch、mods、market、modpacks、content、queue、loaders、java、servers、stats、news、crash、music、settings、ui、plugins、rooms、i18n、pins、integrity、backups、migration、kernels、skins、recordings、play、wiki、presets、versionjava。","parameters":{"type":"object","properties":{"group":{"type":"string"}},"required":["group"]}}},
          {"type":"function","function":{"name":"call_api","description":"在对应页面上执行操作。method 用 list_api 里的名字，例如 mods.scanMods、mods.enableMod、gameContent.listShaderPacks、gameContent.enableShaderPack、loaders.install。args 按参数顺序。扫描模组不用填目录。启用或禁用模组、光影、资源包时，args 里填名字。成功后会打开那个页面。不能删除存档、版本、实例、账号、备份、皮肤或录屏。","parameters":{"type":"object","properties":{"method":{"type":"string"},"args":{"type":"array"}},"required":["method"]}}}
        ]
        """

    fun run(call: AgentModel.ToolCall, vm: LauncherViewModel, leave: () -> Unit): String {
        val args = arguments(call.arguments)
        return try {
            when (call.name) {
                "get_launcher_state" -> state(vm)
                "select_version" -> select(vm, text(args, "version_id"))
                "install_version" -> install(vm, text(args, "version_id"), leave)
                "launch_game" -> launchGame(vm, text(args, "version_id"), leave)
                "launch_instance" -> launchInstance(vm, text(args, "instance_id"), leave)
                "open_page" -> openPage(vm, text(args, "page"), text(args, "section"), leave)
                "list_api" -> AgentApi.catalog(text(args, "group"))
                "call_api" -> callApi(vm, text(args, "method"), array(args, "args"), leave)
                else -> "没有这个操作：${call.name}"
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message ?: "操作失败"
        }
    }

    fun label(name: String): String = when (name) {
        "get_launcher_state" -> "查看启动器"
        "select_version" -> "选择版本"
        "install_version" -> "安装版本"
        "launch_game" -> "启动游戏"
        "launch_instance" -> "启动实例"
        "open_page" -> "打开页面"
        "list_api" -> "查看接口"
        "call_api" -> "调用启动器"
        else -> name
    }

    private fun state(vm: LauncherViewModel): String {
        val installed = vm.localVersionInfos.value.mapNotNull { it.getId() }
        val instances = vm.instances.value.take(20).joinToString(", ") {
            it.getInstanceId() + "=" + it.getName()
        }
        return buildString {
            append("account=").append(vm.account.value?.getUsername() ?: "none")
            append("; selected=").append(vm.selectedVersion.value ?: "none")
            append("; game_running=").append(vm.gameRunning.value)
            append("; installed=").append(if (installed.isEmpty()) "none" else installed.take(40).joinToString(","))
            append("; instances=").append(if (instances.isEmpty()) "none" else instances)
        }
    }

    private fun select(vm: LauncherViewModel, versionId: String): String {
        val id = resolveInstalled(vm, versionId) ?: return missing(vm, versionId)
        vm.selectVersion(id)
        return "已选中 $id"
    }

    private fun install(vm: LauncherViewModel, versionId: String, leave: () -> Unit): String {
        if (versionId.isBlank()) return "缺少 version_id"
        vm.installVersion(versionId)
        vm.requestSecondaryNav("download", "versions")
        leave()
        return "已开始安装 $versionId，并打开了下载页"
    }

    private fun launchGame(vm: LauncherViewModel, versionId: String, leave: () -> Unit): String {
        if (vm.account.value == null) {
            vm.requestNavigation("accounts")
            leave()
            return "还没有登录账号，已打开账号页"
        }
        val id = if (versionId.isBlank()) vm.selectedVersion.value else resolveInstalled(vm, versionId)
            ?: return missing(vm, versionId)
        if (id.isNullOrBlank()) return "还没有选中版本"
        vm.selectVersion(id)
        vm.launch(id)
        leave()
        return "已开始启动 $id"
    }

    private fun launchInstance(vm: LauncherViewModel, instanceId: String, leave: () -> Unit): String {
        if (instanceId.isBlank()) return "缺少 instance_id"
        val info = vm.instances.value.find { it.getInstanceId() == instanceId || it.getName() == instanceId }
            ?: return "没有这个实例。现有：" + vm.instances.value.take(20).joinToString(", ") { it.getName() }
        vm.launchInstance(info.getInstanceId())
        leave()
        return "已开始启动实例 " + info.getName()
    }

    fun directSetting(text: String): AgentOptions.Change? {
        val change = AgentOptions.match(text) ?: return null
        return if (AgentOptions.direct(text)) change else null
    }

    fun applySetting(vm: LauncherViewModel, change: AgentOptions.Change, leave: () -> Unit): String {
        val os = System.getProperty("os.name", "")
        show(vm, AgentRoutes.Destination(change.route, change.section, change.place))
        leave()
        val name = "「${change.label}」"
        if (!AgentOptions.available(change, os)) {
            return "${name}在这个系统上没有。已打开${change.place}。"
        }
        if (change.confirm) {
            return "${name}要在页面上确认。已打开${change.place}。"
        }
        val enabled = change.enabled
        if (enabled != null) {
            writeSetting(vm, change.id, enabled)
            vm.noteSettingsChanged(change.id)
        }
        return when (enabled) {
            true -> "已开启$name，并打开了${change.place}。"
            false -> "已关闭$name，并打开了${change.place}。"
            else -> "已打开${change.place}里的$name。"
        }
    }

    private fun writeSetting(vm: LauncherViewModel, id: String, enabled: Boolean) {
        val pref = vm.preferences
        when (id) {
            "macGameMode" -> pref.setMacGameMode(enabled)
            "macMic" -> pref.setMacMicrophoneRequest(enabled)
            "versionIsolation" -> vm.setVersionIsolation(enabled)
            "fullscreen" -> pref.setGameFullscreen(enabled)
            "demo" -> pref.setGameDemo(enabled)
            "preferUtf8" -> pref.setPreferUtf8(enabled)
            "preferIpv4" -> pref.setPreferIPv4(enabled)
            "showOutput" -> pref.setShowGameOutput(enabled)
            "fastCheck" -> pref.setFastResourceCheck(enabled)
            "linuxZink" -> pref.setLinuxZink(enabled)
            "forceG1" -> pref.setForceG1Gc(enabled)
            "aikar" -> pref.setUseAikarFlags(enabled)
            "imeAgent" -> pref.setImeFixAgent(enabled)
            "useProxy" -> {
                pref.setUseProxy(enabled)
                vm.applyNetworkPreferences()
            }
            "resume" -> {
                pref.setEnableResume(enabled)
                vm.applyNetworkPreferences()
            }
            "material" -> vm.setMaterialTheme(enabled)
            "glass" -> vm.setGlassTheme(enabled)
            "liveGlass" -> vm.setLiveWallpaperGlass(enabled)
            "lockscreen" -> vm.setLockscreenLaunchTheme(enabled)
            "parallax" -> vm.setParallaxBackground(enabled)
            "dark" -> if (vm.themeState == null) pref.setUseDarkTheme(enabled) else vm.onThemeModeChanged(enabled)
            "followSystem" -> if (vm.themeState == null) pref.setFollowSystemTheme(enabled) else vm.setFollowSystemTheme(enabled)
            "mio" -> pref.setMioModeEnabled(enabled)
            "mioJvm" -> pref.setMioModeJvm(enabled)
            "mioPages" -> pref.setMioModeLargePages(enabled)
            "mioZgc" -> pref.setMioModeZgc(enabled)
            "mioRender" -> pref.setMioModeRenderOpt(enabled)
            "mioJit" -> pref.setMioModeJitAggressive(enabled)
            "mioNet" -> pref.setMioModeNetworkOpt(enabled)
            "mioMeta" -> pref.setMioModeMetaspace(enabled)
            "mioPretouch" -> pref.setMioModePretouch(enabled)
            "mioDedup" -> pref.setMioModeStringDedup(enabled)
            "mioIdle" -> pref.setMioModeIdleGc(enabled)
            "mioProcess" -> pref.setMioModeProcess(enabled)
            "mioAwake" -> pref.setMioModeKeepAwake(enabled)
            "mioGpu" -> pref.setMioModeDiscreteGpu(enabled)
            "mioGameMode" -> pref.setMioModeGameMode(enabled)
        }
    }

    private fun openPage(vm: LauncherViewModel, page: String, section: String, leave: () -> Unit): String {
        val spoken = listOf(page, section).filter { it.isNotBlank() }.joinToString(" ")
        val change = AgentOptions.match(spoken)
        if (change != null) return applySetting(vm, change, leave)
        val dest = AgentRoutes.resolve(page, section) ?: return AgentRoutes.unknown()
        show(vm, dest)
        leave()
        return AgentRoutes.opened(dest)
    }

    private fun show(vm: LauncherViewModel, dest: AgentRoutes.Destination) {
        if (dest.section.isBlank()) vm.requestNavigation(dest.route)
        else vm.requestSecondaryNav(dest.route, dest.section)
        when (dest.route) {
            "content" -> when (dest.section) {
                "mods" -> vm.refreshInstalledMods()
                "shaders" -> vm.refreshShaderPacks()
                "resourcepacks" -> vm.refreshResourcePacks()
                "modpacks" -> vm.refreshModpacks()
            }
            "saves" -> if (dest.section == "worlds") vm.refreshWorlds()
            "download" -> when (dest.section) {
                "versions" -> vm.refreshLocalVersions()
                "queue" -> vm.refreshQueue()
            }
            "instances" -> vm.loadInstances()
            "statistics" -> vm.refreshPlayTimeStats()
            "settings" -> if (dest.section == "java") vm.scanJavaInstallations()
        }
    }

    private fun resolveInstalled(vm: LauncherViewModel, versionId: String): String? {
        if (versionId.isBlank()) return null
        val ids = vm.localVersionInfos.value.mapNotNull { it.getId() }
        if (versionId in ids) return versionId
        return ids.filter { it.equals(versionId, true) || it.startsWith(versionId) }.singleOrNull()
    }

    private fun missing(vm: LauncherViewModel, versionId: String): String {
        val ids = vm.localVersionInfos.value.mapNotNull { it.getId() }.take(20).joinToString(", ")
        return "没有安装 $versionId。已安装：$ids。需要的话调用 install_version。"
    }

    private fun callApi(vm: LauncherViewModel, method: String, args: JsonArray, leave: () -> Unit): String {
        val name = method.trim()
        if (name == "ui.navigate") {
            val target = if (args.size() > 0 && args.get(0).isJsonPrimitive) args.get(0).asString else ""
            return openPage(vm, target, "", leave)
        }
        val onPage = when (name) {
            "mods.scanMods" -> {
                val dest = AgentRoutes.homeFor(name)
                if (dest != null) show(vm, dest)
                leave()
                return if (dest == null) "已重新扫描模组" else AgentRoutes.opened(dest) + "，并重新扫描了模组"
            }
            "mods.enableMod" -> modSwitch(vm, args, enable = true, leave)
            "mods.disableMod" -> modSwitch(vm, args, enable = false, leave)
            "gameContent.enableShaderPack" -> packSwitch(vm, args, shaders = true, enable = true, leave)
            "gameContent.disableShaderPack" -> packSwitch(vm, args, shaders = true, enable = false, leave)
            "gameContent.enableResourcePack" -> packSwitch(vm, args, shaders = false, enable = true, leave)
            "gameContent.disableResourcePack" -> packSwitch(vm, args, shaders = false, enable = false, leave)
            else -> null
        }
        if (onPage != null) return onPage
        val result = AgentApi.invoke(pluginContext(vm), name, args)
        val dest = AgentRoutes.homeFor(name)
        if (dest != null && !failed(result)) {
            show(vm, dest)
            leave()
            return AgentRoutes.opened(dest) + "\n" + result
        }
        if (AgentApi.leaves(name) && !failed(result)) leave()
        return result
    }

    private fun modSwitch(vm: LauncherViewModel, args: JsonArray, enable: Boolean, leave: () -> Unit): String {
        val wanted = argText(args, 0)
        if (wanted.isBlank()) return "缺少模组名字"
        val hits = vm.installedMods.value.filter { matchesMod(it, wanted) }
        val dest = AgentRoutes.resolve("模组", "")
        if (dest != null) show(vm, dest)
        if (hits.isEmpty()) {
            leave()
            return "没有在已扫描的模组里找到「$wanted」。已打开模组页并重新扫描。"
        }
        if (hits.size > 1) {
            return "有多个模组像「$wanted」：" + hits.take(8).joinToString("、") { it.name ?: it.jarFile ?: it.modId }
        }
        val mod = hits[0]
        if (enable) vm.enableMod(mod) else vm.disableMod(mod)
        leave()
        val title = mod.name ?: mod.jarFile ?: wanted
        return (if (enable) "已启用 " else "已禁用 ") + title + "。" + (dest?.let { AgentRoutes.opened(it) } ?: "")
    }

    private fun packSwitch(
        vm: LauncherViewModel,
        args: JsonArray,
        shaders: Boolean,
        enable: Boolean,
        leave: () -> Unit
    ): String {
        val wanted = argText(args, 0)
        if (wanted.isBlank()) return if (shaders) "缺少光影名字" else "缺少资源包名字"
        val dest = AgentRoutes.resolve(if (shaders) "光影" else "资源包", "")
        if (dest != null) show(vm, dest)
        if (shaders) {
            val hits = vm.shaderPacks.value.filter {
                it.name.contains(wanted, true) || it.path.fileName?.toString().orEmpty().contains(wanted, true)
            }
            if (hits.isEmpty()) {
                leave()
                return "没有在光影列表里找到「$wanted」。已打开光影页并重新扫描。"
            }
            if (hits.size > 1) return "有多个光影像「$wanted」：" + hits.take(8).joinToString("、") { it.name }
            if (enable) vm.enableShaderPack(hits[0]) else vm.disableShaderPack(hits[0])
            leave()
            return (if (enable) "已启用光影 " else "已禁用光影 ") + hits[0].name + "。" + (dest?.let { AgentRoutes.opened(it) } ?: "")
        }
        val hits = vm.resourcePacks.value.filter {
            it.name.contains(wanted, true) || it.path.fileName?.toString().orEmpty().contains(wanted, true)
        }
        if (hits.isEmpty()) {
            leave()
            return "没有在资源包列表里找到「$wanted」。已打开资源包页并重新扫描。"
        }
        if (hits.size > 1) return "有多个资源包像「$wanted」：" + hits.take(8).joinToString("、") { it.name }
        if (enable) vm.enableResourcePack(hits[0]) else vm.disableResourcePack(hits[0])
        leave()
        return (if (enable) "已启用资源包 " else "已禁用资源包 ") + hits[0].name + "。" + (dest?.let { AgentRoutes.opened(it) } ?: "")
    }

    private fun matchesMod(mod: ModMeta, wanted: String): Boolean {
        val file = mod.jarFile.orEmpty()
        val title = mod.name.orEmpty()
        val id = mod.modId.orEmpty()
        return file.equals(wanted, true) || title.equals(wanted, true) || id.equals(wanted, true) ||
            file.contains(wanted, true) || title.contains(wanted, true) || id.contains(wanted, true)
    }

    private fun argText(args: JsonArray, index: Int): String {
        if (index >= args.size() || !args.get(index).isJsonPrimitive) return ""
        return args.get(index).asString.trim()
    }

    private fun pluginContext(vm: LauncherViewModel): PluginContext {
        return vm.core.plugins().appContext(
            "pmcl.agent",
            vm.config.workDir.resolve("agent-data"),
            AgentApi.permissions()
        )
    }

    private fun failed(result: String): Boolean {
        return result.startsWith("不能执行") ||
            result.startsWith("permission_denied") ||
            result.startsWith("unknown_method") ||
            result.startsWith("failed") ||
            result.startsWith("bad_params") ||
            result.startsWith("api_unavailable") ||
            result.startsWith("缺少")
    }

    private fun arguments(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject()
        return try {
            val parsed = JsonParser.parseString(raw)
            if (parsed.isJsonObject) parsed.asJsonObject else JsonObject()
        } catch (_: Exception) {
            JsonObject()
        }
    }

    private fun text(args: JsonObject, key: String): String {
        if (!args.has(key) || !args.get(key).isJsonPrimitive) return ""
        return args.get(key).asString.trim()
    }

    private fun array(args: JsonObject, key: String): JsonArray {
        if (!args.has(key) || !args.get(key).isJsonArray) return JsonArray()
        return args.get(key).asJsonArray
    }

}
