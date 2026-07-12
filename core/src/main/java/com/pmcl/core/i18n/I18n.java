package com.pmcl.core.i18n;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 启动器国际化。
 * <p>
 * 当前支持：zh_CN（默认）、en_US、ja_JP。
 * 翻译以代码内 Map 形式存储，避免外部资源加载复杂度。
 * <p>
 * 用法：{@code I18n.setLocale(Locale.JAPANESE); I18n.t("launch.start")}
 */
public final class I18n {

    public static final Locale ZH_CN = Locale.SIMPLIFIED_CHINESE;
    public static final Locale EN_US = Locale.US;
    public static final Locale JA_JP = Locale.JAPANESE;

    private static volatile Locale current = ZH_CN;

    private static final Map<String, String> ZH = new LinkedHashMap<>();
    private static final Map<String, String> EN = new LinkedHashMap<>();
    private static final Map<String, String> JA = new LinkedHashMap<>();

    static {
        // ===== 通用 =====
        ZH.put("app.title", "PMCL 启动器");
        ZH.put("common.refresh", "刷新");
        ZH.put("common.delete", "删除");
        ZH.put("common.backup", "备份");
        ZH.put("common.cancel", "取消");
        ZH.put("common.confirm", "确认");
        ZH.put("common.ok", "确定");
        ZH.put("common.status", "状态");
        ZH.put("common.loading", "加载中…");
        ZH.put("common.import", "导入");
        ZH.put("common.export", "导出");
        ZH.put("common.remove", "移除");
        ZH.put("common.pause", "暂停");
        ZH.put("common.resume", "继续");
        ZH.put("common.save", "保存");
        ZH.put("common.browse", "浏览");
        ZH.put("common.retry", "重试");
        ZH.put("common.back", "返回");
        ZH.put("common.open", "打开");
        ZH.put("common.copy", "复制");
        ZH.put("common.close", "关闭");
        ZH.put("common.enable", "启用");
        ZH.put("common.disable", "禁用");
        ZH.put("common.install", "安装");
        ZH.put("common.uninstall", "卸载");
        ZH.put("common.reload", "重载");
        ZH.put("common.empty", "暂无数据");
        ZH.put("common.processing", "处理中…");
        ZH.put("common.times", "次");

        // ===== 导航 =====
        ZH.put("nav.launch", "启动");
        ZH.put("nav.news", "新闻");
        ZH.put("nav.multiplayer", "联机");
        ZH.put("nav.download", "下载");
        ZH.put("nav.content", "内容");
        ZH.put("nav.saves", "存档");
        ZH.put("nav.statistics", "统计");
        ZH.put("nav.accounts", "账号");
        ZH.put("nav.settings", "设置");
        ZH.put("nav.terminal", "终端");
        ZH.put("nav.plugins", "插件");
        ZH.put("nav.mods", "模组");
        ZH.put("nav.market", "市场");
        ZH.put("nav.worlds", "世界");
        ZH.put("nav.screenshots", "截图");
        ZH.put("nav.queue", "队列");
        ZH.put("nav.wiki", "Wiki");
        ZH.put("nav.modpacks", "整合包");
        ZH.put("nav.shaders", "光影包");
        ZH.put("nav.resourcepacks", "资源包");
        ZH.put("nav.datapacks", "数据包");

        // ===== 启动页 =====
        ZH.put("launch.title", "启动游戏");
        ZH.put("launch.select_version", "选择版本");
        ZH.put("launch.start", "启动");
        ZH.put("launch.no_version", "请先选择版本");
        ZH.put("launch.no_account", "请先登录账号");
        ZH.put("launch.running", "游戏已在运行中");
        ZH.put("launch.starting", "启动中…");
        ZH.put("launch.failed", "启动失败");
        ZH.put("launch.system_info", "系统信息");
        ZH.put("launch.quick_launch", "快速启动");
        ZH.put("launch.welcome", "欢迎使用 PMCL");
        ZH.put("launch.subtitle", "一个跨平台的 Minecraft 启动器");
        ZH.put("launch.enter", "进入 PMCL");
        ZH.put("launch.start_minecraft", "启动 Minecraft");
        ZH.put("launch.download_install", "下载并安装");
        ZH.put("launch.game_running", "游戏运行中…");
        ZH.put("launch.downloading", "下载中…");
        ZH.put("launch.not_logged_in_short", "未登录账号 · 进入 PMCL 后可登录");
        ZH.put("launch.no_version_selected", "未选择版本");
        ZH.put("launch.installed", "已安装");
        ZH.put("launch.not_installed", "未安装");
        ZH.put("launch.ready", "就绪");
        ZH.put("launch.account_label", "账号：{0}");
        ZH.put("launch.server_connect", "服务器直连");
        ZH.put("launch.server_address", "服务器地址");
        ZH.put("launch.server_port", "端口");
        ZH.put("launch.server_empty_hint", "留空则正常启动");
        ZH.put("launch.server_leave_empty", "留空不连接");
        ZH.put("launch.server_hint", "启动后自动连接指定服务器，对应 --server / --port 参数");
        ZH.put("launch.running_instances", "运行中实例");
        ZH.put("launch.active", "活跃");

        // ===== 下载页 =====
        ZH.put("download.refresh", "刷新版本列表");
        ZH.put("download.install", "安装");
        ZH.put("download.installing", "安装中…");
        ZH.put("download.install_done", "安装完成");
        ZH.put("download.install_failed", "安装失败");
        ZH.put("download.local_versions", "已安装版本");

        // ===== 下载队列 =====
        ZH.put("queue.title", "下载队列总览");
        ZH.put("queue.empty", "队列为空");
        ZH.put("queue.empty_hint", "在「版本」或「市场」Tab 中点击安装即可加入队列");
        ZH.put("queue.total_items", "共 {0} 项");
        ZH.put("queue.active", "活跃 {0}");
        ZH.put("queue.done", "完成 {0}");
        ZH.put("queue.failed", "失败 {0}");
        ZH.put("queue.pause_all", "全部暂停");
        ZH.put("queue.resume_all", "全部继续");
        ZH.put("queue.cancel_all", "全部取消");
        ZH.put("queue.clear_finished", "清除已完成");
        ZH.put("queue.queued", "排队中");
        ZH.put("queue.running", "运行中");
        ZH.put("queue.paused", "已暂停");
        ZH.put("queue.cancelled", "已取消");

        // ===== 模组页 =====
        ZH.put("mods.installed", "已安装模组");
        ZH.put("mods.conflicts", "冲突检测");
        ZH.put("mods.no_mods", "暂无模组。安装的模组会显示在这里。");
        ZH.put("mods.scan_done", "已扫描 {0} 个模组");
        ZH.put("mods.check_update", "检查更新");
        ZH.put("mods.update_all", "一键更新 ({0})");
        ZH.put("mods.has_update", "有更新");
        ZH.put("mods.new_version", "可更新至: {0} · 来源: {1}");
        ZH.put("mods.up_to_date", "已是最新");
        ZH.put("mods.update", "更新");
        ZH.put("mods.checking_updates", "检测更新");
        ZH.put("mods.translate", "翻译");
        ZH.put("mods.translating", "翻译中…");
        ZH.put("mods.with_deps", "含依赖");
        ZH.put("mods.dep_result_title", "依赖安装结果");
        ZH.put("mods.dep_installed", "已安装依赖 ({0})");
        ZH.put("mods.dep_skipped", "已安装跳过 ({0})");
        ZH.put("mods.dep_system", "系统依赖跳过");
        ZH.put("mods.dep_not_found", "未找到 ({0})");
        ZH.put("mods.dep_failed", "安装失败 ({0})");
        ZH.put("mods.dep_no_extra", "无额外依赖需要安装");
        ZH.put("mods.dep_summary_installed", "已安装依赖: {0}");
        ZH.put("mods.dep_summary_not_found", "未找到: {0}");
        ZH.put("mods.dep_summary_failed", "失败: {0}");
        ZH.put("mods.dep_no_extra_short", "无额外依赖");
        ZH.put("mods.dep_installed_count", "已安装依赖: {0} 个");
        ZH.put("mods.dep_mod_label", "模组: {0}");

        // ===== 市场页 =====
        ZH.put("market.search", "搜索");
        ZH.put("market.query_placeholder", "搜索模组…");
        ZH.put("market.game_version", "游戏版本");
        ZH.put("market.loader", "加载器");
        ZH.put("market.any", "任意");
        ZH.put("market.files", "文件列表");
        ZH.put("market.download", "下载");
        ZH.put("market.curseforge_disabled", "CurseForge 未启用（未设置 API Key）");

        // ===== 整合包 =====
        ZH.put("modpack.title", "整合包管理");
        ZH.put("modpack.empty", "暂无整合包");
        ZH.put("modpack.empty_hint", "点击「导入」安装 .mrpack 或 .zip 整合包");
        ZH.put("modpack.mod_count", "{0} 个模组");
        ZH.put("modpack.delete_title", "删除整合包");
        ZH.put("modpack.delete_confirm", "确定要删除整合包「{0}」吗？\n这将删除该实例的所有 mods、saves 和 config。");
        ZH.put("modpack.import_title", "导入整合包");
        ZH.put("modpack.import_hint", "选择 .mrpack (Modrinth) 或 .zip (CurseForge) 整合包文件");
        ZH.put("modpack.file_path", "文件路径");
        ZH.put("modpack.select_file", "选择整合包文件");
        ZH.put("modpack.export_title", "导出整合包");
        ZH.put("modpack.export_hint", "将版本「{0}」导出为 Modrinth .mrpack 格式");
        ZH.put("modpack.save_path", "保存路径");
        ZH.put("modpack.save_file", "保存整合包");
        ZH.put("modpack.export_content", "导出包含：mods/、config/、resourcepacks/、shaderpacks/、options.txt");

        // ===== 世界页 =====
        ZH.put("worlds.title", "世界管理");
        ZH.put("worlds.empty", "暂无世界。开始游戏后会自动在 saves 目录创建。");
        ZH.put("worlds.size", "大小");
        ZH.put("worlds.modified", "最后修改");

        // ===== 截图页 =====
        ZH.put("screenshots.title", "截图");
        ZH.put("screenshots.empty", "暂无截图。游戏内按 F2 截图后会自动保存。");

        // ===== 统计页 =====
        ZH.put("stats.overview", "游玩总览");
        ZH.put("stats.total_duration", "总时长");
        ZH.put("stats.total_sessions", "总会话");
        ZH.put("stats.daily_avg", "日均");
        ZH.put("stats.recent_days", "近 {0} 天");
        ZH.put("stats.daily_trend", "每日游玩时长");
        ZH.put("stats.version_dist", "版本游玩时长分布");

        // ===== 新闻页 =====
        ZH.put("news.title", "Minecraft 新闻");
        ZH.put("news.source", "来自 Minecraft.net 官方 RSS · 点击卡片查看全文");
        ZH.put("news.fetching", "正在拉取最新新闻…");
        ZH.put("news.empty", "暂无新闻");
        ZH.put("news.open_browser", "在浏览器打开");
        ZH.put("news.loading_article", "正在加载文章…");
        ZH.put("news.load_failed", "加载失败");
        ZH.put("news.back_to_list", "返回列表");
        ZH.put("news.source_link", "原文链接：{0}");
        ZH.put("news.view_full", "查看全文 →");

        // ===== Wiki 页 =====
        ZH.put("wiki.title", "在系统浏览器中打开 Mod Wiki / 项目主页。");
        ZH.put("wiki.search_placeholder", "搜索关键词（如 mod 名称）");
        ZH.put("wiki.shortcuts", "快捷入口");
        ZH.put("wiki.modrinth", "Modrinth 项目主页");
        ZH.put("wiki.curseforge", "CurseForge 模组搜索");
        ZH.put("wiki.mc_wiki", "Minecraft Wiki 搜索");
        ZH.put("wiki.google", "Google 搜索");
        ZH.put("wiki.forge_docs", "Forge 文档");
        ZH.put("wiki.mojang", "Mojang 官方");
        ZH.put("wiki.unsupported", "当前平台不支持系统浏览器调用");

        // ===== 账号页 =====
        ZH.put("accounts.title", "账号");
        ZH.put("accounts.current", "当前账号");
        ZH.put("accounts.not_logged_in", "未登录");
        ZH.put("accounts.logout", "退出登录");
        ZH.put("accounts.offline", "离线账号");
        ZH.put("accounts.microsoft", "微软账号");
        ZH.put("accounts.username", "用户名");
        ZH.put("accounts.login", "登录");
        ZH.put("accounts.start_ms_login", "开始微软登录");
        ZH.put("accounts.logging_in", "登录中…");
        ZH.put("accounts.device_code_hint", "请打开浏览器访问：");

        // ===== 联机页 =====
        ZH.put("mp.connectx", "ConnectX 联机");
        ZH.put("mp.terracotta", "陶瓦联机 · Terracotta");
        ZH.put("mp.easytier", "陶瓦联机 · EasyTier");
        ZH.put("mp.settings", "联机设置");
        ZH.put("mp.backend", "联机后端");
        ZH.put("mp.terracotta_official", "Terracotta（官方）");
        ZH.put("mp.current_room", "当前房间");
        ZH.put("mp.room_code", "房间码（发送给朋友加入）");
        ZH.put("mp.copy_room_code", "复制房间码");
        ZH.put("mp.local_mc_addr", "本地 MC 连接地址");
        ZH.put("mp.copy_addr", "复制地址");
        ZH.put("mp.room_id", "房间短ID");
        ZH.put("mp.invite_code", "邀请码（发送给朋友加入）");
        ZH.put("mp.copy_invite", "复制邀请码");
        ZH.put("mp.network_name", "网络名称");
        ZH.put("mp.virtual_ip", "你的虚拟 IP");
        ZH.put("mp.copy_ip", "复制 IP");
        ZH.put("mp.ip_acquiring", "虚拟 IP 获取中…请稍候");
        ZH.put("mp.not_joined", "尚未加入任何房间。创建房间或粘贴朋友发来的房间码加入。");
        ZH.put("mp.host", "房主");
        ZH.put("mp.create_room", "创建房间");
        ZH.put("mp.guest", "房客");
        ZH.put("mp.room_code_label", "房间码 / 邀请码");
        ZH.put("mp.join_room", "加入房间");
        ZH.put("mp.leave_room", "离开房间");
        ZH.put("mp.usage", "使用说明");
        ZH.put("mp.host_label", "房主：");
        ZH.put("mp.guest_label", "房客：");
        ZH.put("mp.error_detail", "错误详情");
        ZH.put("mp.connectx_settings", "ConnectX 设置");
        ZH.put("mp.connectx_binary", "ConnectX.ClientConsole 二进制路径");
        ZH.put("mp.connectx_server", "ConnectX 服务器地址");
        ZH.put("mp.connectx_port", "ConnectX 服务器端口");
        ZH.put("mp.state.idle", "未加入房间");
        ZH.put("mp.state.connecting_server", "正在连接服务器…");
        ZH.put("mp.state.downloading_terracotta", "正在下载 Terracotta…");
        ZH.put("mp.state.downloading_easytier", "正在下载 EasyTier…");
        ZH.put("mp.state.connecting", "正在连接…");
        ZH.put("mp.state.connected", "已连接");
        ZH.put("mp.state.disconnected", "已断开");
        ZH.put("mp.state.failed", "连接失败");

        // ===== 设置页 =====
        ZH.put("settings.title", "设置");
        ZH.put("settings.memory", "内存");
        ZH.put("settings.min_memory", "最小 (MB)");
        ZH.put("settings.max_memory", "最大 (MB)");
        ZH.put("settings.jvm_advanced", "JVM 高级配置");
        ZH.put("settings.gc_type", "GC 类型");
        ZH.put("settings.aikar", "Aikar's Flags");
        ZH.put("settings.aikar_desc", "社区公认的 MC 优化参数集（推荐开启）");
        ZH.put("settings.custom_args", "自定义 JVM 参数（空格分隔）");
        ZH.put("settings.appearance", "外观");
        ZH.put("settings.dark_theme", "深色主题");
        ZH.put("settings.light_theme", "浅色主题");
        ZH.put("settings.network", "网络配置");
        ZH.put("settings.window_icon", "游戏窗口图标");
        ZH.put("settings.window_icon_path", "图标 PNG 路径");
        ZH.put("settings.window_icon_empty", "留空使用默认图标");
        ZH.put("settings.window_icon_select", "选择 PNG 图标文件");
        ZH.put("settings.window_icon_hint", "启动时自动缩放到 16x16 / 32x32 并注入到游戏目录 icons/");
        ZH.put("settings.mirror", "下载镜像源");
        ZH.put("settings.mirror_official", "官方");
        ZH.put("settings.mirror_bmclapi", "BMCLAPI");
        ZH.put("settings.mirror_custom", "自定义");
        ZH.put("settings.custom_mirror", "自定义镜像基址");
        ZH.put("settings.http_proxy", "HTTP 代理");
        ZH.put("settings.proxy_host", "主机");
        ZH.put("settings.proxy_port", "端口");
        ZH.put("settings.proxy_auth", "代理认证");
        ZH.put("settings.speed_limit", "限速 (KB/s, 0=不限)");
        ZH.put("settings.retry_count", "重试次数");
        ZH.put("settings.chunked_threads", "分片下载连接数");
        ZH.put("settings.enable_resume", "断点续传（.part 文件）");
        ZH.put("settings.system_info", "系统信息");
        ZH.put("settings.work_dir", "工作目录");
        ZH.put("settings.language", "语言");
        ZH.put("settings.version_isolation", "版本隔离");
        ZH.put("settings.version_isolation_desc", "各版本独立 mods/saves/config 目录");
        ZH.put("settings.game_behavior", "游戏行为");

        // ===== 完整性校验 =====
        ZH.put("integrity.check", "校验完整性");
        ZH.put("integrity.checking", "正在校验…");
        ZH.put("integrity.ok", "完整性校验通过");
        ZH.put("integrity.issues", "发现 {0} 个问题");
        ZH.put("integrity.missing", "缺失 {0}");
        ZH.put("integrity.mismatch", "哈希不匹配 {0}");

        // ===== 崩溃分析 =====
        ZH.put("crash.title", "崩溃报告");
        ZH.put("crash.causes", "可能原因");
        ZH.put("crash.suggestions", "建议");
        ZH.put("crash.empty", "暂无崩溃报告");

        // ===== 迁移 =====
        ZH.put("migration.title", "迁移游戏数据");
        ZH.put("migration.from", "从 {0} 迁移");
        ZH.put("migration.size", "大小：{0}");
        ZH.put("migration.done", "已完成迁移");
        ZH.put("migration.processing", "迁移中…");
        ZH.put("migration.no_source", "未检测到 HMCL 或 Launcher X");

        // ===== 插件页 =====
        ZH.put("plugin.title", "插件管理");
        ZH.put("plugin.install", "安装插件");
        ZH.put("plugin.scan", "扫描插件");
        ZH.put("plugin.empty", "暂无插件");
        ZH.put("plugin.enabled", "已启用");
        ZH.put("plugin.disabled", "已禁用");

        // ===== 终端页 =====
        ZH.put("terminal.title", "终端");

        // ===== 日志导出/分享 =====
        ZH.put("log.export", "导出");
        ZH.put("log.export_title", "导出日志");
        ZH.put("log.export_hint", "选择保存位置，日志将导出为 .txt 文件");
        ZH.put("log.export_save", "保存日志文件");
        ZH.put("log.choose_file", "选择保存位置");
        ZH.put("log.export_failed", "导出失败");
        ZH.put("log.share", "分享");
        ZH.put("log.share_success", "分享成功");
        ZH.put("log.share_url_hint", "日志已上传到 paste.gg，复制下方链接分享给他人：");

        // ========================================
        // EN 翻译
        // ========================================
        EN.put("app.title", "PMCL Launcher");
        EN.put("common.refresh", "Refresh");
        EN.put("common.delete", "Delete");
        EN.put("common.backup", "Backup");
        EN.put("common.cancel", "Cancel");
        EN.put("common.confirm", "Confirm");
        EN.put("common.ok", "OK");
        EN.put("common.status", "Status");
        EN.put("common.loading", "Loading…");
        EN.put("common.import", "Import");
        EN.put("common.export", "Export");
        EN.put("common.remove", "Remove");
        EN.put("common.pause", "Pause");
        EN.put("common.resume", "Resume");
        EN.put("common.save", "Save");
        EN.put("common.browse", "Browse");
        EN.put("common.retry", "Retry");
        EN.put("common.back", "Back");
        EN.put("common.open", "Open");
        EN.put("common.copy", "Copy");
        EN.put("common.close", "Close");
        EN.put("common.enable", "Enable");
        EN.put("common.disable", "Disable");
        EN.put("common.install", "Install");
        EN.put("common.uninstall", "Uninstall");
        EN.put("common.reload", "Reload");
        EN.put("common.empty", "No data");
        EN.put("common.processing", "Processing…");
        EN.put("common.times", "times");

        EN.put("nav.launch", "Launch");
        EN.put("nav.news", "News");
        EN.put("nav.multiplayer", "Multiplayer");
        EN.put("nav.download", "Download");
        EN.put("nav.content", "Content");
        EN.put("nav.saves", "Saves");
        EN.put("nav.statistics", "Stats");
        EN.put("nav.accounts", "Accounts");
        EN.put("nav.settings", "Settings");
        EN.put("nav.terminal", "Terminal");
        EN.put("nav.plugins", "Plugins");
        EN.put("nav.mods", "Mods");
        EN.put("nav.market", "Market");
        EN.put("nav.worlds", "Worlds");
        EN.put("nav.screenshots", "Shots");
        EN.put("nav.queue", "Queue");
        EN.put("nav.wiki", "Wiki");
        EN.put("nav.modpacks", "Modpacks");
        EN.put("nav.shaders", "Shaders");
        EN.put("nav.resourcepacks", "Resource Packs");
        EN.put("nav.datapacks", "Datapacks");

        EN.put("launch.title", "Launch Game");
        EN.put("launch.select_version", "Select version");
        EN.put("launch.start", "Launch");
        EN.put("launch.no_version", "Please select a version first");
        EN.put("launch.no_account", "Please log in first");
        EN.put("launch.running", "Game is already running");
        EN.put("launch.starting", "Starting…");
        EN.put("launch.failed", "Launch failed");
        EN.put("launch.system_info", "System Info");
        EN.put("launch.quick_launch", "Quick Launch");
        EN.put("launch.welcome", "Welcome to PMCL");
        EN.put("launch.subtitle", "A cross-platform Minecraft launcher");
        EN.put("launch.enter", "Enter PMCL");
        EN.put("launch.start_minecraft", "Launch Minecraft");
        EN.put("launch.download_install", "Download & Install");
        EN.put("launch.game_running", "Game running…");
        EN.put("launch.downloading", "Downloading…");
        EN.put("launch.not_logged_in_short", "Not logged in · log in after entering PMCL");
        EN.put("launch.no_version_selected", "No version selected");
        EN.put("launch.installed", "Installed");
        EN.put("launch.not_installed", "Not installed");
        EN.put("launch.ready", "Ready");
        EN.put("launch.account_label", "Account: {0}");
        EN.put("launch.server_connect", "Server Direct Connect");
        EN.put("launch.server_address", "Server address");
        EN.put("launch.server_port", "Port");
        EN.put("launch.server_empty_hint", "Leave empty for normal launch");
        EN.put("launch.server_leave_empty", "Leave empty to skip");
        EN.put("launch.server_hint", "Auto-connect to the specified server on launch (--server / --port)");
        EN.put("launch.running_instances", "Running Instances");
        EN.put("launch.active", "Active");

        EN.put("download.refresh", "Refresh versions");
        EN.put("download.install", "Install");
        EN.put("download.installing", "Installing…");
        EN.put("download.install_done", "Install complete");
        EN.put("download.install_failed", "Install failed");
        EN.put("download.local_versions", "Installed versions");

        EN.put("queue.title", "Download Queue Overview");
        EN.put("queue.empty", "Queue is empty");
        EN.put("queue.empty_hint", "Click install in Version or Market tab to add tasks");
        EN.put("queue.total_items", "{0} items");
        EN.put("queue.active", "Active {0}");
        EN.put("queue.done", "Done {0}");
        EN.put("queue.failed", "Failed {0}");
        EN.put("queue.pause_all", "Pause All");
        EN.put("queue.resume_all", "Resume All");
        EN.put("queue.cancel_all", "Cancel All");
        EN.put("queue.clear_finished", "Clear Finished");
        EN.put("queue.queued", "Queued");
        EN.put("queue.running", "Running");
        EN.put("queue.paused", "Paused");
        EN.put("queue.cancelled", "Cancelled");

        EN.put("mods.installed", "Installed mods");
        EN.put("mods.conflicts", "Conflict check");
        EN.put("mods.no_mods", "No mods installed. Installed mods will appear here.");
        EN.put("mods.scan_done", "Scanned {0} mods");
        EN.put("mods.check_update", "Check updates");
        EN.put("mods.update_all", "Update All ({0})");
        EN.put("mods.has_update", "Update");
        EN.put("mods.new_version", "Update to: {0} · Source: {1}");
        EN.put("mods.up_to_date", "Up to date");
        EN.put("mods.update", "Update");
        EN.put("mods.checking_updates", "Checking");
        EN.put("mods.translate", "Translate");
        EN.put("mods.translating", "Translating…");
        EN.put("mods.with_deps", "With Deps");
        EN.put("mods.dep_result_title", "Dependency Install Result");
        EN.put("mods.dep_installed", "Installed ({0})");
        EN.put("mods.dep_skipped", "Already installed ({0})");
        EN.put("mods.dep_system", "System deps skipped");
        EN.put("mods.dep_not_found", "Not found ({0})");
        EN.put("mods.dep_failed", "Failed ({0})");
        EN.put("mods.dep_no_extra", "No extra dependencies needed");
        EN.put("mods.dep_summary_installed", "Installed deps: {0}");
        EN.put("mods.dep_summary_not_found", "Not found: {0}");
        EN.put("mods.dep_summary_failed", "Failed: {0}");
        EN.put("mods.dep_no_extra_short", "No extra deps");
        EN.put("mods.dep_installed_count", "Installed {0} deps");
        EN.put("mods.dep_mod_label", "Mod: {0}");

        EN.put("market.search", "Search");
        EN.put("market.query_placeholder", "Search mods…");
        EN.put("market.game_version", "Game version");
        EN.put("market.loader", "Loader");
        EN.put("market.any", "Any");
        EN.put("market.files", "Files");
        EN.put("market.download", "Download");
        EN.put("market.curseforge_disabled", "CurseForge disabled (no API Key)");

        EN.put("modpack.title", "Modpack Manager");
        EN.put("modpack.empty", "No modpacks");
        EN.put("modpack.empty_hint", "Click Import to install .mrpack or .zip modpacks");
        EN.put("modpack.mod_count", "{0} mods");
        EN.put("modpack.delete_title", "Delete modpack");
        EN.put("modpack.delete_confirm", "Delete modpack \"{0}\"?\nThis will remove all mods, saves and config in this instance.");
        EN.put("modpack.import_title", "Import modpack");
        EN.put("modpack.import_hint", "Select .mrpack (Modrinth) or .zip (CurseForge) file");
        EN.put("modpack.file_path", "File path");
        EN.put("modpack.select_file", "Select modpack file");
        EN.put("modpack.export_title", "Export modpack");
        EN.put("modpack.export_hint", "Export version \"{0}\" as Modrinth .mrpack");
        EN.put("modpack.save_path", "Save path");
        EN.put("modpack.save_file", "Save modpack");
        EN.put("modpack.export_content", "Includes: mods/, config/, resourcepacks/, shaderpacks/, options.txt");

        EN.put("worlds.title", "Worlds");
        EN.put("worlds.empty", "No worlds. Worlds will be created when you start the game.");
        EN.put("worlds.size", "Size");
        EN.put("worlds.modified", "Modified");

        EN.put("screenshots.title", "Screenshots");
        EN.put("screenshots.empty", "No screenshots. Press F2 in-game to capture.");

        EN.put("stats.overview", "Play Time Overview");
        EN.put("stats.total_duration", "Total");
        EN.put("stats.total_sessions", "Sessions");
        EN.put("stats.daily_avg", "Daily avg");
        EN.put("stats.recent_days", "Last {0} days");
        EN.put("stats.daily_trend", "Daily Play Time");
        EN.put("stats.version_dist", "Version Play Time Distribution");

        EN.put("news.title", "Minecraft News");
        EN.put("news.source", "From Minecraft.net official RSS · click card to read");
        EN.put("news.fetching", "Fetching latest news…");
        EN.put("news.empty", "No news");
        EN.put("news.open_browser", "Open in browser");
        EN.put("news.loading_article", "Loading article…");
        EN.put("news.load_failed", "Load failed");
        EN.put("news.back_to_list", "Back to list");
        EN.put("news.source_link", "Source: {0}");
        EN.put("news.view_full", "Read full →");

        EN.put("wiki.title", "Open Mod Wiki / project page in browser.");
        EN.put("wiki.search_placeholder", "Search keyword (e.g. mod name)");
        EN.put("wiki.shortcuts", "Shortcuts");
        EN.put("wiki.modrinth", "Modrinth project page");
        EN.put("wiki.curseforge", "CurseForge mod search");
        EN.put("wiki.mc_wiki", "Minecraft Wiki search");
        EN.put("wiki.google", "Google search");
        EN.put("wiki.forge_docs", "Forge docs");
        EN.put("wiki.mojang", "Mojang official");
        EN.put("wiki.unsupported", "System browser not supported on this platform");

        EN.put("accounts.title", "Accounts");
        EN.put("accounts.current", "Current account");
        EN.put("accounts.not_logged_in", "Not logged in");
        EN.put("accounts.logout", "Log out");
        EN.put("accounts.offline", "Offline account");
        EN.put("accounts.microsoft", "Microsoft account");
        EN.put("accounts.username", "Username");
        EN.put("accounts.login", "Log in");
        EN.put("accounts.start_ms_login", "Start Microsoft login");
        EN.put("accounts.logging_in", "Logging in…");
        EN.put("accounts.device_code_hint", "Open this URL in browser:");

        EN.put("mp.connectx", "ConnectX Multiplayer");
        EN.put("mp.terracotta", "Terracotta Multiplayer");
        EN.put("mp.easytier", "EasyTier Multiplayer");
        EN.put("mp.settings", "Multiplayer settings");
        EN.put("mp.backend", "Backend");
        EN.put("mp.terracotta_official", "Terracotta (Official)");
        EN.put("mp.current_room", "Current room");
        EN.put("mp.room_code", "Room code (share with friends)");
        EN.put("mp.copy_room_code", "Copy room code");
        EN.put("mp.local_mc_addr", "Local MC address");
        EN.put("mp.copy_addr", "Copy address");
        EN.put("mp.room_id", "Room ID");
        EN.put("mp.invite_code", "Invite code (share with friends)");
        EN.put("mp.copy_invite", "Copy invite code");
        EN.put("mp.network_name", "Network name");
        EN.put("mp.virtual_ip", "Your virtual IP");
        EN.put("mp.copy_ip", "Copy IP");
        EN.put("mp.ip_acquiring", "Acquiring virtual IP… please wait");
        EN.put("mp.not_joined", "Not in a room. Create one or paste a friend's room code.");
        EN.put("mp.host", "Host");
        EN.put("mp.create_room", "Create room");
        EN.put("mp.guest", "Guest");
        EN.put("mp.room_code_label", "Room code / invite code");
        EN.put("mp.join_room", "Join room");
        EN.put("mp.leave_room", "Leave room");
        EN.put("mp.usage", "Usage");
        EN.put("mp.host_label", "Host:");
        EN.put("mp.guest_label", "Guest:");
        EN.put("mp.error_detail", "Error detail");
        EN.put("mp.connectx_settings", "ConnectX Settings");
        EN.put("mp.connectx_binary", "ConnectX.ClientConsole binary path");
        EN.put("mp.connectx_server", "ConnectX server address");
        EN.put("mp.connectx_port", "ConnectX server port");
        EN.put("mp.state.idle", "Not in room");
        EN.put("mp.state.connecting_server", "Connecting to server…");
        EN.put("mp.state.downloading_terracotta", "Downloading Terracotta…");
        EN.put("mp.state.downloading_easytier", "Downloading EasyTier…");
        EN.put("mp.state.connecting", "Connecting…");
        EN.put("mp.state.connected", "Connected");
        EN.put("mp.state.disconnected", "Disconnected");
        EN.put("mp.state.failed", "Connection failed");

        EN.put("settings.title", "Settings");
        EN.put("settings.memory", "Memory");
        EN.put("settings.min_memory", "Min (MB)");
        EN.put("settings.max_memory", "Max (MB)");
        EN.put("settings.jvm_advanced", "JVM Advanced");
        EN.put("settings.gc_type", "GC type");
        EN.put("settings.aikar", "Aikar's Flags");
        EN.put("settings.aikar_desc", "Community-recognized MC optimization flags");
        EN.put("settings.custom_args", "Custom JVM args (space separated)");
        EN.put("settings.appearance", "Appearance");
        EN.put("settings.dark_theme", "Dark theme");
        EN.put("settings.light_theme", "Light theme");
        EN.put("settings.network", "Network");
        EN.put("settings.window_icon", "Game Window Icon");
        EN.put("settings.window_icon_path", "Icon PNG path");
        EN.put("settings.window_icon_empty", "Leave empty for default icon");
        EN.put("settings.window_icon_select", "Select PNG icon file");
        EN.put("settings.window_icon_hint", "Auto-resized to 16x16 / 32x32 and injected into gameDir/icons/ on launch");
        EN.put("settings.mirror", "Download mirror");
        EN.put("settings.mirror_official", "Official");
        EN.put("settings.mirror_bmclapi", "BMCLAPI");
        EN.put("settings.mirror_custom", "Custom");
        EN.put("settings.custom_mirror", "Custom mirror base URL");
        EN.put("settings.http_proxy", "HTTP proxy");
        EN.put("settings.proxy_host", "Host");
        EN.put("settings.proxy_port", "Port");
        EN.put("settings.proxy_auth", "Proxy auth");
        EN.put("settings.speed_limit", "Speed limit (KB/s, 0=unlimited)");
        EN.put("settings.retry_count", "Retry count");
        EN.put("settings.chunked_threads", "Chunked download threads");
        EN.put("settings.enable_resume", "Resume (.part file)");
        EN.put("settings.system_info", "System Info");
        EN.put("settings.work_dir", "Working directory");
        EN.put("settings.language", "Language");
        EN.put("settings.version_isolation", "Version isolation");
        EN.put("settings.version_isolation_desc", "Independent mods/saves/config per version");
        EN.put("settings.game_behavior", "Game behavior");

        EN.put("integrity.check", "Check integrity");
        EN.put("integrity.checking", "Checking…");
        EN.put("integrity.ok", "Integrity check passed");
        EN.put("integrity.issues", "{0} issues found");
        EN.put("integrity.missing", "Missing {0}");
        EN.put("integrity.mismatch", "Hash mismatch {0}");

        EN.put("crash.title", "Crash reports");
        EN.put("crash.causes", "Possible causes");
        EN.put("crash.suggestions", "Suggestions");
        EN.put("crash.empty", "No crash reports");

        EN.put("migration.title", "Migrate game data");
        EN.put("migration.from", "Migrate from {0}");
        EN.put("migration.size", "Size: {0}");
        EN.put("migration.done", "Migration complete");
        EN.put("migration.processing", "Migrating…");
        EN.put("migration.no_source", "No HMCL or Launcher X detected");

        EN.put("plugin.title", "Plugin Manager");
        EN.put("plugin.install", "Install plugin");
        EN.put("plugin.scan", "Scan plugins");
        EN.put("plugin.empty", "No plugins");
        EN.put("plugin.enabled", "Enabled");
        EN.put("plugin.disabled", "Disabled");

        EN.put("terminal.title", "Terminal");

        // ===== 日志导出/分享 =====
        EN.put("log.export", "Export");
        EN.put("log.export_title", "Export Log");
        EN.put("log.export_hint", "Choose a save location; the log will be exported as a .txt file");
        EN.put("log.export_save", "Save log file");
        EN.put("log.choose_file", "Choose save location");
        EN.put("log.export_failed", "Export failed");
        EN.put("log.share", "Share");
        EN.put("log.share_success", "Share Success");
        EN.put("log.share_url_hint", "Log uploaded to paste.gg. Copy the link below to share:");

        // ========================================
        // JA 翻訳
        // ========================================
        JA.put("app.title", "PMCL ランチャー");
        JA.put("common.refresh", "更新");
        JA.put("common.delete", "削除");
        JA.put("common.backup", "バックアップ");
        JA.put("common.cancel", "キャンセル");
        JA.put("common.confirm", "確認");
        JA.put("common.ok", "OK");
        JA.put("common.status", "ステータス");
        JA.put("common.loading", "読み込み中…");
        JA.put("common.import", "インポート");
        JA.put("common.export", "エクスポート");
        JA.put("common.remove", "削除");
        JA.put("common.pause", "一時停止");
        JA.put("common.resume", "再開");
        JA.put("common.save", "保存");
        JA.put("common.browse", "参照");
        JA.put("common.retry", "再試行");
        JA.put("common.back", "戻る");
        JA.put("common.open", "開く");
        JA.put("common.copy", "コピー");
        JA.put("common.close", "閉じる");
        JA.put("common.enable", "有効化");
        JA.put("common.disable", "無効化");
        JA.put("common.install", "インストール");
        JA.put("common.uninstall", "アンインストール");
        JA.put("common.reload", "再読み込み");
        JA.put("common.empty", "データなし");
        JA.put("common.processing", "処理中…");
        JA.put("common.times", "回");

        JA.put("nav.launch", "起動");
        JA.put("nav.news", "ニュース");
        JA.put("nav.multiplayer", "マルチプレイ");
        JA.put("nav.download", "ダウンロード");
        JA.put("nav.content", "コンテンツ");
        JA.put("nav.saves", "セーブ");
        JA.put("nav.statistics", "統計");
        JA.put("nav.accounts", "アカウント");
        JA.put("nav.settings", "設定");
        JA.put("nav.terminal", "ターミナル");
        JA.put("nav.plugins", "プラグイン");
        JA.put("nav.mods", "Mod");
        JA.put("nav.market", "市場");
        JA.put("nav.worlds", "ワールド");
        JA.put("nav.screenshots", "スクショ");
        JA.put("nav.queue", "キュー");
        JA.put("nav.wiki", "Wiki");
        JA.put("nav.modpacks", "Modpack");
        JA.put("nav.shaders", "シェーダー");
        JA.put("nav.resourcepacks", "リソースパック");
        JA.put("nav.datapacks", "データパック");

        JA.put("launch.title", "ゲーム起動");
        JA.put("launch.select_version", "バージョン選択");
        JA.put("launch.start", "起動");
        JA.put("launch.no_version", "バージョンを選択してください");
        JA.put("launch.no_account", "先にログインしてください");
        JA.put("launch.running", "ゲームは既に実行中です");
        JA.put("launch.starting", "起動中…");
        JA.put("launch.failed", "起動失敗");
        JA.put("launch.system_info", "システム情報");
        JA.put("launch.quick_launch", "クイック起動");
        JA.put("launch.welcome", "PMCLへようこそ");
        JA.put("launch.subtitle", "クロスプラットフォームのMinecraftランチャー");
        JA.put("launch.enter", "PMCLに入る");
        JA.put("launch.start_minecraft", "Minecraftを起動");
        JA.put("launch.download_install", "ダウンロードしてインストール");
        JA.put("launch.game_running", "ゲーム実行中…");
        JA.put("launch.downloading", "ダウンロード中…");
        JA.put("launch.not_logged_in_short", "未ログイン · PMCLに入ってからログイン可能");
        JA.put("launch.no_version_selected", "バージョン未選択");
        JA.put("launch.installed", "インストール済み");
        JA.put("launch.not_installed", "未インストール");
        JA.put("launch.ready", "準備完了");
        JA.put("launch.account_label", "アカウント: {0}");
        JA.put("launch.server_connect", "サーバー直接接続");
        JA.put("launch.server_address", "サーバーアドレス");
        JA.put("launch.server_port", "ポート");
        JA.put("launch.server_empty_hint", "空欄で通常起動");
        JA.put("launch.server_leave_empty", "空欄で接続しない");
        JA.put("launch.server_hint", "起動後に指定サーバーへ自動接続（--server / --port）");
        JA.put("launch.running_instances", "実行中インスタンス");
        JA.put("launch.active", "アクティブ");

        JA.put("download.refresh", "バージョン一覧を更新");
        JA.put("download.install", "インストール");
        JA.put("download.installing", "インストール中…");
        JA.put("download.install_done", "インストール完了");
        JA.put("download.install_failed", "インストール失敗");
        JA.put("download.local_versions", "インストール済みバージョン");

        JA.put("queue.title", "ダウンロードキュー概要");
        JA.put("queue.empty", "キューが空です");
        JA.put("queue.empty_hint", "バージョンまたは市場タブでインストールをクリックして追加");
        JA.put("queue.total_items", "{0} 件");
        JA.put("queue.active", "実行中 {0}");
        JA.put("queue.done", "完了 {0}");
        JA.put("queue.failed", "失敗 {0}");
        JA.put("queue.pause_all", "全て一時停止");
        JA.put("queue.resume_all", "全て再開");
        JA.put("queue.cancel_all", "全てキャンセル");
        JA.put("queue.clear_finished", "完了を削除");
        JA.put("queue.queued", "待機中");
        JA.put("queue.running", "実行中");
        JA.put("queue.paused", "一時停止");
        JA.put("queue.cancelled", "キャンセル済み");

        JA.put("mods.installed", "インストール済みMod");
        JA.put("mods.conflicts", "競合チェック");
        JA.put("mods.no_mods", "Modがありません。インストールしたModはここに表示されます。");
        JA.put("mods.scan_done", "{0} 個のModをスキャン");
        JA.put("mods.check_update", "更新確認");
        JA.put("mods.update_all", "一括更新 ({0})");
        JA.put("mods.has_update", "更新あり");
        JA.put("mods.new_version", "更新可能: {0} · ソース: {1}");
        JA.put("mods.up_to_date", "最新です");
        JA.put("mods.update", "更新");
        JA.put("mods.checking_updates", "確認中");
        JA.put("mods.translate", "翻訳");
        JA.put("mods.translating", "翻訳中…");
        JA.put("mods.with_deps", "依存関係付き");
        JA.put("mods.dep_result_title", "依存関係インストール結果");
        JA.put("mods.dep_installed", "インストール ({0})");
        JA.put("mods.dep_skipped", "インストール済み ({0})");
        JA.put("mods.dep_system", "システム依存をスキップ");
        JA.put("mods.dep_not_found", "見つからない ({0})");
        JA.put("mods.dep_failed", "失敗 ({0})");
        JA.put("mods.dep_no_extra", "追加の依存関係は不要です");
        JA.put("mods.dep_summary_installed", "インストール: {0}");
        JA.put("mods.dep_summary_not_found", "見つからない: {0}");
        JA.put("mods.dep_summary_failed", "失敗: {0}");
        JA.put("mods.dep_no_extra_short", "依存関係なし");
        JA.put("mods.dep_installed_count", "{0} 個の依存をインストール");
        JA.put("mods.dep_mod_label", "Mod: {0}");

        JA.put("market.search", "検索");
        JA.put("market.query_placeholder", "Modを検索…");
        JA.put("market.game_version", "ゲームバージョン");
        JA.put("market.loader", "ローダー");
        JA.put("market.any", "すべて");
        JA.put("market.files", "ファイル");
        JA.put("market.download", "ダウンロード");
        JA.put("market.curseforge_disabled", "CurseForge無効（API Key未設定）");

        JA.put("modpack.title", "Modpack管理");
        JA.put("modpack.empty", "Modpackがありません");
        JA.put("modpack.empty_hint", "インポートをクリックして.mrpackまたは.zipをインストール");
        JA.put("modpack.mod_count", "{0} 個のMod");
        JA.put("modpack.delete_title", "Modpack削除");
        JA.put("modpack.delete_confirm", "Modpack「{0}」を削除しますか？\nこのインスタンスの全てのmod、セーブ、設定が削除されます。");
        JA.put("modpack.import_title", "Modpackインポート");
        JA.put("modpack.import_hint", ".mrpack (Modrinth) または.zip (CurseForge)を選択");
        JA.put("modpack.file_path", "ファイルパス");
        JA.put("modpack.select_file", "Modpackファイルを選択");
        JA.put("modpack.export_title", "Modpackエクスポート");
        JA.put("modpack.export_hint", "バージョン「{0}」をModrinth .mrpack形式でエクスポート");
        JA.put("modpack.save_path", "保存先");
        JA.put("modpack.save_file", "Modpackを保存");
        JA.put("modpack.export_content", "含む: mods/、config/、resourcepacks/、shaderpacks/、options.txt");

        JA.put("worlds.title", "ワールド管理");
        JA.put("worlds.empty", "ワールドがありません。ゲーム起動時にsavesディレクトリに作成されます。");
        JA.put("worlds.size", "サイズ");
        JA.put("worlds.modified", "最終更新");

        JA.put("screenshots.title", "スクリーンショット");
        JA.put("screenshots.empty", "スクリーンショットがありません。ゲーム内でF2を押して撮影できます。");

        JA.put("stats.overview", "プレイ時間概要");
        JA.put("stats.total_duration", "合計");
        JA.put("stats.total_sessions", "セッション");
        JA.put("stats.daily_avg", "日均");
        JA.put("stats.recent_days", "過去 {0} 日");
        JA.put("stats.daily_trend", "日別プレイ時間");
        JA.put("stats.version_dist", "バージョン別プレイ時間分布");

        JA.put("news.title", "Minecraftニュース");
        JA.put("news.source", "Minecraft.net公式RSS · カードをクリックして全文を読む");
        JA.put("news.fetching", "最新ニュースを取得中…");
        JA.put("news.empty", "ニュースがありません");
        JA.put("news.open_browser", "ブラウザで開く");
        JA.put("news.loading_article", "記事を読み込み中…");
        JA.put("news.load_failed", "読み込み失敗");
        JA.put("news.back_to_list", "一覧に戻る");
        JA.put("news.source_link", "原文リンク: {0}");
        JA.put("news.view_full", "全文を読む →");

        JA.put("wiki.title", "ブラウザでMod Wiki / プロジェクトページを開く。");
        JA.put("wiki.search_placeholder", "検索キーワード（Mod名など）");
        JA.put("wiki.shortcuts", "ショートカット");
        JA.put("wiki.modrinth", "Modrinthプロジェクトページ");
        JA.put("wiki.curseforge", "CurseForge Mod検索");
        JA.put("wiki.mc_wiki", "Minecraft Wiki検索");
        JA.put("wiki.google", "Google検索");
        JA.put("wiki.forge_docs", "Forgeドキュメント");
        JA.put("wiki.mojang", "Mojang公式");
        JA.put("wiki.unsupported", "このプラットフォームではブラウザを開けません");

        JA.put("accounts.title", "アカウント");
        JA.put("accounts.current", "現在のアカウント");
        JA.put("accounts.not_logged_in", "未ログイン");
        JA.put("accounts.logout", "ログアウト");
        JA.put("accounts.offline", "オフラインアカウント");
        JA.put("accounts.microsoft", "Microsoftアカウント");
        JA.put("accounts.username", "ユーザー名");
        JA.put("accounts.login", "ログイン");
        JA.put("accounts.start_ms_login", "Microsoftログイン開始");
        JA.put("accounts.logging_in", "ログイン中…");
        JA.put("accounts.device_code_hint", "ブラウザでこのURLを開いてください:");

        JA.put("mp.connectx", "ConnectXマルチプレイ");
        JA.put("mp.terracotta", "テラコッタマルチプレイ · Terracotta");
        JA.put("mp.easytier", "テラコッタマルチプレイ · EasyTier");
        JA.put("mp.settings", "マルチプレイ設定");
        JA.put("mp.backend", "バックエンド");
        JA.put("mp.terracotta_official", "Terracotta（公式）");
        JA.put("mp.current_room", "現在のルーム");
        JA.put("mp.room_code", "ルームコード（友達に共有）");
        JA.put("mp.copy_room_code", "ルームコードをコピー");
        JA.put("mp.local_mc_addr", "ローカルMC接続先");
        JA.put("mp.copy_addr", "アドレスをコピー");
        JA.put("mp.room_id", "ルームID");
        JA.put("mp.invite_code", "招待コード（友達に共有）");
        JA.put("mp.copy_invite", "招待コードをコピー");
        JA.put("mp.network_name", "ネットワーク名");
        JA.put("mp.virtual_ip", "あなたの仮想IP");
        JA.put("mp.copy_ip", "IPをコピー");
        JA.put("mp.ip_acquiring", "仮想IP取得中…お待ちください");
        JA.put("mp.not_joined", "ルームに参加していません。作成するか友達のコードを貼り付けてください。");
        JA.put("mp.host", "ホスト");
        JA.put("mp.create_room", "ルーム作成");
        JA.put("mp.guest", "ゲスト");
        JA.put("mp.room_code_label", "ルームコード / 招待コード");
        JA.put("mp.join_room", "ルーム参加");
        JA.put("mp.leave_room", "ルーム退出");
        JA.put("mp.usage", "使い方");
        JA.put("mp.host_label", "ホスト:");
        JA.put("mp.guest_label", "ゲスト:");
        JA.put("mp.error_detail", "エラー詳細");
        JA.put("mp.connectx_settings", "ConnectX設定");
        JA.put("mp.connectx_binary", "ConnectX.ClientConsoleバイナリパス");
        JA.put("mp.connectx_server", "ConnectXサーバーアドレス");
        JA.put("mp.connectx_port", "ConnectXサーバーポート");
        JA.put("mp.state.idle", "ルーム未参加");
        JA.put("mp.state.connecting_server", "サーバーに接続中…");
        JA.put("mp.state.downloading_terracotta", "Terracottaダウンロード中…");
        JA.put("mp.state.downloading_easytier", "EasyTierダウンロード中…");
        JA.put("mp.state.connecting", "接続中…");
        JA.put("mp.state.connected", "接続済み");
        JA.put("mp.state.disconnected", "切断済み");
        JA.put("mp.state.failed", "接続失敗");

        JA.put("settings.title", "設定");
        JA.put("settings.memory", "メモリ");
        JA.put("settings.min_memory", "最小 (MB)");
        JA.put("settings.max_memory", "最大 (MB)");
        JA.put("settings.jvm_advanced", "JVM詳細設定");
        JA.put("settings.gc_type", "GCタイプ");
        JA.put("settings.aikar", "Aikar's Flags");
        JA.put("settings.aikar_desc", "コミュニティ推奨のMC最適化パラメータ");
        JA.put("settings.custom_args", "カスタムJVM引数（スペース区切り）");
        JA.put("settings.appearance", "外観");
        JA.put("settings.dark_theme", "ダークテーマ");
        JA.put("settings.light_theme", "ライトテーマ");
        JA.put("settings.network", "ネットワーク");
        JA.put("settings.window_icon", "ゲームウィンドウアイコン");
        JA.put("settings.window_icon_path", "アイコン PNG パス");
        JA.put("settings.window_icon_empty", "空欄でデフォルトアイコン");
        JA.put("settings.window_icon_select", "PNG アイコンファイルを選択");
        JA.put("settings.window_icon_hint", "起動時に 16x16 / 32x32 へ自動リサイズし gameDir/icons/ に注入");
        JA.put("settings.mirror", "ダウンロードミラー");
        JA.put("settings.mirror_official", "公式");
        JA.put("settings.mirror_bmclapi", "BMCLAPI");
        JA.put("settings.mirror_custom", "カスタム");
        JA.put("settings.custom_mirror", "カスタムミラーURL");
        JA.put("settings.http_proxy", "HTTPプロキシ");
        JA.put("settings.proxy_host", "ホスト");
        JA.put("settings.proxy_port", "ポート");
        JA.put("settings.proxy_auth", "プロキシ認証");
        JA.put("settings.speed_limit", "速度制限 (KB/s, 0=無制限)");
        JA.put("settings.retry_count", "リトライ回数");
        JA.put("settings.chunked_threads", "分割ダウンロード接続数");
        JA.put("settings.enable_resume", "レジューム（.partファイル）");
        JA.put("settings.system_info", "システム情報");
        JA.put("settings.work_dir", "作業ディレクトリ");
        JA.put("settings.language", "言語");
        JA.put("settings.version_isolation", "バージョン分離");
        JA.put("settings.version_isolation_desc", "バージョンごとに独立したmods/saves/config");
        JA.put("settings.game_behavior", "ゲーム動作");

        JA.put("integrity.check", "整合性チェック");
        JA.put("integrity.checking", "チェック中…");
        JA.put("integrity.ok", "整合性チェック通過");
        JA.put("integrity.issues", "{0} 個の問題を発見");
        JA.put("integrity.missing", "不足 {0}");
        JA.put("integrity.mismatch", "ハッシュ不一致 {0}");

        JA.put("crash.title", "クラッシュレポート");
        JA.put("crash.causes", "考えられる原因");
        JA.put("crash.suggestions", "提案");
        JA.put("crash.empty", "クラッシュレポートがありません");

        JA.put("migration.title", "ゲームデータ移行");
        JA.put("migration.from", "{0} から移行");
        JA.put("migration.size", "サイズ: {0}");
        JA.put("migration.done", "移行完了");
        JA.put("migration.processing", "移行中…");
        JA.put("migration.no_source", "HMCLまたはLauncher Xが見つかりません");

        JA.put("plugin.title", "プラグイン管理");
        JA.put("plugin.install", "プラグインインストール");
        JA.put("plugin.scan", "プラグインスキャン");
        JA.put("plugin.empty", "プラグインがありません");
        JA.put("plugin.enabled", "有効");
        JA.put("plugin.disabled", "無効");

        JA.put("terminal.title", "ターミナル");

        // ===== 日志导出/分享 =====
        JA.put("log.export", "書き出し");
        JA.put("log.export_title", "ログ書き出し");
        JA.put("log.export_hint", "保存先を選択すると、ログが .txt ファイルとして書き出されます");
        JA.put("log.export_save", "ログファイルを保存");
        JA.put("log.choose_file", "保存先を選択");
        JA.put("log.export_failed", "書き出し失敗");
        JA.put("log.share", "共有");
        JA.put("log.share_success", "共有成功");
        JA.put("log.share_url_hint", "ログは paste.gg にアップロードされました。下記リンクをコピーして共有してください：");
    }

    public static Locale getCurrentLocale() { return current; }

    public static void setLocale(Locale locale) {
        current = locale;
    }

    /** 翻訳キー、サポート {0} {1} などパラメータプレースホルダ */
    public static String t(String key, Object... args) {
        Map<String, String> map;
        if (current == EN_US) map = EN;
        else if (current == JA_JP) map = JA;
        else map = ZH;
        String val = map.getOrDefault(key, key);
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                val = val.replace("{" + i + "}", String.valueOf(args[i]));
            }
        }
        return val;
    }
}
