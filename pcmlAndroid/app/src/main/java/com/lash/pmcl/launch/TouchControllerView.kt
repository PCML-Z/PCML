package com.lash.pmcl.launch

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 触控控件叠加层 — 虚拟摇杆 + 动作按钮。
 *
 * 绘制在 GLSurfaceView 上层，捕捉触摸事件并模拟键盘/鼠标输入。
 */
class TouchControllerView(context: Context) : FrameLayout(context) {

    companion object {
        // 按键映射
        const val KEY_FORWARD = 17   // W
        const val KEY_BACK = 30      // S
        const val KEY_LEFT = 29      // A
        const val KEY_RIGHT = 32     // D
        const val KEY_JUMP = 62      // Space
        const val KEY_SNEAK = 59     // Shift
        const val KEY_INVENTORY = 33 // E
        const val BTN_ATTACK = 0
        const val BTN_USE = 1

        // 配置
        const val JOYSTICK_RADIUS_DP = 80f
        const val BUTTON_SIZE_DP = 48f
    }

    /** 按键事件发送器（由 Activity 注入） */
    var keySender: KeySender? = null

    private val density = resources.displayMetrics.density
    private val joystickRadius = JOYSTICK_RADIUS_DP * density
    private val btnSize = BUTTON_SIZE_DP * density

    // 摇杆
    private var joystickX = 0f      // 摇杆中心 X
    private var joystickY = 0f      // 摇杆中心 Y
    private var joyActive = false
    private var joyPointerId = -1
    private var currentJoyX = 0f    // 当前触摸 X（相对中心）
    private var currentJoyY = 0f
    private var lastDirX = 0        // 上次方向（防重复发送）
    private var lastDirY = 0

    // 动作按钮位置
    private val buttons = mutableListOf<ActionButton>()
    private var activeButton: ActionButton? = null

    // 绘制
    private val paintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF.toInt(); style = Paint.Style.FILL }
    private val paintJoy = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF.toInt(); style = Paint.Style.FILL }
    private val paintJoyKnob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAAFFFFFF.toInt(); style = Paint.Style.FILL }
    private val paintBtnBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF.toInt(); style = Paint.Style.FILL }
    private val paintBtnActive = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88FFFFFF.toInt(); style = Paint.Style.FILL }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; textAlign = Paint.Align.CENTER }

    init {
        setWillNotDraw(false)
        isFocusable = false
        isClickable = false

        // 延迟初始化位置（等 layout 确定）
        post {
            joystickX = joystickRadius * 1.5f
            joystickY = height - joystickRadius * 1.5f

            val bx = width - btnSize * 1.5f
            val baseY = height - btnSize * 3.5f
            val gap = btnSize * 1.3f

            // 动作按钮（右侧）
            buttons.clear()
            buttons.add(ActionButton(bx - gap, baseY, btnSize, "跳", KEY_JUMP, "⤴"))
            buttons.add(ActionButton(bx + gap, baseY, btnSize, "潜", KEY_SNEAK, "⬇"))
            buttons.add(ActionButton(bx - gap, baseY + gap, btnSize, "攻", KEY_JUMP, "◎")) // 左键
            buttons.add(ActionButton(bx + gap, baseY + gap, btnSize, "用", KEY_JUMP, "▶")) // 右键
            buttons.add(ActionButton(bx, baseY - gap, btnSize, "包", KEY_INVENTORY, "▣"))
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIdx = event.actionIndex
        val pointerId = event.getPointerId(pointerIdx)
        val x = event.getX(pointerIdx)
        val y = event.getY(pointerIdx)

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // 检查是否在摇杆区域
                val joyDist = Math.hypot((x - joystickX).toDouble(), (y - joystickY).toDouble()).toFloat()
                if (joyDist < joystickRadius * 2 && !joyActive) {
                    joyActive = true
                    joyPointerId = pointerId
                    updateJoystick(x, y)
                    return true
                }
                // 检查按钮
                for (btn in buttons) {
                    if (btn.contains(x, y)) {
                        activeButton = btn
                        keySender?.sendKeyDown(btn.keyCode)
                        invalidate()
                        return true
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // 更新摇杆
                for (i in 0 until event.pointerCount) {
                    if (event.getPointerId(i) == joyPointerId && joyActive) {
                        updateJoystick(event.getX(i), event.getY(i))
                        break
                    }
                }
                // 检查按钮按住
                if (activeButton != null && activeButton!!.contains(x, y) == false) {
                    keySender?.sendKeyUp(activeButton!!.keyCode)
                    activeButton = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                // 摇杆释放
                if (pointerId == joyPointerId) {
                    stopJoystick()
                }
                // 按钮释放
                if (activeButton != null) {
                    val btn = activeButton!!
                    keySender?.sendKeyUp(btn.keyCode)
                    // 短暂延迟后发送点击（模拟点击左键/右键）
                    if (btn.label == "攻") keySender?.sendMouseButton("left")
                    else if (btn.label == "用") keySender?.sendMouseButton("right")
                    activeButton = null
                    invalidate()
                }
            }
        }
        return true
    }

    private fun updateJoystick(x: Float, y: Float) {
        currentJoyX = (x - joystickX).coerceIn(-joystickRadius, joystickRadius)
        currentJoyY = (y - joystickY).coerceIn(-joystickRadius, joystickRadius)

        // 方向判断（4方向，40% 死区）
        val threshold = joystickRadius * 0.4f
        val dx = if (currentJoyX > threshold) 1 else if (currentJoyX < -threshold) -1 else 0
        val dy = if (currentJoyY > threshold) 1 else if (currentJoyY < -threshold) -1 else 0

        if (dx != lastDirX || dy != lastDirY) {
            // 释放旧方向
            if (lastDirX > 0) keySender?.sendKeyUp(KEY_RIGHT)
            else if (lastDirX < 0) keySender?.sendKeyUp(KEY_LEFT)
            if (lastDirY > 0) keySender?.sendKeyUp(KEY_BACK)
            else if (lastDirY < 0) keySender?.sendKeyUp(KEY_FORWARD)

            // 按下新方向
            if (dx > 0) keySender?.sendKeyDown(KEY_RIGHT)
            else if (dx < 0) keySender?.sendKeyDown(KEY_LEFT)
            if (dy > 0) keySender?.sendKeyDown(KEY_BACK)
            else if (dy < 0) keySender?.sendKeyDown(KEY_FORWARD)

            lastDirX = dx; lastDirY = dy
        }
        invalidate()
    }

    private fun stopJoystick() {
        joyActive = false
        if (lastDirX > 0) keySender?.sendKeyUp(KEY_RIGHT)
        else if (lastDirX < 0) keySender?.sendKeyUp(KEY_LEFT)
        if (lastDirY > 0) keySender?.sendKeyUp(KEY_BACK)
        else if (lastDirY < 0) keySender?.sendKeyUp(KEY_FORWARD)
        lastDirX = 0; lastDirY = 0
        currentJoyX = 0f; currentJoyY = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 半透明背景
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintBg)

        // 摇杆
        canvas.drawCircle(joystickX, joystickY, joystickRadius, paintJoy)
        val knobX = joystickX + currentJoyX
        val knobY = joystickY + currentJoyY
        canvas.drawCircle(knobX, knobY, joystickRadius * 0.45f, paintJoyKnob)

        // 动作按钮
        for (btn in buttons) {
            val active = (btn == activeButton)
            canvas.drawCircle(btn.cx, btn.cy, btn.radius,
                if (active) paintBtnActive else paintBtnBg)
            canvas.drawText(btn.symbol, btn.cx, btn.cy + 10f, paintText)
        }
    }

    data class ActionButton(
        val cx: Float, val cy: Float, val radius: Float,
        val label: String, val keyCode: Int, val symbol: String
    ) {
        fun contains(x: Float, y: Float) = Math.hypot((x - cx).toDouble(), (y - cy).toDouble()) < radius
    }
}

/**
 * 按键发送器接口 — 由平台实现注入。
 */
interface KeySender {
    fun sendKeyDown(keyCode: Int)
    fun sendKeyUp(keyCode: Int)
    fun sendMouseButton(button: String)
}
