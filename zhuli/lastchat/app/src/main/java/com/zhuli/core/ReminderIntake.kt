package com.zhuli.core

import java.util.regex.Pattern

/**
 * 对话里直接下提醒（2026-11-21 真机抓到的问题）。
 *
 * ## 现场
 * 在 Z70 Ultra 上说「3 分钟后提醒我喝水」，助理回的是
 * 「眼下没有明确的待办。有期限的事可以直接丢给我，比如「周五下午三点交材料」。」
 * —— `Q_TODO` 的正则里包含「提醒」，这句话被当成**查询待办**，建提醒的意图整个丢了。
 * 而 `ExtractPipeline.parseDeadline` 其实认得「N 分钟后」（P_AFTER），
 * `ReminderConfidence` 也把它算作高置信。缺的只是路由这一环。
 *
 * ## 定法
 * 先认「让我做提醒」的说法（提醒我 / 叫我 / 记得提醒），且**必须解析出未来时间**才算建提醒；
 * 解析不出就退回原来的查询/闲聊分支，不要为了显得能干而编一个时间。
 *
 * 这里只放纯函数（标题清洗 + 回话文案），Context 相关的落库与排闹钟留在调用方 ——
 * 与 `SelfCheck` 同一套做法：能单测的判断别挪到机器上才发现。
 */
object ReminderIntake {
    /** 「提醒我 / 叫我 / 记得提醒 / 帮我记着提醒」这类祈使说法 */
    private val CUE = Pattern.compile("(?:请)?(?:你)?(?:帮我)?(?:记得)?(?:提醒一下我?|提醒我|叫我|记得提醒)")

    /** 句首的时间短语：「3分钟后」「半小时以后」「两个小时后」 */
    private val LEAD_TIME = Pattern.compile(
        "^(?:在)?\\s*(?:\\d{1,3}|[一二两三四五六七八九十]{1,3})\\s*个?\\s*(?:分钟|分|小时|钟头)\\s*(?:后|以后|之后)"
    )

    /** 句首的日期词，标题里不必重复（「明天提醒我交材料」→「交材料」） */
    private val LEAD_DATE = Pattern.compile("^(?:今天|明天|后天|大后天|今晚|明早|明晚)\\s*")

    /** 句首的钟点：「晚上八点叫我给妈妈打电话」→「给妈妈打电话」 */
    private val LEAD_CLOCK = Pattern.compile(
        "^(?:上午|下午|晚上|中午|早上|凌晨)?\\s*(?:\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*" +
            "(?:[:：]\\s*\\d{1,2}|点(?:\\s*(?:半|\\d{1,2}\\s*分?))?)"
    )

    private val PUNCT = Pattern.compile("[\\s\\p{Punct}。，！？；：、“”‘’（）《》]+")

    /**
     * 从原话里削出提醒标题。
     *
     * 说不出来就返回「提醒」—— 宁可标题朴素，也不要拿整句当标题塞进通知栏。
     */
    fun titleOf(q: String): String {
        var s = q.trim()
        s = LEAD_TIME.matcher(s).replaceFirst("")
        s = LEAD_DATE.matcher(s).replaceFirst("")
        s = LEAD_CLOCK.matcher(s).replaceFirst("")
        s = CUE.matcher(s).replaceAll("")
        s = s.trim().trim('，', ',', '。', '.', '、', ' ')
        // 「提醒我」后面常跟一个「要/该」：「提醒我要买菜」
        s = s.removePrefix("要").removePrefix("该").trim()
        if (s.isEmpty()) return "提醒"
        return if (s.length > 30) s.take(30) else s
    }

    /**
     * 回话：只说库里真发生了什么。
     *
     * @param whenText 已格式化好的时间（如「11-21 11:50」）
     * @param level [ReminderConfidence] 的三档
     * @param basis 判定依据
     */
    fun replyOf(whenText: String, level: String, basis: String, title: String): String = when (level) {
        ReminderConfidence.HIGH ->
            "好，$whenText 叫你：$title。\n（依据：$basis｜到点会响，可在「提醒检查」里改）"
        ReminderConfidence.MEDIUM ->
            "记下了：$whenText · $title。\n这条时间我说不准（$basis），先放进「待确认」，你点一下确认我才排闹钟。"
        else ->
            "这个时间已经过去了，我没排。要不要换个说法，比如「明天上午九点提醒我$title」？"
    }
}

/**
 * 快递取件提醒的置信度口径（2026-11-21 用户拍板）。
 *
 * ## 问题
 * 取件短信里没有「几点去取」，deadline 是 `Express.pickupDue(到站 + 2h)` 推出来的。
 * 于是 `ReminderConfidence` 判「时间来源不明」→ 中置信 → 进「待确认」不响。
 * 可快递取件恰恰是主力场景，每次都要手点一下确认，等于主力功能没通电。
 *
 * ## 定法（用户选 A）
 * **识别到取件码**的快递行直接算高置信，依据如实写「按到站 +2h 推定」，不假装那是短信里写的。
 * 没码的（只有「已派送」之类）仍走原判进待确认 —— 码是取件这件事的硬标志，没码就不确定要不要去。
 *
 * 抽成纯函数：`Express.parse` 的价格在于词表，这里只判断「有没有码」和「依据怎么写」。
 */
object CourierPolicy {

    /**
     * @param code `Express.parse(text).code`，没识别到就传空/null
     * @return 高置信依据；null 表示这条不该提级（交给 [ReminderConfidence] 原判）
     */
    fun basisOf(code: String?): String? {
        val c = code?.trim().orEmpty()
        if (c.isEmpty()) return null
        return "快递到站（取件码 $c，按到站 +2h 推定）"
    }
}
