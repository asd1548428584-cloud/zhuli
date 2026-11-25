package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-E2：恢复时的列对齐（跨版本备份最容易静默出错的一步）。
 */
class BackupPlanTest {

    @Test
    fun `只保留目标表也有的列，顺序按备份`() {
        val a = BackupPlan.align(
            srcCols = listOf("id", "text", "future_col", "received_at"),
            targetCols = listOf("id", "text", "received_at", "category")
        )
        assertEquals(listOf("id", "text", "received_at"), a.use)
        assertEquals(listOf("future_col"), a.dropped)
    }

    @Test
    fun `目标表有而备份没有的列不参与（走默认值）`() {
        val a = BackupPlan.align(listOf("id", "text"), listOf("id", "text", "confidence", "basis"))
        assertEquals(listOf("id", "text"), a.use)
        assertTrue(a.dropped.isEmpty())
    }

    @Test
    fun `重复列只取第一次，避免绑定参数个数对不上`() {
        val a = BackupPlan.align(
            srcCols = listOf("id", "text", "text"),
            targetCols = listOf("id", "text")
        )
        assertEquals(listOf("id", "text"), a.use)
        assertEquals(listOf("text"), a.dropped)
    }

    @Test
    fun `完全没有交集时返回空（调用方跳过这张表而不是拼出空 SQL）`() {
        val a = BackupPlan.align(listOf("a", "b"), listOf("c", "d"))
        assertTrue(a.use.isEmpty())
        assertEquals(listOf("a", "b"), a.dropped)
    }

    @Test
    fun `空备份列不崩`() {
        val a = BackupPlan.align(emptyList(), listOf("id"))
        assertTrue(a.use.isEmpty())
        assertTrue(a.dropped.isEmpty())
    }

    // ==================== 收件箱逐行决策（375→304 事故回归） ====================

    @Test
    fun `id 空着就按原 id 写，哪怕同正文的还有一堆`() {
        // 事故现场：视频 App 连推 71 条一模一样的标题，空库恢复时被互相判成重复
        val act = BackupPlan.inboxAction(
            id = 42, idExists = false, existingText = null,
            text = "您关注的up主更新了", sameTextNearby = true
        )
        assertEquals(BackupPlan.InboxAction.INSERT, act)
    }

    @Test
    fun `id 被占且正文相同 → 跳过（重复恢复幂等）`() {
        val act = BackupPlan.inboxAction(
            id = 7, idExists = true, existingText = "取件码 8-1-2233",
            text = "取件码 8-1-2233", sameTextNearby = true
        )
        assertEquals(BackupPlan.InboxAction.SKIP, act)
    }

    @Test
    fun `id 被占但正文不同、附近已有同正文 → 跳过（跨设备合并时不翻倍）`() {
        val act = BackupPlan.inboxAction(
            id = 7, idExists = true, existingText = "别的消息",
            text = "取件码 8-1-2233", sameTextNearby = true
        )
        assertEquals(BackupPlan.InboxAction.SKIP, act)
    }

    @Test
    fun `id 被占、正文不同、附近也没有同正文 → 不带 id 另插一条`() {
        val act = BackupPlan.inboxAction(
            id = 7, idExists = true, existingText = "别的消息",
            text = "取件码 8-1-2233", sameTextNearby = false
        )
        assertEquals(BackupPlan.InboxAction.INSERT_NEW_ID, act)
    }

    @Test
    fun `备份里没有 id 列（坏包）也当新行插入`() {
        val act = BackupPlan.inboxAction(
            id = 0, idExists = false, existingText = null,
            text = "随便", sameTextNearby = false
        )
        assertEquals(BackupPlan.InboxAction.INSERT, act)
    }
}
