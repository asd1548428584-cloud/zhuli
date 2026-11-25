package com.zhuli.core

import com.zhuli.core.ZhuliLog as Log

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils

/**
 * 提醒通知（第 5 步）。
 *
 * 设计取舍：
 * - 走独立通道 `zhuli_reminder`，importance HIGH —— 第 13 步做「勿扰分级」时，
 *   只需给闲聊换低优先级通道，提醒这条不动；
 * - 通知带「完成 / 稍后 10 分钟」两个动作，躺床上也能处理，不用点进 App；
 * - 点通知正文打开主界面（复用启动 Activity，不额外做页面）。
 */
object ReminderNotifier {

    private const val CHANNEL_ID = "zhuli_reminder"
    private const val CHANNEL_NAME = "到期提醒"
    /** 通知分组：同一刻到点的一批归一组，系统会折叠，不会糊满状态栏 */
    private const val GROUP_KEY = "zhuli_reminders"
    private const val SUMMARY_ID = 9_000
    /** 超过这个条数就只发一条汇总，不再一条条弹 */
    private const val BATCH_SOLO_LIMIT = 3
    const val EXTRA_DUE_TEXT = "due_text"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
            description = "日程与待办的准点提醒"
            enableLights(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * 第 13 步：勿扰时段（23:00–07:00）。夜里到点的提醒照样弹，但**不出声不震动**——
     * 提醒不能丢，觉也不能吵，取这个折中。面板里可以看/改（[AssistantPrefs]）。
     */
    private fun inQuietHours(ctx: Context): Boolean {
        if (!AssistantPrefs.quietHoursEnabled(ctx)) return false
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 7
    }

    fun show(ctx: Context, rem: Reminder) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val whenText = DateUtils.formatDateTime(
            ctx, rem.dueAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
        )
        val overdue = rem.dueAt < System.currentTimeMillis() - 60_000
        // 文案拼装挪到 ReminderText（纯逻辑、可单测），这里只负责取用
        val title = ReminderText.title(rem, overdue)

        val openIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPi = openIntent?.let {
            PendingIntent.getActivity(ctx, rem.id.toInt(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val donePi = actionPi(ctx, rem.id, ReminderActionReceiver.ACTION_DONE, rem.id.toInt())
        val snoozePi = actionPi(ctx, rem.id, ReminderActionReceiver.ACTION_SNOOZE, rem.id.toInt() + 100_000)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(ctx)

        val actionIcon = android.graphics.drawable.Icon.createWithResource(
            ctx, android.R.drawable.ic_menu_agenda)

        // 展开区 = 时间 + 正文 + 凭什么（依据）。收起状态仍只显示正文，不挤占两行
        val expanded = buildString {
            append(whenText)
            append('\n')
            append(rem.body)
            val why = ReminderText.basisLine(rem)
            if (why.isNotEmpty()) {
                append('\n')
                append(why)
            }
        }

        builder
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(rem.body)
            .setStyle(Notification.BigTextStyle().bigText(expanded))
            .setWhen(rem.dueAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(if (inQuietHours(ctx)) 0 else Notification.DEFAULT_ALL)
            .addAction(Notification.Action.Builder(actionIcon, "完成", donePi).build())
            .addAction(Notification.Action.Builder(actionIcon, "稍后10分钟", snoozePi).build())
            .setGroup(GROUP_KEY)

        if (contentPi != null) builder.setContentIntent(contentPi)
        if (inQuietHours(ctx)) {
            // 夜间静音：清声音与震动（setSilent 只在部分版本可用，这里用最小公共集）
            builder.setSound(null as android.net.Uri?)
            builder.setVibrate(null)
        }

        try {
            nm.notify(notificationId(rem.id), builder.build())
        } catch (t: Throwable) {
            // API 33+ 没给通知权限时这里会静默失败，不该让提醒链路崩
            Log.w("zhuli.reminder", "notify failed", t)
        }
    }

    /**
     * 一批提醒同时到点（补响 / 同刻多条）时的出口。
     *
     * 为什么必须批量：真机事故里 100 条同刻提醒一起响，状态栏直接糊死。
     * 规则：≤3 条照常逐条弹（各自带完成/稍后按钮）；超过 3 条只发一条汇总，
     * 点开进 App 处理，绝不再一条条刷屏。
     */
    fun showBatch(ctx: Context, rems: List<Reminder>) {
        if (rems.isEmpty()) return
        if (rems.size == 1) { show(ctx, rems[0]); return }
        if (rems.size <= BATCH_SOLO_LIMIT) {
            rems.forEach { show(ctx, it) }
            return
        }
        showSummary(ctx, rems)
    }

    /** 一条汇总通知（把一批提醒压成一条，点开进 App 处理）。 */
    fun showSummary(ctx: Context, rems: List<Reminder>) {
        if (rems.isEmpty()) return
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val head = rems.take(6).joinToString("\n") { "· ${it.title}" }
        val more = if (rems.size > 6) "\n…另有 ${rems.size - 6} 条" else ""
        val whenText = DateUtils.formatDateTime(
            ctx, rems.first().dueAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
        )

        val openIntent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPi = openIntent?.let {
            PendingIntent.getActivity(ctx, SUMMARY_ID, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(ctx, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        builder
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("⏰ ${rems.size} 条提醒到点了")
            .setContentText(rems.joinToString("、") { it.title }.take(60))
            .setStyle(Notification.BigTextStyle().bigText("$whenText\n$head$more"))
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(if (inQuietHours(ctx)) 0 else Notification.DEFAULT_ALL)
            .setGroup(GROUP_KEY)
        if (contentPi != null) builder.setContentIntent(contentPi)
        if (inQuietHours(ctx)) {
            // 夜间静音：清声音与震动（setSilent 只在部分版本可用，这里用最小公共集）
            builder.setSound(null as android.net.Uri?)
            builder.setVibrate(null)
        }

        try { nm.notify(SUMMARY_ID, builder.build()) } catch (t: Throwable) {
            Log.w("zhuli.reminder", "notify summary failed", t)
        }
        Log.i("zhuli.reminder", "summary ${rems.size} reminders")
    }

    fun cancel(ctx: Context, reminderId: Long) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try { nm.cancel(notificationId(reminderId)) } catch (_: Throwable) {}
    }

    // ==================== 合并出通知（v0.9.1） ====================
    //
    // 为什么不能让每个闹钟自己弹：真机事故里 100 个闹钟在同一秒到点，
    // 每个 Receiver 各弹一条，状态栏直接糊死。
    // 现在改成：到点只落库标 fired（notified=0），由一个 1.2 秒的合并窗口统一出通知 —
    // 1 条→正常弹；2~3 条→各弹一条（都带动作）；>3 条→一条汇总。

    private const val MERGE_DELAY_MS = 1_200L
    private val mergeHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val mergeLock = Any()
    private var merging = false
    /** 合并窗口结束时挨个通知外面（每发一个闹钟都带着一个 goAsync 的 PendingResult，必须都 finish） */
    private val onMerged = mutableListOf<() -> Unit>()

    /** 到点后调它：开一个合并窗口，窗口结束后把所有「还没弹过」的提醒一次性出通知。 */
    fun scheduleFlush(ctx: Context, done: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        synchronized(mergeLock) {
            if (done != null) onMerged.add(done)
            if (merging) return
            merging = true
            mergeHandler.postDelayed({
                try { flush(app) } catch (t: Throwable) {
                    Log.w("zhuli.reminder", "flush failed", t)
                } finally {
                    val cbs: List<() -> Unit>
                    synchronized(mergeLock) {
                        cbs = onMerged.toList()
                        onMerged.clear()
                        merging = false
                    }
                    for (cb in cbs) { try { cb() } catch (_: Throwable) {} }
                }
            }, MERGE_DELAY_MS)
        }
    }

    /**
     * 把库里「已 fired 但没弹过」的提醒一次性出掉，并打上已弹标记。
     * 幂等：重复调用不会重复弹（靠 notified 列）。
     *
     * v0.17：加 `@Synchronized`。flush 有两个入口 —— 闹钟到点的合并窗口（主线程）
     * 和 15 分钟心跳 sweep（io 线程）。没有互斥时两边可能同时读到同一批 notified=0 的行，
     * 于是同一条提醒被弹两次；用户看到的就是「闹钟响了以后一直弹同一个消息」。
     *
     * 折叠规则（真机事故的兜底）：这台 ROM 递送闹钟是按 ~1.2 秒排队送的，
     * 5 个同刻提醒会被拆成 5 次回调、各自开一次合并窗口。所以再看**一分钟内响了几条**——
     * 超过 [BATCH_SOLO_LIMIT] 条就把已经弹出去的单条撤掉，改发一条汇总，绝不越弹越多。
     */
    @Synchronized
    fun flush(ctx: Context): Int {
        val app = ctx.applicationContext
        val db = InboxDatabase(app)
        var fresh: List<Reminder> = emptyList()
        var folded = false
        try {
            fresh = db.firedUnnotified()
            if (fresh.isEmpty()) return 0
            val recent = db.firedSince(System.currentTimeMillis() - 60_000L)
            if (recent.size > BATCH_SOLO_LIMIT) {
                // 一分钟内已经响了太多条：撤掉已弹的单条，折叠成一条汇总
                for (r in recent) cancel(app, r.id)
                showSummary(app, recent)
                for (r in recent) db.setReminderNotified(r.id, true)
                folded = true
            } else {
                showBatch(app, fresh)
                for (r in fresh) db.setReminderNotified(r.id, true)
            }
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
        if (fresh.isNotEmpty()) {
            Log.i("zhuli.reminder", "flush ${fresh.size} reminders folded=$folded")
        }
        return fresh.size
    }

    private fun actionPi(ctx: Context, reminderId: Long, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(ctx, ReminderActionReceiver::class.java).apply {
            this.action = action
            putExtra(ReminderActionReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getBroadcast(ctx, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notificationId(reminderId: Long): Int = 10_000 + reminderId.toInt()
}
