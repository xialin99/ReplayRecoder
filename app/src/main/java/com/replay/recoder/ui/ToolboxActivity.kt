package com.replay.recoder.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.replay.recoder.R

/**
 * 工具箱(二级菜单):WiFi 信号强度 / BLE 信号强度 / 网络诊断 / 噪音计 / 秒表 / 手机信息。
 *
 * 工具页内的工具与录屏主流程解耦:各自独立 Activity,权限在各自工具页内按需申请。
 */
class ToolboxActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toolbox)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        bindRow(
            R.id.rowWifi,
            R.drawable.ic_wifi,
            R.string.tool_wifi_title,
            R.string.tool_wifi_hint
        ) { startActivity(Intent(this, WifiInfoActivity::class.java)) }

        bindRow(
            R.id.rowBle,
            R.drawable.ic_bluetooth,
            R.string.tool_ble_title,
            R.string.tool_ble_hint
        ) { startActivity(Intent(this, BleRssiActivity::class.java)) }

        bindRow(
            R.id.rowNetwork,
            R.drawable.ic_network_diag,
            R.string.tool_network_title,
            R.string.tool_network_hint
        ) { startActivity(Intent(this, NetworkDiagnosticsActivity::class.java)) }

        bindRow(
            R.id.rowDecibel,
            R.drawable.ic_decibel,
            R.string.tool_decibel_title,
            R.string.tool_decibel_hint
        ) { startActivity(Intent(this, DecibelActivity::class.java)) }

        bindRow(
            R.id.rowStopwatch,
            R.drawable.ic_stopwatch,
            R.string.tool_stopwatch_title,
            R.string.tool_stopwatch_hint
        ) { startActivity(Intent(this, StopwatchActivity::class.java)) }

        bindRow(
            R.id.rowDeviceInfo,
            R.drawable.ic_device_info,
            R.string.tool_device_info_title,
            R.string.tool_device_info_hint
        ) { startActivity(Intent(this, DeviceInfoActivity::class.java)) }

        bindRow(
            R.id.rowSsh,
            R.drawable.ic_ssh,
            R.string.tool_ssh_title,
            R.string.tool_ssh_hint
        ) { startActivity(Intent(this, SshToolActivity::class.java)) }
    }

    private fun bindRow(
        rowId: Int,
        iconRes: Int,
        titleRes: Int,
        hintRes: Int,
        onClick: () -> Unit
    ) {
        val row = findViewById<android.view.View>(rowId)
        row.findViewById<ImageView>(R.id.imgIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.tvTitle).setText(titleRes)
        row.findViewById<TextView>(R.id.tvHint).setText(hintRes)
        row.setOnClickListener { onClick() }
    }
}
