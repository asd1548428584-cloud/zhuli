package com.zhuli.bridge

import kotlin.math.sqrt

/**
 * T-G2 三个只读 Tool 的回答纯函数层（纯 JVM，可单测，无 Android / kotlinx 依赖）。
 *
 * 口径全部对齐 zhuli 主项目 core/Tools.kt，不另起一套：
 * - list_todo     ← Tools.run("list_todo")：deadline>now、按 deadline 升序、按分钟+标题前12字去重、取10条
 * - ledger_summary← Tools.run("ledger_summary")：按 dir/cat 分组、金额降序、收支分别合计
 * - search_memory ← VectorSearch.search：整串命中>分词命中 + 1/2gram 哈希向量余弦(0.45) 混合关键词(0.55)
 *
 * 为什么要独立成纯函数：真机 SQL 查询是不可单测的（依赖 Android SQLite），
 * 把所有"怎么答"的逻辑抽到这里，就能用真库导出的 fixture 在 JVM 上验证口径不漂移。
 */
object ZhuliQueryAnswer {

    private fun fmt(ms: Long): String =
        java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(ms))

    private fun fmtMoney(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", v)

    // ---------------- list_todo ----------------

    data class TodoRow(
        val dueAt: Long,
        val title: String,
        val status: String,
        val confidence: String,
        val basis: String,
    )

    /** 判重键：同一分钟 + 同一件事（标题前 12 字），与 Tools.dedupKey 一致。 */
    private fun dedupKey(dueAt: Long, title: String): String =
        "${dueAt / 60_000}|${title.replace(Regex("\\s+"), "").take(12)}"

    /**
     * 真库事实修正：待办都在 reminders 表（inbox.deadline_at>now 在真机快照里为空）。
     * 与 T-G1 的 list_courier 同类——照搬主项目口径会在真库上空答。
     * @param rows 未完成的提醒（status=pending/scheduled），调用方 SQL 已过滤并升序。
     * @param now 用于标注"已过期"的待办（真库里 pending 也可能是过去的时刻）。
     */
    fun todo(rows: List<TodoRow>, now: Long, limit: Int = 10): String {
        val seen = HashSet<String>()
        val picked = rows.mapNotNull { r ->
            val key = dedupKey(r.dueAt, r.title)
            if (seen.add(key)) r else null
        }.take(limit)
        if (picked.isEmpty()) return "眼下没有待办或安排。"
        return picked.joinToString("\n") { r ->
            val overdue = if (r.dueAt in 1 until now) "（时间已过，记的是 ${fmt(r.dueAt)}）" else "（${fmt(r.dueAt)}）"
            val basis = if (r.basis.isBlank() || r.confidence == "high") "" else "｜${r.basis}"
            "· ${r.title.take(30)}$overdue$basis"
        }
    }

    // ---------------- ledger_summary ----------------

    data class LedgerGroup(val dir: String, val category: String, val count: Int, val amount: Double)

    private const val DIR_INCOME = "收"

    /**
     * @param groups 已按 (dir,cat) 分组的账本统计（调用方 SQL 给出）。本函数做排序、收支合计、措辞。
     */
    fun ledger(groups: List<LedgerGroup>, days: Int): String {
        if (groups.isEmpty()) return "最近 $days 天没有记账记录。"
        val by = groups.sortedWith(
            compareByDescending<LedgerGroup> { it.amount }
                .thenBy { if (it.dir == DIR_INCOME) 1 else 0 }
        )
        val exp = by.filter { it.dir != DIR_INCOME }
        val inc = by.filter { it.dir == DIR_INCOME }
        val total = by.sumOf { it.count }
        return buildString {
            append("最近 $days 天共 $total 笔：支出 ¥${fmtMoney(exp.sumOf { it.amount })}")
            if (inc.isNotEmpty()) append("，收入 ¥${fmtMoney(inc.sumOf { it.amount })}")
            append("\n")
            append(by.take(6).joinToString("\n") { g ->
                "· ${g.dir}${g.category} ¥${fmtMoney(g.amount)}（${g.count} 笔）"
            })
        }
    }

    // ---------------- search_memory ----------------

    data class MemRow(
        val id: Long,
        val title: String,
        val text: String,
        val note: String,
        val appLabel: String,
        val source: String,
        val receivedAt: Long,
    )

    /** 关键词得分：整串命中 1.0 > 分词命中 0.5~1.0 > 0。与 VectorSearch.keywordScore 一致。 */
    fun keywordScore(rec: MemRow, q: String): Double {
        val hay = (rec.title + " " + rec.text + " " + rec.note).lowercase()
        if (hay.contains(q.lowercase())) return 1.0
        val parts = q.split(Regex("[\\s\\p{Punct}，。！？、]+")).filter { it.length >= 2 }
        if (parts.isEmpty()) return 0.0
        val hit = parts.count { hay.contains(it.lowercase()) }
        return if (hit == 0) 0.0 else 0.5 + 0.5 * hit / parts.size
    }

    /** 字符 1/2-gram 哈希嵌入（256 维，归一化），与 VectorSearch.HashEmbedder 逐字一致。 */
    fun embed(text: String): FloatArray {
        val dim = 256
        val v = FloatArray(dim)
        val s = text.lowercase().replace(Regex("\\s+"), " ")
        if (s.isBlank()) return v
        fun add(gram: String, w: Float) {
            if (gram.isBlank()) return
            var h = 2166136261.toInt()
            for (c in gram) { h = h xor c.code; h *= 16777619 }
            val idx = (h and 0x7fffffff) % dim
            v[idx] += w
        }
        for (i in s.indices) add(s[i].toString(), 0.5f)
        for (i in 0 until s.length - 1) add(s.substring(i, i + 2), 1.0f)
        var norm = 0.0
        for (f in v) norm += f * f
        norm = sqrt(norm)
        if (norm > 0) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
        return v
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += (a[i].toDouble() * b[i])
        return dot
    }

    /**
     * 混合检索：向量余弦 0.45 + 关键词 0.55。
     * @param candidates 时间窗内的记录（调用方 SQL 已按时间取候选）
     * @param vecMap inboxId -> 已存的向量（可能缺；缺则按 0 处理，靠关键词仍可命中）
     */
    fun searchMemory(
        candidates: List<MemRow>,
        vecMap: Map<Long, FloatArray>,
        query: String,
        limit: Int = 6,
    ): List<MemRow> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val qv = embed(q)
        return candidates.mapNotNull { rec ->
            val kw = keywordScore(rec, q)
            val v = vecMap[rec.id]?.let { cosine(qv, it) } ?: 0.0
            if (kw <= 0.0 && v < 0.20) return@mapNotNull null
            Triple(rec, v, kw)
        }.sortedByDescending { (_, v, kw) -> v * 0.45 + kw * 0.55 }
            .take(limit)
            .map { it.first }
    }

    /** 检索结果 → 给模型看的文本。与 Tools.run 的措辞一致。 */
    fun searchAnswer(hits: List<MemRow>, query: String, days: Int): String {
        if (hits.isEmpty()) return "最近 $days 天没有找到与「$query」相关的记录。"
        return hits.joinToString("\n") { r ->
            "· ${fmt(r.receivedAt)} ${r.appLabel.ifBlank { r.source }}：${(r.note.ifBlank { r.text }).take(40)}"
        }
    }
}
