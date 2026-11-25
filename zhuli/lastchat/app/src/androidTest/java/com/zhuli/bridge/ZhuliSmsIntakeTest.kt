package com.zhuli.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zhuli.core.CourierIntake
import com.zhuli.core.InboxDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 短信（取件码）通道验收：B7 迁移后这条链路整段丢失，B8 补回来。
 *
 * 覆盖两件真机要确认的事：
 * 1. 一条典型取件码短信能被解析成"快递"类别并落库（不是靠通知监听碰运气）；
 * 2. 非快递短信不进库（隐私口径：读完即丢）。
 */
@RunWith(AndroidJUnit4::class)
class ZhuliSmsIntakeTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun countCourierSince(prefix: String): Int {
        val db = InboxDatabase(ctx)
        var n = 0
        try {
            db.readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM inbox WHERE category=? AND note LIKE ?",
                arrayOf("快递", "%$prefix%"),
            ).use { c -> if (c.moveToFirst()) n = c.getInt(0) }
        } finally {
            runCatching { db.close() }
        }
        return n
    }

    @Test
    fun courierSms_isParsedAndStored() {
        val body = "【丰巢】您的包裹已到亚运村站智能柜，取件码 8823，请及时取件，超时将退回。"
        val db = InboxDatabase(ctx)
        val ok = try {
            CourierIntake.intake(
                ctx = ctx,
                db = db,
                text = body,
                receivedAt = System.currentTimeMillis(),
                packageName = CourierIntake.SOURCE_PACKAGE,
                appLabel = CourierIntake.SOURCE_LABEL,
            )
        } finally {
            runCatching { db.close() }
        }
        assertTrue("取件码短信应被识别为快递入库", ok)
        assertTrue("库里应出现这条快递记录", countCourierSince("8823") >= 1)
    }

    @Test
    fun nonCourierSms_isIgnored() {
        val body = "【中国移动】您本月流量已使用 15.2GB，剩余 4.8GB，回复 TD 退订。"
        val db = InboxDatabase(ctx)
        val ok = try {
            CourierIntake.intake(
                ctx = ctx,
                db = db,
                text = body,
                receivedAt = System.currentTimeMillis(),
                packageName = CourierIntake.SOURCE_PACKAGE,
                appLabel = CourierIntake.SOURCE_LABEL,
            )
        } finally {
            runCatching { db.close() }
        }
        assertFalse("非快递短信不应入库", ok)
    }

    @Test
    fun smsPermission_isGranted_andScanRuns() {
        assertTrue("真机应已授予 READ_SMS（Shizuku 向导或手动）", CourierIntake.hasSmsPermission(ctx))
        val r = CourierIntake.scanSms(ctx, days = 3, max = 5)
        assertEquals("扫短信不应报错：${r.error}", "", r.error)
        println("[zhuli] scanSms matched=${r.matched} added=${r.added}")
    }

    @Test
    fun briefNumbers_areComputableLocally() {
        val n = collectBriefNumbers(ctx)
        assertTrue("待办数不应为负", n.todoCount >= 0)
        assertTrue("待取快递数不应为负", n.courierCount >= 0)
        assertTrue("近 7 天支出不应为负", n.weekSpend >= 0.0)
        println("[zhuli] brief todo=${n.todoCount} courier=${n.courierCount} spend=${n.weekSpend}")
    }
}
