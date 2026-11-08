package com.replay.recoder.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.replay.recoder.R
import com.replay.recoder.record.WatermarkConfig

/**
 * 设置页(二级页面):水印入口 + 悬浮球常驻开关。
 *
 * 水印开关/位置/大小/自定义文字 → 迁入 [WatermarkSettingsActivity](三级)。
 * 本页只保留水印设置入口卡片(点击进入三级页)与悬浮球开关。
 * 配置通过 [WatermarkConfig] 持久化,悬浮球开关变更后回到首页按 onResume 生效。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var switchFloating: SwitchCompat

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }
        // 水印设置入口:跳转三级页
        findViewById<android.view.View>(R.id.rowWatermark).setOnClickListener {
            WatermarkSettingsActivity.launch(this)
        }
        // 录制设置入口(声音源 + 画质):跳转三级页
        findViewById<android.view.View>(R.id.rowRecord).setOnClickListener {
            RecordSettingsActivity.launch(this)
        }
        // 关于本应用入口(DS 阶段7:从首页挪到设置页底部)
        findViewById<android.view.View>(R.id.rowAbout).setOnClickListener {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }

        switchFloating = findViewById(R.id.switchFloatingPersistent)
        switchFloating.isChecked = WatermarkConfig.isFloatingPersistent(this)
        // 悬浮球开关:写配置即可。
        // 常驻悬浮球由 [MainActivity] 在 onResume 时按配置显示/隐藏,
        // 这里只持久化,回到首页自动生效;若首页在前台,通过 onResume 刷新。
        switchFloating.setOnCheckedChangeListener { _, checked ->
            WatermarkConfig.setFloatingPersistent(this, checked)
        }
    }
}
