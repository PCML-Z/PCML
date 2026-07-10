package com.example

import com.pmcl.plugin.PmclPlugin
import com.pmcl.plugin.PluginContext

class TestPlugin : PmclPlugin {
    override val pluginId = "test-plugin"

    override fun onEnable(ctx: PluginContext) {
        ctx.info("Test plugin enabled!")

        // Register a custom command
        ctx.registerCommand("greet", "Greet someone") { args ->
            val name = args.firstOrNull() ?: "World"
            "Hello, $name! Greetings from Test Plugin v1.0.0"
        }

        // Register another command that accesses PMCL core
        ctx.registerCommand("list-versions", "List local versions via plugin") { _ ->
            val core = ctx.getService(com.pmcl.core.LauncherCore::class.java)
            if (core != null) {
                val versions = core.versions().scanAllLocalVersions()
                if (versions.isEmpty()) {
                    "No local versions found."
                } else {
                    "Found ${versions.size} local versions:\n" +
                        versions.take(5).joinToString("\n") { "  - ${it.id}" }
                }
            } else {
                "Cannot access LauncherCore"
            }
        }

        // Add an event listener
        ctx.addEventListener { event ->
            when (event.type) {
                "version_installed" -> ctx.info("Test Plugin saw version installed: ${event.javaClass.simpleName}")
                "game_launched" -> ctx.info("Test Plugin saw game launched")
            }
        }

        ctx.info("Test Plugin registered 2 commands and 1 event listener")
    }

    override fun onDisable() {
        println("[test-plugin] Goodbye!")
    }
}
