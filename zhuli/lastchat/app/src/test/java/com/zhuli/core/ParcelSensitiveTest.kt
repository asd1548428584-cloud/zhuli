package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 A：取件码 / 提取码 / 运单号识别。
 *
 * 为什么要单独一组：这几类此前**完全不在 `SensitiveGuard` 里**。
 * 后果不是"误伤"，是"漏放"—— Agent 把 `save_fact(value="8-1-2233")` 的参数
 * 原样带进下一轮请求，取件码就跟着上云了，而自检面板还显示"无敏感项"。
 */
class ParcelSensitiveTest {

    @Test
    fun `带语境的取件码能识别并脱敏`() {
        listOf(
            "取件码 6825",
            "取件码：6825",
            "取件码是 6825",
            "提取码 8xk2",
            "取货码 AB-1234",
            "凭 8876 取件"
        ).forEach { text ->
            assertEquals("应判为取件码：$text", "取件码", SensitiveGuard.reason(text))
            val r = SensitiveGuard.redact(text)
            assertTrue("应留占位符：$r", r.contains("[取件码]"))
            assertNull("脱敏后不该再判敏感：$r", SensitiveGuard.reason(r))
        }
    }

    @Test
    fun `裸结构取件码（Agent 参数里没有语境）也能识别`() {
        val text = """{"code":"8-1-2233"}"""
        assertEquals("取件码", SensitiveGuard.reason(text))
        val r = SensitiveGuard.redact(text)
        assertFalse("裸码残留：$r", r.contains("8-1-2233"))
        assertTrue("仍应是合法 JSON：$r", r.contains("\"code\":\"[取件码]\""))
    }

    @Test
    fun `运单号与订单号能识别并脱敏`() {
        listOf(
            "运单号 SF1234567890123",
            "快递单号：YT9876543210",
            "订单号 202611250001",
            "物流单号 ZT12345678901"
        ).forEach { text ->
            assertEquals("应判为运单号：$text", "运单号", SensitiveGuard.reason(text))
            val r = SensitiveGuard.redact(text)
            assertTrue("应留占位符：$r", r.contains("[运单号]"))
        }
    }

    @Test
    fun `日期与普通编号不会被误判成取件码`() {
        listOf(
            "2026-11-25 下午三点开会",
            "11-25 交材料",
            "订单已发货",
            "明天见",
            // 普通小数不是坐标：两个数之间必须有逗号/空格分隔才判
            "圆周率约等于 3.14159，自然常数 2.71828"
        ).forEach { text ->
            assertNull("不该判敏感：$text", SensitiveGuard.reason(text))
            assertEquals("不该改写：$text", text, SensitiveGuard.redact(text))
        }
    }

    @Test
    fun `取件码不会把金额规则带偏`() {
        // 取件码规则先于金额；这里确认「取件码 + 金额」同句时两类都被处理，
        // 且不会因为先替换取件码而漏掉金额。
        val text = "取件码 8-1-2233，另外这单运费 12 元"
        assertEquals("取件码", SensitiveGuard.reason(text))
        val r = SensitiveGuard.redact(text)
        assertFalse("取件码残留：$r", r.contains("8-1-2233"))
        assertFalse("金额残留：$r", r.contains("12 元"))
        assertTrue(r.contains("[取件码]"))
        assertTrue(r.contains("[金额]"))
    }
}
