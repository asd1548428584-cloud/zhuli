package com.zhuli.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 闹钟到点的落地端：到点提醒交给 [ReminderEngine.onAlarm]，心跳交给 [ReminderEngine.sweep]。
 *
 * v0.9.1：到点不再就地弹通知 —— 先标 fired，再开一个 1.2 秒的合并窗口，
 * 窗口内到点的一起出（1 条正常弹、>3 条只弹一条汇总）。
 * 用 goAsync() 把广播生命周期撑到合并窗口结束，进程不会被立刻回收。
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            ACTION_FIRE -> {
                val id = intent.getLongExtra(EXTRA_REMINDER_ID, 0L)
                if (id <= 0L) return
                // 落库很快，同步做完；同步失败也不能让提醒消失
                ReminderEngine.onAlarm(app, id)
                val pr = goAsync()
                ReminderNotifier.scheduleFlush(app) {
                    // 合并窗口已经弹过通知（notified=1），此刻才给周期事项排下一次；
                    // 早一步改状态，这条通知就永远出不来（v0.17 修）
                    ReminderEngine.advanceRepeatsAsync(app)
                    try { pr.finish() } catch (_: Throwable) {}
                }
            }
            ACTION_HEARTBEAT -> ReminderEngine.sweepAsync(app)
            // 第 14 步：晨间简报（每天 8 点，低优先级通知，不响铃）
            DailyBrief.ACTION_BRIEF -> DailyBrief.show(app)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.zhuli.reminder.FIRE"
        /** 15 分钟心跳：扫一遍有没有被系统吃掉的提醒 */
        const val ACTION_HEARTBEAT = "com.zhuli.reminder.HEARTBEAT"
        const val EXTRA_REMINDER_ID = "reminder_id"
    }
}
