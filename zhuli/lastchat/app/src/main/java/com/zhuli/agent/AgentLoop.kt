package com.zhuli.agent

import com.zhuli.agent.ModelError.Kind
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import java.util.UUID

/** 一轮运行的结果。注意 [Suspended] 不是失败，是「等人」，状态已经落库。 */
sealed class RunOutcome {
    data class Completed(val task: AgentTask) : RunOutcome()
    data class Suspended(val task: AgentTask, val call: ToolCall, val risk: Risk, val reason: String) : RunOutcome()
    /** 到了步数/时间上限，任务还在，可以继续 —— 不是失败。 */
    data class Paused(val task: AgentTask, val reason: String) : RunOutcome()
    data class Failed(val task: AgentTask, val error: String) : RunOutcome()
    data class Cancelled(val task: AgentTask) : RunOutcome()
    data class AlreadyActive(val task: AgentTask) : RunOutcome()
}

/**
 * Agent 主循环。批次 B 把它从「能跑通」加固到「并发、取消、审批恢复都可靠」。
 *
 * 批次 B 的四条硬约束：
 * 1. 状态迁移必须过 [TaskStatus.canMoveTo] 和存储层 CAS，非法跳转不生效；
 * 2. 每次进入循环、每次模型调用前、每次工具执行前后都检查取消/暂停；
 * 3. 审批绑定具体调用指纹并带过期时间，重复批准、旧批准、串任务批准一律拒绝；
 * 4. 上下文按完整的 assistant + tool 组裁剪，不把 tool call 和 tool result 拆开。
 */
class AgentLoop(
    private val router: ModelRouter,
    private val tools: ToolRegistry,
    private val store: AgentStore,
    private val policy: PolicyEngine,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val maxSteps: Int = 8,
    private val hardStepCap: Int = 24,
    private val maxWallClockMs: Long = 4 * 60_000L,
    private val toolTimeoutMs: Long = 25_000L,
    private val onEvent: (String) -> Unit = {},
) {

    companion object {
        const val DEDUPE_WINDOW_MS = 30L * 60 * 1000

        /** 一轮最多执行 3 个调用；剩余的会记录成"未执行"，不会静默丢弃。 */
        private const val MAX_CALLS_PER_TURN = 3

        /** 上下文最多保留 12 个完整消息组（含 assistant 与它的全部 tool 结果）。 */
        internal const val MAX_CONTEXT_GROUPS = 12

        private val DEGRADABLE = setOf(Kind.NETWORK, Kind.TRANSIENT, Kind.FATAL, Kind.UNAVAILABLE)
    }

    /** 新任务入口。[sessionId] 决定并发隔离域：同一会话串行，不同会话并行。 */
    suspend fun start(
        goal: String,
        origin: String = "chat",
        dedupeKey: String = "",
        localOnly: Boolean = false,
        sessionId: String = "",
    ): RunOutcome {
        val now = clock()
        val key = dedupeKey.ifBlank { goal.trim() }
        if (key.isNotBlank()) {
            val existing = store.findActiveByDedupeKey(key, now - DEDUPE_WINDOW_MS)
            if (existing != null) {
                onEvent("同目标任务已在执行，复用 ${existing.id.take(8)}")
                return RunOutcome.AlreadyActive(existing)
            }
        }
        val task = store.create(
            AgentTask(
                id = UUID.randomUUID().toString(),
                goal = goal.trim(),
                status = TaskStatus.PENDING,
                createdAt = now,
                updatedAt = now,
                dedupeKey = key,
                origin = origin,
                localOnly = localOnly,
                sessionId = sessionId,
                phase = "CREATED",
            )
        )
        store.appendStep(AgentStep(taskId = task.id, seq = 0, kind = StepKind.GOAL, content = goal, at = now))
        policy.beginRound(now)
        return run(task.id)
    }

    /** 继续一个已存在的任务（PENDING / PAUSED / RUNNING）。并发与取消在这里统一入口。 */
    suspend fun run(taskId: String): RunOutcome {
        val seed = store.get(taskId) ?: return RunOutcome.Failed(
            AgentTask(id = taskId, goal = "", status = TaskStatus.FAILED), "任务不存在"
        )
        // 必须在**子 Job**里跑：cancel() 只能取消 Agent 自己，绝不能把调用方（UI 协程/测试）
        // 一起取消掉。否则一次点"取消"会让整个聊天页协程挂掉。
        return coroutineScope {
            val guard = async { AgentCoordinator.withLocks(seed.sessionId, taskId) { runInternal(taskId) } }
            AgentCoordinator.register(taskId, guard)
            try {
                guard.await()
            } catch (e: CancellationException) {
                // 用户取消会让工具协程抛 CancellationException；不能把它当失败，也不能吞成成功。
                markCancelled(taskId, "用户取消（执行中的工具已被要求停止）")
                RunOutcome.Cancelled(store.get(taskId) ?: seed)
            } finally {
                if (store.get(taskId)?.status?.terminal == true) AgentCoordinator.finish(taskId)
            }
        }
    }

    private suspend fun runInternal(taskId: String): RunOutcome {
        var task = store.get(taskId) ?: return RunOutcome.Failed(
            AgentTask(id = taskId, goal = "", status = TaskStatus.FAILED), "任务不存在"
        )
        if (task.status == TaskStatus.CANCELLED) return RunOutcome.Cancelled(task)
        if (task.status.terminal) return RunOutcome.Completed(task)
        if (AgentCoordinator.isCancelRequested(taskId)) return cancelled(taskId)

        if (task.status == TaskStatus.WAITING_APPROVAL) {
            val approval = task.pendingApproval
            if (approval != null && !approval.valid(clock())) {
                store.appendStep(note(taskId, "这条审批已经过期，需要重新确认。"))
                task = updateMeta(task, pendingApproval = null, pendingCall = null, phase = "TOOL_EXEC")
                task = move(task, TaskStatus.RUNNING)
            } else {
                val call = task.pendingCall
                return if (call != null) {
                    RunOutcome.Suspended(task, call, Risk.of(task.pendingRisk), "仍在等待确认")
                } else {
                    // 脏数据：标了等待却没有待确认调用 → 修回 RUNNING 继续
                    task = move(task, TaskStatus.RUNNING)
                    resumeRun(task, taskId)
                }
            }
        }
        // PAUSED 但带着有效审批：恢复时先回到等待确认，绝不能在用户没点批准时执行写操作。
        if (task.status == TaskStatus.PAUSED && task.pendingApproval?.valid(clock()) == true) {
            task = move(task, TaskStatus.WAITING_APPROVAL)
            val call = task.pendingCall!!
            return RunOutcome.Suspended(task, call, Risk.of(task.pendingRisk), "暂停前正在等待确认")
        }

        val started = clock()
        if (task.status != TaskStatus.RUNNING) {
            val recovered = store.recoverRunningExecutions(started, taskId)
            if (recovered > 0) {
                store.appendStep(note(taskId, "发现 $recovered 条上次没跑完的工具执行，已标记为「结果未知」，不会自动重试。"))
            }
        }
        if (!policy.roundActive(started)) policy.beginRound(started)
        task = move(task, TaskStatus.RUNNING)
        return resumeRun(task, taskId, started)
    }

    /** 从 RUNNING 状态进入主循环。单独抽出，供 run 与 approve 共用。 */
    private suspend fun resumeRun(task: AgentTask, taskId: String, started: Long = clock()): RunOutcome {
        var current = task
        while (true) {
            val stopped = checkStop(current, taskId, started)
            if (stopped != null) return stopped

            val steps = store.steps(taskId)
            val stepCount = steps.size
            if (stepCount >= hardStepCap) return fail(current, "步骤数超过硬上限（$hardStepCap），已停止以免空转")
            if (clock() - started > maxWallClockMs) {
                val paused = move(current, TaskStatus.PAUSED)
                store.appendStep(note(taskId, "本次运行超过 ${maxWallClockMs / 1000} 秒，已暂停；可以点「继续」接着跑。"))
                return RunOutcome.Paused(paused, "超时暂停")
            }
            if (stepCount >= maxSteps) {
                val paused = move(current, TaskStatus.PAUSED)
                store.appendStep(note(taskId, "已达单次步数上限（$maxSteps），先停在这里，避免无休止调用模型。"))
                return RunOutcome.Paused(paused, "步数上限")
            }

            val req = ModelRequest(
                messages = buildMessages(current, steps),
                tools = tools.specs(),
                tag = taskId.take(8),
                localOnly = current.localOnly,
            )
            val resp = try {
                current = updateMeta(current, phase = "MODEL_DECIDE")
                callModel(current, steps, req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelError) {
                return fail(current, friendly(e))
            }

            store.appendStep(
                AgentStep(
                    taskId = taskId, seq = store.stepsCount(taskId), kind = StepKind.ASSISTANT,
                    content = resp.text, callsJson = CallCodec.encode(resp.toolCalls),
                    at = clock(), latencyMs = resp.latencyMs, providerId = resp.providerId,
                )
            )
            current = store.get(taskId) ?: current
            val stoppedAfterModel = checkStop(current, taskId, started)
            if (stoppedAfterModel != null) return stoppedAfterModel

            if (!resp.wantsTools) {
                val done = current.copy(
                    status = TaskStatus.DONE,
                    result = resp.text.ifBlank { "（模型没有给出文字答复）" },
                    finishedAt = clock(), updatedAt = clock(),
                    pendingCall = null, pendingApproval = null, pendingRisk = "", phase = "FINISH",
                )
                store.update(done)
                onEvent("任务完成 ${taskId.take(8)}")
                AgentCoordinator.finish(taskId)
                return RunOutcome.Completed(done)
            }

            // 多工具调用：逐个执行，每个调用都检查状态；超出的记为"未执行"而不是静默丢掉。
            val calls = resp.toolCalls
            if (calls.size > MAX_CALLS_PER_TURN) {
                store.appendStep(note(taskId, "模型一次给了 ${calls.size} 个调用，本轮只执行前 $MAX_CALLS_PER_TURN 个，其余记为未执行。"))
            }
            for ((index, call) in calls.withIndex()) {
                val remaining = calls.drop(index + 1)
                if (index >= MAX_CALLS_PER_TURN) {
                    store.appendStep(toolStep(taskId, call, ToolResult.fail("本轮调用数超限，未执行"), 0))
                    continue
                }
                when (val r = processCall(current, call, remaining)) {
                    is CallResult.Suspended -> return r.outcome
                    is CallResult.Cancelled -> return cancelled(taskId)
                    is CallResult.Continue -> Unit
                }
                current = store.get(taskId) ?: current
                val stoppedByCall = checkStop(current, taskId, started)
                if (stoppedByCall != null) return stoppedByCall
            }
        }
    }

    private sealed interface CallResult {
        data class Continue(val task: AgentTask) : CallResult
        data class Suspended(val outcome: RunOutcome.Suspended) : CallResult
        data object Cancelled : CallResult
    }

    /** 处理一次工具调用：校验 → 策略 → （可能挂起）→ 幂等执行 → 结果回灌。 */
    private suspend fun processCall(
        task: AgentTask,
        call: ToolCall,
        remaining: List<ToolCall>,
        approved: Boolean = false,
    ): CallResult {
        val taskId = task.id
        val tool = tools.get(call.name)
        if (tool == null) {
            store.appendStep(toolStep(taskId, call, ToolResult.fail("未知工具：${call.name}"), 0))
            return CallResult.Continue(task)
        }
        // 校验必须在策略与执行之前：参数错的调用不能碰到副作用，也不能"顺便"过权限。
        ToolArguments.validate(tool.spec, call.arguments)?.let { why ->
            store.appendStep(toolStep(taskId, call, ToolResult.fail("参数校验失败：$why"), 0))
            onEvent("工具 ${call.name} 参数校验失败：$why")
            return CallResult.Continue(task)
        }
        val now = clock()
        val verdict = policy.decide(tool, call.arguments, now)
        val fingerprint = TaskContextCodec.fingerprint(taskId, call)
        when (verdict.decision) {
            Decision.DENY -> {
                store.appendStep(note(taskId, "拒绝执行 ${call.name}：${verdict.reason}"))
                store.appendStep(toolStep(taskId, call, ToolResult.fail("被策略拒绝：${verdict.reason}"), 0))
            }

            Decision.ASK -> {
                if (!approved) {
                    val approval = TaskContextCodec.newApproval(taskId, task.sessionId, call, verdict.risk, now, remaining)
                    val waiting = updateMeta(
                        task.copy(status = TaskStatus.WAITING_APPROVAL, pendingCall = call, pendingRisk = verdict.risk.name),
                        pendingApproval = approval, phase = "AWAIT_APPROVAL",
                    )
                    // 已执行过的调用结果已经落库；这里只把"还没执行"的留给批准后继续。
                    store.update(waiting)
                    store.appendStep(note(taskId, "等待用户确认：${call.name}（${verdict.risk.label}）"))
                    onEvent("任务 ${taskId.take(8)} 等待确认 ${call.name}")
                    return CallResult.Suspended(RunOutcome.Suspended(waiting, call, verdict.risk, verdict.reason))
                }
                // approved=true 但策略仍要求逐次确认：必须有本次指纹的授权，否则不执行。
                if (!policy.isAuthorized(fingerprint, now)) {
                    store.appendStep(toolStep(taskId, call, ToolResult.fail("审批已失效，未执行"), 0))
                    return CallResult.Continue(task)
                }
                val result = executeIdempotent(task, tool, call)
                store.appendStep(toolStep(taskId, call, result, 0))
            }

            Decision.ALLOW -> {
                // 纵深防御：可能被"同类免问"放行的 WRITE_LOCAL 之外的高风险调用，仍必须有逐次授权。
                if (tool.risk.rank >= Risk.WRITE_EXTERNAL.rank && !policy.isAuthorized(fingerprint, now)) {
                    store.appendStep(note(taskId, "拒绝执行 ${call.name}：高风险操作缺少对应这次参数的授权"))
                    store.appendStep(toolStep(taskId, call, ToolResult.fail("缺少明确授权，未执行"), 0))
                } else {
                    val result = executeIdempotent(task, tool, call)
                    store.appendStep(toolStep(taskId, call, result, 0))
                }
            }
        }
        if (AgentCoordinator.isCancelRequested(taskId)) return CallResult.Cancelled
        return CallResult.Continue(store.get(taskId) ?: task)
    }

    /**
     * 用户对挂起调用的裁决。[fingerprint] 可选：UI 若传了，与库中不一致就拒绝（防审批串任务）。
     */
    suspend fun approve(taskId: String, allow: Boolean, fingerprint: String? = null): RunOutcome {
        val seed = store.get(taskId) ?: return RunOutcome.Failed(
            AgentTask(id = taskId, goal = "", status = TaskStatus.FAILED), "任务不存在"
        )
        return try {
            AgentCoordinator.withLocks(seed.sessionId, taskId) { approveInternal(taskId, allow, fingerprint) }
        } catch (e: CancellationException) {
            markCancelled(taskId, "审批过程中任务被取消")
            RunOutcome.Cancelled(store.get(taskId) ?: seed)
        }
    }

    private suspend fun approveInternal(taskId: String, allow: Boolean, fingerprint: String?): RunOutcome {
        var task = store.get(taskId) ?: return RunOutcome.Failed(
            AgentTask(id = taskId, goal = "", status = TaskStatus.FAILED), "任务不存在"
        )
        if (task.status != TaskStatus.WAITING_APPROVAL) {
            return when {
                task.status.terminal -> RunOutcome.Completed(task)
                // 重复点「允许」：任务已在跑，绝不重新执行被批准的工具。
                else -> {
                    store.appendStep(note(taskId, "重复审批已忽略：任务当前状态为 ${task.status}"))
                    RunOutcome.AlreadyActive(task)
                }
            }
        }
        val now = clock()
        val approval = task.pendingApproval ?: task.pendingCall?.let {
            // 兼容测试/旧数据里只有 pendingCall 的记录：按当前时间生成一个短时效的审批。
            TaskContextCodec.newApproval(
                taskId, task.sessionId, it,
                Risk.of(task.pendingRisk.ifBlank { Risk.WRITE_LOCAL.name }), now,
            )
        } ?: return runInternal(taskId)

        if (approval.taskId != taskId) {
            return fail(task, "审批与任务不匹配，已拒绝（可能串任务）")
        }
        if (fingerprint != null && fingerprint != approval.fingerprint) {
            return fail(task, "审批对应的操作参数已变化，请重新确认")
        }
        if (!approval.valid(now)) {
            store.appendStep(note(taskId, "审批已过期，未执行任何操作；任务回到执行队列等待重新确认。"))
            task = updateMeta(task, pendingApproval = null, pendingCall = null, phase = "TOOL_EXEC")
            task = move(task, TaskStatus.RUNNING)
            return runInternal(taskId)
        }
        val call = approval.call
        if (TaskContextCodec.fingerprint(taskId, call) != approval.fingerprint) {
            return fail(task, "审批上下文被篡改，已拒绝执行")
        }
        val tool = tools.get(call.name)
        if (tool == null) {
            store.appendStep(toolStep(taskId, call, ToolResult.fail("未知工具：${call.name}"), 0))
            task = clearApproval(task, now)
            return runInternal(taskId)
        }
        if (!allow) {
            store.appendStep(toolStep(taskId, call, ToolResult.fail("用户拒绝执行"), 0))
            task = clearApproval(task, now)
            task = move(task, TaskStatus.RUNNING)
            // 拒绝了也要让模型知道，它才能换方案或收尾
            return runInternal(taskId)
        }

        val invalid = ToolArguments.validate(tool.spec, call.arguments)
        if (invalid != null) {
            store.appendStep(toolStep(taskId, call, ToolResult.fail("参数校验失败：$invalid"), 0))
            task = clearApproval(task, now)
            task = move(task, TaskStatus.RUNNING)
            return runInternal(taskId)
        }

        if (!policy.roundActive(now)) policy.beginRound(now)
        // 只授权这一条具体指纹；同类授权仅限可撤销的本地写操作。
        policy.authorize(approval.fingerprint, now)
        if (tool.risk.rank <= Risk.WRITE_LOCAL.rank) policy.grant(tool.op)

        val result = processCall(task, call, emptyList(), approved = true)
        task = clearApproval(store.get(taskId) ?: task, now)
        if (task.status.terminal) return RunOutcome.Completed(task)
        task = move(task, TaskStatus.RUNNING)

        // 审批点之后还有调用：按原顺序继续执行，绝不因为一次确认把它们丢掉。
        val remaining = CallCodec.decode(approval.remainingCallsJson)
        for (next in remaining) {
            when (val r = processCall(task, next, emptyList())) {
                is CallResult.Suspended -> return r.outcome
                is CallResult.Cancelled -> return cancelled(taskId)
                is CallResult.Continue -> Unit
            }
            task = store.get(taskId) ?: task
        }
        return runInternal(taskId)
    }

    private fun clearApproval(task: AgentTask, now: Long): AgentTask = updateMeta(
        task.copy(pendingCall = null, pendingApproval = null, pendingRisk = "", updatedAt = now),
        pendingApproval = null, phase = "TOOL_EXEC",
    )

    /** 用户取消：区分"正在取消"与"已取消"，并立协作标志让执行中的工具停下。 */
    fun cancel(taskId: String, reason: String = "用户取消了任务"): AgentTask? {
        val t = store.get(taskId) ?: return null
        if (t.status.terminal) return t
        if (t.status == TaskStatus.CANCELLING) return t
        val now = clock()
        AgentCoordinator.requestCancel(taskId)
        val target = if (t.status == TaskStatus.RUNNING) TaskStatus.CANCELLING else TaskStatus.CANCELLED
        val changed = store.transition(taskId, t.status, target, now)
        val base = if (changed) store.get(taskId) ?: t else t
        val updated = updateMeta(
            base.copy(
                status = target,
                cancelReason = reason,
                finishedAt = if (target == TaskStatus.CANCELLED) now else 0L,
                updatedAt = now,
                pendingCall = null,
            ),
            pendingApproval = null, phase = "FINISH", cancelReason = reason,
        )
        store.update(updated)
        store.appendStep(note(taskId, reason))
        if (target == TaskStatus.CANCELLED) AgentCoordinator.finish(taskId)
        return updated
    }

    fun pause(taskId: String): AgentTask? {
        val t = store.get(taskId) ?: return null
        if (t.status.terminal || t.status == TaskStatus.CANCELLING) return t
        val moved = move(t, TaskStatus.PAUSED)
        if (moved.status == TaskStatus.PAUSED) store.appendStep(note(taskId, "用户暂停了任务"))
        return moved
    }

    /** 检查取消/暂停，必须在模型调用与工具执行的安全点上调用。 */
    private fun checkStop(task: AgentTask, taskId: String, started: Long): RunOutcome? {
        if (AgentCoordinator.isCancelRequested(taskId) || task.status == TaskStatus.CANCELLING) {
            return cancelled(taskId)
        }
        if (task.status == TaskStatus.CANCELLED) return RunOutcome.Cancelled(task)
        if (task.status == TaskStatus.PAUSED) return RunOutcome.Paused(task, "任务已暂停")
        if (task.status == TaskStatus.DONE) return RunOutcome.Completed(task)
        if (task.status == TaskStatus.FAILED) return RunOutcome.Failed(task, task.error)
        if (clock() - started > maxWallClockMs) return RunOutcome.Paused(move(task, TaskStatus.PAUSED), "超时暂停")
        return null
    }

    private fun cancelled(taskId: String): RunOutcome {
        val t = store.get(taskId) ?: return RunOutcome.Cancelled(AgentTask(taskId, "", status = TaskStatus.CANCELLED))
        if (t.status == TaskStatus.CANCELLING) {
            val now = clock()
            if (store.transition(taskId, TaskStatus.CANCELLING, TaskStatus.CANCELLED, now)) {
                store.appendStep(note(taskId, "取消已完成，后续工具与排队中的调用都不会再执行。"))
            }
        }
        val done = store.get(taskId) ?: t
        AgentCoordinator.finish(taskId)
        return RunOutcome.Cancelled(done)
    }

    private fun markCancelled(taskId: String, reason: String): AgentTask? {
        val t = store.get(taskId) ?: return null
        if (t.status.terminal) return t
        val now = clock()
        val target = if (t.status == TaskStatus.CANCELLING) TaskStatus.CANCELLED else t.status
        val changed = if (target == TaskStatus.CANCELLED && t.status == TaskStatus.CANCELLING) {
            store.transition(taskId, t.status, target, now)
        } else {
            store.transition(taskId, t.status, TaskStatus.CANCELLING, now) &&
                store.transition(taskId, TaskStatus.CANCELLING, TaskStatus.CANCELLED, now)
        }
        val base = if (changed) store.get(taskId) ?: t else t
        val out = updateMeta(
            base.copy(status = TaskStatus.CANCELLED, cancelReason = reason, finishedAt = now, updatedAt = now),
            pendingApproval = null, phase = "FINISH", cancelReason = reason,
        )
        store.update(out)
        store.appendStep(note(taskId, reason))
        AgentCoordinator.finish(taskId)
        return out
    }

    // ---------------- 内部工具 ----------------

    private suspend fun callModel(task: AgentTask, steps: List<AgentStep>, req: ModelRequest): ModelResponse {
        val wanted = req.tools.isNotEmpty()
        return try {
            router.complete(req, needTools = wanted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            if (!wanted || e.kind !in DEGRADABLE) throw e
            store.appendStep(note(task.id, degradationNote(steps, e)))
            onEvent("任务 ${task.id.take(8)} 降级为本地纯文本（${e.kind}）")
            val degradedMessages = buildMessages(task, store.steps(task.id))
            // 批次 A 边界：降级必须保留 localOnly，绝不因为云端失败而把内容改发云端。
            router.complete(
                req.copy(messages = degradedMessages, tools = emptyList(), localOnly = true),
                needTools = false,
                localOnly = true,
            )
        }
    }

    private fun degradationNote(steps: List<AgentStep>, e: ModelError): String = buildString {
        append("降级说明：云端模型不可用（${e.kind}），本次改为本机纯文本回答：不能查库、不能建提醒、不能执行任何操作。")
        val tools = steps.filter { it.kind == StepKind.TOOL }
        if (tools.isNotEmpty()) {
            append("\n已经真实执行过的步骤：")
            append(tools.joinToString("；") { s ->
                "${s.toolName}（${if (s.ok) "成功：${s.content.take(40)}" else "失败：${s.content.take(30)}"}）"
            })
        }
        val stopped = steps.lastOrNull { it.kind == StepKind.ASSISTANT && it.content.isNotBlank() }
        if (stopped != null) append("\n停在这一步之前最后说的话：${stopped.content.take(60)}")
        append("\n回答要求：只根据上面已经拿到的结果说话；没做的步骤明确说没做，不要编造执行记录。")
    }

    private suspend fun executeIdempotent(task: AgentTask, tool: RegisteredTool, call: ToolCall): ToolResult {
        val key = idemKey(task.id, call)
        val now = clock()
        val existing = store.findToolExecution(key)
        when (existing?.state) {
            ToolExecState.SUCCEEDED -> {
                store.appendStep(note(task.id, "工具 ${call.name} 之前已成功执行过，这次直接复用结果，没有重复产生副作用。"))
                return ToolResult.ok(existing!!.result.ifBlank { "（已执行，无输出）" })
            }
            ToolExecState.RUNNING, ToolExecState.PENDING -> {
                store.updateToolExecution(existing!!.copy(state = ToolExecState.UNKNOWN, updatedAt = now))
                store.appendStep(note(task.id, "工具 ${call.name} 上次执行结果未知（进程中断），为避免重复副作用没有重试。"))
                return ToolResult.fail("上次执行结果未知，已停止以免重复操作；如果确实需要，请让用户明确再说一次。")
            }
            ToolExecState.UNKNOWN -> {
                store.appendStep(note(task.id, "工具 ${call.name} 上次结果仍是未知，仍不重试。"))
                return ToolResult.fail("上次执行结果未知，未重复执行。请让用户确认后手动触发。")
            }
            ToolExecState.FAILED -> Unit
            null -> Unit
        }

        val retrying = existing?.state == ToolExecState.FAILED
        val exec = ToolExecution(
            executionId = if (retrying) existing!!.executionId else UUID.randomUUID().toString(),
            taskId = task.id, tool = call.name, args = call.arguments.take(2_000),
            idemKey = key, state = ToolExecState.RUNNING,
            attempts = (existing?.attempts ?: 0) + 1,
            createdAt = existing?.createdAt ?: now, updatedAt = now,
        )
        if (retrying) store.updateToolExecution(exec)
        val stored = store.beginToolExecution(exec)
        if (stored.executionId != exec.executionId) {
            if (stored.state == ToolExecState.SUCCEEDED) return ToolResult.ok(stored.result.ifBlank { "（已执行）" })
            return ToolResult.fail("同一操作已在执行中，未重复执行。")
        }
        // 执行前最后检查一次取消：排队中的工具不能因为用户在执行间隙点了取消而启动。
        if (AgentCoordinator.isCancelRequested(task.id) || store.get(task.id)?.status == TaskStatus.CANCELLING) {
            store.updateToolExecution(exec.copy(state = ToolExecState.UNKNOWN, error = "已取消，结果未知", updatedAt = clock()))
            return ToolResult.fail("任务已取消，工具未执行。")
        }

        val attempt = runToolOnce(tool, call)
        val finished = when {
            attempt.timedOut -> exec.copy(state = ToolExecState.UNKNOWN, error = "超时，结果未知", updatedAt = clock())
            attempt.threw -> exec.copy(state = ToolExecState.UNKNOWN, error = "工具抛异常，副作用是否已发生未知：${attempt.result.error}", updatedAt = clock())
            attempt.result.ok -> exec.copy(state = ToolExecState.SUCCEEDED, result = attempt.result.text, updatedAt = clock())
            else -> exec.copy(state = ToolExecState.FAILED, error = attempt.result.error, updatedAt = clock())
        }
        store.updateToolExecution(finished)
        if (attempt.timedOut || attempt.threw) {
            val why = if (attempt.timedOut) "超时" else "抛异常"
            store.appendStep(note(task.id, "工具 ${call.name} $why：结果未知，不自动重试（可能已经产生副作用）。"))
            return ToolResult.fail("工具 $why（${attempt.result.error}），结果未知，未重复执行。若确实需要请让用户明确再说一次。")
        }
        return attempt.result
    }

    private class Attempt(val result: ToolResult, val timedOut: Boolean, val threw: Boolean = false)

    private suspend fun runToolOnce(tool: RegisteredTool, call: ToolCall, timeoutMs: Long = toolTimeoutMs): Attempt {
        val r = withTimeoutOrNull(timeoutMs) {
            try {
                Attempt(tool.execute(call.arguments), timedOut = false, threw = false)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Attempt(
                    ToolResult.fail("${t.javaClass.simpleName}:${t.message?.take(80)}"),
                    timedOut = false, threw = true,
                )
            }
        }
        return if (r == null) Attempt(ToolResult.fail("工具超时（${timeoutMs / 1000} 秒）"), timedOut = true) else r
    }

    private fun idemKey(taskId: String, call: ToolCall): String =
        taskId + "|" + call.name + "|" + TaskContextCodec.normalizeArgs(call.arguments)

    private fun toolStep(taskId: String, call: ToolCall, result: ToolResult, latency: Long) = AgentStep(
        taskId = taskId, seq = store.stepsCount(taskId), kind = StepKind.TOOL,
        content = if (result.ok) result.text else (result.error.ifBlank { result.text }),
        toolName = call.name, toolArgs = call.arguments, toolCallId = call.id,
        ok = result.ok, at = clock(), latencyMs = latency,
    )

    private fun note(taskId: String, text: String) = AgentStep(
        taskId = taskId, seq = store.stepsCount(taskId), kind = StepKind.NOTE, content = text, at = clock(),
    )

    private fun fail(task: AgentTask, message: String): RunOutcome {
        val failed = task.copy(
            status = TaskStatus.FAILED, error = message, finishedAt = clock(), updatedAt = clock(),
            pendingCall = null, pendingApproval = null, pendingRisk = "", phase = "FINISH",
        )
        store.update(failed)
        store.appendStep(
            AgentStep(
                taskId = task.id, seq = store.stepsCount(task.id), kind = StepKind.ERROR,
                content = message, ok = false, at = clock(),
            )
        )
        AgentCoordinator.finish(task.id)
        onEvent("任务失败 ${task.id.take(8)}：$message")
        return RunOutcome.Failed(failed, message)
    }

    /** 状态迁移：先查表，再用存储层 CAS 落库；被抢占则以库里的最新状态为准。 */
    private fun move(task: AgentTask, next: TaskStatus): AgentTask {
        if (task.status == next) return task
        if (!task.status.canMoveTo(next)) {
            store.appendStep(note(task.id, "非法状态跳转 ${task.status}→$next 已拒绝"))
            return store.get(task.id) ?: task
        }
        val ok = store.transition(task.id, task.status, next, clock())
        if (!ok) onEvent("任务 ${task.id.take(8)} 状态被其他路径更新，放弃本次 ${task.status}→$next")
        return store.get(task.id) ?: task
    }

    /** 只改信封字段（session/phase/审批/取消原因），不动状态列的写入方式。 */
    private fun updateMeta(
        task: AgentTask,
        pendingApproval: PendingApproval? = task.pendingApproval,
        phase: String = task.phase,
        cancelReason: String = task.cancelReason,
        pendingCall: ToolCall? = task.pendingCall,
    ): AgentTask = task.copy(
        pendingApproval = pendingApproval,
        pendingCall = pendingCall,
        phase = phase,
        cancelReason = cancelReason,
    ).also { store.update(it) }

    private fun friendly(e: ModelError): String = when (e.kind) {
        Kind.NETWORK -> "网络不通，任务已停在当前步骤，恢复网络后可以继续。"
        Kind.TRANSIENT -> "模型服务临时不可用（${e.message}），可以稍后继续。"
        Kind.FATAL -> "模型配置有问题：${e.message}"
        Kind.BLOCKED -> "内容被隐私闸门拦下：${e.message}（不会为了跑通而换模型绕开）"
        Kind.UNAVAILABLE -> "没有可用模型：${e.message}"
    }

    /**
     * 从步骤重建上下文：按**完整消息组**裁剪。
     *
     * 一个组 = 一条 assistant（带全部 tool_calls）+ 它对应的所有 TOOL 结果。
     * 若某个调用没有结果（审批中断 / 超限），补一条合成的 tool 消息，保证 OpenAI 兼容端点不 400，
     * 也让模型明确知道"这一步没执行"，而不是以为工具返回了空。
     */
    internal fun buildMessages(task: AgentTask, steps: List<AgentStep>): List<AgentMessage> {
        val out = ArrayList<AgentMessage>()
        out.add(AgentMessage.system(systemPrompt()))
        out.add(AgentMessage.user(task.goal))
        val notes = ArrayList<String>()
        val groups = groupSteps(steps)
        val window = groups.takeLast(MAX_CONTEXT_GROUPS)
        for (group in window) {
            when (group) {
                is StepGroup.Note -> notes.add(group.text)
                is StepGroup.Exchange -> {
                    notes.addAll(group.notes)
                    val calls = CallCodec.decode(group.assistant.callsJson)
                    out.add(AgentMessage.assistant(group.assistant.content, calls))
                    val answered = group.results.map { it.toolCallId }.toSet()
                    for (r in group.results) {
                        out.add(AgentMessage.tool(r.toolCallId, r.content.ifBlank { if (r.ok) "（成功，无输出）" else "（失败）" }))
                    }
                    for (c in calls) {
                        if (c.id !in answered) {
                            out.add(AgentMessage.tool(c.id, "（未执行：等待审批 / 超出本轮上限 / 任务已停止）"))
                        }
                    }
                }
            }
        }
        if (notes.isNotEmpty()) {
            out.add(AgentMessage.system("过程说明（不是用户说的话）：\n" + notes.takeLast(6).joinToString("\n") { "· $it" }))
        }
        return out
    }

    private sealed interface StepGroup {
        data class Note(val text: String) : StepGroup
        data class Exchange(
            val assistant: AgentStep,
            val results: List<AgentStep>,
            /** assistant 与 tool 结果之间的 NOTE（如"等待用户确认"），不能让它拆散配对。 */
            val notes: List<String> = emptyList(),
        ) : StepGroup
    }

    private fun groupSteps(steps: List<AgentStep>): List<StepGroup> {
        val out = ArrayList<StepGroup>()
        var i = 0
        while (i < steps.size) {
            val s = steps[i]
            when (s.kind) {
                StepKind.GOAL -> i++
                StepKind.NOTE, StepKind.ERROR -> { out.add(StepGroup.Note(s.content)); i++ }
                StepKind.ASSISTANT -> {
                    val results = ArrayList<AgentStep>()
                    val between = ArrayList<String>()
                    var j = i + 1
                    // NOTE/ERROR 可能夹在 assistant 与 tool 结果之间（审批提示、降级说明）。
                    // 不能因为一条 NOTE 就把 tool 结果拆成"孤儿"，否则模型会看到"未执行"的假结果。
                    while (j < steps.size) {
                        when (steps[j].kind) {
                            StepKind.TOOL -> { results.add(steps[j]); j++ }
                            StepKind.NOTE, StepKind.ERROR -> { between.add(steps[j].content); j++ }
                            else -> break
                        }
                    }
                    out.add(StepGroup.Exchange(s, results, between))
                    i = j
                }
                StepKind.TOOL -> {
                    // 没有配对 assistant 的孤儿结果：作为单独的一组保留，不丢内容。
                    out.add(StepGroup.Exchange(
                        AgentStep(taskId = s.taskId, seq = s.seq - 1, kind = StepKind.ASSISTANT, content = "", at = s.at),
                        listOf(s),
                    ))
                    i++
                }
            }
        }
        return out
    }

    private fun systemPrompt(): String = buildString {
        appendLine("你是 zhuli，一个跑在用户手机上的个人助理。你要真正把事办完，而不是描述你会怎么办。")
        appendLine("规则：")
        appendLine("1. 需要用户手机里的信息（消息、通知、快递、账本、待办、记忆）时，调用工具查，不要凭空回答。")
        appendLine("2. 一次只做一步；拿到工具结果后判断是否已经能回答，能就收尾。")
        appendLine("3. 不要编造没查到的事实。查不到就说没查到，并说明查了什么。")
        appendLine("4. 写操作（记备忘、建提醒）会先让用户确认，你正常调用即可，不要绕过。")
        appendLine("5. 最终答复要短，直接给结论；不要复述工具返回的原文。")
        appendLine("6. 同一件事不要重复执行；如果不确定上次是否做成，先说明不确定，不要直接再来一次。")
        if (tools.all().isNotEmpty()) {
            appendLine("可用工具：")
            for (t in tools.all()) appendLine("- ${t.spec.name}：${t.spec.description}")
        }
    }
}
