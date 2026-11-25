package com.zhuli.core

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionConfirmTest {

    @After
    fun tearDown() {
        ActionConfirm.requester = null
        ActionConfirm.gate.endRound()
    }

    @Test
    fun noRequesterMeansDeny() {
        ActionConfirm.requester = null
        ActionConfirm.gate.beginRound()
        assertFalse("没 UI 可问时默认拒绝，不能悄悄放行", ActionConfirm.ask(ActionGate.OP_WRITE_MEMORY, "记一件事"))
    }

    @Test
    fun onceGrantsAndThenAsksAgain() {
        var asked = 0
        ActionConfirm.requester = { _, _, done -> asked++; done(Grant.ONCE) }
        ActionConfirm.gate.beginRound()
        assertTrue(ActionConfirm.ask(ActionGate.OP_WRITE_REMINDER, "明天三点"))
        assertTrue(ActionConfirm.ask(ActionGate.OP_WRITE_REMINDER, "后天四点"))
        assertTrue("允许一次 = 每次都要重新问", asked == 2)
    }

    @Test
    fun roundGrantSkipsSameKind() {
        var asked = 0
        ActionConfirm.requester = { _, _, done -> asked++; done(Grant.ROUND) }
        ActionConfirm.gate.beginRound()
        assertTrue(ActionConfirm.ask(ActionGate.OP_SCREEN_CLICK, "取件"))
        assertTrue(ActionConfirm.ask(ActionGate.OP_SCREEN_CLICK, "下一单"))
        assertTrue("本轮允许同类不再问", asked == 1)
    }

    @Test
    fun cancelDeniesAndEndsRound() {
        var asked = 0
        ActionConfirm.requester = { _, _, done -> asked++; done(Grant.CANCEL) }
        ActionConfirm.gate.beginRound()
        assertFalse(ActionConfirm.ask(ActionGate.OP_WRITE_MEMORY, "记一下"))
        assertFalse(ActionConfirm.gate.roundActive())
    }

    @Test
    fun hardDenySkipsRequesterEntirely() {
        var asked = 0
        ActionConfirm.requester = { _, _, done -> asked++; done(Grant.ONCE) }
        ActionConfirm.gate.beginRound()
        assertFalse(ActionConfirm.ask(ActionGate.OP_SCREEN_CLICK, "立即付款", "确认支付 ¥99"))
        assertTrue("硬拦截不需要弹窗", asked == 0)
    }

    @Test
    fun timeoutDeniesWithoutRequesterCallback() {
        ActionConfirm.requester = { _, _, _ -> Unit }   // 故意不回调，模拟用户一直不点
        ActionConfirm.gate.beginRound()
        assertFalse(ActionConfirm.ask(ActionGate.OP_WRITE_MEMORY, "记一下", timeoutMs = 40))
    }

    @Test
    fun requesterThrowsStillDenies() {
        ActionConfirm.requester = { _, _, _ -> throw RuntimeException("UI gone") }
        ActionConfirm.gate.beginRound()
        assertFalse(ActionConfirm.ask(ActionGate.OP_WRITE_MEMORY, "记一下"))
    }
}
