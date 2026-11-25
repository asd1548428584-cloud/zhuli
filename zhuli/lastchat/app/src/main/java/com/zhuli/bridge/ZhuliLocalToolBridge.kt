package com.zhuli.bridge

/**
 * 本地模型 prompt-engineered tool calling 桥（T-G2/B1-方案A，为 Qwen2.5 而做）。
 *
 * 背景：LiteRT-LM 的原生 tool calling 依赖模型包内嵌的处理器模板（内置仅 Gemma 4/Qwen3/MiniCPM5）。
 * Qwen2.5 的 .litertlm 包没有模板 → ConversationConfig.tools 传了模型也不知道。
 * 但 Qwen2.5 训练时见过 Hermes 风格的 `<tool_call>{"name":...,"arguments":{...}}</tool_call>`
 * 输出格式（其原生 chat 模板即此格式）。因此约定：
 *  1. system prompt 注入工具清单 + 输出格式说明（[usagePrompt]）；
 *  2. 模型在文本里输出 <tool_call> 标记；
 *  3. 编排层在原生 toolCalls 为空时用 [parseEmbedded] 从文本解析出调用，转成正式 ToolCall 执行。
 *
 * 实现刻意零依赖（不用 kotlinx-serialization）：主项目 JVM 单测与 fork 两边都能跑。
 * arguments 保留原始 JSON 文本（ToolCall.arguments 本就是 String；多余字段对空参工具无害）。
 */
object ZhuliLocalToolBridge {

    data class ParsedCall(val name: String, val argumentsJson: String)

    // 闭合标签可选：Qwen 实测常生成到 arguments 就停，不输出 </tool_call>
    private val CALL_REGEX = Regex(
        "<tool_call>\\s*(\\{[\\s\\S]*?\\})(?:\\s*</tool_call>)?",
        RegexOption.IGNORE_CASE,
    )
    private val NAME_REGEX = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")

    /** 判断该模型是否为 LiteRT 本地模型（modelId 形如 litert-community/...）。 */
    fun isLocalModel(modelId: String): Boolean =
        modelId.startsWith("litert-community/", ignoreCase = true)

    /**
     * 工具使用说明（拼进 system prompt）。Qwen2.5 对此格式是"母语"，成功率高。
     */
    fun usagePrompt(toolLines: List<String>): String = buildString {
        appendLine("你可以使用以下本地工具查询用户的私人数据：")
        toolLines.forEach { appendLine(it) }
        appendLine("当需要调用工具时，只输出一行（不要附加其他解释）：")
        appendLine("<tool_call>{\"name\":\"工具名\",\"arguments\":{}}</tool_call>")
        appendLine("工具执行结果会回传给你，再据此用中文回答。若问题无需工具，直接回答。")
    }

    /** 截断 JSON 兜底：<tool_call> 后跟着 name 但 JSON 不完整（1.5B 小模型实测常见）。 */
    private val LOOSE_REGEX = Regex(
        "<tool_call>\\s*(.*)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 从模型文本里解析内嵌的 tool_call 标记。无标记返回空列表。
     * 两级容错（小模型输出不稳定实测）：
     * 1. 完整 JSON（闭合标签可选）→ 正常解析；
     * 2. 截断 JSON → 只要能从标记后文本提取出 name 就按调用处理（参数缺省 {}）。
     */
    fun parseEmbedded(text: String): List<ParsedCall> {
        if (!text.contains("<tool_call>", ignoreCase = true)) return emptyList()
        val out = mutableListOf<ParsedCall>()
        var searchFrom = 0
        while (true) {
            val start = text.indexOf("<tool_call>", searchFrom, ignoreCase = true) ?: break
            val after = start + "<tool_call>".length
            val braceStart = text.indexOf('{', after)
            if (braceStart < 0) break
            // 括号平衡扫描（正确处理嵌套 {}；字符串内大括号罕见，先不处理）
            var depth = 0
            var end = -1
            var i = braceStart
            while (i < text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = i; break } }
                }
                i++
            }
            if (end > 0) {
                val body = text.substring(braceStart, end + 1)
                val name = NAME_REGEX.find(body)?.groupValues?.get(1)?.trim().orEmpty()
                if (name.isNotBlank()) out += ParsedCall(name, body)
                searchFrom = end + 1
            } else {
                // 截断 JSON：无平衡结尾 → 从标记后文本提取 name，参数缺省 {}
                val name = NAME_REGEX.find(text.substring(after))?.groupValues?.get(1)?.trim().orEmpty()
                if (name.isNotBlank()) out += ParsedCall(name, "{}")
                break
            }
        }
        return out
    }

    /** 把文本里的 <tool_call> 标记剥离（防止下一轮模型看到自己的调用标记后复读）。 */
    fun stripMarkers(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val start = text.indexOf("<tool_call>", i, ignoreCase = true)
            if (start < 0) { sb.append(text.substring(i)); break }
            sb.append(text.substring(i, start))
            // 跳过整个调用区间：到闭合标签、下一个标记、或文本尾
            var j = start + "<tool_call>".length
            val close = text.indexOf("</tool_call>", j, ignoreCase = true)
            val next = text.indexOf("<tool_call>", j, ignoreCase = true)
            j = when {
                close >= 0 && (next < 0 || close < next) -> close + "</tool_call>".length
                else -> if (next >= 0) next else text.length
            }
            i = j
        }
        return sb.toString().trim()
    }
}

/**
 * zhuli 本地工具清单说明。加新 Tool 时同步维护。
 */
val ZHULI_LOCAL_TOOL_LINES = listOf(
    "list_courier：查近 14 天待取快递（取件码、驿站、到站时间）。用户问快递时必用。",
    "list_todo：查待办提醒清单。用户问接下来有什么事时必用。",
    "ledger_summary：查账本收支小结（按天/分类）。用户问花了多少钱时必用。",
    "search_memory：在本地记忆里检索历史消息。用户问以前/记忆里的事时必用。",
)

/** 本地模型专用的完整 system prompt 片段（含格式约定）。 */
fun zhuliLocalToolsPrompt(): String = ZhuliLocalToolBridge.usagePrompt(ZHULI_LOCAL_TOOL_LINES)
