package com.pmcl.plugin.api

/**
 * Host i18n lookup plus optional plugin string overlays.
 * Plugins may also keep their own strings; this exposes host locale keys and
 * plugin-registered keys (preferred when present).
 */
interface I18nApi {
    /** Current UI language code (e.g. zh_CN, en_US). */
    fun currentLanguage(): String

    /** Translate a host / plugin i18n key with optional format args. Missing keys return the key. */
    fun t(key: String, vararg args: Any?): String

    /**
     * Register translation strings for [language] (e.g. `zh_CN`, `en_US`).
     * Keys should be prefixed with the plugin id to avoid collisions
     * (e.g. `myplugin.hello`).
     * Cleared automatically when the plugin is disabled.
     */
    fun registerStrings(language: String, strings: Map<String, String>)

    /** Remove all strings previously registered by this plugin for [language], or all languages when blank. */
    fun clearStrings(language: String = "")
}
