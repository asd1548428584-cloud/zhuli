package com.zhuli.agent

/**
 * 本地模型后端（网关的另一个实现）。
 *
 * 诚实声明：Qwen3-0.6B 这类端侧小模型 [capabilities.tools] = false，
 * 因而**不会**被选中去执行多步骤任务；它负责：
 * - 云端不可用时的纯文本兜底；
 * - 用户要求「不出设备」的敏感任务。
 *
 * 宿主注入 [generate]（用 RikkaHub 的 ProviderManager / LiteRt 真机链路），
 * 本类不依赖任何 Android 类型，也不假装支持工具调用。
 */
class LocalModelProvider(
    private val generate: suspend (prompt: String) -> String,
    private val modelId: String = "local",
    private val maxContextTokens: Int = 8_192,
) : ModelProvider {

    override val capabilities = ModelCapabilities(
        providerId = "zhuli-local",
        modelId = modelId,
        kind = ProviderKind.LOCAL,
        tools = false,
        vision = false,
        maxContextTokens = maxContextTokens,
        leavesDevice = false,
        tier = 1,
    )

    override suspend fun complete(request: ModelRequest): ModelResponse {
        val prompt = buildString {
            for (m in request.messages) {
                when (m.role) {
                    Role.SYSTEM -> appendLine("【指令】${m.content}")
                    Role.USER -> appendLine("【用户】${m.content}")
                    Role.ASSISTANT -> appendLine("【助理】${m.content}")
                    Role.TOOL -> appendLine("【已查到的资料】${m.content.take(600)}")
                }
            }
            appendLine("请直接给出简短结论，不要复述上面的资料。")
        }
        val started = System.currentTimeMillis()
        val text = try {
            generate(prompt).trim()
        } catch (e: Throwable) {
            throw ModelError(
                if (e.message?.contains("model_not_installed") == true) ModelError.Kind.UNAVAILABLE
                else ModelError.Kind.NETWORK,
                e.message ?: "本地推理失败", e,
            )
        }
        if (text.isBlank()) throw ModelError(ModelError.Kind.TRANSIENT, "本地模型返回空内容")
        return ModelResponse(
            text = text,
            providerId = capabilities.providerId,
            modelId = modelId,
            latencyMs = System.currentTimeMillis() - started,
        )
    }
}
