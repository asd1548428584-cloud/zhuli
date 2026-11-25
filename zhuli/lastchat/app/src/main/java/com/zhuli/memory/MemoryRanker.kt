package com.zhuli.memory

import kotlin.math.exp

/**
 * 记忆检索排序（纯函数）。
 *
 * 为什么不用纯向量分数决定顺序：真机库里的检索结论是「用户打得出原词时，精确命中永远该赢」
 * （见 CURRENT_STATE 的检索基线：LIKE 40/40，trigram 17/40）。向量只该在**没有字面命中**时兜底。
 *
 * 四路信号加权：
 * - 字面命中（0.45）：主体/谓词/值里出现查询词，整词命中最高
 * - 实体命中（0.20）：查询词等于某条事实的主体或出现在别名里
 * - 时效（0.20）：按半衰期衰减，避免三年前的取件码盖过今天的
 * - 置信度（0.15）：同样命中时，原文明确写的排前面，推断的排后面
 */
object MemoryRanker {

    /** 记忆的「半衰期」：30 天后权重减半。取件码、订单号这类东西过期就是废物。 */
    const val HALF_LIFE_DAYS = 30.0

    fun recency(observedAt: Long, now: Long): Double {
        if (observedAt <= 0L) return 0.3
        val days = ((now - observedAt).coerceAtLeast(0L)) / 86_400_000.0
        return exp(-days / HALF_LIFE_DAYS).coerceIn(0.0, 1.0)
    }

    fun lexical(fact: Fact, query: String): Double {
        val q = MemoryExtractor.norm(query)
        if (q.isEmpty()) return 0.0
        val subject = MemoryExtractor.norm(fact.subject)
        val pred = MemoryExtractor.norm(fact.predicate)
        val value = MemoryExtractor.norm(fact.value)
        if (value == q || subject == q) return 1.0
        if (value.contains(q)) return 0.9
        if (subject.contains(q)) return 0.8
        if (pred.contains(q)) return 0.6
        // 查询比字段长（用户问「顺丰的取件码」）：按 2 字片段覆盖比例给分
        if (q.length >= 4) {
            val grams = (0 until q.length - 1).map { q.substring(it, it + 2) }.toSet()
            if (grams.isEmpty()) return 0.0
            val hay = "$subject$pred$value"
            val hit = grams.count { hay.contains(it) }
            return 0.6 * hit / grams.size
        }
        return 0.0
    }

    fun entityMatch(fact: Fact, query: String): Double {
        val q = MemoryExtractor.norm(query)
        if (q.isEmpty()) return 0.0
        val subject = MemoryExtractor.norm(fact.subject)
        return when {
            subject == q -> 1.0
            subject.contains(q) || q.contains(subject) -> 0.7
            else -> 0.0
        }
    }

    /** 查询里出现「快递/钱/会议」时，对应类型加权 —— 这是意图，不是硬编码答案。 */
    fun kindBoost(fact: Fact, query: String): Double {
        val q = query.lowercase()
        val want = when {
            listOf("快递", "取件", "包裹", "运单", "物流").any { q.contains(it) } -> FactKind.EXPRESS
            listOf("钱", "花", "账", "多少", "支出", "收入").any { q.contains(it) } -> FactKind.MONEY
            listOf("会", "日程", "安排", "时间", "几点").any { q.contains(it) } -> FactKind.SCHEDULE
            listOf("电话", "手机", "联系").any { q.contains(it) } -> FactKind.PHONE
            listOf("地址", "在哪", "地点").any { q.contains(it) } -> FactKind.ADDRESS
            else -> null
        } ?: return 0.0
        return if (fact.kind == want) 0.15 else 0.0
    }

    fun score(fact: Fact, query: String, now: Long): FactHit {
        val lex = lexical(fact, query)
        val ent = entityMatch(fact, query)
        val rec = recency(fact.observedAt, now)
        val conf = fact.confidence.coerceIn(0.0, 1.0)
        val boost = kindBoost(fact, query)
        val total = lex * 0.45 + ent * 0.20 + rec * 0.20 + conf * 0.15 + boost
        val reasons = buildList {
            if (lex >= 0.8) add("字面命中")
            if (ent >= 0.7) add("实体命中")
            if (rec >= 0.5) add("近期")
            if (conf >= 0.8) add("原文明确")
            if (boost > 0) add("类型匹配")
        }
        return FactHit(fact, total, reasons, match = maxOf(lex, ent, boost))
    }

    /**
     * 检索：先过滤到 ACTIVE，再打分排序。
     * 没有字面命中且总分低于 [floor] 的丢弃 —— 记忆检索宁可答「没找到」，也不要拿一堆弱相关凑数。
     */
    fun search(facts: List<Fact>, query: String, now: Long, limit: Int = 8, floor: Double = 0.18): List<FactHit> =
        facts.asSequence()
            .filter { it.status == FactStatus.ACTIVE }
            .map { score(it, query, now) }
            // 没有任何相关性信号时，时效再新、置信度再高也不该出现 ——
            // 否则问「今天天气」会返回一条取件码，用户会认为记忆系统在瞎答。
            .filter { it.match > 0.0 && it.score >= floor }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()

    /**
     * 矛盾检测：同一「主体+谓词」下有多条 ACTIVE 且值不同 → 需要用户裁决。
     * 注意：这**不是**错误状态，而是真实世界本来就会变（换了取件码、改了会议时间）。
     * 系统要做的是把冲突摆出来，而不是偷偷选一条。
     */
    fun conflicts(facts: List<Fact>): List<Pair<Fact, Fact>> {
        val active = facts.filter { it.status == FactStatus.ACTIVE }
        val bySlot = active.groupBy { it.slot }
        val out = ArrayList<Pair<Fact, Fact>>()
        for ((_, group) in bySlot) {
            if (group.size < 2) continue
            val distinct = group.distinctBy { MemoryExtractor.norm(it.value) }
            if (distinct.size < 2) continue
            // 相邻两两配对即可：UI 只需要知道「这几条打架」
            for (i in 0 until distinct.size - 1) out.add(distinct[i] to distinct[i + 1])
        }
        return out
    }
}
