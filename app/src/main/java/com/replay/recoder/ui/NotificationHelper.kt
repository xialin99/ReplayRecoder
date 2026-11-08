package com.replay.recoder.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.replay.recoder.R
import com.replay.recoder.record.RecordState
import com.replay.recoder.record.WindowPresets

/**
 * 通知栏助手:前台通知 + 「停止」RemoteAction。
 *
 * 鸿蒙4:通知渠道 IMPORTANCE_LOW 才不响铃,前台通知常驻。
 * 停止按钮通过 [StopActionReceiver] 中转发 STOP 给服务。
 */
class NotificationHelper(private val context: Context) {

    init { createChannel() }

    private fun createChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.channel_record_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = context.getString(R.string.channel_record_desc) }
        nm.createNotificationChannel(ch)
    }

    fun buildRecordingNotification(windowMin: Int): Notification {
        val stopIntent = Intent(context, StopActionReceiver::class.java).apply {
            action = StopActionReceiver.ACTION_STOP
        }
        val pendingStop = PendingIntent.getBroadcast(
            context, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(context.getString(R.string.notif_text, WindowPresets.label(windowMin)))
            .setOngoing(true)
            .addAction(0, context.getString(R.string.action_stop), pendingStop)
            .build()
    }

    /** 悬浮球常驻保活通知:低优先级,无操作按钮,仅维持进程前台优先级。 */
    fun buildKeepAliveNotification(): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(context.getString(R.string.keepalive_title))
            .setContentText(context.getString(R.string.keepalive_text))
            .setOngoing(true)
            .build()
    }

    companion object { const val CHANNEL_ID = "record_channel" }
}
