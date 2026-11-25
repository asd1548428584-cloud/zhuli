package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-C4：提醒文案规则。
 *
 * 这些规则原来埋在对 NotificationManager 的调用里，只能上真机"等 20 分钟看一次"。
 * 抽成纯函数后，规则本身被锁住；真机只需验"显示是否照这个来"。
 */
class ReminderTextTest {

    private fun rem(title: String = "喝水", snooze: Int = 0) =
        Reminder(id = 1, inboxId = 1, dueAt = 0, title = title, body = "", snoozeCount = snooze)

    @Test
    fun `推迟一次不啰嗦`() {
        assertEquals("⏰ 喝水", ReminderText.title(rem(snooze = 1), overdue = false))
        assertTrue(ReminderText.snoozeSuffix(1).isEmpty())
    }

    @Test
    fun `推迟两次起标题劝你换时间`() {
        val t = ReminderText.title(rem(snooze = 2), overdue = false)
        assertTrue("应含已推迟 2 次：$t", t.contains("已推迟 2 次"))
        assertTrue("应劝换时间：$t", t.contains("换个时间"))
        assertEquals("⏰ 喝水（已推迟 3 次，要不要换个时间？）",
            ReminderText.title(rem(snooze = 3), overdue = false))
    }

    @Test
    fun `补响的标题带前缀且后缀不丢`() {
        val t = ReminderText.title(rem(snooze = 2), overdue = true)
        assertTrue("应有补个提醒：$t", t.startsWith("⏰ 补个提醒｜"))
        assertTrue("后缀不能丢：$t", t.contains("已推迟 2 次"))
    }

    @Test
    fun `冲突提示只留对方标题前 12 字`() {
        val long = "这是一个特别特别长特别特别长的会议标题"
        val line = ReminderText.conflictLine(long, 25)
        assertEquals("⚠️ 与「${long.take(12)}」只差 25 分钟", line)
        assertFalse("不应出现完整标题", line.contains(long))
    }

    @Test
    fun `冲突提示保留分钟数`() {
        assertTrue(ReminderText.conflictLine("评审", 4).contains("只差 4 分钟"))
    }

    // ==================== 阶段 G：通知上的"凭什么" ====================

    @Test
    fun `有依据就带上，且截到 60 字`() {
        val r = Reminder(inboxId = 1L, dueAt = 0L, title = "开会", body = "", basis = "明早 9 点 + 原文含「会议」")
        assertEquals("依据：明早 9 点 + 原文含「会议」", ReminderText.basisLine(r))

        val long = Reminder(inboxId = 1L, dueAt = 0L, title = "开会", body = "", basis = "啊".repeat(200))
        assertEquals("依据：".length + 60, ReminderText.basisLine(long).length)
    }

    @Test
    fun `没有依据就返回空串，不硬编一句假话`() {
        val r = Reminder(inboxId = 1L, dueAt = 0L, title = "开会", body = "", basis = "   ")
        assertEquals("", ReminderText.basisLine(r))
    }
}
