package com.pmcl.plugin

/**
 * Plugin metadata descriptor — the strict format specification for PMCL plugins.
 *
 * Parsed from `META-INF/pmcl-plugin.properties` inside the plugin JAR. This file
 * is the **single source of truth** for plugin identity and must adhere to the
 * format rules below.
 *
 * ## Descriptor File Format
 *
 * - **Location**: Exactly `META-INF/pmcl-plugin.properties` (case-sensitive)
 * - **Encoding**: ISO-8859-1 (Java Properties standard); ASCII recommended
 * - **Format**: Standard Java `Properties` key=value pairs, one per line
 * - **Required fields**: All must be present and non-blank
 * - **Optional fields**: If the key is present, the value must be non-blank and valid
 *
 * ### Required Fields
 *
 * | Key                  | Constraint                                             |
 * |----------------------|--------------------------------------------------------|
 * | `plugin.id`          | 3-32 chars, `[a-z][a-z0-9-]*[a-z0-9]`, no `--`, unique |
 * | `plugin.name`        | 1-64 chars, non-blank                                  |
 * | `plugin.version`     | Semantic version `X.Y.Z` or `X.Y.Z-prerelease`        |
 * | `plugin.author`      | 1-64 chars, non-blank                                  |
 * | `plugin.description` | 1-256 chars, non-blank                                 |
 * | `plugin.api-version` | Must equal `1.0` (the current API version)             |
 * | `plugin.main-class`  | Valid Java FQN, e.g. `com.example.MyPlugin`            |
 *
 * ### Optional Fields
 *
 * | Key                  | Constraint                                             |
 * |----------------------|--------------------------------------------------------|
 * | `plugin.dependencies`| Comma-separated plugin IDs, each matching ID rules     |
 * | `plugin.website`     | Valid `http(s)://` URL, max 512 chars                  |
 * | `plugin.license`     | 1-64 chars (e.g. `MIT`, `Apache-2.0`)                  |
 *
 * ## ID Rules (Strict)
 *
 * - Lowercase letters and digits only, plus hyphens
 * - Must start with a lowercase letter (`[a-z]`)
 * - Must end with a letter or digit (`[a-z0-9]`)
 * - No consecutive hyphens (`--` forbidden)
 * - Length: 3-32 characters
 * - Example: `my-awesome-plugin` ✓ | `My-Plugin` ✗ | `my--plugin` ✗ | `my-plugin-` ✗
 *
 * ## Version Rules (Strict SemVer)
 *
 * - Format: `MAJOR.MINOR.PATCH` (e.g. `1.0.0`)
 * - Optional pre-release: `MAJOR.MINOR.PATCH-identifier` (e.g. `1.0.0-beta.1`)
 * - Pre-release identifier: `[a-zA-Z0-9][a-zA-Z0-9.]*`
 * - Build metadata (`+...`) is NOT accepted (stripped for simplicity)
 * - Example: `1.0.0` ✓ | `2.1.3-beta.1` ✓ | `1.0` ✗ | `v1.0.0` ✗
 *
 * ## Main-Class Rules
 *
 * - Must be a valid Java fully-qualified class name
 * - Segments: `[a-zA-Z_][a-zA-Z0-9_]*` separated by `.`
 * - At least 2 segments (package + class)
 * - Example: `com.example.MyPlugin` ✓ | `MyPlugin` ✗ | `com.123.Bad` ✗
 *
 * ## Command Name & Page ID Rules
 *
 * - Same as ID rules but min length 1 (single lowercase letter allowed)
 * - 1-32 chars, `[a-z][a-z0-9-]*[a-z0-9]` for 2+ chars, `[a-z]` for 1 char
 * - No `--`, no trailing hyphen
 * - Example: `greet` ✓ | `list-versions` ✓ | `Greet` ✗ | `greet-` ✗
 *
 * Example properties file:
 * ```
 * plugin.id=my-awesome-plugin
 * plugin.name=My Awesome Plugin
 * plugin.version=1.0.0
 * plugin.author=Author Name
 * plugin.description=Does awesome things
 * plugin.api-version=1.0
 * plugin.main-class=com.example.MyPlugin
 * plugin.dependencies=other-plugin,utility-lib
 * plugin.website=https://example.com/my-plugin
 * plugin.license=MIT
 * ```
 */
data class PluginInfo(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val apiVersion: String,
    val mainClass: String,
    val dependencies: List<String> = emptyList(),
    val website: String = "",
    val license: String = "",
    /**
     * 声明插件所需的敏感权限（逗号分隔的 [PluginPermission] 名称）。
     * <p>
     * 访问敏感服务（AuthService/SelfUpdater/ProcessMonitor 等）需要对应权限：
     * - `READ_ACCOUNTS` — 读取账号信息（含 accessToken）
     * - `WRITE_ACCOUNTS` — 修改账号
     * - `CONTROL_LAUNCH` — 启动/停止 Minecraft
     * - `KILL_PROCESS` — 杀死进程
     * - `SELF_UPDATE` — 替换启动器 JAR
     * - `MANAGE_PLUGINS` — 管理其他插件
     * <p>
     * 示例：`plugin.permissions=READ_ACCOUNTS,CONTROL_LAUNCH`
     */
    val permissions: List<String> = emptyList()
) {
    companion object {
        const val SUPPORTED_API_VERSION = "1.0"
        const val PROPERTIES_PATH = "META-INF/pmcl-plugin.properties"

        // ==================== Property Keys ====================

        const val KEY_ID = "plugin.id"
        const val KEY_NAME = "plugin.name"
        const val KEY_VERSION = "plugin.version"
        const val KEY_AUTHOR = "plugin.author"
        const val KEY_DESCRIPTION = "plugin.description"
        const val KEY_API_VERSION = "plugin.api-version"
        const val KEY_MAIN_CLASS = "plugin.main-class"
        const val KEY_DEPENDENCIES = "plugin.dependencies"
        const val KEY_WEBSITE = "plugin.website"
        const val KEY_LICENSE = "plugin.license"
        const val KEY_PERMISSIONS = "plugin.permissions"

        // ==================== Length Limits ====================

        const val ID_MIN_LEN = 3
        const val ID_MAX_LEN = 32
        const val NAME_MAX_LEN = 64
        const val AUTHOR_MAX_LEN = 64
        const val DESCRIPTION_MAX_LEN = 256
        const val COMMAND_NAME_MAX_LEN = 32
        const val PAGE_ID_MAX_LEN = 32
        const val LICENSE_MAX_LEN = 64
        const val WEBSITE_MAX_LEN = 512

        // ==================== Validation Regexes ====================

        /**
         * Plugin ID: 3-32 chars, starts with [a-z], ends with [a-z0-9],
         * middle allows hyphens. Consecutive hyphens checked separately.
         */
        val ID_REGEX = Regex("^[a-z][a-z0-9-]{1,30}[a-z0-9]$")

        /**
         * Semantic version: X.Y.Z with optional pre-release (-identifier).
         * Build metadata (+...) is rejected for simplicity.
         */
        val VERSION_REGEX = Regex("""^\d+\.\d+\.\d+(?:-[a-zA-Z0-9][a-zA-Z0-9.]*)?$""")

        /**
         * Command name / Page ID: 1-32 chars.
         * Single char: [a-z]. Multi-char: starts [a-z], ends [a-z0-9], middle allows hyphens.
         */
        val COMMAND_NAME_REGEX = Regex("^[a-z]([a-z0-9-]*[a-z0-9])?$")

        /**
         * Java fully-qualified class name: at least 2 segments separated by dots.
         */
        val MAIN_CLASS_REGEX = Regex("""^[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)+$""")

        /**
         * HTTP(S) URL format validation.
         */
        val WEBSITE_REGEX = Regex("""^https?://[a-zA-Z0-9]([a-zA-Z0-9.\-]*[a-zA-Z0-9])?(:\d+)?(/[/\w\-./?%&=#]*)?$""")

        // ==================== Validation Helpers ====================

        /** Validate a plugin ID against the strict format rules. */
        @JvmStatic
        fun isValidId(id: String): Boolean {
            return id.length in ID_MIN_LEN..ID_MAX_LEN &&
                    id.matches(ID_REGEX) &&
                    !id.contains("--")
        }

        /** Validate a command name or page ID against the strict format rules. */
        @JvmStatic
        fun isValidCommandName(name: String): Boolean {
            return name.length in 1..COMMAND_NAME_MAX_LEN &&
                    name.matches(COMMAND_NAME_REGEX) &&
                    !name.contains("--")
        }

        /** Validate a semantic version string. */
        @JvmStatic
        fun isValidVersion(version: String): Boolean =
            version.matches(VERSION_REGEX)

        /** Validate a Java fully-qualified class name. */
        @JvmStatic
        fun isValidMainClass(mainClass: String): Boolean =
            mainClass.matches(MAIN_CLASS_REGEX)

        /** Validate an HTTP(S) URL. */
        @JvmStatic
        fun isValidWebsite(url: String): Boolean =
            url.length <= WEBSITE_MAX_LEN && url.matches(WEBSITE_REGEX)
    }

    /**
     * Validate this plugin info against the strict format specification.
     *
     * @throws IllegalArgumentException if any field violates the format rules.
     *   The error message describes which field failed and why.
     */
    fun validate() {
        // --- ID ---
        require(isValidId(id)) {
            "plugin.id must be $ID_MIN_LEN-$ID_MAX_LEN chars, lowercase alphanumeric + hyphens, " +
            "start with a letter, end with alphanumeric, no consecutive hyphens " +
            "(got: '$id')"
        }

        // --- Name ---
        require(name.isNotBlank()) { "plugin.name must not be blank" }
        require(name.length <= NAME_MAX_LEN) {
            "plugin.name must be at most $NAME_MAX_LEN chars (got ${name.length})"
        }

        // --- Version ---
        require(isValidVersion(version)) {
            "plugin.version must be semantic version X.Y.Z or X.Y.Z-prerelease " +
            "(got: '$version')"
        }

        // --- Author ---
        require(author.isNotBlank()) { "plugin.author must not be blank" }
        require(author.length <= AUTHOR_MAX_LEN) {
            "plugin.author must be at most $AUTHOR_MAX_LEN chars (got ${author.length})"
        }

        // --- Description ---
        require(description.isNotBlank()) { "plugin.description must not be blank" }
        require(description.length <= DESCRIPTION_MAX_LEN) {
            "plugin.description must be at most $DESCRIPTION_MAX_LEN chars (got ${description.length})"
        }

        // --- API Version ---
        require(apiVersion == SUPPORTED_API_VERSION) {
            "plugin.api-version must be '$SUPPORTED_API_VERSION' (got '$apiVersion'). " +
            "This plugin was built for a different PMCL plugin API version."
        }

        // --- Main Class ---
        require(isValidMainClass(mainClass)) {
            "plugin.main-class must be a valid Java FQN with at least 2 segments " +
            "(e.g. 'com.example.MyPlugin'), got: '$mainClass'"
        }

        // --- Dependencies ---
        for (dep in dependencies) {
            val trimmed = dep.trim()
            require(trimmed.matches(ID_REGEX) && !trimmed.contains("--")) {
                "Invalid dependency ID: '$dep' (must match plugin.id format rules)"
            }
        }

        // --- Website (optional) ---
        if (website.isNotEmpty()) {
            require(isValidWebsite(website)) {
                "plugin.website must be a valid http(s):// URL (got: '$website')"
            }
        }

        // --- License (optional) ---
        if (license.isNotEmpty()) {
            require(license.length <= LICENSE_MAX_LEN) {
                "plugin.license must be at most $LICENSE_MAX_LEN chars (got ${license.length})"
            }
        }
    }
}
