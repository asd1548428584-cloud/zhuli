package com.zhuli.core

/**
 * 提醒条目（第 5 步）。
 *
 * 一条 [InboxRecord] 最多对应一条提醒（UNIQUE(inbox_id)），避免重复扫描时刷出一堆闹钟。
 * 状态流转：scheduled → fired / done / canceled；snooze 只是改 due_at 后回到 scheduled。
 */
data class Reminder(
    val id: Long = 0L,
    val inboxId: Long,
    val dueAt: Long,
    val title: String,
    val body: String,
    val status: String = STATUS_SCHEDULED,
    val firedAt: Long = 0L,
    val calendarEventId: Long = 0L,
    /** 是否已经弹过通知（v7）。到点只标 fired，通知由合并器统一出，避免同刻多条各弹一次 */
    val notified: Boolean = false,
    /** 第 14 步：周期规则（daily / weekly:N / monthly:N）；空 = 一次性 */
    val repeatRule: String = "",
    /** P1 待办跟踪：被推迟过几次。>=2 时通知里会劝你换个时间 */
    val snoozeCount: Int = 0,
    /** T-D1：置信度 high / medium / low（见 [ReminderConfidence]） */
    val confidence: String = ReminderConfidence.HIGH,
    /** T-D1：判定依据，便于日后回顾误报 */
    val basis: String = ""
) {
    val isActive: Boolean get() = status == STATUS_SCHEDULED

    /** 待确认：不排闹钟，等用户一键确认 */
    val isPending: Boolean get() = status == STATUS_PENDING

    companion object {
        const val STATUS_SCHEDULED = "scheduled"
        const val STATUS_PENDING = "pending"
        const val STATUS_FIRED = "fired"
        const val STATUS_DONE = "done"
        const val STATUS_CANCELED = "canceled"
        /** T-D1：待确认放太久、时刻已过 → 归档（不删，留作误报统计） */
        const val STATUS_EXPIRED = "expired"
    }
}
