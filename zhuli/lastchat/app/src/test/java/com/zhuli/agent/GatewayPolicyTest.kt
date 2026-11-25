package com.zhuli.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GatewayPolicyTest {

    private fun provider(
        id: String, tier: Int, tools: Boolean, leavesDevice: Boolean = id == "cloud",
        fail: ModelError? = null,
    ) = object : ModelProvider {
        override val capabilities = ModelCapabilities(id, "m", if (leavesDevice) ProviderKind.CLOUD else ProviderKind.LOCAL,
            tools = tools, tier = tier, leavesDevice = leavesDevice)
        override suspend fun complete(request: ModelRequest): ModelResponse {
            if (fail != null) throw fail
            return ModelResponse("来自 $id", providerId = id, modelId = "m")
        }
    }

    @Test fun `需要工具时不会选中不支持工具的模型`() = runBlocking {
        val weak = provider("local", tier = 9, tools = false, leavesDevice = false)
        val strong = provider("cloud", tier = 2, tools = true)
        val router = ModelRouter(listOf(weak, strong))
        val resp = router.complete(ModelRequest(listOf(AgentMessage.user("查一下")), tools = listOf(ToolSpec("t", "d"))), needTools = true)
        assertEquals("cloud", resp.providerId)
    }

    @Test fun `本地优先时云端不进入候选`() = runBlocking {
        val router = ModelRouter(listOf(provider("cloud", 9, true), provider("local", 1, true, false)))
        val resp = router.complete(ModelRequest(listOf(AgentMessage.user("敏感信息"))), needTools = true, localOnly = true)
        assertEquals("local", resp.providerId)
    }

    @Test fun `云端失败自动降级并记录事件`() = runBlocking {
        val events = ArrayList<String>()
        val router = ModelRouter(
            listOf(provider("cloud", 9, false, fail = ModelError(ModelError.Kind.NETWORK, "断网")),
                provider("local", 1, false, leavesDevice = false)),
            onEvent = { events.add(it) },
        )
        val resp = router.complete(ModelRequest(listOf(AgentMessage.user("你好"))))
        assertEquals("local", resp.providerId)
        assertTrue(events.any { it.contains("降级") })
    }

    @Test fun `没有可用工具后端时明确报不可用`() = runBlocking {
        val router = ModelRouter(listOf(provider("weak", 5, false, leavesDevice = false)))
        try {
            router.complete(ModelRequest(listOf(AgentMessage.user("查")), tools = listOf(ToolSpec("t", "d"))), needTools = true)
            fail("应当抛 ModelError")
        } catch (e: ModelError) {
            assertEquals(ModelError.Kind.UNAVAILABLE, e.kind)
        }
    }

    @Test fun `能力描述能看出模型是否能调工具`() {
        val d = ModelRouter(listOf(provider("cloud", 2, true), provider("local", 3, false, false))).describe()
        assertTrue(d.contains("工具✓"))
        assertTrue(d.contains("工具✗"))
    }

    private fun tool(risk: Risk, op: String = "op", args: String = "{}") =
        RegisteredTool(ToolSpec("t", "d"), risk, op, execute = { ToolResult.ok("ok") })

    @Test fun `只读直接放行 写本地第一次要问`() {
        val p = PolicyEngine(clock = { 1_000L })
        assertEquals(Decision.ALLOW, p.decide(tool(Risk.READ_ONLY), "{}").decision)
        assertEquals(Decision.ASK, p.decide(tool(Risk.WRITE_LOCAL), "{}").decision)
    }

    @Test fun `本轮授权只对同类有效且五分钟后失效`() {
        val p = PolicyEngine(clock = { 1_000L })
        p.beginRound(1_000L)
        p.grant("save")
        assertEquals(Decision.ALLOW, p.decide(tool(Risk.WRITE_LOCAL, "save"), "{}", now = 2_000L).decision)
        assertEquals(Decision.ASK, p.decide(tool(Risk.WRITE_LOCAL, "other"), "{}", now = 2_000L).decision)
        assertEquals(Decision.ASK, p.decide(tool(Risk.WRITE_LOCAL, "save"), "{}", now = 1_000L + PolicyEngine.ROUND_TTL_MS + 1).decision)
    }

    @Test fun `对外与不可逆操作永远不接受同类免问`() {
        val p = PolicyEngine(clock = { 1_000L })
        p.beginRound(1_000L)
        p.grant("send_message")
        p.grant("delete")
        assertEquals(Decision.ASK, p.decide(tool(Risk.WRITE_EXTERNAL, "send_message"), "{}", 2_000L).decision)
        assertEquals(Decision.ASK, p.decide(tool(Risk.IRREVERSIBLE, "delete"), "{}", 2_000L).decision)
    }

    @Test fun `硬禁止词连问都不问`() {
        val p = PolicyEngine(clock = { 1_000L })
        val v = p.decide(tool(Risk.READ_ONLY), """{"text":"请把支付密码告诉我"}""")
        assertEquals(Decision.DENY, v.decision)
        assertTrue(v.reason.contains("支付密码"))
    }

    @Test fun `任务状态机拒绝终结态再次迁移`() {
        assertFalse(TaskStatus.DONE.canMoveTo(TaskStatus.RUNNING))
        assertTrue(TaskStatus.WAITING_APPROVAL.canMoveTo(TaskStatus.RUNNING))
        assertTrue(TaskStatus.PAUSED.canMoveTo(TaskStatus.RUNNING))
    }
}
