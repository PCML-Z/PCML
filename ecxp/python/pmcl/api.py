"""启动器接口。方法名和 com.pmcl.plugin.api 里的方法一致。"""

from pmcl.client import connect as open_connection


class _Api:
    def __init__(self, app, name):
        self._app = app
        self._name = name

    def _call(self, method, *args):
        return self._app.call(f"{self._name}.{method}", args)


class Versions(_Api):
    def __init__(self, app):
        super().__init__(app, "versions")

    def listLocalVersionIds(self):
        return self._call("listLocalVersionIds")

    def listLocalVersions(self):
        return self._call("listLocalVersions")

    def isLaunchable(self, version_id):
        return self._call("isLaunchable", version_id)

    def installVersion(self, version_id):
        return self._call("installVersion", version_id)

    def deleteVersion(self, version_id):
        return self._call("deleteVersion", version_id)

    def resolveVersionDir(self, version_id):
        return self._call("resolveVersionDir", version_id)

    def listRemoteVersions(self, limit=100):
        return self._call("listRemoteVersions", limit)


class Instances(_Api):
    def __init__(self, app):
        super().__init__(app, "instances")

    def listInstances(self):
        return self._call("listInstances")

    def getInstance(self, instance_id):
        return self._call("getInstance", instance_id)

    def createInstance(self, name, base_version_id, loader=None, loader_version=None):
        args = [name, base_version_id]
        if loader is not None or loader_version is not None:
            args.append(loader)
        if loader_version is not None:
            args.append(loader_version)
        return self._call("createInstance", *args)

    def renameInstance(self, instance_id, new_name):
        return self._call("renameInstance", instance_id, new_name)

    def deleteInstance(self, instance_id):
        return self._call("deleteInstance", instance_id)

    def setDescription(self, instance_id, description):
        return self._call("setDescription", instance_id, description)

    def resolveInstanceDir(self, instance_id):
        return self._call("resolveInstanceDir", instance_id)


class Accounts(_Api):
    def __init__(self, app):
        super().__init__(app, "accounts")

    def listAccounts(self):
        return self._call("listAccounts")

    def getSelectedAccount(self):
        return self._call("getSelectedAccount")

    def selectAccount(self, uuid):
        return self._call("selectAccount", uuid)

    def addOfflineAccount(self, username):
        return self._call("addOfflineAccount", username)

    def removeAccount(self, uuid):
        return self._call("removeAccount", uuid)


class Launch(_Api):
    def __init__(self, app):
        super().__init__(app, "launch")

    def requestLaunch(self, version_id):
        """返回 None 表示启动器已接受，否则是拒绝原因。"""
        return self._call("requestLaunch", version_id)

    def requestLaunchInstance(self, instance_id):
        return self._call("requestLaunchInstance", instance_id)

    def killAllGames(self):
        return self._call("killAllGames")

    def isGameRunning(self):
        return self._call("isGameRunning")

    def activeProcessCount(self):
        return self._call("activeProcessCount")


class GameProcess(_Api):
    def __init__(self, app):
        super().__init__(app, "gameProcess")

    def listProcesses(self):
        return self._call("listProcesses")

    def killProcess(self, pid):
        return self._call("killProcess", pid)


class Mods(_Api):
    def __init__(self, app):
        super().__init__(app, "mods")

    def scanMods(self, mods_dir):
        return self._call("scanMods", mods_dir)

    def resolveModsDir(self, version_or_instance_id=None):
        return self._call("resolveModsDir", version_or_instance_id)

    def enableMod(self, jar_path):
        return self._call("enableMod", jar_path)

    def disableMod(self, jar_path):
        return self._call("disableMod", jar_path)

    def deleteMod(self, jar_path):
        return self._call("deleteMod", jar_path)


class ModMarket(_Api):
    def __init__(self, app):
        super().__init__(app, "modMarket")

    def search(self, query, game_version=None, loader=None, limit=20):
        return self._call("search", query, game_version, loader, limit)

    def listFiles(self, source, project_id):
        return self._call("listFiles", source, project_id)

    def install(self, source, project_id, file_id, game_version, version_or_instance_id=None):
        return self._call("install", source, project_id, file_id, game_version, version_or_instance_id)


class Modpacks(_Api):
    def __init__(self, app):
        super().__init__(app, "modpacks")

    def listInstalled(self):
        return self._call("listInstalled")

    def importModpack(self, file):
        return self._call("importModpack", file)


class GameContent(_Api):
    def __init__(self, app):
        super().__init__(app, "gameContent")

    def listWorlds(self):
        return self._call("listWorlds")

    def backupWorld(self, world_name):
        return self._call("backupWorld", world_name)

    def importWorld(self, zip_file):
        return self._call("importWorld", zip_file)

    def deleteWorld(self, world_name):
        return self._call("deleteWorld", world_name)

    def listResourcePacks(self):
        return self._call("listResourcePacks")

    def enableResourcePack(self, file_name):
        return self._call("enableResourcePack", file_name)

    def disableResourcePack(self, file_name):
        return self._call("disableResourcePack", file_name)

    def listShaderPacks(self):
        return self._call("listShaderPacks")

    def enableShaderPack(self, file_name):
        return self._call("enableShaderPack", file_name)

    def disableShaderPack(self, file_name):
        return self._call("disableShaderPack", file_name)

    def listDatapacks(self, world_dir):
        return self._call("listDatapacks", world_dir)

    def enableDatapack(self, world_dir, file_name):
        return self._call("enableDatapack", world_dir, file_name)

    def disableDatapack(self, world_dir, file_name):
        return self._call("disableDatapack", world_dir, file_name)

    def listScreenshots(self):
        return self._call("listScreenshots")

    def deleteScreenshot(self, file_name):
        return self._call("deleteScreenshot", file_name)


class DownloadQueue(_Api):
    def __init__(self, app):
        super().__init__(app, "downloadQueue")

    def summary(self):
        return self._call("summary")

    def listTasks(self):
        return self._call("listTasks")

    def enqueueVersionInstall(self, version_id):
        return self._call("enqueueVersionInstall", version_id)

    def pause(self, task_id):
        return self._call("pause", task_id)

    def resume(self, task_id):
        return self._call("resume", task_id)

    def cancel(self, task_id):
        return self._call("cancel", task_id)

    def clearFinished(self):
        return self._call("clearFinished")


class LoaderVersions(_Api):
    def __init__(self, app):
        super().__init__(app, "loaderVersions")

    def listVersions(self, loader, game_version):
        return self._call("listVersions", loader, game_version)


class JavaRuntimes(_Api):
    def __init__(self, app):
        super().__init__(app, "javaRuntimes")

    def listInstalled(self):
        return self._call("listInstalled")

    def listAvailable(self, runtime_type):
        return self._call("listAvailable", runtime_type)

    def install(self, runtime_type, version):
        return self._call("install", runtime_type, version)

    def hostMetrics(self):
        return self._call("hostMetrics")


class Servers(_Api):
    def __init__(self, app):
        super().__init__(app, "servers")

    def listFavorites(self):
        return self._call("listFavorites")

    def addFavorite(self, name, host, port=25565):
        return self._call("addFavorite", name, host, port)

    def removeFavorite(self, index):
        return self._call("removeFavorite", index)

    def updateFavorite(self, index, name, host, port):
        return self._call("updateFavorite", index, name, host, port)

    def ping(self, host, port=25565):
        return self._call("ping", host, port)

    def getDirectConnectHost(self):
        return self._call("getDirectConnectHost")

    def getDirectConnectPort(self):
        return self._call("getDirectConnectPort")

    def setDirectConnect(self, host, port):
        return self._call("setDirectConnect", host, port)


class Stats(_Api):
    def __init__(self, app):
        super().__init__(app, "stats")

    def overall(self, recent_days=30):
        return self._call("overall", recent_days)

    def sessions(self, offset=0, limit=50):
        return self._call("sessions", offset, limit)


class News(_Api):
    def __init__(self, app):
        super().__init__(app, "news")

    def fetchNews(self, limit=20):
        return self._call("fetchNews", limit)


class CrashLogs(_Api):
    def __init__(self, app):
        super().__init__(app, "crashLogs")

    def listReports(self):
        return self._call("listReports")

    def analyzeText(self, content):
        return self._call("analyzeText", content)


class Music(_Api):
    def __init__(self, app):
        super().__init__(app, "music")

    def nowPlaying(self):
        return self._call("nowPlaying")

    def pause(self):
        return self._call("pause")

    def resume(self):
        return self._call("resume")

    def stop(self):
        return self._call("stop")

    def playNext(self):
        return self._call("playNext")

    def playPrevious(self):
        return self._call("playPrevious")

    def setVolume(self, volume):
        return self._call("setVolume", volume)


class Settings(_Api):
    def __init__(self, app):
        super().__init__(app, "settings")

    def getLanguage(self):
        return self._call("getLanguage")

    def setLanguage(self, language):
        return self._call("setLanguage", language)

    def getThemePreset(self):
        return self._call("getThemePreset")

    def setThemePreset(self, preset):
        return self._call("setThemePreset", preset)

    def getMaxMemoryMb(self):
        return self._call("getMaxMemoryMb")

    def setMaxMemoryMb(self, mb):
        return self._call("setMaxMemoryMb", mb)

    def getMinMemoryMb(self):
        return self._call("getMinMemoryMb")

    def setMinMemoryMb(self, mb):
        return self._call("setMinMemoryMb", mb)

    def isVersionIsolation(self):
        return self._call("isVersionIsolation")

    def setVersionIsolation(self, enabled):
        return self._call("setVersionIsolation", enabled)

    def isDarkTheme(self):
        return self._call("isDarkTheme")

    def setDarkTheme(self, enabled):
        return self._call("setDarkTheme", enabled)

    def getJavaPath(self):
        return self._call("getJavaPath")

    def setJavaPath(self, path):
        return self._call("setJavaPath", path)

    def getCustomJvmArgs(self):
        return self._call("getCustomJvmArgs")

    def setCustomJvmArgs(self, args):
        return self._call("setCustomJvmArgs", args)

    def getMirrorType(self):
        return self._call("getMirrorType")

    def setMirrorType(self, mirror_type):
        return self._call("setMirrorType", mirror_type)

    def getCustomMirrorBase(self):
        return self._call("getCustomMirrorBase")

    def setCustomMirrorBase(self, base):
        return self._call("setCustomMirrorBase", base)

    def getGameWindowWidth(self):
        return self._call("getGameWindowWidth")

    def getGameWindowHeight(self):
        return self._call("getGameWindowHeight")

    def setGameWindowSize(self, width, height):
        return self._call("setGameWindowSize", width, height)

    def isGameFullscreen(self):
        return self._call("isGameFullscreen")

    def setGameFullscreen(self, enabled):
        return self._call("setGameFullscreen", enabled)

    def getDownloadThreads(self):
        return self._call("getDownloadThreads")

    def setDownloadThreads(self, threads):
        return self._call("setDownloadThreads", threads)

    def getPluginConfig(self, key):
        return self._call("getPluginConfig", key)

    def setPluginConfig(self, key, value):
        return self._call("setPluginConfig", key, value)


class Ui(_Api):
    def __init__(self, app):
        super().__init__(app, "ui")

    def notify(self, title, message, level="INFO"):
        return self._call("notify", title, message, level)

    def navigate(self, target):
        return self._call("navigate", target)

    def copyToClipboard(self, text):
        return self._call("copyToClipboard", text)

    def openUrl(self, url):
        return self._call("openUrl", url)

    def showProgress(self, progress_id, title, progress):
        return self._call("showProgress", progress_id, title, progress)

    def dismissProgress(self, progress_id):
        return self._call("dismissProgress", progress_id)

    def setNavBadge(self, target, text):
        return self._call("setNavBadge", target, text)

    def clearNavBadge(self, target):
        return self._call("clearNavBadge", target)

    def hideBuiltinNav(self, route):
        return self._call("hideBuiltinNav", route)

    def showBuiltinNav(self, route):
        return self._call("showBuiltinNav", route)

    def confirm(self, title, message, confirm="OK", cancel="Cancel", timeout=120):
        """弹确认框。用户确认返回 True。取消或超时返回 False。"""
        box = {}
        done = __import__("threading").Event()

        def on_result(args):
            box["value"] = bool(args and args[0])
            done.set()

        self._call("showDialog", title, message, "CONFIRM", confirm, cancel, self._app.callback(on_result))
        done.wait(timeout)
        return bool(box.get("value"))

    def pickFolder(self, title, timeout=120):
        return self._path("pickFolder", (title,), timeout)

    def pickFile(self, title, filters="", save=False, timeout=120):
        return self._path("pickFile", (title, filters, save), timeout)

    def _path(self, method, prefix, timeout):
        box = {}
        done = __import__("threading").Event()

        def on_result(args):
            box["value"] = args[0] if args else None
            done.set()

        self._call(method, *prefix, self._app.callback(on_result))
        done.wait(timeout)
        return box.get("value")


class Filesystem(_Api):
    def __init__(self, app):
        super().__init__(app, "filesystem")

    def dataDir(self):
        return self._call("dataDir")

    def workDir(self):
        return self._call("workDir")

    def exists(self, path):
        return self._call("exists", path)

    def isDirectory(self, path):
        return self._call("isDirectory", path)

    def list(self, path):
        return self._call("list", path)

    def readText(self, path):
        return self._call("readText", path)

    def writeText(self, path, content):
        return self._call("writeText", path, content)

    def createDirectories(self, path):
        return self._call("createDirectories", path)

    def delete(self, path):
        return self._call("delete", path)

    def copy(self, source, target):
        return self._call("copy", source, target)

    def move(self, source, target):
        return self._call("move", source, target)

    def resolveData(self, relative):
        return self._call("resolveData", relative)


class Nbt(_Api):
    def __init__(self, app):
        super().__init__(app, "nbt")

    def readSnbt(self, path):
        return self._call("readSnbt", path)

    def getValue(self, path, key_path):
        return self._call("getValue", path, key_path)

    def setString(self, path, key_path, value):
        return self._call("setString", path, key_path, value)

    def setInt(self, path, key_path, value):
        return self._call("setInt", path, key_path, value)

    def isNbtFile(self, path):
        return self._call("isNbtFile", path)


class Plugins(_Api):
    def __init__(self, app):
        super().__init__(app, "plugins")

    def listPlugins(self):
        return self._call("listPlugins")

    def getPlugin(self, plugin_id):
        return self._call("getPlugin", plugin_id)

    def enablePlugin(self, plugin_id):
        return self._call("enablePlugin", plugin_id)

    def disablePlugin(self, plugin_id):
        return self._call("disablePlugin", plugin_id)

    def unloadPlugin(self, plugin_id):
        return self._call("unloadPlugin", plugin_id)

    def isEnabled(self, plugin_id):
        return self._call("isEnabled", plugin_id)


class Rooms(_Api):
    def __init__(self, app):
        super().__init__(app, "rooms")

    def state(self):
        return self._call("state")

    def createRoom(self):
        return self._call("createRoom")

    def joinRoom(self, invitation_code):
        return self._call("joinRoom", invitation_code)

    def leaveRoom(self):
        return self._call("leaveRoom")

    def invitation(self):
        return self._call("invitation")

    def virtualIp(self):
        return self._call("virtualIp")


class Downloads(_Api):
    def __init__(self, app):
        super().__init__(app, "downloads")

    def downloadTo(self, url, target):
        return self._call("downloadTo", url, target)

    def downloadString(self, url):
        return self._call("downloadString", url)


class Http(_Api):
    def __init__(self, app):
        super().__init__(app, "http")

    def request(self, method, url, headers=None, body=None, timeout_ms=30000):
        return self._call("request", method, url, headers or {}, body, timeout_ms)


class I18n(_Api):
    def __init__(self, app):
        super().__init__(app, "i18n")

    def currentLanguage(self):
        return self._call("currentLanguage")

    def t(self, key, *args):
        return self._call("t", key, *args)

    def registerStrings(self, language, strings):
        return self._call("registerStrings", language, strings)

    def clearStrings(self, language=""):
        return self._call("clearStrings", language)


class Scheduler(_Api):
    def __init__(self, app):
        super().__init__(app, "scheduler")

    def cancel(self, task_id):
        return self._call("cancel", task_id)

    def cancelAll(self):
        return self._call("cancelAll")


class Pins(_Api):
    def __init__(self, app):
        super().__init__(app, "pins")

    def pinned(self):
        return self._call("pinned")

    def recent(self):
        return self._call("recent")

    def pin(self, version_id):
        return self._call("pin", version_id)

    def unpin(self, version_id):
        return self._call("unpin", version_id)


class Integrity(_Api):
    def __init__(self, app):
        super().__init__(app, "integrity")

    def check(self, version_id):
        return self._call("check", version_id)


class Backups(_Api):
    def __init__(self, app):
        super().__init__(app, "backups")

    def listSnapshots(self):
        return self._call("listSnapshots")

    def settings(self):
        return self._call("settings")

    def storedBytes(self):
        return self._call("storedBytes")

    def changes(self, snapshot_id):
        return self._call("changes", snapshot_id)

    def setNote(self, snapshot_id, note):
        return self._call("setNote", snapshot_id, note)

    def setPinned(self, snapshot_id, pinned):
        return self._call("setPinned", snapshot_id, pinned)

    def deleteSnapshot(self, snapshot_id):
        return self._call("deleteSnapshot", snapshot_id)

    def restore(self, snapshot_id):
        """整份恢复，不删除快照之后新增的文件。"""
        return self._call("restore", snapshot_id)


class Migration(_Api):
    def __init__(self, app):
        super().__init__(app, "migration")

    def detect(self):
        return self._call("detect")


class Kernels(_Api):
    def __init__(self, app):
        super().__init__(app, "kernels")

    def list(self):
        return self._call("list")

    def setEnabled(self, kernel_id, enabled):
        return self._call("setEnabled", kernel_id, enabled)

    def remove(self, kernel_id):
        return self._call("remove", kernel_id)


class Skins(_Api):
    def __init__(self, app):
        super().__init__(app, "skins")

    def list(self):
        return self._call("list")

    def importPng(self, path, name):
        """png 必须在启动器目录里，不能是符号链接。"""
        return self._call("importPng", path, name)

    def delete(self, skin_id):
        return self._call("delete", skin_id)


class Recordings(_Api):
    def __init__(self, app):
        super().__init__(app, "recordings")

    def list(self):
        return self._call("list")

    def delete(self, name):
        return self._call("delete", name)


class Play(_Api):
    def __init__(self, app):
        super().__init__(app, "play")

    def weekdays(self, recent_days=30):
        return self._call("weekdays", recent_days)

    def records(self):
        return self._call("records")

    def versions(self, recent_days=30):
        return self._call("versions", recent_days)

    def worlds(self):
        return self._call("worlds")

    def servers(self):
        return self._call("servers")

    def instances(self):
        return self._call("instances")

    def mods(self, top=10):
        return self._call("mods", top)


class Loaders(_Api):
    def __init__(self, app):
        super().__init__(app, "loaders")

    def names(self):
        return self._call("names")

    def install(self, loader, game_version, loader_version):
        """开始安装后立即返回，不等到下载结束。"""
        return self._call("install", loader, game_version, loader_version)


class Wiki(_Api):
    def __init__(self, app):
        super().__init__(app, "wiki")

    def search(self, query):
        return self._call("search", query)

    def minecraft(self, query):
        return self._call("minecraft", query)

    def modrinth(self, slug):
        return self._call("modrinth", slug)

    def curseForge(self, project_id):
        return self._call("curseForge", project_id)


class Presets(_Api):
    def __init__(self, app):
        super().__init__(app, "presets")

    def list(self):
        return self._call("list")

    def apply(self, name):
        return self._call("apply", name)

    def delete(self, name):
        return self._call("delete", name)


class VersionJava(_Api):
    def __init__(self, app):
        super().__init__(app, "versionJava")

    def get(self, version_id):
        return self._call("get", version_id)

    def set(self, version_id, java_path):
        """空路径表示清除。只接受启动器 java 或 runtimes 目录里的可执行文件。"""
        return self._call("set", version_id, java_path)

    def customVersions(self):
        return self._call("customVersions")


class Pmcl:
    """一个已经连上的启动器。属性名对应插件上下文里的接口。"""

    def __init__(self, connection):
        self._app = connection
        self.versions = Versions(connection)
        self.instances = Instances(connection)
        self.accounts = Accounts(connection)
        self.launch = Launch(connection)
        self.gameProcess = GameProcess(connection)
        self.mods = Mods(connection)
        self.modMarket = ModMarket(connection)
        self.modpacks = Modpacks(connection)
        self.gameContent = GameContent(connection)
        self.downloadQueue = DownloadQueue(connection)
        self.loaderVersions = LoaderVersions(connection)
        self.javaRuntimes = JavaRuntimes(connection)
        self.servers = Servers(connection)
        self.stats = Stats(connection)
        self.news = News(connection)
        self.crashLogs = CrashLogs(connection)
        self.music = Music(connection)
        self.settings = Settings(connection)
        self.ui = Ui(connection)
        self.filesystem = Filesystem(connection)
        self.nbt = Nbt(connection)
        self.plugins = Plugins(connection)
        self.rooms = Rooms(connection)
        self.downloads = Downloads(connection)
        self.http = Http(connection)
        self.i18n = I18n(connection)
        self.scheduler = Scheduler(connection)
        self.pins = Pins(connection)
        self.integrity = Integrity(connection)
        self.backups = Backups(connection)
        self.migration = Migration(connection)
        self.kernels = Kernels(connection)
        self.skins = Skins(connection)
        self.recordings = Recordings(connection)
        self.play = Play(connection)
        self.loaders = Loaders(connection)
        self.wiki = Wiki(connection)
        self.presets = Presets(connection)
        self.versionJava = VersionJava(connection)

    @property
    def info(self):
        return self._app.info

    def methods(self):
        return self._app.methods()

    def log(self, message, level="info"):
        self._app.log(message, level)

    def call(self, method, args=()):
        """调用 catalog 里没有单独封装的方法。method 形如 versions.listLocalVersions。"""
        return self._app.call(method, args)

    def close(self):
        self._app.close()


def connect(endpoint=None, token=None, timeout=10):
    return Pmcl(open_connection(endpoint, token, timeout))
