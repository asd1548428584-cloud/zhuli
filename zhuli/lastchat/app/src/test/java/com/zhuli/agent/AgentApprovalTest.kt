package com.zhuli.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 批次 B：审批绑定、过期、重复点击、进程恢复与高风险拦截。 */
class AgentApprovalTest {

    private fun loop(store: AgentStore, p: ModelProvider, tools: List<RegisteredTool>, clock: () -> Long = { 1_000L }) =
        AgentLoop(ModelRouter(listOf(p)), ToolRegistry(tools), store, PolicyEngine(), clock = clock)

    /** 审批夹具不声明必填参数，避免参数校验先于审批触发（校验本身另有专门测试）。 */
    private fun writeTool(onRun: (String) -> ToolResult) =
        tool("write", Risk.WRITE_LOCAL, "write", """{"type":"object","properties":{}}""", onRun)

    @Test
    fun `批准时指纹不匹配就拒绝执行`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = writeTool { runs++; ToolResult.ok("已写") }
        val p = ScriptedProvider(resp("", listOf(call("c1", "write", """{"query":"A"}"""))))
        val l = loop(store, p, listOf(t))
        val s = l.start("写入 A", sessionId = "approval-fp") as RunOutcome.Suspended

        val out = l.approve(s.task.id, true, fingerprint = "不一样")
        assertTrue(out is RunOutcome.Failed)
        assertEquals(0, runs)
        assertTrue((out as RunOutcome.Failed).error.contains("参数已变化"))
    }

    @Test
    fun `过期的审批不能执行任何操作`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = writeTool { runs++; ToolResult.ok("已写") }
        val c = call("c1", "write", """{"query":"A"}""")
        val expired = TaskContextCodec.newApproval("exp1", "s", c, Risk.WRITE_LOCAL, 1_000L).copy(expiresAt = 999L)
        store.create(
            AgentTask(
                id = "exp1", goal = "写入 A", status = TaskStatus.WAITING_APPROVAL,
                pendingCall = c, pendingRisk = Risk.WRITE_LOCAL.name, pendingApproval = expired,
                sessionId = "s", createdAt = 1_000L,
            )
        )
        store.appendStep(AgentStep(taskId = "exp1", seq = 0, kind = StepKind.GOAL, content = "写入 A", at = 1_000L))
        val out = loop(store, ScriptedProvider(resp("已经过期了")), listOf(t)).approve("exp1", true, expired.fingerprint)
        assertTrue(out.toString(), out is RunOutcome.Completed)
        assertEquals(0, runs)
        assertTrue(store.steps("exp1").any { it.content.contains("过期") })
        assertEquals(TaskStatus.DONE, store.get("exp1")!!.status)
    }

    @Test
    fun `取消之后再批准不会执行工具`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = writeTool { runs++; ToolResult.ok("已写") }
        val p = ScriptedProvider(resp("", listOf(call("c1", "write"))))
        val l = loop(store, p, listOf(t))
        val s = l.start("写入", sessionId = "approval-cancel") as RunOutcome.Suspended
        l.cancel(s.task.id, "用户改主意了")
        val out = l.approve(s.task.id, true)
        assertEquals(0, runs)
        assertEquals(TaskStatus.CANCELLED, store.get(s.task.id)!!.status)
        assertFalse(out is RunOutcome.Suspended)
    }

    @Test
    fun `重复点击批准只执行一次`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = writeTool { runs++; ToolResult.ok("已写") }
        val p = ScriptedProvider(resp("", listOf(call("c1", "write"))), resp("好了"))
        val l = loop(store, p, listOf(t))
        val s = l.start("写入", sessionId = "approval-repeat") as RunOutcome.Suspended
        val first = l.approve(s.task.id, true)
        val second = l.approve(s.task.id, true)
        assertTrue(first is RunOutcome.Completed)
        assertTrue("重复批准应被忽略，实际 $second", second is RunOutcome.Completed || second is RunOutcome.AlreadyActive)
        assertEquals(1, runs)
    }

    @Test
    fun `进程重启后仍能按原任务批准并保留完整上下文`() = runBlocking {
        val store = InMemoryAgentStore()
        val runs = ArrayList<String>()
        val t = writeTool { a -> runs.add(a); ToolResult.ok("已写") }
        val p1 = ScriptedProvider(resp("", listOf(call("c1", "write", """{"query":"A"}"""))))
        val before = loop(store, p1, listOf(t))
        val s = before.start("把 A 写下来", sessionId = "approval-restart") as RunOutcome.Suspended

        // 新进程：新的 loop / 新的 policy，只共享持久化 store
        val p2 = ScriptedProvider(resp("已经写好 A 了"))
        val after = loop(store, p2, listOf(t))
        val out = after.approve(s.task.id, true)
        assertTrue(out.toString(), out is RunOutcome.Completed)
        assertEquals(listOf("""{"query":"A"}"""), runs)

        val req = p2.requests[0]
        assertTrue("丢了原始目标", req.messages.any { it.role == Role.USER && it.content == "把 A 写下来" })
        val assistant = req.messages.first { it.role == Role.ASSISTANT }
        assertEquals("c1", assistant.toolCalls.single().id)
        assertEquals("已写", req.messages.first { it.role == Role.TOOL }.content)
        assertTrue("丢了系统提示词", req.messages.first().role == Role.SYSTEM)
    }

    @Test
    fun `批准之后同一轮剩余的调用按顺序执行`() = runBlocking {
        val store = InMemoryAgentStore()
        val ran = ArrayList<String>()
        val tools = listOf(
            writeTool { ran.add("write"); ToolResult.ok("W") },
            tool("read", Risk.READ_ONLY, "read") { ran.add("read"); ToolResult.ok("R") },
        )
        val p = ScriptedProvider(
            resp("", listOf(call("c1", "write"), call("c2", "read"))),
            resp("两件都做完了"),
        )
        val l = loop(store, p, tools)
        val s = l.start("先写后读", sessionId = "approval-rest") as RunOutcome.Suspended
        val out = l.approve(s.task.id, true)
        assertTrue(out.toString(), out is RunOutcome.Completed)
        assertEquals(listOf("write", "read"), ran)
    }

    @Test
    fun `对外高风险工具没有批准就绝不执行`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = tool("create_reminder", Risk.WRITE_EXTERNAL, "create_reminder", schemaOf("query")) {
            runs++; ToolResult.ok("已建提醒")
        }
        val p = ScriptedProvider(resp("", listOf(call("c1", "create_reminder", """{"query":"明天九点"}"""))))
        val l = loop(store, p, listOf(t))
        val out = l.start("建提醒", sessionId = "approval-external")
        assertTrue(out is RunOutcome.Suspended)
        assertEquals(0, runs)
        assertEquals(Risk.WRITE_EXTERNAL.name, store.get((out as RunOutcome.Suspended).task.id)!!.pendingApproval!!.risk)
    }

    @Test
    fun `不可逆操作不会因为同类免问而放行`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = tool("delete", Risk.IRREVERSIBLE, "delete", schemaOf("query")) { runs++; ToolResult.ok("已删") }
        val p = ScriptedProvider(resp("", listOf(call("c1", "delete", """{"query":"全部"}"""))), resp("不该到这里"))
        val policy = PolicyEngine(clock = { 1_000L })
        policy.beginRound(1_000L)
        policy.grant("delete") // 即使参数里带了同类授权
        val l = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, policy, clock = { 1_000L })
        val out = l.start("删除全部", sessionId = "approval-irrev")
        assertTrue(out is RunOutcome.Suspended)
        assertEquals(0, runs)
    }

    @Test
    fun `批准页面能看到具体操作与参数`() = runBlocking {
        val store = InMemoryAgentStore()
        val t = writeTool { ToolResult.ok("ok") }
        val args = """{"query":"把备忘写到本地"}"""
        val p = ScriptedProvider(resp("", listOf(call("c1", "write", args))))
        val out = loop(store, p, listOf(t)).start("写入", sessionId = "approval-display") as RunOutcome.Suspended
        assertEquals("write", out.call.name)
        assertEquals(args, out.call.arguments)
        assertEquals(Risk.WRITE_LOCAL, out.risk)
        assertTrue(store.steps(out.task.id).any { it.content.contains("write") && it.content.contains("确认") })
    }

    @Test
    fun `拒绝后模型收到明确失败结果`() = runBlocking {
        val store = InMemoryAgentStore()
        val t = writeTool { ToolResult.ok("不该执行") }
        val p = ScriptedProvider(resp("", listOf(call("c1", "write"))), resp("那我不写了"))
        val l = loop(store, p, listOf(t))
        val s = l.start("写入", sessionId = "approval-reject") as RunOutcome.Suspended
        val out = l.approve(s.task.id, false)
        assertTrue(out is RunOutcome.Completed)
        val toolStep = store.steps(s.task.id).first { it.kind == StepKind.TOOL }
        assertFalse(toolStep.ok)
        assertTrue(toolStep.content.contains("拒绝"))
        assertTrue(p.requests[1].messages.any { it.role == Role.TOOL && it.content.contains("拒绝") })
    }
}
