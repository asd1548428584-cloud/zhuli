package com.zhuli.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * T-G2/B4/P1-3：提醒到点后的「完成 / 稍后 10 分钟 / 撤销」。
 *
 * 为什么单独一个 Receiver：B4 的提醒是"纯闹钟 + 通知"（create_reminder 只排 AlarmManager，不写库），
 * 所以处置状态只能自己记。做法是一份极小的"上一次处置"日志：
 * - 完成/稍后都把「处置动作 + 原定时刻」记进 prefs，撤销就是把原定时刻重新挂回闹钟；
 * - 日志只保留最近一次（提醒本来就是一次性的，不需要历史）。
 */
class ZhuliReminderActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COMPLETE = "com.zhuli.bridge.reminder.COMPLETE"
        const val ACTION_SNOOZE = "com.zhuli.bridge.reminder.SNOOZE"
        const val ACTION_UNDO = "com.zhuli.bridge.reminder.UNDO"

        private const val PREFS = "zhuli_reminder_journal"
        private const val K_ID = "last_id"
        private const val K_TITLE = "last_title"
        private const val K_DUE = "last_due"
        private const val K_ACTION = "last_action"
        private const val K_AT = "last_at"

        /** 撤销窗口：2 小时内都能撤（比"数秒"宽，但对用户更有用）。 */
        private const val UNDO_WINDOW_MS = 2 * 60 * 60 * 1000L

        fun pendingIntentsFor(context: Context, requestCode: Int, title: String, dueAt: Long): List<NotificationCompat.Action> {
            fun action(action: String, label: String, code: Int) = NotificationCompat.Action.Builder(
                0, label,
                PendingIntent.getBroadcast(
                    context,
                    requestCode * 10 + code,
                    Intent(context, ZhuliReminderActionReceiver::class.java).apply {
                        this.action = action
                        putExtra(ZhuliAlarmReceiver.EXTRA_TITLE, title)
                        putExtra(ZhuliAlarmReceiver.EXTRA_REQUEST_CODE, requestCode)
                        putExtra(ZhuliAlarmReceiver.EXTRA_DUE, dueAt)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            ).build()

            return listOf(
                action(ACTION_COMPLETE, "完成", 1),
                action(ACTION_SNOOZE, "稍后 10 分钟", 2),
                action(ACTION_UNDO, "撤销", 3),
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val id = intent.getIntExtra(ZhuliAlarmReceiver.EXTRA_REQUEST_CODE, 0)
        val title = intent.getStringExtra(ZhuliAlarmReceiver.EXTRA_TITLE)
            ?: intent.getStringExtra("title").orEmpty()
        val due = intent.getLongExtra(ZhuliAlarmReceiver.EXTRA_DUE, 0L)
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {
            ACTION_COMPLETE -> {
                journal(sp, id, title, due, "done")
                ZhuliReminderScheduler.cancel(app, id)
                nm.cancel(ZhuliAlarmReceiver.NOTIFICATION_TAG, id)
                feedback(app, id, "已完成：$title", undoable = true)
            }

            ACTION_SNOOZE -> {
                journal(sp, id, title, due, "snooze")
                val next = System.currentTimeMillis() + 10 * 60 * 1000L
                ZhuliReminderScheduler.schedule(app, id, title, next)
                nm.cancel(ZhuliAlarmReceiver.NOTIFICATION_TAG, id)
                feedback(app, id, "已推迟 10 分钟：$title", undoable = true)
            }

            ACTION_UNDO -> {
                val lastId = sp.getInt(K_ID, -1)
                val lastAt = sp.getLong(K_AT, 0L)
                val lastTitle = sp.getString(K_TITLE, "").orEmpty()
                val lastDue = sp.getLong(K_DUE, 0L)
                val fresh = System.currentTimeMillis() - lastAt < UNDO_WINDOW_MS
                if (lastId < 0 || !fresh) {
                    feedback(app, id, "没有可撤销的提醒了", undoable = false)
                    return
                }
                // 原定时刻已过就 1 分钟后补一次，别让它直接消失
                val restoreAt = if (lastDue > System.currentTimeMillis()) lastDue
                else System.currentTimeMillis() + 60_000L
                ZhuliReminderScheduler.schedule(app, lastId, lastTitle, restoreAt)
                nm.cancel(ZhuliAlarmReceiver.NOTIFICATION_TAG, lastId)
                sp.edit().clear().apply()
                feedback(app, id, "已撤销：$lastTitle 会重新提醒", undoable = false)
            }
        }
    }

    private fun journal(sp: android.content.SharedPreferences, id: Int, title: String, due: Long, action: String) {
        sp.edit()
            .putInt(K_ID, id)
            .putString(K_TITLE, title)
            .putLong(K_DUE, due)
            .putString(K_ACTION, action)
            .putLong(K_AT, System.currentTimeMillis())
            .apply()
    }

    /** 处置后的轻回执（3 秒自动消失，带「撤销」按钮）。 */
    private fun feedback(context: Context, id: Int, text: String, undoable: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(ZhuliAlarmReceiver.CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        ZhuliAlarmReceiver.CHANNEL_ID,
                        "zhuli 提醒",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                )
            }
        }
        val builder = NotificationCompat.Builder(context, ZhuliAlarmReceiver.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("zhuli")
            .setContentText(text)
            .setTimeoutAfter(15_000L)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        if (undoable) {
            builder.addAction(
                0,
                "撤销",
                PendingIntent.getBroadcast(
                    context,
                    id * 10 + 9,
                    Intent(context, ZhuliReminderActionReceiver::class.java).apply {
                        action = ACTION_UNDO
                        putExtra(ZhuliAlarmReceiver.EXTRA_REQUEST_CODE, id)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(ZhuliAlarmReceiver.NOTIFICATION_TAG, id + 100_000, builder.build())
        }
    }
}
