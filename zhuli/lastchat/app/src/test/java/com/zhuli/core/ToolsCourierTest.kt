package com.zhuli.core

import org.junit.Assert.*
import org.junit.Test

/**
 * list_courier 口径回归测试（v0.23）。
 * 真机事故：库里 8 条快递，7 条 deadlineAt=0、1 条已过期，旧逻辑 `deadlineAt > now`
 * 全滤掉 → "我快递到哪了"错答"没有待取快递"。这组用例锁住修正后的口径。
 */
class ToolsCourierTest {
    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun row(
        note: String,
        receivedAt: Long = now - 3600_000L,
        deadlineAt: Long = 0L,
        text: String = ""
    ) = InboxRecord(
        source = InboxRecord.SOURCE_NOTIFICATION,
        packageName = "com.cainiao",
        appLabel = "菜鸟",
        title = "取件通知",
        text = text,
        receivedAt = receivedAt,
        note = note,
        category = InboxRecord.CAT_EXPRESS,
        deadlineAt = deadlineAt
    )

    @Test fun `无期限快递也算待取 不再错答没有`() {
        val rows = listOf(row("取件码 6825，18-3-8221", deadlineAt = 0L))
        val (pending, signed) = Tools.pickCourierRows(rows, now)
        assertEquals(1, pending.size)
        assertTrue(signed.isEmpty())
        val ans = Tools.courierAnswer(pending, signed, now)
        assertTrue(ans.contains("6825"))
        assertFalse(ans.contains("没有待取"))
    }

    @Test fun `已过截止时间的快递仍算待取`() {
        val rows = listOf(row("取件码 1234", deadlineAt = now - 2 * 3600_000L))
        val (pending, _) = Tools.pickCourierRows(rows, now)
        assertEquals(1, pending.size)
    }

    @Test fun `含签收字样的单列 不混进待取`() {
        val rows = listOf(
            row("取件码 5555"),
            row("已签收", text = "您的快递已签收"),
        )
        val (pending, signed) = Tools.pickCourierRows(rows, now)
        assertEquals(1, pending.size)
        assertEquals(1, signed.size)
        assertTrue(Tools.courierAnswer(pending, signed, now).contains("另有 1 件已签收"))
    }

    @Test fun `有真实时限的排前 其余按到站倒序`() {
        val rows = listOf(
            row("无期限-新", receivedAt = now - 3600_000L, deadlineAt = 0L),
            row("有时限-旧", receivedAt = now - 5 * 3600_000L, deadlineAt = now + 3600_000L),
            row("无期限-更新", receivedAt = now - 600_000L, deadlineAt = 0L),
        )
        val (pending, _) = Tools.pickCourierRows(rows, now)
        assertEquals("有时限-旧", pending[0].note)
        assertEquals("无期限-更新", pending[1].note)
        assertEquals("无期限-新", pending[2].note)
    }

    @Test fun `超过14天的旧快递不报`() {
        val rows = listOf(
            row("老快递", receivedAt = now - 20 * day),
            row("新快递", receivedAt = now - day),
        )
        val (pending, _) = Tools.pickCourierRows(rows, now)
        assertEquals(1, pending.size)
        assertEquals("新快递", pending[0].note)
    }

    @Test fun `空库给出引导语`() {
        val (pending, signed) = Tools.pickCourierRows(emptyList(), now)
        assertTrue(Tools.courierAnswer(pending, signed, now).contains("自动记下来"))
    }
}
