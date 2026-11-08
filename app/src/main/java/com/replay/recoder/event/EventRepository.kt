package com.replay.recoder.event

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 事件类型区分(DS 阶段 5 新增)。旧 meta.json 没有 type 字段时按 [SCREEN_RECORD] 兼容。
 * - [SCREEN_RECORD]:录屏停录后冻结的事件,产物 event.mp4
 * - [PHOTO]:CameraActivity 拍照,产物 photo.jpg
 * - [CAMERA_VIDEO]:CameraActivity 录像,产物 video.mp4
 */
enum class EventType(val storage: String) {
    SCREEN_RECORD("screen_record"),
    PHOTO("photo"),
    CAMERA_VIDEO("camera_video");

    companion object {
        /** 旧 meta.json 无 type 字段时默认按录屏处理。 */
        fun fromStorage(s: String?): EventType =
            values().firstOrNull { it.storage == s } ?: SCREEN_RECORD
    }
}

/**
 * 回放事件实体(纯文件版,无 Room 依赖)。
 * - [id]:事件目录名后缀,自增
 * - [createdAt]:创建时间戳
 * - [windowMinutes]:该事件冻结时的回放窗口档位(拍照恒为 0)
 * - [segments]:段文件绝对路径列表(录屏=event.mp4;拍照=photo.jpg;录像=video.mp4)
 * - [remark]:测试人填的备注,如"清扫建图卡顿#1"
 * - [type]:事件类型,默认 [EventType.SCREEN_RECORD] 兼容旧数据
 *
 * 一个事件 = filesDir/events/event_<id>/meta.json + 段文件。
 */
data class ReplayEvent(
    val id: Long,
    val createdAt: Long,
    val windowMinutes: Int,
    val segments: List<String>,
    val remark: String = "",
    val type: EventType = EventType.SCREEN_RECORD,
)

/**
 * 事件库 Repository(纯文件实现,接口与原 Room 版一致)。
 *
 * 为什么不用 Room:初版去掉 KSP/Room 注解处理器,规避工具链在 Windows 下
 * 的 IOException("文件名、目录名或卷标语法不正确")编译失败问题。
 * 纯 JSON + 文件目录足够支撑初版的事件存档/查询/备注/删除。
 */
class EventRepository(private val context: Context) {

    private val rootDir: File = File(context.filesDir, EVENTS_DIR).apply { mkdirs() }

    /** 自增 id:取现有事件最大 id + 1,无则从 1 起。 */
    private fun nextId(): Long {
        val max = rootDir.listFiles()
            ?.mapNotNull { it.name.removePrefix("event_").toLongOrNull() }
            ?.maxOrNull() ?: 0L
        return max + 1
    }

    /** 预取下一个事件 id(不写任何文件)。
     *  供"先落视频文件、后写 meta"的流程使用:停录时先拿 id 建目录合并,
     *  全部就绪后再写 meta,meta 出现前事件对 UI 不可见,避免读到半成品。 */
    suspend fun peekNextId(): Long = nextId()

    private fun eventDir(id: Long): File = File(rootDir, "event_$id").apply { mkdirs() }

    private fun metaFile(id: Long): File = File(eventDir(id), META_FILE)

    /** 创建事件。[type] 默认录屏,以兼容 RecordService 已有的 createEvent(segments, window) 调用。
     *  [expectedId] 非空时直接用该 id(配合 peekNextId 的"先落文件后写 meta"流程);
     *  为空则自动分配。 */
    suspend fun createEvent(
        segments: List<String>,
        windowMinutes: Int,
        type: EventType = EventType.SCREEN_RECORD,
        expectedId: Long? = null,
        createdAt: Long = System.currentTimeMillis(),
    ): Long {
        val id = expectedId ?: nextId()
        val ev = ReplayEvent(
            id = id,
            createdAt = createdAt,
            windowMinutes = windowMinutes,
            segments = segments,
            type = type,
        )
        writeMeta(ev)
        return id
    }

    suspend fun setSegments(id: Long, segments: List<String>) {
        val ev = get(id) ?: return
        writeMeta(ev.copy(segments = segments))
    }

    suspend fun setRemark(id: Long, remark: String) {
        val ev = get(id) ?: return
        writeMeta(ev.copy(remark = remark))
    }

    suspend fun latestEvent(): ReplayEvent? {
        val all = allEvents()
        return all.firstOrNull()
    }

    /** 全部事件,按创建时间倒序(最新在前)。供相册列表用。 */
    suspend fun allEvents(): List<ReplayEvent> {
        val files = rootDir.listFiles()?.toList().orEmpty()
        if (files.isEmpty()) return emptyList()
        return files
            .mapNotNull { d -> d.listFiles()?.find { it.name == META_FILE } }
            .mapNotNull { readMeta(it) }
            .sortedByDescending { it.createdAt }
    }

    suspend fun get(id: Long): ReplayEvent? {
        val f = metaFile(id)
        if (!f.exists()) return null
        return readMeta(f)
    }

    suspend fun delete(id: Long) {
        runCatching { eventDir(id).deleteRecursively() }
        // 同步删除磁盘缩略图缓存
        runCatching { File(context.filesDir, "thumbs/event_${id}.jpg").delete() }
    }

    /** 删除全部事件(清空相册)。 */
    suspend fun deleteAll() {
        rootDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
        // 清空所有磁盘缩略图
        runCatching { File(context.filesDir, "thumbs").listFiles()?.forEach { it.delete() } }
    }

    // ---------- 序列化 ----------

    private fun writeMeta(ev: ReplayEvent) {
        val arr = JSONArray()
        ev.segments.forEach { arr.put(it) }
        val json = JSONObject().apply {
            put("id", ev.id)
            put("createdAt", ev.createdAt)
            put("windowMinutes", ev.windowMinutes)
            put("segments", arr)
            put("remark", ev.remark)
            put("type", ev.type.storage)
        }
        // 先写 .tmp 再 rename 原子替换:写入过程中进程被杀/断电不会留下半截 meta.json,
        // 否则 readMeta 解析失败 → 事件从列表永久消失(段文件变孤儿)。
        val target = metaFile(ev.id)
        val tmp = File(target.parentFile, "$META_FILE.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(target)) {
            // 个别文件系统不支持覆盖式 rename:退化为"删旧 + 改名",失败则直接写目标兜底
            runCatching { target.delete() }
            if (!tmp.renameTo(target)) {
                target.writeText(json.toString())
                runCatching { tmp.delete() }
            }
        }
    }

    /** 旧 meta.json 没有 type 字段时,[EventType.fromStorage] 返回 SCREEN_RECORD 兼容。
     *  解析失败(写入中断/半截文件)时尝试从事件目录里的段文件重建最小元数据,
     *  避免素材还在、事件却永久消失。 */
    private fun readMeta(f: File): ReplayEvent? {
        val parsed = runCatching {
            val json = JSONObject(f.readText())
            val arr = json.optJSONArray("segments") ?: JSONArray()
            val segs = (0 until arr.length()).map { arr.getString(it) }
            ReplayEvent(
                id = json.optLong("id", 0),
                createdAt = json.optLong("createdAt", 0),
                windowMinutes = json.optInt("windowMinutes", 5),
                segments = segs,
                remark = json.optString("remark", ""),
                type = EventType.fromStorage(json.optString("type", null)),
            )
        }.getOrNull()
        if (parsed != null && parsed.segments.isNotEmpty()) return parsed
        return rebuildMetaFromDir(f.parentFile, parsed)
    }

    /** 从事件目录现有文件重建最小元数据(meta 损坏时的兜底)。 */
    private fun rebuildMetaFromDir(dir: File?, hint: ReplayEvent?): ReplayEvent? {
        if (dir == null || !dir.isDirectory) return null
        val id = hint?.id?.takeIf { it > 0 }
            ?: dir.name.removePrefix("event_").toLongOrNull() ?: return null
        val files = dir.listFiles().orEmpty()
            .filter { it.isFile && it.name != META_FILE && !it.name.endsWith(".tmp") }
        if (files.isEmpty()) return null
        val type = when {
            files.any { it.name.endsWith(".jpg", true) } -> EventType.PHOTO
            files.any { it.name == VIDEO_FILE } -> EventType.CAMERA_VIDEO
            else -> EventType.SCREEN_RECORD
        }
        return ReplayEvent(
            id = id,
            createdAt = hint?.createdAt?.takeIf { it > 0 } ?: dir.lastModified(),
            windowMinutes = hint?.windowMinutes ?: 0,
            segments = files.map { it.absolutePath }.sorted(),
            remark = hint?.remark ?: "",
            type = type,
        )
    }

    companion object {
        private const val EVENTS_DIR = "events"
        private const val META_FILE = "meta.json"

        /** 事件目录下拍照文件的固定名(CameraActivity 拍照产物)。 */
        const val PHOTO_FILE = "photo.jpg"
        /** 事件目录下录像文件的固定名(CameraActivity 录像产物)。 */
        const val VIDEO_FILE = "video.mp4"
    }
}
