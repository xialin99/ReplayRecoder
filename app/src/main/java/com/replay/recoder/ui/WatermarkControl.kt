package com.replay.recoder.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.replay.recoder.record.WatermarkConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 水印 overlay:录制时显示在屏幕右下角,会被 MediaProjection 采集到录制画面里。
 *
 * 两种水印,各自独立开关:
 * - 时间水印:yyyy-MM-dd HH:mm:ss,每秒刷新
 * - 自定义文字水印:用户在主界面配置的文字
 *
 * 实现:单个 TextView 通过 WindowManager.addView 加到屏幕,
 * 与 [FloatingControl] 同走 SYSTEM_ALERT_WINDOW 权限。
 */
class WatermarkControl(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var textView: TextView? = null
    /** 预览 TextView:设置页调节位置/大小时实时显示,离开设置页自动隐藏 */
    private var previewView: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 拍照快照配置,避免录制期间主界面修改造成每帧抖动 */
    private var timeEnabled: Boolean = false
    private var customEnabled: Boolean = false
    private var customText: String = ""

    private fun buildParams(position: Int): WindowManager.LayoutParams {
        val (gravity, x, y) = when (position) {
            WatermarkConfig.POS_TOP_LEFT -> Triple(Gravity.TOP or Gravity.START, 24, 80)
            WatermarkConfig.POS_TOP_RIGHT -> Triple(Gravity.TOP or Gravity.END, 24, 80)
            WatermarkConfig.POS_BOTTOM_LEFT -> Triple(Gravity.BOTTOM or Gravity.START, 24, 80)
            WatermarkConfig.POS_CENTER -> Triple(Gravity.CENTER, 0, 0)
            else -> Triple(Gravity.BOTTOM or Gravity.END, 24, 80) // 默认右下
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.gravity = gravity
            this.x = x
            this.y = y
        }
    }

    /** 显示水印。读取当前配置快照后绘制。
     *  若两种水印都关,直接不显示。 */
    fun show() {
        if (textView != null) return
        if (!Settings.canDrawOverlays(context)) return
        // 快照当前配置,录制期间固定
        timeEnabled = WatermarkConfig.isTimeEnabled(context)
        customEnabled = WatermarkConfig.isCustomEnabled(context)
        customText = WatermarkConfig.getCustomText(context)
        if (!timeEnabled && !customEnabled) return

        val position = WatermarkConfig.getTimePosition(context)
        val sizeSp = WatermarkConfig.getTimeSize(context)
        val tv = createTextView(sizeSp)
        if (!runCatching { wm.addView(tv, buildParams(position)) }.isSuccess) {
            // addView 失败必须清空引用:否则 show() 因 textView != null 永久直接 return,
            // 该实例再也无法显示水印。
            textView = null
            timeEnabled = false
            customEnabled = false
            return
        }
        textView = tv
        startTicking()
    }

    /** 构建水印 TextView:无底色,白色文字 + 描边阴影。
     *  录制态水印与预览态共用此构建,保证视觉一致。
     *  支持多行:时间水印在上,自定义文字在下(第二行)。
     *  关键:不接收任何触摸事件,点击穿透到下层。 */
    private fun createTextView(sizeSp: Int) = TextView(context).apply {
        setTextColor(Color.parseColor("#FFFFFFFF"))
        // 无底色填充,用描边阴影提升可读性(在浅色背景上也能看清)
        setShadowLayer(6f, 1f, 1f, Color.parseColor("#CC000000"))
        setPadding(8, 4, 8, 4)
        textSize = sizeSp.toFloat()
        typeface = Typeface.MONOSPACE
        alpha = 0.95f
        // 允许多行:时间在上,自定义文字在下(第二行)
        isSingleLine = false
        maxLines = 2
        setLineSpacing(2f, 1f)
        // 不接收触摸:多层保障,确保点击穿透
        isClickable = false
        isFocusable = false
        isFocusableInTouchMode = false
        isLongClickable = false
        setOnTouchListener(null)
    }

    /** 设置页预览:显示当前时间水印样式(实时跟随位置/大小变更)。
     *  仅当未在录制时调用,与 [show] 互斥(录制时不预览)。
     *  每秒刷新时间文字,让用户看到真实效果。 */
    fun showPreview(position: Int, sizeSp: Int) {
        if (textView != null) return // 录制中不预览
        if (previewView != null) return
        if (!Settings.canDrawOverlays(context)) return
        val tv = createTextView(sizeSp)
        previewView = tv
        runCatching {
            wm.addView(tv, buildParams(position))
            startPreviewTicking()
        }
    }

    /** 更新预览位置/大小。先移除旧 view 再以新参数重建,
     *  避免 WindowManager.updateViewLayout 在改 gravity 时位置错乱。 */
    fun updatePreview(position: Int, sizeSp: Int) {
        if (textView != null) return
        val v = previewView ?: return
        stopPreviewTicking()
        runCatching { wm.removeView(v) }
        previewView = null
        showPreview(position, sizeSp)
    }

    /** 隐藏预览(设置页退出时调用)。 */
    fun hidePreview() {
        stopPreviewTicking()
        previewView?.let { runCatching { wm.removeView(it) } }
        previewView = null
    }

    fun hide() {
        stopTicking()
        stopPreviewTicking()
        textView?.let { runCatching { wm.removeView(it) } }
        textView = null
        previewView?.let { runCatching { wm.removeView(it) } }
        previewView = null
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            textView?.text = buildText()
            // 每秒刷新时间
            handler.postAtTime(this, SystemClock.uptimeMillis() + 1000)
        }
    }

    private fun startTicking() {
        // 立即刷一次再开始计时
        textView?.text = buildText()
        handler.post(tickRunnable)
    }

    private fun stopTicking() {
        handler.removeCallbacks(tickRunnable)
    }

    /** 预览时钟:每秒刷新水印文字(时间+自定义),让设置页看到真实两行水印效果 */
    private val previewTickRunnable = object : Runnable {
        override fun run() {
            previewView?.text = buildPreviewText()
            handler.postAtTime(this, SystemClock.uptimeMillis() + 1000)
        }
    }

    private fun startPreviewTicking() {
        previewView?.text = buildPreviewText()
        handler.post(previewTickRunnable)
    }

    private fun stopPreviewTicking() {
        handler.removeCallbacks(previewTickRunnable)
    }

    /** 拼装水印文本(时间在上,自定义文字在下,第二行):
     *  - 仅时间: "2026-09-08 14:30:25"
     *  - 仅自定义: "用户文字"
     *  - 两者: 第一行=时间,第二行=自定义文字 */
    private fun buildText(): String {
        val parts = mutableListOf<String>()
        if (timeEnabled) parts += timeFmt.format(Date())
        if (customEnabled && customText.isNotEmpty()) parts += customText
        return parts.joinToString("\n")
    }

    /** 预览用:实时读取配置(未快照),让设置页看到当前开关+文字的真实两行效果。 */
    private fun buildPreviewText(): String {
        val parts = mutableListOf<String>()
        if (WatermarkConfig.isTimeEnabled(context)) parts += timeFmt.format(Date())
        val customOn = WatermarkConfig.isCustomEnabled(context)
        val custom = WatermarkConfig.getCustomText(context)
        if (customOn && custom.isNotEmpty()) parts += custom
        return parts.joinToString("\n")
    }
}
