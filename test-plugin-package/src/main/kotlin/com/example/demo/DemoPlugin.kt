package com.example.demo

import com.pmcl.plugin.PmclPlugin
import com.pmcl.plugin.PluginContext

/**
 * Demo plugin main class — written in Kotlin.
 * Registers commands that use both Kotlin [Util] and Java [MathHelper].
 */
class DemoPlugin : PmclPlugin {
    override val pluginId = "demo-plugin"

    override fun onEnable(ctx: PluginContext) {
        // Register a greeting command
        ctx.registerCommand("demo-greet", "Greet from the demo plugin") { args ->
            val name = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "World"
            "Demo says: Hello, $name! (from Kotlin)"
        }

        // Register a command that uses the Kotlin utility
        ctx.registerCommand("demo-upper", "Uppercase text via Kotlin util") { args ->
            val text = args.joinToString(" ").ifBlank { "(empty)" }
            Util.toUpperCase(text)
        }

        // Register a command that calls the Java helper
        ctx.registerCommand("demo-calc", "Calculate factorial via Java helper") { args ->
            val n = args.firstOrNull()?.toIntOrNull() ?: 5
            if (n < 0 || n > 20) {
                "Please provide a number between 0 and 20"
            } else {
                val result = MathHelper.factorial(n)
                "MathHelper.factorial($n) = $result (from Java)"
            }
        }

        // Register a command that combines both
        ctx.registerCommand("demo-info", "Show demo plugin info") { _ ->
            buildString {
                appendLine("=== Demo Plugin Package ===")
                appendLine("Kotlin main: ${this.javaClass.simpleName}")
                appendLine("Kotlin util: ${Util.javaClass.simpleName}")
                appendLine("Java helper: ${MathHelper::class.java.simpleName}")
                appendLine("Factorial(10) = ${MathHelper.factorial(10)}")
                appendLine("Upper test: ${Util.toUpperCase("hello")}")
            }.trim()
        }

        println("[DemoPlugin] Enabled: 4 commands registered (greet, upper, calc, info)")
    }
}
