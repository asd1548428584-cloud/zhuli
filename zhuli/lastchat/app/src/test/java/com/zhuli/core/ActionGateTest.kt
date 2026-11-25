package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionGateTest {
    /** 可控时钟：让"3 分钟超时"不用真的等 3 分钟。 */
    private class Clock(var now: Long) {
        val gate get() = ActionGate { now }
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun onceOnlyGrantsThatOneAction() {
        val c = Clock(1000)
        val g = c.gate
        g.beginRound()
        assertEquals(GateCheck.Ask, g.check("write_reminder"))
        assertTrue(g.record("write_reminder", Grant.ONCE))
        // 允许一次：同类下一次仍要问
        assertEquals(GateCheck.Ask, g.check("write_reminder"))
        assertEquals(0, g.grantedCount())
    }

    @Test
    fun roundGrantSkipsSameKindOnly() {
        val c = Clock(1000)
        val g = c.gate
        g.beginRound()
        assertTrue(g.record("screen_click", Grant.ROUND))
        assertEquals(GateCheck.AllowRound, g.check("screen_click"))
        // 不同类仍要问 —— "本轮允许"是同类，不是全放行
        assertEquals(GateCheck.Ask, g.check("write_memory"))
        assertEquals(1, g.grantedCount())
    }

    @Test
    fun cancelEndsRoundAndEverythingAsksAgain() {
        val c = Clock(1000)
        val g = c.gate
        g.beginRound()
        assertTrue(g.record("screen_click", Grant.ROUND))
        assertFalse(g.record("write_memory", Grant.CANCEL))
        assertFalse(g.roundActive())
        // 轮结束后即使之前 ROUND 过，也要重新问
        assertEquals(GateCheck.Ask, g.check("screen_click"))
    }

    @Test
    fun roundDoesNotLeakAcrossRounds() {
        val c = Clock(1000)
        val g = c.gate
        g.beginRound()
        g.record("write_memory", Grant.ROUND)
        val seq = g.beginRound()
        assertTrue(seq > 0)
        assertEquals(GateCheck.Ask, g.check("write_memory"))
        assertEquals(0, g.grantedCount())
    }

    @Test
    fun expiresAfterThreeMinutesOfNoActivity() {
        val c = Clock(1000)
        val g = c.gate
        g.beginRound()
        g.record("screen_click", Grant.ROUND)
        c.advance(ActionGate.ROUND_TIMEOUT_MS - 1)
        assertTrue(g.roundActive())
        c.advance(2)
        assertFalse(g.roundActive())
        assertEquals(GateCheck.Ask, g.check("screen_click"))
    }

    @Test
    fun activityRefreshesDeadline() {
        val c = Clock(1000)
        val g = c.gate
        g.beginRound()
        c.advance(ActionGate.ROUND_TIMEOUT_MS - 1000)
        g.touch()                         // 有操作 → 重新计时
        c.advance(2000)
        assertTrue("续期后 2 秒仍在轮内", g.roundActive())
    }

    @Test
    fun hardDenyIgnoresRoundGrant() {
        val c = Clock(1000)
        val g = c.gate
        g.beginRound()
        g.record("screen_click", Grant.ROUND)
        val hit = g.check("screen_click", "确认支付 ¥299")
        assertTrue(hit is GateCheck.HardDeny)
        assertEquals("支付确认页", (hit as GateCheck.HardDeny).reason)
    }

    @Test
    fun hardDenyCoversPasswordAndSystemSettings() {
        val g = Clock(1000).gate
        assertEquals("密码输入", g.hardBlockReason("请输入密码"))
        assertEquals("验证码输入", g.hardBlockReason("短信验证码"))
        assertEquals("系统设置页", g.hardBlockReason("系统设置"))
        assertNull("普通页面不拦", g.hardBlockReason("菜鸟驿站 取件码 A1234"))
    }

    @Test
    fun neverRoundActionsCannotBePreAuthorized() {
        val c = Clock(1000)
        val g = c.gate
        g.beginRound()
        g.record("send_message", Grant.ROUND)
        g.record("pay", Grant.ROUND)
        g.record("bulk_modify", Grant.ROUND)
        // 三种高风险动作即使回传 ROUND 也执行一次问一次
        assertEquals(GateCheck.Ask, g.check("send_message"))
        assertEquals(GateCheck.Ask, g.check("pay"))
        assertEquals(GateCheck.Ask, g.check("bulk_modify"))
        assertEquals(0, g.grantedCount())
    }

    @Test
    fun screenOffEndsRound() {
        val c = Clock(1000)
        val g = c.gate
        g.beginRound()
        g.record("screen_click", Grant.ROUND)
        g.screenOff()
        assertFalse(g.roundActive())
        assertEquals(GateCheck.Ask, g.check("screen_click"))
    }

    @Test
    fun noRoundDefaultsToAsk() {
        val g = Clock(1000).gate
        // 没开轮时按安全档：每步都问，绝不放行
        assertEquals(GateCheck.Ask, g.check("write_reminder"))
    }
}
