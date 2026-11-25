package com.zhuli.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 批次 B：工具参数的统一校验（纯 JVM，可单测）。
 *
 * 为什么不能用工具实现里的 `JSONObject.optString`：
 * - 非法 JSON 会被吞成空对象，模型拿到"没给关键词"而不是"参数不是合法 JSON"；
 * - 类型错（`"days":"abc"`）在部分工具里会变成 0 或直接抛异常；
 * - 必填、枚举、范围全靠每个工具各写一遍，漏一个就是一个崩溃点。
 *
 * 这里按工具 [ToolSpec.parametersJson] 里声明的 JSON Schema 做**执行前**校验。
 * 校验失败只返回错误文本（由 AgentLoop 回灌给模型），**不执行工具、不触碰权限**。
 */
object ToolArguments {
    private val json = Json { ignoreUnknownKeys = true }

    /** 返回 null = 合法；否则返回给人/模型都能看懂的原因。 */
    fun validate(spec: ToolSpec, raw: String): String? {
        val element = runCatching { json.parseToJsonElement(raw.ifBlank { "{}" }) }
            .getOrElse { return "参数不是合法 JSON：${it.message ?: it.javaClass.simpleName}" }
        val obj = element as? JsonObject ?: return "参数必须是 JSON 对象，实际是 ${element.kind()}"
        val schema = runCatching { json.parseToJsonElement(spec.parametersJson).jsonObject }
            .getOrElse { return "工具声明的 Schema 不合法：${it.message ?: it.javaClass.simpleName}" }

        if (schema.string("type")?.let { it != "object" } == true) {
            return "工具 Schema 的根类型必须是 object"
        }
        for (required in schema.array("required")) {
            val name = required.asText() ?: continue
            if (!obj.containsKey(name) || obj[name] is JsonNull) return "缺少必填参数：$name"
        }
        val props = schema["properties"] as? JsonObject ?: return null
        for ((name, value) in obj) {
            val field = props[name] as? JsonObject ?: continue
            val problem = checkField(name, value, field) ?: continue
            return problem
        }
        return null
    }

    private fun checkField(name: String, value: JsonElement, schema: JsonObject): String? {
        val expected = schema.string("type")
        if (expected != null && !matchesType(value, expected)) {
            return "参数 $name 类型不对：需要 $expected，实际 ${value.kind()}"
        }
        schema.array("enum").takeIf { it.isNotEmpty() }?.let { allowed ->
            if (allowed.none { it.asText() == value.asText() }) {
                return "参数 $name 必须是以下值之一：${allowed.joinToString("/") { it.asText() ?: "?" }}"
            }
        }
        if (value is JsonPrimitive && !value.isString) {
            schema.number("minimum")?.let { min ->
                if ((value.doubleOrNull ?: 0.0) < min) return "参数 $name 不能小于 ${min.format()}"
            }
            schema.number("maximum")?.let { max ->
                if ((value.doubleOrNull ?: 0.0) > max) return "参数 $name 不能大于 ${max.format()}"
            }
            schema.int("minLength")?.let { min ->
                if (value.content.length < min) return "参数 $name 至少 $min 个字符"
            }
            schema.int("maxLength")?.let { max ->
                if (value.content.length > max) return "参数 $name 最多 $max 个字符"
            }
        }
        if (value is JsonArray) {
            schema.int("minItems")?.let { min ->
                if (value.size < min) return "参数 $name 至少 $min 项"
            }
            schema.int("maxItems")?.let { max ->
                if (value.size > max) return "参数 $name 最多 $max 项"
            }
        }
        return null
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "string" -> value is JsonPrimitive && value.isString
        // intOrNull/doubleOrNull 会把 "3" 也转成数字，必须显式排除字符串
        "integer" -> value is JsonPrimitive && !value.isString && value.intOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "boolean" -> value is JsonPrimitive && (value.content == "true" || value.content == "false")
        "array" -> value is JsonArray
        "object" -> value is JsonObject
        "null" -> value is JsonNull
        else -> true // 未知类型不误伤，交给工具自己处理
    }

    private fun JsonElement.kind(): String = when (this) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        is JsonPrimitive -> if (isString) "string" else "number/boolean"
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray)?.toList() ?: emptyList()
    private fun JsonElement.asText(): String? = (this as? JsonPrimitive)?.content
    private fun Double.format(): String = if (this == toLong().toDouble()) "${toLong()}" else toString()
}
