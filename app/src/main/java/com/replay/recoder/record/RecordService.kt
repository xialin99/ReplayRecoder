package com.replay.recoder.record

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.replay.recoder.ui.FloatingControl
import com.replay.recoder.ui.MainActivity
import com.replay.recoder.record.WatermarkConfig
import com.replay.recoder.ui.NotificationHelper
import com.replay.recoder.ui.WatermarkControl

/**
 * 录制前台服务。
 *
 * 启动流程:
 * 1. [MainActivity] 拿到 MediaProjection 授权码(requestCode/resultCode/data)
 * 2. startForegroundService → 本服务 onCreate → startForeground(屏幕采集类型)
 * 3. 启动 [ScreenCapturer] + [SegmentWriter],循环分段落盘
 * 4. stop:冻结快照 → 存事件 → 释放采集
 *
 * 鸿蒙4:前台服务类型 mediaProjection 必须显式声明,否则运行时崩。
 */
class RecordService : Service() {

    private var capturer: ScreenCapturer? = null
    private var audioCapturer: AudioCapturer? = null
    private var writer: SegmentWriter? = null
    private var segmentManager: SegmentManager? = null
    private var projection: MediaProjection? = null
    private var floating: FloatingControl? = null
    private var watermark: WatermarkControl? = null
    private val notif by lazy { NotificationHelper(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    /** stopRecording 防重入标志:连点停止时第二次直接返回,避免后台清理线程并发执行 */
    private val stopPending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 录屏通道是否被系统意外中断(MediaProjection onStop 而非用户主动停录):
     *  中断时事件仍会保存,但要用 Toast 告知用户"录制提前结束",避免"录了很久却只有一小段"的困惑。 */
    private val projectionLost = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 锁屏自动停录:录制中屏幕熄灭 → 停录保存事件;亮屏 → 自动开始新录制。
     *  亮屏自动开录需要保存上次授权的 resultCode/data(进程未被杀时有效)。 */
    private var savedResultCode: Int = 0
    private var savedResultData: Intent? = null
    private var savedWindowMin: Int = 5

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (RecordState.isRecording) {
                        Log.e(TAG, "屏幕熄灭,自动停录保存事件(保留服务和悬浮球)")
                        stopRecording(fromScreenLock = true)
                    }
                }
                Intent.ACTION_SCREEN_ON -> {
                    // 亮屏自动开录:轮询等待 stopPending 释放(后台合并可能还在跑),然后启动新录制
                    // 单次延迟2秒在合并慢时不够,改用每秒轮询一次,最多等30秒
                    if (!RecordState.isRecording && savedResultData != null) {
                        Log.e(TAG, "屏幕点亮,轮询等待合并完成后自动开录")
                        // 亮屏后立即显示 idle 悬浮球作为占位:锁屏停录已 hide 旧球,
                        // 此处先显示一个蓝球让用户看到 App 还在;自动开录成功时
                        // startRecording 会重建悬浮球(FloatingControl 防叠加会先 hide 此球)
                        if (floating == null) {
                            floating = FloatingControl(this@RecordService).also { it.show() }
                        }
                        val handler = mainHandler
                        val maxRetries = 30
                        val retryRunnable = object : Runnable {
                            var count = 0
                            override fun run() {
                                count++
                                if (RecordState.isRecording || savedResultData == null) {
                                    Log.e(TAG, "亮屏开录取消:已在录制或授权丢失")
                                    return
                                }
                                if (!stopPending.get()) {
                                    Log.e(TAG, "stopPending已释放(count=$count),启动新录制")
                                    val restartIntent = Intent(this@RecordService, RecordService::class.java).apply {
                                        action = ACTION_START
                                        putExtra(EXTRA_RESULT_CODE, savedResultCode)
                                        putExtra(EXTRA_RESULT_DATA, savedResultData)
                                        putExtra(EXTRA_WINDOW_MIN, savedWindowMin)
                                    }
                                    startService(restartIntent)
                                } else if (count >= maxRetries) {
                                    Log.e(TAG, "亮屏开录超时(30s),合并仍未完成,放弃自动开录")
                                } else {
                                    handler.postDelayed(this, 1000L)
                                }
                            }
                        }
                        handler.postDelayed(retryRunnable, 1000L)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // ★ 不在 onCreate 里 startForeground:onCreate 拿不到 action,而前台服务类型必须与
        // 实际用途匹配。targetSdk 34 下,保活/停止路径没有 MediaProjection 授权,
        // 用 mediaProjection 类型启动会抛 SecurityException 并触发 ForegroundServiceDidNotStartInTime 崩溃。
        // 因此改为在 onStartCommand 按 action 选择类型(见下)。
        // 锁屏/亮屏广播:录制中熄屏自动停录,亮屏自动开新录制
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)
        Log.e(TAG, "RecordService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 系统 sticky 重启时 intent 为 null 且无 action:此时我们已丢失 MediaProjection 授权
        // (resultCode/data 随进程销毁回收),无法继续录,只能清理并退出。
        if (intent == null) {
            if (RecordState.isRecording) {
                Log.e(TAG, "系统 sticky 重启但 MediaProjection 已失效,停止空转")
                RecordState.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                // 只有真正开始录屏才用 mediaProjection 类型;这是唯一有投影授权的路径。
                startForegroundTyped(
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                    notif.buildRecordingNotification(
                        intent.getIntExtra(EXTRA_WINDOW_MIN, RecordState.windowMinutes)
                    )
                )
                startRecording(intent)
            }
            ACTION_STOP -> {
                // stopIntent 由 startForegroundService 送达(通知栏停止/关闭常驻),
                // 必须立即 startForeground 满足前台服务契约;类型按当前是否在录制选择。
                if (RecordState.isRecording) {
                    startForegroundTyped(
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                        notif.buildRecordingNotification(RecordState.windowMinutes)
                    )
                } else {
                    startForegroundTyped(
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                        notif.buildKeepAliveNotification()
                    )
                }
                // 彻底停止(autoRestart=false):override 配置,停录后不自动重启
                val allowAutoRestart = intent.getBooleanExtra(EXTRA_ALLOW_AUTO_RESTART, true)
                stopRecording(allowAutoRestart = allowAutoRestart)
            }
            ACTION_SET_WINDOW -> {
                val min = intent.getIntExtra(EXTRA_WINDOW_MIN, 5)
                segmentManager?.setWindow(min)
                RecordState.setWindow(min)
                notifyWindowChanged(min)
            }
            ACTION_KEEPALIVE -> startKeepAlive()
        }
        // 录制中用 STICKY:系统资源紧张杀掉后会尝试重启(见上 null 分支兜底)。
        // 不录制时 NOT_STICKY,避免空跑。
        return if (RecordState.isRecording) START_STICKY else START_NOT_STICKY
    }

    private fun startRecording(intent: Intent) {
        if (RecordState.isRecording) {
            Log.e(TAG, "已在录制,忽略")
            return
        }
        // 上一次停录的后台合并/释放尚未完成:此时启动新录制会导致旧 projection.stop()
        // 在 finally 块中把新 projection 也 null 掉,触发 onStop 链式崩溃("系统结束")。
        if (stopPending.get()) {
            Log.e(TAG, "上一次停录仍在处理中,延迟重启")
            android.widget.Toast.makeText(
                this, "上一次事件仍在生成中,请稍候", android.widget.Toast.LENGTH_SHORT
            ).show()
            // ★ 不能直接 stopSelf:后台合并/排空编码器还在跑,杀掉服务会截断尾帧、
            // 丢失刚 signalEndOfStream 的样本,事件直接丢失。改为等合并完成后重试启动。
            retryStartWhenIdle(intent)
            return
        }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: run {
            Log.e(TAG, "无 MediaProjection data,无法启动")
            stopSelf()
            return
        }
        val windowMin = intent.getIntExtra(EXTRA_WINDOW_MIN, 5)

        // 保存授权数据供亮屏自动开录使用(进程未被杀时有效)
        savedResultCode = resultCode
        savedResultData = data
        savedWindowMin = windowMin

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // ★ 自动重启场景复用上一轮仍存活的 projection:
        // 1) 每轮重新 getMediaProjection 会泄漏旧的采集会话;
        // 2) Android 14 起同一授权 data 只能 getMediaProjection 一次,重复调用返回 null。
        val existing = projection
        val proj = existing ?: run {
            val p = runCatching { mpm.getMediaProjection(resultCode, data) }.getOrNull()
            if (p == null) {
                Log.e(TAG, "getMediaProjection 返回 null(授权可能已失效)")
                android.widget.Toast.makeText(
                    this, "录屏授权已失效,请重新打开 App 授权", android.widget.Toast.LENGTH_LONG
                ).show()
                stopSelf()
                return
            }
            p
        }
        projection = proj
        projectionLost.set(false)
        // 复用的 projection 已注册过回调,不重复注册
        if (existing == null) proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // 忽略旧 projection 的延迟回调:projection 已指向新实例时跳过,避免误停新录制
                if (projection !== proj) {
                    Log.e(TAG, "忽略旧 MediaProjection 的 onStop 回调")
                    return
                }
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isInteractive) {
                    // ★ 锁屏触发的系统停投影是预期行为:不再标记"被系统中断",
                    // 否则每次熄屏都会弹"采集被中断",通知栏也一直显示录制中。
                    Log.e(TAG, "MediaProjection 因锁屏停止(预期)")
                    stopRecording(fromScreenLock = true)
                    return
                }
                // 屏幕亮着却被系统停止:视为真正的中断,保存事件并提示用户。
                // 该 projection 已失效,必须清空引用,否则自动重启会复用已停止的实例
                // (createVirtualDisplay 直接抛异常),下次启动需重新 getMediaProjection。
                Log.e(TAG, "MediaProjection 被系统意外停止")
                projection = null
                projectionLost.set(true)
                lastProjectionLost = true  // 同步到 companion object 供 MainActivity.onResume 检测
                stopRecording()
            }
        }, null)

        // 屏幕参数
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = ensureEven(metrics.widthPixels)
        val height = ensureEven(metrics.heightPixels)
        val dpi = metrics.densityDpi

        // 读录制配置:画质决定码率,声源决定 AudioCapturer 行为
        val recCfg = RecordConfig.get(this)
        val quality = recCfg.quality
        // 按面积缩放基准码率,quality 提供档位上限,再按面积缩放后夹紧
        val baseRate = (width * height * 4).coerceIn(2_000_000, quality.bitrate)
        val bitRate = baseRate
        val audioSource = recCfg.audioSource
        Log.e(TAG, "录制配置 quality=$quality bitrate=$bitRate audioSource=$audioSource")

        val sm = SegmentManager(this, windowMin)
        segmentManager = sm
        // audioEnabled:静音录制时 muxer 不挂音频轨,SegmentWriter 也走无音频路径
        val audioEnabled = audioSource != RecordConfig.AudioSource.MUTE
        // 段切换回调:请求 I 帧,确保新段首帧可独立解码。
        // freezeSnapshot 用 Mp4Merger(MediaExtractor→MediaMuxer)零重编码拼接,
        // 段边界处的帧类型原样保留:若新段首帧是 P 帧,合并产物在该处解码无参考帧
        // → 画面冻结(进度条/音频照常推进)。强制新段首帧为 I 帧后边界即独立可解码点。
        // 回调在 SegmentWriter 持锁的 drain 线程触发,capturer 字段在 cap.start 前已赋值。
        val sw = SegmentWriter(sm, SegmentManager.SEGMENT_SEC, audioEnabled = audioEnabled) {
            capturer?.requestSyncFrame()
        }
        writer = sw
        // MIC 模式不需要 projection;SYSTEM/MIXED 需要传;MUTE 不会真正启动音频(在 AudioCapturer 内部短路)
        val acap = when (audioSource) {
            RecordConfig.AudioSource.MIC -> AudioCapturer(projection = null, source = AudioCapturer.Source.MIC)
            RecordConfig.AudioSource.SYSTEM -> AudioCapturer(projection = proj, source = AudioCapturer.Source.SYSTEM)
            RecordConfig.AudioSource.MIXED -> AudioCapturer(projection = proj, source = AudioCapturer.Source.MIXED)
            RecordConfig.AudioSource.MUTE -> AudioCapturer(projection = null, source = AudioCapturer.Source.MUTE)
        }
        audioCapturer = acap

        RecordState.start(windowMin, sm.current)
        // ★ 编码器降级重试(跨厂商加固):
        // 部分机型硬编不支持当前分辨率/码率(1440p+ 高刷屏、低端芯片、老华为 MTK 机型),
        // configure/start 直接抛异常 → 录屏完全不可用。
        // 逐级降分辨率+码率(100% → 75% → 50%)重试,"录得出来"优先于"录得最清晰"。
        // 每次失败必须新建 ScreenCapturer(其内部 catch 已释放 codec/Surface)。
        val scales = floatArrayOf(1f, 0.75f, 0.5f)
        var cap: ScreenCapturer? = null
        var usedScale = 1f
        var lastErr: Throwable? = null
        for (scale in scales) {
            val w = ensureEven((width * scale).toInt())
            val h = ensureEven((height * scale).toInt())
            val br = (bitRate * scale).toInt().coerceAtLeast(1_000_000)
            val candidate = ScreenCapturer(proj, w, h, dpi, br)
            try {
                candidate.start(sw)
                cap = candidate
                usedScale = scale
                break
            } catch (t: Throwable) {
                lastErr = t
                Log.e(TAG, "采集启动失败(${w}x${h} @${br}bps),尝试降级重试", t)
            }
        }
        if (cap == null) {
            // 全部档位失败:清理并退出,明确告知用户(不再静默退出)
            Log.e(TAG, "屏幕采集启动失败(全部档位),清理并退出", lastErr)
            android.widget.Toast.makeText(
                this, "录制启动失败:当前设备编码器不可用", android.widget.Toast.LENGTH_LONG
            ).show()
            RecordState.stop()
            runCatching { proj.stop() }
            projection = null
            stopPending.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        capturer = cap
        if (usedScale < 1f) {
            Log.w(TAG, "编码器降级生效: scale=$usedScale")
            android.widget.Toast.makeText(
                this, "当前设备不支持原分辨率录制,已自动降低画质", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
        acap.start(sw)
        // 更新通知文案为实际窗口,显示悬浮小球
        notifyWindowChanged(windowMin)
        floating = FloatingControl(this).also {
            // service 球:长按未录制时复用 savedResultData 直接开录,不跳首页
            it.onStartRecording = { restartFromSavedAuth() }
            it.onStopRecording = { stopRecording() }
            it.show()
        }
        // 水印 overlay:MediaProjection 会采集到 overlay,无需改 MediaCodec 管线
        watermark = WatermarkControl(this).also { it.show() }
        sendBroadcast(Intent(ACTION_RECORDING_STATE_CHANGED).putExtra(EXTRA_RECORDING, true))
        Log.e(TAG, "录制已启动 window=${windowMin}min")
    }

    /** 停录:冻结快照 → 存事件 → 释放。返回事件 id 给回放用。
     *  Android 14+ 上主线程做 file IO/codec 释放容易触发 ANR 与"按钮状态不更新",
     *  因此把 IO/释放挪到后台线程,仅状态广播在主线程。
     *
     *  @param fromScreenLock 是否由锁屏触发。true 时不调 stopSelf()、不隐藏悬浮球,
     *  保持服务存活等亮屏自动开录;false 为用户主动停录,正常清理+stopSelf。 */
    private fun stopRecording(fromScreenLock: Boolean = false, allowAutoRestart: Boolean = true) {
        // 防重入:连点停止 / 双击音量键 + 通知按钮同时触发时,
        // 第二次进来直接返回,避免 RecStopThread 并发执行导致 sm.clear 与段拷贝重叠。
        if (!stopPending.compareAndSet(false, true)) {
            Log.e(TAG, "stopRecording 已在进行中,忽略重复调用 fromScreenLock=$fromScreenLock")
            return
        }
        if (!RecordState.isRecording) {
            stopPending.set(false)
            // 锁屏时 MediaProjection.onStop 会触发 stopRecording(false),但此时
            // isRecording 已被 ACTION_SCREEN_OFF 的 stopRecording(true) 置 false,
            // 进到这里。必须检查屏幕状态,锁屏时不 stopSelf 保持服务存活
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val screenOn = pm.isInteractive
            if (!fromScreenLock && screenOn) stopSelf()
            return
        }
        Log.e(TAG, "停录,冻结快照(后台线程) fromScreenLock=$fromScreenLock")
        // 立即标记停止,防止重复点 stop 触发并发执行
        RecordState.stop()
        // 事件进入后台合并:计数 +1,首页/相册头部立即显示"生成中"占位卡
        RecordState.onEventGenerating()
        // 先发"已停止"广播,UI 立即响应;后续清理在后台做
        sendBroadcast(Intent(ACTION_RECORDING_STATE_CHANGED).putExtra(EXTRA_RECORDING, false))

        // 悬浮球处理:
        // - 锁屏停录:系统会移除 overlay 窗口,必须 hide 并置 null,亮屏后由 startRecording 重建
        //   (保留实例会导致 ballView 引用还在但实际不在屏幕,show() 因 ballView!=null 跳过 addView)
        // - 用户主动停录 + 悬浮球常驻开:切回蓝色 idle 态继续显示,方便长按再次开录
        // - 用户主动停录 + 常驻关:hide 移除
        if (fromScreenLock) {
            floating?.hide(); floating = null
        } else if (WatermarkConfig.isFloatingPersistent(this)) {
            floating?.onStopRecording()
            // floating 不置 null,保持引用持续显示
        } else {
            floating?.hide(); floating = null
        }
        // 水印始终隐藏
        watermark?.hide(); watermark = null

        val sm = segmentManager
        val w = writer
        val cap = capturer
        val acap = audioCapturer
        val proj = projection
        val lockTriggered = fromScreenLock

        // 后台线程执行 IO 密集操作,主线程立即返回不阻塞。
        // 全局 try-catch(Throwable):任何异常(磁盘满/IO/权限)都保证 mainHandler.post 兜底执行,
        // 否则 stopForeground / stopSelf 永远不调用 → 通知栏残留、Service 永久运行。
        Thread {
            var savedEventId = -1L
            try {
                // 先排空编码器:signalEndOfInputStream 让编码器输出所有剩余帧到 SegmentWriter,
                // drain 线程收到 END_OF_STREAM 后退出。此时所有帧已写入 muxer,
                // 再调 w.stop() 关闭 muxer,段文件的视频轨 stbl 不会为空。
                // 此前顺序(w.stop 先于 cap.stop)导致编码器剩余帧丢失 → 空 stbl → 合并崩溃/时长丢失。
                runCatching { cap?.signalEndOfStream() }
                runCatching { w?.stop() }
                // 编码器已排空且 muxer 已关闭,此处只释放资源
                Thread {
                    runCatching { cap?.stop() }
                    runCatching { acap?.stop() }
                }.also { it.isDaemon = true; it.name = "RecReleaseThread" }.start()
                if (sm != null) {
                    val repo = (application as com.replay.recoder.ReplayApp).eventRepo
                    // 先落文件、后写 meta:peekNextId 拿 id → 冻结合并到 event_<id>/ →
                    // 全部就绪后才 createEvent 写 meta。meta 出现前事件对 UI(allEvents/get)
                    // 不可见,首页/相册不会读到还在合并中的半成品 mp4(此前"时长 15s"假象根因)。
                    val eventId = runCatching {
                        kotlinx.coroutines.runBlocking { repo.peekNextId() }
                    }.getOrDefault(-1L)
                    // 用录制开始时间(墙上时钟)作为事件创建时间,而非合并完成时间
                    val recStart = RecordState.startWallClock
                    val frozen = if (eventId > 0) sm.freezeSnapshot(eventId) else sm.current
                    if (eventId > 0 && frozen.isEmpty()) {
                        // 所有段都无效(磁盘异常/停录过快):不创建空事件。
                        // 否则相册会出现一条没有任何可播文件、时长 --:-- 的假事件。
                        Log.w(TAG, "冻结结果为空,跳过事件创建 eventId=$eventId")
                        runCatching {
                            java.io.File(java.io.File(filesDir, "events"), "event_$eventId").deleteRecursively()
                        }
                    } else if (eventId > 0) {
                        savedEventId = runCatching {
                            kotlinx.coroutines.runBlocking {
                                repo.createEvent(
                                    segments = frozen,
                                    windowMinutes = RecordState.windowMinutes,
                                    expectedId = eventId,
                                    createdAt = recStart,
                                )
                            }
                        }.getOrDefault(-1L)
                    } else {
                        // peek 失败的兜底:走旧流程(先建事件再补段路径)
                        val fallbackId = runCatching {
                            kotlinx.coroutines.runBlocking {
                                repo.createEvent(
                                    segments = sm.current,
                                    windowMinutes = RecordState.windowMinutes,
                                    createdAt = recStart,
                                )
                            }
                        }.getOrDefault(-1L)
                        if (fallbackId > 0) {
                            val f = sm.freezeSnapshot(fallbackId)
                            runCatching {
                                kotlinx.coroutines.runBlocking { repo.setSegments(fallbackId, f) }
                            }
                            savedEventId = fallbackId
                        }
                    }
                    sm.clear()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "停录后台清理异常,执行兜底清理", t)
            } finally {
                // 主线程收尾:清字段、停前台、stopSelf。无论是否异常都执行。
                mainHandler.post {
                    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    val screenOn = pm.isInteractive
                    val autoRestart = RecordConfig.get(this@RecordService).autoRestart && allowAutoRestart
                    val autoRestartNow = !lockTriggered && screenOn && autoRestart && savedResultData != null
                    // 只清自己引用的字段,不覆盖可能已由新 startRecording 赋值的新实例。
                    // 自动重启时保留 projection(会话仍存活,下一轮直接复用,避免泄漏)。
                    if (projection === proj && !autoRestartNow) projection = null
                    if (segmentManager === sm) segmentManager = null
                    if (writer === w) writer = null
                    if (capturer === cap) capturer = null
                    if (audioCapturer === acap) audioCapturer = null
                    // 锁屏时 projection 已被系统 onStop 停止,不能再调 proj.stop():
                    // 否则 savedResultData 的 Intent 会被标记失效,第二次 getMediaProjection
                    // 返回 null,亮屏后无法自动重启录制(华为/鸿蒙已复现)
                    // 锁屏停录保持前台服务:避免系统回收导致 savedResultData 丢失,
                    // 亮屏后 screenReceiver 仍可工作;用户主动停录才移除前台+stopSelf
                    if (lockTriggered) {
                        // 已停止录制但服务仍需存活:把通知从"正在录制"换成保活提示,
                        // 否则锁屏后通知栏一直显示正在进行录制。
                        startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                            notif.buildKeepAliveNotification())
                    }
                    if (!lockTriggered && screenOn && !autoRestartNow) {
                        // 正常停录收尾:stop proj + (常驻→保活通知 / 非常驻→stopSelf)
                        runCatching { proj?.stop() }
                        if (WatermarkConfig.isFloatingPersistent(this@RecordService)) {
                            startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                                notif.buildKeepAliveNotification())
                        } else {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    } else if (autoRestartNow) {
                        // 停录后自动重启:不 stop proj 保持 MediaProjection 会话活跃,
                        // 不 stopForeground 保持前台,等 stopPending 释放后延迟 3 秒重启录制。
                        // proj 不 stop 的情况下 resultData 还有效,可重新 getMediaProjection。
                        Log.e(TAG, "自动重启模式:保留 projection,3秒后开新录制")
                        // 悬浮球切回 idle 蓝球态(停录后录制控制隐藏,悬浮球只是占位)
                        floating?.onStopRecording()
                    }
                    stopPending.set(false)
                    // 自动重启:停录保存完后延迟 3 秒开新录制(锁屏停录不走这里,screenOn=false)
                    if (autoRestartNow) {
                        mainHandler.postDelayed({
                            if (RecordState.isRecording) return@postDelayed  // 已被手动开录,跳过
                            if (stopPending.get()) return@postDelayed       // 又触发了停录,跳过
                            Log.e(TAG, "3秒到,自动启动新录制")
                            val restartIntent = Intent(this@RecordService, RecordService::class.java).apply {
                                action = ACTION_START
                                putExtra(EXTRA_RESULT_CODE, savedResultCode)
                                putExtra(EXTRA_RESULT_DATA, savedResultData)
                                putExtra(EXTRA_WINDOW_MIN, savedWindowMin)
                            }
                            runCatching { startService(restartIntent) }
                        }, 3000L)
                    }
                    // 无论成败都减一:失败也撤占位卡,避免计数泄漏占位卡永远挂着
                    RecordState.onEventGenerated()
                    // 事件真正落盘后才广播:首页/相册收到后再刷新,保证读到的 meta 与 mp4 都是完整的
                    if (savedEventId > 0) {
                        sendBroadcast(
                            Intent(ACTION_EVENT_SAVED).putExtra(EXTRA_EVENT_ID, savedEventId)
                        )
                    }
                    // 录屏通道被系统意外中断:明确告知用户录制提前结束,避免"录了很久只有一小段"的困惑
                    if (projectionLost.getAndSet(false)) {
                        android.widget.Toast.makeText(
                            this,
                            "屏幕采集被系统中断,录制已提前结束并保存",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    Log.e(TAG, "停录完成(后台清理结束) eventId=$savedEventId")
                }
            }
        }.also { it.isDaemon = true; it.name = "RecStopThread" }.start()
    }

    /** service 球长按开录:复用上次保存的 MediaProjection 授权数据直接启动录制。
     *  若授权已失效(savedResultData 为 null)则退化为打开 App 让用户手动授权。 */
    private fun restartFromSavedAuth() {
        if (RecordState.isRecording || stopPending.get()) return
        val data = savedResultData
        if (data == null) {
            Log.e(TAG, "savedResultData 已失效,打开 App 让用户重新授权")
            val home = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(home)
            return
        }
        Log.e(TAG, "悬浮球长按:复用 savedResultData 重新开录")
        val intent = Intent(this, RecordService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, savedResultCode)
            putExtra(EXTRA_RESULT_DATA, data)
            putExtra(EXTRA_WINDOW_MIN, RecordState.windowMinutes)
        }
        startRecording(intent)
    }

    /** 统一 startForeground:显式指定前台服务类型,try-catch 防止类型不匹配崩溃。
     *  Android 14+(API 34)强制要求 startForeground 类型与 manifest 声明匹配,否则抛
     *  ForegroundServiceTypeNotAllowed 崩溃。保活用 specialUse,录屏用 mediaProjection。
     *  ★ 额外叠加 camera 类型:悬浮相机在 App 退到后台后仍要能开相机,
     *    而 Android 11+ 只允许"声明了 camera 类型的前台服务"在后台使用相机。
     *  API < 34 直接用两参版本(类型取自 manifest 声明,已含 camera)。 */
    private fun startForegroundTyped(type: Int, notification: Notification) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID, notification,
                    type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startForeground(type=$type) 失败,去掉 camera 类型重试", t)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIF_ID, notification, type)
                } else {
                    startForeground(NOTIF_ID, notification)
                }
            } catch (t2: Throwable) {
                Log.e(TAG, "startForeground 重试也失败,放弃前台保活", t2)
            }
        }
    }

    /** 悬浮球常驻保活:启动前台服务显示保活通知,不采集。
     *  录制中被调用则忽略(已在前台)。 */
    private fun startKeepAlive() {
        if (RecordState.isRecording) return
        Log.e(TAG, "启动悬浮球保活模式")
        startForegroundTyped(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            notif.buildKeepAliveNotification())
    }

    /** 用户划掉最近任务(三星 MIUI OneUI 等):录制中重启服务防止被彻底杀进程。
     *  只在真正录制中且不在 freezeSnapshot 后台合并中重启——
     *  freeze 中重启会导致段文件被双重操作和数据损坏。
     *  用 START_STICKY 已能覆盖大部分场景,此 override 是额外保险。
     *
     *  ★ 跨厂商加固:
     *  1. Android 12+(API 31+)禁止后台启动前台服务,此处 startForegroundService 可能抛
     *     ForegroundServiceStartNotAllowedException → 必须 runCatching,否则服务在
     *     onTaskRemoved 里直接崩溃,反而加速被杀。
     *  2. 重启 Intent 必须带 ACTION_START + 保存的授权数据;直接拷贝 rootIntent
     *     (action=MAIN)会在 onStartCommand 里空转,服务起了却不录。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "onTaskRemoved isRecording=${RecordState.isRecording} stopPending=$stopPending")
        if (RecordState.isRecording && !stopPending.get() && rootIntent != null
            && savedResultData != null
        ) {
            val restart = Intent(this, RecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, savedResultCode)
                putExtra(EXTRA_RESULT_DATA, savedResultData)
                putExtra(EXTRA_WINDOW_MIN, savedWindowMin)
            }
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(this, restart)
            }.onFailure {
                Log.w(TAG, "onTaskRemoved 重启服务失败(Android12+ 后台启动限制),依赖 STICKY 兜底", it)
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "RecordService onDestroy savedData=${savedResultData != null} isRecording=${RecordState.isRecording}")
        runCatching { unregisterReceiver(screenReceiver) }
        if (RecordState.isRecording && !stopPending.get()) {
            // ★ 录制中被销毁(系统回收/划掉任务):先走正常停录流程冻结事件,
            // 否则只 stop 组件会让段文件成孤儿、用户录像直接丢失。
            // 资源释放与事件入库由 stopRecording 的后台线程完成,此处不能重复 stop 组件。
            Log.w(TAG, "录制中 onDestroy,先冻结当前事件再退出")
            stopRecording()
        } else if (!RecordState.isRecording && !stopPending.get()) {
            runCatching { capturer?.stop() }
            runCatching { audioCapturer?.stop() }
            runCatching { writer?.stop() }
            RecordState.stop()
        }
        floating?.hide()
        watermark?.hide()
        Log.e(TAG, "RecordService onDestroy")
    }

    /** 停录合并尚未完成时,隔 1 秒重试启动,最多等 30 秒;期间不在录制才真正启动。 */
    private fun retryStartWhenIdle(intent: Intent, attempt: Int = 0) {
        if (!stopPending.get()) {
            if (!RecordState.isRecording) startRecording(intent)
            return
        }
        if (attempt >= 30) {
            Log.e(TAG, "等待上次停录完成超时(30s),放弃本次启动")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        mainHandler.postDelayed({ retryStartWhenIdle(intent, attempt + 1) }, 1000L)
    }

    // ---------- 通知 ----------

    private fun notifyWindowChanged(windowMin: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, notif.buildRecordingNotification(windowMin))
    }

    private fun ensureEven(v: Int): Int = if (v % 2 == 0) v else v - 1

    companion object {
        private const val TAG = "RecordService"
        private const val NOTIF_ID = 1001

        /** 上次 MediaProjection 授权是否被系统回收(鸿蒙/vivo 长时间录制会触发)。
         *  MainActivity.onResume 读取后用 clearProjectionLost() 清零,保证 Toast 只弹一次。 */
        @Volatile
        var lastProjectionLost: Boolean = false
            private set
        fun clearProjectionLost() { lastProjectionLost = false }

        const val ACTION_START = "com.replay.recoder.action.START"
        const val ACTION_STOP = "com.replay.recoder.action.STOP"
        const val ACTION_SET_WINDOW = "com.replay.recoder.action.SET_WINDOW"
        /** 悬浮球常驻保活:不录制,仅维持前台服务防止进程被杀。 */
        const val ACTION_KEEPALIVE = "com.replay.recoder.action.KEEPALIVE"
        const val ACTION_RECORDING_STATE_CHANGED = "com.replay.recoder.action.RECORDING_STATE_CHANGED"
        /** 事件已完整落盘(meta + mp4 全部就绪):首页/相册收到后再刷新,避免读到合并中的半成品 */
        const val ACTION_EVENT_SAVED = "com.replay.recoder.action.EVENT_SAVED"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_RECORDING = "recording"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WINDOW_MIN = "window_min"
        const val EXTRA_ALLOW_AUTO_RESTART = "allow_auto_restart"

        fun startIntent(
            ctx: Context, resultCode: Int, data: Intent, windowMin: Int
        ): Intent = Intent(ctx, RecordService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_RESULT_CODE, resultCode)
            putExtra(EXTRA_RESULT_DATA, data)
            putExtra(EXTRA_WINDOW_MIN, windowMin)
        }

        fun stopIntent(ctx: Context, allowAutoRestart: Boolean = true): Intent =
            Intent(ctx, RecordService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_ALLOW_AUTO_RESTART, allowAutoRestart)
            }
    }
}
