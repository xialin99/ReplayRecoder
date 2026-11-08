package com.replay.recoder.record

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 录制状态单例,跨组件(MainActivity/RecordService/FloatingControl/Notification)共享。
 * 后续可改为 Flow,阶段1先用最简单的可观察属性。
 */
object RecordState {

    enum class State { IDLE, RECORDING }

    var state: State = State.IDLE
        private set

    /** 当前回放窗口,分钟 */
    var windowMinutes: Int = 5
        private set

    /** 录制开始时刻(SystemClock.elapsedRealtime):悬浮球据此显示"已录时长 00:00" */
    var startElapsedRealtime: Long = 0L
        private set

    /** 录制开始墙上时钟(System.currentTimeMillis):首页据此显示"录制开始时间 HH:mm" */
    var startWallClock: Long = 0L
        private set

    /** 段队列(按时间顺序,队列头=最旧)。仅在录制时有效。 */
    var segments: List<String> = emptyList()
        private set

    private val lock = Any()

    fun start(windowMin: Int, initialSegments: List<String>) = synchronized(lock) {
        state = State.RECORDING
        windowMinutes = windowMin
        segments = initialSegments
        startElapsedRealtime = SystemClock.elapsedRealtime()
        startWallClock = System.currentTimeMillis()
    }

    fun setWindow(min: Int) = synchronized(lock) {
        windowMinutes = min
    }

    fun appendSegment(path: String) = synchronized(lock) {
        segments = segments + path
    }

    fun pruneSegment(path: String) = synchronized(lock) {
        segments = segments - path
    }

    fun stop(): List<String> = synchronized(lock) {
        val snap = segments.toList()
        state = State.IDLE
        // 停录后段文件会被 SegmentManager.clear() 删除,必须清空路径列表,
        // 否则 MainActivity 点回放时 snapshotSegments() 拿到已删除的旧路径 → FileNotFoundException。
        segments = emptyList()
        snap
    }

    /** 跳到 [PlaybackActivity] 时用,快照当前窗口内所有段路径 */
    fun snapshotSegments(): List<String> = synchronized(lock) {
        segments.toList()
    }

    val isRecording get() = state == State.RECORDING

    /** 正在后台生成(停录合并/落盘)的事件数:首页/相册据此渲染"生成中"占位卡 */
    private val generatingEvents = AtomicInteger(0)

    /** 停录后事件进入后台合并:计数 +1,首页/相册头部立即显示"生成中"占位卡 */
    fun onEventGenerating() {
        val new = generatingEvents.incrementAndGet()
        Log.w(TAG, "onEventGenerating → count=$new (${Log.getStackTraceString(Throwable()).split("\n")[2].trim()})")
    }

    /** 事件落盘完成(或生成失败):计数 -1,撤除一张占位卡 */
    fun onEventGenerated() {
        val new = generatingEvents.updateAndGet { (it - 1).coerceAtLeast(0) }
        if (new < 0) Log.e(TAG, "onEventGenerated 计数异常:count=$new")
        else Log.w(TAG, "onEventGenerated → count=$new")
    }

    val generatingEventCount: Int get() = generatingEvents.get()

    private const val TAG = "RecordState"
}

/**
 * 回放窗口档位:分钟数 -> UI 文案。
 *
 * 特殊值 0 表示 30 秒档位(短场景回放,如操作失误瞬间的快速回看)。
 * 其他值为正整数分钟。
 */
object WindowPresets {
    val ALL = listOf(0, 1, 2, 5, 10, 30, 60)
    fun label(min: Int): String = when {
        min == 0 -> "30s"
        min >= 60 -> "1h"
        else -> "${min}min"
    }
}
