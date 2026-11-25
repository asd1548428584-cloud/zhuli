package com.zhuli.core

/**
 * 出网请求体的统一闸门（纯 JVM，可单测）。
 *
 * 历史：v15 的 `CloudClient.completeOnce()` 只把每条消息的 `content` 过闸，
 * 而 `assistant.tool_calls[].function.arguments` 是一段**字符串里的 JSON**，
 * 它会在后续每一轮请求里被原样重新发出去 —— 用户手机号、地址、取件码、金额
 * 就藏在那一层嵌套里，上一版完全没碰。
 *
 * 批次 A 起，真正的扫描逻辑收敛到 [PrivacyPipeline]（同一份实现同时服务
 * `CloudClient` 与 `ZhuliPrivacyInterceptor`），本类只保留旧调用方的稳定 API，
 * 避免两套规则分叉。
 */
object OutgoingGate {

    /** 内容被拦下。带上位置与原因，便于审计与用户提示。 */
    class Blocked(val where: String, val reason: String) : Exception("$where 含$reason，这一轮不上云")

    /** 可脱敏的文本被改写了一条。 */
    data class Redaction(val where: String, val reason: String)

    data class Result(val payload: String, val redactions: List<Redaction>) {
        val changed: Boolean get() = redactions.isNotEmpty()
    }

    /**
     * @param hardRedLine 返回非空 = 这类内容脱敏也不许发（证件号/卡号/凭证）
     * @param sensitiveReason 返回非空 = 敏感但可脱敏（电话/地址/金额/取件码/运单号/公司+岗位）
     * @param redact 脱敏函数
     * @param requireMessages 聊天请求必须为 true：结构异常时**中止出网**而不是原样放行。
     */
    fun scrub(
        payloadJson: String,
        hardRedLine: (String) -> String?,
        sensitiveReason: (String) -> String?,
        redact: (String) -> String,
        requireMessages: Boolean = false,
    ): Result {
        val rules = PrivacyPipeline.Rules(
            hardRedLine = hardRedLine,
            sensitiveReason = sensitiveReason,
            redact = redact,
        )
        val out = try {
            PrivacyPipeline.scrub(
                payloadJson = payloadJson,
                rules = rules,
                requireKnownShape = requireMessages,
            )
        } catch (e: PrivacyPipeline.Blocked) {
            throw Blocked(e.where, e.reason)
        }
        return Result(out.payload, out.redactions.map { Redaction(it.where, it.reason) })
    }
}
