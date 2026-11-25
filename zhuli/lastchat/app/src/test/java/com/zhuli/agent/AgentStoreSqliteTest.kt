package com.zhuli.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhuli.core.InboxDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Agent 任务的**真实 SQLite** 测试（P1 第六条）。
 *
 * `AgentFlowTest` 用的是 InMemoryAgentStore —— 它证明循环逻辑对，但证明不了
 * "进程被杀之后任务还在"。这一组全部跑在真库上，专门盯持久化：
 * 任务读写、步骤顺序、等待审批跨进程恢复、重复执行保护、执行记录状态机。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.zhuli.testutil.PlainTestApp::class)
class AgentStoreSqliteTest {

    private lateinit var ctx: Context
    private lateinit var db: InboxDatabase
    private lateinit var store: SqlAgentStore

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("zhuli_inbox.db")
        db = InboxDatabase(ctx)
        store = SqlAgentStore(db)
    }

    @After
    fun tearDown() {
        try { db.close() } catch (_: Throwable) {}
        ctx.deleteDatabase("zhuli_inbox.db")
    }

    private fun task(id: String = "t1", status: TaskStatus = TaskStatus.PENDING, localOnly: Boolean = false) =
        AgentTask(
            id = id, goal = "查一下取件码", status = status,
            createdAt = 1_000, updatedAt = 1_000, dedupeKey = id, origin = "chat", localOnly = localOnly,
        )

    @Test
    fun `任务保存后换一个 Store 实例仍能读到（模拟进程重启）`() {
        store.create(task(localOnly = true))
        // 换实例 = 新进程打开同一个库
        val reopened = SqlAgentStore(InboxDatabase(ctx))
        val t = reopened.get("t1")
        assertNotNull(t)
        assertEquals("查一下取件码", t!!.goal)
        assertEquals(TaskStatus.PENDING, t.status)
        assertTrue("localOnly 必须跨进程存活（隐私要求不能重启就忘）", t.localOnly)
    }

    @Test
    fun `步骤按 seq 顺序读回 计数同步`() {
        store.create(task())
        store.appendStep(AgentStep(taskId = "t1", seq = 0, kind = StepKind.GOAL, content = "目标", at = 1))
        store.appendStep(AgentStep(taskId = "t1", seq = 0, kind = StepKind.ASSISTANT, content = "第一步", at = 2))
        store.appendStep(AgentStep(taskId = "t1", seq = 0, kind = StepKind.TOOL, content = "结果", at = 3))

        val steps = store.steps("t1")
        assertEquals(3, steps.size)
        assertEquals(listOf(0, 1, 2), steps.map { it.seq })
        assertEquals("目标", steps[0].content)
        assertEquals(3, store.steps("t1").size)
        assertEquals(3, store.get("t1")!!.stepCount)
    }

    @Test
    fun `等待审批的任务跨进程恢复后 pendingCall 原样还在`() {
        val call = ToolCall("c1", "create_reminder", """{"title":"开会","when":"明天九点"}""")
        store.create(
            task(status = TaskStatus.WAITING_APPROVAL).copy(
                pendingCall = call, pendingRisk = Risk.WRITE_EXTERNAL.name,
            )
        )
        val reopened = SqlAgentStore(InboxDatabase(ctx))
        val t = reopened.get("t1")!!
        assertEquals(TaskStatus.WAITING_APPROVAL, t.status)
        assertEquals("create_reminder", t.pendingCall?.name)
        assertEquals("""{"title":"开会","when":"明天九点"}""", t.pendingCall?.arguments)
        assertEquals(Risk.WRITE_EXTERNAL.name, t.pendingRisk)
        // 新进程启动时不能把它清掉（否则用户点「允许」时找不到待确认调用）
        assertEquals(0, reopened.markOrphansPaused(2_000)) // 只动 RUNNING
        assertEquals(TaskStatus.WAITING_APPROVAL, reopened.get("t1")!!.status)
    }

    @Test
    fun `进程重启把 RUNNING 任务标成 PAUSED`() {
        store.create(task(status = TaskStatus.RUNNING))
        store.create(task(id = "t2", status = TaskStatus.DONE))
        assertEquals(1, store.markOrphansPaused(5_000))
        assertEquals(TaskStatus.PAUSED, store.get("t1")!!.status)
        assertEquals(TaskStatus.DONE, store.get("t2")!!.status)
    }

    @Test
    fun `同一幂等键只落一行 成功状态不会被覆盖`() {
        val e1 = ToolExecution(
            executionId = "e1", taskId = "t1", tool = "create_reminder", args = "{}",
            idemKey = "k1", state = ToolExecState.RUNNING, createdAt = 1, updatedAt = 1,
        )
        store.beginToolExecution(e1)
        val second = store.beginToolExecution(e1.copy(executionId = "e2"))
        assertEquals("同一幂等键插了第二行", "e1", second.executionId)
        assertEquals(1, store.toolExecutions("t1").size)

        store.updateToolExecution(e1.copy(state = ToolExecState.SUCCEEDED, result = "已建提醒", updatedAt = 3))
        val done = store.findToolExecution("k1")!!
        assertEquals(ToolExecState.SUCCEEDED, done.state)
        assertEquals("已建提醒", done.result)
    }

    @Test
    fun `恢复时执行中的记录判为 UNKNOWN 而不是失败`() {
        store.beginToolExecution(
            ToolExecution(
                executionId = "e1", taskId = "t1", tool = "create_reminder", args = "{}",
                idemKey = "k1", state = ToolExecState.RUNNING, createdAt = 1, updatedAt = 1,
            )
        )
        store.beginToolExecution(
            ToolExecution(
                executionId = "e2", taskId = "t1", tool = "save_fact", args = "{}",
                idemKey = "k2", state = ToolExecState.SUCCEEDED, createdAt = 1, updatedAt = 1,
            )
        )
        assertEquals(1, store.recoverRunningExecutions(9_000))
        assertEquals(ToolExecState.UNKNOWN, store.findToolExecution("k1")!!.state)
        assertEquals("已成功的不能被改", ToolExecState.SUCCEEDED, store.findToolExecution("k2")!!.state)
    }

    @Test
    fun `真库上按任务隔离恢复 不误伤另一个任务的执行记录`() {
        store.beginToolExecution(
            ToolExecution("e1", "task-A", "create_reminder", "{}", "k1", ToolExecState.RUNNING, createdAt = 1, updatedAt = 1)
        )
        store.beginToolExecution(
            ToolExecution("e2", "task-B", "save_fact", "{}", "k2", ToolExecState.RUNNING, createdAt = 1, updatedAt = 1)
        )
        assertEquals(1, store.recoverRunningExecutions(9_000, "task-A"))
        assertEquals(ToolExecState.UNKNOWN, store.findToolExecution("k1")!!.state)
        assertEquals(ToolExecState.RUNNING, store.findToolExecution("k2")!!.state)
    }

    @Test
    fun `去重键在窗口内只认活跃任务`() {
        store.create(task(id = "a").copy(createdAt = 1_000, updatedAt = 1_000))
        store.create(task(id = "b", status = TaskStatus.DONE).copy(createdAt = 1_000, updatedAt = 1_000, dedupeKey = "b"))
        assertNotNull(store.findActiveByDedupeKey("a", 0))
        assertNull("已完成的任务不该再挡新任务", store.findActiveByDedupeKey("b", 0))
    }

    @Test
    fun `当前有效任务列表不含已终结任务`() {
        store.create(task(id = "run", status = TaskStatus.RUNNING))
        store.create(task(id = "done", status = TaskStatus.DONE))
        store.create(task(id = "wait", status = TaskStatus.WAITING_APPROVAL))
        val active = store.active().map { it.id }.toSet()
        assertEquals(setOf("run", "wait"), active)
    }
}
