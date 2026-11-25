package com.zhuli.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 批次 B：工具参数统一校验 + 三种 Provider 上下文格式。 */
class ToolArgumentsTest {

    private val schema = """{"type":"object","properties":{"query":{"type":"string"},"n":{"type":"integer","minimum":1,"maximum":5},"mode":{"type":"string","enum":["a","b"]}},"required":["query"]}"""
    private val spec = ToolSpec("search", "检索", schema)

    @Test fun `合法参数通过`() {
        assertNull(ToolArguments.validate(spec, """{"query":"取件码","n":3,"mode":"a"}"""))
        assertNull(ToolArguments.validate(spec, """{"query":"x"}"""))
    }

    @Test fun `非法 JSON 被拒绝`() {
        assertTrue(ToolArguments.validate(spec, "not json")!!.contains("JSON"))
    }

    @Test fun `根节点不是对象被拒绝`() {
        assertTrue(ToolArguments.validate(spec, """["a"]""")!!.contains("对象"))
    }

    @Test fun `缺少必填字段被拒绝`() {
        assertTrue(ToolArguments.validate(spec, """{"n":2}""")!!.contains("query"))
    }

    @Test fun `错误类型被拒绝`() {
        assertTrue(ToolArguments.validate(spec, """{"query":"x","n":"3"}""")!!.contains("类型"))
    }

    @Test fun `枚举外取值被拒绝`() {
        assertTrue(ToolArguments.validate(spec, """{"query":"x","mode":"c"}""")!!.contains("以下值之一"))
    }

    @Test fun `越界参数被拒绝`() {
        assertTrue(ToolArguments.validate(spec, """{"query":"x","n":99}""")!!.contains("不能大于"))
        assertTrue(ToolArguments.validate(spec, """{"query":"x","n":0}""")!!.contains("不能小于"))
    }

    @Test fun `参数错误时工具不执行 错误回灌模型`() = runBlocking {
        val store = InMemoryAgentStore()
        var runs = 0
        val t = tool("search", Risk.READ_ONLY, "read", schema) { runs++; ToolResult.ok("不该发生") }
        val p = ScriptedProvider(
            resp("", listOf(call("c1", "search", """{"n":2}"""))),
            resp("我少给了关键词"),
        )
        val out = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(listOf(t)), store, PolicyEngine()).start("查一下")
        assertTrue(out is RunOutcome.Completed)
        assertEquals(0, runs)
        val toolMsg = p.requests[1].messages.first { it.role == Role.TOOL }
        assertTrue(toolMsg.content.contains("参数校验失败"))
    }

    @Test fun `未知工具有明确错误结果`() = runBlocking {
        val store = InMemoryAgentStore()
        val p = ScriptedProvider(resp("", listOf(call("c1", "no_such_tool"))), resp("换个工具"))
        val out = AgentLoop(ModelRouter(listOf(p)), ToolRegistry(emptyList()), store, PolicyEngine()).start("查一下")
        assertTrue(out is RunOutcome.Completed)
        assertTrue(store.steps((out as RunOutcome.Completed).task.id).any { it.content.contains("未知工具") })
    }

    private fun sampleMessages() = listOf(
        AgentMessage.system("你是助理"),
        AgentMessage.user("查一下"),
        AgentMessage.assistant("", listOf(call("call_1", "search", """{"query":"x"}"""))),
        AgentMessage.tool("call_1", "查到了 A"),
    )

    @Test fun `OpenAI 格式保留工具调用与结果配对`() {
        val raw = ProviderMessageFormat.openAi(sampleMessages())
        assertTrue(raw.contains("tool_call_id"))
        assertTrue(raw.contains("call_1"))
        assertTrue(raw.contains("查到了 A"))
        assertTrue(raw.contains("\"tool_calls\""))
    }

    @Test fun `Claude 格式保留工具调用与结果配对`() {
        val raw = ProviderMessageFormat.claude(sampleMessages())
        assertTrue(raw.contains("tool_use"))
        assertTrue(raw.contains("tool_result"))
        assertTrue(raw.contains("\"tool_use_id\":\"call_1\""))
        assertTrue(raw.contains("\"system\":\"你是助理\""))
    }

    @Test fun `Gemini 格式保留工具调用与结果配对`() {
        val raw = ProviderMessageFormat.gemini(sampleMessages())
        assertTrue(raw.contains("functionCall"))
        assertTrue(raw.contains("functionResponse"))
        assertTrue(raw.contains("search"))
        assertTrue(raw.contains("查到了 A"))
    }
}
