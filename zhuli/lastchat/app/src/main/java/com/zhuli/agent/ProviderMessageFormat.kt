package com.zhuli.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 批次 B：把内部中立的 [AgentMessage] 翻成各家 Provider 的请求格式。
 *
 * 内部只有 SYSTEM/USER/ASSISTANT/TOOL 四种角色，避免 AgentLoop 为每家端点写一套上下文逻辑。
 * 当前生产链路用的是 OpenAI 兼容端点（[CloudModelProvider] 调用 [openAi]）；另外两个格式
 * 是同一套中立消息的确定性翻译，供兼容性测试与后续 Provider 接线使用，不是空实现。
 *
 * 三家都要求 assistant 的 tool_calls 与 tool 结果**按 ID 配对**，否则请求直接被拒。
 */
object ProviderMessageFormat {
    private val json = Json { encodeDefaults = true }

    fun openAi(messages: List<AgentMessage>): String = JsonArray(messages.map { m ->
            buildJsonObject {
                put("role", when (m.role) {
                    Role.SYSTEM -> "system"
                    Role.USER -> "user"
                    Role.ASSISTANT -> "assistant"
                    Role.TOOL -> "tool"
                })
                put("content", m.content)
                if (m.role == Role.TOOL && m.toolCallId.isNotBlank()) put("tool_call_id", m.toolCallId)
                if (m.toolCalls.isNotEmpty()) {
                    put("tool_calls", JsonArray(m.toolCalls.map { c ->
                        buildJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", c.name)
                                put("arguments", c.arguments)
                            })
                        }
                    }))
                }
            }
        }).toString()

    fun claude(messages: List<AgentMessage>): String {
        val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n") { it.content }
        val parts = buildJsonArray {
            for (m in messages) {
                when (m.role) {
                    Role.SYSTEM, Role.USER -> add(buildJsonObject {
                        put("role", "user")
                        put("content", m.content)
                    })

                    Role.ASSISTANT -> {
                        val blocks = buildJsonArray {
                            if (m.content.isNotBlank()) add(buildJsonObject {
                                put("type", "text"); put("text", m.content)
                            })
                            for (c in m.toolCalls) add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", c.id)
                                put("name", c.name)
                                put("input", runCatching { json.parseToJsonElement(c.arguments) }
                                    .getOrDefault(JsonObject(emptyMap())))
                            })
                        }
                        add(buildJsonObject { put("role", "assistant"); put("content", blocks) })
                    }

                    Role.TOOL -> add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", m.toolCallId)
                                put("content", m.content)
                            })
                        })
                    })
                }
            }
        }
        return buildJsonObject {
            put("system", system)
            put("messages", parts)
        }.toString()
    }

    fun gemini(messages: List<AgentMessage>): String = buildJsonObject {
            put("contents", buildJsonArray {
                for (m in messages) {
                    val parts = buildJsonArray {
                        when (m.role) {
                            Role.ASSISTANT -> {
                                if (m.content.isNotBlank()) add(buildJsonObject { put("text", m.content) })
                                for (c in m.toolCalls) add(buildJsonObject {
                                    put("functionCall", buildJsonObject {
                                        put("name", c.name)
                                        put("args", runCatching { json.parseToJsonElement(c.arguments) }
                                            .getOrDefault(JsonObject(emptyMap())))
                                    })
                                })
                            }
                            Role.TOOL -> add(buildJsonObject {
                                put("functionResponse", buildJsonObject {
                                    put("name", m.toolCallId)
                                    put("response", buildJsonObject { put("content", m.content) })
                                })
                            })
                            else -> add(buildJsonObject { put("text", m.content) })
                        }
                    }
                    add(buildJsonObject {
                        put("role", if (m.role == Role.ASSISTANT) "model" else "user")
                        put("parts", parts)
                    })
                }
            })
        }.toString()
}
