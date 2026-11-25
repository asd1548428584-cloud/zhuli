package com.zhuli.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具执行幂等与崩溃恢复（P0 第四条）。
 *
 * 真机事故模型：`create_reminder` 已经把闹钟排进系统 → App 被系统杀掉 →
 * 「执行记录」还没落库 → 用户点「继续」→ 又排一个闹钟。用户看到两个提醒，
 * 而代码日志里只有一条成功。
 *
 * 所以这一组的核心断言不是"执行成功"，而是：
 * - 执行**之前**意图已落库（进程随时死，也必须能判断"可能做了一半"）；
 * - 同一任务同一参数的重复调用只产生一次副作用；
 * - 崩溃后留下的 RUNNING/PENDING 一律判 UNKNOWN，**不自动重试**；
 * - 超时也算"结果未知"，同样不重试；
 * - 明确 FAILED 才允许再试。
 */
class ToolIdempotencyTest {

    private fun provider(vararg responses: ModelResponse) = object : ModelProvider {
        override val capabilities = ModelCapabilities(
            "cloud", "m", ProviderKind.CLOUD, tools = true, tier = 2, leavesDevice = true,
        )
        var i = 0
        override suspend fun complete(request: ModelRequest) =
            responses[minOf(i++, responses.lastIndex)]
    }

    private fun resp(text: String = "", calls: List<ToolCall> = emptyList()) =
        ModelResponse(text, calls, providerId = "cloud", modelId = "m")

    // 幂等逻辑与「审批风险等级」无关，用只读工具避免 ASK 打断执行，
    // 副作用由测试里的计数器模拟（runs++），照样能验证「是否重复执行」。
    private fun tool(name: String, risk: Risk = Risk.READ_ONLY, op: String = "op", run: suspend (String) -> ToolResult) =
        RegisteredTool(ToolSpec(name, "测试工具"), risk, op, execute = run)

    @Test
    fun `执行前先落库意图 执行中状态是 RUNNING`() = runBlocking {
        val store = InMemoryAgentStore()
        var seenStates: List<ToolExecState> = emptyList()
        var seenTaskId = ""
        val t = tool("create_reminder") { _ ->
            // 工具真正开始跑的这一刻，库里必须已经有一条 RUNNING 的执行意图
            val task = store.recent(5).firstOrNull()
            seenTaskId = task?.id.orEmpty()
            seenStates = task?.let { store.toolExecutions(it.id).map { e -> e.state } } ?: emptyList()
            ToolResult.ok("已建提醒")
        }
        val p = provider(
            resp("", listOf(ToolCall("c1", "create_reminder", """{"title":"开会","when":"明天九点"}"""))),
            resp("好了"),
        )
        val policy = PolicyEngine(clock = { 1_000L })
        policy.beginRound(1_000L)
        policy.grant("op")
        val out = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, policy, clock = { 1_000L })
            .start("明天九点提醒我开会")

        assertTrue(out.toString(), out is RunOutcome.Completed)
        assertTrue("执行时没看到 RUNNING 记录（意图没先落库）：$seenStates", seenStates.contains(ToolExecState.RUNNING))
        val taskId = (out as RunOutcome.Completed).task.id
        assertEquals(taskId, seenTaskId)
        val exec = store.toolExecutions(taskId).single()
        assertEquals(ToolExecState.SUCCEEDED, exec.state)
        assertEquals("create_reminder", exec.tool)
        assertNotNull(store.findToolExecution(exec.idemKey))
    }

    @Test
    fun `同一任务同一参数重复调用只执行一次`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = tool("create_reminder") { _ -> runs++; ToolResult.ok("已建提醒") }
        val p = provider(
            resp("", listOf(ToolCall("c1", "create_reminder", """{"title":"开会","when":"明天九点"}"""))),
            // 模型重复发同一个调用（真机上很常见：它没看到结果就再要一次）
            resp("", listOf(ToolCall("c1b", "create_reminder", """{"when":"明天九点","title":"开会"}"""))),
            resp("好了"),
        )
        val policy = PolicyEngine(clock = { 1_000L })
        policy.beginRound(1_000L)
        policy.grant("op")
        val out = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, policy, clock = { 1_000L })
            .start("明天九点提醒我开会")

        assertTrue(out is RunOutcome.Completed)
        assertEquals("重复调用产生了第二次副作用", 1, runs)
        val taskId = (out as RunOutcome.Completed).task.id
        assertTrue(
            "没有向模型说明是复用结果",
            store.steps(taskId).any { it.content.contains("复用") },
        )
    }

    @Test
    fun `崩溃后留下的 RUNNING 记录判为未知 不重复执行`() = runBlocking {
        val store = InMemoryAgentStore()
        val args = """{"title":"开会","when":"明天九点"}"""
        val task = store.create(
            AgentTask(id = "t-crash", goal = "明天九点提醒我开会", status = TaskStatus.PAUSED, createdAt = 0L)
        )
        // 模拟：上次进程在执行中途被杀，意图已落库但结果没落。
        // key 必须与 AgentLoop.idemKey 完全一致（normalizeArgs 里 JSON 字符串值带引号）。
        val key = "t-crash|create_reminder|title=\"开会\",when=\"明天九点\""
        store.beginToolExecution(
            ToolExecution(
                executionId = "e1", taskId = task.id, tool = "create_reminder", args = args,
                idemKey = key, state = ToolExecState.RUNNING, createdAt = 0L, updatedAt = 0L,
            )
        )
        var runs = 0
        val t = tool("create_reminder") { _ -> runs++; ToolResult.ok("已建提醒") }
        val p = provider(
            resp("", listOf(ToolCall("c1", "create_reminder", args))),
            resp("上次那条我不确定有没有建成，你可以看一下任务页"),
        )
        val policy = PolicyEngine(clock = { 1_000L })
        policy.beginRound(1_000L)
        policy.grant("op")
        val out = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, policy, clock = { 1_000L })
            .run(task.id)

        assertTrue(out.toString(), out is RunOutcome.Completed)
        assertEquals("结果未知的操作被重复执行了", 0, runs)
        assertEquals(ToolExecState.UNKNOWN, store.findToolExecution(key)?.state)
        assertTrue(
            "没有把「结果未知」告诉模型",
            store.steps(task.id).any { it.content.contains("未知") },
        )
    }

    @Test
    fun `工具超时记为 UNKNOWN 且不重试`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = tool("slow_write") { _ ->
            runs++
            delay(2_000)
            ToolResult.ok("不该走完")
        }
        val p = provider(
            resp("", listOf(ToolCall("c1", "slow_write", """{"x":1}"""))),
            resp("超时了，我没敢再试"),
        )
        val policy = PolicyEngine(clock = { 1_000L })
        policy.beginRound(1_000L)
        policy.grant("op")
        val loop = AgentLoop(
            ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, policy,
            clock = { 1_000L }, toolTimeoutMs = 120L,
        )
        val out = loop.start("写点东西")
        assertTrue(out is RunOutcome.Completed)
        assertEquals("超时后不应重试", 1, runs)
        val taskId = (out as RunOutcome.Completed).task.id
        assertEquals(ToolExecState.UNKNOWN, store.toolExecutions(taskId).single().state)
    }

    @Test
    fun `工具做了副作用后才抛异常 记为未知并可安全重试被禁止`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        // 模拟真机事故：提醒已经排进系统（runs++），随后抛异常。
        // 这种绝不能标 FAILED 让模型重试，否则用户会看到两个提醒。
        val t = tool("create_reminder") { _ ->
            runs++
            throw IllegalStateException("scanAsync failed after insert")
        }
        val p = provider(
            resp("", listOf(ToolCall("c1", "create_reminder", """{"title":"开会"}"""))),
            resp("这条我不确定建没建成，没敢再试"),
        )
        val policy = PolicyEngine(clock = { 1_000L })
        policy.beginRound(1_000L)
        policy.grant("op")
        val out = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, policy, clock = { 1_000L })
            .start("提醒我开会")

        assertTrue(out is RunOutcome.Completed)
        assertEquals("抛异常的操作被重试了", 1, runs)
        val taskId = (out as RunOutcome.Completed).task.id
        assertEquals("抛异常必须记 UNKNOWN，不能是可重试的 FAILED", ToolExecState.UNKNOWN, store.toolExecutions(taskId).single().state)
    }

    @Test
    fun `恢复一个任务时不会把另一个仍在跑的任务执行记录判为未知`() = runBlocking {
        val store = InMemoryAgentStore()
        val e1 = ToolExecution("e1", "task-A", "create_reminder", "{}", "k1", ToolExecState.RUNNING, createdAt = 1, updatedAt = 1)
        val e2 = ToolExecution("e2", "task-B", "save_fact", "{}", "k2", ToolExecState.RUNNING, createdAt = 1, updatedAt = 1)
        store.beginToolExecution(e1)
        store.beginToolExecution(e2)

        val n = store.recoverRunningExecutions(9_000, "task-A")
        assertEquals("只应恢复 task-A 一条", 1, n)
        assertEquals(ToolExecState.UNKNOWN, store.findToolExecution("k1")!!.state)
        assertEquals("task-B 仍在跑，不能被误判", ToolExecState.RUNNING, store.findToolExecution("k2")!!.state)
    }

    @Test
    fun `明确失败后允许再来一次`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = tool("save_fact") { _ ->
            runs++
            if (runs == 1) ToolResult.fail("数据库忙") else ToolResult.ok("已保存")
        }
        val p = provider(
            resp("", listOf(ToolCall("c1", "save_fact", """{"v":"A"}"""))),
            resp("", listOf(ToolCall("c2", "save_fact", """{"v":"A"}"""))),
            resp("好了"),
        )
        val policy = PolicyEngine(clock = { 1_000L })
        policy.beginRound(1_000L)
        policy.grant("op")
        val out = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, policy, clock = { 1_000L })
            .start("记一下 A")
        assertTrue(out is RunOutcome.Completed)
        assertEquals("明确失败应允许重试", 2, runs)
        val taskId = (out as RunOutcome.Completed).task.id
        assertEquals(ToolExecState.SUCCEEDED, store.toolExecutions(taskId).single().state)
    }

    @Test
    fun `审批中断恢复后同一次调用不会被批准两次执行`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = tool("create_reminder") { _ -> runs++; ToolResult.ok("已建提醒") }
        val task = store.create(
            AgentTask(
                id = "t-approve", goal = "明天九点提醒我", status = TaskStatus.WAITING_APPROVAL,
                createdAt = 0L, pendingCall = ToolCall("c1", "create_reminder", """{"title":"开会","when":"明天"}"""),
                pendingRisk = Risk.WRITE_EXTERNAL.name,
            )
        )
        val loop = AgentLoop(
            router = ModelRouter(listOf(provider(resp("好了")))),
            tools = ToolRegistry(listOf(t)), store = store, policy = PolicyEngine(clock = { 1_000L }),
            clock = { 1_000L },
        )
        loop.approve(task.id, true)
        // 同一个任务再点一次「允许」：状态已经不是 WAITING_APPROVAL，不应再执行
        loop.approve(task.id, true)
        assertEquals("批准被执行了两次", 1, runs)
        assertFalse(store.steps(task.id).isEmpty())
    }
}
