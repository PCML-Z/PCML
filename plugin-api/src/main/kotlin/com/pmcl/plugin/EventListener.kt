package com.pmcl.plugin

/**
 * Listener for PMCL events.
 * Register via [PluginContext.addEventListener].
 */
fun interface EventListener {
    fun onEvent(event: PmclEvent)
}
