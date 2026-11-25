package com.zhuli.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zhuli.core.ZhuliLog as Log
import java.util.concurrent.Executors

/**
 * 提醒引擎（第 5 步）。
 *
 * 职责边界：
 * - 只消费 [InboxDatabase] 里已经挖出来的 `deadline_at`，不重复解析原文（第 4 步已做完）；
 * - 用 AlarmManager 落系统级闹钟，进程被杀也能响（这是「叫得醒」的底线）；
 * - 双写系统日历（需求 C4 默认开启，可在壳里关），让提醒和别的日历 App 互通。
 *
 * 不做什么：
 * - 不代发消息、不转账（§2.6-A3 只收不发）；
 * - 不做周期事项（每周/每月）—— 那是第 14 步 C2 的活。
 */
object ReminderEngine {

    private const val TAG = "zhuli.reminder"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val HEARTBEAT_INTERVAL = 15L * 60 * 1000
    private const val HEARTBEAT_REQUEST = 999_999
    /** 一轮扫描最多新建多少条提醒：防某次回声/轰炸把库里堆成几十条同刻闹钟 */
    private const val MAX_CREATE_PER_SCAN = 15
    private val io = Executors.newSingleThreadExecutor()

    /** 异步跑一轮扫描（装机/启动/提取完成后都调它）。 */
    fun scanAsync(ctx: Context, onDone: ((Int) -> Unit)? = null) {
        val app = ctx.applicationContext
        io.execute {
            val n = try { scan(app) } catch (t: Throwable) { Log.e(TAG, "scan failed", t); 0 }
            if (n > 0) onDone?.invoke(n)
        }
    }

    /**
     * 补响 + 重排（心跳/开 App 时调）。
     *
     * 为什么必须有这一层：Android 12+ 的 Doze、以及努比亚 MyOS 这类厂商省电策略，
     * 会把 setExactAndAllowWhileIdle 也压掉一部分。准点靠闹钟，兜底靠这里——
     * 已经过点但还没响的（1 小时内）当场补响，绝不静默丢掉。
     */
    fun sweep(ctx: Context) {
        val db = InboxDatabase(ctx)
        val late = mutableListOf<Reminder>()
        try {
            val now = System.currentTimeMillis()
            for (rem in db.scheduledReminders()) {
                if (rem.dueAt > now) continue
                if (rem.dueAt < now - 60 * 60_000L) {
                    // 超过 1 小时的陈年旧账只结掉，不炸通知
                    db.setReminderStatus(rem.id, Reminder.STATUS_CANCELED, rem.dueAt)
                    continue
                }
                markFired(db, rem.id)
                late.add(rem.copy(status = Reminder.STATUS_FIRED))
            }
            // T-D1 验收缺口补齐：待确认事项放超过宽限期还没人确认 → 归档成 expired。
            // 放在 sweep 里是因为它本来就在「每次进 App / 每次重启」跑，不需要额外闹钟。
            try {
                val n = db.expirePendingBefore(PendingExpiry.cutoff(now))
                if (n > 0) {
                    Log.i(TAG, "expired $n pending reminders（超过 ${PendingExpiry.GRACE_MS / 3_600_000} 小时未确认）")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "expirePending failed", t)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sweep failed", t)
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
        // 补响按批出：一次补十几条时只弹一条汇总，不再糊满状态栏（真机 100 条同响事故）
        if (late.isNotEmpty()) {
            Log.i(TAG, "sweep marked ${late.size} late reminders fired")
        }
        // 统一出口：把「已 fired 没弹过」的一次性出掉（含刚补响的、以及上次进程被掐掉漏弹的）
        try { ReminderNotifier.flush(ctx) } catch (t: Throwable) { Log.w(TAG, "flush failed", t) }
        // 周期事项：弹过的往下次顺延（在 flush 之后，否则状态被改回 scheduled 就弹不出来了）
        try { advanceRepeats(ctx) } catch (t: Throwable) { Log.w(TAG, "advanceRepeats failed", t) }
        // 补响完再排一遍：新建的、被系统清掉的闹钟都在这儿归位
        scan(ctx)
    }

    fun sweepAsync(ctx: Context) {
        val app = ctx.applicationContext
        io.execute { try { sweep(app) } catch (t: Throwable) { Log.e(TAG, "sweepAsync failed", t) } }
    }

    /**
     * 扫描带期限的消息 → 补建提醒 → 排闹钟 → 双写日历。
     * 幂等：同一条消息已有提醒就跳过；已有提醒但闹钟丢了（重启/清数据）会重排。
     */
    fun scan(ctx: Context): Int {
        if (!AssistantPrefs.reminderEnabled(ctx)) return 0
        val db = InboxDatabase(ctx)
        var created = 0
        try {
            val rows = db.upcoming(100)
            for (rec in rows) {
                val existing = db.reminderOfInbox(rec.id)
                if (existing != null) {
                    // 只补排"已生效但闹钟丢了"的；pending（待确认）和 done/canceled 一律不动，
                    // 否则每次扫描都会把待确认的又排成闹钟（T-D1 必须挡住这条）
                    if (existing.isActive) schedule(ctx, existing)
                    continue
                }
                // 护栏一：回声源/噪声源的消息永远不变成提醒（日历、时钟、计步、系统闹钟文案）
                if (NoiseGuard.shouldNotRemind(rec)) continue
                // 护栏二：已经排到顶了就不再堆，宁可漏一条也不要把状态栏压垮
                if (created >= MAX_CREATE_PER_SCAN) {
                    Log.w(TAG, "too many new reminders in one scan, stop at $created")
                    break
                }
                val title = rec.note.ifBlank { rec.text.take(30) }
                // P1 冲突检测：和已有提醒撞在半小时内，就在通知里点一句
                val conflict = findConflict(db, rec.deadlineAt)
                val body0 = buildBody(rec)
                // 护栏三：同一刻同一件事已经被排过闹钟（重复推送），不再排第二条
                if (db.similarScheduledReminder(rec.deadlineAt, title) != null) {
                    Log.i(TAG, "duplicate reminder skipped inbox=${rec.id} title=${title.take(16)}")
                    continue
                }
                // 护栏四（T-D2）：这条被你取消过 —— 同刻同标题再进来就别重建了。
                // 实测 bilibili 视频标题一次刷出 7 条同刻提醒，取消完下一条通知又建一条。
                if (db.hasCanceledSame(rec.deadlineAt, title)) {
                    Log.i(TAG, "canceled-before reminder not recreated inbox=${rec.id} title=${title.take(16)}")
                    continue
                }
                val body = if (conflict == null) body0 else
                    ReminderText.conflictLine(conflict.title, kotlin.math.abs(conflict.dueAt - rec.deadlineAt) / 60000) + "\n" + body0
                // T-D1：先判置信度。低置信只入库不提醒；中置信进「待确认」，不排闹钟也不写日历
                val base = ReminderConfidence.evaluate(rec.text, rec.deadlineAt)
                // 快递取件（2026-11-21 用户拍板）：识别到取件码就提为高置信 ——
                // deadline 是「到站 +2h」推的，原判会落到「时间来源不明」进待确认，
                // 而取件是主力场景，每次手点确认等于没通电。依据如实写成推定，不冒充短信原文。
                val verdict = if (rec.category == InboxRecord.CAT_EXPRESS) {
                    val code = try { Express.parse(rec.text)?.code } catch (t: Throwable) { null }
                    CourierPolicy.basisOf(code)?.let { ReminderConfidence.Verdict(ReminderConfidence.HIGH, it) } ?: base
                } else {
                    base
                }
                if (verdict.level == ReminderConfidence.LOW) {
                    Log.i(TAG, "low confidence, not reminding inbox=${rec.id} basis=${verdict.basis}")
                    continue
                }
                val pending = verdict.level == ReminderConfidence.MEDIUM
                val id = db.upsertReminder(
                    rec.id, rec.deadlineAt, title, body, rec.repeatRule,
                    confidence = verdict.level,
                    basis = verdict.basis,
                    status = if (pending) Reminder.STATUS_PENDING else Reminder.STATUS_SCHEDULED
                )
                val rem = db.reminderById(id) ?: continue
                if (pending) {
                    created++
                    Log.i(TAG, "pending reminder inbox=${rec.id} basis=${verdict.basis}")
                    continue
                }
                schedule(ctx, rem)
                created++
                // 日历双写：权限没给/开关关了都是正常情况，不当作错误
                if (AssistantPrefs.reminderCalendarSync(ctx)) {
                    val eventId = CalendarWriter.write(ctx, rem)
                    if (eventId > 0) db.setReminderCalendarEvent(id, eventId)
                }
            }
            // 闹钟容易被系统清掉（重启、时区变更、省电模式），顺手把活跃提醒全排一遍
            ensureAlarmsWith(db, ctx)
            ensureHeartbeat(ctx)
            DailyBrief.schedule(ctx)   // 第 14 步：顺手把晨间简报挂上（幂等）
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
        if (created > 0) Log.i(TAG, "created $created reminders")
        return created
    }

    fun ensureAlarms(ctx: Context) {
        val db = InboxDatabase(ctx)
        try { ensureAlarmsWith(db, ctx) } finally { try { db.close() } catch (_: Throwable) {} }
    }

    private fun ensureAlarmsWith(db: InboxDatabase, ctx: Context) {
        val now = System.currentTimeMillis()
        for (rem in db.scheduledReminders()) {
            // 已经过点 1 分钟以上还没响的（进程被冻、装机重启、省电限流）：
            // 不再往后重排（否则每次扫描都把它排到「现在+5秒」，连环响），**但也不取消** ——
            // 交给 sweep 补响（1 小时内补一条，超过 1 小时才归档）。D15：以前这里直接 canceled，
            // 结果"晚响 1 分钟"就等于这条提醒凭空消失，和 sweep 的补响设计自相矛盾。
            if (rem.dueAt < now - 60_000) continue
            schedule(ctx, rem)
        }
    }

    /**
     * 15 分钟一次的心跳（非精确，不需要额外权限）：到点就扫一遍有没有漏响的。
     * 这条是「叫得醒」的保险丝，专治 Doze 和厂商省电把精确闹钟吃掉的情况。
     */
    private fun ensureHeartbeat(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_HEARTBEAT
        }
        val pi = PendingIntent.getBroadcast(
            ctx, HEARTBEAT_REQUEST, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + HEARTBEAT_INTERVAL,
                HEARTBEAT_INTERVAL,
                pi
            )
        } catch (t: Throwable) {
            Log.w(TAG, "heartbeat schedule failed", t)
        }
    }

    /**
     * 落一个系统闹钟。
     *
     * 优先 `setAlarmClock`：这是系统专给「闹钟/日程」开的口子，Doze 不收、厂商省电多半也放行；
     * 代价是状态栏会显示闹钟图标 —— 对提醒助理来说这是应该的。
     * 没精确闹钟权限时才降级 setExact / setAndAllowWhileIdle，绝不因此崩溃。
     */
    fun schedule(ctx: Context, rem: Reminder) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val trigger = maxOf(rem.dueAt, System.currentTimeMillis() + 5_000)
        val pi = alarmIntent(ctx, rem.id)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

        if (exactAllowed) {
            try {
                val show = PendingIntent.getActivity(
                    ctx, rem.id.toInt() + 500_000,
                    ctx.packageManager.getLaunchIntentForPackage(ctx.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), pi)
                Log.i(TAG, "alarmClock id=${rem.id} at=${rem.dueAt}")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "setAlarmClock failed, fallback to exact", t)
            }
        }
        try {
            if (exactAllowed) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            }
            Log.i(TAG, "scheduled id=${rem.id} at=${rem.dueAt} exact=$exactAllowed")
        } catch (t: Throwable) {
            Log.w(TAG, "setExact failed, fallback to inexact", t)
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi) }
            catch (t2: Throwable) { Log.e(TAG, "schedule failed", t2) }
        }
    }

    fun cancelAlarm(ctx: Context, reminderId: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        try { am.cancel(alarmIntent(ctx, reminderId)) } catch (_: Throwable) {}
    }

    private fun alarmIntent(ctx: Context, reminderId: Long): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_FIRE
            putExtra(AlarmReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getBroadcast(
            ctx, reminderId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ==================== 响铃后的动作 ====================

    /**
     * 闹钟到点：只标 fired（通知交给 [ReminderNotifier.flush] 合并出）。
     *
     * v0.17 修：周期事项的「排下一次」**不能在这里做**。
     * 原来的写法是 markFired 之后立刻 rescheduleNextOccurrence()，行状态被改回 scheduled，
     * 1.2 秒后的合并窗口 `firedUnnotified()` 就查不到它了 —— 表现是「每天 8 点吃药」
     * 准点响过但一条通知都没有。现在改成：行保持在 fired，等合并器弹完（notified=1）
     * 再由 [advanceRepeats] 顺延。
     */
    fun onAlarm(ctx: Context, reminderId: Long) {
        val db = InboxDatabase(ctx)
        try {
            val rem = db.reminderById(reminderId) ?: return
            if (rem.status != Reminder.STATUS_SCHEDULED) return
            markFired(db, rem.id)
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
    }

    /**
     * 周期事项：把「已经弹过通知」的 fired 行顺延到下一次并重挂闹钟。
     *
     * 只认 notified=true 的行 —— 没弹过就交给合并窗口，别抢在通知前面把状态改掉（那就是上面那个坑）。
     * 两个调用点：合并窗口结束后（[AlarmReceiver]）和每次 sweep（进程被杀/漏跑后自愈）。
     */
    fun advanceRepeats(ctx: Context): Int {
        val db = InboxDatabase(ctx)
        var n = 0
        try {
            for (rem in db.firedReminders(50)) {
                if (!Repeat.isRepeat(rem.repeatRule)) continue
                if (!rem.notified) continue
                val nextDue = Repeat.firstAfter(rem.repeatRule, rem.dueAt)
                db.rescheduleNextOccurrence(rem.id, nextDue)
                schedule(ctx, rem.copy(dueAt = nextDue, status = Reminder.STATUS_SCHEDULED))
                Log.i(TAG, "repeat ${rem.repeatRule} next=$nextDue id=${rem.id}")
                n++
            }
        } catch (t: Throwable) {
            Log.w(TAG, "advanceRepeats failed", t)
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
        return n
    }

    fun advanceRepeatsAsync(ctx: Context) {
        val app = ctx.applicationContext
        io.execute { try { advanceRepeats(app) } catch (t: Throwable) { Log.w(TAG, "advanceRepeatsAsync failed", t) } }
    }

    /** 回收站：把误点「完成」的提醒捞回来（重新排闹钟）。 */
    fun restore(ctx: Context, reminderId: Long) {
        val db = InboxDatabase(ctx)
        try {
            val rem = db.reminderById(reminderId) ?: return
            val due = if (rem.dueAt > System.currentTimeMillis()) rem.dueAt
            else System.currentTimeMillis() + 10 * 60_000L
            db.rescheduleReminder(rem.id, due)
            schedule(ctx, rem.copy(dueAt = due, status = Reminder.STATUS_SCHEDULED))
            Log.i(TAG, "restore id=${rem.id} due=$due")
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
    }

    /** 状态写回，写完自检一次；个别 ROM 上 update 会静默不生效，这里兜一层 execSQL。 */
    private fun markFired(db: InboxDatabase, id: Long) {
        val now = System.currentTimeMillis()
        try {
            db.markReminderFired(id, now)   // 顺带把 notified 复位成 0，保证合并器一定会弹
        } catch (t: Throwable) {
            Log.w(TAG, "setReminderStatus failed", t)
        }
        val after = try { db.reminderById(id) } catch (t: Throwable) { null }
        if (after != null && after.status != Reminder.STATUS_FIRED) {
            try {
                db.writableDatabase.execSQL(
                    "UPDATE ${InboxDatabase.TABLE_REMINDER} SET " +
                        "${InboxDatabase.COL_REM_STATUS} = ?, ${InboxDatabase.COL_REM_FIRED} = ?, " +
                        "${InboxDatabase.COL_REM_NOTIFIED} = 0 " +
                        "WHERE ${InboxDatabase.COL_REM_ID} = ?",
                    arrayOf<Any>(Reminder.STATUS_FIRED, now, id)
                )
            } catch (t: Throwable) {
                Log.e(TAG, "markFired fallback failed id=$id", t)
            }
        }
    }

    /** 稍后提醒：改期 + 重排闹钟，通知一起撤掉。 */
    fun snooze(ctx: Context, reminderId: Long, minutes: Int = 10) {
        val db = InboxDatabase(ctx)
        try {
            val rem = db.reminderById(reminderId) ?: return
            val next = System.currentTimeMillis() + minutes * 60_000L
            db.rescheduleReminder(rem.id, next, countSnooze = true)
            ReminderNotifier.cancel(ctx, rem.id)
            schedule(ctx, rem.copy(dueAt = next, status = Reminder.STATUS_SCHEDULED))
            Log.i(TAG, "snooze id=${rem.id} +${minutes}min")
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
    }

    /**
     * T-D1：把「待确认」的提醒转正（用户点了确认）。
     * 转正之后才排闹钟、才写日历 —— 确认之前它只是个列表项，不会打扰人。
     */
    fun confirm(ctx: Context, reminderId: Long): Boolean {
        val db = InboxDatabase(ctx)
        return try {
            val rem = db.reminderById(reminderId) ?: return false
            if (!rem.isPending) return false
            db.rescheduleReminder(rem.id, rem.dueAt)
            val fresh = db.reminderById(rem.id) ?: return false
            schedule(ctx, fresh)
            if (AssistantPrefs.reminderCalendarSync(ctx)) {
                val e = CalendarWriter.write(ctx, fresh)
                if (e > 0) db.setReminderCalendarEvent(fresh.id, e)
            }
            Log.i(TAG, "confirmed reminder id=${rem.id}")
            true
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
    }

    /** 完成：清闹钟、撤通知、置 done。 */
    fun complete(ctx: Context, reminderId: Long) {
        val db = InboxDatabase(ctx)
        try {
            val rem = db.reminderById(reminderId) ?: return
            db.setReminderStatus(rem.id, Reminder.STATUS_DONE)
            cancelAlarm(ctx, rem.id)
            ReminderNotifier.cancel(ctx, rem.id)
            CalendarWriter.remove(ctx, rem)
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
    }

    /** 同半小时内是否已有别的提醒（不算它自己）。 */
    private fun findConflict(db: InboxDatabase, dueAt: Long): Reminder? =
        try {
            db.scheduledReminders(300).firstOrNull {
                it.isActive && kotlin.math.abs(it.dueAt - dueAt) in 1..(30 * 60_000L)
            }
        } catch (t: Throwable) { null }

    private fun buildBody(rec: InboxRecord): String {
        val from = rec.appLabel.ifBlank {
            when (rec.source) {
                InboxRecord.SOURCE_SHARE -> "分享"
                InboxRecord.SOURCE_LINK -> "链接"
                else -> "通知"
            }
        }
        val raw = rec.note.ifBlank { rec.text }
        return "$from：${raw.replace(Regex("\\s+"), " ").take(80)}"
    }
}
