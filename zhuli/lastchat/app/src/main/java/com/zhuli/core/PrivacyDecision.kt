package com.zhuli.core

/**
 * 出网判定核心（纯 JVM，无 Android 依赖）。
 *
 * 批次 A 的统一实现：旧壳 [PrivacyRouter] 与新壳 `ZhuliPrivacy` 原先各写了一份**完全相同**的
 * 判定（cloudReady → 端点安全 → 硬红线 → strict → 脱敏），两份代码一旦分叉就会出现
 * 「一处改了、另一处还漏」的经典问题。现在两个入口都只做参数装配，判定只此一处。
 *
 * 口径（与 2026-11-20 用户定的三级策略一致）：
 * 1. 没配 Key / 端点不是 HTTPS（回环除外）→ 不发；
 * 2. 硬红线（证件号/卡号/凭证）→ 脱敏也不发；
 * 3. strict 模式下任何敏感 → 不发；
 * 4. 其余敏感 → 脱敏后发；非敏感 → 原样发。
 */
object PrivacyDecision {

    /** 脱敏后仍有识别风险、绝不参与「脱敏上云」的类型。 */
    val HARD_RED_LINES: Set<String> = setOf("证件号", "卡号", "凭证", "凭证链接")

    data class Verdict(
        val allow: Boolean,
        val reason: String,
        val text: String,
        val redacted: Boolean,
    )

    /** 只有「脱敏也不该发」的那几类才返回类型名。 */
    fun hardRedLine(text: String): String? {
        val why = SensitiveGuard.reason(text) ?: return null
        return if (why in HARD_RED_LINES) why else null
    }

    fun decide(
        cloudReady: Boolean,
        endpointSafe: Boolean,
        strict: Boolean,
        text: String,
    ): Verdict {
        if (!cloudReady) return Verdict(false, "没配云端 Key，只在本地说", text, false)
        if (!endpointSafe) {
            return Verdict(false, "云端地址不是 HTTPS，为安全起见不发（本机回环除外）", text, false)
        }
        val why = SensitiveGuard.reason(text) ?: return Verdict(true, "", text, false)
        if (why in HARD_RED_LINES) {
            return Verdict(false, "这句话里有$why，脱敏也不发，只在本地说", text, false)
        }
        if (strict) {
            return Verdict(false, "这句话里有$why，按严格模式只在本地说", text, false)
        }
        return Verdict(true, "", SensitiveGuard.redact(text), true)
    }
}
