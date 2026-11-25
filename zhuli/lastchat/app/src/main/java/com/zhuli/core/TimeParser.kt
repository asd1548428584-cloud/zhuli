package com.zhuli.core

import com.xkzhangsan.time.nlp.TimeNLP
import com.xkzhangsan.time.nlp.TimeNLPUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 中文时间解析（V3.1 T-D7）。
 *
 * ## 它只做一件事
 * 「文本 → 候选时刻」。过去时间纠正、只有日期没时刻的兜底、歧义降置信，
 * 全都留在 [ExtractPipeline]——那里才知道"这是提醒"还是"这是记录"。
 *
 * ## 为什么先用它兜底、不直接替掉正则
 * 自研正则（`ExtractPipeline` 里 15 条）已经被真机 20 条实弹样本验过，
 * 而 xk-time 是复旦 Time-NLP 的 Java8 重写，覆盖更广但口味不同
 * （比如它更愿意把"三点"解析成凌晨 3 点）。
 * 所以当前策略：**正则先算，算不出来才问它**。等评测集（60 条）跑完再决定是否交换优先级。
 *
 * ## 许可与依赖
 * Apache-2.0，Maven Central 3.2.4；用 java.time，minSdk 24 需 coreLibraryDesugaring。
 * 台账见 `10_架构与决策/复用台账.md`。
 */
object TimeParser {

    data class Hit(
        /** 解析出的时刻（epoch millis） */
        val at: Long,
        /** 只说了日期、没说具体时刻（"下周三""月底"） */
        val allDay: Boolean,
        /** 命中的原文片段，用于"凭什么"展示 */
        val matched: String,
        /** 是否在基准时间之后 */
        val future: Boolean
    )

    /**
     * 解析出全部候选，按出现顺序。解析失败一律返回空表，绝不抛给调用方。
     * @param base 基准时间（"明天"相对于哪天），默认现在
     */
    fun parse(text: String, base: Long = System.currentTimeMillis()): List<Hit> {
        if (text.isBlank()) return emptyList()
        return try {
            TimeNLPUtil.parse(text, baseString(base)).mapNotNull { it.toHit(base) }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /** 只取第一个未来时刻；没有就 null（交给上层判"低置信/不建提醒"）。 */
    fun firstFuture(text: String, base: Long = System.currentTimeMillis()): Hit? =
        parse(text, base).firstOrNull { it.future }

    /** 第一个候选，不管过去未来（部分场景要展示"识别到的时间"）。 */
    fun first(text: String, base: Long = System.currentTimeMillis()): Hit? =
        parse(text, base).firstOrNull()

    private fun TimeNLP.toHit(base: Long): Hit? {
        val t = this.time?.time ?: return null
        return Hit(
            at = t,
            allDay = this.isAllDayTime ?: false,      // Boolean?，可空
            matched = this.timeExpression.orEmpty(),  // 注意不是 getText()
            future = t > base
        )
    }

    private fun baseString(ms: Long): String =
        // 注意：xk-time 的基准时间格式是 `yyyy-MM-dd-HH-mm-ss`（连字符，不是空格+冒号）。
        // 用 "yyyy-MM-dd HH:mm:ss" 会抛 NumberFormatException，被 catch 吞掉后表现为"什么都解析不出"，
        // 排查时极难发现——这一条已用真跑钉死，见 TimeParserTest。
        SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.getDefault()).format(Date(ms))
}
