package com.replay.recoder.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.replay.recoder.R

/**
 * 秒表页(三级菜单):计时清扫时长、回充耗时等。
 *
 * 时间差用 [SystemClock.elapsedRealtime] 计算(不受系统时间被修改影响),
 * Handler 每 30ms 刷新。支持暂停/继续、计圈(记录每圈累计与单圈差值)。
 */
class StopwatchActivity : AppCompatActivity() {

    private enum class State { IDLE, RUNNING, PAUSED }

    private lateinit var tvTime: TextView
    private lateinit var btnStartPause: MaterialButton
    private lateinit var btnLap: MaterialButton
    private lateinit var btnReset: MaterialButton
    private lateinit var tvLapEmpty: TextView
    private lateinit var lapAdapter: LapAdapter

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE

    /** 本次/累计已经走过的毫秒(暂停时刻固化) */
    private var accumulatedMs = 0L
    /** 本次开始/继续的 elapsedRealtime 基准 */
    private var startBase = 0L

    /** 各圈的累计时刻(毫秒),按计圈顺序保存 */
    private val laps = ArrayList<Long>()

    private val tickRunnable = object : Runnable {
        override fun run() {
            render()
            if (state == State.RUNNING) {
                handler.postDelayed(this, 30L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stopwatch)

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }
        tvTime = findViewById(R.id.tvTime)
        btnStartPause = findViewById(R.id.btnStartPause)
        btnLap = findViewById(R.id.btnLap)
        btnReset = findViewById(R.id.btnReset)
        tvLapEmpty = findViewById(R.id.tvLapEmpty)

        lapAdapter = LapAdapter()
        findViewById<RecyclerView>(R.id.lapsList).apply {
            layoutManager = LinearLayoutManager(this@StopwatchActivity)
            adapter = lapAdapter
        }

        btnStartPause.setOnClickListener {
            when (state) {
                State.IDLE, State.PAUSED -> start()
                State.RUNNING -> pause()
            }
        }
        btnLap.setOnClickListener { addLap() }
        btnReset.setOnClickListener { reset() }

        // 旋转/分屏重建后恢复计时状态,旧实现重建即归零、计圈全丢
        savedInstanceState?.let { b ->
            state = runCatching { State.valueOf(b.getString(ST_STATE) ?: State.IDLE.name) }
                .getOrDefault(State.IDLE)
            accumulatedMs = b.getLong(ST_ACC)
            startBase = b.getLong(ST_BASE)
            laps.addAll(b.getLongArray(ST_LAPS)?.toList().orEmpty())
            lapAdapter.submit(buildLapRows().asReversed())
        }

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ST_STATE, state.name)
        outState.putLong(ST_ACC, accumulatedMs)
        outState.putLong(ST_BASE, startBase)
        outState.putLongArray(ST_LAPS, laps.toLongArray())
    }

    private companion object {
        const val ST_STATE = "sw_state"
        const val ST_ACC = "sw_acc"
        const val ST_BASE = "sw_base"
        const val ST_LAPS = "sw_laps"
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (state == State.RUNNING) {
            handler.post(tickRunnable)
        }
    }

    private fun start() {
        startBase = SystemClock.elapsedRealtime()
        state = State.RUNNING
        handler.post(tickRunnable)
        render()
    }

    private fun pause() {
        accumulatedMs = currentMs()
        state = State.PAUSED
        handler.removeCallbacks(tickRunnable)
        render()
    }

    private fun reset() {
        state = State.IDLE
        accumulatedMs = 0L
        startBase = 0L
        laps.clear()
        handler.removeCallbacks(tickRunnable)
        lapAdapter.submit(emptyList())
        render()
    }

    private fun addLap() {
        if (state != State.RUNNING) return
        laps.add(currentMs())
        // 最新一圈显示在最上面
        lapAdapter.submit(buildLapRows().asReversed())
        render()
    }

    private fun currentMs(): Long = when (state) {
        State.RUNNING -> accumulatedMs + (SystemClock.elapsedRealtime() - startBase)
        else -> accumulatedMs
    }

    private fun buildLapRows(): List<LapRow> {
        val rows = ArrayList<LapRow>(laps.size)
        for (i in laps.indices) {
            val total = laps[i]
            val delta = total - if (i == 0) 0L else laps[i - 1]
            rows.add(LapRow(i + 1, total, delta))
        }
        return rows
    }

    private fun render() {
        tvTime.text = formatTime(currentMs())
        btnStartPause.setText(
            when (state) {
                State.RUNNING -> R.string.sw_pause
                State.PAUSED -> R.string.sw_resume
                State.IDLE -> R.string.sw_start
            }
        )
        btnLap.isEnabled = state == State.RUNNING
        btnLap.alpha = if (state == State.RUNNING) 1f else 0.4f
        btnReset.isEnabled = state != State.IDLE
        btnReset.alpha = if (state != State.IDLE) 1f else 0.4f
        tvLapEmpty.visibility = if (laps.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 格式化为 mm:ss.SS,超过 1 小时用 HH:mm:ss.SS */
    private fun formatTime(ms: Long): String {
        val totalCentis = ms / 10
        val centis = totalCentis % 100
        val totalSec = totalCentis / 100
        val sec = totalSec % 60
        val totalMin = totalSec / 60
        val min = totalMin % 60
        val hour = totalMin / 60
        return if (hour > 0) {
            "%02d:%02d:%02d.%02d".format(hour, min, sec, centis)
        } else {
            "%02d:%02d.%02d".format(min, sec, centis)
        }
    }

    private data class LapRow(val num: Int, val totalMs: Long, val deltaMs: Long)

    private inner class LapAdapter : RecyclerView.Adapter<LapAdapter.VH>() {

        private val items = ArrayList<LapRow>()

        fun submit(list: List<LapRow>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_lap, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = items[position]
            holder.tvNum.text = "#${row.num}"
            holder.tvTime.text = formatTime(row.totalMs)
            holder.tvDelta.text = "+${formatTime(row.deltaMs)}"
        }

        override fun getItemCount(): Int = items.size

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvNum: TextView = view.findViewById(R.id.tvLapNum)
            val tvTime: TextView = view.findViewById(R.id.tvLapTime)
            val tvDelta: TextView = view.findViewById(R.id.tvLapDelta)
        }
    }
}
