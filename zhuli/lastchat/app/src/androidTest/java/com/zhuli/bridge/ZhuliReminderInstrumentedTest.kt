package com.zhuli.bridge

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-G2/B4 真机验收：create_reminder 的调度链路——设 3 秒后精确闹钟 → ZhuliAlarmReceiver 到点发通知 →
 * 系统通知栏出现（NotificationManager.activeNotifications 轮询）。全程不依赖云模型。
 */
@RunWith(AndroidJUnit4::class)
class ZhuliReminderInstrumentedTest {

    private val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun scheduleFutureReminder_firesNotification() {
        val reqCode = 987_654
        val r = ZhuliReminderScheduler.schedule(ctx, reqCode, "B4测试提醒XYZ", System.currentTimeMillis() + 60_000)
        assertTrue("设闹钟应成功：${r.message}", r.ok)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var found = false
        // 60s 延迟 + ROM policy 对齐（实测推迟 ~30s）→ 等 150s
        repeat(300) {
            Thread.sleep(500)
            found = nm.activeNotifications.any { n ->
                n.tag == ZhuliAlarmReceiver.NOTIFICATION_TAG &&
                    n.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.contains("B4测试提醒XYZ") == true
            }
            if (found) return@repeat
        }
        assertTrue("到点应收到系统通知（150s 内），实际未出现", found)
        ZhuliReminderScheduler.cancel(ctx, reqCode)
    }

    @Test
    fun schedulePastTime_rejected() {
        val r = ZhuliReminderScheduler.schedule(ctx, 111, "过去的提醒", System.currentTimeMillis() - 10_000)
        assertFalse("过去时刻应拒绝", r.ok)
        assertTrue(r.message.contains("已过"))
    }
}
