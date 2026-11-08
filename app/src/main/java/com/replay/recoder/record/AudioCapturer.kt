package com.replay.recoder.record

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.util.Log
import java.nio.ByteBuffer
/**
 * 系统声音 / 麦克风 / 静音 三模式采集 + AAC 编码。
 *
 * - [Source.SYSTEM]:通过 MediaProjection 的 AudioPlaybackCaptureConfiguration
 *   捕获系统播放音(USAGE_MEDIA/GAME/UNKNOWN),需用户授权 MediaProjection。
 * - [Source.MIC]:用普通 [AudioRecord] + AudioSource.MIC,不需要 MediaProjection。
 * - [Source.MUTE]:不采集,直接通知 writer 降级为仅视频,不写音频轨。
 *
 * PCM 16bit → MediaCodec AAC-LC 编码,输出样本交给 [SegmentWriter]。
 * PTS 用"已读帧数"换算,保证单调;与视频轨不强制同源时钟(初版允许轻微 A/V 漂移)。
 */
class AudioCapturer(
    private val projection: MediaProjection?,
    private val source: Source = Source.SYSTEM,
    private val sampleRate: Int = 44_100,
    private val channelCount: Int = 2, // 立体声
    private val bitRate: Int = 128_000,
) {
    enum class Source { SYSTEM, MIC, MIXED, MUTE }

    interface Callback {
        fun onAudioFormat(format: MediaFormat)
        fun onAudioSample(buf: ByteBuffer, info: MediaCodec.BufferInfo)
        /** 音频初始化失败 / 静音录制 / 系统不允许采集:通知 writer 降级为纯视频,别再等音频 format */
        fun onAudioUnavailable() {}
    }

    // 跨线程访问(input/drain 线程与主线程 stop),必须 volatile
    @Volatile private var audioRecord: AudioRecord? = null
    /** MIXED 模式下的麦克风 AudioRecord;单源模式为 null。 */
    @Volatile private var micRecord: AudioRecord? = null
    /** 启动时构建的 AudioFormat,重建 AudioRecord 时复用 */
    private var audioFormat: AudioFormat? = null
    /** 启动时算的 buffer size,重建时复用 */
    private var bufSizeCached: Int = 0
    @Volatile private var codec: MediaCodec? = null
    private var inputThread: Thread? = null
    private var drainThread: Thread? = null
    @Volatile private var running = false
    /** 音频采集是否真正可用(初始化成功才 true)。失败/静音时只录视频。 */
    @Volatile private var audioOk = false
    private var callback: Callback? = null

    /** 已读 PCM 帧数(1 帧 = 1 个采样点 × 声道) */
    @Volatile private var framesRead = 0L
    /** 连续 read 失败计数,超阈值触发 AudioRecord 重建(蓝牙/耳机插拔导致路由变更) */
    @Volatile private var consecutiveReadFail = 0
    /** 重建 AudioRecord 的锁,防止 inputLoop 与重建逻辑并发 */
    private val rebuildLock = Any()
    /** 是否已重建过(避免无限重建循环,最多一次) */
    @Volatile private var hasRebuilt = false

    fun start(cb: Callback) {
        callback = cb

        // 静音录制:不创建音频轨,直接通知 writer 走纯视频路径
        if (source == Source.MUTE) {
            audioOk = false
            callback?.onAudioUnavailable()
            Log.i(TAG, "静音录制:无音频轨")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufSize = (minBuf * 2).coerceAtLeast(8192)
        bufSizeCached = bufSize

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .apply {
                if (channelCount == 2) setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                else setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            }
            .build()
        this.audioFormat = audioFormat

        // 防御性构建:AudioRecord 在 RECORD_AUDIO 未授权 / 系统限制时会初始化失败,
        // 此时不要把音频轨加进 muxer(否则空轨会让 muxer.stop() 失败、产出损坏 mp4)。
        // 初始化成功才创建编码器并启动线程;失败通知 writer 降级为仅视频。
        val ar = try {
            val builder = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufSize)
            when (source) {
                Source.SYSTEM, Source.MIXED -> {
                    val proj = projection
                        ?: throw IllegalStateException("系统声录制需要 MediaProjection")
                    val captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    builder.setAudioPlaybackCaptureConfig(captureConfig)
                }
                Source.MIC -> {
                    builder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                }
                Source.MUTE -> error("已处理")
            }
            builder.build()
                .also { if (it.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("AudioRecord 未初始化 state=${it.state}") }
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord 初始化失败($source),降级为仅录视频(无音频)", t)
            audioOk = false
            callback?.onAudioUnavailable()
            return
        }
        audioRecord = ar

        // MIXED 模式:再开一个麦克风 AudioRecord,稍后在 inputLoop 软件混音
        if (source == Source.MIXED) {
            micRecord = try {
                AudioRecord.Builder()
                    .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufSize)
                    .build()
                    .also { if (it.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("micRecord 未初始化 state=${it.state}") }
            } catch (t: Throwable) {
                Log.w(TAG, "MIXED 麦克风初始化失败,退化为仅系统声", t)
                null
            }
        }

        val fmt = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
        }
        codec = try {
            MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "音频编码器创建失败,降级为仅录视频", t)
            runCatching { ar.release() }
            runCatching { micRecord?.release() }
            micRecord = null
            audioRecord = null
            codec = null
            audioOk = false
            callback?.onAudioUnavailable()
            return
        }

        audioOk = true
        running = true
        // 必须先 startRecording(),否则 read() 一直返回 ERROR_INVALID_OPERATION(-3),
        // 编码器收不到 PCM → 音频 track 为空 → muxer.stop() 失败 → mp4 损坏。
        runCatching { ar.startRecording() }
            .onFailure { Log.e(TAG, "AudioRecord.startRecording 失败", it) }
        runCatching { micRecord?.startRecording() }
            .onFailure { Log.e(TAG, "micRecord.startRecording 失败", it) }
        inputThread = Thread { inputLoop(bufSize) }.also { it.name = "audio-in"; it.start() }
        drainThread = Thread { drainLoop() }.also { it.name = "audio-drain"; it.start() }
        Log.i(TAG, "音频采集启动 source=$source mic=${micRecord != null} ${sampleRate}Hz ${channelCount}ch ${bitRate}bps state=${ar.state}")
    }

    private fun inputLoop(bufSize: Int) {
        val codec = this.codec ?: return
        val frameBytes = 2 * channelCount // 16bit × 声道
        val pcmBuf = ByteArray(bufSize)
        val micBuf = ByteArray(bufSize)
        // 静音填充用的全零 buffer(与 limit 等长),保证无系统声音时编码器仍持续输出样本,
        // 否则音频 track 为空 → muxer.stop() 报 "track is not started" → moov 不写入 → mp4 损坏。
        val silentBuf = ByteArray(bufSize)
        try {
            while (running) {
                // 每次循环都从字段取最新 AudioRecord(可能被 rebuildAudioRecord 替换)
                val ar = audioRecord ?: break
                val mic = micRecord
                val inputBufIdx = codec.dequeueInputBuffer(10_000)
                if (inputBufIdx < 0) {
                    // 编码器输入缓冲暂时拿不到:仍要把 PCM 读掉并按帧数推进时间轴,
                    // 否则这段真实时间既没采样也没推进 PTS,音频会比视频越来越慢(长录音音画漂移)。
                    val limit = bufSize
                    val read = runCatching { ar.read(pcmBuf, 0, limit) }.getOrDefault(-1)
                    if (read > 0) {
                        consecutiveReadFail = 0
                        framesRead += read / frameBytes
                    }
                    // 麦克风同样读掉,避免 MIXED 模式下积压
                    mic?.let { runCatching { it.read(micBuf, 0, limit) } }
                    continue
                }
                run {
                    val inputBuf = codec.getInputBuffer(inputBufIdx) ?: return@run
                    inputBuf.clear()
                    val limit = minOf(bufSize, inputBuf.capacity())
                    val read = ar.read(pcmBuf, 0, limit)
                    if (read > 0) {
                        // 读成功,清失败计数
                        consecutiveReadFail = 0
                        // MIXED 模式:把麦克风 PCM 软件叠加到系统声上(16bit 有符号 short 平均)
                        if (mic != null) {
                            val mread = runCatching { mic.read(micBuf, 0, limit) }.getOrDefault(0)
                            if (mread > 0) mixPcm(pcmBuf, micBuf, minOf(read, mread))
                            // 麦克风读不到时退化为仅系统声,不再额外静音处理
                        }
                        framesRead += read / frameBytes
                        val ptsUs = framesRead * 1_000_000L / sampleRate
                        inputBuf.put(pcmBuf, 0, read)
                        codec.queueInputBuffer(inputBufIdx, 0, read, ptsUs, 0)
                    } else {
                        // read <= 0:可能是系统静音,也可能是路由变更(蓝牙/耳机插拔)导致 AudioRecord 失效
                        consecutiveReadFail++
                        // 连续失败超阈值(50 次 ≈ 5s 静音填充)且未重建过,尝试重建 AudioRecord
                        if (consecutiveReadFail >= 50 && !hasRebuilt) {
                            Log.w(TAG, "连续 $consecutiveReadFail 次读失败,尝试重建 AudioRecord(可能路由变更)")
                            rebuildAudioRecord(bufSizeCached)
                        }
                        // read <= 0:系统静音或无媒体声音,填静音 PCM 喂入编码器,
                        // 保证音频 track 有连续样本,muxer.stop() 能正常收尾写入 moov。
                        // MIXED 模式如果系统无声音但有麦克风,这里也只填静音(避免双读同步复杂度)
                        framesRead += limit / frameBytes
                        val ptsUs = framesRead * 1_000_000L / sampleRate
                        inputBuf.put(silentBuf, 0, limit)
                        codec.queueInputBuffer(inputBufIdx, 0, limit, ptsUs, 0)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio input 异常", t)
        }
    }

    /** 16bit PCM 软件混音:short 相加并 clamp,写到 dst(就地修改)。 */
    private fun mixPcm(dst: ByteArray, src: ByteArray, len: Int) {
        // 16bit little-endian,short 按 2 字节读
        val end = len - len % 2
        var i = 0
        while (i < end) {
            val d = ((dst[i + 1].toInt() and 0xff) shl 8) or (dst[i].toInt() and 0xff)
            val s = ((src[i + 1].toInt() and 0xff) shl 8) or (src[i].toInt() and 0xff)
            // 用平均防止相加溢出,实际听起来仍接近两个声音叠加
            val m = ((d.toShort() + s.toShort()) / 2).toInt()
            dst[i] = (m and 0xff).toByte()
            dst[i + 1] = ((m shr 8) and 0xff).toByte()
            i += 2
        }
    }

    /**
     * 重建 AudioRecord(主 + 麦克风):用于运行中遇到连续读失败(蓝牙/耳机插拔导致路由变更)。
     * 同步释放旧实例,按启动时的 source/audioFormat 重新构建。
     * 重建失败则保持静音填充(不降级到 MUTE,因为 MUTE 会通知 writer 关音频 track,影响已录制段)。
     * 最多重建一次,避免无限循环。
     */
    private fun rebuildAudioRecord(bufSize: Int) {
        synchronized(rebuildLock) {
            if (hasRebuilt) return
            hasRebuilt = true
            val fmt = audioFormat ?: return
            Log.i(TAG, "rebuildAudioRecord: 释放旧实例并重建")
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            runCatching { micRecord?.stop() }
            runCatching { micRecord?.release() }
            audioRecord = null
            micRecord = null
            val ar = try {
                val builder = AudioRecord.Builder()
                    .setAudioFormat(fmt)
                    .setBufferSizeInBytes(bufSize)
                when (source) {
                    Source.SYSTEM, Source.MIXED -> {
                        val proj = projection
                        if (proj != null) {
                            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(proj)
                                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                .build()
                            builder.setAudioPlaybackCaptureConfig(captureConfig)
                        }
                    }
                    Source.MIC -> builder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                    Source.MUTE -> return
                }
                builder.build().also {
                    if (it.state != AudioRecord.STATE_INITIALIZED)
                        throw IllegalStateException("重建 AudioRecord 未初始化 state=${it.state}")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "重建 AudioRecord 失败,继续静音填充", t)
                return
            }
            audioRecord = ar
            if (source == Source.MIXED) {
                micRecord = try {
                    AudioRecord.Builder()
                        .setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufSize)
                        .build()
                        .also { if (it.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("micRecord 重建未初始化") }
                } catch (t: Throwable) {
                    Log.w(TAG, "重建麦克风失败,退化为仅系统声", t); null
                }
            }
            runCatching { ar.startRecording() }
            runCatching { micRecord?.startRecording() }
            consecutiveReadFail = 0
            Log.i(TAG, "AudioRecord 重建完成,继续录制")
        }
    }

    private fun drainLoop() {
        val codec = this.codec ?: return
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val idx = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 继续 */ }
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        callback?.onAudioFormat(codec.outputFormat)
                    }
                    idx >= 0 -> {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf == null) {
                            // 同视频轨:null 也必须归还缓冲,否则缓冲耗尽后音频永久静音。
                            codec.releaseOutputBuffer(idx, false)
                        } else {
                            if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                callback?.onAudioSample(buf, info)
                            }
                            codec.releaseOutputBuffer(idx, false)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "audio drain 异常", t)
        } finally {
            stopInternal()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        stopInternal()
    }

    @Synchronized
    private fun stopInternal() {
        // audioOk=false 表示初始化阶段就失败/静音,没有 codec/audioRecord 要释放
        if (!running && codec == null && audioRecord == null) return
        runCatching { micRecord?.stop() }
        runCatching { audioRecord?.stop() }
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { audioRecord?.release() }
        runCatching { micRecord?.release() }
        audioRecord = null
        micRecord = null
        codec = null
        Log.i(TAG, "音频采集停止 ok=$audioOk")
    }

    companion object {
        private const val TAG = "AudioCapturer"
        private const val AUDIO_MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    }
}
