package com.example.demo

/**
 * Kotlin utility helper for the demo plugin.
 */
object Util {
    fun toUpperCase(text: String): String {
        return text.uppercase().replace(" ", "_")
    }

    fun repeat(text: String, times: Int): String {
        return text.repeat(times.coerceAtLeast(0))
    }
}
