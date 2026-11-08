package com.replay.recoder.record

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

/**
 * 段写入器:管 muxer 段轮转,视频 + 音频双轨写进同一段 mp4。
 *
 * - 收到 [onOutputFormat](视频) 和 [onAudioFormat](音频) 后,等所需轨道 format 就绪再开第一段
 * - 每段写 [segmentSec] 秒(按视频样本时间戳累计)到点后:停旧 muxer → 申新段 → 启新 muxer
 * - 段切换时请求视频 I 帧,保证新段头部可独立解码
 *
 * 音频是否启用由构造参数 [audioEnabled] 决定:
 * - true:等视频+音频 format 都就绪才开段
 * - false:只等视频 format 就开段
 *
 * pts 策略(关键):直接用编码器输出的 info.presentationTimeUs,再归一化到段内 0 起——
 *   - 视频:Surface 输入模式下该值 = VirtualDisplay 提交帧时刻的 System.nanoTime(),
 *     忠实反映帧的真实时刻;屏幕静止时无帧(时间轴停帧),运动时帧间隔均匀(30fps 流畅)。
 *     切勿用"写入时刻"替代:编码器输出是突发的,会把运动段压缩成快闪,时间轴失真。
 *   - 音频:AudioCapturer 按"已采样帧数"换算 pts 送编码器,原样透传输出,连续精确。
 * 直接写原始大数 pts 会导致 MediaMuxer 时长/索引异常、缩略图黑帧,故每段归一化到 0 起;
 * 段内 pts 单调递增。每段是独立 mp4,导出端用 Mp4Merger(逐段累加偏移)拼接时间轴。
 */
class SegmentWriter(
    private val segmentManager: SegmentManager,
    private val segmentSec: Int,
    audioEnabled: Boolean,
    private val onSegmentSwitch: () -> Unit,
) : ScreenCapturer.Callback, AudioCapturer.Callback {

    /**
     * 音频是否启用(运行时可变):音频初始化成功才为 true,失败时被
     * [onAudioUnavailable] 置 false,使 [tryStartFirstSegment] 不再等音频 format。
     */
    @Volatile private var audioEnabled2 = audioEnabled

    @Volatile private var muxer: MediaMuxer? = null
    @Volatile private var started = false
    private var videoTrack = -1
    private var audioTrack = -1
    @Volatile private var videoFormat: MediaFormat? = null
    @Volatile private var audioFormat: MediaFormat? = null
    /** 第一段是否已尝试启动(避免 format 回调重复开段) */
    @Volatile private var firstSegmentStarted = false

    /**
     * muxer 互斥锁:MediaMuxer 不是线程安全的,视频 drain 线程(onSample)
     * 与音频 drain 线程(onAudioSample)会并发调用 writeSampleData,必须串行化,
     * 否则 muxer 内部缓冲错位,产出无 moov 盒的损坏 mp4(相册显示 00:00:00)。
     * startNewSegment/switchSegment/closeCurrent 也在同锁内,保证换段时不被写。
     */
    private val muxerLock = Any()

    private var currentPath: String? = null
    /** 本段已写时长(微秒,按视频相对 pts) */
    private var segElapsedUs = 0L
    private val segTargetUs get() = segmentSec * 1_000_000L
    /** 本段首个视频样本的原始 pts(绝对时钟),用于把段内 pts 归一化到 0 起 */
    private var segBasePtsUs = 0L
    private var segWriteCount = 0
    /** 是否已捕获本段基线 pts(第一帧前) */
    private var segBaseCaptured = false
    /** 本段首个音频样本的原始 pts,音频归一化用 */
    private var audioBasePtsUs = 0L
    private var audioBaseCaptured = false
    /** 本段内已写视频样本的最大 pts:防止编码器 B 帧输出导致 pts 回退(MPEG4Writer 对
     *  回退时间戳可能 stop 失败/产出非法 stts)。无 B 帧时此钳制无任何影响。 */
    private var segLastRelPts = 0L
    /** 本段首个真实时间戳(System.nanoTime/1000),用于屏幕静止时 pts 补偿 */
    private var segRealBaseUs = 0L
    private var segRealBaseCaptured = false

    // ---------- 视频回调 ----------
    override fun onOutputFormat(format: MediaFormat) {
        videoFormat = format
        tryStartFirstSegment()
    }

    override fun onSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started) return
        try {
            // pts 用编码器自带时间戳:Surface 输入模式下 info.presentationTimeUs
            // = VirtualDisplay 提交该帧时刻的 System.nanoTime(),忠实反映帧的真实时刻——
            // 屏幕静止时无帧,时间轴上如实停帧;运动时帧间隔均匀,回放流畅。
            // 严禁改用"写入时刻"作 pts:编码器输出是突发的(如 3 帧挤在 48ms、随后 575ms 空洞),
            // 写入时刻会把运动段压缩成快闪,回放时间轴完全失真。
            val absPtsUs = info.presentationTimeUs
            // 归一化到段内 0 起
            if (!segBaseCaptured) {
                segBasePtsUs = absPtsUs
                segBaseCaptured = true
            }
            var relPts = (absPtsUs - segBasePtsUs).coerceAtLeast(0L)
            // 屏幕静止时编码器输出的帧 pts 不递增(帧间压缩丢弃重复帧)。
            // 但 500ms sync frame 定时器会强制编码器输出帧,这些帧的 pts 可能
            // 不按真实时间推进(编码器给重复帧相同 pts)。
            // 用 System.nanoTime 作为 pts 补偿:如果编码器 pts 没递增,
            // 用真实纳秒时间戳替代,保证 pts 忠实反映录制时间轴。
            // 这比"固定 +33ms"更准确:500ms 刷新 → pts 应推进 500ms 而非 33ms。
            val realNowUs = System.nanoTime() / 1000
            if (!segRealBaseCaptured) {
                segRealBaseUs = realNowUs
                segRealBaseCaptured = true
            }
            val realRelPts = realNowUs - segRealBaseUs
            // 取编码器 pts 和真实时间 pts 的最大值:保证 pts 单调递增且不落后于真实时间
            relPts = maxOf(relPts, realRelPts, segLastRelPts + 1L)
            segLastRelPts = relPts
            val normInfo = MediaCodec.BufferInfo()
            normInfo.set(info.offset, info.size, relPts, info.flags)
            val needSwitch = segElapsedUs >= segTargetUs
            // 写样本与换段都在锁内,避免与音频线程并发交错损坏 muxer
            synchronized(muxerLock) {
                if (!started) return
                val m = muxer ?: return
                if (videoTrack >= 0) m.writeSampleData(videoTrack, buf, normInfo)
                segElapsedUs = relPts
                if (segWriteCount % 30 == 0) {
                    Log.d(TAG, "写视频 absPts=${absPtsUs}us relPts=${relPts}us segElapsed=${segElapsedUs}us target=${segTargetUs}us")
                }
                segWriteCount++
                if (needSwitch) {
                    switchSegmentLocked()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写视频样本失败", t)
        }
    }

    // ---------- 音频回调 ----------
    override fun onAudioFormat(format: MediaFormat) {
        audioFormat = format
        tryStartFirstSegment()
    }

    /** 音频不可用:把 audioEnabled 置 false,解除对音频 format 的等待,立即用仅视频模式开段。 */
    override fun onAudioUnavailable() {
        audioEnabled2 = false
        Log.w(TAG, "音频不可用,降级为仅视频")
        tryStartFirstSegment()
    }

    override fun onAudioSample(buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started) return
        try {
            // pts 用编码器自带时间戳:AudioCapturer 按"已采样帧数"换算 pts 后 queueInputBuffer,
            // AAC 编码器原样透传到输出——是从录制起点的连续精确采样时间线,与视频同源于同一会话。
            val absPtsUs = info.presentationTimeUs
            val normInfo = MediaCodec.BufferInfo()
            // 与视频共用同一把锁串行写,防止并发损坏 muxer
            synchronized(muxerLock) {
                if (!started) return
                val m = muxer ?: return
                if (audioTrack < 0) return
                // 基线捕获必须在锁内:换段由视频线程在锁内重置基线,
                // 若在锁外捕获,音频线程可能用旧基线算出跨段大 pts 的离群样本写入新段
                if (!audioBaseCaptured) {
                    audioBasePtsUs = absPtsUs
                    audioBaseCaptured = true
                }
                val relPts = (absPtsUs - audioBasePtsUs).coerceAtLeast(0L)
                normInfo.set(info.offset, info.size, relPts, info.flags)
                m.writeSampleData(audioTrack, buf, normInfo)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "写音频样本失败", t)
        }
    }

    /** 第一段:所需 format 都就绪后启动一次。 */
    private fun tryStartFirstSegment() {
        if (firstSegmentStarted) return
        val vf = videoFormat ?: return
        if (audioEnabled2 && audioFormat == null) return
        firstSegmentStarted = true
        synchronized(muxerLock) { startNewSegmentLocked(firstPtsUs = 0L) }
    }

    private fun startNewSegmentLocked(firstPtsUs: Long) {
        val vf = videoFormat ?: run {
            Log.w(TAG, "video format 未就绪,跳过开段")
            return
        }
        closeCurrentLocked()
        val path = segmentManager.nextSegment()
        currentPath = path
        segBasePtsUs = 0L
        segBaseCaptured = false
        audioBasePtsUs = 0L
        audioBaseCaptured = false
        segElapsedUs = 0L
        segWriteCount = 0
        segLastRelPts = 0L
        segRealBaseUs = 0L
        segRealBaseCaptured = false
        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m ->
            videoTrack = m.addTrack(vf)
            audioFormat?.let { af -> audioTrack = m.addTrack(af) }
            m.start()
            started = true
        }
        Log.e(TAG, "开段: $path video=$videoTrack audio=$audioTrack audioEnabled=$audioEnabled2")
    }

    /** 换段:调用方必须已持有 [muxerLock](由 onSample 在锁内触发)。 */
    private fun switchSegmentLocked() {
        closeCurrentLocked()
        segBasePtsUs = 0L
        segBaseCaptured = false
        audioBasePtsUs = 0L
        audioBaseCaptured = false
        segElapsedUs = 0L
        segWriteCount = 0
        segLastRelPts = 0L
        segRealBaseUs = 0L
        segRealBaseCaptured = false
        val path = segmentManager.nextSegment()
        currentPath = path
        val vf = videoFormat ?: return
        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m ->
            videoTrack = m.addTrack(vf)
            audioFormat?.let { af -> audioTrack = m.addTrack(af) }
            m.start()
            started = true
        }
        onSegmentSwitch()
        Log.e(TAG, "换段: $path video=$videoTrack audio=$audioTrack")
    }

    private fun closeCurrentLocked() {
        // stop 与 release 拆开两个独立 try,即使 stop 抛异常(muxer 状态错误),
        // release 仍能执行,文件句柄不泄漏。锁外不再有并发写入,可安全收尾。
        val m = muxer
        val wasStarted = started
        val p = currentPath
        val writeCount = segWriteCount
        // 先置空,让并发的 onSample/onAudioSample 快速 return,不再写这个 muxer
        muxer = null
        started = false
        videoTrack = -1
        audioTrack = -1
        if (m != null) {
            try {
                if (wasStarted) m.stop()
                Log.e(TAG, "关段 stop 成功 $p 已写=$writeCount")
            } catch (t: Throwable) {
                Log.e(TAG, "关段 stop 失败 $p", t)
            }
            try {
                m.release()
            } catch (t: Throwable) {
                Log.e(TAG, "关段 release 失败 $p", t)
            }
            // 空段（未写入任何样本）产出无 stbl 的无效 mp4,
            // 合并时破坏整体时长（30s→17s 根因）。删除文件并从队列移除。
            if (writeCount == 0 && p != null) {
                Log.w(TAG, "空段未写入任何样本,删除: $p")
                segmentManager.removeSegment(p)
            }
        }
    }

    private fun closeCurrent() {
        synchronized(muxerLock) { closeCurrentLocked() }
    }

    fun stop() {
        closeCurrent()
    }

    companion object { private const val TAG = "SegmentWriter" }
}
