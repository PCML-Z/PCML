package com.pmcl.plugin.api

/**
 * Host UI contributions that do not require Compose in the caller for simple actions.
 */
interface UiApi {
    /** Show a toast / snackbar-style notification in the host UI. */
    fun notify(title: String, message: String, level: NotificationLevel = NotificationLevel.INFO)

    /**
     * Ask the host to navigate to a built-in or plugin page id.
     * Built-in examples: `home`, `settings`, `plugins`, `versions`.
     * Plugin pages: `plugin:<pluginId>:<pageId>`.
     */
    fun navigate(target: String)

    /** Copy text to the system clipboard via the host UI thread when possible. */
    fun copyToClipboard(text: String)

    /**
     * Show a host-mediated dialog.
     * For [DialogKind.INFO], [onResult] is invoked with `true` when dismissed.
     * For [DialogKind.CONFIRM], `true` = confirm, `false` = cancel/dismiss.
     */
    fun showDialog(
        title: String,
        message: String,
        kind: DialogKind = DialogKind.INFO,
        confirmLabel: String = "OK",
        cancelLabel: String = "Cancel",
        onResult: BooleanCallback? = null,
    )

    /**
     * Show a text-input dialog.
     * [onResult] receives the entered string, or null when cancelled.
     */
    fun showInputDialog(
        title: String,
        message: String,
        defaultValue: String = "",
        confirmLabel: String = "OK",
        cancelLabel: String = "Cancel",
        onResult: PathCallback,
    )

    /** Open a URL with the host's default browser handler. */
    fun openUrl(url: String)

    /**
     * Ask the host to show a file open/save dialog.
     * [filters] is an optional semicolon-separated extension list, e.g. `jar;zip`.
     */
    fun pickFile(
        title: String,
        filters: String = "",
        save: Boolean = false,
        onResult: PathCallback,
    )

    /** Ask the host to show a folder picker. */
    fun pickFolder(title: String, onResult: PathCallback)

    /**
     * Show or update a progress indicator.
     * [progress] in `0.0..1.0`; pass a negative value for indeterminate.
     */
    fun showProgress(id: String, title: String, progress: Double)

    /** Dismiss a previously shown progress indicator. */
    fun dismissProgress(id: String)

    /**
     * Set a sidebar badge for a built-in route (`settings`, `plugins`, …)
     * or a plugin page (`plugin:<pluginId>:<pageId>`).
     * Pass blank [text] to clear.
     */
    fun setNavBadge(target: String, text: String)

    /** Clear a previously set nav badge. */
    fun clearNavBadge(target: String)

    /**
     * Hide a built-in sidebar destination by route
     * (e.g. `music`, `friends`, `nbt`). Cleared when the plugin is disabled.
     */
    fun hideBuiltinNav(route: String)

    /** Undo [hideBuiltinNav]. */
    fun showBuiltinNav(route: String)
}

/** Java/Kotlin-friendly boolean callback for dialog results. */
fun interface BooleanCallback {
    fun call(value: Boolean)
}
