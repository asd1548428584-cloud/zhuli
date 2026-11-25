package com.zhuli.bridge

/**
 * 快递回答的纯函数层（可 JVM 单测）。
 *
 * 背景（T-G1 真库发现的真实缺陷）：
 * zhuli 现有 Tools.run 的 list_courier 用 `deadlineAt > now` 过滤，但真机库里 8 条快递
 * 有 7 条 deadlineAt=0（无期限）、1 条已过期 —— 结果"我快递到哪了"明明库里有 8 条取件码，
 * 工具却答"眼下没有待取的快递"。这是注入 LastChat 前必须修的口径问题。
 *
 * 修正口径（bridge 侧，不动 zhuli 主项目）：
 * - 未签收的取件通知，按 received_at 倒序，取近 14 天；
 * - deadlineAt>now 的排前面（真正有时限的待取）；无期限的也算待取（真机就是这样）；
 * - 含"签收"字样的归入已签收，只报个数；
 * - 答案带取件码/地点，来自真实库原文，不编造。
 */
object CourierAnswer {

    /** 一条最小快递记录（与 com.zhuli.core.InboxRecord 的快递相关字段对齐）。 */
    data class Row(
        val note: String,
        val receivedAt: Long,
        val deadlineAt: Long,
        val text: String = "",
    )

    private fun fmt(ms: Long): String {
        if (ms <= 0L) return ""
        val d = java.util.Date(ms)
        return java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.getDefault()).format(d)
    }

    /**
     * 把快递记录列表组装成回答。
     * @param rows 近 14 天 category=快递 的记录（调用方查库时保证）
     * @param nowMs 当前时刻
     */
    fun answer(rows: List<Row>, nowMs: Long): String {
        if (rows.isEmpty()) {
            return "还没有快递记录。收到取件短信、或在菜鸟/丰巢/顺丰 App 收到到站推送，我会自动记下来并提醒你去取。"
        }
        val signed = rows.filter { it.note.contains("签收") || it.text.contains("签收") }
        val pending = rows.filterNot { it.note.contains("签收") || it.text.contains("签收") }
        // 有真实时限的排前，其余按到站时间倒序
        val ordered = pending.sortedWith(
            compareByDescending<Row> { it.deadlineAt > nowMs }.thenByDescending { it.receivedAt }
        )
        return buildString {
            if (ordered.isEmpty()) {
                append("近 14 天的快递都已签收。")
            } else {
                append("近 14 天有 ${ordered.size} 件快递待取：\n")
                for (r in ordered.take(8)) {
                    append("· ").append(r.note.replace(Regex("^快递·"), "")).append("｜").append(fmt(r.receivedAt)).append(" 到站\n")
                }
                if (ordered.size > 8) append("（还有 ${ordered.size - 8} 件，略）\n")
            }
            if (signed.isNotEmpty()) append("\n已签收 ${signed.size} 件。")
        }.trim()
    }
}
