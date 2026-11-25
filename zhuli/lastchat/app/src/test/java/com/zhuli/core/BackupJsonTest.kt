package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-E2：备份 JSON 编解码单测。
 *
 * 为什么值得单独测：备份是"平时没人看、出事必须用"的东西。它最容易坏的方式不是崩溃，
 * 而是**悄悄变形**——时间戳被 Double 转手丢了末位、中文被转义坏掉、换行把一行拆成两行。
 * 这些在设备上都看不出来，直到真的去恢复。
 */
class BackupJsonTest {

    @Test
    fun `时间戳（13 位毫秒）来回不丢精度`() {
        val ts = 1_795_183_940_199L   // 2026-11-20 左右，超过 2^31
        val text = Js.obj("at" to Js.of(ts)).write()
        assertEquals("""{"at":1795183940199}""", text)
        val back = Js.parse(text) as Js.Obj
        assertEquals(ts, back.num("at"))
    }

    @Test
    fun `中文与特殊字符往返`() {
        val s = "他说：\"明天 8:00 开会\"\n第二行\tTab\\反斜杠"
        val text = Js.obj("v" to Js.of(s)).write()
        // 换行/引号/反斜杠必须被转义，否则写出来的就不是合法 JSON
        assertTrue(text.contains("\\n"))
        assertTrue(text.contains("\\\""))
        assertFalse(text.contains("\n"))
        val back = Js.parse(text) as Js.Obj
        assertEquals(s, back.str("v"))
    }

    @Test
    fun `控制字符转成 u 转义并能读回`() {
        val s = "a\u0001b\u001Fc"
        val text = Js.obj("v" to Js.of(s)).write()
        assertTrue(text.contains("\\u0001"))
        assertTrue(text.contains("\\u001f"))
        assertEquals(s, (Js.parse(text) as Js.Obj).str("v"))
    }

    @Test
    fun `u 转义能解析（emoji 代理对）`() {
        val v = Js.parse("""{"v":"\u4e2d\u6587\uD83D\uDE00"}""") as Js.Obj
        assertEquals("中文😀", v.str("v"))
    }

    @Test
    fun `嵌套结构与空集合`() {
        val text = Js.obj(
            "tables" to Js.obj(
                "inbox" to Js.obj(
                    "cols" to Js.arr(listOf(Js.Str("id"), Js.Str("text"))),
                    "rows" to Js.arr(listOf(Js.arr(listOf(Js.of(1L), Js.of("你好")))))
                ),
                "empty" to Js.obj("cols" to Js.Arr(), "rows" to Js.Arr())
            )
        ).write()
        val back = Js.parse(text) as Js.Obj
        val inbox = back.obj("tables")!!.obj("inbox")!!
        assertEquals(listOf("id", "text"), inbox.arr("cols")!!.strings())
        val row = inbox.arr("rows")!!.items[0] as Js.Arr
        assertEquals(1L, row.items[0].asLongOr())
        assertEquals("你好", row.items[1].asStringOr())
        assertEquals(0, back.obj("tables")!!.obj("empty")!!.arr("rows")!!.size)
    }

    @Test
    fun `小数与整数能区分（决定 bindLong 还是 bindDouble）`() {
        assertTrue((Js.Num("12") as Js).isIntegral())
        assertFalse(Js.Num("12.5").isIntegral())
        assertFalse(Js.Num("1e3").isIntegral())
        assertEquals(12.5, Js.parse("12.5").asDoubleOr(), 0.0001)
        assertEquals(-7L, Js.parse("-7").asLongOr())
    }

    @Test
    fun `NaN 与 Infinity 写成 null 而不是坏 JSON`() {
        assertEquals("null", Js.of(Double.NaN).write())
        assertEquals("null", Js.of(Double.POSITIVE_INFINITY).write())
    }

    @Test
    fun `坏输入抛异常而不是静默返回空`() {
        val bad = listOf(
            "",                        // 空文件
            "{",                       // 对象未闭合
            """{"a":1,}""",            // 尾随逗号
            """{"a":}""",              // 缺值
            "[1,2",                    // 数组未闭合
            """{"a":"没闭合}""",       // 字符串未闭合
            """{"a":1.2.3}""",         // 非法数字
            "{a:1}",                   // 键没引号
            "null x"                   // 末尾多余内容
        )
        for (b in bad) {
            val ok = try { Js.parse(b); false } catch (e: IllegalArgumentException) { true }
            assertTrue("应当拒绝：$b", ok)
        }
    }

    @Test
    fun `取值类型不符时给默认值不崩`() {
        val o = Js.parse("""{"n":null,"s":123,"b":"true"}""") as Js.Obj
        assertEquals(0L, o.num("n"))
        assertEquals("123", o.str("s"))
        assertTrue(o.get("b")!!.asBoolOr())
        assertNull(o.obj("n"))
        assertNull(o.arr("missing"))
    }

    @Test
    fun `大备份体积下的自读自写（模拟 400 行收件箱）`() {
        val rows = Js.Arr()
        repeat(400) { i ->
            rows.add(Js.arr(listOf(
                Js.of(i.toLong()), Js.of("第 $i 条：您的快递已到驿站，取件码 8-$i-${i * 7}"),
                Js.of(1_795_183_940_000L + i), Js.of(12.34), Js.Null
            )))
        }
        val text = Js.obj("rows" to rows).write()
        val back = Js.parse(text) as Js.Obj
        assertEquals(400, back.arr("rows")!!.size)
        val last = back.arr("rows")!!.items[399] as Js.Arr
        assertEquals(1795183940399L, last.items[2].asLongOr())
        assertTrue(text.length > 10_000)
    }
}
