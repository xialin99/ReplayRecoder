package com.replay.recoder.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.replay.recoder.R
import com.replay.recoder.ReplayApp
import com.replay.recoder.event.EventType
import com.replay.recoder.event.ReplayEvent
import com.replay.recoder.editor.Mp4Merger
import com.replay.recoder.editor.VideoEditor
import com.replay.recoder.record.RecordState
import com.replay.recoder.record.RecordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 相册(功能页):已冻结的回放事件以网格缩略图展示,带增删查改 + 多选批量管理。
 *
 * - 增:由录制停止后自动冻结生成,本页不手动增(见 [EventRepository.createEvent])
 * - 删:长按 → 确认对话框删除单条;顶栏清空按钮批量删除全部;多选模式批量删除
 * - 查:顶栏搜索框按备注模糊过滤
 * - 改:长按 → 编辑备注对话框 → 写回 [EventRepository.setRemark]
 * - 多选:点"多选"按钮进入;长按单条也可进入并预选该条。多选态下:
 *   - 点 item 切换选中
 *   - 顶栏切换为 取消/已选N项/全选/删除/分享
 *   - 删除:确认后批量删除所有选中
 *   - 分享:把每条事件的段合并成 mp4,通过 ACTION_SEND_MULTIPLE 多文件分享(钉钉定向)
 */
class EventListActivity : AppCompatActivity() {

    private lateinit var adapter: EventListAdapter
    private lateinit var rv: RecyclerView
    private lateinit var emptyView: View
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageView
    /** 类型筛选(DS 阶段5/6):默认全部,影响 applyFilter。 */
    private var typeFilter: EventType? = null

    /** 多选模式 UI:普通栏隐藏,选择栏显示。 */
    private lateinit var normalTopBar: View
    private lateinit var selectionTopBar: View
    private lateinit var searchBar: View
    private lateinit var tvSelectedCount: TextView
    private lateinit var btnMultiSelect: TextView
    private lateinit var btnCancelSelection: TextView
    private lateinit var btnSelectAll: TextView
    private lateinit var btnBatchDelete: TextView
    private lateinit var btnBatchShare: TextView
    private lateinit var btnBatchConcat: TextView
    private lateinit var tvEventListTitle: TextView

    /** 全量事件缓存,搜索时本地过滤避免反复读盘。 */
    private var allEvents: List<ReplayEvent> = emptyList()
    private var keyword: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_list)

        normalTopBar = findViewById(R.id.normalTopBar)
        tvEventListTitle = findViewById(R.id.tvEventListTitle)
        selectionTopBar = findViewById(R.id.selectionTopBar)
        searchBar = findViewById(R.id.searchBar)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        btnMultiSelect = findViewById(R.id.btnMultiSelect)
        btnCancelSelection = findViewById(R.id.btnCancelSelection)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnBatchDelete = findViewById(R.id.btnBatchDelete)
        btnBatchShare = findViewById(R.id.btnBatchShare)
        btnBatchConcat = findViewById(R.id.btnBatchConcat)

        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnClearAll).setOnClickListener { confirmClearAll() }

        // 多选入口:点击进入多选模式(默认不预选)
        btnMultiSelect.setOnClickListener {
            if (allEvents.isEmpty()) {
                Toast.makeText(this, R.string.empty_events, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            enterSelectionMode(preselectId = null)
        }
        btnCancelSelection.setOnClickListener { exitSelectionMode() }
        btnSelectAll.setOnClickListener {
            adapter.selectAllVisible()
            updateSelectionUi()
        }
        btnBatchDelete.setOnClickListener { confirmBatchDelete() }
        btnBatchShare.setOnClickListener { batchShare() }
        btnBatchConcat.setOnClickListener { confirmBatchConcat() }

        etSearch = findViewById(R.id.etSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)
        btnClearSearch.setOnClickListener {
            etSearch.setText("")
            keyword = ""
            btnClearSearch.visibility = View.GONE
            applyFilter()
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                keyword = s?.toString().orEmpty().trim()
                btnClearSearch.visibility = if (keyword.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // 类型筛选(DS 阶段5/6 新增):按 EventType 过滤,null = 全部
        val chipGroup: com.google.android.material.chip.ChipGroup = findViewById(R.id.chipGroupFilter)
        val chips = mapOf(
            R.id.chipFilterAll to null,
            R.id.chipFilterRecord to EventType.SCREEN_RECORD,
            R.id.chipFilterPhoto to EventType.PHOTO,
            R.id.chipFilterVideo to EventType.CAMERA_VIDEO,
        )
        chips.forEach { (id, type) ->
            chipGroup.findViewById<com.google.android.material.chip.Chip>(id)
                .setOnClickListener {
                    typeFilter = type
                    applyFilter()
                }
        }

        adapter = EventListAdapter(
            onClick = { ev -> openPlayback(ev) },
            onLongPress = { ev -> showItemMenu(ev) },
            onSelectToggle = { ev ->
                adapter.toggleSelection(ev.id)
                updateSelectionUi()
            },
            onEnterSelection = { ev ->
                // 长按进入多选并预选该条(便于"我要从这条开始勾选")
                enterSelectionMode(preselectId = ev.id)
            }
        )
        rv = findViewById(R.id.rvEvents)
        // 自适应列数:每列目标最小 110dp,小屏 2 列、手机 3 列、平板/折叠内屏 5+ 列。
        val span = computeGridColumns()
        rv.layoutManager = GridLayoutManager(this, span)
        rv.setHasFixedSize(false)
        adapter.rvLayoutSpanCount = span
        rv.adapter = adapter

        emptyView = findViewById(R.id.emptyView)
        tvEmpty = findViewById(R.id.tvEmpty)

        loadEvents()
    }

    override fun onResume() {
        super.onResume()
        loadEvents()
    }

    /** 事件落盘完成广播:前台时收到即刷新(占位卡替换成真实事件) */
    private val eventSavedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loadEvents()
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RecordService.ACTION_EVENT_SAVED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventSavedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(eventSavedReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(eventSavedReceiver)
    }

    /** 返回键:多选模式下优先退出多选,否则 finish。 */
    override fun onBackPressed() {
        if (adapter.isSelectionMode()) {
            exitSelectionMode()
        } else {
            super.onBackPressed()
        }
    }

    private fun loadEvents() {
        lifecycleScope.launch {
            allEvents = withContext(Dispatchers.IO) { eventRepo().allEvents() }
            tvEventListTitle.text = "回放事件(${allEvents.size})"
            applyFilter()
        }
    }

    /** 按备注模糊过滤 + 按类型筛选,空关键字/全部类型显示全部。 */
    private fun applyFilter() {
        val byType = if (typeFilter == null) allEvents
        else allEvents.filter { it.type == typeFilter }
        val filtered = if (keyword.isEmpty()) byType
        else byType.filter { it.remark.contains(keyword, ignoreCase = true) }
        // 停录后事件还在后台合并:列表头部渲染"生成中"占位卡(数量来自 RecordState 全局计数)
        adapter.setPendingCount(com.replay.recoder.record.RecordState.generatingEventCount)
        adapter.submit(filtered)
        // 空状态文案:有事件但被搜索/类型过滤完 vs 完全无事件。
        // 有占位卡在列(正在生成)时不算空,不显示空态。
        if (allEvents.isEmpty() && RecordState.generatingEventCount == 0) {
            emptyView.visibility = View.VISIBLE
            tvEmpty.text = getString(R.string.empty_events)
        } else if (filtered.isEmpty() && RecordState.generatingEventCount == 0) {
            emptyView.visibility = View.VISIBLE
            tvEmpty.text = getString(R.string.no_search_result)
        } else {
            emptyView.visibility = View.GONE
        }
    }

    private fun openPlayback(ev: ReplayEvent) {
        if (ev.segments.isEmpty()) {
            Toast.makeText(this, R.string.empty_events, Toast.LENGTH_SHORT).show()
            return
        }
        // 用当前过滤后的可见事件列表作为可切换集合,传入当前事件索引
        val list = adapter.currentList()
        val idx = list.indexOfFirst { it.id == ev.id }
        if (idx >= 0 && list.isNotEmpty()) {
            PlaybackActivity.launchWithList(this, list, idx)
        } else {
            PlaybackActivity.launch(this, ev.segments, ev.id, ev.type)
        }
    }

    /** 长按弹出管理菜单:编辑备注 / 删除。 */
    private fun showItemMenu(ev: ReplayEvent) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.title_item_menu, ev.id))
            .setItems(arrayOf(
                getString(R.string.action_edit_remark),
                getString(R.string.action_delete)
            )) { _, which ->
                when (which) {
                    0 -> showEditRemarkDialog(ev)
                    1 -> confirmDelete(ev)
                }
            }
            .show()
    }

    /** 改:编辑备注,写回事件库。 */
    private fun showEditRemarkDialog(ev: ReplayEvent) {
        val et = EditText(this).apply {
            setText(ev.remark)
            setSelection(text.length)
            hint = getString(R.string.hint_remark)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_edit_remark)
            .setView(et)
            .setPositiveButton(R.string.btn_save_remark) { _, _ ->
                val text = et.text?.toString().orEmpty()
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { eventRepo().setRemark(ev.id, text) }
                    Toast.makeText(this@EventListActivity,
                        R.string.toast_remark_saved, Toast.LENGTH_SHORT).show()
                    loadEvents()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 删:确认后删除单条事件。 */
    private fun confirmDelete(ev: ReplayEvent) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.confirm_delete, ev.id))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { eventRepo().delete(ev.id) }
                    Toast.makeText(this@EventListActivity,
                        R.string.toast_deleted, Toast.LENGTH_SHORT).show()
                    loadEvents()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 删:清空全部事件(批量)。 */
    private fun confirmClearAll() {
        if (allEvents.isEmpty()) {
            Toast.makeText(this, R.string.empty_events, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_clear_all)
            .setMessage(getString(R.string.confirm_clear_all, allEvents.size))
            .setPositiveButton(R.string.action_clear_all) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { eventRepo().deleteAll() }
                    Toast.makeText(this@EventListActivity,
                        R.string.toast_cleared, Toast.LENGTH_SHORT).show()
                    loadEvents()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------- 多选模式 ----------------

    /** 进入多选模式;[preselectId] 非空时预选该条(长按进入用)。 */
    private fun enterSelectionMode(preselectId: Long?) {
        normalTopBar.visibility = View.GONE
        searchBar.visibility = View.GONE
        selectionTopBar.visibility = View.VISIBLE
        adapter.setSelectionMode(true)
        if (preselectId != null) {
            adapter.toggleSelection(preselectId)
        }
        updateSelectionUi()
    }

    /** 退出多选模式,恢复普通栏。 */
    private fun exitSelectionMode() {
        adapter.setSelectionMode(false)
        normalTopBar.visibility = View.VISIBLE
        searchBar.visibility = View.VISIBLE
        selectionTopBar.visibility = View.GONE
    }

    /** 同步顶栏已选数量文案。 */
    private fun updateSelectionUi() {
        val n = adapter.selectedCount()
        tvSelectedCount.text = getString(R.string.selected_count, n)
    }

    /** 多选删除:确认后批量删除所有已选事件。 */
    private fun confirmBatchDelete() {
        val ids = adapter.snapshotSelectedIds()
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_selection, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_batch_delete)
            .setMessage(getString(R.string.confirm_batch_delete, ids.size))
            .setPositiveButton(R.string.action_batch_delete) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        ids.forEach { eventRepo().delete(it) }
                    }
                    Toast.makeText(this@EventListActivity,
                        getString(R.string.toast_batch_deleted, ids.size),
                        Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                    loadEvents()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * 多选分享:每条事件合并成独立 mp4,通过 ACTION_SEND_MULTIPLE 一次性分享。
     * 钉钉支持 ACTION_SEND_MULTIPLE 多文件分享。
     */
    private fun batchShare() {
        val ids = adapter.snapshotSelectedIds()
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_selection, Toast.LENGTH_SHORT).show()
            return
        }
        val events = allEvents.filter { it.id in ids && it.segments.isNotEmpty() }
        if (events.isEmpty()) {
            Toast.makeText(this, R.string.toast_share_failed, Toast.LENGTH_SHORT).show()
            return
        }

        // 置灰按钮,防止重复点击
        setBatchButtonsEnabled(false)
        Toast.makeText(this,
            getString(R.string.toast_batch_share_preparing, events.size),
            Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            // 只清理 1 小时前的旧分享缓存,避免误删正在途中的编辑/合并临时文件
            val staleBefore = System.currentTimeMillis() - 60 * 60 * 1000L
            cacheDir.listFiles { f -> f.name.endsWith(".mp4") && f.lastModified() < staleBefore }
                ?.forEach { it.delete() }

            val uris = ArrayList<android.net.Uri>()
            var failCount = 0
            // 用过的文件名计数,避免重名覆盖(同备注事件)
            val usedNames = mutableMapOf<String, Int>()
            events.forEach { ev ->
                // 文件名用备注;为空回退到 "事件<id>"
                val baseName = sanitizeShareName(
                    ev.remark.ifBlank { "事件${ev.id}" }
                )
                val dup = usedNames.getOrPut(baseName) { 0 } + 1
                usedNames[baseName] = dup
                val fileName = if (dup == 1) baseName else "${baseName}_${dup}"
                val tmp = File(cacheDir, "$fileName.mp4")
                val ok = withContext(Dispatchers.IO) {
                    Mp4Merger.merge(ev.segments, tmp.absolutePath)
                }
                if (ok && tmp.exists()) {
                    uris.add(FileProvider.getUriForFile(
                        this@EventListActivity, "${packageName}.fileprovider", tmp
                    ))
                } else {
                    failCount++
                }
            }
            setBatchButtonsEnabled(true)

            if (uris.isEmpty()) {
                Toast.makeText(this@EventListActivity,
                    R.string.toast_share_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (failCount > 0) {
                Toast.makeText(this@EventListActivity,
                    "部分事件合并失败($failCount 条),仅分享成功的事件",
                    Toast.LENGTH_SHORT).show()
            }

            val sendIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "video/mp4"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // 检测钉钉:安装则定向,未安装则用系统选择器
            val dingTalkInstalled = try {
                packageManager.getPackageInfo("com.alibaba.android.rimet", 0) != null
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            if (dingTalkInstalled) {
                sendIntent.setPackage("com.alibaba.android.rimet")
            } else {
                Toast.makeText(this@EventListActivity,
                    R.string.toast_dingtalk_not_installed, Toast.LENGTH_SHORT).show()
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.action_batch_share)))
        }
    }

    /**
     * 多选拼接:把选中事件按**录制时间从早到晚**排序,用 [Mp4Merger]
     * 合成一条完整视频,作为新事件存入相册。后台导出,带进度提示;源事件保留不动。
     */
    private fun confirmBatchConcat() {
        val ids = adapter.snapshotSelectedIds()
        if (ids.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_selection, Toast.LENGTH_SHORT).show()
            return
        }
        // 按录制时间升序(早 → 晚),拼接顺序才符合时间线
        val events = allEvents
            .filter { it.id in ids && it.segments.isNotEmpty() }
            .sortedBy { it.createdAt }
        if (events.isEmpty()) {
            Toast.makeText(this, R.string.toast_share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_batch_concat)
            .setMessage(getString(R.string.confirm_batch_concat, events.size))
            .setPositiveButton(R.string.action_batch_concat) { _, _ -> runBatchConcat(events) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runBatchConcat(events: List<ReplayEvent>) {
        setBatchButtonsEnabled(false)
        // 统一进度弹窗(简化版 · 单进度条)
        val dialog = ExportProgressDialog(this)
            .show(getString(R.string.toast_batch_concat_running, events.size))
            .setOnCancel { VideoEditor.cancelCurrent() }

        // 每个事件取其成片(新数据为单个 event.mp4;旧多段事件摊平按序参与)
        val paths = events.flatMap { it.segments }

        lifecycleScope.launch {
            val outFile = File(cacheDir, "concat_${System.currentTimeMillis()}.mp4")
            val ok = withContext(Dispatchers.IO) {
                Mp4Merger.merge(paths, outFile.absolutePath)
            }

            var newEventId = -1L
            if (ok && outFile.exists() && outFile.length() > 0) {
                dialog.setProgress(100)
                newEventId = withContext(Dispatchers.IO) {
                    val repo = eventRepo()
                    val id = repo.createEvent(segments = emptyList(), windowMinutes = 0)
                    // 事件目录约定与 EventRepository 一致:filesDir/events/event_<id>/
                    // (曾写成 File(filesDir, "event_$id") 导致拼接产物落在错误目录、
                    //  删除事件时清不到文件,PRD §10.2 已记录,此为修复)
                    val eventDir = File(File(filesDir, "events"), "event_$id").apply { mkdirs() }
                    val dst = File(eventDir, "event.mp4")
                    val moved = runCatching { outFile.renameTo(dst) }.getOrDefault(false)
                    if (!moved) {
                        runCatching { outFile.inputStream().use { it.copyTo(dst.outputStream()) } }
                    }
                    runCatching { outFile.delete() }
                    if (dst.exists()) {
                        repo.setSegments(id, listOf(dst.absolutePath))
                        repo.setRemark(id, "拼接 ${events.size} 段")
                        id
                    } else -1L
                }
            }

            if (newEventId > 0) {
                dialog.dismiss(true, getString(R.string.toast_batch_concat_done))
                exitSelectionMode()
                loadEvents()
            } else {
                dialog.dismiss(false, getString(R.string.edit_failed))
            }
            setBatchButtonsEnabled(true)
        }
    }

    private fun setBatchButtonsEnabled(enabled: Boolean) {
        btnBatchDelete.isEnabled = enabled
        btnBatchShare.isEnabled = enabled
        btnBatchConcat.isEnabled = enabled
        btnSelectAll.isEnabled = enabled
        btnCancelSelection.isEnabled = enabled
    }

    /**
     * 把任意文字转成安全的文件名(不含扩展名):
     * - 去掉路径分隔符等非法字符
     * - 限制长度到 40 字符以内
     * - 为空时回退到 fallback
     */
    private fun sanitizeShareName(input: String, fallback: String = "event"): String {
        val cleaned = input.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_')
        return if (cleaned.isEmpty()) fallback
        else cleaned.take(40)
    }

    private fun eventRepo() = (application as ReplayApp).eventRepo

    /**
     * 按屏宽算网格列数:每列目标 110dp,最少 2 列。
     * 适配小屏手机(360dp → 3 列)、平板/折叠内屏(800dp+ → 5+ 列)。
     */
    private fun computeGridColumns(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 110).coerceIn(2, 8)
    }

    companion object {
        fun launch(ctx: Context) {
            ctx.startActivity(Intent(ctx, EventListActivity::class.java))
        }
    }
}
