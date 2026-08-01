package com.lash.pmcl.core.i18n

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 启动器国际化 — Android 精简版。
 *
 * 桌面版 I18n.java 包含 4000+ 翻译条目（405KB），全部硬编码在 Java Map 中。
 * Android 上不应复制此模式，UI 文本应使用 `res/values/strings.xml` + 资源限定符。
 *
 * 本类只保留 core 模块自身会用到的少量错误/状态消息（网络、安装、下载等），
 * 以便 core 中的异常信息对用户友好。UI 层不应依赖此类，而应使用 Android 资源。
 *
 * 用法：`I18n.t("download.failed", url)`
 *
 * 支持语言：zh_CN（默认）、en_US。其他语言代码会回退到 zh_CN。
 */
object I18n {

    val ZH_CN: Locale = Locale.SIMPLIFIED_CHINESE
    val EN_US: Locale = Locale.US

    @Volatile
    private var current: Locale = ZH_CN

    private val ZH: Map<String, String> = buildMap {
        // ===== 通用 =====
        put("common.ok", "确定")
        put("common.cancel", "取消")
        put("common.confirm", "确认")
        put("common.retry", "重试")
        put("common.back", "返回")
        put("common.close", "关闭")
        put("common.save", "保存")
        put("common.install", "安装")
        put("common.uninstall", "卸载")
        put("common.delete", "删除")
        put("common.refresh", "刷新")
        put("common.loading", "加载中…")
        put("common.failed", "失败")
        put("common.success", "成功")

        // ===== 下载 =====
        put("download.failed", "下载失败: {0}")
        put("download.sha1_mismatch", "SHA1 校验失败: {0} 期望={1} 实际={2}")
        put("download.interrupted", "下载已中断")
        put("download.no_sha1", "拒绝无 SHA-1 的下载任务: {0}")
        put("download.http_error", "HTTP {0}: {1}")
        put("download.invalid_path", "非法下载相对路径: {0}")
        put("download.path_traversal", "下载路径越界: {0}")
        put("download.empty_body", "响应体为空: {0}")
        put("download.body_too_large", "响应体过大 ({0} > {1}): {2}")
        put("download.ssrf_blocked", "SSRF blocked: {0}")
        put("download.ssrf_redirect_blocked", "SSRF redirect blocked: {0}")

        // ===== 安装 =====
        put("install.failed", "安装失败: {0} — {1}")
        put("install.interrupted", "安装已中断")
        put("install.missing_sha1", "版本清单缺少 SHA-1，拒绝下载: {0}")
        put("install.missing_native", "缺少 native 库，无法解压: {0}")
        put("install.zip_bomb", "ZIP 解压超出条目数上限: {0}")
        put("install.zip_slip", "ZIP 条目路径越界: {0}")
        put("install.zip_size_limit", "ZIP 解压超出总大小上限: {0}")
        put("install.disk_full", "磁盘空间不足，需要 {0} bytes")
        put("install.asset_index_missing_sha1", "assetIndex 缺少 sha1，拒绝无完整性校验的索引下载")
        put("install.asset_index_missing_url", "版本声明了 assets={0} 但缺少 assetIndex.url，拒绝安装")
        put("install.done", "安装完成: {0}")

        // ===== 版本管理 =====
        put("version.not_found", "未找到版本: {0}")
        put("version.json_invalid", "版本 JSON 解析失败: {0}")
        put("version.inheritance_cycle", "版本继承出现循环: {0}")

        // ===== 网络 / 镜像 =====
        put("network.ssl_handshake", "无法连接 {0}（SSL 握手失败），请检查网络或在设置中配置代理。原始错误：{1}")
        put("network.timeout", "连接 {0} 超时，请检查网络或配置代理。原始错误：{1}")
        put("network.unknown_host", "无法解析 {0} 域名，请检查网络或 DNS 设置。原始错误：{1}")
        put("network.mirror_failure", "镜像 {0} 标记失败：{1}")

        // ===== 市场 =====
        put("market.search_failed", "{0} 搜索失败：{1}")
        put("market.list_files_failed", "{0} 拉取版本失败：{1}")
        put("market.get_project_failed", "{0} 获取项目失败：{1}")

        // ===== 偏好 =====
        put("preferences.load_failed", "偏好加载失败，使用默认值: {0}")
        put("preferences.save_failed", "偏好保存失败: {0}")
    }

    private val EN: Map<String, String> = buildMap {
        // ===== Common =====
        put("common.ok", "OK")
        put("common.cancel", "Cancel")
        put("common.confirm", "Confirm")
        put("common.retry", "Retry")
        put("common.back", "Back")
        put("common.close", "Close")
        put("common.save", "Save")
        put("common.install", "Install")
        put("common.uninstall", "Uninstall")
        put("common.delete", "Delete")
        put("common.refresh", "Refresh")
        put("common.loading", "Loading…")
        put("common.failed", "Failed")
        put("common.success", "Success")

        // ===== Download =====
        put("download.failed", "Download failed: {0}")
        put("download.sha1_mismatch", "SHA1 mismatch: {0} expected={1} actual={2}")
        put("download.interrupted", "Download interrupted")
        put("download.no_sha1", "Refused download without SHA-1: {0}")
        put("download.http_error", "HTTP {0}: {1}")
        put("download.invalid_path", "Invalid download path: {0}")
        put("download.path_traversal", "Download path traversal: {0}")
        put("download.empty_body", "Empty response body: {0}")
        put("download.body_too_large", "Response too large ({0} > {1}): {2}")
        put("download.ssrf_blocked", "SSRF blocked: {0}")
        put("download.ssrf_redirect_blocked", "SSRF redirect blocked: {0}")

        // ===== Install =====
        put("install.failed", "Install failed: {0} — {1}")
        put("install.interrupted", "Install interrupted")
        put("install.missing_sha1", "Version manifest missing SHA-1, refused: {0}")
        put("install.missing_native", "Missing native library: {0}")
        put("install.zip_bomb", "ZIP entries exceed limit: {0}")
        put("install.zip_slip", "ZIP entry path traversal: {0}")
        put("install.zip_size_limit", "ZIP total size exceeds limit: {0}")
        put("install.disk_full", "Insufficient disk space, need {0} bytes")
        put("install.asset_index_missing_sha1", "assetIndex missing sha1, refused")
        put("install.asset_index_missing_url", "Version declares assets={0} but assetIndex.url missing")
        put("install.done", "Install complete: {0}")

        // ===== Version =====
        put("version.not_found", "Version not found: {0}")
        put("version.json_invalid", "Version JSON parse failed: {0}")
        put("version.inheritance_cycle", "Version inheritance cycle: {0}")

        // ===== Network =====
        put("network.ssl_handshake", "Cannot connect to {0} (SSL handshake failed). Check network or configure proxy. Original: {1}")
        put("network.timeout", "Connection to {0} timed out. Check network or configure proxy. Original: {1}")
        put("network.unknown_host", "Cannot resolve {0}. Check network or DNS. Original: {1}")
        put("network.mirror_failure", "Mirror {0} marked failure: {1}")

        // ===== Market =====
        put("market.search_failed", "{0} search failed: {1}")
        put("market.list_files_failed", "{0} list files failed: {1}")
        put("market.get_project_failed", "{0} get project failed: {1}")

        // ===== Preferences =====
        put("preferences.load_failed", "Preferences load failed, using defaults: {0}")
        put("preferences.save_failed", "Preferences save failed: {0}")
    }

    /**
     * Plugin-registered overlays: languageCode → (key → value).
     * Looked up before built-in maps so plugins can localize their own strings.
     */
    private val PLUGIN_STRINGS: ConcurrentHashMap<String, ConcurrentHashMap<String, String>> =
        ConcurrentHashMap()

    fun getCurrentLocale(): Locale = current

    fun setLocale(locale: Locale) {
        current = locale
    }

    /** 翻译键，支持 {0} {1} 等参数占位符 */
    fun t(key: String, vararg args: Any?): String {
        val map = if (current == EN_US) EN else ZH
        val langCode = if (current == EN_US) "en_US" else "zh_CN"

        var value: String = PLUGIN_STRINGS[langCode]?.get(key)
            ?: map[key] ?: key

        if (args.isNotEmpty()) {
            for (i in args.indices) {
                value = value.replace("{$i}", args[i]?.toString() ?: "")
            }
        }
        return value
    }

    /**
     * Register or replace plugin translation strings for a language code.
     * Keys should be plugin-prefixed (e.g. `myplugin.hello`).
     */
    fun putPluginStrings(language: String, strings: Map<String, String>) {
        if (language.isBlank() || strings.isEmpty()) return
        val lang = language.trim()
        val map = PLUGIN_STRINGS.computeIfAbsent(lang) { ConcurrentHashMap() }
        for ((k, v) in strings) {
            if (k.isBlank()) continue
            map[k] = v
        }
    }

    /** Remove specific keys previously registered for [language]. */
    fun removePluginStrings(language: String, keys: Iterable<String>) {
        if (language.isBlank()) return
        val map = PLUGIN_STRINGS[language.trim()] ?: return
        for (k in keys) map.remove(k)
    }

    /** Clear all plugin strings for one language, or every language when blank. */
    fun clearPluginStrings(language: String) {
        if (language.isBlank()) {
            PLUGIN_STRINGS.clear()
        } else {
            PLUGIN_STRINGS.remove(language.trim())
        }
    }
}
