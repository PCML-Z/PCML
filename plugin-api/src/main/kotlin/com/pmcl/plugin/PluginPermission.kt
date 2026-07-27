package com.pmcl.plugin

/**
 * 插件权限声明。
 *
 * 敏感服务需要在 plugin.xml / properties 的 `plugin.permissions` 中显式声明。
 * 未知权限名在加载时会被拒绝。
 */
enum class PluginPermission {

    /** 读取账号身份信息（不含 accessToken）。[com.pmcl.plugin.api.AccountsApi] */
    READ_ACCOUNTS,

    /** 修改账号（添加/删除/切换）。[com.pmcl.plugin.api.AccountsApi] 写操作 */
    WRITE_ACCOUNTS,

    /** 请求启动 / 控制游戏进程 / 注册 LaunchHook。[com.pmcl.plugin.api.LaunchApi] */
    CONTROL_LAUNCH,

    /** 杀死游戏进程。 */
    KILL_PROCESS,

    /** 替换启动器 JAR。 */
    SELF_UPDATE,

    /** 管理其他插件（启用/禁用/卸载）。[com.pmcl.plugin.api.PluginsApi] */
    MANAGE_PLUGINS,

    /** 创建/重命名/删除实例。[com.pmcl.plugin.api.InstancesApi] 写操作 */
    MANAGE_INSTANCES,

    /** 扫描模组元数据。[com.pmcl.plugin.api.ModsApi] */
    READ_MODS,

    /** 启用/禁用/删除本地模组，以及市场安装。[com.pmcl.plugin.api.ModsApi] / [com.pmcl.plugin.api.ModMarketApi] */
    MANAGE_MODS,

    /** 导入整合包。[com.pmcl.plugin.api.ModpackApi] 写操作 */
    MANAGE_MODPACKS,

    /** 管理世界 / 资源包 / 光影 / 数据包 / 截图。[com.pmcl.plugin.api.GameContentApi] */
    MANAGE_GAME_CONTENT,

    /** 读取游玩统计。[com.pmcl.plugin.api.StatsApi] */
    READ_STATS,

    /** 联机房间创建/加入/离开。[com.pmcl.plugin.api.RoomsApi] */
    CONTROL_ROOMS,

    /** 安装 / 管理版本与下载队列。[com.pmcl.plugin.api.VersionsApi] / [com.pmcl.plugin.api.DownloadQueueApi] */
    MANAGE_VERSIONS,

    /** 管理收藏服务器 / 直连地址。[com.pmcl.plugin.api.ServersApi] */
    MANAGE_SERVERS,

    /** 读取崩溃报告与分析结果。[com.pmcl.plugin.api.CrashLogsApi] */
    READ_CRASH_LOGS,

    /** 控制宿主音乐播放器。[com.pmcl.plugin.api.MusicApi] */
    CONTROL_MUSIC,

    /** 写入宿主偏好（语言/主题等）。[com.pmcl.plugin.api.SettingsApi] 写操作 */
    WRITE_SETTINGS,

    /** 网络下载 / ping / HTTP / URL 重写。[com.pmcl.plugin.api.DownloadsApi] / [com.pmcl.plugin.api.HttpApi] */
    NETWORK,

    /** 读写本地文件系统（含 [com.pmcl.plugin.api.NbtApi] / [com.pmcl.plugin.api.FilesystemApi]）。 */
    FILESYSTEM;

    companion object {
        @JvmStatic
        fun names(): Set<String> = entries.map { it.name }.toSet()

        @JvmStatic
        fun parseOrNull(raw: String): PluginPermission? =
            entries.find { it.name.equals(raw.trim(), ignoreCase = true) }
    }
}
