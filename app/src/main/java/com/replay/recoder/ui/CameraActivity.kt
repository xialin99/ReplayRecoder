package com.replay.recoder.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.PendingRecording
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.core.util.Consumer
import com.google.android.material.button.MaterialButton
import com.replay.recoder.R
import com.replay.recoder.ReplayApp
import com.replay.recoder.editor.VideoEditor
import com.replay.recoder.event.EventRepository
import com.replay.recoder.event.EventType
import com.replay.recoder.record.RecordConfig
import com.replay.recoder.record.RecordState
import com.replay.recoder.record.WatermarkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * 相机页:拍照 + 录像,产物存事件流(filesDir/events/event_<id>/)。
 * - 拍照按钮:点击→确保 Photo 模式(绑 ImageCapture)→ takePhoto(),产物 photo.jpg,事件 type=PHOTO
 * - 录像按钮:IDLE→确保 Video 模式(绑 VideoCapture)→ startRecording();
 *            RECORDING→ stopRecording()。产物 video.mp4,事件 type=CAMERA_VIDEO
 *
 * 用户反馈:不要 TabLayout 切模式,底部就两个按钮。
 *
 * 维持原硬约束:
 * - launchMode=singleInstance 独立任务栈,双击悬浮球关相机后系统自动切回上个应用
 * - isForeground 静态标志供悬浮球双击判断"开/关相机"
 * - 双击关闭广播 ACTION_CLOSE_CAMERA 走 closeReceiver
 *
 * 录像不可用根因修复:
 * - 旧版 bindToLifecycle 异常被 catch 静默吞掉,videoCapture 提前赋值导致后续 startRecording
 *   拿到的 Recorder 未真正绑到 lifecycle,录制启动即失败。
 * - 新版:先 bindToLifecycle 成功再赋值 videoCapture/imageCapture;bindToLifecycle 失败 Toast 提示。
 * - Recorder 质量选择改为带 fallback 的列表,避免某些机型 HIGHEST 不支持直接抛异常。
 */
class CameraActivity : AppCompatActivity() {

    /** 当前已绑定的用例类型。NONE 表示还未绑定。 */
    private enum class Mode { NONE, PHOTO, VIDEO }
    private enum class VideoState { IDLE, STARTING, RECORDING }

    /** 应用级作用域:产物已落盘后的入库/水印必须完成,不能随 Activity 销毁被取消。 */
    private val appScope get() = (application as ReplayApp).appScope

    private lateinit var cameraPreview: PreviewView
    private lateinit var btnClose: MaterialButton
    private lateinit var btnFlash: MaterialButton
    private lateinit var seekZoom: SeekBar
    private lateinit var tvZoom: TextView
    private lateinit var btnPhoto: MaterialButton
    private lateinit var btnVideo: MaterialButton
    private lateinit var tvRecordTimer: TextView
    private lateinit var tvCameraWatermark: TextView

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val mainExec: Executor by lazy { ContextCompat.getMainExecutor(this) }

    private var flashOn = false
    private var mode = Mode.NONE
    private var videoState = VideoState.IDLE

    /** 水印时间格式,预览与产物共用,保证一致 */
    private val wmFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 统一取水印文本:预览在主线程、水印烧录在 IO 线程,SimpleDateFormat 非线程安全,
     *  必须串行化,否则并发 format 会输出错乱时间或抛异常。 */
    private fun wmTimeText(ms: Long = System.currentTimeMillis()): String =
        synchronized(wmFmt) { wmFmt.format(Date(ms)) }

    /** 预览水印时钟:每秒刷新 */
    private val wmHandler = Handler(Looper.getMainLooper())
    private val wmTickRunnable = object : Runnable {
        override fun run() {
            tvCameraWatermark.text = wmTimeText()
            wmHandler.postDelayed(this, 1000)
        }
    }

    /** 录像计时:每秒刷文本 */
    private val timerHandler = Handler(Looper.getMainLooper())
    private var recordStartMs: Long = 0L
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (videoState != VideoState.RECORDING) return
            val elapsed = (SystemClock.elapsedRealtime() - recordStartMs) / 1000
            tvRecordTimer.text = String.format(Locale.getDefault(), "● %02d:%02d", elapsed / 60, elapsed % 60)
            timerHandler.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val TAG = "CameraActivity"
        /** 相机是否正在前台显示。悬浮球双击据此判断"打开相机"还是"关闭相机"。
         *  用静态标志而非 FloatingControl 实例标志:手动按 X 关闭也能复位,
         *  且 RecordService 与 MainActivity 两个 FloatingControl 实例共享。 */
        @JvmStatic
        var isForeground = false
            private set
    }

    /** 接收悬浮球双击关闭广播,finish() 后系统自动显示底下的应用 */
    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingControl.ACTION_CLOSE_CAMERA) {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        cameraPreview = findViewById(R.id.cameraPreview)
        btnClose = findViewById(R.id.btnClose)
        btnFlash = findViewById(R.id.btnFlash)
        seekZoom = findViewById(R.id.seekZoom)
        tvZoom = findViewById(R.id.tvZoom)
        btnPhoto = findViewById(R.id.btnPhoto)
        btnVideo = findViewById(R.id.btnVideo)
        tvRecordTimer = findViewById(R.id.tvRecordTimer)
        tvCameraWatermark = findViewById(R.id.tvCameraWatermark)

        setupPreviewWatermark()

        btnClose.setOnClickListener { finish() }
        btnFlash.setOnClickListener { toggleFlash() }
        btnPhoto.setOnClickListener { onPhotoClick() }
        btnVideo.setOnClickListener { onVideoClick() }

        // 缩放滑块:1.0x ~ 5.0x
        seekZoom.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                setZoom(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // 注册双击关闭广播
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, IntentFilter(FloatingControl.ACTION_CLOSE_CAMERA), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, IntentFilter(FloatingControl.ACTION_CLOSE_CAMERA))
        }

        // CAMERA + RECORD_AUDIO(录像需要)权限
        val need = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.CAMERA)
        }
        val missing = need.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (!missing) startCamera() else ActivityCompat.requestPermissions(this, need, 100)
    }

    override fun onStart() {
        super.onStart()
        // 全屏相机与悬浮相机共用同一颗物理相机:进全屏前先收掉悬浮窗口,
        // 否则两个 Lifecycle 抢同一路相机,华为 HAL 会进入 DISABLED(黑屏)状态。
        runCatching { FloatingCamera.get(this).hide() }
        isForeground = true
        updateVideoButtonState()
    }

    override fun onStop() {
        super.onStop()
        isForeground = false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            finish()
        }
    }

    /** 启动相机:默认绑 Photo 用例(Preview + ImageCapture)。 */
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            ensurePhotoMode()
            camera?.cameraInfo?.hasFlashUnit()?.let { hasFlash ->
                btnFlash.visibility = if (hasFlash) View.VISIBLE else View.GONE
            }
        }, mainExec)
    }

    /**
     * 确保处于 Photo 模式:若当前不是 Photo,unbindAll 后重绑 Preview + ImageCapture。
     * bindToLifecycle 失败时 Toast 提示,不再静默吞异常(录像不可用根因修复)。
     */
    private fun ensurePhotoMode() {
        if (mode == Mode.PHOTO && imageCapture != null) return
        val provider = cameraProvider ?: run {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            return
        }
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(cameraPreview.surfaceProvider)
        }
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        try {
            provider.unbindAll()
            val img = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            // 先 bind 成功再赋值,避免 bind 抛异常时 imageCapture 仍指向未绑定的实例
            camera = provider.bindToLifecycle(this, selector, preview, img)
            imageCapture = img
            videoCapture = null
            mode = Mode.PHOTO
            camera?.cameraControl?.enableTorch(flashOn)
            Log.d(TAG, "Photo 模式绑定成功")
        } catch (e: Exception) {
            Log.e(TAG, "Photo 模式绑定失败", e)
            Toast.makeText(this, "拍照模式初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 确保处于 Video 模式:若当前不是 Video,unbindAll 后重绑 Preview + VideoCapture。
     * Recorder 质量用 fallback 列表,避免 HIGHEST 不支持时直接抛异常。
     * bindToLifecycle 失败返回 false,调用方据此决定是否继续录像。
     */
    private fun ensureVideoMode(): Boolean {
        if (mode == Mode.VIDEO && videoCapture != null) return true
        val provider = cameraProvider ?: run {
            Toast.makeText(this, "相机未就绪", Toast.LENGTH_SHORT).show()
            return false
        }
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(cameraPreview.surfaceProvider)
        }
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()
        return try {
            provider.unbindAll()
            // 画质档位跟随录制设置:高→1080p、中→720p、低→480p。
            // 旧版固定用 Quality.HIGHEST(多数机型=4K),30s 就 250MB,体积失控。
            // 仍保留降级链,避免个别机型不支持首选档位时直接抛异常。
            val preferred = when (RecordConfig.get(this).quality) {
                RecordConfig.Quality.HIGH -> Quality.FHD
                RecordConfig.Quality.MEDIUM -> Quality.HD
                RecordConfig.Quality.LOW -> Quality.SD
            }
            val ladder = listOf(preferred, Quality.FHD, Quality.HD, Quality.SD).distinct()
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.fromOrderedList(
                        ladder,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val vid = VideoCapture.withOutput(recorder)
            camera = provider.bindToLifecycle(this, selector, preview, vid)
            videoCapture = vid
            imageCapture = null
            mode = Mode.VIDEO
            camera?.cameraControl?.enableTorch(flashOn)
            Log.d(TAG, "Video 模式绑定成功 quality=$preferred")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Video 模式绑定失败", e)
            Toast.makeText(this, "录像模式初始化失败: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun toggleFlash() {
        flashOn = !flashOn
        btnFlash.setIconResource(if (flashOn) R.drawable.ic_flash_on else R.drawable.ic_flash_off)
        camera?.cameraControl?.enableTorch(flashOn)
    }

    /** 缩放:SeekBar progress 0..40 → 1.0x ~ 5.0x。 */
    private fun setZoom(progress: Int) {
        val zoom = 1f + progress / 10f
        tvZoom.text = String.format(Locale.getDefault(), "%.1fx", zoom)
        camera?.cameraControl?.setZoomRatio(zoom)
    }

    /** 拍照按钮点击:录像中先停录并提示;否则切到 Photo 模式 + takePhoto()。 */
    private fun onPhotoClick() {
        if (videoState == VideoState.RECORDING) {
            // 录像中点拍照:先停录,但不拍照(避免绑模式切换造成中断)
            stopRecording()
            Toast.makeText(this, "已停止录像", Toast.LENGTH_SHORT).show()
            return
        }
        if (videoState == VideoState.STARTING) {
            // 启动窗口期内切模式会 unbindAll 打断录像启动,直接忽略
            return
        }
        ensurePhotoMode()
        takePhoto()
    }

    /** 录像按钮点击:IDLE→确保 Video 模式 + startRecording;RECORDING→stopRecording。 */
    private fun onVideoClick() {
        when (videoState) {
            VideoState.IDLE -> {
                // 录屏中:硬件编码器资源不足,无法同时跑录屏+相机录像,提示用户先停止录屏
                if (RecordState.isRecording) {
                    Toast.makeText(this, "录制中无法录像，请先停止录屏", Toast.LENGTH_SHORT).show()
                    return
                }
                // 录音权限缺失则提前拒
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "录像需要录音权限", Toast.LENGTH_SHORT).show()
                    return
                }
                if (!ensureVideoMode()) {
                    Toast.makeText(this, R.string.toast_recording_failed, Toast.LENGTH_SHORT).show()
                    return
                }
                startRecording()
            }
            VideoState.RECORDING -> stopRecording()
            // 启动中(建事件/目录的 IO 窗口):忽略连点,避免并发启动两个录制
            VideoState.STARTING -> Unit
        }
    }

    /** 拍照:CameraX ImageCapture.takePicture 写入事件目录的 photo.jpg,事件流 type=PHOTO。 */
    private fun takePhoto() {
        val img = imageCapture ?: run {
            Toast.makeText(this, R.string.toast_capture_failed, Toast.LENGTH_SHORT).show()
            return
        }
        // 先创建事件目录(类型 PHOTO),拍照元数据写完后 setSegments 关联文件
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // 用应用级作用域:Activity 销毁不应导致已拍下/已录下的产物无法入库
        appScope.launch {
            // 先建事件拿到 id,再拍照到事件目录
            val eventId = withContext(Dispatchers.IO) {
                eventRepo().createEvent(segments = emptyList(), windowMinutes = 0, type = EventType.PHOTO)
            }
            val eventDir = withContext(Dispatchers.IO) {
                File(File(filesDir, "events"), "event_$eventId").apply { mkdirs() }
            }
            val outFile = File(eventDir, EventRepository.PHOTO_FILE)
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outFile).build()

            img.takePicture(
                outputOptions,
                mainExec,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        appScope.launch {
                            withContext(Dispatchers.IO) {
                                // 先在后台把时间水印烧进 JPEG,再登记事件段路径,
                                // 保证 UI 读到的就是带水印的最终文件
                                burnPhotoWatermark(outFile)
                                eventRepo().setSegments(eventId, listOf(outFile.absolutePath))
                                eventRepo().setRemark(eventId, "拍照 $ts")
                            }
                            Toast.makeText(this@CameraActivity,
                                R.string.toast_photo_saved, Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onError(exception: ImageCaptureException) {
                        exception.printStackTrace()
                        // 拍照失败:删掉刚建的空事件,避免相册里出现 0 段的僵尸条目
                        appScope.launch(Dispatchers.IO) {
                            runCatching { eventRepo().delete(eventId) }
                        }
                        Toast.makeText(this@CameraActivity,
                            R.string.toast_capture_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }

    /** 开始录像:VideoCapture 输出到事件目录 video.mp4;启动计时器 + 切按钮为停止态。 */
    private fun startRecording() {
        if (videoState != VideoState.IDLE) return
        val vid = videoCapture ?: run {
            Toast.makeText(this, R.string.toast_recording_failed, Toast.LENGTH_SHORT).show()
            return
        }

        // ★ 同步置"启动中"并禁用按钮:建事件/建目录有两段 IO,窗口期内快速双击
        // 会启动两个 Recording、生成两个事件;期间点拍照还会 unbindAll 打断启动。
        videoState = VideoState.STARTING
        btnVideo.isEnabled = false
        updateVideoButton()

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // 先记下开始时刻:水印要打"开始录像的时间",不是转码时间
        val startWallMs = System.currentTimeMillis()
        appScope.launch {
            val eventId = withContext(Dispatchers.IO) {
                eventRepo().createEvent(segments = emptyList(), windowMinutes = 0, type = EventType.CAMERA_VIDEO)
            }
            val eventDir = withContext(Dispatchers.IO) {
                File(File(filesDir, "events"), "event_$eventId").apply { mkdirs() }
            }
            val outFile = File(eventDir, EventRepository.VIDEO_FILE)
            val options = FileOutputOptions.Builder(outFile).build()

            try {
                // camera-video 1.3.1 API:
                // Recorder.prepareRecording(ctx, FileOutputOptions) → PendingRecording
                // PendingRecording.start(Executor, Consumer<VideoRecordEvent>) → Recording
                // Recording.stop() 停录
                // 录屏中:禁用相机录音,避免与录屏 AudioCapturer 的麦克风 AudioRecord 冲突
                // 注意:withAudioEnabled() 无参,调用即开启音频;不调用即禁用
                val pending: PendingRecording = if (RecordState.isRecording) {
                    vid.output.prepareRecording(this@CameraActivity, options)
                } else {
                    vid.output.prepareRecording(this@CameraActivity, options).withAudioEnabled()
                }
                recording = pending.start(
                    mainExec,
                    Consumer { event ->
                        when (event) {
                            is VideoRecordEvent.Status -> {
                                // 进行中,可选:更新进度
                            }
                            is VideoRecordEvent.Finalize -> {
                                // ★ 无论成功失败都先复位 UI:录像被系统/编码错误/切后台提前结束
                                // 时,不能把界面永久停在"停止录像"并让计时器无限刷新。
                                runOnUiThread { onRecordingFinalized() }
                                appScope.launch {
                                    if (event.error == VideoRecordEvent.Finalize.ERROR_NONE) {
                                        // 转码烧水印耗时与片长相关:先提示,避免用户以为卡住
                                        if (WatermarkConfig.isTimeEnabled(applicationContext)) {
                                            Toast.makeText(applicationContext,
                                                "正在添加时间水印…", Toast.LENGTH_SHORT).show()
                                        }
                                        withContext(Dispatchers.IO) {
                                            burnVideoWatermark(outFile, startWallMs)
                                            eventRepo().setSegments(eventId, listOf(outFile.absolutePath))
                                            eventRepo().setRemark(eventId, "录像 $ts")
                                        }
                                        Toast.makeText(applicationContext,
                                            R.string.toast_video_saved, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Log.e(TAG, "录像 Finalize 错误: ${event.error}")
                                        // 录像失败:删除空事件,避免留下 0 段僵尸事件
                                        withContext(Dispatchers.IO) {
                                            runCatching { eventRepo().delete(eventId) }
                                        }
                                        Toast.makeText(applicationContext,
                                            "${getString(R.string.toast_recording_failed)}(code ${event.error})",
                                            Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                )
                // 启动计时器 + 切录像按钮为停止态
                videoState = VideoState.RECORDING
                recordStartMs = SystemClock.elapsedRealtime()
                tvRecordTimer.visibility = View.VISIBLE
                tvRecordTimer.text = "● 00:00"
                timerHandler.post(timerRunnable)
                updateVideoButton()
            } catch (e: Exception) {
                Log.e(TAG, "startRecording 异常", e)
                // 启动失败:删除刚建的空事件
                runCatching { eventRepo().delete(eventId) }
                // 启动失败必须回滚到 IDLE,否则按钮永久卡在"启动中"不可用
                videoState = VideoState.IDLE
                updateVideoButton()
                btnVideo.isEnabled = !RecordState.isRecording
                Toast.makeText(this@CameraActivity,
                    "${getString(R.string.toast_recording_failed)}: ${e.message}",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 停止录像:停止 Recording + 关闭计时器 + 切录像按钮回开始态。
     *  ★ 不在停止瞬间 unbindAll:CameraX 要求等 VideoRecordEvent.Finalize 之后再解绑,
     *  提前解绑可能截断编码收尾(丢尾帧)。解绑统一放到 [onRecordingFinalized]。
     *  这也覆盖了"录像因切后台/编码错误自动结束"的路径,保证界面状态能复位。 */
    private fun stopRecording() {
        if (videoState == VideoState.STARTING) {
            // 启动尚未完成(Recording 可能已赋值也可能没有):有就停,状态交由 Finalize 复位
            runCatching { recording?.stop() }
            return
        }
        if (videoState != VideoState.RECORDING) return
        videoState = VideoState.IDLE
        timerHandler.removeCallbacks(timerRunnable)
        tvRecordTimer.visibility = View.GONE
        updateVideoButton()
        runCatching { recording?.stop() }
    }

    /** Finalize 统一收尾:复位按钮/计时器,并在确认没有新录制时解绑用例。 */
    private fun onRecordingFinalized() {
        val wasRecording = videoState == VideoState.RECORDING || videoState == VideoState.STARTING
        if (wasRecording) videoState = VideoState.IDLE
        recording = null
        timerHandler.removeCallbacks(timerRunnable)
        tvRecordTimer.visibility = View.GONE
        updateVideoButton()
        btnVideo.isEnabled = !RecordState.isRecording
        // 只有当前不在录制时才解绑:CameraX 要求 Finalize 之后解绑,
        // 若用户已开始下一段录制则不能拆掉正在用的用例。
        if (videoState == VideoState.IDLE) {
            runCatching { cameraProvider?.unbindAll() }
            mode = Mode.NONE
            videoCapture = null
            imageCapture = null
        }
    }

    /** 更新录像按钮文案:IDLE="开始录像";STARTING="启动中…";RECORDING="停止录像"。
     *  背景色保持红色(layout 已设),仅靠文案 + 顶部计时器区分状态。 */
    private fun updateVideoButton() {
        btnVideo.text = when (videoState) {
            VideoState.RECORDING -> getString(R.string.camera_stop_recording)
            VideoState.STARTING -> "启动中…"
            VideoState.IDLE -> getString(R.string.camera_start_recording)
        }
    }

    /** 录屏中:录像按钮置灰不可点;非录屏中:恢复可点。 */
    private fun updateVideoButtonState() {
        val recording = RecordState.isRecording
        btnVideo.isEnabled = !recording
        btnVideo.alpha = if (recording) 0.4f else 1.0f
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(timerRunnable)
        wmHandler.removeCallbacks(wmTickRunnable)
        runCatching { unregisterReceiver(closeReceiver) }
        // 录像中 onDestroy:先停录保留产物再解绑
        runCatching { recording?.stop() }
        cameraProvider?.unbindAll()
    }

    // ---------- 时间水印 ----------

    /** 预览水印:按录屏水印配置(开关/位置/字号)摆放,关掉时间水印则整个隐藏。 */
    private fun setupPreviewWatermark() {
        if (!WatermarkConfig.isTimeEnabled(this)) {
            tvCameraWatermark.visibility = View.GONE
            return
        }
        tvCameraWatermark.setTextSize(WatermarkConfig.getTimeSize(this).toFloat())
        val density = resources.displayMetrics.density
        val marginH = (24 * density).toInt()
        val marginV = (80 * density).toInt()
        val lp = tvCameraWatermark.layoutParams as FrameLayout.LayoutParams
        lp.gravity = when (WatermarkConfig.getTimePosition(this)) {
            WatermarkConfig.POS_TOP_LEFT -> Gravity.TOP or Gravity.START
            WatermarkConfig.POS_TOP_RIGHT -> Gravity.TOP or Gravity.END
            WatermarkConfig.POS_BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            WatermarkConfig.POS_CENTER -> Gravity.CENTER
            else -> Gravity.BOTTOM or Gravity.END
        }
        // 按 gravity 设置对应方向的边距(START 用 left,END 用 right,依此类推)
        lp.leftMargin = if ((lp.gravity and Gravity.START) == Gravity.START) marginH else 0
        lp.rightMargin = if ((lp.gravity and Gravity.END) == Gravity.END) marginH else 0
        lp.topMargin = if ((lp.gravity and Gravity.TOP) == Gravity.TOP) marginV else 0
        lp.bottomMargin = if ((lp.gravity and Gravity.BOTTOM) == Gravity.BOTTOM) marginV else 0
        tvCameraWatermark.layoutParams = lp
        tvCameraWatermark.visibility = View.VISIBLE
        wmHandler.removeCallbacks(wmTickRunnable)
        wmHandler.post(wmTickRunnable)
    }

    /**
     * 给拍照产物烧录时间水印(就地覆盖 photo.jpg)。
     *
     * 步骤:按 EXIF 方向摆正 → 解码(超大图降采样防 OOM)→ Canvas 绘时间 → 重新压 JPEG。
     * 重压后不写 EXIF,方向已通过像素旋转固化,不会出现"文字竖着"的问题。
     */
    private fun burnPhotoWatermark(file: File) {
        if (!WatermarkConfig.isTimeEnabled(this)) return
        val text = wmTimeText()
        val src = decodePhoto(file) ?: run {
            Log.w(TAG, "解码照片失败,跳过水印")
            return
        }
        val rotated = rotateByExif(file, src) ?: src
        val mutable = if (rotated.isMutable) rotated
        else runCatching { rotated.copy(Bitmap.Config.ARGB_8888, true) }.getOrNull()
        if (mutable == null) {
            Log.w(TAG, "照片转可变位图失败,跳过水印")
            // 提前 return 也要回收已解码的位图,否则每次失败泄漏数十 MB
            if (!rotated.isRecycled) runCatching { rotated.recycle() }
            if (src !== rotated && !src.isRecycled) runCatching { src.recycle() }
            return
        }
        // 尽早释放中间位图:4096px ARGB 每份约 64MB,峰值从 3 份降到 2 份,降低低端机 OOM 概率
        if (rotated !== mutable && !rotated.isRecycled) runCatching { rotated.recycle() }
        if (src !== rotated && src !== mutable && !src.isRecycled) runCatching { src.recycle() }

        drawWatermarkOn(mutable, text)
        runCatching {
            FileOutputStream(file).use { mutable.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }.onFailure { Log.w(TAG, "写回带水印照片失败", it) }
        if (!mutable.isRecycled) runCatching { mutable.recycle() }
    }

    /** 解码照片:超过 4096px 的长边按 2/4/8 降采样,避免 48MP 原图 OOM。 */
    private fun decodePhoto(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /** 按 EXIF 方向把位图旋转/翻转到正向。方向为 1 或无 EXIF 时返回 null(调用方用原图)。 */
    private fun rotateByExif(file: File, src: Bitmap): Bitmap? = runCatching {
        val orientation = ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = android.graphics.Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return null
        }
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }.getOrNull()

    /** 在整帧位图上按配置位置画时间水印。字号按画面高度等比换算,与分辨率无关。 */
    private fun drawWatermarkOn(bitmap: Bitmap, text: String) {
        val w = bitmap.width
        val h = bitmap.height
        val sizeSp = WatermarkConfig.getTimeSize(this)
        val fontPx = (h * sizeSp / 413f).coerceAtLeast(12f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontPx
            typeface = Typeface.MONOSPACE
            setShadowLayer(fontPx * 0.22f, fontPx * 0.05f, fontPx * 0.05f, Color.argb(0xCC, 0, 0, 0))
        }
        val fm = paint.fontMetrics
        val tw = paint.measureText(text)
        val m = fontPx * 1.2f
        val x: Float
        val y: Float
        when (WatermarkConfig.getTimePosition(this)) {
            WatermarkConfig.POS_TOP_LEFT -> { x = m; y = m - fm.ascent }
            WatermarkConfig.POS_TOP_RIGHT -> { x = w - tw - m; y = m - fm.ascent }
            WatermarkConfig.POS_BOTTOM_LEFT -> { x = m; y = h - m - fm.descent }
            WatermarkConfig.POS_CENTER -> {
                x = (w - tw) / 2f
                y = (h - (fm.descent - fm.ascent)) / 2f - fm.ascent
            }
            else -> { x = w - tw - m; y = h - m - fm.descent }
        }
        Canvas(bitmap).drawText(text, x, y, paint)
    }

    /**
     * 给录像产物烧录时间水印(就地覆盖 video.mp4)。
     * 转码失败时保留原片(无水印但完整),不让水印成为"录完却拿不到文件"的阻塞点。
     */
    private fun burnVideoWatermark(file: File, startWallMs: Long) {
        if (!WatermarkConfig.isTimeEnabled(this)) return
        if (!file.exists() || file.length() == 0L) return
        val tmp = File(file.parentFile, "video_wm.mp4")
        val ok = VideoEditor.burnTimeWatermark(
            inputPath = file.absolutePath,
            outPath = tmp.absolutePath,
            text = wmTimeText(startWallMs),
            sizeSp = WatermarkConfig.getTimeSize(this),
            position = WatermarkConfig.getTimePosition(this),
        )
        if (ok && tmp.length() > 0) {
            // 同目录 rename 是原子替换,原片在替换成功前始终存在 → 首选。
            if (!tmp.renameTo(file)) {
                // rename 不可用(个别文件系统拒绝覆盖已存在文件)时,先把原片改名备份,
                // 新片就位后再删备份;任一步失败都把备份还原,绝不出现"原片已删、新片未就位"。
                val backup = File(file.parentFile, "video_bak.mp4")
                runCatching { backup.delete() }
                val backedUp = file.renameTo(backup)
                if (backedUp && tmp.renameTo(file)) {
                    runCatching { backup.delete() }
                } else {
                    if (backedUp) runCatching { backup.renameTo(file) }
                    Log.w(TAG, "水印片重命名失败,已保留原片")
                    runCatching { tmp.delete() }
                }
            }
        } else {
            Log.w(TAG, "视频水印失败,保留原片: ${VideoEditor.lastError}")
            runCatching { tmp.delete() }
        }
    }

    private fun eventRepo() = (application as ReplayApp).eventRepo
}
