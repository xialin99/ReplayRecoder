package com.replay.recoder.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.replay.recoder.R

/**
 * 关于页(功能页):版本号 + 制作人 + 包名。
 *
 * 版本号从 [com.replay.recoder.BuildConfig.VERSION_NAME] 读取,
 * 与 build.gradle 的 versionName 自动同步,改版本号只需改 build.gradle。
 * 制作人来自 strings.xml(用户可改)。
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // 版本号直接读 BuildConfig.versionName,与 build.gradle 自动同步,不再手动改 strings
        val version = com.replay.recoder.BuildConfig.VERSION_NAME
        findViewById<android.widget.TextView>(R.id.tvVersionDetail).text = version
        // 构建类型(debug/release),从 BuildConfig 读取
        findViewById<android.widget.TextView>(R.id.tvBuildType).text = com.replay.recoder.BuildConfig.BUILD_TYPE
        // 编译时间仅在 debug 版本显示,release 隐藏整行
        val buildTimeRow = findViewById<android.view.View>(R.id.buildTimeRow)
        if (com.replay.recoder.BuildConfig.DEBUG) {
            findViewById<android.widget.TextView>(R.id.tvBuildTime).text = com.replay.recoder.BuildConfig.BUILD_TIME
        } else {
            buildTimeRow.visibility = android.view.View.GONE
        }
        findViewById<android.widget.TextView>(R.id.tvPackage).text = packageName
        // 使用说明入口副标题带版本号,提醒"此说明对应版本"
        val usageHint = findViewById<android.widget.TextView?>(R.id.tvUsageHint)
        if (usageHint != null) {
            usageHint.text = "${getString(R.string.usage_hint)}(v$version)"
        }

        // 使用说明入口:跳转三级页
        findViewById<android.view.View>(R.id.rowUsage).setOnClickListener {
            UsageActivity.launch(this)
        }
    }
}
