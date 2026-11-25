package com.zhuli.agent

import kotlinx.serialization.Serializable

/**
 * 统一模型接入层（AI Gateway）。
 *
 * 目标：本地模型与云端 API 是**同一个 Agent 的两个推理后端**，不是两套助理。
 * 因此上层（[AgentLoop]）只认 [ModelProvider]，不认 OpenAI、不认 LiteRT。
 *
 * 三条硬规矩：
 * 1. 能力必须显式声明（[ModelCapabilities]）：能不能调工具、能不能看图、上下文多大。
 *    路由按能力选，不按「谁配了 Key」选。Qwen3-0.6B 不会被当成能干活的 Agent 模型。
 * 2. 失败要分类（[ModelError.Kind]）：网络抖一下可以重试，鉴权错了重试一万次也没用。
 * 3. 降级要记账：每次回退都写进 [ModelResponse.providerId]，UI 上能看出「这句是本地答的」。
 */
enum class ProviderKind { CLOUD, LOCAL }

data class ModelCapabilities(
    val providerId: String,
    val modelId: String,
    val kind: ProviderKind,
    /** 是否支持 OpenAI 风格的工具调用。false 的模型不能承担需要动手的任务。 */
    val tools: Boolean = false,
    val vision: Boolean = false,
    val streaming: Boolean = false,
    val maxContextTokens: Int = 8_192,
    /** 是否允许把用户数据发到这台机器之外 */
    val leavesDevice: Boolean = kind == ProviderKind.CLOUD,
    /** 大致能力档：0 = 只能做摘要/改写，1 = 能对话，2 = 能多步工具调用 */
    val tier: Int = 1,
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    /** 原始 JSON 字符串，不要在这里猜结构 */
    val arguments: String = "{}",
)

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class AgentMessage(
    val role: Role,
    val content: String,
    /** assistant 发起的工具调用（回灌给模型时必须带上 toolCallId） */
    val toolCalls: List<ToolCall> = emptyList(),
    /** role=TOOL 时对应哪次调用 */
    val toolCallId: String = "",
) {
    companion object {
        fun system(text: String) = AgentMessage(Role.SYSTEM, text)
        fun user(text: String) = AgentMessage(Role.USER, text)
        fun assistant(text: String, calls: List<ToolCall> = emptyList()) =
            AgentMessage(Role.ASSISTANT, text, calls)
        fun tool(callId: String, text: String) = AgentMessage(Role.TOOL, text, toolCallId = callId)
    }
}

/** 给模型看的工具声明。parametersJson 必须是合法 JSON Schema。 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJson: String = """{"type":"object","properties":{}}""",
    /** 给本地小模型看的一句话用法（云端用不到） */
    val localHint: String = "",
) {
    fun toOpenAiJson(): String = buildString {
        append("""{"type":"function","function":{""")
        append("\"name\":").append(quote(name)).append(',')
        append("\"description\":").append(quote(description)).append(',')
        append("\"parameters\":").append(parametersJson)
        append("}}")
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append(' ') else append(c)
        }
        append('"')
    }
}

data class ModelRequest(
    val messages: List<AgentMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double = 0.3,
    val maxTokens: Int = 900,
    /** 单次调用超时。工具环里每一步都要有上限，否则一次卡死拖垮整个任务。 */
    val timeoutMs: Long = 45_000,
    /** 任务标识，只用于日志定位 */
    val tag: String = "",
    /**
     * 是否只允许在设备内推理。
     *
     * 这个字段是「用户请求 / 隐私策略 → Gateway」的唯一通道：以前 Router 有个
     * `localOnly` 参数但没有任何调用方传值，等于隐私策略根本没走到路由。
     * 现在由 AgentTask 落库的标记带进来，Router 只认这个字段。
     */
    val localOnly: Boolean = false,
)

data class TokenUsage(val prompt: Int = 0, val completion: Int = 0) {
    val total: Int get() = prompt + completion
    operator fun plus(o: TokenUsage) = TokenUsage(prompt + o.prompt, completion + o.completion)
}

data class ModelResponse(
    val text: String,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: TokenUsage = TokenUsage(),
    val providerId: String,
    val modelId: String,
    val latencyMs: Long = 0L,
) {
    val wantsTools: Boolean get() = toolCalls.isNotEmpty()
}

/** 模型调用失败。分类决定「重试」还是「换后端」还是「直接告诉用户」。 */
class ModelError(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Kind {
        /** 断网、超时、DNS */
        NETWORK,

        /** 429 / 5xx，可稍后重试 */
        TRANSIENT,

        /** 400 / 401 / 403 / 404：配置或能力问题，重试无意义 */
        FATAL,

        /** 内容被隐私闸门拦下（不是错误配置，是保护生效） */
        BLOCKED,

        /** 模型没装 / 不是可用的模型 */
        UNAVAILABLE,
    }

    val retryable: Boolean get() = kind == Kind.NETWORK || kind == Kind.TRANSIENT
}

interface ModelProvider {
    val capabilities: ModelCapabilities
    suspend fun complete(request: ModelRequest): ModelResponse
}

/**
 * 路由：按能力挑后端，按顺序降级。
 *
 * 顺序不是写死的「云优先」：任务需要工具时，只有声明了 tools 的后端才进入候选；
 * 隐私等级要求不出设备时，云端直接不进入候选（[preferLocalOnly]）。
 */
class ModelRouter(
    providers: List<ModelProvider>,
    private val onEvent: (String) -> Unit = {},
) {
    private val all: List<ModelProvider> = providers.filter { it.capabilities.tier > 0 }

    val capabilities: List<ModelCapabilities> get() = all.map { it.capabilities }

    /**
     * @param needTools 这一步是否必须调工具
     * @param localOnly 调用方（用户请求 / 隐私策略）是否要求只在设备内完成；
     *                  与 [ModelRequest.localOnly] 取「或」，两处任一为真都不出网。
     */
    suspend fun complete(request: ModelRequest, needTools: Boolean = request.tools.isNotEmpty(), localOnly: Boolean = false): ModelResponse {
        val onlyLocal = localOnly || request.localOnly
        val candidates = all
            .filter { !onlyLocal || !it.capabilities.leavesDevice }
            .filter { !needTools || it.capabilities.tools }
            .sortedWith(compareByDescending<ModelProvider> { it.capabilities.tier })

        if (candidates.isEmpty()) {
            throw ModelError(
                ModelError.Kind.UNAVAILABLE,
                when {
                    onlyLocal && needTools -> "这一步必须在本机完成且要用工具，但本机模型不支持工具调用"
                    onlyLocal -> "只允许本机推理，但没有可用的本地模型"
                    needTools -> "没有可用的模型能调用工具"
                    else -> "没有可用的模型"
                }
            )
        }

        var lastError: ModelError? = null
        for ((index, p) in candidates.withIndex()) {
            try {
                val resp = p.complete(request)
                if (index > 0) {
                    onEvent("降级到 ${p.capabilities.providerId}/${p.capabilities.modelId}（前一个后端不可用）")
                }
                return resp
            } catch (e: ModelError) {
                lastError = e
                onEvent("${p.capabilities.providerId} 失败：${e.kind} ${e.message}")
                // 隐私闸门拦下的内容不许「换个模型」继续发：那是绕过保护，不是降级
                if (e.kind == ModelError.Kind.BLOCKED) throw e
            }
        }
        throw lastError ?: ModelError(ModelError.Kind.UNAVAILABLE, "没有可用的模型")
    }

    /** 给自检/设置页看：现在到底有哪几个后端、各自能干什么。 */
    fun describe(): String = all.joinToString("\n") { p ->
        val c = p.capabilities
        "· ${c.providerId}｜${c.modelId}｜${if (c.kind == ProviderKind.CLOUD) "云" else "本地"}" +
            "｜工具${if (c.tools) "✓" else "✗"}｜上下文 ${c.maxContextTokens}｜能力档 ${c.tier}"
    }.ifBlank { "（没有可用的模型后端）" }
}
