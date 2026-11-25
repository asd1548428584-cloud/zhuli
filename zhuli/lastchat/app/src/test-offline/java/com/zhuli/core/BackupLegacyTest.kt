package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-E2：v9 → v10 备份格式映射。
 *
 * 这一层出错的后果是"恢复回来少一列/串一列"，而且不报错 —— 只能用测试把它钉住。
 */
class BackupLegacyTest {

    private fun legacy(): Js.Obj = Js.parse(
        """
        {
          "app":"zhuli","version":9,"at":1795183940199,
          "inbox":[
            {"id":1,"source":"notification","package":"com.tencent.mm","label":"微信",
             "title":"开会","text":"明天两点开会","receivedAt":1795100000000,
             "category":"工作","deadlineAt":1795186400000,"amount":0,"note":"",
             "url":"","summary":"","repeat":""}
          ],
          "reminders":[
            {"inboxId":1,"dueAt":1795186400000,"title":"开会","body":"",
             "status":"scheduled","repeat":""}
          ],
          "ledger":[
            {"inboxId":1,"dir":"支","category":"餐饮","amount":38.5,
             "occurredAt":1795100000000,"note":"午饭"}
          ],
          "chat":[{"role":"user","content":"记一下","at":1795100000000}]
        }
        """.trimIndent()
    ) as Js.Obj

    @Test
    fun `四个旧数组都映射到真实表名与列名`() {
        val t = BackupLegacy.convert(legacy())
        assertNotNull(t)
        assertEquals(
            listOf("inbox", "reminders", "ledger", "chat_log"),
            BackupLegacy.tables
        )
        // inbox：驼峰必须落到真实列
        val inbox = t!!.obj("inbox")!!
        val cols = inbox.arr("cols")!!.strings()
        assertTrue(cols.contains("package_name"))
        assertTrue(cols.contains("app_label"))
        assertTrue(cols.contains("received_at"))
        assertTrue(cols.contains("deadline_at"))
        assertTrue(cols.contains("repeat_rule"))
        assertTrue(!cols.contains("receivedAt"))
    }

    @Test
    fun `值按同一顺序落到对应列（不串列）`() {
        val t = BackupLegacy.convert(legacy())!!
        val inbox = t.obj("inbox")!!
        val cols = inbox.arr("cols")!!.strings()
        val row = inbox.arr("rows")!!.items[0] as Js.Arr
        fun v(name: String): Js = row.items[cols.indexOf(name)]
        assertEquals(1L, v("id").asLongOr())
        assertEquals("com.tencent.mm", v("package_name").asStringOr())
        assertEquals("明天两点开会", v("text").asStringOr())
        assertEquals(1795186400000L, v("deadline_at").asLongOr())
        assertEquals("工作", v("category").asStringOr())
    }

    @Test
    fun `账本与聊天也换到真实列名`() {
        val t = BackupLegacy.convert(legacy())!!
        val led = t.obj("ledger")!!
        val lcols = led.arr("cols")!!.strings()
        assertTrue(lcols.contains("inbox_id"))
        assertTrue(lcols.contains("occurred_at"))
        val lrow = led.arr("rows")!!.items[0] as Js.Arr
        assertEquals(38.5, lrow.items[lcols.indexOf("amount")].asDoubleOr(), 0.001)

        val chat = t.obj("chat_log")!!
        val ccols = chat.arr("cols")!!.strings()
        assertEquals(listOf("role", "content", "created_at"), ccols)
    }

    @Test
    fun `老备份里没有的列不能被凭空补出来`() {
        // v9 没有 snooze_count / confidence / basis：补 null 会盖掉默认值 'high'
        val t = BackupLegacy.convert(legacy())!!
        val rcols = t.obj("reminders")!!.arr("cols")!!.strings()
        assertTrue(!rcols.contains("confidence"))
        assertTrue(!rcols.contains("snooze_count"))
        assertTrue(rcols.contains("inbox_id"))
    }

    @Test
    fun `不是旧格式就返回 null（交给报错路径）`() {
        val notLegacy = Js.parse("""{"app":"zhuli","version":10,"tables":{}}""") as Js.Obj
        assertNull(BackupLegacy.convert(notLegacy))
    }

    @Test
    fun `空数组也算命中（恢复 0 条而不是报格式错）`() {
        val onlyEmpty = Js.parse("""{"version":9,"inbox":[]}""") as Js.Obj
        val t = BackupLegacy.convert(onlyEmpty)
        assertNotNull(t)
        assertEquals(0, t!!.obj("inbox")!!.arr("rows")!!.size)
    }
}
