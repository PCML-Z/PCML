package com.pmcl.plugin

/**
 * 插件权限声明。
 *
 * 敏感服务（AuthService/SelfUpdater/ProcessMonitor 等）需要插件在 plugin.xml/properties
 * 中显式声明对应权限才能通过 `PluginContext.getService` 获取。
 *
 * 当前实现的权限模型：
 * - **声明式**：插件在 `plugin.permissions` 字段声明所需权限（逗号分隔）
 * - **审计日志**：所有 getService 调用都记录到 stderr，便于事后追溯
 * - **非交互式授权**：当前不弹窗询问用户，仅依赖声明（未来可扩展为安装时提示）
 *
 * 安全说明：这是基础防护层，防止插件"无意中"访问敏感服务。恶意插件仍可通过反射
 * 突破（需配合 SecurityManager 或 JPMS 模块系统才能彻底隔离）。
 */
enum class PluginPermission {

    /**
     * 读取账号信息（含 accessToken）。AuthService 需要。
     *
     * 风险：泄漏后可冒充账号访问 Minecraft 服务。
     */
    READ_ACCOUNTS,

    /**
     * 修改账号（添加/删除/切换）。AuthService 需要。
     */
    WRITE_ACCOUNTS,

    /**
     * 启动/停止 Minecraft 进程。LaunchManager 需要。
     *
     * 风险：可启动任意 Java 进程（通过构造 LaunchProfile）。
     */
    CONTROL_LAUNCH,

    /**
     * 杀死进程。ProcessMonitor 需要。
     *
     * 风险：可杀死其他进程。
     */
    KILL_PROCESS,

    /**
     * 替换启动器 JAR。SelfUpdater 需要。
     *
     * 风险：可植入恶意代码实现持久化。
     */
    SELF_UPDATE,

    /**
     * 读写插件配置。PluginManager 自身需要。
     */
    MANAGE_PLUGINS,

    /**
     * 访问网络（已内置，无需声明）。
     */
    NETWORK,

    /**
     * 读写本地文件系统（已内置，无需声明）。
     */
    FILESYSTEM
}
