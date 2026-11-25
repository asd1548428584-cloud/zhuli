package com.zhuli.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * 任务状态机与持久化契约。
 *
 * 为什么任务要单独建模，而不是「一次对话调用」：
 * - 需要**跨进程存活**：用户点完确认可能已经过了 10 分钟，App 可能被杀过一轮；
 * - 需要**可审计**：每一步谁发起、调了什么、参数是什么、结果如何，都要落库；
 * - 需要**防重复**：同一个目标在 30 分钟内不应该跑两遍（真机事故里提醒被排了 7 个闹钟）；
 * - 需要**可中断**：等待用户确认时必须停在那，而不是丢上下文重来。
 */
enum class TaskStatus {
    /** 已创建，还没开始 */
    PENDING,

    /** 正在跑 */
    RUNNING,

    /** 卡在需要用户确认的工具调用上（状态已落库，进程死了也能恢复） */
    WAITING_APPROVAL,

    /** 用户主动暂停，或进程重启后把「跑一半」的标成暂停，等下一次继续 */
    PAUSED,

    /** 用户点了取消，但正在执行的工具还没安全停下（批次 B：暂停与取消必须分开） */
    CANCELLING,

    /** 正常完成，result 里有最终答复 */
    DONE,

    /** 失败，error 里有原因；可重试的失败用 PAUSED 而不是 FAILED */
    FAILED,

    /** 用户取消 */
    CANCELLED;

    val terminal: Boolean get() = this == DONE || this == FAILED || this == CANCELLED

    /**
     * 允许的状态转换（批次 B：这是唯一判定表，[AgentLoop.move] 与存储层 CAS 都用它）。
     * 终态不可再迁出；CANCELLING 只能落到 CANCELLED（或异常落 FAILED），不能"取消后又完成"。
     */
    fun canMoveTo(next: TaskStatus): Boolean = when (this) {
        PENDING -> next in setOf(RUNNING, CANCELLING, CANCELLED, FAILED)
        RUNNING -> next in setOf(WAITING_APPROVAL, PAUSED, CANCELLING, CANCELLED, DONE, FAILED)
        WAITING_APPROVAL -> next in setOf(RUNNING, PAUSED, CANCELLING, CANCELLED, FAILED)
        PAUSED -> next in setOf(RUNNING, CANCELLING, CANCELLED, FAILED)
        CANCELLING -> next in setOf(CANCELLED, FAILED)
        DONE, FAILED, CANCELLED -> false
    }
}

enum class StepKind {
    /** 用户目标 */
    GOAL,

    /** 模型的文字输出（可能是最终答复，也可能是「我先查一下」） */
    ASSISTANT,

    /** 工具执行结果 */
    TOOL,

    /** 系统的说明：等待确认、被策略拒绝、降级到本地… */
    NOTE,

    /** 错误 */
    ERROR,
}

@Serializable
data class AgentStep(
    val id: Long = 0L,
    val taskId: String,
    val seq: Int,
    val kind: StepKind,
    val content: String = "",
    val toolName: String = "",
    val toolArgs: String = "",
    val toolCallId: String = "",
    /** assistant 一轮里发出的全部工具调用（JSON），重建上下文用 */
    val callsJson: String = "",
    val ok: Boolean = true,
    val at: Long = 0L,
    val latencyMs: Long = 0L,
    val providerId: String = "",
)

data class AgentTask(
    val id: String,
    val goal: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val finishedAt: Long = 0L,
    val result: String = "",
    val error: String = "",
    val pendingCall: ToolCall? = null,
    val pendingRisk: String = "",
    /**
     * 批次 B：审批/恢复的完整上下文。与 [pendingCall] 同步维护，
     * 但多了会话 ID、参数指纹、过期时间与该轮尚未执行的调用。
     * 落库在 v16 已有的 pending_call 列里（[TaskContextCodec] 兼容旧格式）。
     */
    val pendingApproval: PendingApproval? = null,
    /** 会话隔离键：同一会话的任务串行，不同会话可并行。 */
    val sessionId: String = "",
    /** 唯一阶段来源：CREATED / MODEL_DECIDE / TOOL_EXEC / AWAIT_APPROVAL / FINISH。 */
    val phase: String = "CREATED",
    /** 取消原因（批次 B：与失败原因 error 分开，便于审计）。 */
    val cancelReason: String = "",
    /** 去重键：目标归一化后的哈希。同一键在窗口内只允许一个活跃任务 */
    val dedupeKey: String = "",
    /** 来源：chat / brief / manual */
    val origin: String = "manual",
    val stepCount: Int = 0,
    /**
     * 这次任务是否只许在设备内完成（隐私策略 / 用户明确要求）。
     * 落库是必须的：恢复任务时不能把用户「别联网」的要求忘掉。
     */
    val localOnly: Boolean = false,
) {
    val active: Boolean get() = !status.terminal
}

/**
 * 工具执行记录的状态机。
 *
 * ```
 *   PENDING ──► RUNNING ──► SUCCEEDED
 *      │            ├─────► FAILED
 *      │            └─────► UNKNOWN   （进程在执行中被杀，结果不可知）
 *      └──────────────────► UNKNOWN   （还没开始就被杀：同样不重试，交给人判断）
 * ```
 *
 * 为什么需要 UNKNOWN：工具已经产生副作用（提醒已建、闹钟已排），但结果没来得及落库。
 * 这时**重复执行**会造成两个提醒，比"少执行一次"更糟。所以恢复时把这类记为 UNKNOWN，
 * 让模型看到「上次结果未知」，由用户决定要不要再来一次。
 */
enum class ToolExecState {
    PENDING, RUNNING, SUCCEEDED, FAILED, UNKNOWN;

    val done: Boolean get() = this == SUCCEEDED || this == FAILED

    companion object {
        fun of(raw: String): ToolExecState = entries.firstOrNull { it.name == raw } ?: UNKNOWN
    }
}

data class ToolExecution(
    val executionId: String,
    val taskId: String,
    val tool: String,
    val args: String,
    /** 幂等键：任务 + 工具 + 规范化的参数。同一键成功后不再重复执行。 */
    val idemKey: String,
    val state: ToolExecState,
    val result: String = "",
    val error: String = "",
    val attempts: Int = 1,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

/**
 * 任务存储。生产实现是 SQLite（[SqlAgentStore]），测试用 [InMemoryAgentStore]。
 * 接口里全部是同步方法：调用方（AgentLoop）已经在 IO 线程上，不需要再套一层协程。
 */
interface AgentStore {
    fun create(task: AgentTask): AgentTask
    fun get(id: String): AgentTask?
    fun update(task: AgentTask)

    /**
     * 原子状态迁移：仅当当前状态等于 [from] 时才写入 [to]。
     * 返回 false 表示被别的执行路径抢先改过（重复审批 / 并发取消），调用方必须放弃本次动作。
     * 这是"任务状态唯一可靠来源"在并发下的保证。
     */
    fun transition(id: String, from: TaskStatus, to: TaskStatus, now: Long): Boolean

    fun appendStep(step: AgentStep): AgentStep
    fun steps(taskId: String): List<AgentStep>
    fun stepsCount(taskId: String): Int
    fun recent(limit: Int = 50): List<AgentTask>
    fun active(): List<AgentTask>

    /** 同目标、同来源、时间窗内的活跃任务，用于防重复执行。 */
    fun findActiveByDedupeKey(key: String, since: Long): AgentTask?

    /** 进程重启后：上次没跑完的都标成 PAUSED（不是 FAILED —— 它还有救）。 */
    fun markOrphansPaused(now: Long): Int

    /** 执行前落一条意图（PENDING/RUNNING）；同 idemKey 已有行时返回已存在的那条。 */
    fun beginToolExecution(exec: ToolExecution): ToolExecution

    fun findToolExecution(idemKey: String): ToolExecution?

    fun updateToolExecution(exec: ToolExecution)

    fun toolExecutions(taskId: String): List<ToolExecution>

    /**
     * 把「正在执行（RUNNING/PENDING）」的记录记为 UNKNOWN —— 副作用可能已发生，不可重试。
     * @param taskId 非空时只处理该任务（单任务恢复）；为空时处理全库（仅进程启动用）。
     *   必须支持按任务隔离：一个任务恢复时不能把另一个仍在跑的任务的记录误判为 UNKNOWN。
     */
    fun recoverRunningExecutions(now: Long, taskId: String? = null): Int
}

/** 内存实现：单测用，也是 SQLite 不可用时的降级（宁可记不住，也不要崩）。 */
class InMemoryAgentStore : AgentStore {
    private val tasks = LinkedHashMap<String, AgentTask>()
    private val stepMap = HashMap<String, MutableList<AgentStep>>()
    private val stepId = AtomicLong(0)
    private val execs = LinkedHashMap<String, ToolExecution>()

    @Synchronized
    override fun create(task: AgentTask): AgentTask {
        tasks[task.id] = task.copy(stepCount = 0)
        return tasks.getValue(task.id)
    }

    @Synchronized
    override fun get(id: String): AgentTask? = tasks[id]

    @Synchronized
    override fun update(task: AgentTask) {
        if (tasks.containsKey(task.id)) tasks[task.id] = task
    }

    @Synchronized
    override fun transition(id: String, from: TaskStatus, to: TaskStatus, now: Long): Boolean {
        val t = tasks[id] ?: return false
        if (t.status != from || !from.canMoveTo(to)) return false
        tasks[id] = t.copy(status = to, updatedAt = now)
        return true
    }

    @Synchronized
    override fun appendStep(step: AgentStep): AgentStep {
        val withId = step.copy(id = stepId.incrementAndGet())
        stepMap.getOrPut(step.taskId) { ArrayList() }.add(withId)
        tasks[step.taskId]?.let {
            tasks[step.taskId] = it.copy(stepCount = stepMap.getValue(step.taskId).size, updatedAt = step.at)
        }
        return withId
    }

    @Synchronized
    override fun steps(taskId: String): List<AgentStep> = stepMap[taskId]?.toList() ?: emptyList()

    @Synchronized
    override fun stepsCount(taskId: String): Int = stepMap[taskId]?.size ?: 0

    @Synchronized
    override fun recent(limit: Int): List<AgentTask> =
        tasks.values.sortedByDescending { it.createdAt }.take(limit)

    @Synchronized
    override fun active(): List<AgentTask> = tasks.values.filter { it.active }.sortedByDescending { it.createdAt }

    @Synchronized
    override fun findActiveByDedupeKey(key: String, since: Long): AgentTask? =
        tasks.values.firstOrNull { it.dedupeKey == key && it.active && it.createdAt >= since }

    @Synchronized
    override fun markOrphansPaused(now: Long): Int {
        var n = 0
        for ((id, t) in tasks.entries.toList()) {
            if (t.status == TaskStatus.RUNNING) {
                tasks[id] = t.copy(status = TaskStatus.PAUSED, updatedAt = now)
                n++
            } else if (t.status == TaskStatus.CANCELLING) {
                // 进程在取消中途死了：用户已明确取消，不能复活成 PAUSED
                tasks[id] = t.copy(status = TaskStatus.CANCELLED, updatedAt = now, finishedAt = now)
            }
        }
        return n
    }

    @Synchronized
    override fun beginToolExecution(exec: ToolExecution): ToolExecution {
        execs[exec.idemKey]?.let { return it }
        execs[exec.idemKey] = exec
        return exec
    }

    @Synchronized
    override fun findToolExecution(idemKey: String): ToolExecution? = execs[idemKey]

    @Synchronized
    override fun updateToolExecution(exec: ToolExecution) {
        execs[exec.idemKey] = exec
    }

    @Synchronized
    override fun toolExecutions(taskId: String): List<ToolExecution> =
        execs.values.filter { it.taskId == taskId }.sortedBy { it.createdAt }

    @Synchronized
    override fun recoverRunningExecutions(now: Long, taskId: String?): Int {
        var n = 0
        for ((key, e) in execs.entries.toList()) {
            if (taskId != null && e.taskId != taskId) continue
            if (e.state == ToolExecState.RUNNING || e.state == ToolExecState.PENDING) {
                execs[key] = e.copy(state = ToolExecState.UNKNOWN, updatedAt = now)
                n++
            }
        }
        return n
    }
}

/** 步骤里的工具调用编解码（持久化与上下文重建共用一套，避免两处格式漂移）。 */
object CallCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(calls: List<ToolCall>): String =
        if (calls.isEmpty()) "" else json.encodeToString(calls)

    fun decode(raw: String): List<ToolCall> =
        if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString<List<ToolCall>>(raw) }.getOrDefault(emptyList())
}
