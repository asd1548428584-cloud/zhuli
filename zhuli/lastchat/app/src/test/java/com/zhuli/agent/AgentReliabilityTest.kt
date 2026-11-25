package com.zhuli.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/** 批次 B：多工具循环、取消/暂停、任务并发隔离。 */
class AgentReliabilityTest {

    private fun loop(
        store: AgentStore,
        provider: ModelProvider,
        tools: List<RegisteredTool> = emptyList(),
        clock: () -> Long = { System.currentTimeMillis() },
        maxSteps: Int = 12,
    ) = AgentLoop(ModelRouter(listOf(provider)), ToolRegistry(tools), store, PolicyEngine(), clock = clock, maxSteps = maxSteps)

    @Test
    fun `单工具调用结果回灌并收尾`() = runBlocking {
        val store = InMemoryAgentStore()
        val seen = ArrayList<String>()
        val t = tool("read") { a -> seen.add(a); ToolResult.ok("读到 A") }
        val p = ScriptedProvider(resp("", listOf(call("c1", "read", """{"query":"a"}"""))), resp("结论 A"))
        val out = loop(store, p, listOf(t)).start("查一下 A")

        assertTrue(out is RunOutcome.Completed)
        assertEquals(listOf("""{"query":"a"}"""), seen)
        val second = p.requests[1]
        assertEquals("c1", second.messages.first { it.role == Role.TOOL }.toolCallId)
    }

    @Test
    fun `一次两个工具按顺序执行 结果都回到模型`() = runBlocking {
        val store = InMemoryAgentStore()
        val order = ArrayList<String>()
        val tools = listOf(
            tool("a") { order.add("a"); ToolResult.ok("A 的结果") },
            tool("b") { order.add("b"); ToolResult.ok("B 的结果") },
        )
        val p = ScriptedProvider(
            resp("", listOf(call("c1", "a"), call("c2", "b"))),
            resp("两件事都做完了"),
        )
        val out = loop(store, p, tools).start("做两件事")
        assertTrue(out is RunOutcome.Completed)
        assertEquals(listOf("a", "b"), order)

        val second = p.requests[1]
        val toolMsgs = second.messages.filter { it.role == Role.TOOL }
        assertEquals(listOf("c1", "c2"), toolMsgs.map { it.toolCallId })
        assertEquals(listOf("A 的结果", "B 的结果"), toolMsgs.map { it.content })
    }

    @Test
    fun `工具A的结果能作为工具B的输入`() = runBlocking {
        val store = InMemoryAgentStore()
        var bArgs = ""
        val tools = listOf(
            tool("a") { ToolResult.ok("A-result") },
            tool("b") { a -> bArgs = a; ToolResult.ok("ok") },
        )
        val p = ScriptedProvider(resp("", listOf(call("c1", "a"))), resp("完成"))
        // 第二步把 A 的真实结果塞进 B 的参数；第三步给最终答复
        p.onComplete = { req, i ->
            when (i) {
                0 -> resp("", listOf(call("c1", "a")))
                1 -> resp("", listOf(call("c2", "b", """{"query":"${req.messages.first { it.role == Role.TOOL }.content}"}""")))
                else -> resp("完成")
            }
        }
        val out = loop(store, p, tools).start("串联两步")
        assertTrue(out.toString(), out is RunOutcome.Completed)
        assertTrue("B 没有拿到 A 的结果：$bArgs", bArgs.contains("A-result"))
    }

    @Test
    fun `超过三轮的调用记为未执行而不是静默丢弃`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        // 参数各不相同，避免幂等键把四次调用合并成一次
        val t = tool("read", Risk.READ_ONLY, "read", schemaOf("query")) { runs++; ToolResult.ok("ok") }
        val calls = (1..4).map { call("c$it", "read", """{"query":"q$it"}""") }
        val p = ScriptedProvider(resp("", calls), resp("够了"))
        val out = loop(store, p, listOf(t)).start("四个调用")
        assertTrue(out is RunOutcome.Completed)
        assertEquals("只应执行前 3 个", 3, runs)

        val steps = store.steps((out as RunOutcome.Completed).task.id).filter { it.kind == StepKind.TOOL }
        assertEquals("四个调用都要有结果记录，包含未执行", 4, steps.size)
        assertFalse(steps.last().ok)
        assertTrue(steps.last().content.contains("未执行"))
    }

    @Test
    fun `工具失败后模型继续处理`() = runBlocking {
        val store = InMemoryAgentStore()
        var bRan = false
        val tools = listOf(
            tool("a") { ToolResult.fail("查不到") },
            tool("b") { bRan = true; ToolResult.ok("兜底结果") },
        )
        val p = ScriptedProvider(resp("", listOf(call("c1", "a"))), resp("", listOf(call("c2", "b"))), resp("用兜底回答"))
        val out = loop(store, p, tools).start("查不到也要答")
        assertTrue(out is RunOutcome.Completed)
        assertTrue(bRan)
        assertEquals("用兜底回答", (out as RunOutcome.Completed).task.result)
    }

    @Test
    fun `反复请求同一工具在步数上限处停止`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = tool("read") { runs++; ToolResult.ok("again") }
        val p = ScriptedProvider(resp("", listOf(call("c1", "read"))))
        val out = loop(store, p, listOf(t), maxSteps = 4).start("一直查")
        assertTrue(out is RunOutcome.Paused)
        assertEquals(TaskStatus.PAUSED, (out as RunOutcome.Paused).task.status)
        assertTrue("循环保护失效：跑了 $runs 次", runs <= 2)
    }

    @Test
    fun `推理阶段取消后不再执行工具`() = runBlocking {
        val store = InMemoryAgentStore()
        var executed = false
        val t = tool("write") { executed = true; ToolResult.ok("不该发生") }
        lateinit var l: AgentLoop
        val p = ScriptedProvider(resp("", listOf(call("c1", "write"))), resp("不该被调用"))
        p.onComplete = { _, i ->
            if (i == 0) {
                val id = store.active().first().id
                l.cancel(id, "测试取消")
            }
            null
        }
        l = loop(store, p, listOf(t))
        val out = l.start("取消推理")
        assertTrue(out.toString(), out is RunOutcome.Cancelled)
        assertFalse("取消后仍执行了工具", executed)
        assertEquals(1, p.requests.size)
        assertNotNull(store.get((out as RunOutcome.Cancelled).task.id)!!.cancelReason.ifBlank { null })
    }

    @Test
    fun `工具执行期间取消会中断工具且不触发第二次执行`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = tool("slow") { runs++; delay(5_000); ToolResult.ok("不该完成") }
        val p = ScriptedProvider(resp("", listOf(call("c1", "slow"))), resp("不该被调用"))
        val l = loop(store, p, listOf(t))
        val job = async { l.start("长任务") }
        delay(150)
        val taskId = store.active().first().id
        l.cancel(taskId, "测试中取消")
        val out = withTimeout(3_000) { job.await() }
        assertTrue(out.toString(), out is RunOutcome.Cancelled)
        assertEquals(1, runs)
        assertEquals(TaskStatus.CANCELLED, store.get(taskId)!!.status)
    }

    @Test
    fun `多工具调用中途取消 排队工具不会启动`() = runBlocking {
        val store = InMemoryAgentStore()
        val ran = ArrayList<String>()
        lateinit var loopRef: AgentLoop
        store.create(AgentTask("mt1", "两件事", status = TaskStatus.RUNNING, sessionId = "s-mt"))
        store.appendStep(AgentStep(taskId = "mt1", seq = 0, kind = StepKind.GOAL, content = "两件事", at = 1L))
        val tools = listOf(
            tool("a") { ran.add("a"); loopRef.cancel("mt1", "中途取消"); ToolResult.ok("A") },
            tool("b") { ran.add("b"); ToolResult.ok("B") },
        )
        val p = ScriptedProvider(resp("", listOf(call("c1", "a"), call("c2", "b"))), resp("不该完成"))
        loopRef = loop(store, p, tools)
        val out = withTimeout(3_000) { loopRef.run("mt1") }
        assertTrue(out.toString(), out is RunOutcome.Cancelled)
        assertEquals(listOf("a"), ran)
    }

    @Test
    fun `暂停后不再调用工具 恢复时从原位置继续`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = tool("a") { runs++; ToolResult.ok("A") }
        val paused = store.create(AgentTask(id = "p1", goal = "暂停任务", status = TaskStatus.PAUSED, sessionId = "s-pause"))
        store.appendStep(AgentStep(taskId = "p1", seq = 0, kind = StepKind.GOAL, content = paused.goal, at = 1L))
        val out = loop(store, ScriptedProvider(resp("恢复后完成")), listOf(t)).run("p1")
        assertTrue(out is RunOutcome.Completed)
        assertEquals("恢复后完成", (out as RunOutcome.Completed).task.result)
        assertEquals("暂停期间不该执行工具", 0, runs)
        assertEquals("应当复用原任务 ID", "p1", out.task.id)
    }

    @Test
    fun `已完成任务再次取消不改变状态`() = runBlocking {
        val store = InMemoryAgentStore()
        val done = store.create(AgentTask("d1", "已完成", status = TaskStatus.DONE))
        val l = loop(store, ScriptedProvider(resp("")))
        assertEquals(TaskStatus.DONE, l.cancel("d1")?.status)
        assertTrue(l.run("d1") is RunOutcome.Completed)
    }

    @Test
    fun `同一会话的两个任务串行执行`() = runBlocking {
        val store = InMemoryAgentStore()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val p = object : ModelProvider {
            override val capabilities = ModelCapabilities("c", "m", ProviderKind.CLOUD, tools = true, tier = 2)
            override suspend fun complete(request: ModelRequest): ModelResponse {
                val n = active.incrementAndGet()
                peak.updateAndGet { max(it, n) }
                delay(120)
                active.decrementAndGet()
                return resp("完成")
            }
        }
        val l = loop(store, p)
        val a = async { l.start("任务 A", sessionId = "same-session") }
        val b = async { l.start("任务 B", sessionId = "same-session") }
        assertTrue(a.await() is RunOutcome.Completed)
        assertTrue(b.await() is RunOutcome.Completed)
        assertEquals("同会话必须串行", 1, peak.get())
    }

    @Test
    fun `不同会话的任务可以并行`() = runBlocking {
        val store = InMemoryAgentStore()
        val bothEntered = CompletableDeferred<Unit>()
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val p = object : ModelProvider {
            override val capabilities = ModelCapabilities("c", "m", ProviderKind.CLOUD, tools = true, tier = 2)
            override suspend fun complete(request: ModelRequest): ModelResponse {
                val n = active.incrementAndGet()
                peak.updateAndGet { max(it, n) }
                if (n == 2) bothEntered.complete(Unit)
                delay(150)
                active.decrementAndGet()
                return resp("完成")
            }
        }
        val l = loop(store, p)
        val a = async { l.start("任务 A", sessionId = "session-A") }
        val b = async { l.start("任务 B", sessionId = "session-B") }
        withTimeout(2_000) { bothEntered.await() }
        assertTrue(a.await() is RunOutcome.Completed)
        assertTrue(b.await() is RunOutcome.Completed)
        assertEquals("不同会话应能并行", 2, peak.get())
    }

    @Test
    fun `一个任务等待审批时另一个任务照常完成`() = runBlocking {
        val store = InMemoryAgentStore()
        val write = tool("write", Risk.WRITE_LOCAL, "w") { ToolResult.ok("已写") }
        val p1 = ScriptedProvider(resp("", listOf(call("c1", "write"))))
        val l1 = loop(store, p1, listOf(write))
        val waiting = l1.start("等待审批", sessionId = "s1") as RunOutcome.Suspended

        val p2 = ScriptedProvider(resp("另一个任务完成"))
        val l2 = loop(store, p2, emptyList())
        val other = l2.start("另一个任务", sessionId = "s2")
        assertTrue(other is RunOutcome.Completed)
        assertEquals(TaskStatus.WAITING_APPROVAL, store.get(waiting.task.id)!!.status)
    }

    @Test
    fun `一个任务失败不影响另一个任务`() = runBlocking {
        val store = InMemoryAgentStore()
        val failing = object : ModelProvider {
            override val capabilities = ModelCapabilities("c", "m", ProviderKind.CLOUD, tools = true, tier = 2)
            override suspend fun complete(request: ModelRequest): ModelResponse =
                throw ModelError(ModelError.Kind.FATAL, "配置错误")
        }
        val f = loop(store, failing).start("会失败", sessionId = "f")
        val ok = loop(store, ScriptedProvider(resp("成功"))).start("会成功", sessionId = "o")
        assertTrue(f is RunOutcome.Failed)
        assertTrue(ok is RunOutcome.Completed)
    }

    @Test
    fun `快速连续发送同目标只创建一个任务`() = runBlocking {
        val store = InMemoryAgentStore()
        val p = ScriptedProvider(resp("完成"))
        // 让第一次调用停住，模拟用户连点时首个任务还在执行
        p.onComplete = { _, _ -> delay(400); resp("完成") }
        val l = loop(store, p)
        val a = async { l.start("同一句话", sessionId = "rapid") }
        val b = async { l.start("同一句话", sessionId = "rapid") }
        val c = async { l.start("同一句话", sessionId = "rapid") }
        val results = listOf(a.await(), b.await(), c.await())
        assertEquals(1, results.count { it is RunOutcome.Completed })
        assertEquals(2, results.count { it is RunOutcome.AlreadyActive })
        assertEquals(1, store.recent(50).size)
    }

    /**
     * 批次 B1 回归：步数上限暂停后，恢复必须能继续推进并完成。
     * 旧实现拿任务终身步数当"单次"预算，恢复后预算永不重置——每次 resume 只追加一条
     * NOTE，任务永远卡在 PAUSED 直到撞 hardStepCap 变 FAILED。
     */
    @Test
    fun `步数上限暂停后恢复能继续推进并完成`() = runBlocking {
        val store = InMemoryAgentStore()
        val tools = listOf(tool("step") { ToolResult.ok("ok") })
        val p = ScriptedProvider()
        p.onComplete = { _, i ->
            if (i < 6) resp("", listOf(call("c$i", "step", """{"query":"$i"}""")))
            else resp("长任务完成")
        }
        val l = loop(store, p, tools, maxSteps = 4)
        val first = l.start("长任务")
        assertTrue("应因单次步数上限暂停：$first", first is RunOutcome.Paused)
        val task = store.recent(1).first()
        assertEquals(TaskStatus.PAUSED, store.get(task.id)?.status)

        // 恢复若干次直到完成；每次恢复都应推进（新步数增长），而不是原地打转
        var out: RunOutcome = l.run(task.id)
        var resumes = 0
        var prevSteps = store.stepsCount(task.id)
        while (out is RunOutcome.Paused && resumes < 10) {
            out = l.run(task.id)
            resumes++
            val now = store.stepsCount(task.id)
            assertTrue("恢复第 $resumes 次没有推进（$prevSteps → $now）", now > prevSteps)
            prevSteps = now
        }
        assertTrue("恢复 $resumes 次后仍未完成：$out", out is RunOutcome.Completed)
        assertEquals("长任务完成", store.get(task.id)?.result)
        // 防跑飞上限依然生效：总步数被 hardStepCap=24 兜底
        assertTrue(store.stepsCount(task.id) < 24)
    }
}
