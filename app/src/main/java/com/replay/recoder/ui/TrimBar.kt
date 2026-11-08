package com.replay.recoder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 剪辑范围条(关键帧时间轴):
 * - 背景按等分方格平铺视频关键帧缩略图,直观定位画面
 * - 首尾各一枚可拖动滑块,拖动实时回调区间变化
 * - 选中区间外压暗遮罩 + 白色边界线,一眼看清裁剪范围
 *
 * 坐标用 0..1 占比表达,由使用方与视频时长换算毫秒。
 */
class TrimBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** @param draggingStart true=正在拖起点滑块;dragging true=拖动中(可实时预览),false=本次拖动结束 */
    fun interface OnRangeChangeListener {
        fun onRangeChanged(startRatio: Float, endRatio: Float, draggingStart: Boolean, dragging: Boolean)
    }

    var listener: OnRangeChangeListener? = null

    /** 两滑块最小间距(占比),防止选出 0 时长 */
    var minGapRatio: Float = 0.02f

    private var startRatio = 0f
    private var endRatio = 1f
    private var thumbnails: List<Bitmap> = emptyList()

    private val dimPaint = Paint().apply { color = 0xA6000000.toInt() }
    private val placeholderPaint = Paint().apply { color = 0xFF242424.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2E7DFF.toInt() }
    private val handleRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dp(2f)
    }

    private var draggingStart = false
    private var dragActive = false

    /** 设置关键帧缩略图(数量与格子数一致时效果最佳,超出会循环取用)。 */
    fun setThumbnails(list: List<Bitmap>) {
        thumbnails = list
        invalidate()
    }

    /** 用占比同步区间(设起点/终点按钮、重置时调用)。 */
    fun setRange(start: Float, end: Float) {
        startRatio = start.coerceIn(0f, 1f)
        endRatio = end.coerceIn((startRatio + minGapRatio).coerceAtMost(1f), 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 1. 关键帧缩略图平铺(方格居中裁切)
        if (thumbnails.isEmpty()) {
            canvas.drawRect(0f, 0f, w, h, placeholderPaint)
        } else {
            val cell = h
            val count = ceil((w / cell).toDouble()).toInt()
            for (i in 0 until count) {
                val bmp = thumbnails[i % thumbnails.size]
                val left = i * cell
                val side = minOf(bmp.width, bmp.height)
                val sx = (bmp.width - side) / 2
                val sy = (bmp.height - side) / 2
                canvas.drawBitmap(
                    bmp,
                    Rect(sx, sy, sx + side, sy + side),
                    RectF(left, 0f, left + cell, h),
                    null
                )
            }
        }

        // 2. 区间外遮罩
        val sxp = startRatio * w
        val exp = endRatio * w
        if (sxp > 0f) canvas.drawRect(0f, 0f, sxp, h, dimPaint)
        if (exp < w) canvas.drawRect(exp, 0f, w, h, dimPaint)

        // 3. 边界线 + 滑块
        canvas.drawLine(sxp, 0f, sxp, h, borderPaint)
        canvas.drawLine(exp, 0f, exp, h, borderPaint)
        drawHandle(canvas, sxp)
        drawHandle(canvas, exp)
    }

    private fun drawHandle(canvas: Canvas, x: Float) {
        val cy = height / 2f
        val r = dp(13f)
        canvas.drawCircle(x, cy, r, handleFillPaint)
        canvas.drawCircle(x, cy, r, handleRingPaint)
        // 握把竖纹
        canvas.drawLine(x - dp(3f), cy - dp(5f), x - dp(3f), cy + dp(5f), gripPaint)
        canvas.drawLine(x + dp(3f), cy - dp(5f), x + dp(3f), cy + dp(5f), gripPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width.toFloat()
        if (w <= 0f) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dS = abs(event.x - startRatio * w)
                val dE = abs(event.x - endRatio * w)
                val touch = dp(30f)
                if (dS > touch && dE > touch) return false
                draggingStart = if (dS <= touch && dE <= touch) dS <= dE else dS <= touch
                dragActive = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragActive) return false
                val ratio = (event.x / w).coerceIn(0f, 1f)
                if (draggingStart) {
                    startRatio = ratio.coerceAtMost(endRatio - minGapRatio).coerceAtLeast(0f)
                } else {
                    endRatio = ratio.coerceAtLeast(startRatio + minGapRatio).coerceAtMost(1f)
                }
                listener?.onRangeChanged(startRatio, endRatio, draggingStart, true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragActive) {
                    dragActive = false
                    listener?.onRangeChanged(startRatio, endRatio, draggingStart, false)
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
            }
            else -> return false
        }
        return true
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
