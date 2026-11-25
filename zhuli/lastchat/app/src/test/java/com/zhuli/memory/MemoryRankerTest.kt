package com.zhuli.memory

import org.junit.Assert.*
import org.junit.Test

class MemoryRankerTest {
    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun fact(
        value: String,
        subject: String = "菜鸟驿站",
        pred: String = "取件码",
        kind: FactKind = FactKind.EXPRESS,
        conf: Double = 0.9,
        at: Long = now,
        status: FactStatus = FactStatus.ACTIVE,
    ) = Fact(
        subject = subject, predicate = pred, value = value, kind = kind, confidence = conf,
        sourceType = SourceType.INBOX, sourceId = 1L, observedAt = at, status = status,
    )

    @Test fun `精确命中得分高于片段匹配`() {
        val exact = MemoryRanker.lexical(fact("6825"), "6825")
        val partial = MemoryRanker.lexical(fact("68254321"), "6825")
        assertTrue("exact=$exact partial=$partial", exact > partial)
    }

    @Test fun `过期事实不会盖过今天的事实`() {
        val fresh = MemoryRanker.recency(now, now)
        val old = MemoryRanker.recency(now - 120 * day, now)
        assertTrue(fresh > old * 4)
    }

    @Test fun `检索过滤非活跃事实`() {
        val list = listOf(fact("1111", status = FactStatus.DELETED), fact("2222"))
        val hits = MemoryRanker.search(list, "取件码", now)
        assertTrue(hits.all { it.fact.value == "2222" })
    }

    @Test fun `弱相关被门槛过滤 不拿凑数结果糊弄`() {
        val hits = MemoryRanker.search(listOf(fact("6825")), "今天天气怎么样", now)
        assertTrue(hits.isEmpty())
    }

    @Test fun `同槽位不同值被识别为矛盾`() {
        val pairs = MemoryRanker.conflicts(listOf(fact("1111", at = now), fact("2222", at = now - day)))
        assertEquals(1, pairs.size)
        assertEquals("1111", pairs[0].first.value)
    }

    @Test fun `纠错后旧值不再参与矛盾检测`() {
        val pairs = MemoryRanker.conflicts(
            listOf(fact("1111", at = now - day), fact("2222", status = FactStatus.SUPERSEDED))
        )
        assertTrue(pairs.isEmpty())
    }

    @Test fun `查询带类型意图时对应类型加权`() {
        val express = MemoryRanker.score(fact("6825"), "我的快递", now)
        val money = MemoryRanker.score(fact("¥88", pred = "金额", kind = FactKind.MONEY), "我的快递", now)
        assertTrue(express.score > money.score)
    }

    @Test fun `置信度不同的同值事实 原文明确者排前`() {
        val high = MemoryRanker.score(fact("6825", conf = 0.95), "取件码", now)
        val low = MemoryRanker.score(fact("6825", conf = 0.45), "取件码", now)
        assertTrue(high.score > low.score)
    }
}
