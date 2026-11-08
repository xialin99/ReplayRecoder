package com.replay.recoder.record

import android.content.Context
import android.content.SharedPreferences

/**
 * 录制配置(纯 SharedPreferences 持久化)。
 *
 * 持久化项:
 * - [audioSource]:录制声音源(MIC/SYSTEM/MUTE)
 * - [quality]:录制画质(HIGH/MEDIUM/LOW)
 *
 * 用法:
 *   val cfg = RecordConfig.get(ctx)
 *   val source = cfg.audioSource      // 读
 *   cfg.audioSource = AudioSource.SYSTEM  // 写
 */
class RecordConfig private constructor(private val sp: SharedPreferences) {

    enum class AudioSource(val label: String) {
        MIC("麦克风"),
        SYSTEM("系统内声"),
        MIXED("麦克风+系统声"),
        MUTE("静音");
        companion object {
            fun of(idx: Int): AudioSource = values().getOrElse(idx) { MIC }
        }
    }

    enum class Quality(val label: String, val bitrate: Int, val labelShort: String) {
        HIGH("高画质(8 Mbps)", 8_000_000, "高"),
        MEDIUM("中画质(4 Mbps)", 4_000_000, "中"),
        LOW("低画质(2 Mbps)", 2_000_000, "低");
        companion object {
            fun of(idx: Int): Quality = values().getOrElse(idx) { MEDIUM }
        }
    }

    var audioSource: AudioSource
        get() = AudioSource.of(sp.getInt(KEY_AUDIO_SOURCE, 0))
        set(v) = sp.edit().putInt(KEY_AUDIO_SOURCE, v.ordinal).apply()

    var quality: Quality
        get() = Quality.of(sp.getInt(KEY_QUALITY, 1))
        set(v) = sp.edit().putInt(KEY_QUALITY, v.ordinal).apply()

    /** 停录后自动重启循环录制(默认 true):冻结事件保存后 3 秒自动开始下一段录制,
     *  适合"录一段→定格→再录一段"的连续复现场景;关掉则停录后等待手动开录。 */
    var autoRestart: Boolean
        get() = sp.getBoolean(KEY_AUTO_RESTART, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_RESTART, v).apply()

    companion object {
        private const val PREF = "record_config"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_QUALITY = "quality"
        private const val KEY_AUTO_RESTART = "auto_restart"

        @Volatile private var instance: RecordConfig? = null
        fun get(ctx: Context): RecordConfig =
            instance ?: synchronized(this) {
                instance ?: RecordConfig(
                    ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
