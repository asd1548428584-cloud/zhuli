package com.zhuli.core

import android.content.Context
import com.zhuli.core.ZhuliLog as Log

/**
 * 隐私路由（第 9 步）：所有出网调用的唯一闸门。
 *
 * 判定顺序（任一不过就留在本地，绝不上云）：
 * 1. 用户没配 Key / 配了但地址不是 HTTPS（回环除外）→ 不上云；
 * 2. 待发内容命中 [SensitiveGuard] 任一特征（电话/证件/卡号/凭证/金额/地址/公司+岗位）→ 不上云；
 * 3. 通过 → 放行，并在 `cloud_log` 留一条脱敏摘要。
 *
 * 「拿不准按敏感处理」是硬口径：宁可聊天傻一点，也不让用户的钱、住址、证件出机。
 */
object PrivacyRouter {

    private const val TAG = "zhuli.privacy"

    /** 出网任务类型（留档与用户可见提示都用它）。 */
    enum class Task(val label: String) {
        CHAT("闲聊"),
        LINK("链接摘要"),
        VOICE("语音转写"),
        /** 视频理解（2026-11-21）：画面帧出网给视觉模型读字幕/看内容 */
        VIDEO("视频理解")
    }

    /** text = 实际要发出去的内容（可能已脱敏）；redacted = 是否打了码 */
    data class Decision(val allow: Boolean, val reason: String, val text: String = "", val redacted: Boolean = false)

    /**
     * 三级策略（需求调整于 2026-11-20）：
     * 1. 非敏感 → 原样上云；
     * 2. 敏感（电话/金额/地址/公司+岗位…）→ **脱敏后上云**（默认模式，用户选的"本地脱敏再上云"）；
     * 3. 硬红线（证件号/卡号/凭证）→ 脱敏也不发，只在本地说 —— 这几类脱敏后仍有识别风险，
     *    而且命中它们本身就说明这句话不该交给外人看。
     * 另：[AssistantPrefs.privacyMode] 设成 strict 时退化为"任何敏感都不出机"（第 9 步的原始口径）。
     */
    fun decide(ctx: Context, task: Task, text: String): Decision {
        if (!AssistantPrefs.cloudReady(ctx)) return Decision(false, "没配云端 Key，只在本地说", text)
        val base = AssistantPrefs.linkBaseUrl(ctx)
        if (!CloudClient.isEndpointSafe(base)) {
            return Decision(false, "云端地址不是 HTTPS，为安全起见不发（本机回环除外）", text)
        }
        val why = SensitiveGuard.reason(text)
        if (why == null) return Decision(true, "", text, redacted = false)
        if (why in HARD_RED_LINES) {
            return Decision(false, "这句话里有$why，脱敏也不发，只在本地说", text)
        }
        if (AssistantPrefs.privacyMode(ctx) == "strict") {
            return Decision(false, "这句话里有$why，按严格模式只在本地说", text)
        }
        return Decision(true, "", SensitiveGuard.redact(text), redacted = true)
    }

    /** 这几类不参与"脱敏上云"：证件号、卡号、凭证（验证码/密码/token）。 */
    private val HARD_RED_LINES = setOf("证件号", "卡号", "凭证", "凭证链接")

    /**
     * 硬红线判定：只有"脱敏也不该发"的那几类才返回类型名，其余返回 null。
     * 供 [CloudClient] 的最后一道闸门使用（T-B3）—— 它只关心"该不该中止整轮"。
     */
    fun hardRedLine(text: String): String? {
        val why = SensitiveGuard.reason(text) ?: return null
        return if (why in HARD_RED_LINES) why else null
    }

    /** 写一条出网留档（内容先脱敏，再截断）。 */
    fun audit(
        ctx: Context,
        task: Task,
        host: String,
        model: String,
        content: String,
        ok: Boolean,
        note: String = ""
    ) {
        try {
            val db = InboxDatabase(ctx.applicationContext)
            try {
                db.appendCloudLog(task.label, host, model, SensitiveGuard.redact(content), ok, note)
            } finally {
                try { db.close() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            Log.w(TAG, "audit failed", t)
        }
    }

    /**
     * 自检样例：把一组典型句子跑一遍闸门，给壳里「隐私自检」面板用。
     * 不联网、纯本地判定，用来当场证明「这类内容不会出机」。
     */
    fun selfTest(samples: List<String> = DEFAULT_SAMPLES): List<Triple<String, Boolean, String>> =
        samples.map { s ->
            val why = SensitiveGuard.reason(s)
            Triple(s, why == null, why ?: "非敏感")
        }

    private val DEFAULT_SAMPLES = listOf(
        "帮我把今天的事捋一捋",
        "明天下午三点开会别忘",
        "我手机号 13800001111",
        "身份证 110101199003071234",
        "工资到账 8000 元",
        "我家在朝阳区建国路 88 号 3 号楼 2 单元",
        "我们公司市场部经理姓王",
        "验证码是 8823"
    )

    // ==================== T-B7 三层自检 ====================

    /** 三层自检的一行结果。 */
    data class SelfCheck(
        val text: String,
        /** 第一层：判定结论（可上云 / 留本地 + 原因） */
        val verdict: String,
        /** 第二层：实际会发出去的内容（被拦时为空） */
        val outgoing: String,
        /** 第二层是否合格：脱敏后不再被判敏感 */
        val redactOk: Boolean,
        /** 第三层是否合格：模拟的请求体里查不到原始命中片段 */
        val payloadOk: Boolean,
        val note: String = ""
    ) {
        val allOk: Boolean get() = redactOk && payloadOk
    }

    /**
     * 三层自检：判定 → 脱敏 → 请求体。
     *
     * 为什么必须三层：本项目栽过的坑就是"只测第一层"——
     * 判定说"敏感、留本地"或"敏感、已脱敏"，可请求体里原文照旧（D1/D2/D3）。
     * 所以第三层拿 [SensitiveGuard.hits] 抽出的**原始片段**去比对模拟请求体，
     * 片段还在就是不合格，不依赖任何"再判一次"的循环论证。
     */
    fun selfTest3(ctx: Context, samples: List<String> = DEFAULT_SAMPLES): List<SelfCheck> =
        samples.map { s ->
            val d = decide(ctx, Task.CHAT, s)
            if (!d.allow) {
                return@map SelfCheck(
                    text = s,
                    verdict = "留本地（${d.reason}）",
                    outgoing = "",
                    redactOk = true,
                    payloadOk = true,
                    note = "整句未出网"
                )
            }
            val outgoing = d.text.ifBlank { s }
            val stillSensitive = SensitiveGuard.reason(outgoing)
            // 模拟真实请求体结构（system + user），跟 CloudClient 组包形状一致
            val payload = buildString {
                append("{\"model\":\"self-check\",\"messages\":[")
                append("{\"role\":\"system\",\"content\":\"你是助理\"},")
                append("{\"role\":\"user\",\"content\":\"").append(escape(outgoing)).append("\"}]}")
            }
            // 扫描前先把占位符摘掉：占位符本身就含"公司/岗位"这类字，
            // 不摘会把「已脱敏」误判成「有残留」（第一版面板就踩了这个，自检自己误报）
            val stripped = payload.replace(Regex("\\[[^\\]]{1,8}\\]"), "")
            val leaked = SensitiveGuard.hits(s).filter { it.isNotBlank() && stripped.contains(it) }
            SelfCheck(
                text = s,
                verdict = if (d.redacted) "可上云（已脱敏）" else "可上云",
                outgoing = outgoing,
                redactOk = stillSensitive == null,
                payloadOk = leaked.isEmpty(),
                note = listOfNotNull(
                    stillSensitive?.let { "脱敏后仍被判：$it" },
                    leaked.takeIf { it.isNotEmpty() }?.let { "请求体残留：${it.joinToString("/")}" }
                ).joinToString("；")
            )
        }

    private fun escape(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append(' ') else append(c)
        }
    }
}
