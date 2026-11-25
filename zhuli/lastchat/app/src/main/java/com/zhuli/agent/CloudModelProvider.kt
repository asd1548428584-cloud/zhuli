package com.zhuli.agent

import android.content.Context
import com.zhuli.core.CloudClient
import com.zhuli.core.PrivacyRouter
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端推理后端（统一网关的一个实现）。
 *
 * 职责只有「把网关请求翻译给 OpenAI 兼容端点，再把响应翻译回来」。
 * 它**不执行工具**、不碰权限、不写库 —— 那些都在 AgentLoop 里，模型绕不过去。
 * 所有出网内容仍经 [CloudClient.completeOnce] 里的隐私闸门，逐条脱敏 / 拦截。
 */
class CloudModelProvider(
    private val ctx: Context,
    private val task: PrivacyRouter.Task = PrivacyRouter.Task.CHAT,
    private val configuredModel: String = "",
) : ModelProvider {

    override val capabilities = ModelCapabilities(
        providerId = "zhuli-cloud",
        modelId = configuredModel.ifBlank { "endpoint-default" },
        kind = ProviderKind.CLOUD,
        tools = true,
        streaming = false,
        maxContextTokens = 32_000,
        leavesDevice = true,
        tier = 2,
    )

    override suspend fun complete(request: ModelRequest): ModelResponse {
        // 批次 B：三家 Provider 共用一套中立消息 → OpenAI 格式转换，工具调用与结果按 ID 配对。
        val messages = ProviderMessageFormat.openAi(request.messages)
        val toolsJson = if (request.tools.isEmpty()) "" else
            JSONArray(request.tools.map { it.toOpenAiJson() }).toString()

        val started = System.currentTimeMillis()
        val msg = try {
            JSONObject(CloudClient.completeOnce(ctx, task, messages.toString(), toolsJson, request.temperature))
        } catch (e: Throwable) {
            throw translate(e)
        }
        val calls = ArrayList<ToolCall>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val fn = o.optJSONObject("function") ?: continue
                calls.add(
                    ToolCall(
                        id = o.optString("id").ifBlank { "call_$i" },
                        name = fn.optString("name"),
                        arguments = fn.optString("arguments", "{}"),
                    )
                )
            }
        }
        val usage = msg.optJSONObject("usage")
        return ModelResponse(
            text = msg.optString("content"),
            toolCalls = calls,
            usage = TokenUsage(
                prompt = usage?.optInt("prompt_tokens", 0) ?: 0,
                completion = usage?.optInt("completion_tokens", 0) ?: 0,
            ),
            providerId = capabilities.providerId,
            modelId = capabilities.modelId,
            latencyMs = System.currentTimeMillis() - started,
        )
    }

    /**
     * 异常 → 网关错误分类。
     *
     * 这张表是「降级能不能发生」的唯一依据，所以按**真实故障形态**分，不按消息长短猜：
     * - 闸门拦截 / PrivacyBlockedException → BLOCKED（换后端也不许发）
     * - 401/403/400/404、未配置 → FATAL（重试无意义，但换本地仍可答）
     * - 408/409/425/429/5xx → TRANSIENT（过一会儿再来）
     * - 断网 / DNS / 超时 / SSL / 连接重置 → NETWORK（本地降级的主场景）
     */
    private fun translate(e: Throwable): ModelError = when (e) {
        is ModelError -> e
        is CloudClient.PrivacyBlockedException -> ModelError(ModelError.Kind.BLOCKED, e.message.orEmpty(), e)
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.SocketTimeoutException,
        is java.net.NoRouteToHostException,
        is javax.net.ssl.SSLException -> ModelError(ModelError.Kind.NETWORK, networkText(e), e)
        is java.io.IOException -> ModelError(ModelError.Kind.NETWORK, networkText(e), e)
        else -> {
            val m = e.message.orEmpty()
            val code = Regex("HTTP (\\d{3})").find(m)?.groupValues?.getOrNull(1)?.toIntOrNull()
            when {
                m.contains("闸门") || m.contains("红线") -> ModelError(ModelError.Kind.BLOCKED, m, e)
                code == 408 || code == 409 || code == 425 || code == 429 -> ModelError(ModelError.Kind.TRANSIENT, m, e)
                code != null && code >= 500 -> ModelError(ModelError.Kind.TRANSIENT, m, e)
                code == 401 || code == 403 || code == 400 || code == 404 -> ModelError(ModelError.Kind.FATAL, m, e)
                m.contains("未配置") || m.contains("未设置") -> ModelError(ModelError.Kind.FATAL, m, e)
                m.contains("返回解析失败") -> ModelError(ModelError.Kind.TRANSIENT, m, e)
                else -> ModelError(ModelError.Kind.NETWORK, m.ifBlank { e.javaClass.simpleName }, e)
            }
        }
    }

    private fun networkText(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException -> "请求超时"
        is java.net.UnknownHostException -> "域名解析失败（可能断网）"
        is java.net.ConnectException -> "连不上服务器（可能断网）"
        else -> e.message ?: e.javaClass.simpleName
    }
}
