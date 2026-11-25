package com.zhuli.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 出网请求体的统一隐私管线（批次 A 的核心实现，纯 JVM，可单测）。
 *
 * 为什么要有这一层：项目里原本有两套扫描器 ——
 * - 旧壳 `CloudClient.lastGate` → [OutgoingGate]：只认 chat/completions 的 `messages`；
 * - 新壳 `ZhuliPrivacyInterceptor`：自己写了一份 `rewrite`，只认 `messages[*].content` 与 `system`，
 *   既不认 `tool_calls[].function.arguments`，也不认 Responses API 的 `input`。
 * 同一个仓库两套规则，必然一边修好、另一边继续漏。
 *
 * 本类把「哪些字段里可能藏着用户原文」一次说清，供所有出网路径复用：
 *
 * | 结构 | 字段 |
 * |---|---|
 * | chat/completions（OpenAI 兼容） | `messages[*].content`（字符串/多模态分段）、`messages[*].tool_calls[*].function.arguments`、`messages[*].function_call.arguments` |
 * | Responses API | `instructions`、`input`（字符串 / 分段数组 / function_call.arguments / function_call_output.output） |
 * | Claude Messages | `system`、`messages[*].content[*].text`、`tool_use.input`、`tool_result.content` |
 * | Gemini generateContent | `systemInstruction.parts[*].text`、`contents[*].parts[*].text`、`functionCall.args`、`functionResponse.response` |
 * | embeddings | `input`（字符串或字符串数组） |
 * | legacy completions | `prompt`（字符串或字符串数组） |
 *
 * 三条硬规矩：
 * 1. **命中硬红线 → 抛 [Blocked]**，整轮中止，不允许「换个字段继续发」；
 * 2. **结构无法识别 → 由调用方决定**（聊天必须 [requireKnownShape]=true，即中止；
 *    语音 multipart 这类本来没有文本结构的载荷才允许原样通过）；
 * 3. **工具参数脱敏后必须仍是合法 JSON**，否则中止 —— 发一个坏包出去，模型只会回 400，
 *    用户看到的是「助理坏了」，而不是「内容被保护了」。
 */
object PrivacyPipeline {

    /** 识别出的请求体结构，只用于留档与自检展示。 */
    enum class Shape(val label: String) {
        CHAT_COMPLETIONS("chat/completions"),
        RESPONSES("responses"),
        CLAUDE_MESSAGES("claude/messages"),
        GEMINI("gemini/generateContent"),
        TEXT_INPUT("embeddings input"),
        PROMPT("legacy completions"),
        UNKNOWN("未知结构"),
    }

    data class Redaction(val where: String, val reason: String)

    data class Result(
        val payload: String,
        val shape: Shape,
        val redactions: List<Redaction>,
        /** 实际检查过的文本字段数（0 表示没扫到任何东西，自检要如实显示） */
        val scannedFields: Int,
    ) {
        val changed: Boolean get() = redactions.isNotEmpty()
    }

    /** 命中硬红线 / 结构损坏：这一轮不能发。 */
    class Blocked(val where: String, val reason: String) : Exception("$where 含$reason，已中止出网")

    data class Rules(
        val hardRedLine: (String) -> String? = { PrivacyDecision.hardRedLine(it) },
        val sensitiveReason: (String) -> String? = { SensitiveGuard.reason(it) },
        val redact: (String) -> String = { SensitiveGuard.redact(it) },
        val strict: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * @param requireKnownShape true = 结构异常时抛 [Blocked]（聊天/Responses/embeddings 一律用 true）
     */
    fun scrub(
        payloadJson: String,
        rules: Rules = Rules(),
        requireKnownShape: Boolean = true,
    ): Result {
        val root = runCatching { json.parseToJsonElement(payloadJson) }.getOrNull()
            ?: return unknown(payloadJson, requireKnownShape, "不是合法 JSON")
        val rootObj = root as? JsonObject
            ?: return unknown(payloadJson, requireKnownShape, "根节点不是对象")

        // Claude 也有 messages，但 content 的分段类型不同（tool_use / tool_result）。
        // 先看 Anthropic 特征（system + messages + 无 tools? 不成立），最稳的判别是
        // `messages[*].content[*].type` 里出现 tool_use/tool_result —— 但这要逐条扫。
        // 实务上两者的 content 分段扫描逻辑可以合并（见 parts()），所以统一按 CHAT 处理即可；
        // 这里保留 CLAUDE_MESSAGES 仅用于留档时的可读性。
        val messages = rootObj["messages"] as? JsonArray
        val contents = rootObj["contents"] as? JsonArray
        val hasSystemInstruction = rootObj.containsKey("systemInstruction")
        val hasInput = rootObj.containsKey("input")
        val hasInstructions = rootObj.containsKey("instructions")
        val hasPrompt = rootObj.containsKey("prompt")

        val shape = when {
            contents != null || hasSystemInstruction -> Shape.GEMINI
            // Claude 的 system 是顶层字段；OpenAI 兼容端把 system 放在 messages 里。
            // 两种的 content 分段扫描逻辑已合并（见 parts()），这里只影响留档里的可读标签。
            messages != null -> if (rootObj.containsKey("system")) {
                Shape.CLAUDE_MESSAGES
            } else {
                Shape.CHAT_COMPLETIONS
            }

            hasInput -> Shape.TEXT_INPUT
            hasInstructions -> Shape.RESPONSES
            hasPrompt -> Shape.PROMPT
            else -> Shape.UNKNOWN
        }
        if (shape == Shape.UNKNOWN) {
            return unknown(payloadJson, requireKnownShape, "既没有 messages/contents 也没有 input/instructions/prompt")
        }

        val scrubber = Scrubber(rules)
        val out = LinkedHashMap<String, JsonElement>(rootObj.size)
        for ((k, v) in rootObj) {
            out[k] = when (k) {
                "messages" -> scrubber.messages(v)
                "contents" -> scrubber.geminiContents(v)
                "systemInstruction" -> scrubber.geminiSystem(v)
                "instructions", "system" -> scrubber.parts(v, k)
                "input" -> scrubber.input(v)
                "prompt" -> scrubber.prompt(v)
                else -> v
            }
        }
        if (scrubber.redactions.isEmpty()) {
            // 没改动就原样返回：避免重新序列化引入与本次无关的格式变化
            return Result(payloadJson, shape, emptyList(), scrubber.scanned)
        }
        return Result(
            payload = JsonObject(out).toString(),
            shape = shape,
            redactions = scrubber.redactions,
            scannedFields = scrubber.scanned,
        )
    }

    private fun unknown(payloadJson: String, requireKnownShape: Boolean, why: String): Result {
        if (requireKnownShape) throw Blocked("请求体", "$why，拒绝原样出网")
        return Result(payloadJson, Shape.UNKNOWN, emptyList(), 0)
    }

    // ==================== 内部扫描器 ====================

    private class Scrubber(private val rules: Rules) {
        val redactions = ArrayList<Redaction>()
        var scanned = 0
            private set

        fun messages(el: JsonElement): JsonElement {
            val arr = el as? JsonArray ?: return el
            return JsonArray(arr.mapIndexed { i, m ->
                val obj = m as? JsonObject ?: return@mapIndexed m
                val role = (obj["role"] as? JsonPrimitive)?.content ?: "?"
                val where = "消息[$i:$role]"
                val out = LinkedHashMap<String, JsonElement>(obj.size)
                for ((k, v) in obj) {
                    out[k] = when (k) {
                        "content" -> parts(v, "$where.content")
                        "tool_calls" -> toolCalls(v, "$where.tool_calls")
                        "function_call" -> functionCall(v, "$where.function_call")
                        else -> v
                    }
                }
                JsonObject(out)
            })
        }

        private fun toolCalls(el: JsonElement, where: String): JsonElement {
            val arr = el as? JsonArray ?: return el
            return JsonArray(arr.mapIndexed { i, c ->
                val call = c as? JsonObject ?: return@mapIndexed c
                val fn = call["function"] as? JsonObject ?: return@mapIndexed c
                val name = (fn["name"] as? JsonPrimitive)?.content ?: "?"
                val newFn = LinkedHashMap<String, JsonElement>(fn.size)
                for ((k, v) in fn) {
                    newFn[k] = if (k == "arguments") {
                        JsonPrimitive(arguments(v, "$where[$i].function.arguments($name)"))
                    } else {
                        v
                    }
                }
                val newCall = LinkedHashMap<String, JsonElement>(call.size)
                for ((k, v) in call) {
                    newCall[k] = if (k == "function") JsonObject(newFn) else v
                }
                JsonObject(newCall)
            })
        }

        private fun functionCall(el: JsonElement, where: String): JsonElement {
            val obj = el as? JsonObject ?: return el
            val name = (obj["name"] as? JsonPrimitive)?.content ?: "?"
            val out = LinkedHashMap<String, JsonElement>(obj.size)
            for ((k, v) in obj) {
                out[k] = if (k == "arguments") {
                    JsonPrimitive(arguments(v, "$where.arguments($name)"))
                } else {
                    v
                }
            }
            return JsonObject(out)
        }

        fun input(el: JsonElement): JsonElement = when (el) {
            is JsonPrimitive -> if (el.isString) JsonPrimitive(gate(el.content, "input")) else el
            is JsonArray -> JsonArray(el.mapIndexed { i, item ->
                when (item) {
                    is JsonPrimitive -> if (item.isString) {
                        JsonPrimitive(gate(item.content, "input[$i]"))
                    } else {
                        item
                    }

                    is JsonObject -> inputItem(item, "input[$i]")
                    else -> item
                }
            })

            else -> el
        }

        /** Responses API 的 item：消息、function_call、function_call_output 都在这一层。 */
        private fun inputItem(obj: JsonObject, where: String): JsonObject {
            val out = LinkedHashMap<String, JsonElement>(obj.size)
            for ((k, v) in obj) {
                out[k] = when (k) {
                    "content", "text" -> parts(v, "$where.$k")
                    "arguments" -> JsonPrimitive(arguments(v, "$where.arguments"))
                    "output" -> parts(v, "$where.output")
                    else -> v
                }
            }
            return JsonObject(out)
        }

        /** legacy `/completions`：prompt 是字符串或字符串数组。 */
        fun prompt(el: JsonElement): JsonElement = when (el) {
            is JsonPrimitive -> if (el.isString) JsonPrimitive(gate(el.content, "prompt")) else el
            is JsonArray -> JsonArray(el.mapIndexed { i, item ->
                if (item is JsonPrimitive && item.isString) {
                    JsonPrimitive(gate(item.content, "prompt[$i]"))
                } else {
                    item
                }
            })

            else -> el
        }

        fun geminiSystem(el: JsonElement): JsonElement {
            val obj = el as? JsonObject ?: return parts(el, "systemInstruction")
            val out = LinkedHashMap<String, JsonElement>(obj.size)
            for ((k, v) in obj) {
                out[k] = if (k == "parts") parts(v, "systemInstruction.parts") else v
            }
            return JsonObject(out)
        }

        fun geminiContents(el: JsonElement): JsonElement {
            val arr = el as? JsonArray ?: return el
            return JsonArray(arr.mapIndexed { i, c ->
                val obj = c as? JsonObject ?: return@mapIndexed c
                val out = LinkedHashMap<String, JsonElement>(obj.size)
                for ((k, v) in obj) {
                    out[k] = if (k == "parts") geminiParts(v, "contents[$i].parts") else v
                }
                JsonObject(out)
            })
        }

        private fun geminiParts(el: JsonElement, where: String): JsonElement {
            val arr = el as? JsonArray ?: return el
            return JsonArray(arr.mapIndexed { i, p ->
                val obj = p as? JsonObject ?: return@mapIndexed p
                val out = LinkedHashMap<String, JsonElement>(obj.size)
                for ((k, v) in obj) {
                    out[k] = when (k) {
                        "text" -> if (v is JsonPrimitive && v.isString) {
                            JsonPrimitive(gate(v.content, "$where[$i].text"))
                        } else {
                            v
                        }

                        "functionCall" -> gateField(v, "args", "$where[$i].functionCall.args")
                        "functionResponse" -> gateField(v, "response", "$where[$i].functionResponse.response")
                        else -> v
                    }
                }
                JsonObject(out)
            })
        }

        private fun gateField(obj: JsonElement, field: String, where: String): JsonElement {
            val o = obj as? JsonObject ?: return obj
            val out = LinkedHashMap<String, JsonElement>(o.size)
            for ((k, v) in o) {
                out[k] = if (k == field) gateDeep(v, where) else v
            }
            return JsonObject(out)
        }

        /**
         * content / instructions / output / parts：字符串，或 `[{type:"text",text:"…"}]` 形式的分段数组。
         *
         * 同时覆盖 Claude 的两种工具分段：
         * - `{"type":"tool_use","input":{…}}` → input 里的所有字符串都要过闸；
         * - `{"type":"tool_result","content":"…"}` → content 继续按分段递归。
         */
        fun parts(el: JsonElement, where: String): JsonElement = when (el) {
            is JsonPrimitive -> if (el.isString) JsonPrimitive(gate(el.content, where)) else el
            is JsonArray -> JsonArray(el.mapIndexed { i, part ->
                val obj = part as? JsonObject ?: return@mapIndexed part
                val out = LinkedHashMap<String, JsonElement>(obj.size)
                for ((k, v) in obj) {
                    out[k] = when {
                        k == "text" && v is JsonPrimitive && v.isString ->
                            JsonPrimitive(gate(v.content, "$where[$i].text"))

                        k == "content" -> parts(v, "$where[$i].content")
                        k == "input" -> gateDeep(v, "$where[$i].input")
                        else -> v
                    }
                }
                JsonObject(out)
            })

            else -> el
        }

        /** 把 JSON 里所有字符串叶子都过一遍闸（用于结构不固定的工具参数对象）。 */
        private fun gateDeep(el: JsonElement, where: String): JsonElement = when (el) {
            is JsonPrimitive -> if (el.isString) JsonPrimitive(gate(el.content, where)) else el
            is JsonArray -> JsonArray(el.mapIndexed { i, x -> gateDeep(x, "$where[$i]") })
            is JsonObject -> {
                val out = LinkedHashMap<String, JsonElement>(el.size)
                for ((k, v) in el) out[k] = gateDeep(v, "$where.$k")
                JsonObject(out)
            }
        }

        private fun arguments(el: JsonElement, where: String): String {
            // OpenAI 规范里 arguments 是「字符串里的 JSON」；个别兼容端点直接给对象，
            // 这里统一序列化成字符串再扫描 —— 无论如何都先过闸，不允许因为形态不同就跳过。
            val raw = when (el) {
                is JsonPrimitive -> el.content
                else -> el.toString()
            }
            val wasJson = runCatching { json.parseToJsonElement(raw) }.isSuccess
            val masked = gate(raw, where)
            if (wasJson && masked != raw && runCatching { json.parseToJsonElement(masked) }.isFailure) {
                throw Blocked(where, "脱敏后参数结构损坏")
            }
            return masked
        }

        private fun gate(text: String, where: String): String {
            if (text.isBlank()) return text
            scanned++
            rules.hardRedLine(text)?.let { throw Blocked(where, it) }
            val why = rules.sensitiveReason(text) ?: return text
            if (rules.strict) throw Blocked(where, why)
            val masked = rules.redact(text)
            if (masked != text) redactions += Redaction(where, why)
            return masked
        }
    }
}
