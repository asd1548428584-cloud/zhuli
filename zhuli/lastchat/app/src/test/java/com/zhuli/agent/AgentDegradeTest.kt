package com.zhuli.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 网关降级测试（P0 第二条）。
 *
 * 真机上的故障形态不是「只有 UNAVAILABLE」：断网是 NETWORK、429/5xx 是 TRANSIENT、
 * Key 过期是 FATAL。上一版 AgentLoop 只捕 UNAVAILABLE，所以断网时任务直接失败，
 * 用户根本看不到「先用本机答一句」这条兜底路径。
 *
 * 这里逐类验证：能降的都降、不能降的（隐私 BLOCKED）绝不换后端、
 * 降级后工具列表必须清空、已拿到的工具结果必须保留、本地模型不得编造执行记录。
 */
class AgentDegradeTest {

    private class Fake(
        override val capabilities: ModelCapabilities,
        private val answer: String = "本地答复",
        private val script: List<ModelResponse> = emptyList(),
        private val fail: ModelError? = null,
    ) : ModelProvider {
        val requests = ArrayList<ModelRequest>()
        var index = 0
        override suspend fun complete(request: ModelRequest): ModelResponse {
            requests.add(request)
            fail?.let { throw it }
            if (script.isNotEmpty()) return script[minOf(index++, script.lastIndex)]
            return ModelResponse(answer, providerId = capabilities.providerId, modelId = capabilities.modelId)
        }
    }

    private fun cloud(script: List<ModelResponse> = emptyList(), fail: ModelError? = null) = Fake(
        ModelCapabilities("cloud", "big", ProviderKind.CLOUD, tools = true, tier = 2, leavesDevice = true),
        script = script, fail = fail,
    )

    private fun local(answer: String = "本地答复") = Fake(
        ModelCapabilities("local", "tiny", ProviderKind.LOCAL, tools = false, tier = 1, leavesDevice = false),
        answer = answer,
    )

    private fun readTool(hit: MutableList<String>) = RegisteredTool(
        ToolSpec("search_memory", "查记忆"), Risk.READ_ONLY, "search",
    ) { args -> hit.add(args); ToolResult.ok("取件码 1234") }

    private fun loop(c: Fake, l: Fake, store: AgentStore = InMemoryAgentStore(), onEvent: (String) -> Unit = {}) =
        AgentLoop(
            router = ModelRouter(listOf(c, l), onEvent = onEvent),
            tools = ToolRegistry(listOf(readTool(ArrayList()))),
            store = store, policy = PolicyEngine(), clock = { 1_000L }, onEvent = onEvent,
        )

    private fun kindCases() = listOf(
        ModelError.Kind.NETWORK to "断网",
        ModelError.Kind.TRANSIENT to "HTTP 429",
        ModelError.Kind.FATAL to "HTTP 401",
    )

    @Test
    fun `云端正常时不降级 工具结果照常回灌`() = runBlocking {
        val hit = ArrayList<String>()
        val calls = ArrayList<ToolCall>()
        val c = Fake(
            ModelCapabilities("cloud", "big", ProviderKind.CLOUD, tools = true, tier = 2, leavesDevice = true),
            script = listOf(
                ModelResponse("", listOf(ToolCall("c1", "search_memory", """{"query":"取件码"}""")), providerId = "cloud", modelId = "big"),
                ModelResponse("取件码 1234", providerId = "cloud", modelId = "big"),
            ),
        )
        val l = local()
        val loop = AgentLoop(
            router = ModelRouter(listOf(c, l)),
            tools = ToolRegistry(listOf(readTool(hit))),
            store = InMemoryAgentStore(), policy = PolicyEngine(), clock = { 1_000L },
        )
        val out = loop.start("我的取件码是多少")
        assertTrue(out is RunOutcome.Completed)
        assertEquals("取件码 1234", (out as RunOutcome.Completed).task.result)
        assertEquals("云端正常时不该调用本地模型", 0, l.requests.size)
        assertEquals(1, hit.size)
        assertEquals(2, c.requests.size)
        // 第二轮的请求必须带工具声明（说明没有误判成降级）
        assertTrue(c.requests[1].tools.isNotEmpty())
    }

    @Test
    fun `网络_临时_配置错误都会降级到本地纯文本`() = runBlocking {
        for ((kind, msg) in kindCases()) {
            val c = cloud(fail = ModelError(kind, msg))
            val l = local("本机答：网络不太好")
            val events = ArrayList<String>()
            val out = loop(c, l, onEvent = { events.add(it) }).start("我上次说的取件码是多少")

            assertTrue("$kind 应当降级成功：$out", out is RunOutcome.Completed)
            assertEquals("本机答：网络不太好", (out as RunOutcome.Completed).task.result)
            assertEquals("降级后应由本地作答", 1, l.requests.size)
            assertTrue("降级时工具列表必须清空", l.requests[0].tools.isEmpty())
            assertTrue("降级请求本身必须标记 localOnly", l.requests[0].localOnly)
            assertTrue(events.any { it.contains("降级") })
        }
    }

    @Test
    fun `隐私 BLOCKED 不通过换模型绕过`() = runBlocking {
        val c = cloud(fail = ModelError(ModelError.Kind.BLOCKED, "含证件号"))
        val l = local()
        val out = loop(c, l).start("把我身份证号发过去")
        assertTrue("BLOCKED 应当直接失败：$out", out is RunOutcome.Failed)
        assertTrue((out as RunOutcome.Failed).error.contains("隐私闸门"))
        assertEquals("BLOCKED 时不许调用本地模型续答", 0, l.requests.size)
    }

    @Test
    fun `模型未安装时明确报没有可用模型 不假装成功`() = runBlocking {
        val c = cloud(fail = ModelError(ModelError.Kind.UNAVAILABLE, "没配 Key"))
        val l = Fake(
            ModelCapabilities("local", "none", ProviderKind.LOCAL, tools = false, tier = 1, leavesDevice = false),
            fail = ModelError(ModelError.Kind.UNAVAILABLE, "model_not_installed"),
        )
        val out = loop(c, l).start("查一下快递")
        assertTrue(out is RunOutcome.Failed)
        assertTrue((out as RunOutcome.Failed).error.contains("没有可用模型"))
    }

    @Test
    fun `降级保留已拿到的工具结果 并明确告知哪些没做`() = runBlocking {
        val calls = ArrayList<String>()
        val store = InMemoryAgentStore()
        // 第 1 轮云端给工具调用，第 2 轮云端失败 → 本地兜底
        val c = Fake(
            ModelCapabilities("cloud", "big", ProviderKind.CLOUD, tools = true, tier = 2, leavesDevice = true),
            script = listOf(
                ModelResponse("", listOf(ToolCall("c1", "search_memory", """{"query":"取件码"}""")), providerId = "cloud", modelId = "big"),
            ),
        )
        var calls2 = 0
        val flaky = object : ModelProvider {
            override val capabilities = c.capabilities
            override suspend fun complete(request: ModelRequest): ModelResponse {
                calls2++
                if (calls2 == 1) return ModelResponse("", listOf(ToolCall("c1", "search_memory", """{"query":"取件码"}""")), providerId = "cloud", modelId = "big")
                throw ModelError(ModelError.Kind.NETWORK, "断网")
            }
        }
        val l = local("根据已查到的结果：取件码是 1234（提醒功能这次没做成）")
        val loop = AgentLoop(
            router = ModelRouter(listOf(flaky, l)),
            tools = ToolRegistry(listOf(readTool(calls))),
            store = store, policy = PolicyEngine(), clock = { 1_000L },
        )
        val out = loop.start("查我的取件码，然后建个提醒")
        assertTrue(out is RunOutcome.Completed)

        val localReq = l.requests.single()
        // 已执行过的工具结果必须在上下文里（不能因为降级就丢掉已发生的事）
        assertTrue(
            "降级请求里丢了工具结果",
            localReq.messages.any { it.role == Role.TOOL && it.content.contains("1234") },
        )
        // 必须明确说明「哪些做完了、哪些没做」，而不是让本地模型自由发挥
        val notes = localReq.messages.filter { it.role == Role.SYSTEM }.joinToString("|") { it.content }
        assertTrue("缺少降级说明：$notes", notes.contains("不能查库"))
        assertTrue("没有说明已完成的步骤：$notes", notes.contains("search_memory"))
        assertTrue("没有禁止编造：$notes", notes.contains("不要编造执行记录"))
        assertTrue("工具列表必须清空", localReq.tools.isEmpty())
        // 步骤里也要留一条，任务页可追溯
        val taskId = (out as RunOutcome.Completed).task.id
        assertTrue(store.steps(taskId).any { it.content.contains("降级") })
    }

    @Test
    fun `任务级 localOnly 会传到网关 云端一次都不调`() = runBlocking {
        val c = cloud()
        val l = local("本机回答")
        val out = loop(c, l).start("别联网，帮我看下备忘", localOnly = true)
        assertTrue(out is RunOutcome.Completed)
        assertEquals("localOnly 任务不应调用云端", 0, c.requests.size)
        assertEquals(1, l.requests.size)
        assertTrue("localOnly 必须带进 ModelRequest", l.requests[0].localOnly)
    }

    @Test
    fun `本地模型不会被当成支持工具调用`() = runBlocking {
        val l = local()
        val store = InMemoryAgentStore()
        val loop = AgentLoop(
            router = ModelRouter(listOf(l)),
            tools = ToolRegistry(listOf(readTool(ArrayList()))),
            store = store, policy = PolicyEngine(), clock = { 1_000L },
        )
        val out = loop.start("查一下我的快递")
        // 需要工具但没有支持工具的模型：要么降级成本地纯文本（tools 清空），要么如实失败，
        // 但绝不能把工具声明塞给本地模型、然后假装它会调用。
        assertTrue(out is RunOutcome.Completed || out is RunOutcome.Failed)
        for (r in l.requests) {
            assertTrue("本地模型收到了工具声明", r.tools.isEmpty())
        }
        assertFalse(out is RunOutcome.Completed && (out as RunOutcome.Completed).task.result.contains("已执行"))
    }
}
