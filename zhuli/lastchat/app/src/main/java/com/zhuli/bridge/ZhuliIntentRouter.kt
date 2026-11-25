package com.zhuli.bridge

import android.content.Context
import com.zhuli.bridge.ZhuliQueryAnswer

/**
 * T-G2/B8 发热根治：意图分流 + 模板回答。
 *
 * 查询类对话（快递/待办/账本）不走 LLM 推理——直接查 zhuli 本地库 + 模板生成回答：
 * 零模型推理 = 零发热 = 即时响应。数据源与 Tool 相同（fork 自持 zhuli_inbox.db）。
 * 未命中的意图返回 null，照常走模型（云/本地）。
 */
object ZhuliIntentRouter {

    private val COURIER_KEYWORDS = listOf("快递", "取件", "包裹", "物流", "驿站", "件到哪")
    private val TODO_KEYWORDS = listOf("待办", "提醒我有哪些", "有什么提醒", "日程", "接下来要做什么", "今天的安排", "今天要做什么")
    private val LEDGER_KEYWORDS = listOf("花了", "花销", "账单", "收支", "消费", "记账", "花了多少", "花费")

    enum class Intent { COURIER, TODO, LEDGER }

    private const val PREFS = "zhuli_intent"
    private const val K_PENDING = "pending_intent"
    private const val K_PENDING_AT = "pending_at"
    private val CONFIRM_WORDS = listOf("确认", "查吧", "查一下", "是的", "好", "嗯", "要", "查")
    private val REJECT_WORDS = listOf("不查", "别查", "算了", "不用", "取消", "不对", "不是")
    private const val PENDING_TTL_MS = 30 * 60 * 1000L

    /**
     * 两跳确认式：命中意图先回确认问句（不执行）；用户回复确认词才真正查询。
     * 人工把关，避免意图分错；30 分钟未确认自动过期。
     */
    fun tryReply(context: Context, lastUserText: String): String? {
        val text = lastUserText.trim()
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = sp.getString(K_PENDING, null)
        val pendingAt = sp.getLong(K_PENDING_AT, 0)
        if (pending != null && System.currentTimeMillis() - pendingAt < PENDING_TTL_MS) {
            val confirmed = CONFIRM_WORDS.any { text == it || text.startsWith(it) }
            val rejected = REJECT_WORDS.any { text.contains(it) }
            if (confirmed) {
                sp.edit().remove(K_PENDING).remove(K_PENDING_AT).apply()
                return runCatching {
                    when (Intent.valueOf(pending)) {
                        Intent.COURIER -> replyCourier(context)
                        Intent.TODO -> replyTodo(context)
                        Intent.LEDGER -> replyLedger(context)
                    }
                }.getOrNull()
            }
            if (rejected) {
                sp.edit().remove(K_PENDING).remove(K_PENDING_AT).apply()
                return "好的，不查了。想聊什么直接说。"
            }
        } else if (pending != null) {
            sp.edit().remove(K_PENDING).remove(K_PENDING_AT).apply()
        }
        val intent = match(text) ?: return null
        sp.edit().putString(K_PENDING, intent.name).putLong(K_PENDING_AT, System.currentTimeMillis()).apply()
        return when (intent) {
            Intent.COURIER -> "要我查一下你当前的快递吗（取件码/到站时间）？回复「确认」即查。"
            Intent.TODO -> "要我查一下你的待办提醒吗？回复「确认」即查。"
            Intent.LEDGER -> "要我查一下近 7 天的收支小结吗？回复「确认」即查。"
        }
    }

    fun match(text: String): Intent? = when {
        COURIER_KEYWORDS.any { text.contains(it) } -> Intent.COURIER
        LEDGER_KEYWORDS.any { text.contains(it) } -> Intent.LEDGER
        TODO_KEYWORDS.any { text.contains(it) } -> Intent.TODO
        else -> null
    }

    private fun replyCourier(context: Context): String {
        val now = System.currentTimeMillis()
        val all = ZhuliCourierTool(context).queryCourier(now)
        if (all.isEmpty()) return "最近 14 天没有查到快递记录。"
        // 真机实测：库里 8 条里有已签收的，全列出来会让用户以为都要去取
        val pending = all.filterNot { it.note.contains("签收") || it.text.contains("签收") }
        if (pending.isEmpty()) return "近 14 天的 ${all.size} 条快递都已签收，没有待取的。"
        val preview = pending.take(5)
        return buildString {
            appendLine("📦 待取快递（近 14 天，共 ${pending.size} 件）：")
            preview.forEach { r ->
                val note = r.note.replace(Regex("^快递[·•]\\s*"), "").trim()
                // 取件码：优先抓「取件码 xxx」（支持 8-1-2233 这种带横杠的），否则退回 4-8 位纯数字
                val code = Regex("取件码[：: ]*([0-9A-Za-z][0-9A-Za-z-]{2,})").find(note)?.groupValues?.get(1)
                    ?: Regex("\\d{4,8}").find(note)?.value
                append("· ").append(note.ifBlank { r.text.take(50) })
                // 备注里已经带了码就不再重复（真机出现「取件码 6825（取件码 6825）」）
                if (code != null && !note.contains("取件码")) append("（取件码 $code）")
                if (r.deadlineAt > 0) {
                    append("，截止 ")
                    append(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA).format(java.util.Date(r.deadlineAt)))
                }
                appendLine()
            }
            if (pending.size > preview.size) {
                appendLine("（还有 ${pending.size - preview.size} 件没列，问我要完整列表就行）")
            }
        }.trim()
    }

    private fun replyTodo(context: Context): String {
        val tools = ZhuliQueryTools(context)
        val rows = tools.queryTodo(20)
        if (rows.isEmpty()) return "当前没有待办提醒，清清爽爽。"
        return buildString {
            appendLine("✅ 接下来的提醒（共 ${rows.size} 条）：")
            ZhuliQueryAnswer.todo(rows, System.currentTimeMillis(), 6)
                .lineSequence().take(7).forEach { appendLine(it) }
        }.trim()
    }

    private fun replyLedger(context: Context): String {
        val tools = ZhuliQueryTools(context)
        val groups = tools.queryLedger(7, System.currentTimeMillis())
        if (groups.isEmpty()) return "最近 7 天没有收支记录。"
        return buildString {
            appendLine("💰 近 7 天收支小结：")
            ZhuliQueryAnswer.ledger(groups, 7)
                .lineSequence().take(10).forEach { appendLine(it) }
        }.trim()
    }
}
