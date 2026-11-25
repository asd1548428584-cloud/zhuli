package com.zhuli.core

/**
 * T-B11 执行闸门：三档确认（借 DSH-Go）+ 硬禁止名单。
 *
 * 只判定「该不该问 / 能不能放行」，**不弹窗、不 import android.***，
 * 所以纯 JVM 单测能覆盖全部分支。UI 层只负责把选择回传。
 *
 * 口径（V3.1 §1.3）：
 * - 允许一次：只这一下（默认）；
 * - 本轮允许：本次任务内**同类**操作不再问，任务结束即失效，不跨会话；
 * - 取消：结束本轮。
 *
 * 硬约束不受分档影响：支付/银行/券商、密码/验证码/证件输入、系统设置/权限页
 * 一律拦截；发消息、代付款、批量点屏、改系统权限即使"本轮允许"也不放行。
 */
enum class Grant { ONCE, ROUND, CANCEL }

sealed class GateCheck {
    /** 本轮同类已授权，直接执行。 */
    object AllowRound : GateCheck()
    /** 命中硬禁止名单，无条件停。 */
    data class HardDeny(val reason: String) : GateCheck()
    /** 需要弹窗问用户。 */
    object Ask : GateCheck()
}

class ActionGate(private val clock: () -> Long = { System.currentTimeMillis() }) {

    companion object {
        /** 3 分钟无操作自动结束本轮（V3.1 §1.3 硬约束 5）。 */
        const val ROUND_TIMEOUT_MS = 3L * 60 * 1000

        /** 动作类型：同类共享"本轮允许"，不同类仍要问。 */
        const val OP_SCREEN_CLICK = "screen_click"
        const val OP_WRITE_REMINDER = "write_reminder"
        const val OP_WRITE_MEMORY = "write_memory"

        /**
         * 目标文字命中即停。只匹配**明确**的场景词，不用「银行」这种单字泛化，
         * 免得一条「招商银行到账」的普通通知把整个屏幕操作锁死。
         */
        val HARD_DENY_PATTERNS: List<Pair<String, String>> = listOf(
            "确认支付" to "支付确认页",
            "立即付款" to "付款页",
            "输密码" to "密码输入",
            "输入密码" to "密码输入",
            "支付密码" to "支付密码",
            "验证码" to "验证码输入",
            "身份证号" to "证件输入",
            "银行卡号" to "银行卡输入",
            "系统设置" to "系统设置页",
            "权限管理" to "权限页",
            "开发者选项" to "开发者选项"
        )

        /**
         * 这些动作永远不能进"本轮允许"：.model 理解意图 ≠ 获得执行权限。
         * 即使 UI 回传 ROUND，也只按 ONCE 执行。
         */
        val NEVER_ROUND = setOf(
            "send_message", "pay", "delete", "bulk_modify",
            "system_permission", "continuous_screen"
        )
    }

    private var roundEndsAt = 0L
    private var roundSeq = 0L
    private val roundGrants = HashSet<String>()

    /** 开始一轮任务。重复调用等于重置本轮（授权不继承）。 */
    fun beginRound(nowMs: Long = clock()): Long {
        roundSeq++
        roundEndsAt = nowMs + ROUND_TIMEOUT_MS
        roundGrants.clear()
        return roundSeq
    }

    fun endRound() {
        roundEndsAt = 0L
        roundGrants.clear()
    }

    /** 息屏即停：语义上就是结束本轮，但保留 seq 便于日志追溯。 */
    fun screenOff() = endRound()

    fun roundActive(nowMs: Long = clock()): Boolean = roundEndsAt > 0 && nowMs < roundEndsAt

    /** 有进展就续期，实现"3 分钟无操作自动结束"。 */
    fun touch(nowMs: Long = clock()) {
        if (roundActive(nowMs)) roundEndsAt = nowMs + ROUND_TIMEOUT_MS
    }

    fun hardBlockReason(targetText: String): String? {
        if (targetText.isBlank()) return null
        return HARD_DENY_PATTERNS.firstOrNull { targetText.contains(it.first) }?.second
    }

    /**
     * 判定一次动作。返回 Ask 时 UI 必须弹窗并把结果传给 [record]。
     */
    fun check(op: String, targetText: String = "", nowMs: Long = clock()): GateCheck {
        if (!roundActive(nowMs)) return GateCheck.Ask      // 没开轮 = 每步都问，回退安全档
        hardBlockReason(targetText)?.let { return GateCheck.HardDeny(it) }
        if (op in roundGrants) return GateCheck.AllowRound
        return GateCheck.Ask
    }

    /**
     * 记录用户选择。CANCEL 直接结束本轮；ROUND 只对非 NeverRound 的动作生效。
     *
     * @return 是否允许执行这一次。
     */
    fun record(op: String, grant: Grant, nowMs: Long = clock()): Boolean {
        touch(nowMs)
        return when (grant) {
            Grant.CANCEL -> { endRound(); false }
            Grant.ROUND -> {
                if (op !in NEVER_ROUND) roundGrants.add(op)
                true
            }
            Grant.ONCE -> true
        }
    }

    /** 仅供自检面板展示：本轮已放行的同类动作数。 */
    fun grantedCount(): Int = roundGrants.size
}
