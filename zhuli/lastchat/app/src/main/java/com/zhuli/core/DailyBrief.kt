package com.zhuli.core

import com.zhuli.core.ZhuliLog as Log

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.Locale

/**
 * 晨间简报 + 周一加大号周报（第 14 步）。
 *
 * 为什么值得做：助理收了那么多东西，用户没空一条条翻。每天 8 点一条「今天该干嘛」，
 * 比任何花哨界面都实在 —— 这也是需求 §2.2 里「晨间简报/周报」那一条的落地。
 *
 * 实现取舍：
 * - 用 `setInexactRepeating` 每日重复（简报不需要准点，省一个精确闹钟名额，Doze 也不会吃掉）；
 * - 通知走**单独的低优先级通道**（不响铃不震动），和「到点提醒」分开，免得早上被两套声音吵；
 * - 内容全部本地算，零网络。
 */
object DailyBrief {

    private const val TAG = "zhuli.brief"
    const val ACTION_BRIEF = "com.zhuli.brief.DAILY"
    private const val CHANNEL_ID = "zhuli_brief"
    private const val NOTIF_ID = 950_001
    private const val REQUEST = 888_888

    /** 每天 8:00（用 InexactRepeating，误差几分钟无所谓）。 */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, REQUEST,
            Intent(ctx, AlarmReceiver::class.java).apply { action = ACTION_BRIEF },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cal = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 今天还没到 8 点就直接排今天
        if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) < 8) cal.add(Calendar.DAY_OF_YEAR, -1)
        try {
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                cal.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pi
            )
        } catch (t: Throwable) {
            Log.w(TAG, "schedule brief failed", t)
        }
    }

    fun show(ctx: Context) {
        val app = ctx.applicationContext
        if (!AssistantPrefs.briefEnabled(app)) return
        val db = InboxDatabase(app)
        val text = try { build(app, db) } finally {
            try { db.close() } catch (_: Throwable) {}
        }
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "晨间简报", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "每天早上的收件/待办/账本小结"
                        setShowBadge(false)
                        enableVibration(false)
                    }
                )
            }
        }
        val open = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pi = open?.let {
            PendingIntent.getActivity(app, NOTIF_ID, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(app, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(app)
        b.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (isMonday()) "📋 周一简报" else "☀️ 今日简报")
            .setContentText(text.lineSequence().firstOrNull() ?: "")
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (pi != null) b.setContentIntent(pi)
        try { nm.notify(NOTIF_ID, b.build()) } catch (t: Throwable) {
            Log.w(TAG, "notify brief failed", t)
        }
    }

    /** 简报正文（也供壳里「简报预览」直接展示）。 */
    fun build(ctx: Context, db: InboxDatabase): String {
        val now = System.currentTimeMillis()
        val dayStart = startOfToday()
        val yesterdayStart = dayStart - 24 * 60 * 60 * 1000L

        val yesterday = db.between(yesterdayStart, dayStart, 100)
        val todo = db.upcoming(5)
        val courier = db.recentByCategory(InboxRecord.CAT_EXPRESS, 20)
        .filter { it.deadlineAt > now }
        val weekSpend = db.ledgerCategoryBreakdown(dayStart - 6 * 24 * 60 * 60 * 1000L, now)
            .filter { it.first != Ledger.DIR_INCOME }
            .sumOf { it.third.second }

        return buildString {
            appendLine("☀️ 早上好，今天是 ${dateStr(now)}")
            appendLine()
            appendLine("【昨天】收到 ${yesterday.size} 条" + if (yesterday.isEmpty()) "" else
                "（" + yesterday.groupBy { it.category.ifBlank { "其他" } }
                    .entries.sortedByDescending { it.value.size }.take(3)
                    .joinToString("、") { "${it.key} ${it.value.size}" } + "）")
            if (todo.isEmpty()) {
                appendLine("【今天】没有明确的待办")
            } else {
                appendLine("【今天】${todo.size} 件有时限：")
                for (r in todo.take(3)) {
                    appendLine("· ${deadlineStr(r.deadlineAt)}｜${r.note.ifBlank { r.text.take(20) }}")
                }
            }
            if (courier.isNotEmpty()) {
                appendLine("【快递】待取 ${courier.size} 件：" + courier.take(2).joinToString("；") { it.note.take(24) })
            }
            if (weekSpend > 0) appendLine("【账本】近七天支出 ¥${fmt(weekSpend)}")

            if (isMonday()) {
                appendLine()
                appendLine("—— 上周小结 ——")
                val weekAgo = dayStart - 7 * 24 * 60 * 60 * 1000L
                val lastWeek = db.between(weekAgo, dayStart, 200)
                val cats = lastWeek.groupBy { it.category.ifBlank { "其他" } }
                    .entries.sortedByDescending { it.value.size }.take(4)
                appendLine("收件 ${lastWeek.size} 条" + if (cats.isEmpty()) "" else
                    "：" + cats.joinToString("、") { "${it.key} ${it.value.size}" })
                val spend = db.ledgerCategoryBreakdown(weekAgo, dayStart)
                if (spend.isNotEmpty()) {
                    val exp = spend.filter { it.first != Ledger.DIR_INCOME }
                    appendLine("支出 ¥${fmt(exp.sumOf { it.third.second })}，" +
                        "共 ${spend.sumOf { it.third.first }} 笔；大头：" +
                        exp.take(2).joinToString("、") { "${it.second} ¥${fmt(it.third.second)}" })
                } else appendLine("上周没记账")
            }
        }.trim()
    }

    private fun isMonday(): Boolean =
        Calendar.getInstance().get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY

    private fun startOfToday(): Long =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dateStr(ms: Long): String =
        java.text.SimpleDateFormat("M月d日 EEEE", Locale.getDefault()).format(java.util.Date(ms))

    private fun deadlineStr(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(ms))

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
}
