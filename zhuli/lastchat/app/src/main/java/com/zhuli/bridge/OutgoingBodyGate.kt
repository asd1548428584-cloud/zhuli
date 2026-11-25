package com.zhuli.bridge

import com.zhuli.core.PrivacyPipeline

/**
 * 出网拦截器的纯逻辑部分（批次 A 抽出，可在 JVM 单测直接钉住）。
 *
 * 为什么单独抽：拦截器本体依赖 OkHttp 的 `Interceptor.Chain`，单测里很难覆盖
 * 「请求体读不出来」「不是 JSON」「结构不认识」这些**恰恰最容易 fail-open** 的分支。
 * 把判定收到这里之后，每一种失败形态都有一条可执行断言。
 *
 * 口径：**无法扫描 = 不许出网**。宁可让这条请求失败并留下记录，
 * 也不允许「解析不了就原样发」——那正是历史缺陷 D1/D2/D3 的成因。
 */
internal object OutgoingBodyGate {

    data class Scanned(
        val body: String,
        val shape: String,
        val redactions: Int,
        val scannedFields: Int,
    )

    sealed interface Decision {
        data class Send(val scanned: Scanned) : Decision
        data class Block(val reason: String) : Decision
    }

    /**
     * @param raw 已读出的请求体；null 表示不可读（one-shot / duplex）
     * @param strict 用户选了「严格模式」时为 true：任何敏感项都不出网
     */
    fun decide(raw: String?, strict: Boolean): Decision {
        if (raw == null) {
            return Decision.Block("请求体不可读（one-shot/duplex），无法在出网前完成扫描")
        }
        if (raw.isBlank()) {
            return Decision.Block("请求体为空，拒绝原样出网")
        }
        return try {
            val out = PrivacyPipeline.scrub(
                payloadJson = raw,
                rules = PrivacyPipeline.Rules(
                    hardRedLine = { ZhuliPrivacy.hardRedLine(it) },
                    sensitiveReason = { ZhuliSensitiveGuard.reason(it) },
                    redact = { ZhuliSensitiveGuard.redact(it) },
                    strict = strict,
                ),
                // 走到这里的都是聊天/Responses/embeddings 端点：结构不认识就中止
                requireKnownShape = true,
            )
            Decision.Send(
                Scanned(
                    body = out.payload,
                    shape = out.shape.label,
                    redactions = out.redactions.size,
                    scannedFields = out.scannedFields,
                )
            )
        } catch (e: PrivacyPipeline.Blocked) {
            Decision.Block("${e.where} 含${e.reason}")
        }
    }
}
