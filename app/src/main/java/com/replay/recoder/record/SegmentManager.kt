package com.replay.recoder.record

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 段文件管理:轮转队列。
 *
 * 每段固定时长 [SEGMENT_SEC] 秒。窗口 = windowMinutes 分钟时,
 * 应保留段数 = ceil(windowMinutes * 60 / SEGMENT_SEC)。
 * 超出则删最旧段。回放时把 [current] 里的段按序串联。
 *
 * 段文件放在 app 私有目录下 [SEG_DIR],不污染相册;导出时才合并成 mp4 到相册。
 */
class SegmentManager(
    private val context: Context,
    private var windowMinutes: Int,
) {
    private val segDir: File = File(context.filesDir, SEG_DIR).apply { mkdirs() }

    init {
        // 清理"孤儿段":进程被系统杀掉/异常退出后遗留的段文件既不属于任何事件(没有 meta),
        // 也永远不会被回放,只会长期占用磁盘直到把空间撑满(进而导致下次录制失败)。
        // 只清理超过 24 小时的,避免误删正在进行的录制(本类只在开录时创建,不会并发)。
        // 放后台线程执行:段多时 listFiles/删除会明显拖慢开录,在主线程易触发 ANR。
        Thread {
            runCatching {
                val cutoff = System.currentTimeMillis() - ORPHAN_MAX_AGE_MS
                segDir.listFiles { f -> f.isFile && f.name.startsWith("seg_") && f.lastModified() < cutoff }
                    ?.forEach { f ->
                        if (f.delete()) Log.i(TAG, "清理孤儿段: ${f.name}")
                    }
            }.onFailure { Log.w(TAG, "清理孤儿段失败", it) }
        }.also { it.isDaemon = true; it.name = "seg-orphan-clean" }.start()
    }

    /** 段队列,队头=最旧 */
    private val queue = ArrayDeque<String>()
    private var seq = 0

    /** 段队列快照。必须加锁:与 drain 线程的 nextSegment(addLast) 并发,
     *  不加锁遍历 ArrayDeque 会抛 ConcurrentModificationException。 */
    val current: List<String> get() = synchronized(this) { queue.toList() }

    /** 应保留段数。
     *  windowMinutes == 0 表示 30 秒档位(2 段,15s/段);否则按分钟计算。
     *  额外 +1:换段瞬间新段刚开始写(内容≈0),若不留缓冲,新段一入队就会把窗口内
     *  最旧的整段挤掉——停录恰卡在段边界时窗口内容只剩 (max-1)×15s(曾致 30s 回录
     *  只剩 15s)。多留 1 段保证任意时刻窗口内容 ≥ 设定窗口,多余部分由
     *  [freezeSnapshot] 按窗口时长精确裁剪。 */
    private fun maxSegments(): Int {
        val total = if (windowMinutes == 0) 30 else windowMinutes * 60
        return (total + SEGMENT_SEC - 1) / SEGMENT_SEC + 1
    }

    /** 下一段文件路径,并登记入队。 */
    fun nextSegment(): String {
        val max = maxSegments()
        val path = File(segDir, "seg_${seq++.toString().padStart(6, '0')}.mp4").absolutePath
        synchronized(this) {
            queue.addLast(path)
            // 超过窗口就删最旧(包含当前正在写的段不删,所以这里删到 max-1)
            while (queue.size > max) {
                val old = queue.removeFirst()
                runCatching { File(old).delete() }
                    .onFailure { Log.w(TAG, "删段失败: $old", it) }
                RecordState.pruneSegment(old)
            }
        }
        RecordState.appendSegment(path)
        return path
    }

    /** 从队列中移除指定段并删除文件（用于空段清理）。 */
    fun removeSegment(path: String) {
        synchronized(this) { queue.remove(path) }
        runCatching { File(path).delete() }
        RecordState.pruneSegment(path)
    }

    /** 切换窗口档位,立即裁掉超出的旧段。 */
    fun setWindow(minutes: Int) {
        windowMinutes = minutes
        val max = maxSegments()
        synchronized(this) {
            while (queue.size > max) {
                val old = queue.removeFirst()
                runCatching { File(old).delete() }
                RecordState.pruneSegment(old)
            }
        }
    }

    /**
     * 冻结当前窗口:把队列里的段**合并成一个完整 mp4**,存到事件专属目录。
     *
     * 15s 分段只是录制期环形缓冲的内部实现(用于丢弃旧画面),用户不需要看到
     * 一段段的碎文件。停录时用 FFmpeg concat -c copy 把窗口内所有段拼接
     * (零重编码,边界自动对齐)成单个 `event.mp4`,
     * 回放/分享/导出都是这一个整体文件,回放也不再有段间切换的顿挫。
     *
     * 合并在停录后台线程执行([RecordService.RecStopThread]),不阻塞 UI。
     * 合并失败时降级为逐段 [File.renameTo] 移动(仍可回放),保证事件不丢。
     *
     * @return 事件文件路径列表,正常为单个 `event.mp4`;降级时为多个段文件
     */
    fun freezeSnapshot(eventId: Long): List<String> {
        // 与 EventRepository 路径一致:filesDir/events/event_<id>/
        val eventDir = File(File(context.filesDir, "events"), "event_${eventId}").apply { mkdirs() }
        val queued = synchronized(this) { queue.toList() }
        if (queued.isEmpty()) {
            Log.w(TAG, "freezeSnapshot:队列为空,eventId=$eventId")
            synchronized(this) { queue.clear() }
            return emptyList()
        }
        // 先过滤无效段:空文件/太小(<100KB 的段帧数太少,合并易出错)。
        val valid = queued.filter { File(it).exists() && File(it).length() > 100_000 }
        if (valid.isEmpty()) {
            Log.w(TAG, "freezeSnapshot:全部段无效,eventId=$eventId")
            synchronized(this) { queue.clear() }
            return emptyList()
        }
        // ★ 用段数裁剪替代 duration 累加裁剪(不用依赖 KEY_DURATION,避免读到异常时长)。
        // 注意:这里必须用「不含 +1 缓冲」的精确段数。
        // maxSegments() 里的 +1 是录制期队列轮转用的缓冲(防止换段瞬间新段的≈0 内容
        // 把最旧整段挤掉),若冻结时也保留这 1 段,产物会多出约 15s:
        // 5min 窗口 → 21 段 × 15s ≈ 315s+ → 实测 5m21s(事件 69 的根因)。
        // 冻结产物应只有窗口时长,故精确段数 = ceil(窗口秒 / 15),不加缓冲。
        val windowSec = if (windowMinutes == 0) 30 else windowMinutes * 60
        val exactSegs = (windowSec + SEGMENT_SEC - 1) / SEGMENT_SEC
        val keepCount = minOf(valid.size, exactSegs)
        val cut = valid.size - keepCount
        val snap = if (cut > 0) valid.takeLast(keepCount) else valid
        Log.i(TAG, "freezeSnapshot eventId=$eventId windowMin=$windowMinutes " +
            "valid=${valid.size} exactSegs=$exactSegs keep=$keepCount 裁掉=$cut")

        // 被裁掉的更旧段:删除文件,避免残留 segments 目录
        for (i in 0 until cut) {
            runCatching { File(valid[i]).delete() }
        }
        // 队列中已被过滤掉的无效段也删文件
        queued.filterNot { it in valid }.forEach { runCatching { File(it).delete() } }

        val merged = File(eventDir, "event.mp4")
        // 用 Mp4Merger(MediaExtractor + MediaMuxer)拼接:与段文件同源的 Android 原生 muxer,
        // 产出的 MP4 与单段文件编码参数/盒结构一致,鸿蒙及各厂商硬件解码器均能正常解码。
        // 曾用 FFmpeg concat -c copy:虽快,但其 mp4 muxer 写出的 avcC/SPS 盒结构与 Android
        // MediaMuxer 不同,部分硬件解码器(如鸿蒙 QC2V4l2Codec)拒绝其像素格式,表现为
        // 回放画面冻结、进度条照跑、音频照播(段文件本身可播,故问题出在 FFmpeg 重封后)。
        // Mp4Merger 经 MediaExtractor 读出再 MediaMuxer 写入,零重编码,产出等同段文件,可播。
        val ok = runCatching {
            com.replay.recoder.editor.Mp4Merger.merge(snap, merged.absolutePath)
        }.onFailure { Log.e(TAG, "Mp4Merger 合并失败,降级为分段保存", it) }.getOrDefault(false)

        val result = if (ok && merged.exists() && merged.length() > 0) {
            // 合并成功:删除环形缓冲里的原始分段(已并入 event.mp4)
            snap.forEach { runCatching { File(it).delete() } }
            listOf(merged.absolutePath)
        } else {
            // 降级:逐段移动到事件目录(renameTo 毫秒级,失败再拷贝)
            runCatching { merged.delete() }
            val moved = mutableListOf<String>()
            for (src in snap) {
                val srcFile = File(src)
                val dst = File(eventDir, srcFile.name)
                val renamed = runCatching { srcFile.renameTo(dst) }.getOrDefault(false)
                if (renamed) {
                    moved.add(dst.absolutePath)
                } else {
                    runCatching {
                        srcFile.inputStream().use { input ->
                            dst.outputStream().use { input.copyTo(it) }
                        }
                        moved.add(dst.absolutePath)
                    }.onFailure { Log.w(TAG, "冻结段失败: $src", it) }
                }
            }
            moved
        }

        // 原始分段已并入/移走,清空队列防止 clear() 重复处理
        synchronized(this) { queue.clear() }
        return result
    }

    /** 录制结束时清空队列(段文件已拷到事件目录) */
    fun clear() {
        synchronized(this) {
            queue.forEach { runCatching { File(it).delete() } }
            queue.clear()
        }
    }

    /** 段文件时长(微秒):取各轨 duration 的最大值。
     *  小文件(<1KB)或读不出时长 → 返回0,让裁剪逻辑跳过它(不虚增15s
     *  导致裁剪算错、有效段被误裁)。仅停录后台线程调用。 */
    private fun segmentDurationUs(path: String): Long = runCatching {
        val f = File(path)
        if (!f.exists() || f.length() < 1024) return 0L
        val ex = android.media.MediaExtractor()
        try {
            ex.setDataSource(path)
            var dur = 0L
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if (fmt.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                    val d = fmt.getLong(android.media.MediaFormat.KEY_DURATION)
                    if (d > dur) dur = d
                }
            }
            dur
        } finally {
            runCatching { ex.release() }
        }
    }.getOrDefault(0L)

    companion object {
        private const val TAG = "SegmentManager"
        const val SEG_DIR = "segments"
        /** 每段时长(秒)。15s 是精度与段数平衡点。 */
        const val SEGMENT_SEC = 15
        /** 孤儿段最长保留时长:超过则视为上次异常退出的遗留产物,开录时清理。 */
        private const val ORPHAN_MAX_AGE_MS = 24 * 60 * 60 * 1000L
    }
}
