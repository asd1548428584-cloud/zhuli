package com.zhuli.core

import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.dmfs.rfc5545.recurrenceset.OfRule
import java.util.Calendar
import java.util.TimeZone

/**
 * 周期事项（第 14 步 / V3.1 T-D6）。
 *
 * ## 为什么换掉自研
 * 旧实现是 79 行 `Calendar.add`，只认 `daily / weekly:N / monthly:N` 三种：每 N 天、
 * 双周、工作日、排除某天、只到某日为止，全都没有。V3.1 起改用 `org.dmfs:lib-recur`
 * （Apache-2.0，RFC5545 完整实现，Etar 日历在用），对外签名保持不变。
 *
 * ## 规则串
 * 一律存 RFC5545：`FREQ=DAILY`、`FREQ=WEEKLY;BYDAY=WE`、`FREQ=MONTHLY;BYMONTHDAY=15`、
 * `FREQ=MONTHLY;BYMONTHDAY=-1`（月底）、`FREQ=DAILY;INTERVAL=2`（每两天）。
 *
 * ## 旧值兼容
 * v13 及更早的库里是 `daily / weekly:N / monthly:N`。三层兜底：
 * 1. 打开库时 [InboxDatabase] 把存量值迁成 RFC（v14）；
 * 2. [migrate] 幂等，任何入口传旧值都能就地翻译；
 * 3. [next] / [label] 内部先 migrate —— 即使从旧备份恢复进来，也不会算错。
 *
 * ## 月底口径（V3.1 §11-8，用户拍板 A）
 * `monthly:N`（N ≥ 29）迁成 `BYMONTHDAY=-1`，也就是"每月最后一天"：
 * 1/31 → 2/28 → 3/31。RFC 的 `BYMONTHDAY=31` 会**跳过**没有 31 号的月份，
 * 那样"每月 31 号"一年只响 8 次，不是用户要的。理由与取舍见
 * `zhuli_实现方案与代码骨架_V1.0.md` §2.1。
 *
 * ## 已验证
 * 用 0.17.1 真跑过：WEEKLY/BYDAY、DAILY;INTERVAL、工作日、BYMONTHDAY=-1、
 * YEARLY;BYMONTH=2;BYMONTHDAY=29 行为均符合预期（见同文档 §2.1）。
 */
object Repeat {

    /** 每天都发生。保留这个常量是为了兼容旧调用点。 */
    const val DAILY = "FREQ=DAILY"

    private const val DAY_MS = 86_400_000L

    fun isRepeat(rule: String): Boolean = rule.isNotBlank()

    // ==================== 构造（让映射只存在一处） ====================

    fun daily(): String = DAILY

    /** [dayOfWeek] 用 [Calendar] 的常量（Calendar.MONDAY=2 … SUNDAY=1） */
    fun weekly(dayOfWeek: Int): String {
        val code = when (dayOfWeek) {
            Calendar.MONDAY -> "MO"
            Calendar.TUESDAY -> "TU"
            Calendar.WEDNESDAY -> "WE"
            Calendar.THURSDAY -> "TH"
            Calendar.FRIDAY -> "FR"
            Calendar.SATURDAY -> "SA"
            else -> "SU"
        }
        return "FREQ=WEEKLY;BYDAY=$code"
    }

    /** 每月 [day] 号；≥29 按"月底口径"处理（见类注释） */
    fun monthly(day: Int): String =
        if (day >= 29) "FREQ=MONTHLY;BYMONTHDAY=-1" else "FREQ=MONTHLY;BYMONTHDAY=$day"

    /** 每 [n] 天（n ≥ 2） */
    fun everyDays(n: Int): String = if (n <= 1) DAILY else "FREQ=DAILY;INTERVAL=$n"

    /** 工作日（周一至周五） */
    fun workdays(): String = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR"

    // ==================== 旧值迁移 ====================

    /**
     * `daily / weekly:N / monthly:N` → RFC5545。已是 RFC 的原样返回（幂等）。
     * 认不出的旧值也原样返回，交给 [next] 的兜底，绝不抛。
     */
    fun migrate(legacy: String): String {
        val s = legacy.trim()
        if (s.isEmpty() || s.startsWith("FREQ=")) return s
        return when {
            s == "daily" || s == "每日" -> DAILY
            s.startsWith("weekly:") -> {
                // 旧值里是 Calendar.DAY_OF_WEEK：1=周日 … 7=周六
                val d = s.substringAfter("weekly:").toIntOrNull() ?: 1
                weekly(d)
            }
            s.startsWith("monthly:") -> {
                val d = s.substringAfter("monthly:").toIntOrNull() ?: 1
                monthly(d)
            }
            else -> s
        }
    }

    // ==================== 计算 ====================

    /**
     * 下一次发生时刻，严格晚于 [from]。
     * 时分秒沿用 [from]，因为 DTSTART 就是上一次的到点时刻。
     * 解析失败或规则无解时返回 `from + 1 天` —— 保证调用方一定向前走，
     * 不会在 [firstAfter] 里空转。
     */
    fun next(rule: String, from: Long): Long {
        val r = migrate(rule)
        if (r.isBlank()) return from
        if (!isValid(r)) return from + DAY_MS   // 坏规则退化为"明天"，绝不静默算到别的日子
        return try {
            val start = DateTime(TimeZone.getDefault(), from)
            val iter = OfRule(RecurrenceRule(r), start).iterator()
            var guard = 0
            while (iter.hasNext() && guard < 500) {
                val t = iter.next().timestamp
                guard++
                if (t > from) return t
            }
            from + DAY_MS
        } catch (t: Throwable) {
            from + DAY_MS
        }
    }

    /**
     * 规则体检。
     *
     * 为什么需要：lib-recur 对越界值**不抛错**，而是悄悄退回 DTSTART 的日。
     * 实测 `FREQ=MONTHLY;BYMONTHDAY=32` 从 11-20 起会给 11-20 / 12-20 / 01-20——
     * 也就是"每月 20 号"，与我们想要的"拒绝坏规则"完全相反。
     * 这里只挡会导致静默错算的项，不做完整 RFC 校验。
     */
    internal fun isValid(rule: String): Boolean {
        val r = rule.trim()
        if (!r.startsWith("FREQ=")) return false
        val freq = r.substringAfter("FREQ=").substringBefore(';')
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return false

        Regex("INTERVAL=(-?\\d+)").find(r)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return false
            if (n < 1) return false
        }
        Regex("BYMONTHDAY=([^;]+)").find(r)?.let { m ->
            for (v in m.groupValues[1].split(',')) {
                val d = v.toIntOrNull() ?: return false
                if (d == 0 || d > 31 || d < -31) return false
            }
        }
        Regex("BYMONTH=([^;]+)").find(r)?.let { m ->
            for (v in m.groupValues[1].split(',')) {
                val mo = v.toIntOrNull() ?: return false
                if (mo !in 1..12) return false
            }
        }
        Regex("BYDAY=([^;]+)").find(r)?.let { m ->
            val allowed = setOf("SU", "MO", "TU", "WE", "TH", "FR", "SA")
            for (v in m.groupValues[1].split(',')) {
                if (v.takeLast(2) !in allowed) return false
            }
        }
        return true
    }

    /** 从「第一次该响的时刻」开始，往后找到第一个还没过的点。 */
    fun firstAfter(rule: String, candidate: Long, now: Long = System.currentTimeMillis()): Long {
        var t = candidate
        var guard = 0
        while (t <= now && guard < 400) {
            val n = next(rule, t)
            if (n <= t) return t   // 防御：绝不原地打转
            t = n
            guard++
        }
        return t
    }

    // ==================== 说人话 ====================

    fun label(rule: String): String {
        val r = migrate(rule)
        if (r.isBlank()) return "一次性"
        val interval = Regex("INTERVAL=(\\d+)").find(r)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return when {
            r.startsWith("FREQ=DAILY") ->
                if (interval > 1) "每 $interval 天" else "每天"
            r.startsWith("FREQ=WEEKLY") -> {
                val byDay = r.substringAfter("BYDAY=", "").substringBefore(';')
                when {
                    byDay.isEmpty() -> "每周"
                    byDay == "MO,TU,WE,TH,FR" -> "工作日"
                    byDay.contains(",") -> "每周 $byDay"
                    else -> (if (interval == 2) "隔周" else "每周") + weekdayCn(byDay)
                }
            }
            r.startsWith("FREQ=MONTHLY") -> {
                val d = r.substringAfter("BYMONTHDAY=", "").substringBefore(';')
                if (d == "-1") "每月最后一天" else "每月 $d 号"
            }
            r.startsWith("FREQ=YEARLY") -> "每年"
            else -> r
        }
    }

    private fun weekdayCn(code: String): String = when (code.take(2)) {
        "SU" -> "日"
        "MO" -> "一"
        "TU" -> "二"
        "WE" -> "三"
        "TH" -> "四"
        "FR" -> "五"
        "SA" -> "六"
        else -> "?"
    }
}
