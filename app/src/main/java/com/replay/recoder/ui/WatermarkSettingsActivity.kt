package com.replay.recoder.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.replay.recoder.R
import com.replay.recoder.record.WatermarkConfig

/**
 * 水印设置页(三级页面):主界面 → 设置 → 水印设置。
 *
 * 迁自 [SettingsActivity],负责:
 * - 时间水印开关 / 位置(5档) / 字号(10-30sp)
 * - 自定义文字水印开关 / 内容
 * - 实时预览(进入即显示,离开隐藏)
 */
class WatermarkSettingsActivity : AppCompatActivity() {

    private lateinit var switchTime: SwitchCompat
    private lateinit var switchCustom: SwitchCompat
    private lateinit var etCustom: EditText
    private lateinit var rgPosition: RadioGroup
    private lateinit var seekSize: SeekBar
    private lateinit var tvSizeLabel: TextView

    /** 水印预览:调节位置/大小时实时显示效果,退出设置页隐藏 */
    private var watermarkPreview: WatermarkControl? = null
    private var previewVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_watermark_settings)

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        switchTime = findViewById(R.id.switchWatermarkTime)
        switchCustom = findViewById(R.id.switchWatermarkCustom)
        etCustom = findViewById(R.id.etWatermarkCustom)
        rgPosition = findViewById(R.id.rgTimePosition)
        seekSize = findViewById(R.id.seekTimeSize)
        tvSizeLabel = findViewById(R.id.tvTimeSizeLabel)

        // 回填当前配置
        switchTime.isChecked = WatermarkConfig.isTimeEnabled(this)
        switchCustom.isChecked = WatermarkConfig.isCustomEnabled(this)
        etCustom.setText(WatermarkConfig.getCustomText(this))
        etCustom.isEnabled = switchCustom.isChecked

        // 回填时间水印位置
        val pos = WatermarkConfig.getTimePosition(this)
        val posId = when (pos) {
            WatermarkConfig.POS_TOP_LEFT -> R.id.rbPosTopLeft
            WatermarkConfig.POS_TOP_RIGHT -> R.id.rbPosTopRight
            WatermarkConfig.POS_BOTTOM_LEFT -> R.id.rbPosBottomLeft
            WatermarkConfig.POS_CENTER -> R.id.rbPosCenter
            else -> R.id.rbPosBottomRight
        }
        rgPosition.check(posId)
        rgPosition.setOnCheckedChangeListener { _, checkedId ->
            val v = when (checkedId) {
                R.id.rbPosTopLeft -> WatermarkConfig.POS_TOP_LEFT
                R.id.rbPosTopRight -> WatermarkConfig.POS_TOP_RIGHT
                R.id.rbPosBottomLeft -> WatermarkConfig.POS_BOTTOM_LEFT
                R.id.rbPosCenter -> WatermarkConfig.POS_CENTER
                else -> WatermarkConfig.POS_BOTTOM_RIGHT
            }
            WatermarkConfig.setTimePosition(this, v)
            updatePreview(v, WatermarkConfig.getTimeSize(this))
        }

        // 回填时间水印大小
        val sizeSp = WatermarkConfig.getTimeSize(this)
        seekSize.progress = sizeSp
        tvSizeLabel.text = getString(R.string.watermark_size, sizeSp)
        seekSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress.coerceIn(10, 30)
                tvSizeLabel.text = getString(R.string.watermark_size, v)
                updatePreview(WatermarkConfig.getTimePosition(this@WatermarkSettingsActivity), v)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = (sb?.progress ?: 13).coerceIn(10, 30)
                WatermarkConfig.setTimeSize(this@WatermarkSettingsActivity, v)
            }
        })

        switchTime.setOnCheckedChangeListener { _, checked ->
            WatermarkConfig.setTimeEnabled(this, checked)
        }
        switchCustom.setOnCheckedChangeListener { _, checked ->
            WatermarkConfig.setCustomEnabled(this, checked)
            etCustom.isEnabled = checked
        }
        etCustom.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                WatermarkConfig.setCustomText(this, etCustom.text?.toString().orEmpty())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 进入即显示预览(若已授权悬浮窗),让用户立刻看到当前水印效果
        showPreview(WatermarkConfig.getTimePosition(this), WatermarkConfig.getTimeSize(this))
    }

    override fun onStop() {
        super.onStop()
        // 兜底:防止用户输入后未失焦就返回,导致自定义水印文字没存上
        WatermarkConfig.setCustomText(this, etCustom.text?.toString().orEmpty())
        // 离开:隐藏预览(避免叠加到其他 App)
        hidePreview()
    }

    /** 显示预览:仅授权悬浮窗权限时生效,未授权静默跳过(不报错)。 */
    private fun showPreview(position: Int, sizeSp: Int) {
        if (android.provider.Settings.canDrawOverlays(this)) {
            if (watermarkPreview == null) {
                watermarkPreview = WatermarkControl(this)
            }
            watermarkPreview?.showPreview(position, sizeSp)
            previewVisible = true
        }
    }

    /** 更新预览位置/大小,显示时即时刷新。 */
    private fun updatePreview(position: Int, sizeSp: Int) {
        if (!previewVisible) return
        watermarkPreview?.updatePreview(position, sizeSp)
    }

    private fun hidePreview() {
        watermarkPreview?.hidePreview()
        watermarkPreview = null
        previewVisible = false
    }

    companion object {
        fun launch(ctx: Context) {
            ctx.startActivity(Intent(ctx, WatermarkSettingsActivity::class.java))
        }
    }
}
