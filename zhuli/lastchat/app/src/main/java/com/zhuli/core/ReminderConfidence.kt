package com.zhuli.core

import java.util.regex.Pattern

/**
 * 提醒置信度三级（T-D1，附录 C）。
 *
 * 解决的问题（D10）：以前只要从文本里解析出未来时间就自动建闹钟，
 * 「下周给我」「明天左右」这种含糊话也会变成准点响的闹钟 —— 误报一次对信任的损害大于漏报一次。
 *
 * 口径（§11-4 已确认：照附录 C 原样）：
 * - **高**：明确日期 + 具体时刻（「周三下午三点评审」「2 小时后」）→ 自动建提醒
 * - **中**：时间能解析但语义含糊（约/左右/下周/月底前），或只有日期没时刻、只有时刻没日期
 *   → 进「待确认」，不排闹钟、不打扰，等用户一键确认后再建
 * - **低**：无法确定未来性 / 已过期 → 只入库，不提醒
 *
 * 纯逻辑、无 Android 依赖，可直接 JVM 单测。
 */
object ReminderConfidence {

    const val HIGH = "high"
    const val MEDIUM = "medium"
    const val LOW = "low"

    /** level + 判定依据（依据会存进库，方便日后回顾误报时看清"当时为什么这么判"）。 */
    data class Verdict(val level: String, val basis: String)

    /** 「N 分钟后 / N 小时后 / 一个小时后」——相对当下，最不含糊 */
    private val RELATIVE = Pattern.compile(
        "(\\d{1,3}|[一二两三四五六七八九十]{1,3})\\s*个?\\s*(分钟|分|小时|钟头)\\s*(?:后|以后|之后)"
    )

    /** 具体时刻：下午三点 / 15:30 / 晚上 8 点半 */
    private val CLOCK = Pattern.compile(
        "(?:上午|下午|晚上|中午|早上|凌晨)?\\s*(?:\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*" +
            "(?:[:：]\\s*\\d{1,2}|点(?:\\s*(?:半|\\d{1,2}\\s*分?))?)"
    )

    /** 明确日期：11月21日 / 周五 / 明天 / 2026-11-20 / 11-20 */
    private val DATE = Pattern.compile(
        "\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]|周[一二三四五六日天]|星期[一二三四五六日天]|" +
            "明天|后天|大后天|今晚|明早|明晚|今天|" +
            // 数字日期：工具创建提醒时会把时间规范成「关灯（11-20 21:17）」，这里不认就会误判成「只有时刻」
            "\\d{4}\\s*[-/]\\s*\\d{1,2}\\s*[-/]\\s*\\d{1,2}|\\d{1,2}\\s*[-/]\\s*\\d{1,2}"
    )

    /** 含糊表述：有它就不要自动建闹钟 */
    private val VAGUE = Pattern.compile(
        "左右|前后|差不多|大概|估计|月底前|月初|下周|下个月|回头|尽快|有空|找个时间|这两天|过几天|晚点|稍后"
    )

    fun evaluate(text: String, deadlineAt: Long, now: Long = System.currentTimeMillis()): Verdict {
        if (deadlineAt <= now) return Verdict(LOW, "时间不在未来")
        val t = text

        // 相对时刻最可靠，优先判高
        if (RELATIVE.matcher(t).find()) return Verdict(HIGH, "相对时刻（N 分钟后）")
        // 含糊词优先于「看起来完整」的日期时刻：「明天左右三点」这种仍然算中
        if (VAGUE.matcher(t).find()) return Verdict(MEDIUM, "时间表述含糊（约/左右/下周类）")

        val hasDate = DATE.matcher(t).find()
        val hasClock = CLOCK.matcher(t).find()
        return when {
            hasDate && hasClock -> Verdict(HIGH, "明确日期+时刻")
            hasDate -> Verdict(MEDIUM, "只有日期、没有具体时刻")
            hasClock -> Verdict(MEDIUM, "只有时刻、没有日期")
            else -> Verdict(MEDIUM, "时间来源不明")
        }
    }
}
