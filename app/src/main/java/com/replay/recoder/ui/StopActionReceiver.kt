package com.replay.recoder.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.replay.recoder.record.RecordService

/**
 * 通知栏「停止」按钮接收器。
 * 收到后向 [RecordService] 发 STOP,服务自行冻结快照并存事件。
 */
class StopActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, RecordService.stopIntent(context)
            )
        }
    }
    companion object {
        const val ACTION_STOP = "com.replay.recoder.action.NOTIF_STOP"
    }
}
