package com.lash.pmcl.launch.control

import android.content.Context
import android.graphics.*
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * 全键盘渲染视图 — 标准 PC 键盘在屏幕上的映射。
 *
 * 每帧绘制所有启用的按键，触摸时发送 KeyEvent。
 * 支持编辑模式（长按拖动）和游戏模式（点击=按键）。
 */
class FullKeyboardView(
    context: Context,
    private val layout: ControlLayout,
    private var editMode: Boolean = false
) : View(context) {

    /** 按键事件分发器（Activity 注入） */
    interface KeyDispatcher {
        fun onKeyDown(keyCode: Int)
        fun onKeyUp(keyCode: Int)
    }
    var keyDispatcher: KeyDispatcher? = null

    companion object {
        /** 基础按键大小 (dp) */
        const val BASE_KEY_SIZE_DP = 52f
        /** 字体大小 (dp) */
        const val LABEL_SIZE_DP = 14f
        /** 拖动灵敏度 (dp) */
        const val DRAG_THRESHOLD_DP = 8f
    }

    private val density = resources.displayMetrics.density
    private val baseKeySize = BASE_KEY_SIZE_DP * density
    private val labelSize = LABEL_SIZE_DP * density
    private val dragThreshold = DRAG_THRESHOLD_DP * density

    // 绘制
    private val keyBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val keyBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density
    }
    private val keyText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = labelSize
        isFakeBoldText = true
    }

    // 拖动
    private var dragKey: KeyWidget? = null
    private var dragStartX = 0f; private var dragStartY = 0f
    private var dragInitX = 0f; private var dragInitY = 0f
    private var isDragging = false

    // 当前按下的键
    private val pressedKeys = mutableSetOf<Int>()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x; val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val key = findKeyAt(x, y)
                if (key != null && key.enabled) {
                    if (editMode) {
                        // 编辑模式：开始拖动
                        dragKey = key; dragStartX = x; dragStartY = y
                        dragInitX = key.x; dragInitY = key.y
                        isDragging = false
                    } else {
                        // 游戏模式：按下按键
                        pressedKeys.add(key.keyCode)
                        keyDispatcher?.onKeyDown(key.keyCode)
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragKey != null && editMode) {
                    val dx = Math.abs(x - dragStartX); val dy = Math.abs(y - dragStartY)
                    if (!isDragging && (dx > dragThreshold || dy > dragThreshold)) {
                        isDragging = true
                    }
                    if (isDragging) {
                        val scaleX = 1f / width; val scaleY = 1f / height
                        dragKey?.x = (dragInitX * width + (x - dragStartX)) * scaleX
                        dragKey?.y = (dragInitY * height + (y - dragStartY)) * scaleY
                        // 钳制到屏幕内
                        dragKey?.x = dragKey?.x?.coerceIn(0.02f, 0.98f) ?: 0.5f
                        dragKey?.y = dragKey?.y?.coerceIn(0.02f, 0.98f) ?: 0.5f
                        invalidate()
                    }
                }
                // 游戏模式：检查手指是否移出按键范围
                if (!editMode && pressedKeys.isNotEmpty()) {
                    val key = findKeyAt(x, y)
                    for (kc in pressedKeys.toList()) {
                        if (key == null || key.keyCode != kc) {
                            pressedKeys.remove(kc)
                            keyDispatcher?.onKeyUp(kc)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragKey != null && editMode) {
                    if (!isDragging) {
                        // 点击切换启用状态
                        dragKey?.let { k -> k.enabled = !k.enabled }
                    }
                    dragKey = null
                    invalidate()
                }
                // 游戏模式：释放所有按下的键
                if (!editMode) {
                    for (kc in pressedKeys) {
                        keyDispatcher?.onKeyUp(kc)
                    }
                    pressedKeys.clear()
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                dragKey = null
                if (!editMode) {
                    for (kc in pressedKeys) { keyDispatcher?.onKeyUp(kc) }
                    pressedKeys.clear()
                }
            }
        }
        return true
    }

    /** 多指支持 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        return onTouchEvent(event)
    }

    private fun findKeyAt(x: Float, y: Float): KeyWidget? {
        val threshold = baseKeySize * 1.2f
        var best: KeyWidget? = null
        var bestDist = Float.MAX_VALUE
        for (k in layout.keys) {
            if (!editMode && !k.enabled) continue
            val kx = k.x * width; val ky = k.y * height
            val dist = Math.hypot((x - kx).toDouble(), (y - ky).toDouble()).toFloat()
            if (dist < threshold && dist < bestDist) {
                bestDist = dist; best = k
            }
        }
        return best
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 半透明背景（编辑模式更明显）
        val bgAlpha = if (editMode) 0x22 else 0x08
        canvas.drawColor((bgAlpha shl 24) or 0xFFFFFF)

        for (key in layout.keys) {
            if (!editMode && !key.enabled) continue

            val kx = key.x * width
            val ky = key.y * height
            val ks = baseKeySize * 0.5f * key.size
            val rad = ks * 0.2f

            // 背景
            val alpha = (key.opacity * 255).toInt()
            val baseColor = if (key.keyCode in pressedKeys) 0xFF4444FF.toInt() else 0xFFAAAAAA.toInt()
            val bgColor = (alpha shl 24) or (baseColor and 0xFFFFFF)

            keyBg.color = bgColor
            keyBorder.color = (alpha shl 24) or (if (editMode) 0xFF00FF.toInt() else 0xFFFFFF)
            keyText.alpha = if (key.enabled) 255 else 64

            canvas.drawRoundRect(kx - ks, ky - ks, kx + ks, ky + ks, rad, rad, keyBg)
            canvas.drawRoundRect(kx - ks, ky - ks, kx + ks, ky + ks, rad, rad, keyBorder)

            // 标签
            canvas.drawText(key.label, kx, ky + labelSize * 0.35f, keyText)
        }

        // 编辑模式提示
        if (editMode) {
            val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xAAFFAA00.toInt(); textSize = 18f * density
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("编辑模式：拖动改位置 · 点击切换启用 · 返回键退出",
                width / 2f, height * 0.96f, hintPaint)
        }
    }

    fun setEditMode(on: Boolean) { editMode = on; invalidate() }
    fun isEditMode() = editMode
}
