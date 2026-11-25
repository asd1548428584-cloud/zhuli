package com.zhuli.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 A：出网拦截器的 fail-closed 判定。
 *
 * 这组用例专门覆盖旧实现里「读不出来就放行」的五个分支。
 * 它们不依赖 OkHttp，所以能在 JVM 单测里稳定跑；
 * 拦截器本体只负责把这里的结果翻译成 403 或继续发包。
 */
class OutgoingBodyGateTest {

    private fun decide(raw: String?, strict: Boolean = false) = OutgoingBodyGate.decide(raw, strict)

    @Test
    fun `请求体不可读时拒绝而不是放行`() {
        val d = decide(null)
        assertTrue("应拦下，实际 $d", d is OutgoingBodyGate.Decision.Block)
    }

    @Test
    fun `空请求体拒绝`() {
        assertTrue(decide("   ") is OutgoingBodyGate.Decision.Block)
    }

    @Test
    fun `不是 JSON 时拒绝`() {
        val d = decide("phone=13800001111")
        assertTrue(d is OutgoingBodyGate.Decision.Block)
        // 具体措辞可能是「不是合法 JSON」或「根节点不是对象」（取决于 lenient 解析到哪一步），
        // 但结论必须一致：拒绝原样出网。
        assertTrue((d as OutgoingBodyGate.Decision.Block).reason.contains("拒绝原样出网"))
    }

    @Test
    fun `结构不认识时拒绝（Responses input 之外的私有形状）`() {
        val d = decide("""{"model":"m","foo":{"phone":"13800001111"}}""")
        assertTrue("应拦下，实际 $d", d is OutgoingBodyGate.Decision.Block)
    }

    @Test
    fun `正常聊天请求放行并报告扫描字段数`() {
        val d = decide("""{"model":"m","messages":[{"role":"user","content":"明天下午三点开会"}]}""")
        assertTrue("应放行，实际 $d", d is OutgoingBodyGate.Decision.Send)
        val s = (d as OutgoingBodyGate.Decision.Send).scanned
        assertEquals(1, s.scannedFields)
        assertEquals(0, s.redactions)
        assertEquals("chat/completions", s.shape)
    }

    @Test
    fun `Responses 的 input 会被实际扫描而不是当成无敏感项`() {
        val d = decide("""{"model":"m","input":"我电话 13800001111"}""")
        assertTrue(d is OutgoingBodyGate.Decision.Send)
        val s = (d as OutgoingBodyGate.Decision.Send).scanned
        assertEquals(1, s.redactions)
        assertFalse(s.body.contains("13800001111"))
    }

    @Test
    fun `strict 模式下敏感项直接拦下`() {
        val d = decide("""{"model":"m","messages":[{"role":"user","content":"我电话 13800001111"}]}""", strict = true)
        assertTrue("应拦下，实际 $d", d is OutgoingBodyGate.Decision.Block)
    }

    @Test
    fun `硬红线在拦截器这一层也中止`() {
        val d = decide("""{"model":"m","messages":[{"role":"user","content":"身份证 110101199003071234"}]}""")
        assertTrue("应拦下，实际 $d", d is OutgoingBodyGate.Decision.Block)
        assertTrue((d as OutgoingBodyGate.Decision.Block).reason.contains("证件号"))
    }

    @Test
    fun `取件码与运单号会被打码（业务敏感不是盲区）`() {
        val d = decide("""{"model":"m","messages":[{"role":"user","content":"取件码 8-1-2233，运单号 SF1234567890123"}]}""")
        assertTrue(d is OutgoingBodyGate.Decision.Send)
        val body = (d as OutgoingBodyGate.Decision.Send).scanned.body
        assertFalse(body.contains("8-1-2233"))
        assertFalse(body.contains("SF1234567890123"))
        assertTrue(body.contains("[取件码]"))
        assertTrue(body.contains("[运单号]"))
    }
}
