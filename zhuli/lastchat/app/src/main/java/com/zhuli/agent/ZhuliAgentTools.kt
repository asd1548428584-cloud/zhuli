package com.zhuli.agent

import android.content.Context
import com.zhuli.core.ExtractPipeline
import com.zhuli.core.InboxDatabase
import com.zhuli.core.InboxRecord
import com.zhuli.core.Ledger
import com.zhuli.core.ReminderEngine
import com.zhuli.core.Tools
import com.zhuli.core.VectorSearch
import com.zhuli.memory.EmbeddingProvider
import com.zhuli.memory.EntityKind
import com.zhuli.memory.FactDraft
import com.zhuli.memory.FactKind
import com.zhuli.memory.HashEmbeddingProvider
import com.zhuli.memory.MemoryRepo
import com.zhuli.memory.SourceType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Agent 可用的真实工具集。
 *
 * 和旧 [Tools.run] 的分工：
 * - 旧聊天链路继续用 Tools.run（已验证，不动）；
 * - Agent 链路用这里的实现，**不调用 ActionConfirm** —— 弹窗由 [PolicyEngine] + AgentLoop
 *   的 WAITING_APPROVAL 统一负责，否则用户会被问两遍。
 *
 * 每个工具都声明了输入（JSON Schema）、风险、同类授权键；所有执行都在这里，
 * 没有「模型说一句就调系统 API」的旁路。
 */
object ZhuliAgentTools {

    fun build(
        ctx: Context,
        db: InboxDatabase,
        memory: MemoryRepo,
        store: AgentStore,
        /**
         * 向量源是**惰性读取**的：Gemma 可能中途装好/被删，绑定成常量会让索引空间和查询空间
         * 悄悄错位。宿主传 `{ gate.active() }` 即可。
         */
        embedder: suspend () -> EmbeddingProvider = { HashEmbeddingProvider() },
    ): ToolRegistry {
        fun args(raw: String) = try { JSONObject(raw.ifBlank { "{}" }) } catch (t: Throwable) { JSONObject() }
        fun fmt(ms: Long) = SimpleDateFormat("M-d HH:mm", Locale.CHINA).format(Date(ms))

        val list = listOf(
            RegisteredTool(
                ToolSpec(
                    "search_memory", "在用户的本地记忆库检索通知、短信、链接摘要与备忘。问过去发生的事时用。",
                    """{"type":"object","properties":{"query":{"type":"string"},"days":{"type":"integer"}},"required":["query"]}""",
                ),
                Risk.READ_ONLY, "search",
            ) { raw ->
                val a = args(raw)
                val q = a.optString("query").ifBlank { return@RegisteredTool ToolResult.fail("没给关键词") }
                val days = a.optInt("days", 7).coerceIn(1, 365)
                val now = System.currentTimeMillis()
                val hits = VectorSearch.search(db, q, now - days * 86_400_000L, now, 6)
                ToolResult.ok(
                    if (hits.isEmpty()) "最近 $days 天没有找到与「$q」相关的记录。"
                    else hits.joinToString("\n") { "· ${fmt(it.receivedAt)} ${it.appLabel.ifBlank { it.source }}：${(it.note.ifBlank { it.text }).take(60)}" }
                )
            },

            RegisteredTool(
                ToolSpec(
                    "search_facts", "查结构化事实：人物、取件码、金额、会议时间、地址、电话。比 search_memory 更精确，用户问具体值时优先用。",
                    """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""",
                ),
                Risk.READ_ONLY, "search",
            ) { raw ->
                val q = args(raw).optString("query").ifBlank { return@RegisteredTool ToolResult.fail("没给关键词") }
                val hits = memory.hybridSearch(q, embedder(), System.currentTimeMillis(), 8)
                ToolResult.ok(
                    if (hits.isEmpty()) "没有找到相关事实。"
                    else hits.joinToString("\n") { "· ${it.fact.display}（来源：${it.fact.sourceType} #${it.fact.sourceId}，${(it.reasons).joinToString("+")}）" }
                )
            },

            RegisteredTool(
                ToolSpec("list_todo", "列出接下来有时限的待办与提醒。", """{"type":"object","properties":{}}"""),
                Risk.READ_ONLY, "search",
            ) { _ ->
                val rows = db.upcoming(20).distinctBy { it.deadlineAt to it.note }.take(10)
                ToolResult.ok(
                    if (rows.isEmpty()) "眼下没有有时限的待办。"
                    else rows.joinToString("\n") { "· ${fmt(it.deadlineAt)}｜${it.note.ifBlank { it.text }.take(40)}" }
                )
            },

            RegisteredTool(
                ToolSpec(
                    "ledger_summary", "统计最近若干天的支出与收入合计及分类明细。",
                    """{"type":"object","properties":{"days":{"type":"integer"}},"required":[]}""",
                ),
                Risk.READ_ONLY, "search",
            ) { raw ->
                val days = args(raw).optInt("days", 7).coerceIn(1, 365)
                val now = System.currentTimeMillis()
                val by = db.ledgerCategoryBreakdown(now - days * 86_400_000L, now)
                ToolResult.ok(
                    if (by.isEmpty()) "最近 $days 天没有记账记录。"
                    else {
                        val exp = by.filter { it.first != Ledger.DIR_INCOME }
                        val inc = by.filter { it.first == Ledger.DIR_INCOME }
                        "最近 $days 天：支出 ¥${money(exp.sumOf { it.third.second })}" +
                            (if (inc.isNotEmpty()) "，收入 ¥${money(inc.sumOf { it.third.second })}" else "") +
                            "\n" + by.take(6).joinToString("\n") { "· ${it.first} ${it.second} ¥${money(it.third.second)}" }
                    }
                )
            },

            RegisteredTool(
                ToolSpec("list_courier", "查待取快递的取件码与驿站。", """{"type":"object","properties":{}}"""),
                Risk.READ_ONLY, "search",
            ) { _ ->
                val (pending, signed) = Tools.pickCourierRows(db.recentByCategory(InboxRecord.CAT_EXPRESS, 50), System.currentTimeMillis())
                ToolResult.ok(Tools.courierAnswer(pending, signed, System.currentTimeMillis()))
            },

            RegisteredTool(
                ToolSpec("list_tasks", "查看助理当前执行的任务与进度。", """{"type":"object","properties":{}}"""),
                Risk.READ_ONLY, "search",
            ) { _ ->
                val rows = store.recent(10)
                ToolResult.ok(
                    if (rows.isEmpty()) "当前没有任务记录。"
                    else rows.joinToString("\n") { "· ${it.id.take(8)} ${it.status}｜${it.goal.take(30)}" }
                )
            },

            RegisteredTool(
                ToolSpec(
                    "save_memory", "把用户明确说要记住的内容存进本地记忆库。只在用户说「记住/记一下」时调用。",
                    """{"type":"object","properties":{"text":{"type":"string"},"title":{"type":"string"}},"required":["text"]}""",
                ),
                Risk.WRITE_LOCAL, "save_memory",
            ) { raw ->
                val a = args(raw)
                val text = a.optString("text").ifBlank { return@RegisteredTool ToolResult.fail("没给内容") }
                val f = ExtractPipeline.extract(text)
                val now = System.currentTimeMillis()
                if (db.hasSameTextSince(text, now - 30 * 60_000L, now + 1_000L)) {
                    ToolResult.ok("这条刚才已经记过了，没有重复存。")
                } else {
                    val id = db.insert(
                        InboxRecord(
                            source = InboxRecord.SOURCE_CHAT,
                            packageName = ctx.packageName,
                            appLabel = "备忘",
                            title = a.optString("title").ifBlank { "备忘" },
                            text = text,
                            receivedAt = now,
                            pendingExtract = 0,
                            category = f.category,
                            deadlineAt = f.deadlineTs,
                            amount = f.amount,
                            note = f.note.ifBlank { text.take(30) },
                        )
                    )
                    val n = memory.ingest(
                        listOf(FactDraft("我", "备忘", text.take(80), FactKind.TODO, 0.9, text.take(200))),
                        SourceType.USER, id, now,
                    )
                    ToolResult.ok("已存到本地记忆库（inbox=$id，事实 $n 条）：${text.take(30)}")
                }
            },

            RegisteredTool(
                ToolSpec(
                    "save_fact", "保存一条结构化事实（主体、字段、值）。用户纠正已有事实时不要用这个，请用 correct_fact。",
                    """{"type":"object","properties":{"subject":{"type":"string"},"predicate":{"type":"string"},"value":{"type":"string"}},"required":["subject","predicate","value"]}""",
                ),
                Risk.WRITE_LOCAL, "save_fact",
            ) { raw ->
                val a = args(raw)
                val s = a.optString("subject").ifBlank { return@RegisteredTool ToolResult.fail("没给主体") }
                val p = a.optString("predicate").ifBlank { return@RegisteredTool ToolResult.fail("没给字段名") }
                val v = a.optString("value").ifBlank { return@RegisteredTool ToolResult.fail("没给值") }
                val now = System.currentTimeMillis()
                val n = memory.ingest(
                    listOf(FactDraft(s, p, v, FactKind.OTHER, 0.9, "用户在对话中明确说出", EntityKind.OTHER)),
                    SourceType.USER, 0L, now,
                )
                ToolResult.ok("已保存事实：$s｜$p｜$v（新增 $n 条）")
            },

            RegisteredTool(
                ToolSpec(
                    "correct_fact", "纠正一条已有事实。用户说「不是 X 是 Y」时必须用这个，保留历史。",
                    """{"type":"object","properties":{"fact_id":{"type":"integer"},"new_value":{"type":"string"}},"required":["fact_id","new_value"]}""",
                ),
                Risk.WRITE_LOCAL, "save_fact",
            ) { raw ->
                val a = args(raw)
                val id = a.optLong("fact_id")
                val v = a.optString("new_value").ifBlank { return@RegisteredTool ToolResult.fail("没给新值") }
                if (memory.correct(id, v, System.currentTimeMillis()) == null) ToolResult.fail("事实 #$id 不存在")
                else ToolResult.ok("已纠正事实 #$id（旧值保留在历史里）")
            },

            RegisteredTool(
                ToolSpec(
                    "create_reminder", "给用户建提醒。只有用户明确说了具体时间才调用；会请求用户确认。",
                    """{"type":"object","properties":{"title":{"type":"string"},"when":{"type":"string"}},"required":["title","when"]}""",
                ),
                // 会改系统闹钟/日历，属于「重大日程变更」——按需求每次都要确认，不吃同类免问
                Risk.WRITE_EXTERNAL, "create_reminder",
            ) { raw ->
                val a = args(raw)
                val title = a.optString("title").ifBlank { "提醒" }
                val whenText = a.optString("when")
                val due = ExtractPipeline.parseDeadline("$whenText $title", Calendar.getInstance())
                when {
                    due <= 0L -> ToolResult.fail("没听懂时间（「$whenText」），请让用户说清楚具体时间。")
                    db.similarScheduledReminder(due, title) != null -> ToolResult.ok("同一时间已有相同提醒，没有重复创建。")
                    else -> {
                        val now = System.currentTimeMillis()
                        val id = db.insert(
                            InboxRecord(
                                source = InboxRecord.SOURCE_CHAT, packageName = ctx.packageName,
                                appLabel = "助理", title = "提醒", text = "$title（$whenText）",
                                receivedAt = now, pendingExtract = 0, category = "生活",
                                deadlineAt = due, note = title,
                            )
                        )
                        ReminderEngine.scanAsync(ctx)
                        ToolResult.ok("已建提醒：$title，${fmt(due)}（inbox=$id）")
                    }
                }
            },
        )
        return ToolRegistry(list)
    }

    private fun money(v: Double) = if (v == v.toLong().toDouble()) "${v.toLong()}" else String.format(Locale.US, "%.2f", v)
}
