package com.zhuli.agent

import com.zhuli.agent.ModelError.Kind
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
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
 * Agent 主循环。
 *
 * 一次「用户输入 → 完成任务」的真实流程：
 *   目标 → 重建上下文 → 模型决策（可能带工具调用）→ 策略判定 → 执行 → 结果回灌 → 再决策 → 直到给出答复。
 *
 * 明确不支持的事（避免把「套壳 API」说成 Agent）：
 * - 不做无限自主循环：步数、墙钟、工具重试都有上限；
 * - 不在没有确认的情况下做写操作：策略说 ASK 就停下并把状态存进库；
 * - 不假装记得：上下文只来自本任务的步骤 + 调用方给的 system prompt；
 * - 不重复执行有副作用的工具：执行前落意图，崩溃后记 UNKNOWN 而不是重跑
 *   （见 [ToolExecState]）。
 *
 * 所有依赖（模型、工具、存储、策略、时钟）都是注入的，所以整个循环能在 JVM 单测里用假 provider 跑。
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
        /** 同目标在该窗口内不重复开跑（真机事故：同一句叮嘱排出 7 个闹钟）。 */
        const val DEDUPE_WINDOW_MS = 30L * 60 * 1000

        private const val MAX_CALLS_PER_TURN = 3

        /**
         * 允许降级到本地的错误类型。
         *
         * 云端网络失败的真实分类不是只有 UNAVAILABLE：断网是 NETWORK、429/5xx 是 TRANSIENT、
         * Key 过期是 FATAL。旧实现只捕 UNAVAILABLE，于是真机上「断网 → 直接失败」，
         * 用户看到的是「没有可用模型」而不是「先用本机答一句」。
         *
         * BLOCKED 故意不在列表里：那是隐私闸门的结论，换后端（哪怕换成本地）
         * 都不该由循环自己决定绕过，应该如实告诉用户。
         */
        private val DEGRADABLE = setOf(Kind.NETWORK, Kind.TRANSIENT, Kind.FATAL, Kind.UNAVAILABLE)
    }

    /**
     * 新任务入口。[dedupeKey] 由调用方给（通常用目标文本归一化），同键活跃任务直接复用。
     * [localOnly] 来自用户明确要求或隐私策略，落库后整个任务生命周期都遵守。
     */
    suspend fun start(goal: String, origin: String = "chat", dedupeKey: String = "", localOnly: Boolean = false): RunOutcome {
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
            )
        )
        store.appendStep(AgentStep(taskId = task.id, seq = 0, kind = StepKind.GOAL, content = goal, at = now))
        policy.beginRound(now)
        return run(task.id)
    }

    /** 继续一个已存在的任务（PENDING / PAUSED / RUNNING）。 */
    suspend fun run(taskId: String): RunOutcome {
        var task = store.get(taskId) ?: return RunOutcome.Failed(
            AgentTask(id = taskId, goal = "", status = TaskStatus.FAILED),
            "任务不存在"
        )
        if (task.status.terminal) return RunOutcome.Completed(task)
        if (task.status == TaskStatus.WAITING_APPROVAL) {
            val call = task.pendingCall
            return if (call != null) {
                RunOutcome.Suspended(task, call, Risk.of(task.pendingRisk), "仍在等待确认")
            } else {
                // 脏数据：标了等待却没有待确认调用 → 修回 RUNNING 继续
                task = move(task, TaskStatus.RUNNING)
                run(task.id)
            }
        }

        val started = clock()
        // 从「非运行中」进入 = 上次进程可能死在这里：只把**本任务**遗留的执行中/待执行记录
        // 判成 UNKNOWN，避免恢复后把已产生副作用的操作再做一遍。
        // B 修复：必须按 taskId 隔离 —— 全局 recover 会把另一个仍在跑的任务的记录也误判成 UNKNOWN。
        // C 修复：全局 recover 只在进程启动时做一次（ZhuliAgentRuntime.init），普通任务启动
        // 或审批恢复绝不在这里无条件处理所有任务。
        if (task.status != TaskStatus.RUNNING) {
            val recovered = store.recoverRunningExecutions(started, taskId)
            if (recovered > 0) {
                store.appendStep(note(taskId, "发现 $recovered 条上次没跑完的工具执行，已标记为「结果未知」，不会自动重试。"))
            }
        }
        // 审批后 approve() 已经开了轮次并授了权：这里不能再 beginRound，
        // 否则会把刚给的授权清掉，用户点完「允许」又被问一遍（真机不可接受的体验）。
        if (!policy.roundActive(started)) policy.beginRound(started)
        task = move(task, TaskStatus.RUNNING)

        while (true) {
            val steps = store.steps(taskId)
            val stepCount = steps.size

            if (stepCount >= hardStepCap) {
                return fail(task, "步骤数超过硬上限（$hardStepCap），已停止以免空转")
            }
            if (clock() - started > maxWallClockMs) {
                val paused = move(task, TaskStatus.PAUSED)
                store.appendStep(
                    note(taskId, "本次运行超过 ${maxWallClockMs / 1000} 秒，已暂停；可以点「继续」接着跑。")
                )
                return RunOutcome.Paused(paused, "超时暂停")
            }
            if (stepCount >= maxSteps) {
                val paused = move(task, TaskStatus.PAUSED)
                store.appendStep(note(taskId, "已达单次步数上限（$maxSteps），先停在这里，避免无休止调用模型。"))
                return RunOutcome.Paused(paused, "步数上限")
            }

            val messages = buildMessages(task, steps)
            val req = ModelRequest(
                messages = messages,
                tools = tools.specs(),
                tag = taskId.take(8),
                localOnly = task.localOnly,
            )
            val resp = try {
                callModel(task, steps, req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelError) {
                return fail(task, friendly(e))
            }

            store.appendStep(
                AgentStep(
                    taskId = taskId,
                    seq = store.stepsCount(taskId),
                    kind = StepKind.ASSISTANT,
                    content = resp.text,
                    callsJson = CallCodec.encode(resp.toolCalls),
                    at = clock(),
                    latencyMs = resp.latencyMs,
                    providerId = resp.providerId,
                )
            )

            if (!resp.wantsTools) {
                val done = task.copy(
                    status = TaskStatus.DONE,
                    result = resp.text.ifBlank { "（模型没有给出文字答复）" },
                    finishedAt = clock(),
                    updatedAt = clock(),
                    pendingCall = null,
                    pendingRisk = "",
                )
                store.update(done)
                onEvent("任务完成 ${taskId.take(8)}")
                return RunOutcome.Completed(done)
            }

            // 一轮最多处理 3 个调用：再多说明模型在乱打，先让它看结果
            val calls = resp.toolCalls.take(MAX_CALLS_PER_TURN)
            if (resp.toolCalls.size > MAX_CALLS_PER_TURN) {
                store.appendStep(note(taskId, "模型一次给了 ${resp.toolCalls.size} 个调用，本轮只执行前 $MAX_CALLS_PER_TURN 个。"))
            }

            for (call in calls) {
                val tool = tools.get(call.name)
                if (tool == null) {
                    store.appendStep(toolStep(taskId, call, ToolResult.fail("未知工具：${call.name}"), 0))
                    continue
                }
                val verdict = policy.decide(tool, call.arguments, clock())
                when (verdict.decision) {
                    Decision.DENY -> {
                        store.appendStep(note(taskId, "拒绝执行 ${call.name}：${verdict.reason}"))
                        store.appendStep(toolStep(taskId, call, ToolResult.fail("被策略拒绝：${verdict.reason}"), 0))
                    }

                    Decision.ASK -> {
                        val waiting = task.copy(
                            status = TaskStatus.WAITING_APPROVAL,
                            pendingCall = call,
                            pendingRisk = verdict.risk.name,
                            updatedAt = clock(),
                        )
                        store.update(waiting)
                        store.appendStep(note(taskId, "等待用户确认：${call.name}（${verdict.risk.label}）"))
                        onEvent("任务 ${taskId.take(8)} 等待确认 ${call.name}")
                        return RunOutcome.Suspended(waiting, call, verdict.risk, verdict.reason)
                    }

                    Decision.ALLOW -> {
                        val result = executeIdempotent(task, tool, call)
                        store.appendStep(toolStep(taskId, call, result, 0))
                    }
                }
            }
            task = store.get(taskId) ?: task
        }
    }

    /**
     * 用户对挂起调用的裁决。
     * @param allow true=执行这一次（并授予同类「本轮允许」，减少骚扰）
     */
    suspend fun approve(taskId: String, allow: Boolean): RunOutcome {
        val task = store.get(taskId) ?: return RunOutcome.Failed(
            AgentTask(id = taskId, goal = "", status = TaskStatus.FAILED), "任务不存在"
        )
        if (task.status != TaskStatus.WAITING_APPROVAL) {
            return if (task.status.terminal) RunOutcome.Completed(task) else run(taskId)
        }
        val call = task.pendingCall ?: return run(taskId)
        val tool = tools.get(call.name)

        if (!allow) {
            store.appendStep(toolStep(taskId, call, ToolResult.fail("用户拒绝执行"), 0))
            store.update(task.copy(status = TaskStatus.RUNNING, pendingCall = null, pendingRisk = "", updatedAt = clock()))
            // 拒绝了也要让模型知道，它才能换方案或收尾
            return run(taskId)
        }

        if (tool == null) {
            store.appendStep(toolStep(taskId, call, ToolResult.fail("未知工具：${call.name}"), 0))
            store.update(task.copy(status = TaskStatus.RUNNING, pendingCall = null, pendingRisk = "", updatedAt = clock()))
            return run(taskId)
        }

        if (!policy.roundActive()) policy.beginRound()
        policy.grant(tool.op)
        val result = executeIdempotent(task, tool, call)
        store.appendStep(toolStep(taskId, call, result, 0))
        store.update(task.copy(status = TaskStatus.RUNNING, pendingCall = null, pendingRisk = "", updatedAt = clock()))
        return run(taskId)
    }

    fun cancel(taskId: String): AgentTask? {
        val t = store.get(taskId) ?: return null
        if (t.status.terminal) return t
        val done = t.copy(status = TaskStatus.CANCELLED, finishedAt = clock(), updatedAt = clock(), pendingCall = null)
        store.update(done)
        store.appendStep(note(taskId, "用户取消了任务"))
        return done
    }

    fun pause(taskId: String): AgentTask? {
        val t = store.get(taskId) ?: return null
        if (t.status.terminal) return t
        val paused = t.copy(status = TaskStatus.PAUSED, updatedAt = clock())
        store.update(paused)
        store.appendStep(note(taskId, "用户暂停了任务"))
        return paused
    }

    // ---------------- 内部 ----------------

    /**
     * 调模型：云端 / 本地由 Router 按能力选。
     *
     * 失败分类决定行为（见 [DEGRADABLE]）：
     * - NETWORK / TRANSIENT / FATAL / UNAVAILABLE 且原本要工具 → 清空工具、退到本机纯文本；
     * - BLOCKED / 原本就不要工具 → 直接向上抛，交给 [friendly] 转成人话。
     *
     * 降级时**不隐藏已经发生的事**：往步骤里写一条说明，列清哪些工具真的做完了、
     * 哪些没做，并要求本地模型不得声称执行过未执行的操作（它在上下文里能看到这条）。
     */
    private suspend fun callModel(task: AgentTask, steps: List<AgentStep>, req: ModelRequest): ModelResponse {
        val wanted = req.tools.isNotEmpty()
        return try {
            router.complete(req, needTools = wanted)
        } catch (e: ModelError) {
            if (!wanted || e.kind !in DEGRADABLE) throw e
            store.appendStep(note(task.id, degradationNote(steps, e)))
            onEvent("任务 ${task.id.take(8)} 降级为本地纯文本（${e.kind}）")
            // 必须用重建后的 steps 重新拼 messages：刚写入的降级说明要真的出现在
            // 本地模型看到的上下文里，否则「告诉用户哪些做了/没做」只是写进库给自己看。
            val degradedMessages = buildMessages(task, store.steps(task.id))
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

    /**
     * 幂等执行：先落「我要执行」的意图，再执行，最后落结果。
     *
     * 三种历史记录的处理：
     * - SUCCEEDED：直接复用结果，不碰副作用（模型重发同一个调用不会建第二个提醒）；
     * - RUNNING / PENDING / UNKNOWN：上次结果不可知，**不重试**，把「未知」如实告诉模型；
     * - FAILED：允许再试（上次明确失败，通常没有副作用）。
     */
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

            ToolExecState.FAILED -> {
                // 明确失败（工具回报没做成，通常无副作用）允许重试。
                // 但 beginToolExecution 对同幂等键是 CONFLICT_IGNORE，会挡住重试，
                // 所以这里复用旧 executionId 并先把状态重置为 RUNNING（重试意图先落库），
                // 再往下走到执行。绝不能因为「同键已存在」就把 FAILED 永久锁死。
            }
            null -> Unit // 从没执行过 → 正常新建
        }

        val retrying = existing?.state == ToolExecState.FAILED
        val exec = ToolExecution(
            // 重试时复用旧 id，保证 beginToolExecution 拿到的记录就是我们重置的那条
            executionId = if (retrying) existing!!.executionId else UUID.randomUUID().toString(),
            taskId = task.id,
            tool = call.name,
            args = call.arguments.take(2_000),
            idemKey = key,
            state = ToolExecState.RUNNING,
            attempts = (existing?.attempts ?: 0) + 1,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        // 执行意图先落库：这一步之后 App 被杀，恢复时才知道「可能已经做了一半」
        if (retrying) store.updateToolExecution(exec)
        val stored = store.beginToolExecution(exec)
        if (stored.executionId != exec.executionId) {
            // 并发/重复插入：已有同键记录，按它的状态走一遍上面的判定（保守处理）
            if (stored.state == ToolExecState.SUCCEEDED) return ToolResult.ok(stored.result.ifBlank { "（已执行）" })
            return ToolResult.fail("同一操作已在执行中，未重复执行。")
        }

        val attempt = runToolOnce(tool, call)
        // A 修复：FAILED 只代表「工具明确回报没做成」（参数错、明确的领域拒绝），
        // 这类通常没有副作用，允许重试。但超时 / 抛异常意味着工具可能已经做完副作用才出错
        // （create_reminder 插入成功、scanAsync 抛异常就是真实例子），一律记 UNKNOWN，绝不重试。
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

    /**
     * [threw] 区分「工具主动返回失败」与「工具抛异常」。
     * 前者通常是明确的参数/领域拒绝（无副作用），后者可能是做完副作用才炸（UNKNOWN）。
     */
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
                    timedOut = false,
                    threw = true,
                )
            }
        }
        return if (r == null) Attempt(ToolResult.fail("工具超时（${timeoutMs / 1000} 秒）"), timedOut = true)
        else r
    }

    /** 幂等键：任务 + 工具名 + 规范化参数。 */
    private fun idemKey(taskId: String, call: ToolCall): String =
        taskId + "|" + call.name + "|" + normalizeArgs(call.arguments)

    private fun normalizeArgs(raw: String): String {
        val s = raw.ifBlank { "{}" }.trim()
        // 参数顺序不同的同一调用不应被当成两次：按 key 排序后再拼（值保持原样）。
        // 用 kotlinx.serialization 而不是 org.json —— 后者在 JVM 单测里是 stub，
        // 会让「幂等键」这种纯逻辑变得只能在真机上验。
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(s).jsonObject
            obj.keys.sorted().joinToString(",") { k -> "$k=${obj[k]}" }
        }.getOrElse { s.replace(Regex("\\s+"), "") }
    }

    private fun toolStep(taskId: String, call: ToolCall, result: ToolResult, latency: Long) = AgentStep(
        taskId = taskId,
        seq = store.stepsCount(taskId),
        kind = StepKind.TOOL,
        content = if (result.ok) result.text else (result.error.ifBlank { result.text }),
        toolName = call.name,
        toolArgs = call.arguments,
        toolCallId = call.id,
        ok = result.ok,
        at = clock(),
        latencyMs = latency,
    )

    private fun note(taskId: String, text: String) = AgentStep(
        taskId = taskId,
        seq = store.stepsCount(taskId),
        kind = StepKind.NOTE,
        content = text,
        at = clock(),
    )

    private fun fail(task: AgentTask, message: String): RunOutcome {
        val failed = task.copy(
            status = TaskStatus.FAILED,
            error = message,
            finishedAt = clock(),
            updatedAt = clock(),
            pendingCall = null,
        )
        store.update(failed)
        store.appendStep(
            AgentStep(
                taskId = task.id,
                seq = store.stepsCount(task.id),
                kind = StepKind.ERROR,
                content = message,
                ok = false,
                at = clock(),
            )
        )
        onEvent("任务失败 ${task.id.take(8)}：$message")
        return RunOutcome.Failed(failed, message)
    }

    private fun move(task: AgentTask, next: TaskStatus): AgentTask {
        val t = if (task.status.canMoveTo(next)) {
            task.copy(status = next, updatedAt = clock())
        } else {
            // 非法迁移不抛异常：宁可继续跑，也不要把用户的任务弄丢
            task.copy(status = next, updatedAt = clock())
        }
        store.update(t)
        return t
    }

    private fun friendly(e: ModelError): String = when (e.kind) {
        Kind.NETWORK -> "网络不通，任务已停在当前步骤，恢复网络后可以继续。"
        Kind.TRANSIENT -> "模型服务临时不可用（${e.message}），可以稍后继续。"
        Kind.FATAL -> "模型配置有问题：${e.message}"
        Kind.BLOCKED -> "内容被隐私闸门拦下：${e.message}（不会为了跑通而换模型绕开）"
        Kind.UNAVAILABLE -> "没有可用模型：${e.message}"
    }

    /**
     * 从步骤重建上下文。
     *
     * 只重建「协议需要」的部分：assistant 的 tool_calls 与对应 tool 结果必须成对出现，
     * 否则 OpenAI 兼容端点会直接 400。NOTE/ERROR 折成一条 system 说明，放在最后。
     */
    internal fun buildMessages(task: AgentTask, steps: List<AgentStep>): List<AgentMessage> {
        val out = ArrayList<AgentMessage>()
        out.add(AgentMessage.system(systemPrompt()))
        out.add(AgentMessage.user(task.goal))
        val notes = ArrayList<String>()
        val window = steps.takeLast(24)
        for (s in window) {
            when (s.kind) {
                StepKind.ASSISTANT -> {
                    val calls = CallCodec.decode(s.callsJson)
                    out.add(AgentMessage.assistant(s.content, calls))
                }
                StepKind.TOOL -> {
                    out.add(AgentMessage.tool(s.toolCallId, s.content.ifBlank { if (s.ok) "（成功，无输出）" else "（失败）" }))
                }
                StepKind.NOTE, StepKind.ERROR -> notes.add(s.content)
                StepKind.GOAL -> Unit
            }
        }
        if (notes.isNotEmpty()) {
            out.add(AgentMessage.system("过程说明（不是用户说的话）：\n" + notes.takeLast(6).joinToString("\n") { "· $it" }))
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
