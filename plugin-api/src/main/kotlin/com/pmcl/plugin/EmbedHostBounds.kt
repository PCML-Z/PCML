package com.pmcl.plugin

import java.awt.Rectangle

/**
 * 停靠宿主区域边界注册表（屏幕全局坐标，单位 = 点/points）。
 *
 * <p>用于 `embed=window` 模式：UI 层的 [DockHost] 组合在每次布局后把自身在屏幕上的
 * 全局矩形写入此处；核心层的 [com.pmcl.core.plugin.NativeDockBridge] 停靠循环读取该矩形，
 * 把被停靠应用的真实窗口定位/缩放到这个区域。
 *
 * <p>坐标体系约定（与 AWT `java.awt.Rectangle` 一致）：macOS 上 AWT 窗口坐标与
 * AppleScript 窗口坐标都是 points，因此这里直接用 points，无需额外换算。
 */
object EmbedHostBounds {

    private val bounds = mutableMapOf<String, Rectangle?>()

    @Synchronized
    fun set(pluginId: String, rect: Rectangle?) {
        bounds[pluginId] = rect
    }

    @Synchronized
    fun get(pluginId: String): Rectangle? = bounds[pluginId]

    @Synchronized
    fun remove(pluginId: String) {
        bounds.remove(pluginId)
    }
}
