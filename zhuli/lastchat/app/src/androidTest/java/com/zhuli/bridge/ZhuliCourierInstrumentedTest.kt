package com.zhuli.bridge

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-G1 真机验收：注入 LastChat 的 list_courier Tool 在真机上从 assets 快照查出真实取件码。
 * 这是 fork 路线"对话层能查到本地库并回答"决策点的最终证据。
 */
@RunWith(AndroidJUnit4::class)
class ZhuliCourierInstrumentedTest {

    @Test
    fun queryCourier_fromAssetsSnapshot_returnsRealPickupCodes() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val tool = ZhuliCourierTool(context, assetName = "zhuli_inbox_snapshot.db")
        val now = System.currentTimeMillis()
        val rows = tool.queryCourier(now)
        assertTrue("真机应查出快递记录，实际 ${rows.size} 条", rows.isNotEmpty())
        val allText = rows.joinToString("\n") { it.note + " " + it.text }
        assertTrue("应含真实取件码 6825，实际: $allText", allText.contains("6825"))
        val answer = CourierAnswer.answer(rows, now)
        assertTrue("回答应含待取快递:", answer.contains("快递待取"))
        assertTrue("回答不应错答没有待取", !answer.contains("没有待取"))
    }

    @Test
    fun asTool_hasCorrectNameAndSchema() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tool = ZhuliCourierTool(context)
        val t = tool.asTool()
        assertEquals("list_courier", t.name)
        assertTrue(t.description.contains("快递"))
    }
}
