package com.zhuli.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 通知栏动作落地端：「完成」= 结掉，「稍后10分钟」= 改期重排。
 * 都是本地点按，不问服务端、不外发，符合 §2.6-A3。
 */
class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_REMINDER_ID, 0L)
        if (id <= 0L) return
        val app = context.applicationContext
        when (intent.action) {
            ACTION_DONE -> ReminderEngine.complete(app, id)
            ACTION_SNOOZE -> ReminderEngine.snooze(app, id, 10)
        }
    }

    companion object {
        const val ACTION_DONE = "com.zhuli.reminder.DONE"
        const val ACTION_SNOOZE = "com.zhuli.reminder.SNOOZE"
        const val EXTRA_REMINDER_ID = "reminder_id"
    }
}
