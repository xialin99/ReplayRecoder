package com.replay.recoder.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.replay.recoder.R
import com.replay.recoder.ReplayApp
import com.replay.recoder.event.EventType
import com.replay.recoder.event.ReplayEvent
import com.replay.recoder.editor.Mp4Merger
import com.replay.recoder.editor.VideoEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 回放页:悬浮顶栏 + 视频区 600dp + 底部 128dp(进度/控制/动作)。
 *
 * 入口:
 * - [launch] 传段路径列表(实时回放当前窗口快照,eventId=-1 表示未入事件库)
 * - 不带参启动:从事件库读最近一条事件回放
 *
 * 自定义控制:进度行(录制点 + seekbar + 时间) / 控制行(⏪ ▶ ⏩ + 速度) /
 * 动作行([剪辑][导出][分享])。备注/删除/属性移到顶栏 ⋮ 溢出菜单。
 *
 * 拍照事件:隐藏进度/控制/速度/剪辑,导出文案改为"保存到相册"。
 */
@UnstableApi
class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    /** 切后台前是否正在播放,onStart 时据此恢复。 */
    private var resumeOnStart = false
    private lateinit var playerView: PlayerView
    private lateinit var ivPhoto: ImageView
    private lateinit var etRemark: android.widget.EditText
    private lateinit var btnSaveRemark: android.widget.Button
    private lateinit var seekBar: SeekBar
    private lateinit var tvTime: TextView
    private lateinit var btnPlayPause: ImageView
    private lateinit var btnSpeed: com.google.android.material.button.MaterialButton
    private lateinit var progressRow: View
    private lateinit var controlsRow: View
    private lateinit var dotRec: View

    /** 进度刷新 Handler,仅在播放时每秒回调一次。 */
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 500)
        }
    }

    private var eventId: Long = -1L
    private var segments: List<String> = emptyList()
    /** 事件类型:决定走视频回放还是照片预览,以及按钮可见性。实时回放(eventId=-1)恒为录屏。 */
    private var eventType: EventType = EventType.SCREEN_RECORD
    /** 当前播放速度,默认 1.0x。 */
    private var currentSpeed: Float = 1.0f

    /** 播放列表每段时长(ms):多段(合并降级)事件据此按全部段累加显示总时长并映射进度条。
     *  为空或与 [segments] 不一致时退化为"只按当前段"计时。 */
    private var itemDurationsMs: List<Long> = emptyList()

    /** 可切换的事件 id 列表(从相册进入时传入),支持左右滑切换。空=不可切换(实时回放/单事件)。 */
    private var eventIds: List<Long> = emptyList()
    private var currentIndex: Int = 0

    private var gestureDetector: GestureDetector? = null
    /** 拖动进度条前是否在播放,松手后恢复。 */
    private var wasPlayingBeforeSeek = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        playerView = findViewById(R.id.playerView)
        ivPhoto = findViewById(R.id.ivPhoto)
        etRemark = findViewById(R.id.etRemark)
        btnSaveRemark = findViewById(R.id.btnSaveRemark)
        seekBar = findViewById(R.id.seekBar)
        tvTime = findViewById(R.id.tvTime)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnSpeed = findViewById(R.id.btnSpeed)
        progressRow = findViewById(R.id.progressRow)
        controlsRow = findViewById(R.id.controlsRow)
        dotRec = findViewById(R.id.dotRec)

        // 顶栏:返回 + 溢出菜单
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnOverflow).setOnClickListener { showOverflowMenu() }

        var segs: List<String> = intent.getStringArrayListExtra(EXTRA_SEGMENTS) ?: emptyList()
        eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        eventType = intent.getSerializableExtra(EXTRA_TYPE) as? EventType ?: EventType.SCREEN_RECORD
        // 可切换事件列表(相册进入):左右滑切换上/下一个事件
        eventIds = intent.getLongArrayExtra(EXTRA_EVENT_IDS)?.toList() ?: emptyList()
        currentIndex = intent.getIntExtra(EXTRA_CURRENT_INDEX, 0)

        if (segs.isEmpty() && eventId > 0L) {
            // 传了事件 id 却没有任何段(空事件/文件被清理):直接提示并退出。
            // 不能走下面的"最近事件"兜底 —— 那会把用户点的事件换成另一条播放(播错事件)。
            hideActionsForTransient()
            hideRemarkArea()
            Toast.makeText(this, R.string.empty_events, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (segs.isEmpty()) {
            val repo = (application as ReplayApp).eventRepo
            hideActionsForTransient()
            hideRemarkArea()
            lifecycleScope.launch {
                val ev = withContext(Dispatchers.IO) { repo.latestEvent() }
                if (ev != null && ev.segments.isNotEmpty()) {
                    eventId = ev.id
                    segments = ev.segments
                    eventType = ev.type
                    showRemarkArea(ev.remark)
                    playOrShowContent()
                    showActionsForEvent()
                    applyModeForType()
                } else {
                    Toast.makeText(this@PlaybackActivity, R.string.empty_events, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        } else {
            segments = segs
            if (eventId <= 0L) {
                hideRemarkArea()
                hideActionsForTransient()
            } else {
                val repo = (application as ReplayApp).eventRepo
                lifecycleScope.launch {
                    val ev = withContext(Dispatchers.IO) { repo.get(eventId) }
                    if (ev != null) eventType = ev.type
                    showRemarkArea(ev?.remark ?: "")
                    applyModeForType()
                }
                showActionsForEvent()
            }
            playOrShowContent()
            applyModeForType()
        }

        // 动作行按钮:剪辑 + 分享(系统选择器,由 shareToSystem 统一处理)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShareDingTalk).setOnClickListener {
            if (eventType == EventType.PHOTO) sharePhotoToSystem() else shareToSystem()
        }
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEdit).setOnClickListener {
            if (segments.isNotEmpty()) EditActivity.launch(this, segments)
        }

        // 自定义控制:播放/暂停 + 快退/快进 + 速度 + 进度条
        btnPlayPause.setOnClickListener {
            player?.let {
                if (it.isPlaying) it.pause() else { it.play(); it.playWhenReady = true }
            }
        }
        findViewById<ImageView>(R.id.btnRewind).setOnClickListener {
            player?.seekTo((player?.currentPosition ?: 0) - 10_000L)
        }
        findViewById<ImageView>(R.id.btnForward).setOnClickListener {
            player?.seekTo((player?.currentPosition ?: 0) + 10_000L)
        }
        btnSpeed.setOnClickListener { showSpeedPicker() }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val p = player ?: return
                    // 多段事件按全片总长映射到具体段,避免拖进度只在本段内跳
                    val total = totalDurationMs()
                    val dur = if (total > 0L) total else (p.duration.takeIf { it > 0 } ?: return)
                    seekToGlobal(p, progress.toLong() * dur / 1000)
                    updateProgress()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                progressHandler.removeCallbacks(progressRunnable)
                // 拖动时暂停播放,确保 seekTo 立即渲染目标帧(实时预览)
                wasPlayingBeforeSeek = player?.isPlaying == true
                if (wasPlayingBeforeSeek) player?.pause()
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                updateProgress()
                // 松手后恢复拖动前的播放状态
                if (wasPlayingBeforeSeek) {
                    player?.play()
                    player?.playWhenReady = true
                    progressHandler.post(progressRunnable)
                }
            }
        })

        // 预览区手势:左右滑切换事件 + 上划属性 + 下滑备注
        setupPreviewGestures()
    }

    /**
     * 绑定视频/图片预览区手势(仅单指)。
     * 左右滑切换事件、上划属性、下滑备注。
     */
    private fun setupPreviewGestures() {
        val previewRoot = findViewById<View>(R.id.videoContainer)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // 单指单击:播放/暂停(用 onSingleTapConfirmed 避免与滑动冲突)
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                return true
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val absX = kotlin.math.abs(dx)
                val absY = kotlin.math.abs(dy)
                if (absY > absX && absY > 150f) {
                    if (dy < 0) showInfo() else showRemarkDialog()
                    return true
                }
                if (absX > absY && absX > 200f) {
                    if (dx < 0) switchEvent(1) else switchEvent(-1)
                    return true
                }
                return false
            }
        })

        previewRoot.setOnTouchListener { _, event ->
            gestureDetector?.onTouchEvent(event)
            true
        }
    }

    /** 切换到相邻事件(offset +1/-1),无更多时 toast 提示。 */
    private fun switchEvent(offset: Int) {
        if (eventIds.isEmpty()) return
        val next = currentIndex + offset
        if (next < 0 || next >= eventIds.size) {
            Toast.makeText(this, if (offset < 0) "已是第一个" else "已是最后一个", Toast.LENGTH_SHORT).show()
            return
        }
        currentIndex = next
        val targetId = eventIds[next]
        val repo = (application as ReplayApp).eventRepo
        lifecycleScope.launch {
            val ev = withContext(Dispatchers.IO) { repo.get(targetId) }
            if (ev == null || ev.segments.isEmpty()) {
                Toast.makeText(this@PlaybackActivity, R.string.empty_events, Toast.LENGTH_SHORT).show()
                return@launch
            }
            // 释放旧播放器
            runCatching { player?.release() }
            player = null
            progressHandler.removeCallbacks(progressRunnable)
            // 加载新事件
            eventId = ev.id
            segments = ev.segments
            eventType = ev.type
            showRemarkArea(ev.remark)
            playOrShowContent()
            showActionsForEvent()
            applyModeForType()
        }
    }

    /** 按事件类型呈现内容:照片=静态图预览,视频=ExoPlayer 串联段。 */
    private fun playOrShowContent() {
        if (eventType == EventType.PHOTO) showPhoto() else playSegments(segments)
    }

    /** 按事件类型切换 UI:照片隐藏播放器/进度/控制/速度/剪辑,导出文案改为"保存到相册"。 */
    private fun applyModeForType() {
        val isPhoto = eventType == EventType.PHOTO
        playerView.visibility = if (isPhoto) View.GONE else View.VISIBLE
        ivPhoto.visibility = if (isPhoto) View.VISIBLE else View.GONE
        // 照片不需要进度/控制/速度
        progressRow.visibility = if (isPhoto) View.GONE else View.VISIBLE
        controlsRow.visibility = if (isPhoto) View.GONE else View.VISIBLE
        // 剪辑只对视频已入库事件开放
        val btnEdit = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEdit)
        btnEdit.visibility = if (!isPhoto && eventId > 0L) View.VISIBLE else View.GONE
        // 只留剪辑和分享,btnExport 已从 layout 移除
    }

    /** 实时回放(无事件 id)时隐藏动作按钮和溢出菜单项。 */
    private fun hideActionsForTransient() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShareDingTalk).visibility = View.GONE
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnEdit).visibility = View.GONE
    }

    /** 已入库事件显示动作按钮。 */
    private fun showActionsForEvent() {
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShareDingTalk).visibility = View.VISIBLE
    }

    /** 照片预览:降采样解码 jpg 显示到 ivPhoto,避免全尺寸图 OOM。 */
    private fun showPhoto() {
        val path = segments.firstOrNull() ?: return
        findViewById<View>(R.id.loadingView).visibility = View.GONE
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                val w = bounds.outWidth
                val h = bounds.outHeight
                if (w <= 0 || h <= 0) return@withContext null
                // 目标显示宽 ≈ 屏宽,按 2 的幂降采样,再交给 fitCenter 等比缩放
                val targetW = resources.displayMetrics.widthPixels
                var sample = 1
                while (w / sample > targetW * 2) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(path, opts)
            }
            if (bmp != null) ivPhoto.setImageBitmap(bmp)
        }
    }

    /** 显示备注输入框 + 保存按钮,并预填已有备注(隐藏 EditText 仅作状态同步)。 */
    private fun showRemarkArea(remark: String) {
        etRemark.visibility = View.VISIBLE
        btnSaveRemark.visibility = View.VISIBLE
        etRemark.setText(remark)
        etRemark.hint = getString(R.string.hint_remark)
    }

    /** 实时回放(未冻结成事件)时隐藏备注区,因为没有事件 id 无处可存。 */
    private fun hideRemarkArea() {
        etRemark.visibility = View.GONE
        btnSaveRemark.visibility = View.GONE
    }

    /** 保存备注到当前事件(溢出菜单弹窗保存后调用)。 */
    private fun saveRemark() {
        if (eventId <= 0L) return
        val text = etRemark.text?.toString().orEmpty()
        val repo = (application as ReplayApp).eventRepo
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repo.setRemark(eventId, text) }
            Toast.makeText(this@PlaybackActivity, R.string.toast_remark_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun playSegments(segs: List<String>) {
        // 显示加载提示,避免首帧渲染前黑屏
        val loading = findViewById<View>(R.id.loadingView)
        loading.visibility = View.VISIBLE
        // 本地文件播放:起播等待 300ms;maxBuffer 60s 保证 4x/8x 倍速下不会频繁耗尽缓冲卡顿
        // (5s 缓冲在 8x 下只够 0.6s 实时播放,会不停 rebuffer)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs= */ 50_000,
                /* maxBufferMs= */ 60_000,
                /* bufferForPlaybackMs= */ 300,
                /* bufferForPlaybackAfterRebufferMs= */ 500
            )
            .build()
        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()
        // 恢复用户此前选择的倍速(新建播放器默认 1x,不设置会导致切事件/重进后倍速丢失)
        if (currentSpeed != 1f) {
            player.playbackParameters = PlaybackParameters(currentSpeed)
        }
        val items = segs.map { androidx.media3.common.MediaItem.fromUri("file://" + it) }
        player.setMediaItems(items, true)
        playerView.player = player
        player.prepare()
        // 先不自动播放,等首帧渲染完成后再开始,避免加载中进度条空跑
        player.playWhenReady = false
        // 每段时长后台读取:多段事件(合并降级)若只按当前段计时,进度条和总时长会显示成 00:15。
        // ExoPlayer 的 duration 只反映当前 mediaItem,无法直接拿到整条播放列表总长。
        itemDurationsMs = emptyList()
        lifecycleScope.launch {
            val ds = withContext(Dispatchers.IO) {
                segs.map { path ->
                    runCatching {
                        MediaMetadataRetriever().use { r ->
                            r.setDataSource(path)
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        }
                    }.getOrDefault(0L)
                }
            }
            // 期间可能已切换到别的事件(段列表变了),过期的结果直接丢弃
            if (segments == segs) {
                itemDurationsMs = ds
                updateProgress()
            }
        }
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                // 首帧渲染完成:隐藏加载提示 + 开始播放
                loading.visibility = View.GONE
                player.playWhenReady = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
                if (isPlaying) {
                    progressHandler.post(progressRunnable)
                } else {
                    progressHandler.removeCallbacks(progressRunnable)
                    updateProgress()
                }
            }

            /** 某一段解析失败(合并降级事件里的损坏段)时,ExoPlayer 会整场停在错误处,
             *  表现为"只播了十几秒就卡住"。这里跳过损坏段继续播下一段,播到最后才提示,
             *  避免用户误以为整场录制只剩很短一段。 */
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val badIndex = player.currentMediaItemIndex
                val next = badIndex + 1
                progressHandler.post {
                    // 异步执行,避免在错误回调里重入播放器
                    val p = this@PlaybackActivity.player ?: return@post
                    if (next < p.mediaItemCount) {
                        Toast.makeText(
                            this@PlaybackActivity,
                            "第 ${badIndex + 1} 段视频损坏,已跳过", Toast.LENGTH_SHORT
                        ).show()
                        p.seekToDefaultPosition(next)
                        p.prepare()
                        p.playWhenReady = true
                    } else {
                        loading.visibility = View.GONE
                        Toast.makeText(
                            this@PlaybackActivity, "视频损坏,无法播放", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
        this.player = player
        // 启动一次进度刷新,拿到时长后填充 tvTime
        progressHandler.post(progressRunnable)
    }

    /** 刷新进度条 + 时间标签。多段事件按全部段累加计时(否则总时长只显示当前段的 00:15)。 */
    private fun updateProgress() {
        val p = player ?: return
        val total = totalDurationMs()
        val pos: Long
        val dur: Long
        if (total > 0L) {
            dur = total
            pos = (elapsedBeforeCurrentItem() + p.currentPosition.coerceAtLeast(0)).coerceAtMost(total)
        } else {
            pos = p.currentPosition.coerceAtLeast(0)
            dur = p.duration.takeIf { it > 0 } ?: 0L
        }
        if (dur > 0) {
            val ratio = (pos.toFloat() / dur).coerceIn(0f, 1f)
            seekBar.progress = (ratio * 1000).toInt()
            seekBar.max = 1000
        } else {
            seekBar.progress = 0
        }
        tvTime.text = "${fmt(pos)}/${fmt(dur)}"
    }

    /** 整条播放列表总时长(ms)。任一段时长读不出(<=0)时返回 0,退化为单段口径,避免进度条错乱。 */
    private fun totalDurationMs(): Long {
        val ds = itemDurationsMs
        if (ds.isEmpty() || ds.size != segments.size) return 0L
        if (ds.any { it <= 0L }) return 0L
        return ds.sum()
    }

    /** 当前段之前所有段的时长之和(全局进度 = 该值 + 段内位置)。 */
    private fun elapsedBeforeCurrentItem(): Long {
        val ds = itemDurationsMs
        val idx = player?.currentMediaItemIndex ?: 0
        if (ds.isEmpty() || ds.size != segments.size || idx <= 0) return 0L
        return ds.subList(0, idx.coerceAtMost(ds.size)).sum()
    }

    /** 把全局毫秒位置映射到 (段索引, 段内位置) 再 seek。 */
    private fun seekToGlobal(target: Player, globalMs: Long) {
        val ds = itemDurationsMs
        val total = totalDurationMs()
        if (total <= 0L || ds.size != segments.size) {
            target.seekTo(globalMs)
            return
        }
        var acc = 0L
        for (i in ds.indices) {
            val end = acc + ds[i]
            if (globalMs < end || i == ds.lastIndex) {
                target.seekTo(i, (globalMs - acc).coerceIn(0L, ds[i] - 1L))
                return
            }
            acc = end
        }
    }

    private fun fmt(ms: Long): String {
        val totalSec = ms / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", totalSec / 60, totalSec % 60)
    }

    /** 顶栏 ⋮ 溢出菜单:备注 / 删除 / 属性(已入库事件才显示)。 */
    private fun showOverflowMenu() {
        val popup = PopupMenu(this, findViewById(R.id.btnOverflow))
        if (eventId > 0L) {
            popup.menu.add(0, MENU_REMARK, 0, R.string.action_edit_remark)
            popup.menu.add(0, MENU_INFO, 0, R.string.btn_info)
            popup.menu.add(0, MENU_DELETE, 0, R.string.btn_delete)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_REMARK -> { showRemarkDialog(); true }
                MENU_INFO -> { showInfo(); true }
                MENU_DELETE -> { confirmDelete(); true }
                else -> false
            }
        }
        popup.show()
    }

    /** 备注编辑弹窗:AlertDialog + EditText,保存后同步到隐藏 etRemark(供 shareToDingTalk 取文件名)。 */
    private fun showRemarkDialog() {
        if (eventId <= 0L) return
        val ev = (application as ReplayApp).eventRepo
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { ev.get(eventId)?.remark ?: "" }
            val edt = android.widget.EditText(this@PlaybackActivity).apply {
                setText(current)
                setSingleLine(true)
                hint = getString(R.string.hint_remark)
                setSelection(text.length)
            }
            AlertDialog.Builder(this@PlaybackActivity)
                .setTitle(R.string.action_edit_remark)
                .setView(edt)
                .setPositiveButton(R.string.btn_save_remark) { _, _ ->
                    val text = edt.text?.toString().orEmpty()
                    etRemark.setText(text) // 同步隐藏 EditText,shareToDingTalk 仍能读到
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { ev.setRemark(eventId, text) }
                        Toast.makeText(this@PlaybackActivity, R.string.toast_remark_saved, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** 速度选择弹窗:0.5/1/1.5/2/4/8x 循环切换。 */
    private fun showSpeedPicker() {
        val speeds = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f, 4.0f, 8.0f)
        val labels = speeds.map { String.format(Locale.getDefault(), "%.1fx", it) }.toTypedArray()
        val checked = speeds.indexOfFirst { it == currentSpeed }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.btn_speed)
            .setSingleChoiceItems(labels, checked) { dlg, which ->
                setSpeed(speeds[which])
                dlg.dismiss()
            }
            .show()
    }

    /** 分享到系统选择器:合并段 → 生成 content:// URI → ACTION_SEND 系统选择器(钉钉/微信/Jira 任选)。 */
    private fun shareToSystem() {
        val segs = segments
        if (segs.isEmpty()) {
            Toast.makeText(this, R.string.empty_events, Toast.LENGTH_SHORT).show()
            return
        }
        val btn = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShareDingTalk)
        btn.isEnabled = false
        // 合并+压缩可能耗时几十秒(5min 视频压缩约 1min):必须有进度弹窗,
        // 只有一个一闪而过的 Toast 会被感知为"点了没反应/卡顿"
        val dialog = ExportProgressDialog(this)
            .show(getString(R.string.btn_share))
            .setOnCancel { VideoEditor.cancelCurrent() }

        lifecycleScope.launch {
            // 统一进度:合并占 0-40%,压缩占 40-100%,全程连续走
            val updateProgress = { pct: Int -> dialog.setProgress(pct.coerceIn(0, 100)) }
            // 文件名优先用备注(已保存的备注 > 输入框当前文本);为空时回退到事件 id
            val remarkName = etRemark.text?.toString().orEmpty().ifBlank { "事件${eventId}" }
            val fileName = sanitizeFileName(remarkName)
            // 合并到 cache 目录临时文件,通过 FileProvider 暴露 content:// URI
            val tmpFile = File(cacheDir, "$fileName.mp4")
            // 只清理 1 小时前的旧分享缓存。直接删全部 *.mp4 会误删正在途中的
            // edit_src_* / concat_*.mp4,导致并发导出/分享失败。
            val staleBefore = System.currentTimeMillis() - 60 * 60 * 1000L
            cacheDir.listFiles { f -> f.name.endsWith(".mp4") && f.lastModified() < staleBefore }
                ?.forEach { it.delete() }
            val ok = withContext(Dispatchers.IO) {
                Mp4Merger.merge(segs, tmpFile.absolutePath) { doneSegs, totalSegs ->
                    updateProgress((doneSegs * 40f / totalSegs).toInt())
                }
            }
            // 压缩:CRF 30 砍半以上体积(H.264 编码,Jira/浏览器认);失败/缺 moov 回退未压缩合并源
            val shareFile = if (ok) {
                val compressed = File(cacheDir, "${fileName}_small.mp4")
                val compOk = withContext(Dispatchers.IO) {
                    com.replay.recoder.editor.VideoEditor.compressToFit(
                        tmpFile.absolutePath, compressed.absolutePath
                    ) { p -> updateProgress(40 + p * 60 / 100) }
                }
                if (compOk && compressed.exists() && compressed.length() > 1_024 &&
                    com.replay.recoder.editor.Mp4Merger.mergedOk(compressed.absolutePath)
                ) compressed else tmpFile
            } else tmpFile
            btn.isEnabled = true
            if (!ok) {
                dialog.dismiss(false, "准备分享文件失败")
                return@launch
            }
            dialog.dismiss(true, null)
            val uri = FileProvider.getUriForFile(
                this@PlaybackActivity, "${packageName}.fileprovider", shareFile
            )
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                // EXTRA_TITLE 给接收方一个展示名(部分应用会用作文件名展示)
                putExtra(Intent.EXTRA_TITLE, fileName)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.btn_share)))
        }
    }

    /** 设置播放速度,并更新 btnSpeed 文案。 */
    private fun setSpeed(speed: Float) {
        currentSpeed = speed
        player?.playbackParameters = PlaybackParameters(speed)
        btnSpeed.text = String.format(Locale.getDefault(), "%.1fx", speed)
        Toast.makeText(this, "播放速度: ${btnSpeed.text}", Toast.LENGTH_SHORT).show()
    }

    /** 照片分享到系统选择器:直接以 content:// URI 分享 jpg,由用户选择目标应用。 */
    private fun sharePhotoToSystem() {
        val srcPath = segments.firstOrNull() ?: run {
            Toast.makeText(this, R.string.empty_events, Toast.LENGTH_SHORT).show()
            return
        }
        val src = File(srcPath)
        if (!src.exists()) {
            Toast.makeText(this, R.string.empty_events, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", src
        )
        val remarkName = etRemark.text?.toString().orEmpty().ifBlank { "事件${eventId}" }
        val fileName = "${sanitizeFileName(remarkName)}.jpg"
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.btn_share)))
    }

    /** 确认删除当前事件。 */
    private fun confirmDelete() {
        if (eventId <= 0L) return
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(R.string.dialog_delete_msg)
            .setPositiveButton("删除") { _, _ ->
                val repo = (application as ReplayApp).eventRepo
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { repo.delete(eventId) }
                    Toast.makeText(this@PlaybackActivity, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 显示属性信息。逐段读时长/解码都在 IO 线程:
     *  合并降级事件可能有几十上百段,在主线程逐段 new MediaMetadataRetriever 会直接 ANR。 */
    private fun showInfo() {
        if (eventId <= 0L) return
        val repo = (application as ReplayApp).eventRepo
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                buildInfoText(repo.get(eventId))
            }
            if (text == null) {
                Toast.makeText(this@PlaybackActivity, "事件不存在", Toast.LENGTH_SHORT).show()
                return@launch
            }
            AlertDialog.Builder(this@PlaybackActivity)
                .setTitle(R.string.btn_info)
                .setMessage(text)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    /** 生成属性文本(仅 IO 线程调用):元信息 + 文件大小 + 分辨率/时长。 */
    private fun buildInfoText(ev: ReplayEvent?): String? {
        ev ?: return null
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("事件ID: ${ev.id}\n")
        sb.append("创建时间: ${df.format(Date(ev.createdAt))}\n")
        if (ev.type != EventType.PHOTO) {
            sb.append("回放窗口: ${ev.windowMinutes}分钟\n")
        }
        sb.append("类型: ${typeLabel(ev.type)}\n")
        sb.append("备注: ${ev.remark.ifEmpty { "无" }}\n")
        sb.append("段数: ${ev.segments.size}\n")
        // 获取文件信息:照片用 BitmapFactory 读尺寸,视频用 MediaMetadataRetriever 取时长/分辨率
        val firstSeg = ev.segments.firstOrNull() ?: return sb.toString()
        // 文件大小取全部段之和(合并降级事件是多段)
        val totalBytes = ev.segments.sumOf { f -> File(f).takeIf { it.exists() }?.length() ?: 0L }
        if (totalBytes > 0) {
            sb.append("文件大小: ${totalBytes / 1024 / 1024}MB\n")
        }
        if (ev.type == EventType.PHOTO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(firstSeg, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                    sb.append("分辨率: ${bounds.outWidth}x${bounds.outHeight}")
                } else {
                    sb.append("图片信息: 读取失败")
                }
            }.onFailure { sb.append("图片信息: 读取失败") }
        } else {
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(firstSeg)
                // 时长必须逐段累加:多段事件只读首段会显示成 00:15
                val duration = ev.segments.sumOf { seg ->
                    runCatching {
                        MediaMetadataRetriever().use { r ->
                            r.setDataSource(seg)
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        }
                    }.getOrDefault(0L)
                }
                val width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
                val height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
                sb.append("时长: ${duration / 1000}秒\n")
                sb.append("分辨率: ${width}x${height}")
            } catch (e: Exception) {
                sb.append("视频信息: 读取失败")
            } finally {
                runCatching { mmr.release() }
            }
        }
        return sb.toString()
    }

    /** 切后台/锁屏暂停:旧版只在 onDestroy 释放,退到后台音视频会继续播放。 */
    override fun onStop() {
        super.onStop()
        player?.let {
            resumeOnStart = it.isPlaying
            if (it.isPlaying) it.pause()
        }
    }

    override fun onStart() {
        super.onStart()
        if (resumeOnStart) {
            resumeOnStart = false
            player?.play()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressRunnable)
        player?.release()
        player = null
    }

    /**
     * 把任意文字转成安全的文件名(不含扩展名):
     * - 去掉路径分隔符等非法字符
     * - 限制长度到 40 字符以内
     * - 为空时回退到 "事件" + id
     */
    private fun sanitizeFileName(input: String, fallback: String = "事件${eventId}"): String {
        val cleaned = input.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_')
        return if (cleaned.isEmpty()) fallback
        else cleaned.take(40)
    }

    /** 事件类型中文名(属性弹窗用)。 */
    private fun typeLabel(type: EventType): String = when (type) {
        EventType.SCREEN_RECORD -> "录屏"
        EventType.PHOTO -> "拍照"
        EventType.CAMERA_VIDEO -> "录像"
    }

    companion object {
        private const val EXTRA_SEGMENTS = "segments"
        private const val EXTRA_EVENT_ID = "event_id"
        private const val EXTRA_TYPE = "event_type"
        private const val EXTRA_EVENT_IDS = "event_ids"
        private const val EXTRA_CURRENT_INDEX = "current_index"
        private const val MENU_REMARK = 1
        private const val MENU_INFO = 2
        private const val MENU_DELETE = 3

        /** 单事件回放(实时回放 / 最近事件 等)。 */
        fun launch(ctx: Context, segments: List<String>, eventId: Long, type: EventType = EventType.SCREEN_RECORD) {
            ctx.startActivity(android.content.Intent(ctx, PlaybackActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_SEGMENTS, ArrayList(segments))
                putExtra(EXTRA_EVENT_ID, eventId)
                putExtra(EXTRA_TYPE, type)
            })
        }

        /** 相册回放:传入事件 id 列表 + 当前索引,支持左右滑切换。 */
        fun launchWithList(
            ctx: Context,
            events: List<ReplayEvent>,
            currentIndex: Int,
        ) {
            if (events.isEmpty()) return
            val cur = events[currentIndex]
            ctx.startActivity(android.content.Intent(ctx, PlaybackActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_SEGMENTS, ArrayList(cur.segments))
                putExtra(EXTRA_EVENT_ID, cur.id)
                putExtra(EXTRA_TYPE, cur.type)
                putExtra(EXTRA_EVENT_IDS, events.map { it.id }.toLongArray())
                putExtra(EXTRA_CURRENT_INDEX, currentIndex)
            })
        }
    }
}
