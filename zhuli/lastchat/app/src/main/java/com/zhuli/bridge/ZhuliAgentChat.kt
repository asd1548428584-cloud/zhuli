package com.zhuli.bridge

import android.content.Context
import com.zhuli.agent.AgentTask
import com.zhuli.agent.RunOutcome
import com.zhuli.agent.TaskStatus
import com.zhuli.agent.ZhuliAgentRuntime
import com.zhuli.core.ZhuliLog as Log

/**
 * 主聊天 ↔ Agent 的最小桥接（P1 第七条）。
 *
 * 设计原则（先跑通，不重做 UI）：
 * - **普通闲聊不进 Agent**：只有明确要"动手 / 查本地数据"的话才走多步循环，
 *   其余照旧交给原来的聊天链路，自然对话体验不变；
 * - **回答回到同一个消息列表**：Agent 的最终答复就是一条 assistant 文本，前端零改动；
 * - **审批在聊天里完成**：挂起时回一条带任务号与风险等级的消息，用户回「允许 / 拒绝」即可；
 * - **权限只有一套**：聊天里批准走的就是 [ZhuliAgentRuntime.approve]，
 *   和任务页按钮是同一条路径，不存在两套互相冲突的弹窗；
 * - **失败就退回普通聊天**：Agent 失败不吞掉用户的问题，让他照常得到回答，
 *   详细步骤留在任务页可查。
 */
object ZhuliAgentChat {

    data class Reply(
        val text: String,
        val taskId: String,
        val awaitingApproval: Boolean = false,
    )

    /** 写操作/取数据的强意图词。宁可少拦，也不要让闲聊被拖进多步循环。 */
    private val ACTION_WORDS = listOf(
        "提醒我", "叫我", "别忘了", "定个闹钟", "建个提醒", "设个提醒",
        "记住", "记一下", "记下来", "备忘", "帮我记",
        "帮我查", "帮我找", "帮我看", "查一下", "查查", "看看我的", "找一下",
        "还有多久", "什么时候",
    )

    private val DATA_WORDS = listOf(
        "快递", "取件", "驿站", "包裹", "账", "花了", "支出", "收入",
        "待办", "日程", "会议", "记忆", "事实", "通知", "短信", "取件码",
    )

    private val APPROVE_WORDS = setOf("允许", "同意", "可以", "执行", "确认", "好", "嗯", "yes", "ok")
    private val DENY_WORDS = setOf("拒绝", "不要", "别", "取消", "不用", "no", "不行")

    /** 这条用户输入是否该交给 Agent。 */
    fun needsAgent(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3) return false
        if (ACTION_WORDS.any { t.contains(it) }) return true
        // 「快递到哪了」这类纯查询也走 Agent（要真查库，而不是让模型编）
        return DATA_WORDS.any { t.contains(it) } && listOf("哪", "几", "多少", "有没有", "查", "什么").any { t.contains(it) }
    }

    /** 解析「允许 / 拒绝」，不是裁决语句就返回 null。保留给意图判定用。 */
    fun parseApproval(text: String): Boolean? = parseApprovalToken(text)?.first

    /**
     * 解析「允许 / 拒绝 [任务号前缀]」→ (是否允许, 任务号前缀或 null)。
     * 允许用户在有多个待确认任务时带上任务号（如「允许 a1b2c3d4」）精确指定。
     */
    private fun parseApprovalToken(text: String): Pair<Boolean, String?>? {
        val t = text.trim().lowercase()
        if (t.isBlank()) return null
        val parts = t.split(Regex("\\s+"), limit = 2)
        val word = parts[0]
        if (word.length > 8) return null // 长句子交给正常流程，不猜
        val allow = when {
            DENY_WORDS.any { word == it || word.startsWith(it) } -> false
            APPROVE_WORDS.any { word == it || word.startsWith(it) } -> true
            else -> return null
        }
        val prefix = parts.getOrNull(1)?.trim()
            ?.takeIf { it.matches(Regex("[0-9a-z-]{4,40}")) }
        return allow to prefix
    }

    /** 当前所有等待确认的聊天任务（按创建时间倒序）。 */
    suspend fun pendingApprovals(): List<AgentTask> = runCatching {
        ZhuliAgentRuntime.store.active()
            .filter { it.status == TaskStatus.WAITING_APPROVAL && it.origin == "chat" }
            .sortedByDescending { it.createdAt }
    }.getOrDefault(emptyList())

    /** 兼容旧调用方：最新的一个待确认任务。 */
    suspend fun pendingApproval(): AgentTask? = pendingApprovals().firstOrNull()

    /**
     * 处理「允许 / 拒绝」。
     * @return null = 这不是裁决语句，调用方继续走正常流程
     */
    suspend fun handleApproval(context: Context, text: String): Reply? {
        val (allow, prefix) = parseApprovalToken(text) ?: return null
        // A 修复：必须先确保 Runtime 初始化，再查询持久化的待审批任务。
        // 原实现先 pendingApproval() 再 init —— 进程重启后 store 是 lateinit 未初始化，
        // runCatching 把异常吞成 null，导致重启后的审批永远无法恢复。
        ZhuliAgentRuntime.init(context)
        val candidates = pendingApprovals()
        if (candidates.isEmpty()) return null
        // B 修复：多个任务同时等待确认时，不能仅凭「最新一个」就无条件执行。
        // 用户带了任务号就精确定位；没带且多于一个则列出清单要求明确，绝不猜。
        val task = if (prefix != null) {
            candidates.firstOrNull { it.id.startsWith(prefix) }
                ?: return Reply("没找到编号以「$prefix」开头的待确认任务，请核对后重试。", "")
        } else if (candidates.size == 1) {
            candidates.first()
        } else {
            return Reply(
                buildString {
                    appendLine("你有 ${candidates.size} 件事在等我确认，为避免弄错，请回复「允许 <任务号>」或「拒绝 <任务号>」明确指定：")
                    for (t in candidates) appendLine("· ${t.id.take(8)}｜${t.goal.take(40)}")
                },
                candidates.first().id,
            )
        }
        // 把用户当时看到的那条调用指纹一并传下去：参数中途变了就自动失效。
        val outcome = runCatching { ZhuliAgentRuntime.approve(task.id, allow, task.pendingApproval?.fingerprint) }
            .onFailure { Log.w("zhuli.chat", "approve failed", it) }
            .getOrNull() ?: return Reply("这一步没能执行，具体原因在任务页里。", task.id)
        return toReply(outcome)
    }

    /**
     * 尝试用 Agent 处理一条用户消息。
     * @return null = 不该由 Agent 处理（或 Agent 失败），调用方退回普通聊天
     */
    suspend fun tryHandle(context: Context, text: String): Reply? {
        if (!needsAgent(text)) return null
        ZhuliAgentRuntime.init(context)
        val outcome = runCatching { ZhuliAgentRuntime.start(text, origin = "chat") }
            .onFailure { Log.w("zhuli.chat", "agent start failed", it) }
            .getOrNull() ?: return null
        return toReply(outcome)
    }

    private fun toReply(outcome: RunOutcome): Reply? = when (outcome) {
        is RunOutcome.Completed -> {
            val answer = outcome.task.result.ifBlank { "做完了，但模型没给出文字说明。" }
            Reply(answer, outcome.task.id)
        }

        is RunOutcome.Suspended -> Reply(
            buildString {
                appendLine("这件事需要你确认一下：")
                appendLine("· 任务号：${outcome.task.id.take(8)}")
                appendLine("· 操作：${outcome.call.name}（${outcome.risk.label}）")
                if (outcome.call.arguments.isNotBlank()) appendLine("· 参数：${outcome.call.arguments.take(160)}")
                append("回「允许」执行，回「拒绝」跳过；有多件事时请带上任务号，如「允许 ${outcome.task.id.take(8)}」。")
            },
            outcome.task.id,
            awaitingApproval = true,
        )

        is RunOutcome.Paused -> Reply(
            "这一步先停在这里（${outcome.reason}）。你可以到「任务」页点继续。",
            outcome.task.id,
        )

        is RunOutcome.AlreadyActive -> Reply(
            "同一件事正在处理（任务 ${outcome.task.id.take(8)}），我没有重复开一遍。",
            outcome.task.id,
        )

        // 失败/取消不占用这次回答：让普通聊天照常回应，细节留在任务页
        is RunOutcome.Failed -> null
        is RunOutcome.Cancelled -> null
    }
}
