package com.replay.recoder.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.replay.recoder.R
import com.replay.recoder.ReplayApp
import com.replay.recoder.editor.Mp4Merger
import com.replay.recoder.editor.VideoEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * 视频剪辑页:悬浮顶栏 + 视频区 600dp(与回放页同高,从回放进剪辑视频不移动) +
 * 底部 128dp(TrimBar 48 + 控制 40 + 保存 40)。
 *
 * 交互:
 * - TrimBar 拖动首尾滑块选裁剪范围,两端 tvStartLabel/tvEndLabel 实时显示时间
 * - ⏪▶⏩ 自定义控制:快退/快进 10s + 播放/暂停(图标切换)
 * - 🔇 静音切换:在 0%/100% 之间切,保存时按当前态输出音量
 * - ⋮ 溢出菜单:重置范围
 * - 点"保存剪辑"走 [VideoEditor.trimWithFFmpeg](FFmpeg 流拷贝)后台导出,完成后新事件入库
 *
 * 多段事件进入页面时先在后台 [Mp4Merger] 拼接成临时单文件,
 * 保证预览时间轴与裁剪输入一致。
 */
@UnstableApi
class EditActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var trimBar: TrimBar
    private lateinit var tvRange: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvPreparing: TextView
    private lateinit var seekVolume: SeekBar
    private lateinit var btnSave: com.google.android.material.button.MaterialButton
    private lateinit var tvStartLabel: TextView
    private lateinit var tvEndLabel: TextView
    private lateinit var tvRangeSummary: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnMute: ImageView

    /** 进度刷新 Handler,仅在播放时回调,让 TrimBar 起点跟随当前播放位置(可选)。 */
    private val progressHandler = Handler(Looper.getMainLooper())

    /** 事件段路径(入参)。 */
    private var segments: List<String> = emptyList()
    /** 实际剪辑输入文件:单段直接用;多段合并后的临时文件。 */
    private var inputPath: String? = null
    private var tempMerged: File? = null

    private var startMs = 0L
    private var endMs = 0L
    private var durationMs = 0L

    /** 关键帧缩略图是否已抽取(避免重复抽取) */
    private var keyframesExtracted = false
    /** 拖动起点滑块时的节流 seek 记录 */
    private var lastPreviewSeekMs = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnOverflow).setOnClickListener { showOverflowMenu() }
        playerView = findViewById(R.id.editPlayerView)
        trimBar = findViewById(R.id.trimBar)
        tvRange = findViewById(R.id.tvRange)
        tvVolume = findViewById(R.id.tvVolume)
        tvPreparing = findViewById(R.id.tvPreparing)
        seekVolume = findViewById(R.id.seekVolume)
        btnSave = findViewById(R.id.btnSaveEdit)
        tvStartLabel = findViewById(R.id.tvStartLabel)
        tvEndLabel = findViewById(R.id.tvEndLabel)
        tvRangeSummary = findViewById(R.id.tvRangeSummary)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnMute = findViewById(R.id.btnMute)

        segments = intent.getStringArrayListExtra(EXTRA_SEGMENTS) ?: emptyList()
        if (segments.isEmpty()) {
            Toast.makeText(this, R.string.empty_events, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 音量(隐藏 seekVolume)文案初始化;UI 走 btnMute 静音切换
        tvVolume.text = "${seekVolume.progress}%"
        seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvVolume.text = "$progress%"
                updateMuteIcon(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 重置范围:底部控制行中间按钮,点击恢复整片
        findViewById<TextView>(R.id.btnResetRange).setOnClickListener {
            resetRange()
        }
        btnSave.setOnClickListener { saveEdit() }

        // 自定义控制:播放/暂停 + 静音切换(已去掉快进快退)
        btnPlayPause.setOnClickListener {
            player?.let {
                if (it.isPlaying) it.pause() else { it.play(); it.playWhenReady = true }
            }
        }
        btnMute.setOnClickListener {
            // 0% ↔ 100% 切换:100(有声)切到 0(静音),反之切到 100
            val cur = seekVolume.progress
            seekVolume.progress = if (cur > 0) 0 else 100
            tvVolume.text = "${seekVolume.progress}%"
            updateMuteIcon(seekVolume.progress)
        }
        // 初始化静音图标态(默认 100% 有声)
        updateMuteIcon(seekVolume.progress)

        // 关键帧时间条:拖动首尾滑块选择范围;拖起点实时预览画面,拖终点松手预览落点
        trimBar.minGapRatio = 1f / 1000f // 具体值在时长就绪后按 1 秒重设
        trimBar.listener = TrimBar.OnRangeChangeListener { sr, er, draggingStart, dragging ->
            if (durationMs <= 0L) return@OnRangeChangeListener
            startMs = (sr * durationMs).toLong()
            endMs = (er * durationMs).toLong()
            updateRangeText()
            when {
                draggingStart && dragging -> previewSeek(startMs)
                !dragging && !draggingStart -> player?.seekTo(endMs)
            }
        }

        prepareAndPlay()
    }

    /** 准备剪辑输入:单段直接用;多段后台用 Mp4Merger 拼接成临时文件再播放。
     *  不能用裸流拷贝合并:15s 段在录制期文件轮转时编码器不重启,段文件首帧可能
     *  不是关键帧/时间戳不连续,MediaMuxer 直接拼会 writeSampleData 报错。 */
    private fun prepareAndPlay() {
        tvPreparing.visibility = View.VISIBLE
        lifecycleScope.launch {
            val input = withContext(Dispatchers.IO) {
                if (segments.size == 1) {
                    segments.first()
                } else {
                    // 多段:先检查文件是否都存在,避免无效路径导致 concat 静默失败
                    val missing = segments.filterNot { File(it).exists() }
                    if (missing.isNotEmpty()) {
                        Log.e(TAG, "剪辑输入段缺失: ${missing.size}/${segments.size}")
                        missing.forEach { Log.e(TAG, "  缺失: $it") }
                        return@withContext null
                    }
                    val tmp = File(cacheDir, "edit_src_${System.currentTimeMillis()}.mp4")
                    // 段文件 moov 可能不完整,Media3 Transformer 已弃用;直接用 Mp4Merger(MediaExtractor 容错解析)
                    val ok = Mp4Merger.merge(segments, tmp.absolutePath)
                    if (ok && tmp.exists() && tmp.length() > 0) {
                        tempMerged = tmp
                        tmp.absolutePath
                    } else {
                        Log.e(TAG, "剪辑拼接失败(Mp4Merger 也失败),段数=${segments.size}")
                        runCatching { tmp.delete() }
                        null
                    }
                }
            }
            tvPreparing.visibility = View.GONE
            if (input == null) {
                val reason = Mp4Merger.lastError
                val detail = buildString {
                    append(getString(R.string.edit_prepare_failed))
                    if (!reason.isNullOrBlank()) append("($reason)")
                }
                Toast.makeText(this@EditActivity, detail, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            inputPath = input
            playInput(input)
            btnSave.isEnabled = true
        }
    }

    private fun playInput(path: String) {
        val p = ExoPlayer.Builder(this).build()
        p.setMediaItem(MediaItem.fromUri("file://$path"))
        p.prepare()
        p.playWhenReady = false
        playerView.player = p
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            }
        })
        // 时长就绪后初始化范围为整片,并抽取关键帧铺时间条
        playerView.post(object : Runnable {
            override fun run() {
                val d = p.duration
                if (d > 0) {
                    durationMs = d
                    if (endMs == 0L) endMs = d
                    updateRangeText()
                    extractKeyframes(path)
                } else {
                    playerView.postDelayed(this, 300)
                }
            }
        })
    }

    private fun updateRangeText() {
        val duration = (endMs - startMs).coerceAtLeast(0)
        tvRange.text = getString(
            R.string.edit_range_format,
            fmt(startMs), fmt(endMs), fmt(duration)
        )
        // 同步两端时间标签(新 mockup 的时间显示位置)
        tvStartLabel.text = fmt(startMs)
        tvEndLabel.text = fmt(endMs)
        // 范围汇总行:起点 · 终点 · 时长,拖动滑块实时刷新
        tvRangeSummary.text = getString(
            R.string.edit_range_summary,
            fmt(startMs), fmt(endMs), fmt(duration)
        )
        // 同步时间条滑块位置(设起点/终点/重置按钮 与 拖滑块 双向一致)
        if (durationMs > 0L) {
            trimBar.setRange(startMs / durationMs.toFloat(), endMs / durationMs.toFloat())
        }
    }

    /** 重置裁剪范围为整片。 */
    private fun resetRange() {
        if (durationMs <= 0L) return
        startMs = 0L
        endMs = durationMs
        updateRangeText()
        Toast.makeText(this, R.string.edit_reset, Toast.LENGTH_SHORT).show()
    }

    /** 顶栏 ⋮ 溢出菜单:重置范围。 */
    private fun showOverflowMenu() {
        val popup = PopupMenu(this, findViewById(R.id.btnOverflow))
        popup.menu.add(0, MENU_RESET, 0, R.string.edit_reset)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RESET -> { resetRange(); true }
                else -> false
            }
        }
        popup.show()
    }

    /** 静音图标态:0% 显示灰色(静音),>0 显示白色(有声)。 */
    private fun updateMuteIcon(progress: Int) {
        val color = if (progress > 0) 0xFFFFFFFF.toInt() else 0xFF9CA3AF.toInt()
        btnMute.setColorFilter(color, PorterDuff.Mode.SRC_ATOP)
    }

    /** 拖动起点滑块时节流预览:位置变化超过 200ms 才 seek,避免频繁寻址卡顿。 */
    private fun previewSeek(targetMs: Long) {
        if (kotlin.math.abs(targetMs - lastPreviewSeekMs) < 200) return
        lastPreviewSeekMs = targetMs
        player?.seekTo(targetMs)
    }

    /** 抽取关键帧缩略图铺到时间条上(OPTION_CLOSEST_SYNC 取真正的关键帧,速度快)。 */
    private fun extractKeyframes(path: String) {
        if (keyframesExtracted) return
        keyframesExtracted = true
        val duration = durationMs
        // 两滑块最小间隔 = 1 秒
        trimBar.minGapRatio = (1000f / duration).coerceIn(0.001f, 0.2f)
        trimBar.post {
            val cellPx = 64f * resources.displayMetrics.density
            val count = (if (trimBar.width > 0) {
                kotlin.math.ceil(trimBar.width / cellPx).toInt()
            } else 8).coerceIn(6, 12)
            lifecycleScope.launch {
                val thumbs = withContext(Dispatchers.IO) {
                    val mmr = MediaMetadataRetriever()
                    try {
                        mmr.setDataSource(path)
                        val out = ArrayList<Bitmap>(count)
                        for (i in 0 until count) {
                            val atUs = duration * 1000.0 * (i + 0.5) / count
                            val frame = runCatching {
                                mmr.getFrameAtTime(atUs.toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            }.getOrNull() ?: continue
                            // 居中裁成方格缩略图,原帧及时回收防 OOM
                            out.add(ThumbnailUtils.extractThumbnail(frame, 160, 160))
                            if (frame !== out.last()) frame.recycle()
                        }
                        out
                    } finally {
                        runCatching { mmr.release() }
                    }
                }
                if (thumbs.isNotEmpty()) trimBar.setThumbnails(thumbs)
            }
        }
    }

    private fun fmt(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    /** 保存剪辑:后台导出 → 作为新事件入库。 */
    private fun saveEdit() {
        val input = inputPath ?: return
        // 夹紧范围,防止滑块四舍五入越界
        if (durationMs > 0) {
            // 时长不足 1 秒时 durationMs-1000 为负,coerceIn 会因 min > max 抛
            // IllegalArgumentException 崩溃;此时最小跨度退化为整段时长。
            val minSpan = minOf(1000L, durationMs)
            startMs = startMs.coerceIn(0L, durationMs - minSpan)
            endMs = endMs.coerceIn(startMs + minSpan, durationMs)
        }
        if (endMs <= startMs) {
            Toast.makeText(this, R.string.edit_range_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val volume = seekVolume.progress / 100f
        btnSave.isEnabled = false

        // 统一进度弹窗(简化版 · 单进度条)
        val dialog = ExportProgressDialog(this).show(getString(R.string.edit_save))
            // 取消时真正终止 FFmpeg,而不只是关掉弹窗让导出继续在后台跑
            .setOnCancel { VideoEditor.cancelCurrent() }

        lifecycleScope.launch {
            val outFile = File(cacheDir, "edit_out_${System.currentTimeMillis()}.mp4")
            val ok = withContext(Dispatchers.IO) {
                VideoEditor.trimWithFFmpeg(
                    input, outFile.absolutePath,
                    startMs, endMs, volume
                ) { pct ->
                    // 流拷贝阶段占 0-30%,压缩占 30-100%
                    dialog.setProgress(pct * 30 / 100)
                }
            }

            if (!ok || !outFile.exists() || outFile.length() < 1_024L) {
                // FFmpeg 返回 0 但产物为空/缺失时也判失败,避免把 0 字节文件入库
                val detail = VideoEditor.lastError
                    ?: if (!outFile.exists()) "输出文件不存在" else "输出文件为空"
                runCatching { outFile.delete() }
                dialog.dismiss(false, "${getString(R.string.edit_failed)}:$detail")
                btnSave.isEnabled = true
                return@launch
            }
            // 压缩:CRF 30 砍半体积(允许丢画质),失败/缺 moov 回退未压缩(也是 H.264)
            val trimmedFile = File(outFile.absolutePath)
            val compressed = File(cacheDir, "edit_cmp_${System.currentTimeMillis()}.mp4")
            val compOk = withContext(Dispatchers.IO) {
                VideoEditor.compressToFit(trimmedFile.absolutePath, compressed.absolutePath) { p ->
                    dialog.setProgress(30 + p * 70 / 100)
                }
            }
            val finalSrc = if (compOk && compressed.exists() && compressed.length() > 1_024 &&
                Mp4Merger.mergedOk(compressed.absolutePath)
            ) {
                runCatching { trimmedFile.delete() }
                compressed
            } else {
                runCatching { compressed.delete() }
                trimmedFile
            }
            val volumeSkipped = VideoEditor.lastError == "AUDIO_VOLUME_SKIPPED"
            if (volumeSkipped) {
                Toast.makeText(this@EditActivity,
                    "导出成功,但音量调整未能生效(已按原音量保存)",
                    Toast.LENGTH_LONG).show()
            }
            dialog.setProgress(100)

            // 入库:创建事件 → 移动成片到事件目录 → 更新 meta
            val repo = (application as ReplayApp).eventRepo
            val eventId = withContext(Dispatchers.IO) {
                val id = repo.createEvent(segments = emptyList(), windowMinutes = 0)
                // 事件目录约定与 EventRepository 一致:filesDir/events/event_<id>/
                val eventDir = File(File(filesDir, "events"), "event_$id").apply { mkdirs() }
                val dst = File(eventDir, "event.mp4")
                val moved = runCatching { finalSrc.renameTo(dst) }.getOrDefault(false)
                if (!moved) {
                    runCatching {
                        finalSrc.inputStream().use { it.copyTo(dst.outputStream()) }
                    }
                }
                runCatching { finalSrc.delete() }
                if (dst.exists()) {
                    repo.setSegments(id, listOf(dst.absolutePath))
                    repo.setRemark(id, "剪辑 ${fmt(startMs)}-${fmt(endMs)}")
                    id
                } else -1L
            }

            if (eventId > 0) {
                // 保存成功:允许再次保存,不 finish
                btnSave.isEnabled = true
                dialog.dismiss(true, getString(R.string.edit_saved))
            } else {
                dialog.dismiss(false, getString(R.string.edit_failed))
                btnSave.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacksAndMessages(null)
        runCatching { player?.release() }
        // 后台 FFmpeg 可能仍在读 tempMerged:先取消导出,稍后再删,避免它读到被删的源文件
        VideoEditor.cancelCurrent()
        tempMerged?.let { f ->
            Thread {
                Thread.sleep(600)
                runCatching { f.delete() }
            }.also { it.isDaemon = true }.start()
        }
        tempMerged = null
        player = null
    }

    companion object {
        private const val TAG = "EditActivity"
        private const val EXTRA_SEGMENTS = "edit_segments"
        private const val MENU_RESET = 1

        fun launch(ctx: Context, segments: List<String>) {
            ctx.startActivity(
                Intent(ctx, EditActivity::class.java).apply {
                    putStringArrayListExtra(EXTRA_SEGMENTS, ArrayList(segments))
                }
            )
        }
    }
}
