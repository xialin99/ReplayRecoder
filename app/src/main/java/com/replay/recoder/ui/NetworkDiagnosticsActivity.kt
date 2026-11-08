package com.replay.recoder.ui

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.replay.recoder.R
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 网络诊断:本机网络信息 + Ping 测试。
 *
 * 本机信息用 WifiManager / NetworkInterface 读取(不申请 INTERNET 权限)。
 * Ping 通过 Runtime.exec("ping -c N -W 2 目标") 解析输出,避免沙箱权限限制。
 */
class NetworkDiagnosticsActivity : AppCompatActivity() {

    private val rows = HashMap<Int, TextView>()
    private lateinit var tvPingResult: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_network_diag)

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }
        tvPingResult = findViewById(R.id.tvPingResult)
        tvPingResult.text = ""

        bindRow(R.id.rowSsid, R.string.net_ssid)
        bindRow(R.id.rowIp, R.string.net_ip)
        bindRow(R.id.rowGateway, R.string.net_gateway)
        bindRow(R.id.rowMask, R.string.net_mask)
        bindRow(R.id.rowDns, R.string.net_dns)

        findViewById<View>(R.id.btnPing).setOnClickListener { runPing() }

        refresh()
    }

    private fun bindRow(rowId: Int, labelRes: Int) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.tvLabel).setText(labelRes)
        rows[rowId] = row.findViewById(R.id.tvValue)
    }

    private fun set(rowId: Int, value: String) {
        rows[rowId]?.text = value
    }

    private fun refresh() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val enabled = wifiManager?.isWifiEnabled == true
            if (!enabled) {
                set(R.id.rowSsid, getString(R.string.net_not_connected))
                set(R.id.rowIp, "--")
                set(R.id.rowGateway, "--")
                set(R.id.rowMask, "--")
                set(R.id.rowDns, "--")
                return
            }
            @Suppress("DEPRECATION")
            val info = wifiManager?.connectionInfo
            val connected = info != null && info.networkId != -1
            if (!connected) {
                set(R.id.rowSsid, getString(R.string.net_not_connected))
                set(R.id.rowIp, "--")
                set(R.id.rowGateway, "--")
                set(R.id.rowMask, "--")
                set(R.id.rowDns, "--")
                return
            }
            val rawSsid = info?.ssid?.removePrefix("\"")?.removeSuffix("\"")
            set(R.id.rowSsid, if (rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>") "未知" else rawSsid)
            @Suppress("DEPRECATION")
            set(R.id.rowIp, Formatter.formatIpAddress(info?.ipAddress ?: 0))
            @Suppress("DEPRECATION")
            set(R.id.rowGateway, Formatter.formatIpAddress(wifiManager?.dhcpInfo?.gateway ?: 0))
            @Suppress("DEPRECATION")
            set(R.id.rowMask, Formatter.formatIpAddress(wifiManager?.dhcpInfo?.netmask ?: 0))
            @Suppress("DEPRECATION")
            val dns1 = wifiManager?.dhcpInfo?.dns1 ?: 0
            set(R.id.rowDns, if (dns1 == 0) "--" else Formatter.formatIpAddress(dns1))
        } catch (t: Throwable) {
            android.util.Log.e("NetworkDiag", "读取网络信息失败", t)
        }
    }

    /** ping 任务序号:只允许最后一次的结果写回 UI,避免连点导致结果乱序覆盖。 */
    private var pingSeq = 0

    private fun runPing() {
        val target = findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTarget)
            .text.toString().trim()
        val target2 = if (target.isBlank()) {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                Formatter.formatIpAddress(wifiManager?.dhcpInfo?.gateway ?: 0)
            } catch (t: Throwable) { "8.8.8.8" }
        } else target

        val seq = ++pingSeq
        // 禁用到本次结束:连点会创建多个线程,结果互相覆盖
        val btnPing = findViewById<android.view.View>(R.id.btnPing)
        btnPing.isEnabled = false
        tvPingResult.text = getString(R.string.net_ping_pinging, target2)

        Thread {
            val count = 4
            val cmd = arrayOf("ping", "-c", count.toString(), "-W", "2", target2)
            var report = ""
            try {
                val process = try { Runtime.getRuntime().exec(cmd) } catch (t: Throwable) {
                    finishPing(seq, getString(R.string.net_ping_failed, t.message ?: "exec 失败"))
                    return@Thread
                }
                val output = try {
                    BufferedReader(InputStreamReader(process.inputStream)).readText()
                } catch (t: Throwable) { "" }
                process.waitFor()

                // 简单解析:找 "time=NN ms" / "packets transmitted, N received"
                // 兼容中文系统输出(如 "N 个包接收")和英文系统输出
                val received = (Regex("(\\d+) packets received").find(output)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("收到 (\\d+) 个包").find(output)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("((\\d+) 个包接收)").find(output)?.groupValues?.get(2)?.toIntOrNull()
                    ?: 0)
                val times = Regex("time=([0-9.]+) ms").findAll(output).map { it.groupValues[1].toDouble() }.toList()
                val avg = if (times.isNotEmpty()) times.average() else Double.NaN
                val loss = (count - received) * 100 / count
                val display = if (received == 0) {
                    getString(R.string.net_ping_failed, "100% 丢包")
                } else {
                    val avgStr = if (avg.isNaN()) "--" else "%.1f".format(avg)
                    getString(R.string.net_ping_result, received, count, avgStr, loss)
                }
                report = "$display\n\n---\n$output"
            } catch (t: Throwable) {
                report = getString(R.string.net_ping_failed, t.message ?: "未知错误")
            }
            finishPing(seq, report)
        }.also { it.isDaemon = true; it.name = "net-ping" }.start()
    }

    /** 只有最新一次 ping 才写回结果,且 Activity 已销毁时不再碰视图。 */
    private fun finishPing(seq: Int, text: String) {
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            if (seq == pingSeq) {
                tvPingResult.text = text
                findViewById<android.view.View>(R.id.btnPing).isEnabled = true
            }
        }
    }
}
