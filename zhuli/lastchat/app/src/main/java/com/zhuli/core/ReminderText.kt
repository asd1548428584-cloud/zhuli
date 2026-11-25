package com.zhuli.core

/**
 * 提醒文案拼装（纯逻辑，可 JVM 单测）。
 *
 * 抽出来的原因：这两行文案藏在对 Android 通知的调用里，只能上真机拧着看；
 * T-C4 要求「点两次稍后，第三次通知标题要带已推迟 N 次」，可这条路径要等 20 分钟才看得见一次。
 * 把拼装逻辑拿出来，规则本身就能被单测锁住，真机只负责验"有没有照这个显示"。
 */
object ReminderText {

    /** 被推迟 ≥2 次就在标题里劝一句换个时间；1 次不啰嗦。 */
    fun snoozeSuffix(count: Int): String =
        if (count >= 2) "（已推迟 $count 次，要不要换个时间？）" else ""

    fun title(rem: Reminder, overdue: Boolean): String {
        val suffix = snoozeSuffix(rem.snoozeCount)
        return if (overdue) "⏰ 补个提醒｜${rem.title}$suffix" else "⏰ ${rem.title}$suffix"
    }

    /** 冲突提示：和已有提醒撞在半小时内，正文前置一句。 */
    fun conflictLine(otherTitle: String, minutes: Long): String =
        "⚠️ 与「${otherTitle.take(12)}」只差 $minutes 分钟"

    /**
     * "凭什么"这一行（阶段 G）。
     *
     * 通知上只写标题和时间，用户看到的就是一个凭空的结论 —— 尤其当它排错了，
     * 用户既不知道为什么，也不知道该改哪。把排它的依据带上，纠正才有落点。
     *
     * 空依据返回空串（早期版本没有 basis 字段，不硬编一句假话）；
     * 截到 60 字：通知展开区就那么大，太长会把正文挤没。
     */
    fun basisLine(rem: Reminder): String {
        val b = rem.basis.trim()
        if (b.isEmpty()) return ""
        return "依据：" + b.take(60)
    }
}
