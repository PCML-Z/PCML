package com.lash.pmcl.core.paths

import java.nio.file.Path

/**
 * 路径抽象层：替代桌面版 PMCL 中硬编码的 `System.getProperty("user.home") + ".pmcl"`。
 *
 * 桌面实现使用 `~/.pmcl/`，Android 实现使用 `Context.getFilesDir()/pmcl/`。
 * core 模块所有需要文件系统路径的代码都应通过此接口获取，不直接调用 System.getProperty。
 */
interface PmclPaths {
    /** PMCL 根目录（桌面: ~/.pmcl，Android: app/files/pmcl） */
    val root: Path

    /** 下载缓存目录 */
    val cache: Path

    /** 更新包目录 */
    val updates: Path

    /** 好友数据目录 */
    val friendData: Path

    /** 音乐缓存目录 */
    val musicCache: Path

    /** 插件目录 */
    val plugins: Path

    /** 偏好文件 */
    val preferences: Path

    /** 账号文件 */
    val accounts: Path

    /** Azure client ID 文件 */
    val azureClientId: Path

    /** GitHub client ID 文件 */
    val githubClientId: Path

    /** Mod 标签文件 */
    val modTags: Path

    /** 游戏时间文件 */
    val playtime: Path

    /** Minecraft 工作目录（versions/libraries/assets/runtimes 的父目录） */
    val minecraftWorkDir: Path

    /** versions 目录 */
    val versions: Path

    /** libraries 目录 */
    val libraries: Path

    /** assets 目录 */
    val assets: Path

    /** runtimes 目录（Java 运行时） */
    val runtimes: Path

    /** 实例目录 */
    val instances: Path

    companion object {
        /**
         * 桌面实现：所有路径基于 `~/.pmcl/`。
         * 保留用于桌面端兼容测试，Android 端不应使用。
         */
        fun desktop(): PmclPaths = DesktopPaths()

        /**
         * 从指定根目录创建路径提供者。
         * Android 端传入 `Context.getFilesDir().toPath().resolve("pmcl")`。
         */
        fun fromRoot(rootPath: Path): PmclPaths = RootPaths(rootPath)
    }
}

/** 桌面实现：基于 ~/.pmcl */
private class DesktopPaths : PmclPaths {
    private val home = Path.of(System.getProperty("user.home"))
    override val root = home.resolve(".pmcl")
    override val cache = root.resolve("cache")
    override val updates = root.resolve("updates")
    override val friendData = root.resolve("friend-data")
    override val musicCache = root.resolve("music").resolve("cache")
    override val plugins = root.resolve("plugins")
    override val preferences = root.resolve("preferences.json")
    override val accounts = root.resolve("accounts.json")
    override val azureClientId = root.resolve("azure_client_id.txt")
    override val githubClientId = root.resolve("github_client_id.txt")
    override val modTags = root.resolve("mod_tags.json")
    override val playtime = root.resolve("playtime.json")
    override val minecraftWorkDir = root
    override val versions = root.resolve("versions")
    override val libraries = root.resolve("libraries")
    override val assets = root.resolve("assets")
    override val runtimes = root.resolve("runtimes")
    override val instances = root.resolve("instances")
}

/** 通用实现：从指定根目录派生所有路径 */
private class RootPaths(rootPath: Path) : PmclPaths {
    override val root = rootPath
    override val cache = root.resolve("cache")
    override val updates = root.resolve("updates")
    override val friendData = root.resolve("friend-data")
    override val musicCache = root.resolve("music").resolve("cache")
    override val plugins = root.resolve("plugins")
    override val preferences = root.resolve("preferences.json")
    override val accounts = root.resolve("accounts.json")
    override val azureClientId = root.resolve("azure_client_id.txt")
    override val githubClientId = root.resolve("github_client_id.txt")
    override val modTags = root.resolve("mod_tags.json")
    override val playtime = root.resolve("playtime.json")
    override val minecraftWorkDir = rootPath
    override val versions = rootPath.resolve("versions")
    override val libraries = rootPath.resolve("libraries")
    override val assets = rootPath.resolve("assets")
    override val runtimes = rootPath.resolve("runtimes")
    override val instances = rootPath.resolve("instances")
}
