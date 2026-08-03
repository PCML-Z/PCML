package com.lash.pmcl.launch

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import com.lash.pmcl.launch.control.ControlLayout
import com.lash.pmcl.launch.control.FullKeyboardView
import com.oracle.dalvik.VMLauncher
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minecraft 渲染 Activity — GLSurfaceView + 可自定义全键盘控件。
 */
class MinecraftActivity : Activity() {

    companion object {
        const val EXTRA_ARGS = "launch_args"
    }

    private val surfaceReady = AtomicBoolean(false)
    private var container: FrameLayout? = null
    private var keyboardView: FullKeyboardView? = null
    private var controlLayout: ControlLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val args = intent.getStringArrayExtra(EXTRA_ARGS) ?: emptyArray()

        // 加载控件布局
        controlLayout = ControlLayout.load(this)

        container = FrameLayout(this)

        // GL 渲染层
        val glSurface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setEGLConfigChooser(8, 8, 8, 8, 24, 0)
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?,
                                               config: javax.microedition.khronos.egl.EGLConfig?) {
                    if (surfaceReady.compareAndSet(false, true)) {
                        Thread({
                            try {
                                val code = VMLauncher.launchJVM(args)
                                android.util.Log.i("MC", "JVM exit: $code")
                            } catch (e: Exception) {
                                android.util.Log.e("MC", "JVM fail", e)
                            } finally { runOnUiThread { finish() } }
                        }, "pmcl-mc").apply { isDaemon = false; priority = Thread.MAX_PRIORITY; start() }
                    }
                }
                override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, w: Int, h: Int) {}
                override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {}
            })
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        container?.addView(glSurface, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // 键盘控件层
        keyboardView = FullKeyboardView(this, controlLayout!!, editMode = false).apply {
            keyDispatcher = object : FullKeyboardView.KeyDispatcher {
                override fun onKeyDown(keyCode: Int) {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                }
                override fun onKeyUp(keyCode: Int) {
                    dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                }
            }
        }
        container?.addView(keyboardView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        setContentView(container)
    }

    /** 返回键：游戏模式→切换编辑模式；编辑模式→保存并退出编辑 */
    override fun onBackPressed() {
        if (keyboardView?.isEditMode() == true) {
            keyboardView?.setEditMode(false)
            controlLayout?.save(this)
        } else {
            keyboardView?.setEditMode(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controlLayout?.save(this)
    }
}
