package com.zhuli.bridge

import com.zhuli.core.PrivacyDecision

/**
 * 隐私路由判定核心（T-G2/B1 移植，来自 zhuli 旧壳 PrivacyRouter）。
 *
 * 设计取舍：旧壳 [PrivacyRouter] 的判定核心是纯逻辑，但被 Context / AssistantPrefs / 写库 audit 缠住。
 * 这里把"云配置"抽成 [Config] 由调用方注入（fork 侧接线时传 LastChat 的 provider 设置），
 * 判定规则与三层自检 1:1 复用，去掉 Context 与审计写库（审计属新壳自己的日志体系）。
 *
 * 硬口径不变：拿不准按敏感处理；硬红线（证件号/卡号/凭证）脱敏也不发。
 */
object ZhuliPrivacy {

    /** 由宿主注入的云端状态（不依赖 Android Context）。 */
    data class Config(
        val cloudReady: Boolean,
        val baseUrl: String,
        val strict: Boolean = false,
        /** 本机回环地址视为安全（与旧壳 CloudClient.isEndpointSafe 一致） */
        val isLoopback: (String) -> Boolean = { url ->
            Regex("^https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?(/|$)", RegexOption.IGNORE_CASE)
                .containsMatchIn(url)
        },
    )

    enum class Task(val label: String) {
        CHAT("闲聊"), LINK("链接摘要"), VOICE("语音转写"), VIDEO("视频理解")
    }

    data class Decision(val allow: Boolean, val reason: String, val text: String = "", val redacted: Boolean = false)

    private val defaultLoopback: (String) -> Boolean = { url ->
        Regex("^https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?(/|$)", RegexOption.IGNORE_CASE)
            .containsMatchIn(url)
    }

    /** HTTPS 或本机回环才允许出网。 */
    fun isEndpointSafe(baseUrl: String, isLoopback: (String) -> Boolean = defaultLoopback): Boolean {
        if (baseUrl.isBlank()) return false
        if (isLoopback(baseUrl)) return true
        return baseUrl.startsWith("https://", ignoreCase = true)
    }

    /**
     * 出网判定（对齐旧壳 PrivacyRouter.decide）：
     * 1. 没配 Key / 地址非 HTTPS（回环除外）→ 拦；
     * 2. 命中硬红线 → 拦（脱敏也不发）；
     * 3. strict 模式下任何敏感 → 拦；
     * 4. 其余敏感 → 脱敏后放行；非敏感 → 原样放行。
     *
     * 批次 A 起判定本身收敛到 [PrivacyDecision]，这里只做参数装配。
     */
    fun decide(config: Config, task: Task, text: String): Decision {
        val v = PrivacyDecision.decide(
            cloudReady = config.cloudReady,
            endpointSafe = isEndpointSafe(config.baseUrl, config.isLoopback),
            strict = config.strict,
            text = text,
        )
        return Decision(v.allow, v.reason, v.text, v.redacted)
    }

    /** 硬红线判定：只有"脱敏也不该发"的类型才返回类型名。 */
    fun hardRedLine(text: String): String? = PrivacyDecision.hardRedLine(text)

    /** 自检样例（对齐旧壳 DEFAULT_SAMPLES）。 */
    val defaultSamples = listOf(
        "帮我把今天的事捋一捋",
        "明天下午三点开会别忘",
        "我手机号 13800001111",
        "身份证 110101199003071234",
        "工资到账 8000 元",
        "我家在朝阳区建国路 88 号 3 号楼 2 单元",
        "我们公司市场部经理姓王",
        "验证码是 8823"
    )

    /** 一层自检：逐句给判定结论（纯判定，不联网）。 */
    fun selfTest(samples: List<String> = defaultSamples): List<Triple<String, Boolean, String>> =
        samples.map { s ->
            val why = ZhuliSensitiveGuard.reason(s)
            Triple(s, why == null, why ?: "非敏感")
        }

    /** 三层自检的一行结果（对齐旧壳 SelfCheck）。 */
    data class SelfCheck(
        val text: String,
        val verdict: String,
        val outgoing: String,
        val redactOk: Boolean,
        val payloadOk: Boolean,
        val note: String = "",
    ) {
        val allOk: Boolean get() = redactOk && payloadOk
    }

    /**
     * 三层自检：判定 → 脱敏 → 模拟请求体。
     * 第三层拿 hits() 的原始片段去比对请求体（摘掉占位符后），片段还在即不合格——
     * 避免"只测第一层"和"再判一次的循环论证"（旧壳 D1/D2/D3 踩过的坑）。
     */
    fun selfTest3(
        config: Config = Config(cloudReady = true, baseUrl = "https://example.com/v1"),
        samples: List<String> = defaultSamples,
    ): List<SelfCheck> = samples.map { s ->
        val d = decide(config, Task.CHAT, s)
        if (!d.allow) {
            SelfCheck(s, "留本地（${d.reason}）", "", true, true, "整句未出网")
        } else {
            val outgoing = d.text.ifBlank { s }
            val stillSensitive = ZhuliSensitiveGuard.reason(outgoing)
            val payload = buildString {
                append("{\"model\":\"self-check\",\"messages\":[")
                append("{\"role\":\"system\",\"content\":\"你是助理\"},")
                append("{\"role\":\"user\",\"content\":\"").append(escape(outgoing)).append("\"}]}")
            }
            val stripped = payload.replace(Regex("\\[[^\\]]{1,8}\\]"), "")
            val leaked = ZhuliSensitiveGuard.hits(s).filter { it.isNotBlank() && stripped.contains(it) }
            SelfCheck(
                text = s,
                verdict = if (d.redacted) "可上云（已脱敏）" else "可上云",
                outgoing = outgoing,
                redactOk = stillSensitive == null,
                payloadOk = leaked.isEmpty(),
                note = listOfNotNull(
                    stillSensitive?.let { "脱敏后仍被判：$it" },
                    leaked.takeIf { it.isNotEmpty() }?.let { "请求体残留：${it.joinToString("/")}" },
                ).joinToString("；"),
            )
        }
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
