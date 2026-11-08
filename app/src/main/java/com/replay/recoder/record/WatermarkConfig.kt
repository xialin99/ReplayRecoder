package com.replay.recoder.record

import android.content.Context
import android.content.SharedPreferences

/**
 * 水印配置:时间水印 + 自定义文字水印,各自独立开关。
 * 持久化到 SharedPreferences,主界面设置后录制服务读取。
 *
 * 水印通过 WindowManager overlay 绘制到屏幕(MediaProjection 会采集到 overlay),
 * 无需修改 MediaCodec/OpenGL 管线。
 */
object WatermarkConfig {
    private const val PREFS = "watermark_prefs"
    private const val KEY_TIME_ENABLED = "time_enabled"
    private const val KEY_CUSTOM_ENABLED = "custom_enabled"
    private const val KEY_CUSTOM_TEXT = "custom_text"
    private const val KEY_FLOATING_PERSISTENT = "floating_persistent"
    /** 时间水印位置:0=左上,1=右上,2=左下,3=右下,4=中心 */
    private const val KEY_TIME_POSITION = "time_position"
    /** 时间水印字号(sp),默认 13 */
    private const val KEY_TIME_SIZE = "time_size"

    /** 位置取值范围 */
    const val POS_TOP_LEFT = 0
    const val POS_TOP_RIGHT = 1
    const val POS_BOTTOM_LEFT = 2
    const val POS_BOTTOM_RIGHT = 3
    const val POS_CENTER = 4

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 时间水印开关 */
    fun isTimeEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_TIME_ENABLED, true)

    fun setTimeEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_TIME_ENABLED, enabled).apply()
    }

    /** 自定义文字水印开关 */
    fun isCustomEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_CUSTOM_ENABLED, false)

    fun setCustomEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CUSTOM_ENABLED, enabled).apply()
    }

    /** 自定义水印文字 */
    fun getCustomText(ctx: Context): String =
        prefs(ctx).getString(KEY_CUSTOM_TEXT, "") ?: ""

    fun setCustomText(ctx: Context, text: String) {
        prefs(ctx).edit().putString(KEY_CUSTOM_TEXT, text).apply()
    }

    /** 悬浮球常驻开关:
     *  - true(默认):悬浮球一直显示(非录制时静态显示,长按开始录制,单击打开 App)
     *  - false:仅在录制时显示 */
    fun isFloatingPersistent(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FLOATING_PERSISTENT, true)

    fun setFloatingPersistent(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FLOATING_PERSISTENT, enabled).apply()
    }

    /** 时间水印位置:0=左上,1=右上,2=左下,3=右下,4=中心 */
    fun getTimePosition(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_TIME_POSITION, POS_BOTTOM_RIGHT)
        return if (v in POS_TOP_LEFT..POS_CENTER) v else POS_BOTTOM_RIGHT
    }

    fun setTimePosition(ctx: Context, position: Int) {
        val v = if (position in POS_TOP_LEFT..POS_CENTER) position else POS_BOTTOM_RIGHT
        prefs(ctx).edit().putInt(KEY_TIME_POSITION, v).apply()
    }

    /** 时间水印字号(sp),范围 10..30,默认 13 */
    fun getTimeSize(ctx: Context): Int {
        val v = prefs(ctx).getInt(KEY_TIME_SIZE, 13)
        return v.coerceIn(10, 30)
    }

    fun setTimeSize(ctx: Context, sizeSp: Int) {
        val v = sizeSp.coerceIn(10, 30)
        prefs(ctx).edit().putInt(KEY_TIME_SIZE, v).apply()
    }

    /** 是否有任一水印启用 */
    fun anyEnabled(ctx: Context): Boolean =
        isTimeEnabled(ctx) || isCustomEnabled(ctx)
}
