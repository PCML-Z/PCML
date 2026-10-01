package com.pmcl.ui

import java.awt.Component
import java.awt.Window
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 游戏全屏会占独立 Space，或者把窗口层级抬到菜单栏之上。
 * 普通置顶进不去。这里按这扇 AWT 窗口的 NSWindow，让它加入所有 Space，并抬到最高层。
 */
object MacOverlay {
    private var loaded = false
    private var warned = false

    fun pin(window: Window?) {
        val os = System.getProperty("os.name", "")
        if (!os.contains("mac", ignoreCase = true) || window == null) return
        try {
            ensureLoaded()
            val peer = peer(window) ?: return
            val platform = peer.javaClass.getMethod("getPlatformWindow").invoke(peer) ?: return
            val action = Class.forName("sun.lwawt.macosx.CFRetainedResource\$CFNativeAction")
            val callback = Proxy.newProxyInstance(action.classLoader, arrayOf(action)) { _, method, args ->
                if (method.name == "run" && args != null && args.isNotEmpty()) {
                    val ptr = (args[0] as Number).toLong()
                    if (ptr != 0L) Natives.pin(ptr)
                }
                null
            }
            platform.javaClass.getMethod("execute", action).invoke(platform, callback)
        } catch (t: Throwable) {
            if (!warned) {
                warned = true
                System.err.println("[Music] macOS fullscreen overlay: ${t.message}")
            }
        }
    }

    private fun peer(window: Window): Any? {
        val field = Component::class.java.getDeclaredField("peer")
        field.isAccessible = true
        return field.get(window)
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val name = "libpmcloverlay.dylib"
            val input = MacOverlay::class.java.getResourceAsStream("/native/$name")
                ?: throw IllegalStateException("missing $name")
            input.use { stream ->
                val dir = Files.createTempDirectory("pmcl-overlay")
                dir.toFile().deleteOnExit()
                val lib = dir.resolve(name)
                Files.copy(stream, lib, StandardCopyOption.REPLACE_EXISTING)
                lib.toFile().deleteOnExit()
                System.load(lib.toAbsolutePath().toString())
            }
            loaded = true
        }
    }

    private object Natives {
        @JvmStatic
        external fun pin(nsWindowPtr: Long)
    }
}
