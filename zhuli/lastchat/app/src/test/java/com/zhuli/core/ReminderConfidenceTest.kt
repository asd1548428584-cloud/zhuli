package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T-D1：置信度三级判定。
 *
 * 这套规则的意义：以前"解析出未来时间"就等于"建闹钟"，
 * 「下周给我」「明天左右」也会准点响 —— 响错一次比漏一次更伤信任。
 */
class ReminderConfidenceTest {

    private val now = 1_795_000_000_000L          // 固定基准，避免测试随真实时间漂
    private val inHour = now + 3_600_000L
    private val inDay = now + 86_400_000L

    @Test
    fun `明确日期加时刻算高置信`() {
        listOf("周三下午三点评审", "明天下午三点开会", "今天晚上八点吃饭", "3月5日上午10点体检").forEach {
            assertEquals("应判高：$it", ReminderConfidence.HIGH, ReminderConfidence.evaluate(it, inDay, now).level)
        }
    }

    @Test
    fun `相对时刻算高置信`() {
        listOf("2 小时后喝水", "30分钟后休息", "一个小时后出发").forEach {
            assertEquals("应判高：$it", ReminderConfidence.HIGH, ReminderConfidence.evaluate(it, inHour, now).level)
        }
    }

    @Test
    fun `含糊表述算中置信`() {
        listOf("下周给我答复", "明天左右到", "月底前提交", "回头再说", "这两天有空弄一下").forEach {
            assertEquals("应判中：$it", ReminderConfidence.MEDIUM, ReminderConfidence.evaluate(it, inDay, now).level)
        }
    }

    @Test
    fun `只有日期或只有时刻算中置信`() {
        assertEquals(ReminderConfidence.MEDIUM, ReminderConfidence.evaluate("明天交材料", inDay, now).level)
        assertEquals(ReminderConfidence.MEDIUM, ReminderConfidence.evaluate("下午三点碰一下", inDay, now).level)
    }

    @Test
    fun `数字日期加时刻算高置信`() {
        // 实测来源：工具建提醒时会把时间规范成「关灯（11-20 21:17）」，
        // 第一版只认「11月20日」，结果被判成「只有时刻、没有日期」进了待确认。
        listOf("关灯（11-20 21:17）", "开会（2026-11-21 15:00）", "体检（11/22 09:00）").forEach {
            assertEquals("应判高：$it", ReminderConfidence.HIGH, ReminderConfidence.evaluate(it, inDay, now).level)
        }
    }

    @Test
    fun `时间已过算低置信`() {
        val past = now - 60_000L
        assertEquals(ReminderConfidence.LOW, ReminderConfidence.evaluate("昨天下午三点的事", past, now).level)
    }

    @Test
    fun `判定必须带依据`() {
        listOf("明天下午三点开会", "下周给我", "2小时后").forEach {
            val v = ReminderConfidence.evaluate(it, inDay, now)
            assert(v.basis.isNotBlank()) { "缺依据：$it" }
        }
    }
}
