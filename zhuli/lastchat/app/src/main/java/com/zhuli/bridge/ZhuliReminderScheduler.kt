package com.zhuli.bridge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * T-G2/B4：系统精确闹钟调度（zhuli ReminderEngine 的最小移植）。
 *
 * 口径对齐旧壳 ReminderEngine.schedule：
 * - Android 12+ 优先 setExactAndAllowWhileIdle；无精确闹钟权限时降级 setAndAllowWhileIdle（会晚几分钟）；
 * - request code 即通知 id，重复设同一 id 覆盖旧提醒（改期语义）。
 * 审批链路：Tool 层标 RequiresApproval（LastChat 原生审批 UI）+ execute 内过 [ZhuliActionConfirm]（硬禁止名单）。
 */
object ZhuliReminderScheduler {

    data class Result(val ok: Boolean, val message: String)

    fun schedule(context: Context, requestCode: Int, title: String, dueAtMs: Long): Result {
        if (dueAtMs <= System.currentTimeMillis()) {
            return Result(false, "提醒时间已过（须为未来时刻）")
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ZhuliAlarmReceiver::class.java)
            .putExtra(ZhuliAlarmReceiver.EXTRA_TITLE, title)
            .putExtra(ZhuliAlarmReceiver.EXTRA_REQUEST_CODE, requestCode)
            .putExtra(ZhuliAlarmReceiver.EXTRA_DUE, dueAtMs)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else true
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAtMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAtMs, pi)
            }
        }.onFailure { return Result(false, "设闹钟失败：${it.message}") }
        val mode = if (canExact) "精确闹钟" else "非精确（缺权限，可能晚几分钟）"
        return Result(true, "已设$mode：$title")
    }

    fun cancel(context: Context, requestCode: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ZhuliAlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
    }
}
