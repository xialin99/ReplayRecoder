package com.replay.recoder.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.replay.recoder.BuildConfig
import com.replay.recoder.R

/**
 * 使用说明页(三级页):关于 → 使用说明。
 *
 * 迁自 [AboutActivity] 内嵌的说明卡片,独立成页:
 * - 应用逻辑、开关说明、悬浮球交互、快捷停录 四张卡
 * - 顶栏右上角显示当前版本号,与 [BuildConfig.VERSION_NAME] 同步
 */
class UsageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_usage)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        // 顶栏右上显示版本号,方便用户对照"此版本对应说明"
        findViewById<TextView>(R.id.tvUsageVersion).text = "v${BuildConfig.VERSION_NAME}"
    }

    companion object {
        fun launch(ctx: Context) {
            ctx.startActivity(Intent(ctx, UsageActivity::class.java))
        }
    }
}
