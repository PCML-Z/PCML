package com.lash.pmcl.launch.control

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class KeyWidget(
    val keyCode: Int,
    val label: String,
    var x: Float = 0.5f,
    var y: Float = 0.5f,
    var opacity: Float = 0.5f,
    var size: Float = 1.0f,
    var enabled: Boolean = true
)

class ControlLayout {
    val keys: MutableList<KeyWidget> = mutableListOf()

    companion object {
        fun createDefault(): ControlLayout {
            val l = ControlLayout()
            // WASD 移动
            l.keys.add(KeyWidget(51, "W", 0.12f, 0.62f))
            l.keys.add(KeyWidget(47, "S", 0.12f, 0.78f))
            l.keys.add(KeyWidget(29, "A", 0.04f, 0.78f))
            l.keys.add(KeyWidget(32, "D", 0.20f, 0.78f))
            // Space
            l.keys.add(KeyWidget(62, "Space", 0.30f, 0.90f, size = 1.8f))
            // Shift
            l.keys.add(KeyWidget(59, "Shift", 0.04f, 0.90f))
            // E — 背包
            l.keys.add(KeyWidget(33, "E", 0.85f, 0.62f))
            // Q — 丢弃
            l.keys.add(KeyWidget(45, "Q", 0.85f, 0.46f))
            // Esc
            l.keys.add(KeyWidget(111, "Esc", 0.02f, 0.04f))
            // Tab
            l.keys.add(KeyWidget(61, "Tab", 0.85f, 0.30f))
            // F3
            l.keys.add(KeyWidget(136, "F3", 0.04f, 0.30f))
            // F5
            l.keys.add(KeyWidget(138, "F5", 0.04f, 0.46f))
            // 数字 1-9
            for (i in 1..9) {
                l.keys.add(KeyWidget(7 + i, "$i", 0.30f + (i - 1) * 0.06f, 0.30f, size = 0.6f))
            }
            // Ctrl / Alt
            l.keys.add(KeyWidget(113, "Ctrl", 0.02f, 0.78f))
            l.keys.add(KeyWidget(57, "Alt", 0.30f, 0.78f))
            // Enter
            l.keys.add(KeyWidget(66, "Enter", 0.92f, 0.62f))
            // 方向键
            l.keys.add(KeyWidget(19, "↑", 0.70f, 0.78f))
            l.keys.add(KeyWidget(20, "↓", 0.70f, 0.90f))
            l.keys.add(KeyWidget(21, "←", 0.62f, 0.84f))
            l.keys.add(KeyWidget(22, "→", 0.78f, 0.84f))
            return l
        }

        fun load(context: Context): ControlLayout {
            val file = File(context.filesDir, "pmcl/controls.json")
            return if (file.exists()) {
                try { fromJson(file.readText()) } catch (_: Exception) {
                    createDefault().also { it.save(context) }
                }
            } else createDefault().also { it.save(context) }
        }

        fun fromJson(json: String): ControlLayout {
            val l = ControlLayout()
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                l.keys.add(KeyWidget(
                    keyCode = o.getInt("keyCode"),
                    label = o.getString("label"),
                    x = o.getDouble("x").toFloat(),
                    y = o.getDouble("y").toFloat(),
                    opacity = o.optDouble("opacity", 0.5).toFloat(),
                    size = o.optDouble("size", 1.0).toFloat(),
                    enabled = o.optBoolean("enabled", true)
                ))
            }
            return l
        }
    }

    fun save(context: Context) {
        File(context.filesDir, "pmcl").mkdirs()
        File(context.filesDir, "pmcl/controls.json").writeText(toJson())
    }

    fun resetToDefault() {
        keys.clear()
        keys.addAll(createDefault().keys)
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (k in keys) {
            val o = JSONObject()
            o.put("keyCode", k.keyCode)
            o.put("label", k.label)
            o.put("x", k.x.toDouble())
            o.put("y", k.y.toDouble())
            o.put("opacity", k.opacity.toDouble())
            o.put("size", k.size.toDouble())
            o.put("enabled", k.enabled)
            arr.put(o)
        }
        return arr.toString(2)
    }
}
