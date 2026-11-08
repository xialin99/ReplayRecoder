package com.replay.recoder.ui

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.replay.recoder.R
import com.replay.recoder.record.RecordService
import com.replay.recoder.record.RecordState
import java.lang.ref.WeakReference

/**
 * 悬浮胶囊:录制时显示在屏幕边缘。
 *
 * 视觉(DS 阶段3 · 蓝色工具风 M3):
 * - 胶囊形(80x36dp,全圆角)替代原 60dp 圆球
 * - 蓝色径向渐变(统一主题色)+ 中心白色录制圆点
 * - 录制中:切换为红色胶囊 + 中心停止符号,配合脉冲呼吸动画
 *
 * 交互:
 * - 单击:打开回放 App(回主界面,用户可点回放)
 * - 双击:切换相机 ↔ 上一个应用(CameraActivity singleInstance)
 * - 长按 500ms:未录制 → 开始录制(走 onStartRecording 回调);录制中 → 结束录制
 * - 拖动:移动位置(位置自动持久化,下次还在原处)
 *
 * 需要 SYSTEM_ALERT_WINDOW 权限。
 */
class FloatingControl(private val context: Context) {

    /** 开始录制回调(由创建者注入:MainActivity 走权限流程,Service 复用 savedResultData)。
     *  为 null 时长按未录制退化为打开 App。 */
    var onStartRecording: (() -> Unit)? = null
    /** 停止录制回调(为 null 时内部默认发 stopIntent)。 */
    var onStopRecording: (() -> Unit)? = null

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var ballView: FrameLayout? = null
    private var tvStatus: TextView? = null

    /** 悬浮球位置持久化:RecordService/MainActivity 各自 new FloatingControl,
     *  不持久化会导致开始录制时球回到初始位置。拖到哪、下次还在哪。 */
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        // 恢复上次拖动位置(无记录则默认屏幕左缘垂直居中:0,0)
        x = prefs.getInt(KEY_X, 0)
        y = prefs.getInt(KEY_Y, 0)
    }

    /** 拖动结束后保存位置,跨实例/跨重启保持。 */
    private fun savePosition() {
        prefs.edit().putInt(KEY_X, params.x).putInt(KEY_Y, params.y).apply()
    }

    /** 把位置夹紧在屏幕内:gravity = START|CENTER_VERTICAL,
     *  x ∈ [0, 屏宽-胶囊宽],y ∈ [-半高+半胶囊高, 半高-半胶囊高](相对垂直居中的偏移)。
     *  防止旋转/分辨率变化后胶囊卡在屏外找不回。
     *  DS 阶段3:胶囊形 80x36dp 替换原 60dp 圆球,宽高分别夹紧。 */
    private fun clampPosition(persist: Boolean = false) {
        val dm = context.resources.displayMetrics
        val ballW = (80 * dm.density).toInt()
        val ballH = (36 * dm.density).toInt()
        val maxX = (dm.widthPixels - ballW).coerceAtLeast(0)
        val maxY = (dm.heightPixels / 2 - ballH / 2).coerceAtLeast(0)
        val cx = params.x.coerceIn(0, maxX)
        val cy = params.y.coerceIn(-maxY, maxY)
        if (cx != params.x || cy != params.y) {
            params.x = cx; params.y = cy
            if (persist) savePosition()
        }
    }

    fun show() {
        if (ballView != null) return
        if (!Settings.canDrawOverlays(context)) return
        clampPosition(persist = true)
        // 防叠加:同一时刻屏幕上只允许一个悬浮球。
        // MainActivity idle 球与 RecordService 录制球分属不同实例,
        // 切换(Activity 重建/Service 重启)时旧实例可能未被及时 hide,
        // 导致屏上出现两个球。show 前先 hide 旧实例(弱引用,已 GC 则跳过)。
        currentRef?.get()?.let { other ->
            if (other !== this) runCatching { other.hideImmediate() }
        }
        currentRef = WeakReference(this)

        val dm = context.resources.displayMetrics
        // 胶囊容器:80x36dp,背景=胶囊 drawable,内部居中显示录制状态文字
        val container = FrameLayout(context).apply {
            background = ContextCompat.getDrawable(context, ballDrawable())
            alpha = SEMI_TRANSPARENT
            minimumWidth = (80 * dm.density).toInt()
            minimumHeight = (36 * dm.density).toInt()
            val tv = TextView(context).apply {
                text = statusText()
                setTextColor(ContextCompat.getColor(context, android.R.color.white))
                textSize = 10f
                gravity = Gravity.CENTER
            }
            addView(
                tv,
                FrameLayout.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
            tvStatus = tv
            // 脉冲动画:呼吸效果,一看就知道在录(idle 常驻时不开动画)
            if (RecordState.isRecording) {
                animation = AnimationUtils.loadAnimation(context, R.anim.floating_pulse)
            }
            setOnTouchListener(TouchHandler())
            isLongClickable = true
        }
        // 录制态创建(RecordService 停录前持有):启动每秒计时刷新,否则时长卡在初值
        if (RecordState.isRecording) startTimer()
        if (!runCatching { wm.addView(container, params) }.isSuccess) {
            // addView 失败(权限被回收等)必须回滚引用:否则 show() 会因 ballView != null
            // 永久直接 return,该实例再也无法显示,用户看到"悬浮球消失且无法恢复"。
            Log.e("FloatingControl", "addView 失败,回滚悬浮球状态")
            handler.removeCallbacks(timeRunnable)
            container.animation?.cancel()
            ballView = null
            tvStatus = null
            if (currentRef?.get() === this) currentRef = null
            return
        }
        ballView = container
    }

    /** 按 [RecordState.isRecording] 选 drawable:
     *  - 录制中:绿色胶囊(floating_capsule_recording)
     *  - idle:蓝色胶囊(floating_capsule) */
    private fun ballDrawable(): Int =
        if (RecordState.isRecording) R.drawable.floating_capsule_recording
        else R.drawable.floating_capsule

    /** 胶囊内状态文字:录制中显示"已录时长"(recordingTimeText),未录制"未录制"。 */
    private fun statusText(): String =
        if (RecordState.isRecording) recordingTimeText() else "未录制"

    /** 已录时长文案 mm:ss;超过回放窗口时长后封顶显示窗口时长(胶囊语义=窗口里存了多少)。 */
    private fun recordingTimeText(): String {
        val elapsedSec = (SystemClock.elapsedRealtime() - RecordState.startElapsedRealtime) / 1000
        val capSec = if (RecordState.windowMinutes == 0) 30L else RecordState.windowMinutes * 60L
        val shown = elapsedSec.coerceAtLeast(0L).coerceAtMost(capSec)
        return String.format("%02d:%02d", shown / 60, shown % 60)
    }

    /** 录制时长计时:每秒刷新胶囊文字 */
    private val timeRunnable = object : Runnable {
        override fun run() {
            if (!RecordState.isRecording) return
            tvStatus?.text = recordingTimeText()
            handler.postDelayed(this, 1000)
        }
    }

    /** 启动时长计时(先清旧回调防重复) */
    private fun startTimer() {
        handler.removeCallbacks(timeRunnable)
        handler.post(timeRunnable)
    }

    /** 切到录制态:换绿色胶囊 + 已录时长计时 + 脉冲动画,提示正在录。
     *  已在显示(常驻模式)时调用,避免重复 addView。 */
    fun onStartRecording() {
        val v = ballView ?: return
        v.background = ContextCompat.getDrawable(context, R.drawable.floating_capsule_recording)
        tvStatus?.text = recordingTimeText()
        startTimer()
        if (v.animation == null) {
            v.animation = AnimationUtils.loadAnimation(context, R.anim.floating_pulse)
        }
    }

    /** 切到 idle 态:换回蓝色胶囊 + 状态文字"未录制" + 停计时/停脉冲,变静态(常驻模式下保持显示)。 */
    fun onStopRecording() {
        val v = ballView ?: return
        handler.removeCallbacks(timeRunnable)
        v.background = ContextCompat.getDrawable(context, R.drawable.floating_capsule)
        tvStatus?.text = "未录制"
        v.animation?.cancel()
        v.animation = null
        v.alpha = SEMI_TRANSPARENT
    }

    fun hide() {
        handler.removeCallbacksAndMessages(null)
        ballView?.let {
            it.animation?.cancel()
            runCatching { wm.removeView(it) }
        }
        ballView = null
        // 清理静态引用(若指向自己)
        if (currentRef?.get() === this) currentRef = null
    }

    /** 同步隐藏(不走 handler,供 show() 防叠加时立即移除旧球)。 */
    private fun hideImmediate() {
        ballView?.let {
            it.animation?.cancel()
            runCatching { wm.removeView(it) }
        }
        ballView = null
    }

    /** 单击:打开回放 App(回主界面,让用户点回放按钮进回放页)。
     *  不直接进 PlaybackActivity,因为停录是异步的,段还没冻结完,进去拿不到段。 */
    private fun onClick() {
        Log.i("FloatingControl", "单击 → 打开回放 App")
        val home = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        context.startActivity(home)
    }

    /** 双击:切换悬浮相机显示/隐藏。
     *  ★ 必须用进程内单例(FloatingCamera.get):悬浮相机窗口常驻 WindowManager,
     *  每次 new 实例会造成多个窗口 + 多套相机 session,二次绑定必黑屏(华为 HAL 拒绝重开)。
     *  全屏相机保留在 App 工具箱入口。 */
    private fun onDoubleClick() {
        // 全屏相机在前台时,双击悬浮球语义应是"关闭相机",而不是再叠一个悬浮相机窗口
        // (两者共用同一颗物理相机,叠开只会得到一个黑窗)。
        if (CameraActivity.isForeground) {
            Log.i("FloatingControl", "双击 → 关闭全屏相机")
            context.sendBroadcast(Intent(ACTION_CLOSE_CAMERA).setPackage(context.packageName))
            return
        }
        val cam = FloatingCamera.get(context)
        Log.i("FloatingControl", "双击 → 切换悬浮相机 (visible=${cam.isVisible})")
        cam.toggle()
    }

    /** 长按:录制中 → 结束录制;未录制 → 开始录制。 */
    private fun onLongPress() {
        if (RecordState.isRecording) {
            Log.i("FloatingControl", "长按 → 结束录制")
            if (onStopRecording != null) onStopRecording!!()
            else androidx.core.content.ContextCompat.startForegroundService(
                context, RecordService.stopIntent(context)
            )
        } else {
            Log.i("FloatingControl", "长按 → 开始录制")
            if (onStartRecording != null) onStartRecording!!()
            else {
                // 无回调:退化为打开 App,让用户手动点开始录制
                onClick()
            }
        }
    }

    /** 触摸:拖动 + 区分单击/长按。
     *  长按改为"按住达到时长立即触发"(不等到抬手):
     *  - ACTION_DOWN 记录起点,启动 [longPressRunnable] 定时器(500ms)
     *  - 定时器到点(手指仍在、未拖动)→ 立即结束录制,标记 longPressFired
     *  - MOVE 超阈值 → 标记拖动,取消长按定时器
     *  - UP:未拖动且长按未触发 → 单击;长按已触发则忽略 */
    private inner class TouchHandler : View.OnTouchListener {
        private var startX = 0; private var startY = 0
        private var downX = 0f; private var downY = 0f
        private var moved = false
        private var longPressFired = false
        private var lastClickTime = 0L

        private val longPressRunnable = Runnable {
            if (!moved && !longPressFired) {
                longPressFired = true
                onLongPress()
            }
        }

        private val singleClickRunnable = Runnable {
            onClick()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downX = e.rawX; downY = e.rawY
                    moved = false
                    longPressFired = false
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (dx * dx + dy * dy > 64) {
                        if (!moved) handler.removeCallbacks(longPressRunnable)
                        moved = true
                    }
                    params.x = startX + dx
                    params.y = startY + dy
                    clampPosition()
                    runCatching { wm.updateViewLayout(v, params) }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (e.action == MotionEvent.ACTION_UP) {
                        if (moved) {
                            savePosition()
                        } else if (!longPressFired) {
                            val now = System.currentTimeMillis()
                            if (now - lastClickTime < DOUBLE_CLICK_MS) {
                                // 双击 → 打开相机,取消等待中的单击
                                handler.removeCallbacks(singleClickRunnable)
                                lastClickTime = 0L
                                onDoubleClick()
                            } else {
                                // 等待是否双击,300ms 后执行单击
                                lastClickTime = now
                                handler.postDelayed(singleClickRunnable, DOUBLE_CLICK_MS)
                            }
                        }
                    }
                }
            }
            return true
        }
    }

    companion object {
        /** 长按阈值:按住 500ms 立即触发(与系统 long-press timeout 一致)。 */
        private const val LONG_PRESS_MS = 500L
        /** 双击间隔阈值:两次点击 < 300ms 视为双击。 */
        private const val DOUBLE_CLICK_MS = 300L
        /** 悬浮球半透明度:整体 65% 不透明,不遮挡底下内容,文字仍可读。 */
        private const val SEMI_TRANSPARENT = 0.65f
        private const val PREFS_NAME = "floating_ball_prefs"
        private const val KEY_X = "ball_x"
        private const val KEY_Y = "ball_y"
        /** 双击关闭相机的广播 action */
        const val ACTION_CLOSE_CAMERA = "com.replay.recoder.CLOSE_CAMERA"
        /** 当前屏幕上唯一悬浮球的弱引用(防叠加):MainActivity idle 球与
         *  RecordService 录制球分属不同实例,切换时旧实例可能未及时 hide,
         *  show() 前先 hide 旧实例,保证同时只有一个球。 */
        @JvmStatic
        private var currentRef: WeakReference<FloatingControl>? = null
    }
}
