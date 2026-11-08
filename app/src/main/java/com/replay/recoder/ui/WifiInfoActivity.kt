package com.replay.recoder.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.replay.recoder.R

/**
 * WiFi 信号强度查看页(三级菜单)。
 *
 * 显示当前连接 WiFi 的实时信息:RSSI(dBm)、信号等级、SSID、BSSID、速率、频段/信道、IP、状态。
 * 数据来源 [WifiManager.getConnectionInfo],通过系统广播(RSSI 变化/网络状态变化)与
 * 每 2 秒轮询双驱动刷新。
 *
 * 权限说明:RSSI 数值无需定位权限;SSID/BSSID 在 Android 10+ 需要 ACCESS_FINE_LOCATION,
 * 未授权时降级显示"未知",并提供授权入口。该权限仅此工具页使用,不影响录屏主流程。
 */
class WifiInfoActivity : AppCompatActivity() {

    private lateinit var wifiManager: WifiManager
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var tvRssi: TextView
    private lateinit var tvQuality: TextView
    private lateinit var cardPermission: View
    private val rowValues = HashMap<Int, TextView>()

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000L)
        }
    }

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // 无论授权与否都刷新:授权后显示 SSID,拒绝则降级
            cardPermission.visibility = View.GONE
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wifi_info)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }

        tvRssi = findViewById(R.id.tvRssi)
        tvQuality = findViewById(R.id.tvQuality)
        cardPermission = findViewById(R.id.cardPermission)

        bindRow(R.id.rowSsid, R.string.wifi_ssid)
        bindRow(R.id.rowBssid, R.string.wifi_bssid)
        bindRow(R.id.rowSpeed, R.string.wifi_speed)
        bindRow(R.id.rowFrequency, R.string.wifi_frequency)
        bindRow(R.id.rowChannel, R.string.wifi_channel)
        bindRow(R.id.rowIp, R.string.wifi_ip)
        bindRow(R.id.rowState, R.string.wifi_state)

        findViewById<View>(R.id.btnGrantLocation).setOnClickListener {
            requestLocationPermission()
        }
    }

    private fun bindRow(rowId: Int, labelRes: Int) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.tvLabel).setText(labelRes)
        rowValues[rowId] = row.findViewById(R.id.tvValue)
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            this, wifiReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.post(refreshRunnable)
        refreshPermissionCard()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
        runCatching { unregisterReceiver(wifiReceiver) }
    }

    private fun refreshPermissionCard() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        cardPermission.visibility = if (granted) View.GONE else View.VISIBLE
    }

    private fun requestLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private fun refresh() {
        // 所有 WiFi API 调用都可能抛 SecurityException(权限)或 RemoteException,
        // 统一兜底:任何异常都降级为占位显示,绝不让工具页闪退
        try {
            if (!wifiManager.isWifiEnabled) {
                showPlaceholder(stateText = "WiFi 已关闭")
                return
            }

            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            // networkId == -1 且 BSSID 为占位地址表示当前未连接任何网络
            val connected = info != null && info.networkId != -1 &&
                    info.bssid != null && info.bssid != "02:00:00:00:00:00"

            setValue(R.id.rowState, if (connected) "已连接" else "未连接")
            if (!connected) {
                showPlaceholder(stateText = "未连接")
                return
            }

            val rssi = info.rssi
            tvRssi.text = "$rssi dBm"
            tvQuality.text = qualityText(rssi)

            // SSID 带引号且需要定位权限
            val rawSsid = info.ssid
            val ssid = when {
                !hasLocationPermission() -> getString(R.string.wifi_unknown)
                rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>" -> "未知"
                else -> rawSsid.removePrefix("\"").removeSuffix("\"")
            }
            setValue(R.id.rowSsid, ssid)
            setValue(R.id.rowBssid, if (hasLocationPermission()) info.bssid ?: "--" else getString(R.string.wifi_unknown))
            setValue(R.id.rowSpeed, "${info.linkSpeed} Mbps")

            val freqMHz = info.frequency
            if (freqMHz > 0) {
                setValue(R.id.rowFrequency, "$freqMHz MHz · ${bandText(freqMHz)}")
                setValue(R.id.rowChannel, channelOf(freqMHz)?.toString() ?: "--")
            } else {
                setValue(R.id.rowFrequency, "--")
                setValue(R.id.rowChannel, "--")
            }

            @Suppress("DEPRECATION")
            val ip = Formatter.formatIpAddress(info.ipAddress)
            setValue(R.id.rowIp, if (ip == "0.0.0.0") "--" else ip)
        } catch (t: Throwable) {
            android.util.Log.e("WifiInfoActivity", "读取 WiFi 信息失败", t)
            showPlaceholder(stateText = "不可用")
        }
    }

    /** 统一占位显示:无连接/WiFi 关闭/读取失败时重置各字段 */
    private fun showPlaceholder(stateText: String) {
        tvRssi.text = "-- dBm"
        tvQuality.setText(R.string.wifi_not_connected)
        setValue(R.id.rowSsid, "--")
        setValue(R.id.rowBssid, "--")
        setValue(R.id.rowSpeed, "--")
        setValue(R.id.rowFrequency, "--")
        setValue(R.id.rowChannel, "--")
        setValue(R.id.rowIp, "--")
        setValue(R.id.rowState, stateText)
    }

    private fun setValue(rowId: Int, value: String) {
        rowValues[rowId]?.text = value
    }

    /** RSSI 等级:>=-55 极强,>=-66 强,>=-77 一般,>=-88 弱,更差 极弱 */
    private fun qualityText(rssi: Int): String = when {
        rssi >= -55 -> "极强"
        rssi >= -66 -> "强"
        rssi >= -77 -> "一般"
        rssi >= -88 -> "弱"
        else -> "极弱"
    }

    private fun bandText(freqMHz: Int): String = when {
        freqMHz in 2400..2500 -> "2.4GHz"
        freqMHz in 4900..5900 -> "5GHz"
        freqMHz in 5925..7125 -> "6GHz"
        else -> "未知频段"
    }

    /** 由中心频率换算信道号 */
    private fun channelOf(freqMHz: Int): Int? = when {
        freqMHz == 2484 -> 14
        freqMHz in 2412..2472 -> (freqMHz - 2412) / 5 + 1
        freqMHz in 4900..5900 -> (freqMHz - 5000) / 5
        freqMHz in 5925..7125 -> (freqMHz - 5950) / 5
        else -> null
    }
}
