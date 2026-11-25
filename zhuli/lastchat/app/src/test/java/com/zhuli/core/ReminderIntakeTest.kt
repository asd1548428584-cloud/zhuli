package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 对话里直接下提醒（2026-11-21，真机抓到）。
 *
 * 「3 分钟后提醒我喝水」原来被当成「查询待办」回答「眼下没有明确的待办」——
 * 路由里 `Q_TODO` 含「提醒」，抢在建提醒分支前面。
 */
class ReminderIntakeTest {

    @Test
    fun `标题削掉时间与祈使词`() {
        assertEquals("喝水", ReminderIntake.titleOf("3分钟后提醒我喝水"))
        assertEquals("交材料", ReminderIntake.titleOf("明天提醒我交材料"))
        assertEquals("买菜", ReminderIntake.titleOf("提醒我要买菜"))
        assertEquals("给妈妈打电话", ReminderIntake.titleOf("晚上八点叫我给妈妈打电话").let { it })
    }

    @Test
    fun `削完什么都不剩时给个朴素标题`() {
        assertEquals("提醒", ReminderIntake.titleOf("提醒我"))
        assertEquals("提醒", ReminderIntake.titleOf("3分钟后提醒我"))
    }

    @Test
    fun `标题过长截断到30字`() {
        val long = "提醒我" + "很长的待办事项".repeat(10)
        assertTrue(ReminderIntake.titleOf(long).length <= 30)
    }

    @Test
    fun `高置信回话说会响`() {
        val s = ReminderIntake.replyOf("11-21 11:50", ReminderConfidence.HIGH, "相对时刻（N 分钟后）", "喝水")
        assertTrue(s.contains("11-21 11:50"))
        assertTrue(s.contains("喝水"))
        assertTrue(s.contains("到点会响"))
    }

    @Test
    fun `中置信回话说明在待确认而不是已排`() {
        val s = ReminderIntake.replyOf("11-21 15:00", ReminderConfidence.MEDIUM, "只有时刻、没有日期", "开会")
        assertTrue(s.contains("待确认"))
        assertFalse(s.contains("到点会响"))
    }

    @Test
    fun `低置信不排并给出改法`() {
        val s = ReminderIntake.replyOf("11-20 15:00", ReminderConfidence.LOW, "时间不在未来", "开会")
        assertTrue(s.contains("已经过去"))
        assertTrue(s.contains("没排"))
    }
}

/**
 * 快递取件提级（2026-11-21 用户选「识别到取件码就自动排」）。
 */
class CourierPolicyTest {

    @Test
    fun `有取件码才提级，依据写清是推定`() {
        val b = CourierPolicy.basisOf("6825")
        assertTrue(b != null && b.contains("取件码 6825"))
        assertTrue(b!!.contains("推定"))
    }

    @Test
    fun `没码不提级`() {
        assertEquals(null, CourierPolicy.basisOf(null))
        assertEquals(null, CourierPolicy.basisOf(""))
        assertEquals(null, CourierPolicy.basisOf("   "))
    }

    @Test
    fun `真实取件短信能解析出码并提级`() {
        // 真机 11-21 收到的原文（韵达 / 欢猫驿站）
        val text = "【欢猫驿站】您的韵达快递包裹已到一矿9号楼西侧28号车库，请凭6825取件"
        val info = Express.parse(text)
        assertTrue(info != null)
        assertTrue(CourierPolicy.basisOf(info!!.code) != null)
    }
}
