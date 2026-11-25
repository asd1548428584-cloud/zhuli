package com.zhuli.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 出网请求体的统一闸门（纯 JVM，可单测）。
 *
 * 为什么单独抽出来：v15 的 `CloudClient.completeOnce()` 只把每条消息的 `content` 过闸，
 * 而 `assistant.tool_calls[].function.arguments` 是一段**字符串里的 JSON**，
 * 它会在后续每一轮请求里被原样重新发出去 —— 用户手机号、地址、取件码、金额
 * 就藏在那一层嵌套里，上一版完全没碰。这种"数据躲在嵌套字段里"的绕过不是理论问题：
 * Agent 一旦调用过 `save_fact(subject=我, predicate=住址, value=朝阳区…)`，
 * 这段参数就会随历史一起反复上云。
 *
 * 本类只做三件事，顺序固定：
 * 1. 逐条（system / user / assistant / tool / 多模态文本片段 / tool_calls.arguments）判硬红线；
 * 2. 命中硬红线 → 立刻中止整轮出网（[Blocked]），不允许"脱敏后照发"；
 * 3. 其余敏感项就地脱敏，返回**实际要发出去的那份 JSON**（cloud_log 记的就是它）。
 */
object OutgoingGate {

    /** 内容被拦下。带上位置与原因，便于审计与用户提示。 */
    class Blocked(val where: String, val reason: String) : Exception("$where 含$reason，这一轮不上云")

    /** 可脱敏的文本被改写了一条。 */
    data class Redaction(val where: String, val reason: String)

    data class Result(val payload: String, val redactions: List<Redaction>) {
        val changed: Boolean get() = redactions.isNotEmpty()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param hardRedLine 返回非空 = 这类内容脱敏也不许发（证件号/卡号/凭证）
     * @param sensitiveReason 返回非空 = 敏感但可脱敏（电话/地址/金额/公司+岗位）
     * @param redact 脱敏函数
     */
    fun scrub(
        payloadJson: String,
        hardRedLine: (String) -> String?,
        sensitiveReason: (String) -> String?,
        redact: (String) -> String,
        /**
         * 聊天请求必须为 true：结构异常（不是 JSON / 无 messages / messages 不是数组）时
         * **中止出网**而不是原样放行。默认 false 是为了不破坏语音 multipart 等合法非聊天载荷 ——
         * 那些请求本来就不该、也无法从这里逐条审查文本。
         */
        requireMessages: Boolean = false,
    ): Result {
        val root = runCatching { json.parseToJsonElement(payloadJson) }.getOrNull()
            ?: return invalidPayload(payloadJson, requireMessages, "不是合法 JSON")
        val rootObj = root as? JsonObject
            ?: return invalidPayload(payloadJson, requireMessages, "根节点不是对象")
        val messages = rootObj["messages"] as? JsonArray
            ?: return invalidPayload(payloadJson, requireMessages, "缺少 messages 数组")

        val redactions = ArrayList<Redaction>()
        val outMessages = ArrayList<JsonElement>()
        for ((i, m) in messages.withIndex()) {
            val obj = m as? JsonObject
            if (obj == null) {
                outMessages.add(m)
                continue
            }
            val role = obj.str("role") ?: "?"
            outMessages.add(walkMessage(obj, "消息[$i:$role]", hardRedLine, sensitiveReason, redact, redactions))
        }
        val newRoot = LinkedHashMap<String, JsonElement>(rootObj)
        newRoot["messages"] = JsonArray(outMessages)
        return Result(JsonObject(newRoot).toString(), redactions)
    }

    /** 聊天载荷结构异常：要么中止（requireMessages），要么原样交给调用方（非聊天接口）。 */
    private fun invalidPayload(payloadJson: String, requireMessages: Boolean, why: String): Result {
        if (requireMessages) throw Blocked("请求体", "$why，拒绝原样出网")
        return Result(payloadJson, emptyList())
    }

    private fun walkMessage(
        obj: JsonObject,
        where: String,
        hardRedLine: (String) -> String?,
        sensitiveReason: (String) -> String?,
        redact: (String) -> String,
        redactions: MutableList<Redaction>,
    ): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        for ((k, v) in obj) {
            when (k) {
                // content / tool_call_id：字符串或（content 的）多模态数组，都由 gateText 处理
                "content", "tool_call_id" ->
                    out[k] = gateText(v, "$where.$k", hardRedLine, sensitiveReason, redact, redactions)
                // tool_calls[].function.arguments —— 上一版漏掉的嵌套敏感数据
                "tool_calls" ->
                    out[k] = gateToolCalls(v, "$where.tool_calls", hardRedLine, sensitiveReason, redact, redactions)
                else -> out[k] = v
            }
        }
        return JsonObject(out)
    }

    private fun gateToolCalls(
        v: JsonElement,
        where: String,
        hardRedLine: (String) -> String?,
        sensitiveReason: (String) -> String?,
        redact: (String) -> String,
        redactions: MutableList<Redaction>,
    ): JsonElement {
        val arr = v as? JsonArray ?: return v
        val out = ArrayList<JsonElement>()
        for ((i, c) in arr.withIndex()) {
            val call = c as? JsonObject
            if (call == null) {
                out.add(c)
                continue
            }
            val fn = call["function"] as? JsonObject
            if (fn == null) {
                out.add(c)
                continue
            }
            val name = fn.str("name") ?: "?"
            val newFn = LinkedHashMap<String, JsonElement>()
            for ((k, fv) in fn) {
                if (k == "arguments") {
                    newFn[k] = gateText(
                        fv, "$where[$i].function.arguments($name)",
                        hardRedLine, sensitiveReason, redact, redactions,
                    )
                } else {
                    newFn[k] = fv
                }
            }
            val newCall = LinkedHashMap<String, JsonElement>(call)
            newCall["function"] = JsonObject(newFn)
            out.add(JsonObject(newCall))
        }
        return JsonArray(out)
    }

    private fun gateText(
        v: JsonElement,
        where: String,
        hardRedLine: (String) -> String?,
        sensitiveReason: (String) -> String?,
        redact: (String) -> String,
        redactions: MutableList<Redaction>,
    ): JsonElement {
        // 多模态 content 是数组：只处理 type=text 的片段（图片 base64 不做文本判定）
        if (v is JsonArray) {
            val parts = ArrayList<JsonElement>(v.size)
            for (part in v) {
                val p = part as? JsonObject
                if (p == null || p.str("type") != "text") {
                    parts.add(part)
                    continue
                }
                val t = p.str("text")
                if (t == null) {
                    parts.add(part)
                    continue
                }
                val newPart = LinkedHashMap<String, JsonElement>(p)
                newPart["text"] = JsonPrimitive(gateString(t, where, hardRedLine, sensitiveReason, redact, redactions))
                parts.add(JsonObject(newPart))
            }
            return JsonArray(parts)
        }
        val prim = v as? JsonPrimitive
        if (prim == null || !prim.isString) return v
        val s = prim.content
        if (s.isBlank()) return v
        return JsonPrimitive(gateString(s, where, hardRedLine, sensitiveReason, redact, redactions))
    }

    private fun gateString(
        s: String,
        where: String,
        hardRedLine: (String) -> String?,
        sensitiveReason: (String) -> String?,
        redact: (String) -> String,
        redactions: MutableList<Redaction>,
    ): String {
        hardRedLine(s)?.let { throw Blocked(where, it) }
        val why = sensitiveReason(s) ?: return s
        val masked = redact(s)
        if (masked != s) redactions.add(Redaction(where, why))
        return masked
    }

    private fun JsonObject.str(key: String): String? {
        val e = this[key] as? JsonPrimitive ?: return null
        return if (e.isString) e.content else null
    }
}
