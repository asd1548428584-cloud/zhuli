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
    fun queryCourier_readsLocalInboxAndReturnsCodes() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val tool = ZhuliCourierTool(context, assetName = "zhuli_inbox_snapshot.db")
        val now = System.currentTimeMillis()
        val rows = tool.queryCourier(now)
        assertTrue("真机应查出快递记录，实际 ${rows.size} 条", rows.isNotEmpty())
        val allText = rows.joinToString("\n") { it.note + " " + it.text }
        // 不再断言某个具体取件码：那是用户真实数据，写进断言等于把 PII 带进仓库。
        // 断言「结构上确实有码」即可，换任何一台设备都成立。
        assertTrue(
            "结果应含取件码结构，实际: ${allText.take(200)}",
            Regex("取件码\\s*[:：]?\\s*[0-9A-Za-z][0-9A-Za-z-]{2,}").containsMatchIn(allText),
        )
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
