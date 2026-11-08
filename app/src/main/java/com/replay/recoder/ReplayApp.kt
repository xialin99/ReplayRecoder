package com.replay.recoder

import android.app.Application
import com.replay.recoder.event.EventRepository
import com.replay.recoder.ui.FloatingCamera
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用入口,持有事件库单例。
 */
class ReplayApp : Application() {
    lateinit var eventRepo: EventRepository

    /** 应用级协程作用域:产物已落盘后的入库/水印等"必须完成"的工作不能随 Activity 销毁被取消。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this
        eventRepo = EventRepository(applicationContext)
        // 预初始化悬浮相机的全局 CameraProvider 和 LifecycleOwner
        // (不打开相机,只准备好资源,show() 时直接绑定)
        FloatingCamera.ensureInitialized(applicationContext)
    }

    companion object {
        lateinit var instance: ReplayApp
            private set
    }
}
