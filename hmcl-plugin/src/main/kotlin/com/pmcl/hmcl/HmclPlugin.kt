package com.pmcl.hmcl

import com.pmcl.plugin.PmclPlugin
import com.pmcl.plugin.PluginContext

/**
 * PMCL plugin that embeds HMCL's JavaFX UI into PMCL's Compose Desktop.
 *
 * How it works:
 * 1. Registers a Compose page "HMCL" in the sidebar
 * 2. The page contains a SwingPanel wrapping a JFXPanel (JavaFX-Swing bridge)
 * 3. On first render, HmclEmbedder initializes JavaFX and calls HMCL's
 *    Controllers.initialize(stage) to build the main scene
 * 4. The scene is "stolen" from a hidden Stage and attached to the JFXPanel
 * 5. HMCL's full JavaFX UI is now rendered inside the Compose Desktop window
 */
class HmclPlugin : PmclPlugin {

    override fun onLoad() {
        // No-op
    }

    override fun onEnable(ctx: PluginContext) {
        ctx.info("HMCL Embed plugin enabled — registering HMCL page")
        ctx.registerPage("hmcl-view", "HMCL", HmclPageContent())
    }

    override fun onDisable() {
        HmclEmbedder.shutdown()
    }
}
