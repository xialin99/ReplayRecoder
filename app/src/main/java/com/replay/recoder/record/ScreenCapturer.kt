package com.replay.recoder.record

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 屏幕采集 + H264 编码。
 *
 * 职责单一:MediaProjection → VirtualDisplay → 编码器 inputSurface,
 * 编码后的样本 + 格式通过 [Callback] 输出。段切换由 [SegmentWriter] 决定。
 *
 * 段间无缝:采集/编码器输入端不中断,输出端到点时由 writer 换 muxer。
 * KEY_I_FRAME_INTERVAL=1 保证每秒一个 I 帧,段头部可独立解码。
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val width: Int,
    private val height: Int,
    private val dpi: Int,
    private val bitRate: Int,
) {
    /** 样本回调,由 [SegmentWriter] 实现 */
    interface Callback {
        fun onOutputFormat(format: MediaFormat)
        fun onSample(buf: ByteBuffer, info: MediaCodec.BufferInfo)
    }

    // 跨线程访问(drain 线程 / 主线程 stop),必须 volatile,避免二次 stop 看到旧引用
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var callback: Callback? = null

    private val bufferInfo = MediaCodec.BufferInfo()
    @Volatile private var running = false
    private var sampleCount = 0
    /** 是否已通知编码器输入结束(signalEndOfStream 后为 true) */
    @Volatile private var eosSignaled = false
    /** drain 线程结束 latch:signalEndOfStream 等待编码器排空 */
    private val drainLatch = java.util.concurrent.CountDownLatch(1)
    /** 强制刷新帧定时器:屏幕静止时编码器不输出帧(帧间压缩丢弃重复帧),
     *  导致段文件出现长时间无帧区间,回放时定格上一帧(曾出现5s+ 卡帧)。
     *  每 500ms 请求一次 sync frame,强制编码器输出至少一帧,保证时间轴连续。 */
    private var refreshHandler: android.os.Handler? = null
    private val refreshRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            // 请求 sync frame:让编码器强制输出一帧,即使画面没变化
            requestSyncFrame()
            refreshHandler?.postDelayed(this, 500)
        }
    }

    /**
     * 请求编码器立即输出一个 I 帧(sync frame)。
     *
     * 两处使用:
     * - [refreshRunnable] 每 500ms 调用,保证屏幕静止时时间轴连续
     * - 段切换时由 [RecordService] 的 onSegmentSwitch 回调调用,确保新段首帧是 I 帧。
     *   FFmpeg concat -c copy 不重编码,段边界处若为 P 帧,播放器解码时找不到跨段
     *   参考帧 → 画面卡死(音频照常播,表现为"进度条跑但画面不动")。
     *   新段首帧为 I 帧后,段边界是独立可解码点,concat 产物全程可播。
     */
    fun requestSyncFrame() {
        val c = codec ?: return
        runCatching {
            val params = android.os.Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            c.setParameters(params)
        }
    }

    /** 通知编码器输入结束,等待所有剩余帧输出到 SegmentWriter。
     *  必须在 [stop] 之前调用:先排空编码器,确保所有帧已写入 muxer,
     *  再关闭 muxer(w.stop),避免段文件视频轨 stbl 为空(停录时长丢失的根因)。
     *  VirtualDisplay 先释放,不再有新帧输入;signalEndOfInputStream 让编码器
     *  输出剩余帧并标记 END_OF_STREAM;drain 线程收到后退出,本方法返回。 */
    fun signalEndOfStream() {
        if (eosSignaled) return
        eosSignaled = true
        // 采集尚未启动成功(或已释放)时没有编码器可排空,立即返回避免白等 5 秒
        if (codec == null) return
        // 立即停掉刷新定时器:否则在排空等待期间,定时器还会强制产生 sync frame,
        // 这些帧带有实时 nanoTime PTS,会把段时长拉长到远超实际录制时间
        // (曾致 18s 录制生成 23s 事件)
        refreshHandler?.removeCallbacks(refreshRunnable)
        // 先停 VirtualDisplay,不再有新帧输入编码器
        runCatching { virtualDisplay?.release() }
        // 通知编码器输入流结束,它会输出剩余帧并标记 END_OF_STREAM
        runCatching { codec?.signalEndOfInputStream() }
        // 等 drain 线程处理完所有剩余帧(收到 END_OF_STREAM 后退出)
        drainLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        Log.e(TAG, "编码器已排空,所有帧已写入")
    }

    fun start(cb: Callback) {
        callback = cb
        val fmt = MediaFormat.createVideoFormat(VIDEO_MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        try {
            codec = MediaCodec.createEncoderByType(VIDEO_MIME).apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = createInputSurface()
                start()
            }
            virtualDisplay = projection.createVirtualDisplay(
                "ReplayRecoderDisplay", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface,
                null,
                null
            )
        } catch (t: Throwable) {
            // 启动失败必须释放已创建的 codec / Surface,否则编码器与 Surface 泄漏,
            // 后续重试录制会因编码器实例耗尽持续失败
            Log.e(TAG, "采集启动失败,释放已创建资源", t)
            runCatching { virtualDisplay?.release() }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { inputSurface?.release() }
            virtualDisplay = null
            codec = null
            inputSurface = null
            throw t
        }
        running = true
        Log.e(TAG, "采集启动 ${width}x${height} dpi=$dpi bitrate=$bitRate")
        Thread { drainLoop() }.also { it.name = "captor-drain" }.start()
        // 启动强制刷新定时器:屏幕静止时编码器不输出帧,每 500ms 请求 sync frame 保证时间轴连续
        refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
        refreshHandler?.postDelayed(refreshRunnable, 500)
    }

    private fun drainLoop() {
        val codec = this.codec ?: return
        try {
            while (running) {
                // dequeue 可能与 stop() 的 codec.stop() 并发,抛 IllegalStateException:
                // 若是主动停录(running=false)属良性竞争,静默退出;真正运行中异常才记错误日志。
                val idx = try {
                    codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                } catch (t: IllegalStateException) {
                    if (!running) break
                    Log.e(TAG, "dequeue 异常,采集终止", t)
                    break
                }
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 继续 */ }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        callback?.onOutputFormat(codec.outputFormat)
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf == null) {
                            // getOutputBuffer 返回 null 时也必须归还缓冲,否则该缓冲被永久占用,
                            // 耗尽后编码器不再产出(画面冻结),而录制状态仍显示进行中。
                            codec.releaseOutputBuffer(idx, false)
                        } else {
                            if (bufferInfo.size > 0) {
                                buf.position(bufferInfo.offset)
                                buf.limit(bufferInfo.offset + bufferInfo.size)
                                if (sampleCount % 30 == 0) {
                                    Log.e(TAG, "样本 #$sampleCount pts=${bufferInfo.presentationTimeUs}us size=${bufferInfo.size} flags=${bufferInfo.flags}")
                                }
                                sampleCount++
                                callback?.onSample(buf, bufferInfo)
                            }
                            codec.releaseOutputBuffer(idx, false)
                        }
                        // 编码器输出完所有剩余帧:drain 线程退出,通知 signalEndOfStream
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            running = false
                            break
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "drain 异常", t)
        } finally {
            drainLatch.countDown()
            stop()
        }
    }

    @Synchronized
    fun stop() {
        if (!running && codec == null) return
        running = false
        refreshHandler?.removeCallbacks(refreshRunnable)
        refreshHandler = null
        // signalEndOfStream 已释放 VirtualDisplay 时不再重复释放
        if (!eosSignaled) runCatching { virtualDisplay?.release() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { inputSurface?.release() }
        virtualDisplay = null
        codec = null
        inputSurface = null
        Log.e(TAG, "采集停止")
    }

    companion object {
        private const val TAG = "ScreenCapturer"
        private const val VIDEO_MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_US = 10_000L
        private const val FPS = 30
    }
}
