package com.pmcl.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.pmcl.core.ecxp.EcxpBridge;
import com.pmcl.plugin.PluginContext;

import java.util.Set;

/**
 * 助手能调用的启动器接口，和 Python / ECXP 用的是同一套方法名。
 * 不读账号令牌，不删存档、版本、实例和账号，不写 preferences.json，也不碰本机文件接口。
 */
public final class AgentApi {

    private static final int MAX_CHARS = 3500;

    private static final Set<String> DENY = Set.of(
            "versions.deleteVersion",
            "instances.deleteInstance",
            "gameContent.deleteWorld",
            "gameContent.deleteScreenshot",
            "mods.deleteMod",
            "accounts.addOfflineAccount",
            "accounts.removeAccount",
            "plugins.unloadPlugin",
            "settings.setCustomJvmArgs",
            "settings.setCustomMirrorBase",
            "settings.setPluginConfig",
            "ui.hideBuiltinNav",
            "ui.showDialog",
            "ui.showInputDialog",
            "ui.pickFile",
            "ui.pickFolder",
            "launch.killAllGames",
            "gameProcess.killProcess",
            "i18n.registerStrings",
            "i18n.clearStrings",
            "backups.deleteSnapshot",
            "backups.restore",
            "kernels.remove",
            "skins.delete",
            "recordings.delete",
            "presets.delete"
    );

    private static final Set<String> LEAVE = Set.of(
            "launch.requestLaunch",
            "launch.requestLaunchInstance",
            "ui.navigate",
            "versions.installVersion",
            "downloadQueue.enqueueVersionInstall",
            "modMarket.install",
            "javaRuntimes.install",
            "loaders.install"
    );

    private AgentApi() {}

    /** 助手上下文需要的权限。没有 FILESYSTEM、KILL_PROCESS、SELF_UPDATE。 */
    public static Set<String> permissions() {
        return Set.of(
                "READ_ACCOUNTS",
                "WRITE_ACCOUNTS",
                "CONTROL_LAUNCH",
                "MANAGE_PLUGINS",
                "MANAGE_INSTANCES",
                "READ_MODS",
                "MANAGE_MODS",
                "MANAGE_MODPACKS",
                "MANAGE_GAME_CONTENT",
                "READ_STATS",
                "CONTROL_ROOMS",
                "MANAGE_VERSIONS",
                "MANAGE_SERVERS",
                "READ_CRASH_LOGS",
                "CONTROL_MUSIC",
                "WRITE_SETTINGS",
                "NETWORK"
        );
    }

    public static boolean denied(String method) {
        if (method == null || method.isBlank()) return true;
        if (method.startsWith("filesystem.") || method.startsWith("http.")
                || method.startsWith("downloads.") || method.startsWith("scheduler.")
                || method.startsWith("nbt.")) {
            return true;
        }
        return DENY.contains(method);
    }

    /** 调用成功后应离开调度中心，让用户看到页面或启动进度。 */
    public static boolean leaves(String method) {
        return LEAVE.contains(method);
    }

    /**
     * 某一组接口的方法名和参数顺序。group 不认识时返回分组列表。
     */
    public static String catalog(String group) {
        String key = group == null ? "" : group.trim().toLowerCase();
        return switch (key) {
            case "versions" -> """
                    versions.listLocalVersionIds()
                    versions.listLocalVersions()
                    versions.isLaunchable(versionId)
                    versions.installVersion(versionId)
                    versions.listRemoteVersions(limit)
                    versions.resolveVersionDir(versionId)""";
            case "instances" -> """
                    instances.listInstances()
                    instances.getInstance(instanceId)
                    instances.createInstance(name, baseVersionId, loader, loaderVersion)
                    instances.renameInstance(instanceId, newName)
                    instances.setDescription(instanceId, description)
                    instances.resolveInstanceDir(instanceId)""";
            case "accounts" -> """
                    accounts.listAccounts()
                    accounts.getSelectedAccount()
                    accounts.selectAccount(uuid)""";
            case "launch" -> """
                    launch.requestLaunch(versionId)
                    launch.requestLaunchInstance(instanceId)
                    launch.isGameRunning()
                    launch.activeProcessCount()
                    gameProcess.listProcesses()""";
            case "mods" -> """
                    mods.resolveModsDir(versionOrInstanceId)
                    mods.scanMods(modsDir)
                    mods.enableMod(jarPath)
                    mods.disableMod(jarPath)""";
            case "market" -> """
                    modMarket.search(query, gameVersion, loader, limit)
                    modMarket.listFiles(source, projectId)
                    modMarket.install(source, projectId, fileId, gameVersion, versionOrInstanceId)""";
            case "modpacks" -> """
                    modpacks.listInstalled()
                    modpacks.importModpack(file)""";
            case "content" -> """
                    gameContent.listWorlds()
                    gameContent.backupWorld(worldName)
                    gameContent.importWorld(zipFile)
                    gameContent.listResourcePacks()
                    gameContent.enableResourcePack(fileName)
                    gameContent.disableResourcePack(fileName)
                    gameContent.listShaderPacks()
                    gameContent.enableShaderPack(fileName)
                    gameContent.disableShaderPack(fileName)
                    gameContent.listDatapacks(worldDir)
                    gameContent.enableDatapack(worldDir, fileName)
                    gameContent.disableDatapack(worldDir, fileName)
                    gameContent.listScreenshots()""";
            case "queue" -> """
                    downloadQueue.summary()
                    downloadQueue.listTasks()
                    downloadQueue.enqueueVersionInstall(versionId)
                    downloadQueue.pause(taskId)
                    downloadQueue.resume(taskId)
                    downloadQueue.cancel(taskId)
                    downloadQueue.clearFinished()""";
            case "loaders" -> """
                    loaderVersions.listVersions(loader, gameVersion)
                    loaders.names()
                    loaders.install(loader, gameVersion, loaderVersion)""";
            case "java" -> """
                    javaRuntimes.listInstalled()
                    javaRuntimes.listAvailable(type)
                    javaRuntimes.install(type, version)
                    javaRuntimes.hostMetrics()""";
            case "servers" -> """
                    servers.listFavorites()
                    servers.addFavorite(name, host, port)
                    servers.removeFavorite(index)
                    servers.updateFavorite(index, name, host, port)
                    servers.ping(host, port)
                    servers.getDirectConnectHost()
                    servers.getDirectConnectPort()
                    servers.setDirectConnect(host, port)""";
            case "stats" -> """
                    stats.overall(recentDays)
                    stats.sessions(offset, limit)""";
            case "news" -> """
                    news.fetchNews(limit)""";
            case "crash" -> """
                    crashLogs.listReports()
                    crashLogs.analyzeText(content)""";
            case "music" -> """
                    music.nowPlaying()
                    music.pause()
                    music.resume()
                    music.stop()
                    music.playNext()
                    music.playPrevious()
                    music.setVolume(volume)""";
            case "settings" -> """
                    settings.getLanguage()
                    settings.setLanguage(language)
                    settings.getThemePreset()
                    settings.setThemePreset(preset)
                    settings.isDarkTheme()
                    settings.setDarkTheme(enabled)
                    settings.getMaxMemoryMb()
                    settings.setMaxMemoryMb(mb)
                    settings.getMinMemoryMb()
                    settings.setMinMemoryMb(mb)
                    settings.getJavaPath()
                    settings.setJavaPath(path)
                    settings.isVersionIsolation()
                    settings.setVersionIsolation(enabled)
                    settings.getGameWindowWidth()
                    settings.getGameWindowHeight()
                    settings.setGameWindowSize(width, height)
                    settings.isGameFullscreen()
                    settings.setGameFullscreen(enabled)
                    settings.getDownloadThreads()
                    settings.setDownloadThreads(threads)
                    settings.getMirrorType()""";
            case "ui" -> """
                    ui.notify(title, message, level)
                    ui.copyToClipboard(text)
                    ui.openUrl(url)
                    ui.showProgress(id, title, progress)
                    ui.dismissProgress(id)
                    ui.setNavBadge(target, text)
                    ui.clearNavBadge(target)
                    ui.showBuiltinNav(route)""";
            case "plugins" -> """
                    plugins.listPlugins()
                    plugins.getPlugin(pluginId)
                    plugins.isEnabled(pluginId)
                    plugins.enablePlugin(pluginId)
                    plugins.disablePlugin(pluginId)""";
            case "rooms" -> """
                    rooms.state()
                    rooms.createRoom()
                    rooms.joinRoom(invitationCode)
                    rooms.leaveRoom()
                    rooms.invitation()
                    rooms.virtualIp()""";
            case "i18n" -> """
                    i18n.currentLanguage()
                    i18n.t(key)""";
            case "pins" -> """
                    pins.pinned()
                    pins.recent()
                    pins.pin(versionId)
                    pins.unpin(versionId)""";
            case "integrity" -> """
                    integrity.check(versionId)""";
            case "backups" -> """
                    backups.listSnapshots()
                    backups.settings()
                    backups.storedBytes()
                    backups.changes(snapshotId)
                    backups.setNote(snapshotId, note)
                    backups.setPinned(snapshotId, pinned)""";
            case "migration" -> """
                    migration.detect()""";
            case "kernels" -> """
                    kernels.list()
                    kernels.setEnabled(id, enabled)""";
            case "skins" -> """
                    skins.list()
                    skins.importPng(path, name)""";
            case "recordings" -> """
                    recordings.list()""";
            case "play" -> """
                    play.records()
                    play.weekdays(recentDays)
                    play.versions(recentDays)
                    play.worlds()
                    play.servers()
                    play.instances()
                    play.mods(top)""";
            case "wiki" -> """
                    wiki.search(query)
                    wiki.minecraft(query)
                    wiki.modrinth(slug)
                    wiki.curseForge(projectId)""";
            case "presets" -> """
                    presets.list()
                    presets.apply(name)""";
            case "versionjava" -> """
                    versionJava.get(versionId)
                    versionJava.set(versionId, javaPath)
                    versionJava.customVersions()""";
            default -> "groups: versions, instances, accounts, launch, mods, market, modpacks, content, queue, loaders, java, servers, stats, news, crash, music, settings, ui, plugins, rooms, i18n, pins, integrity, backups, migration, kernels, skins, recordings, play, wiki, presets, versionjava";
        };
    }

    public static String invoke(PluginContext context, String method, JsonArray args) {
        String name = method == null ? "" : method.trim();
        if (name.isEmpty()) return "缺少 method";
        if (denied(name)) return "不能执行 " + name;
        try {
            JsonElement result = EcxpBridge.call(context, name, args == null ? new JsonArray() : args);
            String text = result == null ? "null" : result.toString();
            if (text.length() > MAX_CHARS) return text.substring(0, MAX_CHARS) + "…";
            return text;
        } catch (EcxpBridge.Failure e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            return message.isEmpty() ? e.code() : e.code() + ": " + message;
        }
    }
}
