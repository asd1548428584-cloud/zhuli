package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * V3.1 T-D7：xk-time 兜底解析的契约测试。
 *
 * 只钉"我们依赖的性质"：未来标记、日期、明确时刻的时/分、空输入不抛。
 * 不钉它的内部实现细节——库升级允许表述更准，但不许把过去算成未来。
 */
class TimeParserTest {

    private fun base(): Long = Calendar.getInstance().apply {
        set(2026, 10, 20, 10, 0, 0)   // 2026-11-20 10:00（周五）
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun parts(t: Long): IntArray =
        Calendar.getInstance().apply { timeInMillis = t }
            .let {
                intArrayOf(
                    it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH),
                    it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE), it.get(Calendar.DAY_OF_WEEK)
                )
            }

    @Test
    fun `明天下午三点`() {
        val hit = TimeParser.firstFuture("明天下午三点开会", base())
        assertNotNull(hit)
        val p = parts(hit!!.at)
        assertEquals(2026, p[0]); assertEquals(11, p[1]); assertEquals(21, p[2])
        assertEquals(15, p[3]); assertEquals(0, p[4])
        assertTrue(hit.future)
        assertFalse(hit.allDay)
        assertTrue("应带出命中原文", hit.matched.isNotBlank())
    }

    @Test
    fun `后天上午十点`() {
        val hit = TimeParser.firstFuture("后天上午十点交材料", base())!!
        val p = parts(hit.at)
        assertEquals(22, p[2]); assertEquals(10, p[3])
    }

    @Test
    fun `下周三落在周三且在未来`() {
        val hit = TimeParser.firstFuture("下周三给张总回电话", base())!!
        val p = parts(hit.at)
        assertEquals("周三", Calendar.WEDNESDAY, p[5])
        assertTrue("必须在基准之后", hit.at > base())
    }

    @Test
    fun `过去的表述不能被当成未来`() {
        val hit = TimeParser.first("去年5月的事", base())
        assertNotNull(hit)
        assertFalse("去年不是未来", hit!!.future)
    }

    @Test
    fun `空文本返回空表且不抛`() {
        assertTrue(TimeParser.parse("   ", base()).isEmpty())
        assertTrue(TimeParser.parse("", base()).isEmpty())
        assertEquals(null, TimeParser.firstFuture("", base()))
    }

    @Test
    fun `纯噪声不炸`() {
        // 没有时间词也应安全返回空
        val hits = TimeParser.parse("这是一条没有任何时间信息的普通通知", base())
        assertTrue("不应凭空造时间", hits.all { it.matched.isNotBlank() })
    }
}
