package com.pmcl.plugin

/**
 * Compact card shown on the Launch / home surface.
 *
 * Plugins register via [PluginContext.registerHomeCard].
 * [content] is Compose UI rendered inside a host-provided container.
 */
data class HomeCard(
    /** Unique within the owning plugin (1-32 chars, same rules as page ids). */
    val id: String,
    /** Card title shown in the host chrome. */
    val title: String,
    /** Optional subtitle / hint. */
    val subtitle: String = "",
    /** Sort order; lower values appear first. */
    val order: Int = 100,
    /** Compose content for the card body. */
    val content: ComposableContent,
)
