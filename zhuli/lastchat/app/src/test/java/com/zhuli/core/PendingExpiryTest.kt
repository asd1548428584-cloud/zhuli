package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-D1 验收项：待确认超期归档的判定。
 *
 * 最容易错的是边界：正好卡在 24 小时那一刻算不算过期？验证它，避免"用户第二天打开就看不到"。
 */
class PendingExpiryTest {

    private val hour = 60 * 60 * 1000L
    private val now = 1_795_189_569_945L

    @Test
    fun `刚到点还没过宽限期不归档`() {
        // 到期时刻就是现在：用户可能正要点确认
        assertFalse(PendingExpiry.shouldExpire(now, now))
        assertFalse(PendingExpiry.shouldExpire(now - hour, now))
        assertFalse(PendingExpiry.shouldExpire(now - 23 * hour, now))
    }

    @Test
    fun `超过 24 小时才归档`() {
        assertTrue(PendingExpiry.shouldExpire(now - 25 * hour, now))
        assertTrue(PendingExpiry.shouldExpire(now - 30 * 24 * hour, now))
    }

    @Test
    fun `边界正好 24 小时不算过期（宁多留一刻）`() {
        assertFalse(PendingExpiry.shouldExpire(now - 24 * hour, now))
        assertTrue(PendingExpiry.shouldExpire(now - 24 * hour - 1, now))
    }

    @Test
    fun `未来的待确认永远不归档`() {
        assertTrue(!PendingExpiry.shouldExpire(now + hour, now))
        assertTrue(!PendingExpiry.shouldExpire(now + 30 * 24 * hour, now))
    }

    @Test
    fun `非法数据不清掉`() {
        // dueAt<=0 是老库补列/坏数据：留着让人看见，别静默处理
        assertFalse(PendingExpiry.shouldExpire(0, now))
        assertFalse(PendingExpiry.shouldExpire(-5, now))
        // now 非法时不归档（cutoff 返回 0，expirePendingBefore(0) 直接不动）
        assertEquals(0L, PendingExpiry.cutoff(0))
        assertEquals(0L, PendingExpiry.cutoff(-1))
    }

    @Test
    fun `截止点等于 now 减去宽限期`() {
        assertEquals(now - 24 * hour, PendingExpiry.cutoff(now))
        assertEquals(now - 6 * hour, PendingExpiry.cutoff(now, 6 * hour))
    }
}
