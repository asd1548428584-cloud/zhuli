package com.zhuli.agent

import org.junit.Assert.*
import org.junit.Test

/** 批次 B：状态机、CAS、审批信封兼容性与上下文裁剪。 */
class AgentStateMachineTest {

    @Test fun `合法状态转换表`() {
        assertTrue(TaskStatus.PENDING.canMoveTo(TaskStatus.RUNNING))
        assertTrue(TaskStatus.RUNNING.canMoveTo(TaskStatus.WAITING_APPROVAL))
        assertTrue(TaskStatus.WAITING_APPROVAL.canMoveTo(TaskStatus.RUNNING))
        assertTrue(TaskStatus.RUNNING.canMoveTo(TaskStatus.CANCELLING))
        assertTrue(TaskStatus.CANCELLING.canMoveTo(TaskStatus.CANCELLED))
        assertTrue(TaskStatus.PAUSED.canMoveTo(TaskStatus.RUNNING))
    }

    @Test fun `非法状态转换被拒绝`() {
        assertFalse(TaskStatus.DONE.canMoveTo(TaskStatus.RUNNING))
        assertFalse(TaskStatus.CANCELLED.canMoveTo(TaskStatus.PAUSED))
        assertFalse(TaskStatus.CANCELLING.canMoveTo(TaskStatus.RUNNING))
        assertFalse(TaskStatus.CANCELLING.canMoveTo(TaskStatus.DONE))
        assertFalse(TaskStatus.WAITING_APPROVAL.canMoveTo(TaskStatus.DONE))
        assertFalse(TaskStatus.CANCELLING.terminal)
    }

    @Test fun `存储层 CAS 只让一个并发路径成功`() {
        val store = InMemoryAgentStore()
        store.create(AgentTask("cas", "目标", status = TaskStatus.PENDING))
        assertTrue(store.transition("cas", TaskStatus.PENDING, TaskStatus.RUNNING, 10L))
        assertFalse("旧状态再次迁移必须失败", store.transition("cas", TaskStatus.PENDING, TaskStatus.RUNNING, 11L))
        assertEquals(TaskStatus.RUNNING, store.get("cas")!!.status)

        store.update(store.get("cas")!!.copy(status = TaskStatus.DONE))
        assertFalse(store.transition("cas", TaskStatus.DONE, TaskStatus.RUNNING, 12L))
        assertEquals(TaskStatus.DONE, store.get("cas")!!.status)
    }

    @Test fun `进程重启把 CANCELLING 落为 CANCELLED 而不是 PAUSED`() {
        val store = InMemoryAgentStore()
        store.create(AgentTask("c1", "目标", status = TaskStatus.RUNNING))
        store.create(AgentTask("c2", "目标", status = TaskStatus.CANCELLING))
        assertEquals(1, store.markOrphansPaused(20L))
        assertEquals(TaskStatus.PAUSED, store.get("c1")!!.status)
        assertEquals(TaskStatus.CANCELLED, store.get("c2")!!.status)
    }

    @Test fun `审批信封能跨编解码保留全部上下文`() {
        val call = ToolCall("c1", "write", """{"query":"A"}""")
        val approval = TaskContextCodec.newApproval("t1", "session-9", call, Risk.WRITE_EXTERNAL, 1_000L, listOf(call("c2", "read")))
        val raw = TaskContextCodec.encode(TaskContext(sessionId = "session-9", phase = "AWAIT_APPROVAL", approval = approval))
        val back = TaskContextCodec.decode(raw, "t1")

        assertEquals("session-9", back.sessionId)
        assertEquals("AWAIT_APPROVAL", back.phase)
        assertEquals("t1", back.approval!!.taskId)
        assertEquals(approval.fingerprint, back.approval!!.fingerprint)
        assertEquals(listOf("c2"), CallCodec.decode(back.approval!!.remainingCallsJson).map { it.id })
        assertTrue(back.approval!!.valid(1_001L))
        assertFalse(back.approval!!.valid(1_000L + TaskContextCodec.APPROVAL_TTL_MS + 1))
    }

    @Test fun `v16 旧格式的 pending_call 仍可读`() {
        val raw = CallCodec.encode(listOf(ToolCall("old", "save_fact", """{"v":"x"}""")))
        val back = TaskContextCodec.decode(raw, "t-old")
        assertEquals("old", back.approval!!.call.id)
        assertEquals("save_fact", back.approval!!.call.name)
        assertFalse("旧数据没有可靠的授权时间，必须视为过期", back.approval!!.valid(0L))
    }

    @Test fun `上下文裁剪保留系统提示与当前目标`() {
        val l = loop()
        val task = AgentTask("ctx1", "我的取件码是多少", sessionId = "s-ctx")
        val steps = ArrayList<AgentStep>()
        steps.add(AgentStep(taskId = "ctx1", seq = 0, kind = StepKind.GOAL, content = task.goal, at = 1))
        repeat(20) { i ->
            steps.add(AgentStep(taskId = "ctx1", seq = steps.size, kind = StepKind.ASSISTANT, content = "第 $i 轮", callsJson = CallCodec.encode(listOf(call("c$i", "read"))), at = 1))
            steps.add(AgentStep(taskId = "ctx1", seq = steps.size, kind = StepKind.TOOL, content = "结果 $i", toolCallId = "c$i", at = 1))
        }
        val messages = l.buildMessages(task, steps)
        assertEquals(Role.SYSTEM, messages.first().role)
        assertTrue(messages.first().content.contains("zhuli"))
        assertEquals(Role.USER, messages[1].role)
        assertEquals("我的取件码是多少", messages[1].content)
        assertTrue("应按组裁剪", messages.count { it.role == Role.ASSISTANT } <= AgentLoop.MAX_CONTEXT_GROUPS)
        assertTrue(messages.last().content.contains("结果 19"))
    }

    @Test fun `裁剪后每一条工具调用都有对应结果`() {
        val l = loop()
        val task = AgentTask("ctx2", "目标")
        val calls = listOf(call("c1", "a"), call("c2", "b"))
        val steps = listOf(
            AgentStep(taskId = "ctx2", seq = 0, kind = StepKind.GOAL, content = "目标", at = 1),
            AgentStep(taskId = "ctx2", seq = 1, kind = StepKind.ASSISTANT, content = "", callsJson = CallCodec.encode(calls), at = 1),
            AgentStep(taskId = "ctx2", seq = 2, kind = StepKind.TOOL, content = "A 结果", toolCallId = "c1", at = 1),
        )
        val messages = l.buildMessages(task, steps)
        val results = messages.filter { it.role == Role.TOOL }
        assertEquals(2, results.size)
        assertEquals(listOf("c1", "c2"), results.map { it.toolCallId })
        assertTrue(results.last().content.contains("未执行"))
    }

    @Test fun `孤立的 tool 结果不会被静默丢弃`() {
        val l = loop()
        val task = AgentTask("ctx3", "目标")
        val steps = listOf(
            AgentStep(taskId = "ctx3", seq = 0, kind = StepKind.GOAL, content = "目标", at = 1),
            AgentStep(taskId = "ctx3", seq = 1, kind = StepKind.TOOL, content = "孤立结果", toolCallId = "orphan", at = 1),
        )
        val messages = l.buildMessages(task, steps)
        assertTrue(messages.any { it.role == Role.TOOL && it.content == "孤立结果" })
    }

    private fun loop() = AgentLoop(
        ModelRouter(listOf(object : ModelProvider {
            override val capabilities = ModelCapabilities("c", "m", ProviderKind.CLOUD, tools = true, tier = 2)
            override suspend fun complete(request: ModelRequest) = resp("unused")
        })),
        ToolRegistry(emptyList()), InMemoryAgentStore(), PolicyEngine(),
    )
}
