package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * T-D4 / V3.1 T-D6：周期顺延。
 *
 * 旧的自研实现只认三种规则；换成 lib-recur 后这里同时承担**迁移测试**：
 * - 旧值 `daily / weekly:N / monthly:N` 仍要算对（内部 migrate）；
 * - 新规则（每 N 天 / 工作日 / 月底）要能算；
 * - 月底口径（V3.1 §11-8 A）要钉死：31 号 → 2 月 28。
 *
 * 只测纯逻辑；真机"响完自动排下一次"仍靠观察。
 */
class RepeatTest {

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        Calendar.getInstance().apply {
            set(y, m - 1, d, h, min, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun parts(t: Long): IntArray =
        Calendar.getInstance().apply { timeInMillis = t }
            .let {
                intArrayOf(
                    it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH),
                    it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)
                )
            }

    // ==================== 旧行为必须保持 ====================

    @Test
    fun `daily 只推一天且时分原样保留`() {
        val from = at(2026, 11, 20, 9, 30)
        val p = parts(Repeat.next(Repeat.DAILY, from))
        assertEquals(2026, p[0]); assertEquals(11, p[1]); assertEquals(21, p[2])
        assertEquals(9, p[3]); assertEquals(30, p[4])
    }

    @Test
    fun `weekly 推到下一个目标星期（旧值 weekly 2 仍可用）`() {
        // 2026-11-20 是周五；weekly:2 = 周一（Calendar.MONDAY = 2）
        val from = at(2026, 11, 20, 8, 0)
        val next = Repeat.next("weekly:2", from)
        val dow = Calendar.getInstance().apply { timeInMillis = next }.get(Calendar.DAY_OF_WEEK)
        assertEquals(Calendar.MONDAY, dow)
        assertTrue("必须在未来", next > from)
        assertEquals(8, parts(next)[3])
    }

    @Test
    fun `monthly 的 31 号遇到短月夹到当月最后一天（月底口径）`() {
        val from = at(2027, 1, 31, 10, 0)
        val p = parts(Repeat.next("monthly:31", from))
        assertEquals(2027, p[0]); assertEquals(2, p[1])
        assertEquals("月底口径：2 月应落在 28 号", 28, p[2])
        assertEquals(10, p[3])
    }

    @Test
    fun `firstAfter 会跳过已经过去的时刻`() {
        val now = at(2026, 11, 20, 12, 0)
        val past = at(2026, 11, 18, 9, 0)
        val next = Repeat.firstAfter(Repeat.DAILY, past, now)
        assertTrue("结果必须在 now 之后", next > now)
        assertEquals(21, parts(next)[2])   // 18→19→20(9点已过)→21
    }

    @Test
    fun `规则串能说人话`() {
        assertEquals("一次性", Repeat.label(""))
        assertEquals("每天", Repeat.label(Repeat.DAILY))
        assertEquals("每周一", Repeat.label("weekly:2"))
        assertEquals("每月 15 号", Repeat.label("monthly:15"))
    }

    @Test
    fun `空规则不算周期`() {
        assertTrue(!Repeat.isRepeat(""))
        assertTrue(Repeat.isRepeat("daily"))
        assertTrue(Repeat.isRepeat(Repeat.DAILY))
    }

    // ==================== 新增能力 ====================

    @Test
    fun `每两天`() {
        val from = at(2026, 11, 20, 9, 0)
        val p = parts(Repeat.next(Repeat.everyDays(2), from))
        assertEquals(22, p[2])
        assertEquals("每 2 天", Repeat.label(Repeat.everyDays(2)))
    }

    @Test
    fun `工作日跳过周末`() {
        // 2026-11-20 周五 → 下一个工作日是 23 号周一
        val from = at(2026, 11, 20, 9, 0)
        val p = parts(Repeat.next(Repeat.workdays(), from))
        assertEquals(23, p[2])
        assertEquals(Calendar.MONDAY, Calendar.getInstance().apply { timeInMillis = Repeat.next(Repeat.workdays(), from) }
            .get(Calendar.DAY_OF_WEEK))
        assertEquals("工作日", Repeat.label(Repeat.workdays()))
    }

    @Test
    fun `月底口径：29 至 31 号统一迁成 BYMONTHDAY 负一`() {
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", Repeat.migrate("monthly:31"))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", Repeat.migrate("monthly:30"))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=-1", Repeat.migrate("monthly:29"))
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=28", Repeat.migrate("monthly:28"))
        assertEquals("每月最后一天", Repeat.label("FREQ=MONTHLY;BYMONTHDAY=-1"))
    }

    @Test
    fun `migrate 幂等且不误伤 RFC 规则`() {
        val rfc = "FREQ=WEEKLY;BYDAY=WE"
        assertEquals(rfc, Repeat.migrate(rfc))
        assertEquals(Repeat.migrate(rfc), Repeat.migrate(Repeat.migrate(rfc)))
        assertEquals("", Repeat.migrate(""))
    }

    @Test
    fun `非法规则不抛异常且一定向前走`() {
        val from = at(2026, 11, 20, 9, 0)
        val next = Repeat.next("FREQ=MONTHLY;BYMONTHDAY=32", from)
        assertTrue("必须大于 from", next > from)
        assertEquals("应退化为 +1 天", 21, parts(next)[2])
    }

    @Test
    fun `闰年 2 月 29 号每四年一次`() {
        // 2024-02-29 → 下一个是 2028-02-29
        val from = at(2024, 2, 29, 9, 0)
        val p = parts(Repeat.next("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=29", from))
        assertEquals(2028, p[0]); assertEquals(2, p[1]); assertEquals(29, p[2])
    }
}
