package com.replay.recoder.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.replay.recoder.R
import com.replay.recoder.record.RecordConfig

/**
 * 录制设置页(三级页面):主界面 → 设置 → 录制设置。
 *
 * 负责两个开关:
 * - 录音源:麦克风 / 系统内声 / 静音
 * - 录制画质:高 / 中 / 低 (决定视频码率)
 *
 * 配置实时持久化,下次启动录制即生效。
 */
class RecordSettingsActivity : AppCompatActivity() {

    private lateinit var rgAudio: RadioGroup
    private lateinit var rgQuality: RadioGroup
    private val cfg by lazy { RecordConfig.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record_settings)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        rgAudio = findViewById(R.id.rgAudioSource)
        rgQuality = findViewById(R.id.rgQuality)

        // 读当前配置回显选中项
        when (cfg.audioSource) {
            RecordConfig.AudioSource.MIC -> rgAudio.check(R.id.rbMic)
            RecordConfig.AudioSource.SYSTEM -> rgAudio.check(R.id.rbSystem)
            RecordConfig.AudioSource.MIXED -> rgAudio.check(R.id.rbMixed)
            RecordConfig.AudioSource.MUTE -> rgAudio.check(R.id.rbMute)
        }
        when (cfg.quality) {
            RecordConfig.Quality.HIGH -> rgQuality.check(R.id.rbHigh)
            RecordConfig.Quality.MEDIUM -> rgQuality.check(R.id.rbMedium)
            RecordConfig.Quality.LOW -> rgQuality.check(R.id.rbLow)
        }

        // 选即写,立即生效(下次录制读取)
        rgAudio.setOnCheckedChangeListener { _, checkedId ->
            val src = when (checkedId) {
                R.id.rbMic -> RecordConfig.AudioSource.MIC
                R.id.rbSystem -> RecordConfig.AudioSource.SYSTEM
                R.id.rbMixed -> RecordConfig.AudioSource.MIXED
                R.id.rbMute -> RecordConfig.AudioSource.MUTE
                else -> RecordConfig.AudioSource.SYSTEM
            }
            cfg.audioSource = src
        }
        rgQuality.setOnCheckedChangeListener { _, checkedId ->
            val q = when (checkedId) {
                R.id.rbHigh -> RecordConfig.Quality.HIGH
                R.id.rbMedium -> RecordConfig.Quality.MEDIUM
                R.id.rbLow -> RecordConfig.Quality.LOW
                else -> RecordConfig.Quality.MEDIUM
            }
            cfg.quality = q
        }
    }

    companion object {
        fun launch(ctx: Context) {
            ctx.startActivity(Intent(ctx, RecordSettingsActivity::class.java))
        }
    }
}
