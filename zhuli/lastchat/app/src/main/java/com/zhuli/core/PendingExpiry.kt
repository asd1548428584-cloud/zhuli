package com.zhuli.core

/**
 * T-D1 验收项：待确认事项的**超期归档**规则（纯函数，可单测）。
 *
 * ## 为什么需要
 * 中置信事项（「下周给我」「明天左右」）进「待确认」，不排闹钟、不打扰，等用户点确认。
 * 但如果用户始终没点，而那个时刻已经过去好几天，这条还挂在待确认列表里：
 * - 面板只显示 20 条，陈年待确认会把新进来的挤掉；
 * - 用户看到「确认」按钮时，确认的是一个**已经过去的时间**，点了只会立刻变成一条补响。
 *
 * ## 宽限期为什么是 24 小时
 * 待确认不是打扰，用户可能一天才开一次 App；当天就到期的中置信事项（「下午三点左右」）
 * 必须留到他下次打开。给一整天的窗口，既不催也不至于无限堆着。
 * 超过宽限期归档为 `expired`（不删），误报统计仍然用得到。
 */
object PendingExpiry {

    /** 宽限期：到期后 24 小时内仍可在「待确认」里点到 */
    const val GRACE_MS = 24 * 60 * 60 * 1000L

    /**
     * 该条待确认是否已过期待归档。
     *
     * `dueAt <= 0` 视为无效数据（老库补列时可能出现），不归档 —— 宁可留着让人看见，也别静默处理掉。
     */
    fun shouldExpire(dueAt: Long, now: Long, grace: Long = GRACE_MS): Boolean =
        dueAt > 0 && now > 0 && dueAt < now - grace

    /** 给 `expirePendingBefore(before)` 用的截止点；now 非法时返回 0（= 什么都不归档）。 */
    fun cutoff(now: Long, grace: Long = GRACE_MS): Long = if (now <= 0) 0 else now - grace
}
