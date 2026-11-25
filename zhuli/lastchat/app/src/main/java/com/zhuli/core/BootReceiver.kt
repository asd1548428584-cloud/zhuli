package com.zhuli.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhuli.core.ZhuliLog as Log

/**
 * 开机 / 升级 / 改时间后把闹钟重新排一遍（AlarmManager 的闹钟不跨重启存活，这是硬约束）。
 * 只在「有活儿要排」时才碰数据库，避免开机时无谓唤醒。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                Log.i("zhuli.reminder", "reschedule on ${intent.action}")
                val app = context.applicationContext
                ReminderEngine.ensureAlarms(app)
                DailyBrief.schedule(app)
                // 重启后顺便把「还没建提醒」的期限补上（例如关机期间收到的新消息）
                ReminderEngine.scanAsync(app)
            }
        }
    }
}
