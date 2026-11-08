package com.replay.recoder.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.replay.recoder.R

/**
 * 手机信息页(三级菜单):品牌、型号、Android 版本、SDK、屏幕分辨率、DPI、刷新率、
 * 电量、电池温度、充电状态、存储容量。支持一键复制全部到剪贴板。
 *
 * 数据来源:[Build]、Resources DisplayMetrics、WindowMetrics、BatteryManager、StatFs。
 */
class DeviceInfoActivity : AppCompatActivity() {

    /** rowId -> (labelRes, value),复制全部时按插入顺序拼接 */
    private val rowData = LinkedHashMap<Int, Pair<Int, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_info)

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnCopy).setOnClickListener { copyAll() }

        bindRow(R.id.rowBrand, R.string.di_brand, Build.BRAND.ifBlank { "unknown" })
        bindRow(R.id.rowModel, R.string.di_model, Build.MODEL.ifBlank { "unknown" })
        bindRow(
            R.id.rowAndroid,
            R.string.di_android,
            Build.VERSION.RELEASE?.let { "Android $it" } ?: "--"
        )
        bindRow(R.id.rowSdk, R.string.di_sdk, "API ${Build.VERSION.SDK_INT}")
        bindRow(R.id.rowScreen, R.string.di_screen, screenResolution())
        bindRow(R.id.rowDpi, R.string.di_dpi, "${resources.displayMetrics.densityDpi} dpi")
        bindRow(R.id.rowRefresh, R.string.di_refresh, refreshRate())

        val battery = readBattery()
        bindRow(
            R.id.rowBattery,
            R.string.di_battery,
            if (battery.capacity != Int.MIN_VALUE) "${battery.capacity}%" else "--"
        )
        bindRow(
            R.id.rowBatteryTemp,
            R.string.di_battery_temp,
            if (battery.tempC.isNaN()) "--" else "%.1f ℃".format(battery.tempC)
        )
        bindRow(
            R.id.rowCharging,
            R.string.di_charging,
            getString(if (battery.charging) R.string.di_charging_yes else R.string.di_charging_no)
        )

        val storage = StatFs(filesDir.path)
        bindRow(
            R.id.rowStorageTotal,
            R.string.di_storage_total,
            "%.1f GB".format(storage.totalBytes / GB_F)
        )
        bindRow(
            R.id.rowStorageAvail,
            R.string.di_storage_avail,
            "%.1f GB".format(storage.availableBytes / GB_F)
        )
    }

    private fun bindRow(rowId: Int, labelRes: Int, value: String) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.tvLabel).setText(labelRes)
        row.findViewById<TextView>(R.id.tvValue).text = value
        rowData[rowId] = Pair(labelRes, value)
    }

    /** 物理屏幕分辨率:API 30+ 用 WindowMetrics,低版本用 getRealMetrics */
    private fun screenResolution(): String = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            "${bounds.width()} × ${bounds.height()}"
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            "${dm.widthPixels} × ${dm.heightPixels}"
        }
    } catch (t: Throwable) {
        android.util.Log.e("DeviceInfo", "读取分辨率失败", t)
        val dm = resources.displayMetrics
        "${dm.widthPixels} × ${dm.heightPixels}"
    }

    private fun refreshRate(): String = try {
        val rate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.refreshRate ?: 0f
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate
        }
        if (rate > 0f) "%.0f Hz".format(rate) else "--"
    } catch (t: Throwable) {
        "--"
    }

    /** 通过 BatteryManager 属性读电量,通过粘性广播读温度与充电状态 */
    private fun readBattery(): BatteryInfo {
        var capacity = Int.MIN_VALUE
        try {
            val bm = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            capacity = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?: Int.MIN_VALUE
        } catch (t: Throwable) {
            android.util.Log.e("DeviceInfo", "读取电量失败", t)
        }

        var tempC = Double.NaN
        var charging = false
        try {
            val intent: Intent? =
                applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) {
                val tempTenth = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                if (tempTenth != Int.MIN_VALUE) tempC = tempTenth / 10.0
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (t: Throwable) {
            android.util.Log.e("DeviceInfo", "读取电池广播失败", t)
        }
        return BatteryInfo(capacity, tempC, charging)
    }

    private fun copyAll() {
        val text = rowData.values.joinToString("\n") { (labelRes, value) ->
            "${getString(labelRes)}: $value"
        }
        try {
            val clipboard =
                getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("device_info", text))
            Toast.makeText(this, R.string.di_copied, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            android.util.Log.e("DeviceInfo", "复制失败", t)
        }
    }

    private data class BatteryInfo(
        val capacity: Int,
        val tempC: Double,
        val charging: Boolean
    )

    companion object {
        private const val GB_F = 1024.0 * 1024 * 1024
    }
}
