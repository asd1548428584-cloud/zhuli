package com.zhuli.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * T-G2/B4：zhuli 提醒到点广播（LastChat 原无系统闹钟链路，此为最小闭环）。
 * [ZhuliReminderScheduler] 用显式组件 PendingIntent 指到本 Receiver，extra 带标题。
 * exported=false：只接受本应用发出的闹钟广播。
 */
class ZhuliAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_TITLE = "zhuli.reminder.title"
        const val EXTRA_REQUEST_CODE = "zhuli.reminder.reqCode"
        const val EXTRA_DUE = "zhuli.reminder.dueAt"
        const val CHANNEL_ID = "zhuli_reminders"
        const val NOTIFICATION_TAG = "zhuli_reminder"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val reqCode = intent.getIntExtra(EXTRA_REQUEST_CODE, 0)
        val dueAt = intent.getLongExtra(EXTRA_DUE, System.currentTimeMillis())

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "zhuli 提醒", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        // 点通知打开 LastChat 主界面（RouteActivity 为 launcher）
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = PendingIntent.getActivity(
            context, reqCode, open ?: Intent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("提醒")
            .setContentText(title)
            .setAutoCancel(true)
            .setContentIntent(pi)
            // 到点直接在通知上处置：完成 / 稍后 10 分钟 / 撤销（不用点进 App）
            .addAction(
                zhuliAction(context, reqCode, title, dueAt, ZhuliReminderActionReceiver.ACTION_COMPLETE, "完成", 1)
            )
            .addAction(
                zhuliAction(context, reqCode, title, dueAt, ZhuliReminderActionReceiver.ACTION_SNOOZE, "稍后 10 分钟", 2)
            )
            .addAction(
                zhuliAction(context, reqCode, title, dueAt, ZhuliReminderActionReceiver.ACTION_UNDO, "撤销", 3)
            )
            .build()
        runCatching { nm.notify(NOTIFICATION_TAG, reqCode, notif) }
    }

    private fun zhuliAction(
        context: Context,
        reqCode: Int,
        title: String,
        dueAt: Long,
        action: String,
        label: String,
        slot: Int,
    ): androidx.core.app.NotificationCompat.Action =
        androidx.core.app.NotificationCompat.Action.Builder(
            0,
            label,
            PendingIntent.getBroadcast(
                context,
                reqCode * 10 + slot,
                Intent(context, ZhuliReminderActionReceiver::class.java).apply {
                    this.action = action
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_REQUEST_CODE, reqCode)
                    putExtra(EXTRA_DUE, dueAt)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        ).build()
}
