package com.replay.recoder.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import com.replay.recoder.record.WatermarkConfig
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.CountDownLatch

/**
 * 视频编辑内核,基于 FFmpeg(ffmpeg-kit-min 6.0-2)。
 *
 * 提供两个能力:
 * - [trimWithFFmpeg]:按起点/终点毫秒裁剪,可同时调节音量(volume 0..2,1 = 原音量)
 * - [concatWithFFmpeg]:把多个视频按给定顺序拼接成一条成片(-c copy 零重编码)
 *
 * 为什么不用 Media3 Transformer(历史踩坑,勿回退):
 * - Transformer 对段文件首帧非关键帧会抛 "Malformed sample table (stbl) missing stsd" 崩溃
 * - Transformer 默认重编码,输出 8-10Mbps 高码率,分享文件严重膨胀
 * - FFmpeg concat demuxer 容错强,-c copy 无画质损失且速度极快
 *
 * 注意:两个函数都会**阻塞调用线程直到导出完成**,务必在后台线程调用;
 * 进度通过 onProgress 回调(0..100),在 FFmpeg 内部回调线程触发。
 */
object VideoEditor {

    private const val TAG = "VideoEditor"

    /** 最近一次导出失败的原因(供 UI 展示),成功时为 null。 */
    @Volatile
    var lastError: String? = null
        private set

    /** 当前正在执行的 FFmpeg sessionId(供 [cancelCurrent] 取消) */
    @Volatile
    private var currentSessionId: Long? = null

    /**
     * 裁剪 + 音量调节(FFmpeg 版)。
     *
     * 内核策略:
     * - 音量 = 1:纯流拷贝(`-c copy -movflags +faststart`),秒级完成、零画质损失
     * - 音量 ≠ 1(含 0 静音):视频流拷贝 + 仅音频重编码挂音量滤镜
     *   (`-c:v copy -af volume=<v> -c:a aac -b:a 128k`)。
     *   ffmpeg-kit-min 不带 libx264 等视频重编码器,所以**不能**重编码视频轨;
     *   只动音频轨即可满足"调音量/静音"需求,视频画质无损。
     *   音频重编码失败时自动降级为纯流拷贝(音量不生效),保证剪辑永远能存出来。
     *
     * 裁剪精度:`-ss`/`-to` 放在 `-i` 前(input seeking),流拷贝模式下从最近关键帧
     * 开始(本项目 KEY_I_FRAME_INTERVAL=1s,起点误差 ≤1s),对"复现 bug"场景足够。
     *
     * @param inputPath 源视频绝对路径
     * @param outPath 输出 mp4 绝对路径
     * @param startMs 起点毫秒(含)
     * @param endMs 终点毫秒(含),需 > startMs
     * @param volume 音量倍数 0..2(0 = 静音,1 = 原音量,2 = 双倍)
     * @param onProgress 进度回调 0..100
     * @return true 成功
     */
    fun trimWithFFmpeg(
        inputPath: String,
        outPath: String,
        startMs: Long,
        endMs: Long,
        volume: Float,
        onProgress: (Int) -> Unit,
    ): Boolean {
        if (startMs < 0 || endMs <= startMs) {
            lastError = "裁剪范围非法: start=${startMs}ms end=${endMs}ms"
            return false
        }
        if (!File(inputPath).exists()) {
            lastError = "源文件不存在: $inputPath"
            return false
        }
        runCatching { File(outPath).delete() }
        lastError = null

        val durationMs = endMs - startMs
        // 裁剪时长进进度估算:已知区间时长,无需 probe(比 probe 更准)
        val totalMs = durationMs

        // -ss/-to 放在 -i 之前:input seeking,直接跳到目标位置不解码前面内容(快)。
        // -to 在 input 侧按文件绝对时间轴解释(ffmpeg 6.x 语义),即 [startMs, endMs] 闭区间。
        val ss = String.format(Locale.US, "%.3f", startMs / 1000.0)
        val to = String.format(Locale.US, "%.3f", endMs / 1000.0)

        // 音量接近 1 视为原音量(纯拷贝);否则走音频重编码分支
        val needVolume = volume < 0.99f || volume > 1.01f

        val baseArgs = "-ss $ss -to $to -i \"$inputPath\""
        val volumeStr = String.format(Locale.US, "%.3f", volume)

        // 先试主命令;音频重编码失败(aac 编码器异常等)时降级纯流拷贝重跑
        val primaryCmd = if (needVolume) {
            "$baseArgs -map 0:v:0 -map 0:a? -c:v copy -af volume=$volumeStr " +
                "-c:a aac -b:a 128k -movflags +faststart \"$outPath\""
        } else {
            "$baseArgs -map 0:v:0 -map 0:a? -c copy -movflags +faststart \"$outPath\""
        }
        var ok = runFfmpeg(primaryCmd, totalMs, onProgress)

        if (!ok && needVolume) {
            // 降级:放弃音量,纯流拷贝保证"一定能存出来"(画质完整优先于音量生效)
            Log.w(TAG, "音频重编码失败,降级纯流拷贝(音量不生效): ${lastError}")
            lastError = null
            runCatching { File(outPath).delete() }
            val fallbackCmd = "$baseArgs -map 0:v:0 -map 0:a? -c copy -movflags +faststart \"$outPath\""
            ok = runFfmpeg(fallbackCmd, totalMs, onProgress)
            if (ok) {
                // 成功但音量没生效:暴露给调用方,由 UI 决定是否提示
                lastError = "AUDIO_VOLUME_SKIPPED"
            }
        }

        if (!ok) {
            Log.e(TAG, "trimWithFFmpeg 失败: $lastError")
        }
        return ok
    }

    /**
     * 拼接:把 [inputPaths] 按列表顺序拼成一条完整视频。
     *
     * FFmpeg concat demuxer + `-c copy` 零重编码:
     * - PTS 自动归一化:段间无间隙,回放不卡帧
     * - -c copy 零重编码:速度极快(1min 视频 < 1s),无画质损失
     * - +faststart:moov 移到文件头,ExoPlayer 秒开
     * - 工业级容错:段文件首帧非关键帧也不会崩溃(Transformer 会报 missing stsd)
     *
     * @param inputPaths 分段文件路径列表(按时间顺序)
     * @param outPath 输出 mp4 绝对路径
     * @param onProgress 进度回调 0..100
     * @return true 成功
     */
    fun concatWithFFmpeg(
        inputPaths: List<String>,
        outPath: String,
        onProgress: (Int) -> Unit,
    ): Boolean {
        if (inputPaths.isEmpty()) return false
        runCatching { File(outPath).delete() }
        lastError = null

        // 单段直接拷贝
        if (inputPaths.size == 1) {
            return runCatching {
                File(inputPaths.first()).copyTo(File(outPath), overwrite = true)
                onProgress(100)
                true
            }.getOrDefault(false)
        }

        // 生成 concat 列表文件。文件名必须唯一:编辑合并/播放页分享/列表批量可能并发,
        // 固定名 concat_list.txt 会被一方 delete 掐掉另一方正在读取的列表。
        val listFile = File(File(outPath).parentFile, "concat_list_${System.nanoTime()}.txt")
        try {
            listFile.bufferedWriter().use { writer ->
                inputPaths.forEach { path ->
                    val escaped = path.replace("'", "'\\''")
                    writer.write("file '$escaped'")
                    writer.newLine()
                }
            }
        } catch (t: Throwable) {
            lastError = "生成 concat 列表失败: ${t.message}"
            return false
        }

        // 探测各段时长(微秒),用于进度估算
        val segDurations = inputPaths.map { probeDurationUs(it) }
        val totalUs = segDurations.sum()
        Log.i(TAG, "FFmpeg concat: ${inputPaths.size}段, 各段时长=${segDurations.map { it / 1000 }}, 总时长=${totalUs / 1000}ms")

        // FFmpeg concat demuxer + -c copy:
        // concat demuxer 会自动累加段间 PTS(每段内部 pts 从 0 起,拼接时按前段末尾 pts 偏移),
        // -c copy 零重编码,-movflags +faststart 把 moov 移到文件头。
        val cmd = "-f concat -safe 0 -i \"${listFile.absolutePath}\"" +
            " -c copy -movflags +faststart \"$outPath\""

        val latch = CountDownLatch(1)
        val success = booleanArrayOf(false)
        val errorMsg = arrayOfNulls<String>(1)

        val session = FFmpegKit.executeAsync(cmd, { session ->
            runCatching { listFile.delete() }
            val rc = session.returnCode
            when {
                ReturnCode.isSuccess(rc) -> {
                    val out = File(outPath)
                    success[0] = out.exists() && out.length() > 0
                    if (!success[0]) {
                        errorMsg[0] = "输出文件为空"
                    }
                }
                ReturnCode.isCancel(rc) -> {
                    errorMsg[0] = "合并已取消"
                }
                else -> {
                    errorMsg[0] = "FFmpeg 失败 rc=${rc.value}: ${session.failStackTrace?.take(200)}"
                }
            }
            latch.countDown()
        }, { log ->
            Log.d(TAG, "FFmpeg: ${log.message}")
        }, { stats ->
            if (totalUs > 0) {
                val pct = (stats.time * 100 / (totalUs / 1000)).toInt().coerceIn(0, 99)
                onProgress(pct)
            }
        })

        // 超时保护:FFmpeg 卡死时不能永久阻塞调用协程(按目标时长 5 倍 + 60s 估算)
        val timeoutMs = (totalUs / 1000).coerceAtLeast(0L) * 5 + 60_000L
        val finished = awaitWithCancel(latch, session.sessionId, timeoutMs)
        // 无论成功/超时都清掉列表文件,避免残留
        runCatching { listFile.delete() }
        onProgress(100)

        if (!finished) {
            lastError = "FFmpeg 合并超时(${timeoutMs}ms)"
            Log.e(TAG, "concat 超时: $lastError")
            return false
        }
        if (!success[0]) {
            lastError = errorMsg[0] ?: "FFmpeg 合并失败"
            Log.e(TAG, "concat 失败: $lastError")
        }
        return success[0]
    }

    /**
     * 重编码压缩:用 libx264 + CRF 30 砍半以上体积,产物 H.264 可被 Jira/浏览器内嵌预览。
     *
     * 参数选择(允许丢画质换体积):
     * - CRF 30:比默认 23 多砍约 30-40%,画质对录屏场景可接受(桌面 UI 变化多但不是电影)
     * - superfast preset:比 ultrafast 压缩效率高 10-20%,速度只慢 10%
     * - 高分辨率视频(≥1080p)先降到 1280 宽再压:再砍约 30%,画质损失比 CRF 再拉大更可控
     * - 音频 aac 96k:比 128k 再砍 25%,录屏声音多为环境声/系统音,差异不大
     * - 不指定 -movflags +faststart:鸿蒙设备上 faststart + libx264 组合丢 moov(实测),
     *   让 moov 自然写在文件尾,MP4 规范合法
     *
     * 失败处理:
     * - libx264 不可用/中途崩 → 返回 false,由调用方回退到"不压缩的合并源"(也是 H.264,
     *   Jira 认,只是体积大些);不回退 mpeg4(产物 Jira 不认)
     *
     * @param inputPath 源视频(MP4)绝对路径
     * @param outPath 压缩输出绝对路径
     * @param onProgress 进度回调 0..100
     * @return true 成功(产物非空,但不校验 moov——那是调用方职责)
     */
    fun compressToFit(
        inputPath: String,
        outPath: String,
        onProgress: (Int) -> Unit = {},
    ): Boolean {
        lastError = null
        val durUs = probeDurationUs(inputPath)
        if (durUs <= 0L) {
            lastError = "读不到时长,无法压缩"
            return false
        }
        val durMs = durUs / 1000
        val t0 = System.currentTimeMillis()
        runCatching { File(outPath).delete() }

        // 探测源视频分辨率:≥1080p 先降到 1280 宽,再砍 30%
        val (w, h) = probeResolution(inputPath)
        val scaleFilter = if (w >= 1920 || h >= 1080) {
            " -vf scale=1280:-2"
        } else ""

        // CRF 30 + superfast + 可选降分辨率:砍半以上 + 稳定 + 够快
        val cmd = "-y -i \"$inputPath\"$scaleFilter" +
            " -c:v libx264 -preset superfast -crf 30" +
            " -c:a aac -b:a 96k" +
            " \"$outPath\""

        val ok = runFfmpeg(cmd, durMs, onProgress)
        if (!ok) {
            Log.w(TAG, "libx264 压缩失败,耗时=${System.currentTimeMillis() - t0}ms, 错误=$lastError")
            return false
        }
        val outFile = File(outPath)
        if (!outFile.exists() || outFile.length() == 0L) {
            lastError = "压缩产物为空"
            return false
        }
        val ratio = String.format(Locale.US, "%.1fx", File(inputPath).length().toDouble() / outFile.length())
        Log.i(TAG, "压缩完成($w x $h → $scaleFilter CRF30): ${File(inputPath).length() / 1024 / 1024}MB → ${outFile.length() / 1024 / 1024}MB ($ratio), 耗时=${System.currentTimeMillis() - t0}ms")
        return true
    }

    /**
     * 给视频烧录时间水印(FFmpeg overlay + Android Canvas 生成的透明文字图)。
     *
     * 为什么用 overlay 而不是 drawtext:
     * - drawtext 依赖 freetype/fontconfig,ffmpeg-kit 在 Android 上没有 fontconfig 配置,
     *   fontfile 路径各机型不一致,极易失败;
     * - Canvas 绘制的字体可用性 100%(Typeface.MONOSPACE 系统内置),且与录屏水印视觉一致。
     *
     * 水印文本是录制开始时刻的固定时间串(不逐帧跳动):复现录像里起始时刻已足以定位时间点。
     * 字号/位置按视频高度等比换算,与录制分辨率无关(1080p 下 13sp ≈ 34px)。
     *
     * @param text 水印文字,如 "2026-10-29 15:30:12"
     * @param sizeSp 相对字号(10..30),与录屏水印设置共用
     * @param position 0=左上 1=右上 2=左下 3=右下 4=中心
     * @param onProgress 进度回调 0..100
     * @return true 成功(产物存在且非空)
     */
    fun burnTimeWatermark(
        inputPath: String,
        outPath: String,
        text: String,
        sizeSp: Int,
        position: Int,
        onProgress: (Int) -> Unit = {},
    ): Boolean {
        lastError = null
        val (_, vh) = probeResolution(inputPath)
        if (vh <= 0) {
            lastError = "读不到视频分辨率"
            return false
        }
        val durUs = probeDurationUs(inputPath)
        if (durUs <= 0L) {
            lastError = "读不到视频时长"
            return false
        }

        val png = File(File(outPath).parentFile, "wm_${System.nanoTime()}.png")
        val made = runCatching { makeTextPng(png, text, sizeSp, vh) }.getOrDefault(false)
        if (!made) {
            lastError = "生成水印文字图失败"
            runCatching { png.delete() }
            return false
        }

        // 边距与字号同比例:1080p / 13sp → 约 34px 字号、41px 边距
        val fontPx = (vh * sizeSp / 413f).coerceAtLeast(12f)
        val m = (fontPx * 1.2f).toInt()
        val posExpr = when (position) {
            WatermarkConfig.POS_TOP_LEFT -> "x=$m:y=$m"
            WatermarkConfig.POS_TOP_RIGHT -> "x=W-w-$m:y=$m"
            WatermarkConfig.POS_BOTTOM_LEFT -> "x=$m:y=H-h-$m"
            WatermarkConfig.POS_CENTER -> "x=(W-w)/2:y=(H-h)/2"
            else -> "x=W-w-$m:y=H-h-$m"
        }

        runCatching { File(outPath).delete() }
        // 不指定 -movflags +faststart:鸿蒙上 faststart + libx264 组合会丢 moov(本项目实测),
        // 让 moov 自然写在文件尾,MP4 规范合法。
        val cmd = "-y -i \"$inputPath\" -i \"${png.absolutePath}\"" +
            " -filter_complex \"[0:v][1:v]overlay=$posExpr[v]\"" +
            " -map \"[v]\" -map 0:a? -c:v libx264 -preset superfast -crf 26" +
            " -c:a aac -b:a 128k \"$outPath\""
        val ok = runFfmpeg(cmd, durUs / 1000, onProgress)
        runCatching { png.delete() }
        if (!ok) {
            Log.w(TAG, "烧录水印失败: $lastError")
            return false
        }
        val out = File(outPath)
        if (!out.exists() || out.length() == 0L) {
            lastError = "水印产物为空"
            return false
        }
        Log.i(TAG, "水印烧录完成: ${File(inputPath).length() / 1024 / 1024}MB → ${out.length() / 1024 / 1024}MB")
        return true
    }

    /** 把水印文字画进一张紧凑的透明 PNG(只包住文字本身),交由 overlay 按坐标合成。
     *  做成小子图而非整帧位图:4K 帧位图要 33MB,子图只有几十 KB。 */
    private fun makeTextPng(file: File, text: String, sizeSp: Int, videoHeight: Int): Boolean {
        val fontPx = (videoHeight * sizeSp / 413f).coerceAtLeast(12f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = fontPx
            typeface = Typeface.MONOSPACE
            // 描边阴影:浅色背景上也能看清(与 WatermarkControl 视觉一致)
            setShadowLayer(fontPx * 0.22f, fontPx * 0.05f, fontPx * 0.05f, Color.argb(0xCC, 0, 0, 0))
        }
        val fm = paint.fontMetrics
        val pad = (fontPx * 0.35f).toInt() + 2
        val tw = paint.measureText(text).toInt()
        val th = (fm.descent - fm.ascent).toInt()
        if (tw <= 0 || th <= 0) return false
        val bmp = Bitmap.createBitmap(tw + pad * 2, th + pad * 2, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, pad.toFloat(), pad - fm.ascent, paint)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return file.exists() && file.length() > 0
    }

    /** 用 MediaMetadataRetriever 探测视频分辨率(w,h)。失败返回 (0,0)。 */
    private fun probeResolution(path: String): Pair<Int, Int> = runCatching {
        MediaMetadataRetriever().use { r ->
            r.setDataSource(path)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            Pair(w, h)
        }
    }.getOrDefault(Pair(0, 0))

    /**
     * 执行一条 FFmpeg 命令并阻塞等待结果。
     *
     * @param cmd FFmpeg 参数串(不含程序名)
     * @param totalMs 目标输出总时长(毫秒),用于进度估算;≤0 时只回调 0/100
     * @return true 成功(输出文件存在且非空)
     */
    private fun runFfmpeg(cmd: String, totalMs: Long, onProgress: (Int) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        val success = booleanArrayOf(false)
        val errorMsg = arrayOfNulls<String>(1)

        val session = FFmpegKit.executeAsync(cmd, { session ->
            val rc = session.returnCode
            when {
                ReturnCode.isSuccess(rc) -> {
                    // 输出文件检查由调用方做(outPath 存在);这里先记成功
                    success[0] = true
                }
                ReturnCode.isCancel(rc) -> {
                    errorMsg[0] = "已取消"
                }
                else -> {
                    errorMsg[0] = "FFmpeg 失败 rc=${rc.value}: ${session.failStackTrace?.take(200)}"
                }
            }
            latch.countDown()
        }, { log ->
            Log.d(TAG, "FFmpeg: ${log.message}")
        }, { stats ->
            if (totalMs > 0) {
                val pct = (stats.time * 100 / totalMs).toInt().coerceIn(0, 99)
                onProgress(pct)
            }
        })

        // 超时保护:FFmpeg 卡死时不能永久阻塞(按目标时长 5 倍 + 60s 估算)
        val timeoutMs = totalMs.coerceAtLeast(0L) * 5 + 60_000L
        val finished = awaitWithCancel(latch, session.sessionId, timeoutMs)
        if (!finished) {
            lastError = "FFmpeg 超时(${timeoutMs}ms)"
            return false
        }
        onProgress(100)

        if (!success[0]) {
            lastError = errorMsg[0] ?: "FFmpeg 执行失败"
        }
        return success[0]
    }

    /** 等待 FFmpeg 完成;超时或调用方取消(取消会通过 [cancelCurrent] 触发 latch)时返回 false。
     *  超时会真正 cancel 掉 session,避免对话框关掉后后台仍在跑。 */
    private fun awaitWithCancel(latch: CountDownLatch, sessionId: Long, timeoutMs: Long): Boolean {
        currentSessionId = sessionId
        val finished = try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) runCatching { FFmpegKit.cancel(sessionId) }
        currentSessionId = null
        return finished
    }

    /** 取消正在执行的 FFmpeg 导出(对话框"取消"按钮调用)。 */
    fun cancelCurrent() {
        currentSessionId?.let { runCatching { FFmpegKit.cancel(it) } }
    }

    /** 用 FFprobe 探测文件时长(微秒) */
    private fun probeDurationUs(path: String): Long = runCatching {
        val session = FFprobeKit.getMediaInformation(path)
        val dur = session.mediaInformation?.duration?.toDoubleOrNull() ?: 0.0
        (dur * 1_000_000).toLong()
    }.getOrDefault(0L)
}
