package com.replay.recoder.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.replay.recoder.R
import kotlin.concurrent.thread
import kotlin.math.log10

/**
 * 噪音计页(三级菜单):用麦克风 PCM 采样计算 RMS,换算为相对分贝参考值。
 *
 * 换算:dB = 20*log10(rms/32767) + 94(以 1 Pa≈94dB SPL 为参考的近似偏移),
 * 受设备麦克风增益/AGC 影响,仅作环境噪音强弱参考,非专业分贝计精度。
 */
class DecibelActivity : AppCompatActivity() {

    private lateinit var tvDbValue: TextView
    private lateinit var tvQuality: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnToggle: MaterialButton
    private lateinit var tvPeak: TextView
    private lateinit var tvAvg: TextView

    @Volatile
    private var measuring = false
    private var audioRecord: AudioRecord? = null

    private var peakDb = Double.NaN
    private var sumDb = 0.0
    private var sampleCount = 0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMeasure() else {
                Toast.makeText(this, R.string.db_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_decibel)

        findViewById<android.widget.ImageView>(R.id.btnBack).setOnClickListener { finish() }
        tvDbValue = findViewById(R.id.tvDbValue)
        tvQuality = findViewById(R.id.tvQuality)
        progressBar = findViewById(R.id.progressBar)
        btnToggle = findViewById(R.id.btnToggle)

        findViewById<View>(R.id.rowPeak).findViewById<TextView>(R.id.tvLabel)
            .setText(R.string.db_peak)
        findViewById<View>(R.id.rowAvg).findViewById<TextView>(R.id.tvLabel)
            .setText(R.string.db_avg)
        tvPeak = findViewById<View>(R.id.rowPeak).findViewById(R.id.tvValue)
        tvAvg = findViewById<View>(R.id.rowAvg).findViewById(R.id.tvValue)

        btnToggle.setOnClickListener {
            if (measuring) stopMeasure() else requestAndStart()
        }
        resetStats()
    }

    override fun onPause() {
        super.onPause()
        // 离开页面自动释放麦克风
        stopMeasure()
    }

    private fun requestAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startMeasure()
        } else {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMeasure() {
        if (measuring) return
        resetStats()

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Toast.makeText(this, R.string.db_start_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val bufferSize = maxOf(minBuffer, BYTES_PER_BATCH)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "创建 AudioRecord 失败", t)
            null
        }
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, R.string.db_start_failed, Toast.LENGTH_SHORT).show()
            record?.release()
            return
        }
        audioRecord = record
        measuring = true
        btnToggle.setText(R.string.db_stop)

        thread(name = "decibel-capture", isDaemon = true) {
            val buffer = ShortArray(bufferSize / 2)
            try {
                record.startRecording()
                while (measuring) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val db = rmsToDb(buffer, read)
                        onSample(db)
                    } else if (read < 0) {
                        // ERROR_INVALID_OPERATION / 麦克风被抢占等:旧版只 continue,
                        // 会空转占满 CPU 且永不恢复。直接结束测量并复位按钮。
                        android.util.Log.e(TAG, "AudioRecord.read 错误: $read")
                        measuring = false
                        runOnUiThread { btnToggle.setText(R.string.db_start) }
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "麦克风采样失败", t)
            } finally {
                try {
                    record.stop()
                } catch (_: Throwable) {
                }
                record.release()
            }
        }
    }

    private fun stopMeasure() {
        if (!measuring) return
        measuring = false
        val rec = audioRecord
        audioRecord = null
        // ★ stop() 才能让阻塞中的 read() 返回,采集线程随之退出并在 finally 释放实例。
        // 旧版只置 measuring=false,线程仍卡在 read() 占用麦克风,紧接着再点"开始"
        // 会创建第二个 AudioRecord 导致初始化失败。
        runCatching { rec?.stop() }
        btnToggle.setText(R.string.db_start)
    }

    private fun resetStats() {
        peakDb = Double.NaN
        sumDb = 0.0
        sampleCount = 0
        tvDbValue.text = "-- dB"
        tvQuality.text = ""
        progressBar.progress = 0
        tvPeak.text = "--"
        tvAvg.text = "--"
    }

    /** 单批 PCM 的 RMS → 相对分贝 */
    private fun rmsToDb(buffer: ShortArray, len: Int): Double {
        var sumSq = 0.0
        for (i in 0 until len) {
            val v = buffer[i].toDouble()
            sumSq += v * v
        }
        val rms = Math.sqrt(sumSq / len)
        if (rms < 1.0) return 0.0
        val dbFs = 20.0 * log10(rms / 32767.0)
        return (dbFs + DB_OFFSET).coerceIn(0.0, 120.0)
    }

    private fun onSample(db: Double) {
        if (peakDb.isNaN() || db > peakDb) peakDb = db
        sumDb += db
        sampleCount++
        val avg = sumDb / sampleCount
        runOnUiThread {
            if (!measuring) return@runOnUiThread
            tvDbValue.text = "%.0f dB".format(db)
            tvPeak.text = "%.0f dB".format(peakDb)
            tvAvg.text = "%.0f dB".format(avg)
            progressBar.progress = ((db - DB_MIN) / (DB_MAX - DB_MIN) * 100)
                .toInt().coerceIn(0, 100)
            tvQuality.setText(
                when {
                    db < 45 -> R.string.db_quality_silent
                    db < 65 -> R.string.db_quality_normal
                    db < 80 -> R.string.db_quality_loud
                    else -> R.string.db_quality_very_loud
                }
            )
        }
    }

    companion object {
        private const val TAG = "Decibel"
        private const val SAMPLE_RATE = 8000
        /** 每批 800 个 short ≈ 100ms */
        private const val BYTES_PER_BATCH = 800 * 2
        /** dBFS → 环境 dB 的近似偏移 */
        private const val DB_OFFSET = 94.0
        private const val DB_MIN = 20.0
        private const val DB_MAX = 120.0
    }
}
