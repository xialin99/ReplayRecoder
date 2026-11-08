package com.replay.recoder.editor

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 多段 MP4 拼接器:用 [MediaExtractor] + [MediaMuxer] 把多个同编码段拼成一条成片。
 *
 * 背景:录屏段文件由 [com.replay.recoder.record.SegmentWriter] 轮转写出,段间编码器
 * 不重启,首帧可能非关键帧,且段文件 moov 可能不完整。Media3 Transformer 类方案
 * 在解析此类段时抛 "Malformed sample table (stbl) missing stsd",导致拼接失败。
 * 而 [MediaExtractor] 与 ExoPlayer 一样有容错解析,能读能播,因此用它逐段抽取样本、
 * [MediaMuxer] 重新封装,产出合法 MP4。
 *
 * 约束:所有段必须同源(同视频/音频编码参数),轨道数一致。pts 逐段累加。
 */
object Mp4Merger {

    private const val TAG = "Mp4Merger"

    /** 读取样本的初始缓冲(1MB),不足时倍增到 [MAX_BUF_BYTES]。 */
    private const val INITIAL_BUF_BYTES = 1 * 1024 * 1024

    /** 单样本缓冲上限(32MB):超过则认定样本表异常,跳过该轨剩余样本而不是放弃整场合并。 */
    private const val MAX_BUF_BYTES = 32 * 1024 * 1024

    /** 最近一次合并失败的原因(供 UI 展示),成功时为 null。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 把 [inputPaths] 按顺序拼接成 [outPath]。
     * @param onProgress 每处理完一段调用一次,参数 (当前完成段数,总段数);总段数 ≤1 时不回调。
     * @return true 成功
     */
    fun merge(
        inputPaths: List<String>,
        outPath: String,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): Boolean {
        lastError = null
        if (inputPaths.isEmpty()) {
            lastError = "输入段列表为空"
            return false
        }
        val totalSegs = inputPaths.size
        runCatching { File(outPath).delete() }

        // 用 muxerRef 兜底释放:lambda 内使用非空局部 val muxer,避免 Kotlin 无法智能转换可空变量
        var muxerRef: MediaMuxer? = null
        var videoTrack = -1
        var audioTrack = -1
        // 累积 pts:每段内部 pts 从 0 起,跨段需加上前面所有段的最大 pts
        var videoPtsOffset = 0L
        var audioPtsOffset = 0L
        var videoMaxPts = 0L
        var audioMaxPts = 0L
        // 样本缓冲:初值 1MB 并按需倍增。I 帧可能远大于旧实现的 256KB(高分屏/高码率/
        // 画面突变的关键帧),缓冲不足时 MediaExtractor.readSampleData 会抛
        // IllegalArgumentException。旧实现让整段失败 → allWritten=false → 有效产物被丢弃
        // → 降级成多段(事件82 用户只看到 15s)。
        var buf = ByteBuffer.allocate(INITIAL_BUF_BYTES)
        var writtenSamples = 0L
        var tracksAdded = false
        var skippedSegs = 0

        try {
            val muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerRef = muxer
            inputPaths.forEachIndexed { segIdx, path ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(path)
                    val trackCount = extractor.trackCount
                    // 用第一个能正常打开的段探测轨道:若首段损坏(样本表异常),
                    // 仍能用后续段建轨,避免整场事件失败。
                    if (!tracksAdded) {
                        for (i in 0 until trackCount) {
                            val fmt = extractor.getTrackFormat(i)
                            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                            if (mime.startsWith("video/") && videoTrack < 0) {
                                videoTrack = muxer.addTrack(fmt)
                            } else if (mime.startsWith("audio/") && audioTrack < 0) {
                                audioTrack = muxer.addTrack(fmt)
                            }
                        }
                        if (videoTrack < 0 && audioTrack < 0) {
                            Log.e(TAG, "段[$segIdx] 无可识别轨道,尝试下一段: $path")
                            skippedSegs++
                            return@forEachIndexed
                        }
                        muxer.start()
                        tracksAdded = true
                    }

                    // 逐轨道抽取样本写入。
                    // 关键:每段样本必须按轨道顺序读到底再切下一段(extractor 单次 selectTrack 后
                    // readSampleData/advance 只走该轨),不能在同段内先读部分视频又切去读音频——
                    // 切轨会导致 extractor 内部指针错位,丢失该段剩余样本 → 拼接产物时长缩水。
                    for (t in 0 until trackCount) {
                        val fmt = extractor.getTrackFormat(t)
                        val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                        val isVideo = mime.startsWith("video/")
                        val track = if (isVideo) videoTrack else audioTrack
                        if (track < 0) continue
                        extractor.selectTrack(t)
                        val offset = if (isVideo) videoPtsOffset else audioPtsOffset
                        val info = android.media.MediaCodec.BufferInfo()
                        var trackDone = false
                        while (!trackDone) {
                            var size = -1
                            // 缓冲不足时倍增重试同一样本(样本索引未推进,可安全重试)
                            while (true) {
                                buf.clear()
                                try {
                                    size = extractor.readSampleData(buf, 0)
                                    break
                                } catch (e: IllegalArgumentException) {
                                    if (buf.capacity() >= MAX_BUF_BYTES) {
                                        Log.e(TAG, "样本超限或样本表损坏,跳过该轨剩余样本: $path", e)
                                        trackDone = true
                                        break
                                    }
                                    buf = ByteBuffer.allocate(minOf(buf.capacity() * 2, MAX_BUF_BYTES))
                                }
                            }
                            if (trackDone || size < 0) break
                            info.set(0, size, (extractor.sampleTime + offset).coerceAtLeast(0L), extractor.sampleFlags)
                            try {
                                muxer.writeSampleData(track, buf, info)
                            } catch (e: Throwable) {
                                Log.e(TAG, "写入样本失败,跳过该轨剩余样本: $path", e)
                                break
                            }
                            writtenSamples++
                            if (isVideo) {
                                videoMaxPts = maxOf(videoMaxPts, info.presentationTimeUs)
                            } else {
                                audioMaxPts = maxOf(audioMaxPts, info.presentationTimeUs)
                            }
                            try {
                                extractor.advance()
                            } catch (e: Throwable) {
                                Log.e(TAG, "advance 失败,结束该轨: $path", e)
                                break
                            }
                        }
                        runCatching { extractor.unselectTrack(t) }
                    }
                    // 段结束:把本段最大 pts 累加到下一段的偏移,保证跨段 pts 单调递增。
                    // 用 1ms 间隙而非紧贴:MediaMuxer 对完全相邻/回退的 pts 可能 stop 失败。
                    videoPtsOffset = videoMaxPts + 1_000L
                    audioPtsOffset = audioMaxPts + 1_000L
                } catch (t: Throwable) {
                    // 单段损坏(样本表异常 / I 帧超出缓冲且无法扩容)不应毁掉整场事件:
                    // 跳过该段继续处理剩余段,产物只少这一段。
                    Log.e(TAG, "段[$segIdx] 抽取失败,跳过该段: $path", t)
                    skippedSegs++
                } finally {
                    runCatching { extractor.release() }
                    // 进度回调:完成段数=segIdx+1(forEachIndexed 的索引从 0 起)
                    if (totalSegs > 1) runCatching { onProgress?.invoke(segIdx + 1, totalSegs) }
                }
            }
            // 只有「一个样本都没写进去」才算失败;个别段损坏不影响整体
            if (writtenSamples == 0L || !tracksAdded) {
                lastError = "所有段均无可写入样本"
                return false
            }
            // ★ 最终以「产物是否含 moov」为准,而不是以「是否每段都成功」为准。
            // 旧实现:任何一段抛异常 → allWritten=false → return false,把已经写好的
            // 33MB 有效产物当成失败删掉,再降级成多段 → 用户只看到 15s(事件82 S级bug)。
            // muxer.stop() 抛异常时 release() 仍可能补写 moov,所以 stop 失败也要校验产物。
            var stopError: Throwable? = null
            try {
                muxer.stop()
            } catch (t: Throwable) {
                stopError = t
                Log.e(TAG, "muxer.stop 抛异常,稍后校验产物", t)
            }
            runCatching { muxer.release() }
            muxerRef = null
            val valid = mergedOk(outPath) && File(outPath).length() > 1_024
            if (!valid) {
                lastError = "产物无 moov 或为空" + (stopError?.let { " (${it.message})" } ?: "")
                runCatching { File(outPath).delete() }
                return false
            }
            if (stopError != null) Log.w(TAG, "muxer.stop 抛异常但产物完整,按成功处理")
            if (skippedSegs > 0) Log.w(TAG, "合并完成,跳过 $skippedSegs 个损坏/空段")
            lastError = null
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "拼接失败", t)
            lastError = "拼接失败: ${t.message}"
            return false
        } finally {
            // muxer 是 try 块内的局部变量,finally 不可见;用 muxerRef 兜底释放
            runCatching { muxerRef?.release() }
            // 产物缺 moov(不可播)时删除半成品,避免被当成正常事件
            if (!mergedOk(outPath)) {
                if (lastError == null) lastError = "产物缺 moov 盒"
                runCatching { File(outPath).delete() }
            }
        }
    }

    /** 校验产物是否为含 moov 的合法 mp4。供导出/分享流程调用。
     *  Android MediaMuxer 把 moov 写在文件**末尾**(mdat 之后),不能用只读头部的方式检测,
     *  否则正常产物会被误判为损坏并删除。用 RandomAccessFile 按 box 顺序 seek 遍历整个文件,
     *  找到 moov 即合法;走到文件尾仍无 moov 则损坏。
     *
     *  ★ 必须正确处理 MP4 规范中三种特殊 size 值(曾因不处理 size=0 把合法 mdat 误判损坏导致删除产物 S级bug):
     *    size=0 → box 延伸到文件末尾(流式写入常见,MediaMuxer 的 mdat 就是这种)
     *    size=1 → 真实 size 在后续 8 字节(64 位扩展)
     *    size=8 → 顶层完整 box(size field 本身占 8 字节,内容为空) */
    internal fun mergedOk(path: String): Boolean = runCatching {
        val f = File(path)
        if (!f.exists() || f.length() < 64) return false
        java.io.RandomAccessFile(f, "r").use { raf ->
            var pos = 0L
            val len = f.length()
            val header = ByteArray(8)
            val extSize = ByteArray(8)
            while (pos + 8 <= len) {
                raf.seek(pos)
                raf.readFully(header)
                val size = ((header[0].toLong() and 0xFFL) shl 24) or ((header[1].toLong() and 0xFFL) shl 16) or
                    ((header[2].toLong() and 0xFFL) shl 8) or (header[3].toLong() and 0xFFL)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                if (type == "moov") return true
                when {
                    size == 0L -> {
                        // box 延伸到文件末尾,后面不该再有 box → 到此为止
                        return false
                    }
                    size == 1L -> {
                        // 64 位扩展 size,读后续 8 字节
                        if (pos + 16 > len) return false
                        raf.seek(pos + 8)
                        raf.readFully(extSize)
                        val realSize = ((extSize[0].toLong() and 0xFFL) shl 56) or ((extSize[1].toLong() and 0xFFL) shl 48) or
                            ((extSize[2].toLong() and 0xFFL) shl 40) or ((extSize[3].toLong() and 0xFFL) shl 32) or
                            ((extSize[4].toLong() and 0xFFL) shl 24) or ((extSize[5].toLong() and 0xFFL) shl 16) or
                            ((extSize[6].toLong() and 0xFFL) shl 8) or (extSize[7].toLong() and 0xFFL)
                        if (realSize < 16 || realSize > len - pos) return false
                        pos += realSize
                    }
                    size < 8L -> return false
                    else -> pos += size
                }
            }
            false
        }
    }.getOrDefault(false)
}
