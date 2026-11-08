package com.replay.recoder.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.replay.recoder.R

/**
 * 悬浮相机窗口:覆盖在任意 App 之上,纯预览(不录像不拍照)。
 *
 * ★ 防黑屏核心策略(华为/HarmonyOS 实测):
 *   日志实证:removeView / setSurfaceProvider(新 surface) 会销毁 Surface → 采集会话报
 *   CAMERA_ERROR(3) → CameraX 内部 reopenCameraAfterError → HAL 返回 ERROR_CAMERA_DISABLED,
 *   之后该相机设备永久不可用(黑屏)。
 *
 *   因此本类保证:
 *   1. ProcessCameraProvider 全局单例,只 create 一次。
 *   2. LifecycleOwner 全局单例,永远 STARTED,永不 DESTROYED。
 *   3. PreviewView 只创建一次、**永不 removeView**、**永不更换 surfaceProvider**。
 *   4. hide() 先 unbind(优雅关闭相机设备),再把窗口移到屏幕外;
 *      Surface 全程存活,不在采集过程中被销毁。
 *   5. show() 重新 bind 到同一个 surfaceProvider(不重建 Surface)。
 *   6. 缩放/移动只改 WindowManager.LayoutParams,不碰 CameraX。
 *
 *   结果:不存在"采集过程中 Surface 被销毁" → 不会触发会话 fatal 错误与 HAL DISABLED。
 */
class FloatingCamera private constructor(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainExec = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** root 只在首次 show 时创建,之后常驻 WindowManager。 */
    private var root: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var lp: WindowManager.LayoutParams? = null

    /** 是否正处于显示状态(位置在屏幕内)。 */
    var isVisible = false
        private set

    /** 当前是否已绑定相机(use case 已绑定到 surfaceProvider)。 */
    private var surfaceBound = false

    /** 等待布局后绑定的轮询任务(便于取消,避免叠加)。 */
    private var bindRetry: Runnable? = null

    // ==================== 对外接口 ====================

    /** 双击悬浮球调用:显示 ↔ 隐藏 切换。 */
    fun toggle() {
        if (isVisible) hide() else show()
    }

    /** 显示:窗口移回屏幕内,尺寸/位置取持久化值。 */
    fun show() {
        if (root == null) initWindow()
        val r = root ?: return
        val p = previewView ?: return
        val params = lp ?: return

        val (w, h) = savedSize()
        val (x, y) = savedPosition()
        params.width = w
        params.height = h
        params.x = x
        params.y = y
        clampPosition(params)
        clampSize(params)
        r.visibility = View.VISIBLE
        runCatching { wm.updateViewLayout(r, params) }
        isVisible = true
        Log.i(TAG, "show: ${params.width}x${params.height} @ ${params.x},${params.y} bound=$surfaceBound")

        if (surfaceBound) {
            Log.i(TAG, "show: 复用已有 session(不碰 CameraX)")
        } else {
            bindWhenLaidOut(p)
        }
    }

    /** 隐藏:先优雅解绑(关闭相机设备),再把窗口移到屏幕外。
     *  ★ 顺序关键:必须让 CameraX 先停止采集,再让窗口离开可见区域。
     *  反了会销毁/打断正在推帧的 Surface → 会话 fatal 错误 → HAL DISABLED → 永久黑屏。
     *  ★ PreviewView 本身不 removeView、不换 surfaceProvider,Surface 常驻,
     *  下次 show 重新 bind 到同一个 surfaceProvider,不涉及 Surface 重建。 */
    fun hide() {
        val r = root ?: return
        val params = lp ?: return
        if (!isVisible) return
        bindRetry?.let { handler.removeCallbacks(it) }
        bindRetry = null
        releaseCamera()
        params.x = -params.width - 200
        params.y = -params.height - 200
        runCatching { wm.updateViewLayout(r, params) }
        isVisible = false
        Log.i(TAG, "hide: 相机已解绑 + 移到屏外")
    }

    /** 优雅关闭相机:只解绑 use case,不销毁 Surface。 */
    private fun releaseCamera() {
        if (!surfaceBound) return
        val prov = provider ?: return
        val prev = currentPreview ?: return
        surfaceBound = false
        runCatching { prov.unbind(prev) }
        currentPreview = null
        Log.i(TAG, "releaseCamera: unbind 完成")
    }

    // ==================== 初始化 ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun initWindow() {
        if (root != null) return

        val (w, h) = savedSize()
        val (x, y) = savedPosition()
        val params = WindowManager.LayoutParams(
            w, h,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        clampPosition(params)
        clampSize(params)
        lp = params

        val frame = FrameLayout(context)

        // COMPATIBLE = TextureView,对窗口移动/尺寸变化更宽容(不像 SurfaceView 会重建 surface)
        val pv = PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_START
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(pv)

        // 关闭按钮(右上角)
        val closeSize = dp(32)
        val closePad = dp(6)
        val btnClose = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setBackgroundResource(R.drawable.bg_close_btn)
            setPadding(closePad, closePad, closePad, closePad)
            layoutParams = FrameLayout.LayoutParams(closeSize, closeSize).apply {
                gravity = Gravity.TOP or Gravity.END
                marginEnd = dp(4)
                topMargin = dp(4)
            }
            setOnClickListener { hide() }
        }
        frame.addView(btnClose)

        // 缩放手柄(右下角)
        val handleSize = dp(28)
        val handle = ImageView(context).apply {
            setImageResource(R.drawable.ic_resize_handle)
            setBackgroundResource(R.drawable.bg_resize_handle)
            layoutParams = FrameLayout.LayoutParams(handleSize, handleSize).apply {
                gravity = Gravity.BOTTOM or Gravity.END
            }
        }
        frame.addView(handle)

        frame.setOnTouchListener(TouchHandler(params, frame, btnClose, handle))

        // 先校验悬浮窗权限并确认 addView 成功,再登记 root/previewView。
        // 旧实现无条件赋值 root,addView 失败后 toggle 仍认为"正在显示",
        // 实际没有窗口、相机绑定失败且无任何提示。
        if (!android.provider.Settings.canDrawOverlays(context)) {
            Log.e(TAG, "缺少悬浮窗权限,无法创建悬浮相机")
            android.widget.Toast.makeText(context, "请先授予悬浮窗权限", android.widget.Toast.LENGTH_SHORT).show()
            lp = null
            return
        }
        val added = runCatching { wm.addView(frame, params) }.isSuccess
        if (!added) {
            Log.e(TAG, "addView 失败(无悬浮窗权限?)")
            lp = null
            return
        }
        root = frame
        previewView = pv
        Log.i(TAG, "initWindow: addView ${params.width}x${params.height} @ ${params.x},${params.y}")
    }

    /** 等 PreviewView 完成布局(有真实宽高)后再绑,只绑一次。 */
    private fun bindWhenLaidOut(pv: PreviewView) {
        bindRetry?.let { handler.removeCallbacks(it) }
        var tries = 0
        val runnable = object : Runnable {
            override fun run() {
                val cur = previewView ?: return
                tries++
                // 期间被隐藏 → 放弃本次绑定,等下次 show 再来
                if (!isVisible) return
                if (cur.width > 10 && cur.height > 10) {
                    bindOnce(cur)
                } else if (tries < 40) {
                    handler.postDelayed(this, 100)
                } else {
                    Log.e(TAG, "❌ PreviewView 等了 4s 仍无尺寸")
                }
            }
        }
        bindRetry = runnable
        handler.postDelayed(runnable, 100)
    }

    /** 绑定:全局 provider + 全局 lifecycle,复用同一个 PreviewView/surfaceProvider。 */
    private fun bindOnce(pv: PreviewView) {
        if (surfaceBound) return
        val owner = lifecycleOwner ?: run {
            Log.e(TAG, "lifecycleOwner 未初始化")
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val prov = runCatching { future.get() }.getOrElse { e ->
                Log.e(TAG, "getCameraProvider 失败", e)
                return@addListener
            }
            // 异步回调期间窗口可能已被隐藏:不再绑定,避免相机在"无窗口"状态下仍被占用
            if (!isVisible || previewView !== pv) return@addListener
            provider = prov
            // 仅进程内首次绑定时清理其它模块的占用,之后不再干扰。
            // 全屏相机在前台时不能 unbindAll,否则会拆掉它正在用的绑定。
            if (!firstBindDone && !CameraActivity.isForeground) {
                runCatching { prov.unbindAll() }
            }
            val p = Preview.Builder().build().apply { setSurfaceProvider(pv.surfaceProvider) }
            currentPreview = p
            try {
                prov.bindToLifecycle(owner, CAMERA_SELECTOR, p)
                surfaceBound = true
                firstBindDone = true
                Log.i(TAG, "✅ bind 成功,相机已打开")
            } catch (e: Exception) {
                Log.e(TAG, "❌ bindToLifecycle 失败", e)
            }
        }, mainExec)
    }

    // ==================== 尺寸/位置 ====================

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun defaultSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        val w = (dm.widthPixels * 0.75f).toInt()
        return w to (w * 16f / 9f).toInt()
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun savedSize(): Pair<Int, Int> {
        val w = prefs.getInt(KEY_W, -1)
        val h = prefs.getInt(KEY_H, -1)
        val (dw, dh) = defaultSize()
        return if (w > 100 && h > 100) w to h else dw to dh
    }

    private fun savedPosition(): Pair<Int, Int> {
        val x = prefs.getInt(KEY_X, -1)
        val y = prefs.getInt(KEY_Y, -1)
        if (x >= 0 && y >= 0) return x to y
        val (sw, _) = screenSize()
        val (w, _) = savedSize()
        return (sw - w - dp(20)) to dp(20)
    }

    private fun saveLayout(x: Int, y: Int, w: Int, h: Int) {
        prefs.edit()
            .putInt(KEY_X, x).putInt(KEY_Y, y)
            .putInt(KEY_W, w).putInt(KEY_H, h)
            .apply()
    }

    private fun clampPosition(params: WindowManager.LayoutParams) {
        val (sw, sh) = screenSize()
        params.x = params.x.coerceIn(0, (sw - params.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, (sh - params.height).coerceAtLeast(0))
    }

    /** 只限制最小宽度(屏宽 1/8)与"放大后不超出屏幕"的上限,高度按 9:16 竖屏跟随。
     *  上限按当前位置计算:放大时不会把窗口顶出屏幕,也就不会触发位置夹紧(否则会"跳")。 */
    private fun clampSize(params: WindowManager.LayoutParams) {
        val (sw, sh) = screenSize()
        val minW = (sw / 8f).toInt()
        val maxByWidth = (sw - params.x).coerceAtLeast(minW)
        val maxByHeight = ((sh - params.y) * 9f / 16f).toInt().coerceAtLeast(minW)
        val maxW = kotlin.math.min(maxByWidth, maxByHeight)
        params.width = params.width.coerceIn(minW, maxW)
        params.height = (params.width * 16f / 9f).toInt()
    }

    // ==================== 触摸 ====================

    private inner class TouchHandler(
        private val params: WindowManager.LayoutParams,
        private val frame: FrameLayout,
        private val btnClose: View,
        private val handle: View,
    ) : View.OnTouchListener {
        /** 手势模式只在 ACTION_DOWN 判定一次。
         *  ★ 不能每个事件都 hit 判定:缩放时窗口变大/变小,手柄会跟着移走,
         *  手指就会"离开手柄"落到拖动分支 → 窗口突然跳位。 */
        private var mode = MODE_NONE
        private var startW = 0
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var downX = 0f
        private var downY = 0f
        private var moving = false

        private fun hit(v: View, e: MotionEvent): Boolean {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val x = e.rawX.toInt()
            val y = e.rawY.toInt()
            return x in loc[0]..(loc[0] + v.width) && y in loc[1]..(loc[1] + v.height)
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (hit(btnClose, e)) {
                        btnClose.performClick()
                        return true
                    }
                    if (hit(handle, e)) {
                        mode = MODE_RESIZE
                        startW = params.width
                        startRawX = e.rawX
                        startRawY = e.rawY
                    } else {
                        mode = MODE_DRAG
                        startX = params.x
                        startY = params.y
                        downX = e.rawX
                        downY = e.rawY
                        moving = false
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    when (mode) {
                        MODE_RESIZE -> {
                            val dx = (e.rawX - startRawX).toInt()
                            val dy = (e.rawY - startRawY).toInt()
                            // 只取位移更大的那个轴,避免两个方向互相牵扯导致尺寸来回抖
                            val d = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) dx else dy
                            params.width = startW + d
                            clampSize(params)
                            runCatching { wm.updateViewLayout(frame, params) }
                        }
                        MODE_DRAG -> {
                            val dx = (e.rawX - downX).toInt()
                            val dy = (e.rawY - downY).toInt()
                            if (!moving && dx * dx + dy * dy > 64) moving = true
                            if (moving) {
                                params.x = startX + dx
                                params.y = startY + dy
                                clampPosition(params)
                                runCatching { wm.updateViewLayout(frame, params) }
                            }
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == MODE_RESIZE || (mode == MODE_DRAG && moving)) {
                        saveLayout(params.x, params.y, params.width, params.height)
                    }
                    mode = MODE_NONE
                    moving = false
                }
            }
            return true
        }
    }

    companion object {
        private const val TAG = "FloatingCamera"
        private const val PREFS_NAME = "floating_camera_prefs"
        private const val KEY_X = "x"
        private const val KEY_Y = "y"
        private const val KEY_W = "w"
        private const val KEY_H = "h"

        /** 触摸手势模式:按下时判定一次,整段手势内不再改变。 */
        private const val MODE_NONE = 0
        private const val MODE_DRAG = 1
        private const val MODE_RESIZE = 2

        private val CAMERA_SELECTOR = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        @Volatile private var instance: FloatingCamera? = null

        /** ProcessCameraProvider 全局单例(只 create 一次)。 */
        @Volatile private var provider: ProcessCameraProvider? = null

        /** LifecycleOwner 全局单例(永远 STARTED,永不 DESTROYED)。 */
        @Volatile private var lifecycleOwner: LifecycleOwner? = null

        /** 唯一的 Preview use case(每次 show 重新创建,绑定到同一个 surfaceProvider)。 */
        @Volatile private var currentPreview: Preview? = null

        /** 进程内是否已完成过一次绑定(首次才做 unbindAll 清理)。 */
        @Volatile private var firstBindDone = false

        /** 进程内单例:保证只有一个悬浮窗、一套相机 session。 */
        fun get(ctx: Context): FloatingCamera =
            instance ?: synchronized(this) {
                instance ?: FloatingCamera(ctx.applicationContext).also { instance = it }
            }

        /** App 启动时预初始化全局 LifecycleOwner。 */
        @Synchronized
        fun ensureInitialized(ctx: Context) {
            if (lifecycleOwner != null) return
            lateinit var reg: LifecycleRegistry
            val owner = object : LifecycleOwner {
                override val lifecycle: Lifecycle get() = reg
            }
            reg = LifecycleRegistry(owner)
            reg.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            reg.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleOwner = owner
            Log.i(TAG, "ensureInitialized: LifecycleOwner ready")
        }
    }
}
