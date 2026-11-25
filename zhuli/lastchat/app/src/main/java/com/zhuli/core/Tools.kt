package com.zhuli.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 工具层（本次重构的核心）。
 *
 * 原来的毛病：规则既判断"该查什么"又决定"怎么说"，于是 84% 的内容只会被存下来。
 * 现在规则退成**工具**，模型决定调哪个、怎么讲 —— 这就是"规则答"变成"模型答"的关键一步。
 *
 * 五个工具都只读本地库（create_reminder 会写），模型拿不到也发不出原文以外的任何东西。
 */
object Tools {

    private fun fmt(ms: Long): String =
        SimpleDateFormat("M-d HH:mm", Locale.getDefault()).format(Date(ms))

    /** 给模型看的工具声明（OpenAI tools 格式）。 */
    fun spec(): String = """
    [
      {"type":"function","function":{
        "name":"search_memory",
        "description":"在用户手机的记忆库里检索（消息、通知、链接摘要、账单）。用户问过去发生过什么、谁说过什么时用。",
        "parameters":{"type":"object","properties":{
          "query":{"type":"string","description":"关键词，2-6 个字最有效"},
          "days":{"type":"integer","description":"回看天数，默认 7"}},
          "required":["query"]}}},
      {"type":"function","function":{
        "name":"list_todo",
        "description":"列出用户接下来有时限的待办/提醒。问'接下来有什么事''有什么安排'时用。",
        "parameters":{"type":"object","properties":{}}}},
      {"type":"function","function":{
        "name":"ledger_summary",
        "description":"查账本：支出/收入合计与分类明细。问花了多少钱、这个月开销时用。",
        "parameters":{"type":"object","properties":{
          "days":{"type":"integer","description":"统计最近多少天，默认 7"}},"required":[]}}},
      {"type":"function","function":{
        "name":"list_courier",
        "description":"查待取的快递（驿站、取件码、到站时间）。问快递到了没、要取什么时用。",
        "parameters":{"type":"object","properties":{}}}},
      {"type":"function","function":{
        "name":"save_memory",
        "description":"把用户说的东西记到本地记忆库（便签/备忘/待办都行）。用户说'记住''帮我记一下''存下来'时用。数据只存手机本地。",
        "parameters":{"type":"object","properties":{
          "text":{"type":"string","description":"要记住的原文，别改写"},
          "title":{"type":"string","description":"一句话概括，15 字内"}},
          "required":["text"]}}},
      {"type":"function","function":{
        "name":"create_reminder",
        "description":"给用户建一条提醒。只有当用户明确要求提醒时才调，别自己替他加。",
        "parameters":{"type":"object","properties":{
          "title":{"type":"string","description":"提醒什么，20 字内"},
          "when":{"type":"string","description":"什么时候，如'明天下午3点''2小时后''周五上午'"}},
          "required":["title","when"]}}}
    ]
    """.trimIndent()

    /** 执行一个工具，返回给模型看的纯文本结果（不要太长）。 */
    fun run(ctx: Context, db: InboxDatabase, name: String, argsJson: String): String {
        val args = try { org.json.JSONObject(argsJson.ifBlank { "{}" }) } catch (t: Throwable) { org.json.JSONObject() }
        return try {
            when (name) {
                "search_memory" -> {
                    val q = args.optString("query").ifBlank { return "（没给关键词）" }
                    val days = args.optInt("days", 7).coerceIn(1, 365)
                    val now = System.currentTimeMillis()
                    val hits = VectorSearch.search(db, q, now - days * 86_400_000L, now, 6)
                    if (hits.isEmpty()) "最近 $days 天没有找到与「$q」相关的记录。"
                    else hits.joinToString("\n") { "· ${fmt(it.receivedAt)} ${it.appLabel.ifBlank { it.source }}：${(it.note.ifBlank { it.text }).take(40)}" }
                }
                "list_todo" -> {
                    // v0.17：同一件事被重复登记时只报一条。
                    // 真机事故里「小刻…19:30」在库里有 7 条一模一样的行，模型照着念了 7 遍，
                    // 用户看到的就是「助理一直在发同一条消息」——病根在收件箱重复，不在模型。
                    val rows = db.upcoming(20)
                        .distinctBy { dedupKey(it.deadlineAt, it.note.ifBlank { it.text }) }
                        .take(10)
                    if (rows.isEmpty()) "眼下没有有时限的待办。"
                    else rows.joinToString("\n") { "· ${fmt(it.deadlineAt)}｜${it.note.ifBlank { it.text }.take(30)}" }
                }
                "ledger_summary" -> {
                    val days = args.optInt("days", 7).coerceIn(1, 365)
                    val now = System.currentTimeMillis()
                    val by = db.ledgerCategoryBreakdown(now - days * 86_400_000L, now)
                    if (by.isEmpty()) "最近 $days 天没有记账记录。"
                    else {
                        val exp = by.filter { it.first != Ledger.DIR_INCOME }
                        val inc = by.filter { it.first == Ledger.DIR_INCOME }
                        val total = by.sumOf { it.third.first }
                        "最近 $days 天共 $total 笔：支出 ¥${fmtMoney(exp.sumOf { it.third.second })}" +
                            (if (inc.isNotEmpty()) "，收入 ¥${fmtMoney(inc.sumOf { it.third.second })}" else "") +
                            "\n" + by.take(6).joinToString("\n") { "· ${it.first} ${it.second} ¥${fmtMoney(it.third.second)}（${it.third.first} 笔）" }
                    }
                }
                "list_courier" -> {
                    val now = System.currentTimeMillis()
                    // v0.23 修正：原先用 deadlineAt > now 过滤，真机 8 条快递里 7 条无期限、
                    // 1 条已过期，导致「我快递到哪了」明明库里有取件码却错答「没有待取快递」。
                    // 新口径见 pickCourierRows：近 14 天、签收单列、有期限的排前、无期限也算待取。
                    val (pending, signed) = pickCourierRows(
                        db.recentByCategory(InboxRecord.CAT_EXPRESS, 50), now
                    )
                    courierAnswer(pending, signed, now)
                }
                "save_memory" -> {
                    val text = args.optString("text").ifBlank { return "（没给内容）" }
                    val now = System.currentTimeMillis()
                    // v0.17 幂等：模型在一个工具循环里可能把同一次「记住」调两三遍
                    // （它自己重试、或者把同一句拆成两次调用），原来每调一次就插一行，
                    // 于是同一句备忘在库里堆成好几条，用户看到的就是「它一直重复发同一句话」。
                    if (db.hasSameTextSince(text, now - SAME_WRITE_WINDOW, now + 1_000L)) {
                        "这条刚才已经记过了，没有重复存。"
                    } else if (!ActionConfirm.ask(ActionGate.OP_WRITE_MEMORY, text)) {
                        "用户没允许存这条备忘（或取消了本轮），没有写入。"
                    } else {
                        val f = ExtractPipeline.extract(text)
                        db.insert(
                            InboxRecord(
                                source = InboxRecord.SOURCE_NOTIFICATION,
                                packageName = ctx.packageName,
                                appLabel = "备忘",
                                title = "备忘",
                                text = text,
                                receivedAt = now,
                                pendingExtract = 0,
                                category = f.category,
                                deadlineAt = f.deadlineTs,
                                amount = f.amount,
                                note = args.optString("title").ifBlank { f.note.ifBlank { text.take(30) } }
                            )
                        )
                        if (f.deadlineTs > 0) ReminderEngine.scanAsync(ctx)
                        "已存到本地记忆库：${text.take(30)}"
                    }
                }
                "create_reminder" -> {
                    val title = args.optString("title").ifBlank { "提醒" }
                    val whenText = args.optString("when")
                    val cal = Calendar.getInstance()
                    val due = ExtractPipeline.parseDeadline(whenText + " " + title, cal)
                    // v0.17 幂等：同刻同标题已经有待响的提醒，就不再建第二条。
                    // 这条护栏原来只长在 ReminderEngine.scan 里，模型走工具建提醒时绕过了它 ——
                    // 结果同一个时间被排了 7 个闹钟，到点一起响，就是用户说的「闹钟之后一直发消息」。
                    val dup = if (due > 0L) db.similarScheduledReminder(due, title) else null
                    when {
                        due <= 0L -> "没听懂时间（「$whenText」），请让用户说得更具体，比如'明天下午三点'。"
                        dup != null -> "同一时间已经有「${dup.title.take(16)}」这条提醒了，没有重复建。"
                        !ActionConfirm.ask(ActionGate.OP_WRITE_REMINDER, "$title $whenText") ->
                            "用户没允许建这条提醒（或取消了本轮），没有写入。"
                        else -> {
                            val id = db.insert(
                                InboxRecord(
                                    source = InboxRecord.SOURCE_NOTIFICATION,
                                    packageName = ctx.packageName,
                                    appLabel = "助理",
                                    title = "提醒",
                                    text = "$title（$whenText）",
                                    receivedAt = System.currentTimeMillis(),
                                    pendingExtract = 0,
                                    category = "生活",
                                    deadlineAt = due,
                                    note = title
                                )
                            )
                            ReminderEngine.scanAsync(ctx)
                            "已建提醒：$title，${fmt(due)}（inbox=$id）"
                        }
                    }
                }
                else -> "未知工具：$name"
            }
        } catch (t: Throwable) {
            "工具执行出错：${t.message?.take(40)}"
        }
    }

    /**
     * 快递待取口径（v0.23 修正，纯函数便于 JVM 单测）。
     * @return Pair(待取列表, 已签收列表)
     *
     * 不用 deadlineAt > now 过滤——真机取件通知大多 deadlineAt=0（无期限），
     * 那样会把库里所有取件码都漏掉。改为：近 14 天窗口 → 签收字样单列 →
     * 未签收里 deadlineAt>now（真有时限）排前，其余按到站时间倒序。
     */
    internal fun pickCourierRows(
        rows: List<InboxRecord>,
        now: Long,
        windowDays: Int = 14
    ): Pair<List<InboxRecord>, List<InboxRecord>> {
        val since = now - windowDays * 86_400_000L
        val recent = rows.filter { it.receivedAt >= since }
        val signed = recent.filter { isSigned(it) }
        val pending = recent
            .filterNot(::isSigned)
            .sortedWith(compareByDescending<InboxRecord> { it.deadlineAt > now }
                .thenByDescending { it.receivedAt })
        return pending to signed
    }

    private fun isSigned(r: InboxRecord): Boolean =
        r.note.contains("签收") || r.text.contains("签收")

    /** 把待取/已签收两组拼成给模型的回答（纯函数，可 JVM 单测）。 */
    internal fun courierAnswer(
        pending: List<InboxRecord>,
        signed: List<InboxRecord>,
        now: Long
    ): String = when {
        pending.isEmpty() && signed.isEmpty() ->
            "最近没有快递到站记录。收到取件通知后我会自动记下来。"
        pending.isEmpty() -> "近 14 天的 ${signed.size} 件快递都已签收。"
        else -> buildString {
            append("近 14 天有 ${pending.size} 件快递待取：\n")
            pending.take(8).forEach {
                append("· ${it.note.ifBlank { it.text }.take(40)}｜${fmt(it.receivedAt)} 到站")
                if (it.deadlineAt > now) append("｜提醒 ${fmt(it.deadlineAt)}")
                append("\n")
            }
            if (signed.isNotEmpty()) append("（另有 ${signed.size} 件已签收）")
        }.trim()
    }

    /** 判重键：同一分钟 + 同一件事（标题前 12 字），用于列表去重。 */
    private fun dedupKey(dueAt: Long, title: String): String =
        "${dueAt / 60_000}|${title.replace(Regex("\\s+"), "").take(12)}"

    /** 同一条写入在这段时间内重复到达，只认第一次。 */
    private const val SAME_WRITE_WINDOW = 10 * 60_000L

    private fun fmtMoney(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format(Locale.US, "%.2f", v)
}
