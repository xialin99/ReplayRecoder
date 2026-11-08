package com.replay.recoder.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.replay.recoder.R
import com.replay.recoder.ReplayApp
import com.replay.recoder.databinding.ActivityMainBinding
import com.replay.recoder.databinding.ItemRecentEventAllBinding
import com.replay.recoder.databinding.ItemRecentEventBinding
import com.replay.recoder.databinding.ItemRecentEventLoadingBinding
import com.replay.recoder.event.EventType
import com.replay.recoder.event.ReplayEvent
import com.replay.recoder.record.RecordService
import com.replay.recoder.record.RecordState
import com.replay.recoder.record.WatermarkConfig
import com.replay.recoder.record.WindowPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 主界面:开录/停录、回放窗口档位、进入回放。
 *
 * 鸿蒙4 权限顺序:
 * 1. POST_NOTIFICATIONS(Android 13+,鸿蒙4 兼容)
 * 2. SYSTEM_ALERT_WINDOW(悬浮小球,阶段2,引导用户去设置开)
 * 3. MediaProjection 授权(系统弹窗,授权后启动 [RecordService])
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    /** 常驻悬浮球:由 [WatermarkConfig.isFloatingPersistent] 控制。
     *  开 → App 在前台时显示(idle 静态),录制时由 RecordService 接管加脉冲。
     *  关 → 不显示,录制时由 RecordService 临时显示。 */
    private var idleFloating: FloatingControl? = null

    /** setupRecentEvents 最近一次启动的刷新协程。
     *  连续触发时 cancel 旧协程,避免旧协程还在跑但 View 已被 removeAllViews 替换。 */
    private var recentEventsJob: kotlinx.coroutines.Job? = null

    /** 监听 RecordService 录制状态变化,及时刷新按钮(服务异步启动,onResume 来不及) */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshUi()
            // 录制态切换时,常驻悬浮球显示/隐藏要跟着变
            updateIdleFloating()
            when (intent?.action) {
                RecordService.ACTION_EVENT_SAVED -> {
                    // 事件真正落盘(合并完成、meta 写好):占位卡计数已在 service 侧减一,刷新最近事件
                    setupRecentEvents()
                }
                RecordService.ACTION_RECORDING_STATE_CHANGED -> {
                    // 刚停录:事件进入后台合并(RecordState 计数已 +1),
                    // 刷新最近事件——头部按计数渲染"生成中"占位卡,已有卡片保留
                    if (intent.getBooleanExtra(RecordService.EXTRA_RECORDING, true) == false) {
                        setupRecentEvents()
                    }
                }
            }
        }
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val windowMin = currentWindow()
            androidx.core.content.ContextCompat.startForegroundService(
                this, RecordService.startIntent(this, result.resultCode, result.data!!, windowMin)
            )
            // 不在此处 refreshUi:服务异步启动,RecordState 尚未更新,
            // 由 stateReceiver 收到服务广播后刷新。
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 通知权限不是开录的硬前提,记录即可;未授权也能录,只是没有前台通知
        refreshUi()
    }

    /** 录音权限:AudioPlaybackCapture 走 MediaProjection,但系统仍要求 RECORD_AUDIO 运行时授权。
     *  被拒也允许继续录(只是无声),AudioCapturer 会自动降级为纯视频。 */
    private val recordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        android.util.Log.i("MainActivity", "RECORD_AUDIO granted=$granted")
        proceedToOverlayCheck()
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshUi() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // 折叠屏/平板(sw600dp+):主界面内容限宽居中,避免按钮被拉过宽
        val maxW = resources.getDimensionPixelSize(R.dimen.content_max_width)
        if (maxW > 0) {
            val lp = b.contentContainer.layoutParams
            lp.width = maxW
            b.contentContainer.layoutParams = lp
            b.contentContainer.gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        setupChips()
        // 录制态两个按钮(停录后自动重启 / 彻底停止);idle 态一个大蓝按钮
        b.btnRecord.setOnClickListener {
            if (!RecordState.isRecording) onStartClick()
        }
        b.btnStopFreeze.setOnClickListener { onStopClick() }
        b.btnStopFinal.setOnClickListener { onStopFinalClick() }
        // "工具"入口(原回放按钮):进入工具箱二级菜单(回放/WiFi 信号强度等)
        b.btnPlayback.setOnClickListener {
            startActivity(android.content.Intent(this, ToolboxActivity::class.java))
        }
        b.btnCamera.setOnClickListener {
            startActivity(android.content.Intent(this, CameraActivity::class.java))
        }
        b.btnGallery.setOnClickListener { EventListActivity.launch(this) }
        b.btnSettings.setOnClickListener {
            startActivity(android.content.Intent(this, SettingsActivity::class.java))
        }
        refreshUi()
    }

    /** 水印设置已迁出到 [SettingsActivity],首页只保留录制仪表盘 + 导航。 */

    override fun onStart() {
        super.onStart()
        // Android 14+ (API 34) 强制要求 registerReceiver 指定 EXPORTED 标志,
        // 否则抛 SecurityException。本应用只接收本进程内 RecordService 发的广播,用 NOT_EXPORTED。
        // 监听两个 action:录制状态变化(刷新按钮) + 事件落盘完成(刷新最近事件)
        val filter = IntentFilter(RecordService.ACTION_RECORDING_STATE_CHANGED)
        filter.addAction(RecordService.ACTION_EVENT_SAVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(stateReceiver)
        // App 进后台:常驻模式下保持悬浮球显示(用户要求常驻就一直在)。
        // 不在此处 hide;只有开关关或 Activity 销毁时才 hide。
        // 切回前台时 onResume 会按配置同步状态。
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
        // MediaProjection 授权失效(鸿蒙/vivo 长时间录制后系统会回收):
        // RecordService 已置 projectionLost,此处检测并引导用户重新开录。
        if (RecordService.lastProjectionLost) {
            RecordService.clearProjectionLost()
            if (!RecordState.isRecording && RecordState.generatingEventCount == 0) {
                android.widget.Toast.makeText(
                    this, "录屏授权已失效,请重新开始录制", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
        // 常驻悬浮球:App 在前台时按配置显示。
        // 录制中由 RecordService 接管,这里不重复显示。
        updateIdleFloating()
        // 每次回前台都重刷最近事件:停录后新生成的事件能立刻出现
        setupRecentEvents()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity 销毁时 WindowManager token 失效,清理 idle 球避免泄漏。
        // 注意:录制中不在此清理,RecordService 管自己的球。
        if (!RecordState.isRecording) {
            idleFloating?.hide(); idleFloating = null
        }
    }

    /** 按 [WatermarkConfig.isFloatingPersistent] 显示/隐藏常驻悬浮球。
     *  - 录制中:不显示 idle 球,由 RecordService 管(带脉冲)。
     *  - 开关开 + 非录制:显示静态球。
     *  - 开关关:隐藏 idle 球。 */
    private fun updateIdleFloating() {
        if (RecordState.isRecording) {
            // 录制中:RecordService 已在管悬浮球,隐藏 idle 副本避免叠两个球
            idleFloating?.hide(); idleFloating = null
            return
        }
        val shouldShow = WatermarkConfig.isFloatingPersistent(this)
        if (shouldShow && idleFloating == null) {
            idleFloating = FloatingControl(this).also {
                // 长按未录制 → 走 onStartClick 权限流程开录
                it.onStartRecording = { onStartClick() }
                it.onStopRecording = {
                    androidx.core.content.ContextCompat.startForegroundService(
                        this, RecordService.stopIntent(this)
                    )
                }
                it.show()
            }
            // 启动保活前台服务:维持进程优先级,防止悬浮球被系统回收
            androidx.core.content.ContextCompat.startForegroundService(
                this, Intent(this, RecordService::class.java).apply {
                    action = RecordService.ACTION_KEEPALIVE
                }
            )
        } else if (!shouldShow && idleFloating != null) {
            idleFloating?.hide(); idleFloating = null
            // 关闭常驻:停掉保活服务
            androidx.core.content.ContextCompat.startForegroundService(
                this, RecordService.stopIntent(this)
            )
        }
    }

    private fun setupChips() {
        val chips = mapOf(
            b.chip30s to 0, b.chip1 to 1, b.chip2 to 2, b.chip5 to 5,
            b.chip10 to 10, b.chip30 to 30, b.chip60 to 60
        )
        chips.forEach { (chip, min) ->
            chip.setOnClickListener {
                refreshUi()
            }
        }
    }

    private fun currentWindow(): Int {
        val checkedId = b.chipGroupWindow.checkedChipId
        return when (checkedId) {
            b.chip30s.id -> 0; b.chip1.id -> 1; b.chip2.id -> 2; b.chip5.id -> 5
            b.chip10.id -> 10; b.chip30.id -> 30; b.chip60.id -> 60
            else -> 5
        }
    }

    private fun onStartClick() {
        // 生成中(上一次停录仍在合并)拒绝启动:不拉起系统授权界面,不进权限流程,
        // 直接 Toast 提示。否则用户走完授权流程才被告知"不能录",体验极差。
        if (RecordState.generatingEventCount > 0) {
            android.widget.Toast.makeText(
                this, "上一次事件仍在生成中,请稍候", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        // 1) 通知权限(Android 13+)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // 2) 录音权限(RECORD_AUDIO):系统声音采集需要,被拒则自动降级为纯视频
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            recordAudioLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        proceedToOverlayCheck()
    }

    /** 通知/录音权限就绪后,继续检查悬浮窗 → 电池优化白名单 → MediaProjection。 */
    private fun proceedToOverlayCheck() {
        // 3) 悬浮窗权限(阶段2 用,提前引导)
        if (!Settings.canDrawOverlays(this)) {
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"))
            )
            return
        }
        // 4) 电池优化白名单(P0 保活引导):未加白名单时提示,不强制阻止录制——
        // 但用户确认加白后从系统设置返回时自动继续流程,不要求用户再次点开始
        if (VendorIntents.isBatteryOptimized(this)) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("后台保活提示")
                .setMessage(
                    "检测到未加入电池优化白名单,\n" +
                    "${VendorIntents.friendlyBrand()}系统可能在后台杀掉录制进程。\n\n" +
                    "建议同时开启「自启动管理」(小米/vivo/OPPO 必需)。\n\n" +
                    "是否现在去设置?"
                )
                .setPositiveButton("加白名单") { _, _ ->
                    VendorIntents.requestBatteryOptimization(this)
                }
                // 小米/vivo/OPPO/华为 光加白名单不够,还需自启动权限,否则后台照样被杀
                .setNeutralButton("自启动管理") { _, _ ->
                    VendorIntents.jumpAutoStartSettings(this)
                }
                .setNegativeButton("跳过") { _, _ ->
                    launchMediaProjection()
                }
                .show()
            return
        }
        // 5) MediaProjection 授权
        launchMediaProjection()
    }

    private fun launchMediaProjection() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as android.media.projection.MediaProjectionManager
        mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun onStopClick() {
        // 停止冻结(默认):按 RecordConfig.autoRestart 决定是否重启
        ContextCompat.startForegroundService(this, RecordService.stopIntent(this, allowAutoRestart = true))
    }

    private fun onStopFinalClick() {
        // 彻底停止:停录后不自动重启,覆盖配置
        ContextCompat.startForegroundService(this, RecordService.stopIntent(this, allowAutoRestart = false))
    }

    private fun refreshUi() {
        val recording = RecordState.isRecording
        val generating = RecordState.generatingEventCount > 0
        // FrameLayout 叠两层:按录制状态切 visibility(不改整体布局)
        b.btnRecord.visibility = if (recording) android.view.View.GONE else android.view.View.VISIBLE
        b.recordingButtons.visibility = if (recording) android.view.View.VISIBLE else android.view.View.GONE
        // idle 态蓝按钮文案/图标
        b.btnRecord.text = getString(R.string.btn_start)
        b.btnRecord.setIconResource(R.drawable.ic_record)
        // 生成中(上一次停录仍在合并)置灰 idle 态的开始按钮,避免拉起系统授权后才被告知不可录
        b.btnRecord.isEnabled = !generating && !recording
        b.btnRecord.alpha = if (generating) 0.4f else 1f
        // 录制中(从锁屏恢复后手动停录期间)不置灰录制按钮——用户需要能点停止
        b.btnStopFreeze.isEnabled = true
        b.btnStopFinal.isEnabled = true
        val min = currentWindow()
        b.tvWindow.text = getString(R.string.status_window_summary, WindowPresets.label(min))
        b.tvStatus.text = if (recording)
            getString(R.string.label_status_recording, WindowPresets.label(min))
        else
            getString(R.string.label_status_idle)
        // 状态灯:录制中红、idle 灰
        b.statusDot.setBackgroundResource(
            if (recording) R.drawable.circle_red else R.drawable.circle_gray
        )
        // 提示文案
        b.tvHint.text = if (recording)
            "长按悬浮球可停止,或点下方停止"
        else
            "点开始录制,录完点停止即可回放"
        // 录制中置灰窗口档位,禁止切换
        val chips = listOf(b.chip30s, b.chip1, b.chip2, b.chip5, b.chip10, b.chip30, b.chip60)
        chips.forEach { it.isEnabled = !recording }
        // 录制中显示录制开始时间,空闲隐藏
        b.tvWindowLocked.visibility = if (recording) View.VISIBLE else View.GONE
        if (recording) {
            val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(java.util.Date(RecordState.startWallClock))
            b.tvWindowLocked.text = getString(R.string.status_window_locked, time)
        }
    }

    /**
     * 最近事件横向卡:onResume 时刷新,取最新 3 条 + 末尾"→ 全部"卡。
     * 渐进式两阶段加载,停录后新事件能尽快可见:
     * 1) IO 只读 meta(毫秒级),立即渲染卡片标题骨架;
     * 2) 缩略图/时长并行异步取(MediaMetadataRetriever 阻塞且慢,约数百 ms/条),
     *    每条完成立即更新对应卡,不互相等待。
     */
    private fun setupRecentEvents() {
        val container = b.recentEventsContainer
        val emptyView = b.tvRecentEmpty

        // 取消旧的刷新协程,避免并发写已 detach 的 View
        recentEventsJob?.cancel()
        recentEventsJob = lifecycleScope.launch {
            val repo = (application as ReplayApp).eventRepo
            // 阶段1:仅读 meta,轻量快速
            val events: List<ReplayEvent> = withContext(Dispatchers.IO) {
                repo.allEvents().take(3)
            }

            container.removeAllViews()
            if (events.isEmpty() && RecordState.generatingEventCount == 0) {
                emptyView.visibility = View.VISIBLE
                container.visibility = View.GONE
                return@launch
            }
            emptyView.visibility = View.GONE
            container.visibility = View.VISIBLE

            // 停录合并中:按"生成中"事件数渲染等量占位卡(支持连停多次累加),
            // 落盘完成(EVENT_SAVED 计数减一)后由刷新替换成真实事件卡
            repeat(RecordState.generatingEventCount) {
                val loading = ItemRecentEventLoadingBinding.inflate(layoutInflater, container, false)
                container.addView(loading.root)
            }

            // 卡片骨架:标题立即显示,缩略图空、时长 --:-- 待补
            val bindings = events.map { ev ->
                val item = ItemRecentEventBinding.inflate(layoutInflater, container, true)
                item.tvRemark.text = ev.remark.ifBlank {
                    getString(R.string.title_item_menu, ev.id)
                }
                item.root.setOnClickListener {
                    PlaybackActivity.launch(this@MainActivity, ev.segments, ev.id, ev.type)
                }
                item
            }
            // 末尾"→ 全部"卡:点击跳事件列表
            val all = ItemRecentEventAllBinding.inflate(layoutInflater, container, true)
            all.root.setOnClickListener { EventListActivity.launch(this@MainActivity) }

            // 阶段2:并行解帧/时长,完成一条立即更新对应卡。
            // 极端情况下旧刷新协程还在跑但视图已被 removeAllViews 替换,写已 detach 的视图无副作用。
            events.forEachIndexed { idx, ev ->
                launch {
                    val row = withContext(Dispatchers.IO) {
                        val segPath = ev.segments.firstOrNull()
                        EventRow(
                            ev,
                            segPath?.let { loadEventThumbnail(ev.type, it) },
                            if (ev.type == EventType.PHOTO) "照片"
                            else formatEventDuration(ev.segments)
                        )
                    }
                    bindings.getOrNull(idx)?.let { item ->
                        if (row.thumb != null) item.ivThumb.setImageBitmap(row.thumb)
                        item.tvDuration.text = row.dur
                    }
                }
            }
        }
    }

    /** 取事件首段缩略图(80x80):照片用 BitmapFactory 解码,视频用 MediaMetadataRetriever 取首帧。仅 IO 线程调用。 */
    private fun loadEventThumbnail(type: EventType, path: String): Bitmap? = runCatching {
        if (type == EventType.PHOTO) {
            // 先读边界算采样,再降采样解码,避免全尺寸 jpg OOM
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return@runCatching null
            var sample = 1
            while (w / sample > 160 && h / sample > 160) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeFile(path, opts)
            raw?.let { ThumbnailUtils.extractThumbnail(it, 80, 80) }
        } else {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(path)
            val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            mmr.release()
            frame?.let { ThumbnailUtils.extractThumbnail(it, 80, 80) }
        }
    }.getOrNull()

    /** 事件总时长(mm:ss)。
     *  多段事件(合并降级保存)必须**逐段累加**:只读首段会把 5min 事件显示成 00:15,
     *  用户会误以为"只保留了 15s"(事件82 的感知来源)。单段(正常合并产物)只读一次。
     *  失败返回 "--:--"。仅 IO 线程调用。 */
    private fun formatEventDuration(segments: List<String>): String {
        if (segments.isEmpty()) return "--:--"
        val totalMs = segments.sumOf { segDurationMs(it) }
        if (totalMs <= 0L) return "--:--"
        val total = totalMs / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", total / 60, total % 60)
    }

    /** 单个段/文件的时长(ms),读不出返回 0。仅 IO 线程调用。 */
    private fun segDurationMs(path: String): Long = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(path)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrDefault(0L)

    /** setupRecentEvents 内部数据载体:事件 + 缩略图 + 时长文本。 */
    private data class EventRow(
        val ev: ReplayEvent,
        val thumb: Bitmap?,
        val dur: String,
    )
}
