package com.zhuli.agent

/** 批次 B 测试共用夹具。只在 test source set 中，不进 APK。 */

internal class ScriptedProvider(
    private vararg val responses: ModelResponse,
) : ModelProvider {
    override val capabilities = ModelCapabilities(
        providerId = "test-cloud", modelId = "scripted", kind = ProviderKind.CLOUD,
        tools = true, tier = 2,
    )
    var index = 0
    val requests = ArrayList<ModelRequest>()

    /** 每次调用前可插桩：返回 null 表示使用脚本里的下一个响应。 */
    var onComplete: (suspend (ModelRequest, Int) -> ModelResponse?)? = null

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val i = index++
        requests.add(request)
        return onComplete?.invoke(request, i) ?: responses[minOf(i, responses.lastIndex)]
    }
}

internal fun resp(text: String = "", calls: List<ToolCall> = emptyList()) = ModelResponse(
    text = text, toolCalls = calls, providerId = "test-cloud", modelId = "scripted",
)

internal fun call(id: String, name: String, args: String = "{}") = ToolCall(id, name, args)

internal fun tool(
    name: String,
    risk: Risk = Risk.READ_ONLY,
    op: String = "op",
    schema: String = """{"type":"object","properties":{}}""",
    run: suspend (String) -> ToolResult,
) = RegisteredTool(ToolSpec(name, "测试工具：$name", schema), risk, op, execute = run)

internal fun schemaOf(vararg required: String) =
    """{"type":"object","properties":{"query":{"type":"string"},"n":{"type":"integer"}},"required":[${required.joinToString { "\"$it\"" }}]}"""
