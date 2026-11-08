package com.replay.recoder.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.replay.recoder.R
import com.replay.recoder.event.EventType
import com.replay.recoder.event.ReplayEvent
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 相册列表适配器。支持:
 * - 单击进回放、长按弹出管理菜单(编辑备注/删除)
 * - 多选模式:点击切换选中、选中态显示遮罩+勾选圈、未选态半透明
 * - [submit] 接收过滤后的列表,DiffUtil 增量刷新
 * - 缩略图 LruCache 缓存,线程池串行生成
 */
class EventListAdapter(
    private val onClick: (ReplayEvent) -> Unit,
    private val onLongPress: (ReplayEvent) -> Unit,
    /** 多选模式:点击 item 时回调切换选中态;非多选模式回调与 [onClick] 一致。 */
    private val onSelectToggle: (ReplayEvent) -> Unit = onClick,
    /** 长按进入多选模式(仅在非多选模式下触发一次)。 */
    private val onEnterSelection: (ReplayEvent) -> Unit = onLongPress,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<ReplayEvent>()
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前展示的事件列表(快照),供外部(如回放页切换)读取。 */
    fun currentList(): List<ReplayEvent> = items.toList()

    /** 头部"生成中"占位卡数量(不占用 items,渲染在列表最前)。 */
    private var pendingCount = 0

    /** 设置占位卡数量(数量变化时整体刷新;与 submit 的 DiffUtil 互不影响)。 */
    fun setPendingCount(n: Int) {
        val v = n.coerceAtLeast(0)
        if (v == pendingCount) return
        pendingCount = v
        notifyDataSetChanged()
    }

    /**
     * 缩略图内存缓存:key = 事件 id,value = 已压缩到 200px 宽的 bitmap。
     *
     * 原图(1080×2400)约 10MB/张,直接缓存 2 张就撑爆 12MB 阈值,频繁驱逐 + native 内存泄漏。
     * 现在压缩到 200px 宽(约 100KB/张),可缓存上百张。
     * onRemove 回收被驱逐的 bitmap 的 native 内存,防泄漏。
     */
    // sizeOf 返回的是 KB,所以 maxSize 的单位也必须是 KB:12MB = 12*1024 KB。
    // 旧写法 (12*1024*1024)/8 被当作 KB 使用,实际上限约 1.5GB,等于没有上限。
    private val thumbCache = object : LruCache<Long, Bitmap>(12 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap): Int = value.byteCount / 1024
        override fun entryRemoved(
            evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?
        ) {
            // 只在真被驱逐(非重 put)时 recycle,避免误回收正在用的 bitmap
            if (oldValue != newValue && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    /** 事件时长文本缓存:key = 首段路径#段数(事件被删/重建后 key 变化自然失效)。 */
    private val durationCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * 缩略图磁盘缓存目录(懒加载,所有实例共享)。
     * 存为 event_<id>.jpg,首次生成后持久化,进程重启也能秒加载。
     */
    companion object {
        @Volatile private var thumbDir: File? = null
        private val lockDir = Any()
        private const val THUMB_QUALITY = 85
        private const val PAYLOAD_SELECTION = "sel"
        private const val TYPE_EVENT = 0
        private const val TYPE_PENDING = 1

        @JvmStatic
        fun getThumbDir(ctx: Context): File = synchronized(lockDir) {
            thumbDir?.let { return it }
            val dir = File(ctx.filesDir, "thumbs").apply { mkdirs() }
            thumbDir = dir
            dir
        }

        /** 删除指定事件的磁盘缩略图(事件被删时调用) */
        @JvmStatic
        fun deleteThumb(ctx: Context, eventId: Long) {
            runCatching { File(getThumbDir(ctx), "event_${eventId}.jpg").delete() }
        }
    }

    /** 缩略图目标宽度(px),压缩到此宽度,等比缩放 */
    private val thumbTargetWidth = 200

    private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    /** 多选模式开关。 */
    private var selectionMode: Boolean = false
    /** 已选事件 id 集合。 */
    private val selectedIds: MutableSet<Long> = mutableSetOf()

    /** 当前 RecyclerView 的列数,缩略图高度按此算。 */
    var rvLayoutSpanCount: Int? = null

    fun submit(list: List<ReplayEvent>) {
        // 占位卡(pendingCount)不计入 DiffUtil 的 old/new 列表,但 getItemCount() 含它,
        // diff 派发的索引会整体偏移 pendingCount → RecyclerView 抛 Inconsistency detected
        // 或占位卡/事件卡错位。有占位卡时退化为全量刷新,保证索引一致。
        if (pendingCount > 0) {
            items.clear()
            items.addAll(list)
            selectedIds.retainAll(list.map { it.id }.toSet())
            notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = items[o].id == list[n].id
            override fun areContentsTheSame(o: Int, n: Int) = items[o] == list[n]
        })
        items.clear()
        items.addAll(list)
        // 列表刷新后,清掉已选但已不在列表里的 id(避免幽灵选中)
        selectedIds.retainAll(list.map { it.id }.toSet())
        diff.dispatchUpdatesTo(this)
    }

    /** 进入/退出多选模式;退出时清空选中。 */
    fun setSelectionMode(on: Boolean) {
        if (selectionMode == on) return
        selectionMode = on
        if (!on) selectedIds.clear()
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
    }

    fun isSelectionMode() = selectionMode

    fun isSelected(id: Long) = id in selectedIds

    /** 切换某事件选中态,返回切换后的选中数量。 */
    fun toggleSelection(id: Long): Int {
        if (!selectionMode) return selectedIds.size
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) notifyItemChanged(idx, PAYLOAD_SELECTION)
        return selectedIds.size
    }

    /** 全选当前可见列表(供"全选"按钮)。返回切换后选中数量。 */
    fun selectAllVisible(): Int {
        if (!selectionMode) return 0
        items.forEach { selectedIds.add(it.id) }
        notifyItemRangeChanged(0, itemCount, PAYLOAD_SELECTION)
        return selectedIds.size
    }

    fun selectedCount(): Int = selectedIds.size

    /** 当前列表中已选的 id 快照。 */
    fun snapshotSelectedIds(): Set<Long> = selectedIds.toSet()

    override fun getItemViewType(position: Int): Int =
        if (position < pendingCount) TYPE_PENDING else TYPE_EVENT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_PENDING) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_event_generating, parent, false)
            PendingVH(v)
        } else {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_event, parent, false)
            VH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is PendingVH) holder.bind() else (holder as VH).bind(items[position - pendingCount])
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            if (holder is VH) holder.applySelectionState(items[position - pendingCount])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = pendingCount + items.size

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val ivThumb: ImageView = v.findViewById(R.id.ivThumb)
        private val ivCheck: ImageView = v.findViewById(R.id.ivCheck)
        private val selectionOverlay: View = v.findViewById(R.id.selectionOverlay)
        private val tvTime: TextView = v.findViewById(R.id.tvTime)
        private val tvSegments: TextView = v.findViewById(R.id.tvSegments)
        private val tvRemark: TextView = v.findViewById(R.id.tvRemark)

        fun bind(ev: ReplayEvent) {
            // 等比缩略图:高 = 实际列宽 * 4/3。列宽由屏宽减 RecyclerView padding 后 / 列数得,
            // 列数取自 LayoutManager(自适应后不再固定 3 列)。最小 240px 兜底防极小屏。
            val ctx = itemView.context
            val spanCount = (rvLayoutSpanCount ?: 3)
            val pad = 12 // rv padding 左右共 12dp(布局写死 6dp 边距)
            val colWidth = ((ctx.resources.displayMetrics.widthPixels - pad) / spanCount)
                .coerceAtLeast(1)
            ivThumb.layoutParams.height = (colWidth * 4 / 3).coerceAtLeast(240)
            ivThumb.requestLayout()

            tvTime.text = dateFmt.format(Date(ev.createdAt))
            // 事件名:有备注显示备注,无备注显示"事件#ID"(与最近事件卡片一致)
            tvRemark.text = ev.remark.ifBlank {
                itemView.context.getString(R.string.title_item_menu, ev.id)
            }

            // 用 tag 防止复用错位:加载完成后比对是否还是同一个事件
            val tag = ev.id
            ivThumb.setTag(R.id.ivThumb, tag)
            // 时长标签异步计算(多段事件逐段累加),放到 tag 设置之后才能比对复用
            bindDuration(ev, tag)
            // 三级查找:内存缓存 → 磁盘缓存 → 异步生成
            val cached = thumbCache[tag]
            if (cached != null) {
                ivThumb.setImageBitmap(cached)
            } else {
                // 先读磁盘缓存(同步,通常 < 5ms,200px jpg 约 10KB)
                val thumbFile = File(getThumbDir(ctx), "event_${tag}.jpg")
                if (thumbFile.exists()) {
                    val diskBmp = BitmapFactory.decodeFile(thumbFile.absolutePath)
                    if (diskBmp != null) {
                        thumbCache.put(tag, diskBmp)
                        ivThumb.setImageBitmap(diskBmp)
                    } else {
                        // 磁盘缓存损坏,删除后异步重新生成
                        runCatching { thumbFile.delete() }
                        ivThumb.setImageDrawable(null)
                        executor.execute {
                            val bmp = genThumb(ev)
                            if (bmp != null) {
                                thumbCache.put(tag, bmp)
                                saveThumbToDisk(tag, bmp, ctx)
                            }
                            mainHandler.post {
                                if (ivThumb.getTag(R.id.ivThumb) == tag) {
                                    bmp?.let { ivThumb.setImageBitmap(it) }
                                }
                            }
                        }
                    }
                } else {
                    // 无磁盘缓存,异步生成
                    ivThumb.setImageDrawable(null)
                    executor.execute {
                        val bmp = genThumb(ev)
                        if (bmp != null) {
                            thumbCache.put(tag, bmp)
                            saveThumbToDisk(tag, bmp, ctx)
                        }
                        mainHandler.post {
                            if (ivThumb.getTag(R.id.ivThumb) == tag) {
                                bmp?.let { ivThumb.setImageBitmap(it) }
                            }
                        }
                    }
                }
            }

            applySelectionState(ev)

            itemView.setOnClickListener {
                if (selectionMode) onSelectToggle(ev) else onClick(ev)
            }
            itemView.setOnLongClickListener {
                if (selectionMode) {
                    // 多选模式下长按也切换,方便快速勾选
                    onSelectToggle(ev)
                } else {
                    onEnterSelection(ev)
                }
                true
            }
        }

        /** 底部时长标签:照片=照片;视频=各段时长累加(异步,结果缓存)。
         *  多段(合并降级)事件只读首段会把 5min 显示成 00:15,必须逐段累加。 */
        private fun bindDuration(ev: ReplayEvent, tag: Long) {
            if (ev.type == EventType.PHOTO) {
                tvSegments.text = "照片"
                return
            }
            if (ev.segments.isEmpty()) {
                tvSegments.text = "--:--"
                return
            }
            val key = ev.segments[0] + "#" + ev.segments.size
            durationCache[key]?.let {
                tvSegments.text = it
                return
            }
            tvSegments.text = "--:--"
            executor.execute {
                var totalMs = 0L
                for (p in ev.segments) {
                    totalMs += runCatching {
                        MediaMetadataRetriever().use { r ->
                            r.setDataSource(p)
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        }
                    }.getOrDefault(0L)
                }
                val text = if (totalMs > 0L) {
                    val sec = totalMs / 1000
                    String.format(Locale.getDefault(), "%02d:%02d", sec / 60, sec % 60)
                } else "--:--"
                durationCache[key] = text
                mainHandler.post {
                    if (ivThumb.getTag(R.id.ivThumb) == tag) tvSegments.text = text
                }
            }
        }

        /** 仅刷新选中态(由 PAYLOAD_SELECTION 触发),不重新生成缩略图。 */
        fun applySelectionState(ev: ReplayEvent) {
            val selected = ev.id in selectedIds
            if (selectionMode) {
                // 多选模式:选中=全亮+勾选圈;未选=半透明(仍可见,提示可点)
                ivCheck.visibility = if (selected) View.VISIBLE else View.GONE
                ivThumb.alpha = if (selected) 1.0f else 0.45f
                selectionOverlay.visibility = View.GONE
            } else {
                ivCheck.visibility = View.GONE
                ivThumb.alpha = 1.0f
                selectionOverlay.visibility = View.GONE
            }
        }

        private fun genThumb(ev: ReplayEvent): Bitmap? {
            val firstSeg = ev.segments.firstOrNull() ?: return null
            // 拍照事件:产物是 jpg,MediaMetadataRetriever 取不到帧 → 用 BitmapFactory 解码
            if (ev.type == EventType.PHOTO) return decodePhotoThumb(firstSeg)
            val retr = MediaMetadataRetriever()
            return try {
                retr.setDataSource(firstSeg)
                // OPTION_CLOSEST_SYNC 在 pts 退化/首帧无参考帧时取不到图,改 OPTION_CLOSEST
                // 并略过 0(取 100ms 处的帧,避开首帧黑帧/SPS-PPS 头部),取不到再退回 0
                val raw = retr.getFrameAtTime(100_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return null
                // 压缩到 200px 宽,等比缩放,原图立即 recycle 防 native 内存泄漏
                val w = raw.width
                val h = raw.height
                if (w <= thumbTargetWidth) return raw
                val scale = thumbTargetWidth.toFloat() / w
                val scaled = Bitmap.createScaledBitmap(raw, thumbTargetWidth, (h * scale).toInt().coerceAtLeast(1), true)
                if (scaled != raw && !raw.isRecycled) raw.recycle()
                scaled
            } catch (t: Throwable) {
                null
            } finally {
                runCatching { retr.release() }
            }
        }

        /** 拍照缩略图:用 inSampleSize 降采样解码 jpg,避免直接 decode 全尺寸图 OOM。 */
        private fun decodePhotoThumb(path: String): Bitmap? = runCatching {
            // 先只读边界算采样
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sample = 1
            while (w / sample > thumbTargetWidth * 2 && h / sample > thumbTargetWidth * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw = BitmapFactory.decodeFile(path, opts) ?: return null
            if (raw.width <= thumbTargetWidth) return raw
            val scale = thumbTargetWidth.toFloat() / raw.width
            val scaled = Bitmap.createScaledBitmap(
                raw, thumbTargetWidth, (raw.height * scale).toInt().coerceAtLeast(1), true
            )
            if (scaled != raw && !raw.isRecycled) raw.recycle()
            scaled
        }.getOrNull()

        /** 把生成的缩略图持久化到磁盘(jpg,质量85),下次打开相册秒加载 */
        private fun saveThumbToDisk(eventId: Long, bmp: Bitmap, ctx: Context) {
            runCatching {
                val file = File(getThumbDir(ctx), "event_${eventId}.jpg")
                FileOutputStream(file).use { fos ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, fos)
                }
            }
        }
    }

    /** "生成中"占位卡 ViewHolder:仅显示转圈提示,高度对齐事件缩略图保持网格整齐。 */
    inner class PendingVH(v: View) : RecyclerView.ViewHolder(v) {
        private val thumbArea: View = v.findViewById(R.id.thumbArea)

        fun bind() {
            val ctx = itemView.context
            val spanCount = (rvLayoutSpanCount ?: 3)
            val pad = 12 // 与事件卡同公式:rv padding 左右共 12dp
            val colWidth = ((ctx.resources.displayMetrics.widthPixels - pad) / spanCount)
                .coerceAtLeast(1)
            thumbArea.layoutParams.height = (colWidth * 4 / 3).coerceAtLeast(240)
            thumbArea.requestLayout()
        }
    }
}
