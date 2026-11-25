package com.zhuli.agent

/**
 * 工具注册表 + 权限策略。
 *
 * 与旧实现的关键差别：旧代码把「工具定义」「参数校验」「权限弹窗」「副作用」混在一个 object 里，
 * 于是模型能绕开闸门直接调（`Tools.run` 里那句 `ActionConfirm.ask` 是唯一防线，且它只认三条固定操作）。
 * 现在拆开：
 * - [RegisteredTool] 只声明「我是谁、我多危险」；
 * - [PolicyEngine] 独立判定「这一次能不能做」；
 * - 执行器由宿主注入，执行前必须已经过 policy。
 *
 * 这样 `AgentLoop` 里只有一条路径能碰到副作用：`policy.decide → execute`，绕不过去。
 */

/** 风险等级。数字越大越危险，排序与展示都靠它。 */
enum class Risk(val rank: Int, val label: String) {
    /** 只读本地数据，不改变任何状态 */
    READ_ONLY(0, "只读"),

    /** 写本地库（记忆、待办），可撤销 */
    WRITE_LOCAL(1, "写本地"),

    /** 对外产生影响（发消息、改日历邀请），必须每次确认 */
    WRITE_EXTERNAL(2, "对外"),

    /** 不可撤销（删除、付款）。本轮一律不允许「同类免问」 */
    IRREVERSIBLE(3, "不可逆");

    companion object {
        /** 从持久化的等级名还原；脏数据按最坏情况处理（写本地，需要确认）。 */
        fun of(name: String): Risk = entries.firstOrNull { it.name == name } ?: WRITE_LOCAL
    }
}

enum class Decision { ALLOW, ASK, DENY }

data class PolicyResult(val decision: Decision, val reason: String, val risk: Risk)

data class ToolResult(val ok: Boolean, val text: String, val error: String = "") {
    companion object {
        fun ok(text: String) = ToolResult(true, text)
        fun fail(error: String, text: String = "") = ToolResult(false, text, error)
    }
}

/** 一个可执行工具。executor 收到的是模型给的原始 JSON 参数。 */
class RegisteredTool(
    val spec: ToolSpec,
    val risk: Risk,
    /** 同类授权的键：同一 op 在本轮内可以「同类免问」 */
    val op: String,
    val execute: suspend (argsJson: String) -> ToolResult,
)

class ToolRegistry(tools: List<RegisteredTool>) {

    private val byName: Map<String, RegisteredTool>

    init {
        val dup = tools.groupBy { it.spec.name }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "工具名重复：$dup" }
        byName = tools.associateBy { it.spec.name }
    }

    fun get(name: String): RegisteredTool? = byName[name]

    fun specs(): List<ToolSpec> = byName.values.map { it.spec }

    /** 只给模型看它当前被允许用的工具（比如本地模型不支持工具时传空表）。 */
    fun specsFor(allow: (RegisteredTool) -> Boolean): List<ToolSpec> =
        byName.values.filter(allow).map { it.spec }

    fun all(): List<RegisteredTool> = byName.values.toList()

    fun describe(): String = byName.values.joinToString("\n") { t ->
        "· ${t.spec.name}（${t.risk.label}）${t.spec.description.take(40)}"
    }
}

/**
 * 权限判定。
 *
 * 规则（与 V3.1 §1.3 一致，只是从「屏幕点击」扩展到「工具调用」）：
 * - 只读：直接放行（不打扰用户是产品要求，读自己的库不需要许可）；
 * - 写本地：第一次问，用户选「本轮允许」后同类不再问；轮次 5 分钟没有新动作就失效；
 * - 对外：每次都问，不接受「同类免问」；
 * - 不可逆：每次都问，且不会因为「本轮允许」而放行；
 * - 命中硬禁止词（支付/密码/验证码/证件/系统权限）：直接拒绝，连问都不问。
 */
class PolicyEngine(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** 一轮授权有效期：5 分钟。比屏幕操作的 3 分钟宽一点，因为 Agent 一步可能要等模型。 */
        const val ROUND_TTL_MS = 5L * 60 * 1000

        /** 命中即拒绝。这些词出现在**参数**里，说明模型想碰不该碰的东西。 */
        val HARD_DENY: List<Pair<String, String>> = listOf(
            "支付密码" to "支付密码",
            "确认支付" to "支付确认",
            "立即付款" to "付款操作",
            "短信验证码" to "验证码",
            "身份证号" to "证件信息",
            "银行卡号" to "银行卡信息",
            "开发者选项" to "系统设置",
            "pm grant" to "系统权限修改",
            "su -c" to "提权命令",
        )

        /** 这些 op 永远不进「本轮允许」。 */
        val NEVER_ROUND = setOf("send_message", "pay", "delete", "system_permission")
    }

    private var roundEndsAt = 0L
    private val granted = HashSet<String>()
    /** 批次 B：用户逐次批准过的调用指纹 → 授权到期时间。参数一变指纹就对不上。 */
    private val authorized = HashMap<String, Long>()

    fun beginRound(now: Long = clock()) {
        roundEndsAt = now + ROUND_TTL_MS
        granted.clear()
        authorized.clear()
    }

    fun endRound() {
        roundEndsAt = 0L
        granted.clear()
        authorized.clear()
    }

    /** 只授权这一次具体调用（不是整个 op），且只在有效轮次内生效。 */
    fun authorize(fingerprint: String, now: Long = clock()): Boolean {
        if (!roundActive(now)) return false
        authorized[fingerprint] = roundEndsAt
        return true
    }

    /** 高风险工具执行前的最后一道闸：没有对应指纹的批准就不许执行。 */
    fun isAuthorized(fingerprint: String, now: Long = clock()): Boolean {
        val until = authorized[fingerprint] ?: return false
        if (now >= until || now >= roundEndsAt) {
            authorized.remove(fingerprint)
            return false
        }
        return true
    }

    fun roundActive(now: Long = clock()): Boolean = roundEndsAt > 0 && now < roundEndsAt

    fun grant(op: String) {
        if (op !in NEVER_ROUND) granted.add(op)
    }

    fun grantedOps(): Set<String> = granted.toSet()

    fun decide(tool: RegisteredTool, argsJson: String, now: Long = clock()): PolicyResult {
        hardDenyReason(argsJson)?.let {
            return PolicyResult(Decision.DENY, "命中硬禁止：$it", tool.risk)
        }
        return when (tool.risk) {
            Risk.READ_ONLY -> PolicyResult(Decision.ALLOW, "只读本地数据", tool.risk)
            Risk.WRITE_LOCAL ->
                if (roundActive(now) && tool.op in granted) PolicyResult(Decision.ALLOW, "本轮已授权同类操作", tool.risk)
                else PolicyResult(Decision.ASK, "要写入本地数据，需要你确认", tool.risk)
            Risk.WRITE_EXTERNAL ->
                PolicyResult(Decision.ASK, "会产生对外影响，必须逐次确认", tool.risk)
            Risk.IRREVERSIBLE ->
                PolicyResult(Decision.ASK, "不可逆操作，必须逐次确认", tool.risk)
        }
    }

    private fun hardDenyReason(text: String): String? {
        if (text.isBlank()) return null
        return HARD_DENY.firstOrNull { text.contains(it.first, ignoreCase = true) }?.second
    }
}
