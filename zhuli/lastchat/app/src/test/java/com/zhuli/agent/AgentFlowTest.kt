package com.zhuli.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * 真循环测试：用假模型provider + 真工具注册表 + 真策略引擎 + 真内存存储。
 * 这里测的是「Agent 是否真的把事办完」，不是「接口是否存在」。
 */
class AgentFlowTest {

    private class ScriptedProvider(
        vararg val responses: ModelResponse,
        val failsWith: ModelError? = null,
    ) : ModelProvider {
        override val capabilities = ModelCapabilities(
            providerId = "test-cloud", modelId = "scripted", kind = ProviderKind.CLOUD,
            tools = true, tier = 2,
        )
        var calls = 0
        val requests = ArrayList<ModelRequest>()
        var index = 0

        override suspend fun complete(request: ModelRequest): ModelResponse {
            requests.add(request)
            calls++
            failsWith?.let { throw it }
            return responses[minOf(index++, responses.lastIndex)]
        }
    }

    private fun resp(text: String, calls: List<ToolCall> = emptyList()) = ModelResponse(
        text = text, toolCalls = calls, providerId = "test-cloud", modelId = "scripted",
    )

    private fun call(id: String, name: String, args: String = "{}") = ToolCall(id, name, args)

    private fun tool(name: String, risk: Risk, op: String, onRun: (String) -> ToolResult) = RegisteredTool(
        spec = ToolSpec(name, "测试工具：$name"), risk = risk, op = op, execute = { onRun(it) }
    )

    @Test fun `多步骤任务真的调工具并用结果收尾`() = runBlocking {
        val seen = ArrayList<String>()
        val p = ScriptedProvider(
            resp("", listOf(call("c1", "search_memory", """{"query":"取件码"}"""))),
            resp("查到了：取件码 1234"),
        )
        val loop = AgentLoop(
            router = ModelRouter(listOf(p)),
            tools = ToolRegistry(listOf(tool("search_memory", Risk.READ_ONLY, "read") { args ->
                seen.add(args); ToolResult.ok("取件码 1234")
            })),
            store = InMemoryAgentStore(), policy = PolicyEngine(), clock = { 1_000L },
        )

        val outcome = loop.start("我的取件码是多少")
        assertTrue(outcome.toString(), outcome is RunOutcome.Completed)
        assertEquals("查到了：取件码 1234", (outcome as RunOutcome.Completed).task.result)
        assertEquals(1, seen.size)
        assertTrue(seen[0].contains("取件码"))

        // 上下文必须成对：assistant.tool_calls 后面紧跟对应 tool 结果，否则 OpenAI 端点直接 400
        val second = p.requests[1]
        val assistant = second.messages.indexOfLast { it.role == Role.ASSISTANT }
        assertTrue(assistant >= 0)
        assertTrue(second.messages[assistant].toolCalls.isNotEmpty())
        assertEquals("c1", second.messages[assistant + 1].toolCallId)
    }

    @Test fun `写操作挂起并持久化 审批后恢复完成`() = runBlocking {
        val store = InMemoryAgentStore()
        val executed = ArrayList<String>()
        val writeTool = tool("save_fact", Risk.WRITE_LOCAL, "save_fact") { args ->
            executed.add(args); ToolResult.ok("已记住")
        }
        val p1 = ScriptedProvider(resp("", listOf(call("c2", "save_fact", """{"text":"记住我住在北京"}"""))))
        val loop1 = AgentLoop(ModelRouter(listOf(p1)), ToolRegistry(listOf(writeTool)), store, PolicyEngine(), clock = { 2_000L })

        val suspended = loop1.start("记住我住在北京")
        assertTrue(suspended is RunOutcome.Suspended)
        val taskId = (suspended as RunOutcome.Suspended).task.id
        assertEquals(TaskStatus.WAITING_APPROVAL, store.get(taskId)?.status)
        assertEquals("save_fact", store.get(taskId)?.pendingCall?.name)
        assertTrue(store.steps(taskId).any { it.content.contains("等待用户确认") })
        assertTrue("未确认就执行了副作用", executed.isEmpty())

        // 模拟进程重启：换一个新的 loop，只共享持久化存储
        // approve() 直接执行被批准的那次调用；恢复后的第一次模型调用应给出最终答复
        val p2 = ScriptedProvider(resp("已经记好了"))
        val loop2 = AgentLoop(ModelRouter(listOf(p2)), ToolRegistry(listOf(writeTool)), store, PolicyEngine(), clock = { 2_000L })
        val done = loop2.approve(taskId, true)
        assertTrue(done.toString(), done is RunOutcome.Completed)
        assertEquals(1, executed.size)
        assertEquals(TaskStatus.DONE, store.get(taskId)?.status)
        assertNull(store.get(taskId)?.pendingCall)
    }

    @Test fun `用户拒绝写操作 工具不执行且模型拿到拒绝结果`() = runBlocking {
        val store = InMemoryAgentStore()
        var executed = false
        val t = tool("save_fact", Risk.WRITE_LOCAL, "save_fact") { executed = true; ToolResult.ok("不该发生") }
        val p = ScriptedProvider(
            resp("", listOf(call("c3", "save_fact"))),
            resp("好吧，那我不记了"),
        )
        val loop = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, PolicyEngine(), clock = { 3_000L })
        val s = loop.start("记住这个") as RunOutcome.Suspended
        val done = loop.approve(s.task.id, false)
        assertTrue(done is RunOutcome.Completed)
        assertFalse(executed)
        assertTrue(store.steps(s.task.id).any { it.content.contains("用户拒绝") })
    }

    @Test fun `硬禁止词直接拒绝 连确认都不问`() = runBlocking {
        val store = InMemoryAgentStore()
        var executed = false
        val t = tool("read_sms", Risk.READ_ONLY, "read") { executed = true; ToolResult.ok("验证码 123456") }
        val p = ScriptedProvider(
            resp("", listOf(call("c4", "read_sms", """{"query":"短信验证码"}"""))),
            resp("这类信息我不能查"),
        )
        val loop = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, PolicyEngine())
        val out = loop.start("查一下验证码")
        assertTrue(out is RunOutcome.Completed)
        assertFalse("命中硬禁止仍然执行了", executed)
        assertTrue(store.steps((out as RunOutcome.Completed).task.id).any { it.content.contains("拒绝执行") })
    }

    @Test fun `同一目标在窗口内不重复开跑`() = runBlocking {
        val store = InMemoryAgentStore()
        val t = tool("save_fact", Risk.WRITE_LOCAL, "save_fact") { ToolResult.ok("ok") }
        val p = ScriptedProvider(resp("", listOf(call("c5", "save_fact"))))
        val loop = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, PolicyEngine(), clock = { 4_000L })
        loop.start("明天下午三点提醒我开会")
        val second = loop.start("明天下午三点提醒我开会")
        assertTrue(second is RunOutcome.AlreadyActive)
        assertEquals(1, p.calls)
    }

    @Test fun `挂起的任务从库里恢复而不是丢上下文`() = runBlocking {
        val store = InMemoryAgentStore()
        val stuck = store.create(AgentTask(id = "stuck", goal = "继续上次的查询", status = TaskStatus.RUNNING, createdAt = 10L))
        store.update(stuck.copy(status = TaskStatus.RUNNING))
        val p = ScriptedProvider(resp("接着说：结果是 A"))
        val loop = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(emptyList()), store, PolicyEngine(), clock = { 5_000L })
        val out = loop.run("stuck")
        assertTrue(out is RunOutcome.Completed)
        assertTrue(p.requests[0].messages.any { it.role == Role.USER && it.content == "继续上次的查询" })
    }

    @Test fun `步数到上限时暂停而不是失败`() = runBlocking {
        val store = InMemoryAgentStore()
        val t = tool("read", Risk.READ_ONLY, "read") { ToolResult.ok("ok") }
        val p = ScriptedProvider(resp("", listOf(call("c6", "read"))))
        val loop = AgentLoop(
            ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, PolicyEngine(),
            clock = { 6_000L }, maxSteps = 4,
        )
        val out = loop.start("一直查")
        assertTrue(out.toString(), out is RunOutcome.Paused)
        assertEquals(TaskStatus.PAUSED, (out as RunOutcome.Paused).task.status)
        assertTrue(store.get(out.task.id)!!.active)
    }

    @Test fun `取消后不再调用模型 状态可终结`() = runBlocking {
        val store = InMemoryAgentStore()
        val p = ScriptedProvider(resp("", listOf(call("c7", "write"))))
        // 需要确认的工具，start 后停在 WAITING_APPROVAL，才能测「取消后不再跑」
        val t = tool("write", Risk.WRITE_LOCAL, "write") { ToolResult.ok("ok") }
        val loop = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, PolicyEngine(), clock = { 7_000L })
        val s = loop.start("取消测试") as RunOutcome.Suspended
        loop.cancel(s.task.id)
        val after = loop.run(s.task.id)
        // 批次 B：取消是独立结局，不能再伪装成"已完成"
        assertTrue(after is RunOutcome.Cancelled)
        assertEquals(TaskStatus.CANCELLED, (after as RunOutcome.Cancelled).task.status)
    }
}
