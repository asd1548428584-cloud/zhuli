package com.zhuli.core

import android.content.Context
import com.zhuli.core.ZhuliLog as Log

/**
 * 通知噪声闸门（v0.9.1 补丁 · 断自激回路）。
 *
 * 为什么要有这一层（真机事故复盘 2026-11-20）：
 * 用户设了一个系统闹钟（07:30「传软件」），接下来半小时手机被弹了一百多条提醒。链路是：
 *
 *   系统闹钟/日历通知 → 通知监听器收下 → 提取出 07:30 → 建提醒
 *   → 建提醒时双写系统日历（提前 10 分钟提醒）→ 日历 App 弹通知
 *   → 又被自己的监听器收下 → 再建提醒 → 再写日历 → …… 每 10 秒一轮，越滚越多
 *   最后一刻钟内堆出 100 条同刻提醒，全部在 07:30 一起响。
 *
 * 三条闸门（缺一不可）：
 * 1. 回声源黑名单：日历 / 时钟 / 健康计步这类「自己说自己」或高频刷屏的包，直接不收；
 * 2. 文本去重 + 前缀去重：同一条通知被更新着重复上报（标题越滚越长）只留第一条；
 * 3. 频率熔断：任何包 10 分钟内最多收 20 条，超了就当回路掐掉，宁可漏也不刷屏。
 *
 * 另外提供 [purgeOnce]：把已经被回路灌进去的脏数据（含写出去的日历事件）一次性收干净。
 */
object NoiseGuard {

    private const val TAG = "zhuli.noise"

    // ==================== 回声源 ====================
    /**
     * 会把「我们自己做的事」再讲一遍的 App：
     * - 日历类：提醒双写进去的日程，日历 App 会按提前 10 分钟弹通知 → 回到监听器 → 死循环；
     * - 时钟类：用户自己的系统闹钟，本来就是手机在叫，助理不该再记一条、再叫一遍。
     */
    private val ECHO_PACKAGES = setOf(
        "com.android.calendar",
        "com.google.android.calendar",
        "com.android.providers.calendar",
        "com.zte.calendar",
        "com.huawei.calendar",
        "com.miui.calendar",
        "com.coloros.calendar",
        "zte.com.cn.alarmclock",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.coloros.alarmclock",
        "com.miui.clock",
        "com.huawei.deskclock",
        "com.sec.android.app.clockpackage"
    )

    /** 包名里带这些词的，一律按回声源处理（厂商日历/时钟改名也躲不过） */
    private val ECHO_KEYWORDS = listOf("calendar", "alarmclock", "deskclock")

    // ==================== 低信号源 ====================
    /**
     * 计步、睡眠、商店更新这类通知：每分钟刷一次，且永远不含「要我去做的事」。
     * 收进来只有两个后果——把库撑爆、把真提醒挤掉。
     */
    private val NOISE_PACKAGES = setOf(
        "android",
        "com.android.vending",
        "com.google.android.gms",
        "com.google.android.gsf"
    )

    private val NOISE_KEYWORDS = listOf("health", "hm.health", "fitness", "stepcounter")

    /**
     * 聊天类：消息本来就密，不能被频率熔断误伤（用户真可能要「记住谁说了啥」）。
     * 这类只做严格同文去重，不做前缀/频率限制。
     */
    private val CHAT_PACKAGES = setOf(
        "com.tencent.mm",
        "com.tencent.mobileqq",
        "com.tencent.wework",
        "com.alibaba.android.rimet",
        "com.ss.android.lark",
        "org.telegram.messenger"
    )

    // ==================== 闸门阈值 ====================

    /** 完全相同（包名+标题+正文）在这么久内重复上报，只留第一条 */
    private const val DUP_WINDOW_MS = 10 * 60_000L

    /** 标题是最近某条的「延长版」（通知被反复更新、内容越滚越长）也判重 */
    private const val PREFIX_WINDOW_MS = 10 * 60_000L

    /** 频率熔断：同一包在这段时间内超过 [RATE_LIMIT] 条就停收 */
    private const val RATE_WINDOW_MS = 10 * 60_000L
    private const val RATE_LIMIT = 20

    // ==================== 判定 ====================

    /**
     * 该不该丢。调用方须在后台线程、且已持有可用的 [db]（避免每条通知都开库开关）。
     *
     * @return 丢弃原因；null = 正常收下
     */
    fun dropReason(db: InboxDatabase, pkg: String, title: String, text: String): String? {
        if (pkg.isBlank()) return "空包名"
        // 用户自己定的规则优先于我们的启发式：面板里点了静音就是真的不想收
        if (db.isSourceMuted(pkg)) return "已静音该来源（来源管理里打开）"
        if (isEcho(pkg)) return "回声源（日历/时钟，收了会自己叫自己）"
        if (isNoise(pkg)) return "低信号源（计步/商店等高频刷屏）"

        val now = System.currentTimeMillis()
        try {
            if (db.hasSameSince(pkg, title, text, now - DUP_WINDOW_MS)) return "10 分钟内已收过同一条"
            if (pkg in CHAT_PACKAGES) return null   // 聊天类只做严格同文去重，不掐频率
            if (db.hasTitlePrefixSince(pkg, title, now - PREFIX_WINDOW_MS)) return "同一通知被反复更新（标题累加）"
            if (db.countFromPackageSince(pkg, now - RATE_WINDOW_MS) >= RATE_LIMIT) {
                return "10 分钟内超过 $RATE_LIMIT 条，疑似回路已掐断"
            }
        } catch (t: Throwable) {
            // 判定失败不该把通知丢掉：宁可收下
            Log.w(TAG, "dropReason query failed", t)
        }
        return null
    }

    fun isEcho(pkg: String): Boolean =
        pkg in ECHO_PACKAGES || ECHO_KEYWORDS.any { pkg.contains(it, ignoreCase = true) }

    fun isNoise(pkg: String): Boolean =
        pkg in NOISE_PACKAGES || NOISE_KEYWORDS.any { pkg.contains(it, ignoreCase = true) }

    // ==================== 自愈：清掉回路灌进来的脏数据 ====================

    /** 清理结果，给壳里显示用。 */
    data class PurgeResult(
        val inbox: Int = 0,
        val reminders: Int = 0,
        val calendarEvents: Int = 0,
        val mergedDuplicates: Int = 0
    ) {
        val touched: Boolean get() = inbox > 0 || reminders > 0 || mergedDuplicates > 0
    }

    private const val K_PURGED = "noise_purged_v1"

    /** 只跑一次（装完新版开 App 时自动收尾）。 */
    fun purgeOnce(ctx: Context): PurgeResult {
        val sp = ctx.applicationContext.getSharedPreferences("zhuli_prefs", Context.MODE_PRIVATE)
        if (sp.getBoolean(K_PURGED, false)) return PurgeResult()
        val r = try { purge(ctx) } catch (t: Throwable) {
            Log.e(TAG, "purge failed", t)
            return PurgeResult()
        }
        sp.edit().putBoolean(K_PURGED, true).apply()
        Log.i(TAG, "purge done inbox=${r.inbox} reminders=${r.reminders} cal=${r.calendarEvents} merged=${r.mergedDuplicates}")
        return r
    }

    /** 不判断开关，直接清（壳里「清理噪声」按钮也走这里）。 */
    fun purge(ctx: Context): PurgeResult {
        val app = ctx.applicationContext
        val db = InboxDatabase(app)
        var reminders = 0
        var events = 0
        var inbox = 0
        try {
            // ① 回声源/低信号源的收件行
            val ids = db.idsByPackageRules(ECHO_PACKAGES, ECHO_KEYWORDS + NOISE_KEYWORDS, NOISE_PACKAGES)
            if (ids.isNotEmpty()) {
                // 先撤闹钟、删我们写出去的日历事件，再删提醒行
                for (rem in db.remindersByInboxIds(ids)) {
                    try { ReminderEngine.cancelAlarm(app, rem.id) } catch (_: Throwable) {}
                    try { ReminderNotifier.cancel(app, rem.id) } catch (_: Throwable) {}
                    if (rem.calendarEventId > 0) {
                        try { CalendarWriter.remove(app, rem); events++ } catch (_: Throwable) {}
                    }
                    reminders++
                }
                db.deleteRemindersByInboxIds(ids)
                inbox = db.deleteInboxByIds(ids)
            }
            // ② 同刻同标题的重复提醒（同一段文字被反复推进来留下的副本）只留最新一条
            val merged = mergeDuplicateReminders(app, db)
            return PurgeResult(inbox, reminders, events, merged)
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
    }

    /** 同一时刻 + 标题前 12 字相同的待响提醒，只保留 id 最小的一条，其余撤掉。 */
    private fun mergeDuplicateReminders(ctx: Context, db: InboxDatabase): Int {
        val all = db.scheduledReminders(500)
        val keep = HashMap<String, Reminder>()
        var removed = 0
        for (rem in all) {
            val key = "${rem.dueAt / 60_000}|${normTitle(rem.title)}"
            val first = keep[key]
            if (first == null) { keep[key] = rem; continue }
            try { ReminderEngine.cancelAlarm(ctx, rem.id) } catch (_: Throwable) {}
            try { ReminderNotifier.cancel(ctx, rem.id) } catch (_: Throwable) {}
            if (rem.calendarEventId > 0) {
                try { CalendarWriter.remove(ctx, rem) } catch (_: Throwable) {}
            }
            db.setReminderStatus(rem.id, Reminder.STATUS_CANCELED)
            removed++
        }
        return removed
    }

    private fun normTitle(s: String): String =
        s.replace(Regex("\\s+"), "").take(12)

    // ==================== 建提醒前的护栏（ReminderEngine.scan 调用） ====================

    /** 这条消息压根不该变成提醒：回声/噪声源，或正文里只是在报时（系统闹钟文案）。 */
    fun shouldNotRemind(rec: InboxRecord): Boolean {
        if (isEcho(rec.packageName) || isNoise(rec.packageName)) return true
        val t = rec.title + rec.text
        // 系统闹钟文案：「闹钟(预定的闹钟) 周五07:30」——用户自己的闹钟已经在响，不需要助理再叫
        return t.contains("预定的闹钟") || t.contains("闹钟(预定")
    }
}
