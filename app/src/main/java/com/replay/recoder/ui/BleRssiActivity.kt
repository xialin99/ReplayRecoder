package com.replay.recoder.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.replay.recoder.R

/**
 * 蓝牙 BLE 信号强度页(三级菜单)。
 *
 * 扫描周边 BLE 设备,按 MAC 去重显示设备名 / MAC / 实时 RSSI(dBm),
 * 名称命中扫地机常见关键词(roborock/dreame/ecovacs/roomba/石头/追觅/科沃斯等)时标记"疑似扫地机"。
 *
 * 权限:
 * - Android 12+(API 31):BLUETOOTH_SCAN + BLUETOOTH_CONNECT 运行时权限;
 * - Android 12 以下:ACCESS_FINE_LOCATION 运行时权限。
 * 进入页面自动开始扫描,离开自动停止(持续 BLE 扫描耗电)。
 */
class BleRssiActivity : AppCompatActivity() {

    private data class DeviceEntry(
        val name: String,
        val mac: String,
        val rssi: Int,
        val robot: Boolean
    )

    private lateinit var tvStatus: TextView
    private lateinit var btnScan: MaterialButton
    private lateinit var cardPermission: View
    private lateinit var deviceAdapter: BleDeviceAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false

    /** MAC -> 设备,保留首次发现顺序,展示时按 RSSI 排序 */
    private val devices = LinkedHashMap<String, DeviceEntry>()

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** 扫地机常见品牌/关键词,大小写不敏感 */
    private val robotKeywords = listOf(
        "robot", "roborock", "clean", "sweep", "vacuum",
        "dreame", "ecovacs", "cobos", "deebot", "roomba", "irobot",
        "tineco", "narwal", "石头", "追觅", "科沃斯", "云鲸", "小米扫"
    )

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.isNotEmpty() && result.values.all { it }) {
                cardPermission.visibility = View.GONE
                startScan()
            } else {
                cardPermission.visibility = View.VISIBLE
                Toast.makeText(this, R.string.ble_permission_denied, Toast.LENGTH_SHORT).show()
            }
            updateUi()
        }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> {
                    updateUi()
                    if (hasPermissions()) startScan()
                }
                BluetoothAdapter.STATE_OFF -> {
                    stopScan()
                    updateUi()
                }
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { handleResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            android.util.Log.e(TAG, "BLE 扫描失败 errorCode=$errorCode")
            if (scanning) {
                scanning = false
                updateUi()
            }
        }
    }

    /** 列表刷新节流:扫描回调可能高频触发,400ms 合并一次 */
    private val refreshRunnable = Runnable {
        deviceAdapter.submit(devices.values.sortedByDescending { it.rssi })
        updateUi()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ble_rssi)

        bluetoothAdapter =
            (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }
        tvStatus = findViewById(R.id.tvStatus)
        btnScan = findViewById(R.id.btnScan)
        cardPermission = findViewById(R.id.cardPermission)

        deviceAdapter = BleDeviceAdapter()
        findViewById<RecyclerView>(R.id.devicesList).apply {
            layoutManager = LinearLayoutManager(this@BleRssiActivity)
            adapter = deviceAdapter
        }

        btnScan.setOnClickListener {
            if (scanning) stopScan() else startScan()
        }
        findViewById<View>(R.id.btnGrant).setOnClickListener {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onStart() {
        super.onStart()
        // 监听蓝牙开关:用户从系统设置打开蓝牙后自动续扫
        try {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(bluetoothStateReceiver, filter)
            }
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "注册蓝牙状态监听失败", t)
        }
    }

    override fun onResume() {
        super.onResume()
        updateUi()
        // 进入页面自动扫描
        if (hasPermissions() && isBluetoothOn()) startScan()
    }

    override fun onPause() {
        super.onPause()
        // 离开页面自动停止,避免持续耗电
        stopScan()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(bluetoothStateReceiver)
        } catch (_: Throwable) {
        }
    }

    private fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBluetoothOn(): Boolean = try {
        bluetoothAdapter?.isEnabled == true
    } catch (t: Throwable) {
        // 部分机型 isEnabled 也可能抛 SecurityException
        android.util.Log.e(TAG, "读取蓝牙状态失败", t)
        false
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (scanning) return
        if (!hasPermissions()) {
            cardPermission.visibility = View.VISIBLE
            updateUi()
            return
        }
        if (!isBluetoothOn()) {
            updateUi()
            return
        }
        val s = bluetoothAdapter?.bluetoothLeScanner
        if (s == null) {
            android.util.Log.e(TAG, "BluetoothLeScanner 不可用")
            updateUi()
            return
        }
        scanner = s
        // 重新扫描:清空旧结果
        devices.clear()
        deviceAdapter.submit(emptyList())
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            s.startScan(null, settings, scanCallback)
            scanning = true
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "启动 BLE 扫描失败", t)
            scanning = false
            Toast.makeText(this, R.string.ble_permission_denied, Toast.LENGTH_SHORT).show()
        }
        updateUi()
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "停止 BLE 扫描失败", t)
        }
        handler.removeCallbacks(refreshRunnable)
        // 停止时立即刷新最终列表
        deviceAdapter.submit(devices.values.sortedByDescending { it.rssi })
        updateUi()
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult?) {
        result ?: return
        // 已停止扫描:丢弃迟到的扫描回调,否则停止后列表仍会继续刷新、界面又回到"扫描中"
        if (!scanning) return
        try {
            val device = result.device ?: return
            val mac = device.address ?: return
            // 优先用广播包里的名字(无需 BLUETOOTH_CONNECT),取不到再问系统
            var name: String? = result.scanRecord?.deviceName
            if (name.isNullOrBlank()) {
                name = try {
                    device.name
                } catch (t: Throwable) {
                    null
                }
            }
            val displayName =
                if (name.isNullOrBlank()) getString(R.string.ble_unknown_device) else name
            devices[mac] = DeviceEntry(
                name = displayName,
                mac = mac,
                rssi = result.rssi,
                robot = isLikelyRobot(displayName)
            )
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, 400L)
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "处理扫描结果失败", t)
        }
    }

    private fun isLikelyRobot(name: String): Boolean {
        val lower = name.lowercase()
        return robotKeywords.any { lower.contains(it.lowercase()) }
    }

    private fun updateUi() {
        val permOk = hasPermissions()
        val btOn = isBluetoothOn()
        cardPermission.visibility = if (!permOk) View.VISIBLE else View.GONE
        btnScan.isEnabled = permOk && btOn
        btnScan.alpha = if (permOk && btOn) 1f else 0.4f
        btnScan.text = getString(
            if (scanning) R.string.ble_scan_stop else R.string.ble_scan_start
        )
        tvStatus.text = when {
            !permOk -> getString(R.string.ble_hint_auto)
            !btOn -> getString(R.string.ble_bluetooth_off)
            devices.isEmpty() ->
                if (scanning) getString(R.string.ble_scanning)
                else getString(R.string.ble_no_devices)
            else -> {
                val count = getString(R.string.ble_found_count, devices.size)
                if (scanning) getString(R.string.ble_scanning) + " · " + count else count
            }
        }
    }

    private class BleDeviceAdapter : RecyclerView.Adapter<BleDeviceAdapter.VH>() {

        private val items = ArrayList<DeviceEntry>()

        fun submit(list: List<DeviceEntry>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_ble_device, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.tvName.text = item.name
            holder.tvMac.text = item.mac
            holder.tvRssi.text = holder.itemView.context
                .getString(R.string.ble_dbm, item.rssi)
            holder.tvRobot.visibility = if (item.robot) View.VISIBLE else View.GONE
        }

        override fun getItemCount(): Int = items.size

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvMac: TextView = view.findViewById(R.id.tvMac)
            val tvRssi: TextView = view.findViewById(R.id.tvRssi)
            val tvRobot: TextView = view.findViewById(R.id.tvRobot)
        }
    }

    companion object {
        private const val TAG = "BleRssi"
    }
}
