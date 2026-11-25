package com.zhuli.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 批次 B：审批上下文信封。
 *
 * v16 的 `zhuli_agent_task.pending_call` 列只放一个 ToolCall。审批恢复需要的远不止这些：
 * 会话 ID、当前阶段、这一轮尚未执行的调用、这次批准对应的**参数指纹**、过期时间。
 * 如果另开一列就要升 DB 版本；这里改成在该列里放一个向后兼容的信封：
 *
 * - 旧行是 `[{"id":...}]`（ToolCall 数组），照旧能读；
 * - 新行是 `{"v":2,...}`（[TaskContext]），读新格式。
 *
 * 因此 **DB_VERSION 保持 16**，存量任务不会因批次 B 升级而失效。
 */
@Serializable
data class PendingApproval(
    /** 必须与 task.id 一致，防止审批串任务。 */
    val taskId: String,
    val sessionId: String,
    val call: ToolCall,
    val risk: String,
    /** taskId + 调用 ID + 工具名 + 规范化参数；参数一变就不相等。 */
    val fingerprint: String,
    val createdAt: Long,
    val expiresAt: Long,
    /** 该轮里排在这次调用之后、尚未执行的调用（CallCodec 编码）。 */
    val remainingCallsJson: String = "",
    val phase: String = "AWAIT_APPROVAL",
) {
    /** 到期时刻本身也算过期；旧数据 expiresAt=0 因此永远无效。 */
    fun valid(now: Long): Boolean = now < expiresAt
}

@Serializable
data class TaskContext(
    val v: Int = 2,
    val sessionId: String = "",
    /** CREATED / MODEL_DECIDE / TOOL_EXEC / AWAIT_APPROVAL / FINISH 之一，随任务落库。 */
    val phase: String = "CREATED",
    val approval: PendingApproval? = null,
    val cancelReason: String = "",
)

object TaskContextCodec {
    private const val FORMAT_VERSION = 2
    const val APPROVAL_TTL_MS = 5L * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(ctx: TaskContext): String = json.encodeToString(ctx.copy(v = FORMAT_VERSION))

    /**
     * 兼容三种输入：空串、v16 的 ToolCall 数组、v2 信封。
     * 解析不出来时返回空上下文 —— 绝不因为一条脏数据让任务恢复崩溃。
     */
    fun decode(raw: String, taskId: String = ""): TaskContext {
        if (raw.isBlank()) return TaskContext()
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("[") -> runCatching {
                val calls = json.decodeFromString<List<ToolCall>>(trimmed)
                val call = calls.firstOrNull() ?: return@runCatching TaskContext(phase = "TOOL_EXEC")
                TaskContext(
                    phase = "AWAIT_APPROVAL",
                    approval = PendingApproval(
                        taskId = taskId,
                        sessionId = "",
                        call = call,
                        risk = Risk.WRITE_LOCAL.name,
                        fingerprint = fingerprint(taskId, call),
                        createdAt = 0L,
                        // 旧数据没有过期时间：按"立即过期"处理，要求用户重新确认。
                        // 宁可多问一次，也不能让一条不知道放了多久的授权继续生效。
                        expiresAt = 0L,
                    ),
                )
            }.getOrDefault(TaskContext())

            trimmed.startsWith("{") -> runCatching {
                json.decodeFromString<TaskContext>(trimmed).takeIf { it.v == FORMAT_VERSION }
            }.getOrNull() ?: TaskContext()

            else -> TaskContext()
        }
    }

    /** 审批指纹：任务 + 调用 ID + 工具名 + 规范化参数。 */
    fun fingerprint(taskId: String, call: ToolCall): String =
        listOf(taskId, call.id, call.name, normalizeArgs(call.arguments)).joinToString("\u001f")

    /**
     * 参数规范化：按 key 排序，数值/布尔/数组结构保留，字符串保留引号。
     * 这样 `{"a":1,"b":2}` 与 `{"b":2,"a":1}` 同指纹，而 `"1"` 与 `1` 不同指纹。
     */
    fun normalizeArgs(raw: String): String {
        val s = raw.ifBlank { "{}" }.trim()
        return runCatching {
            val obj: JsonObject = Json.parseToJsonElement(s).jsonObject
            obj.keys.sorted().joinToString(",") { k -> "$k=${obj[k]}" }
        }.getOrElse { s.replace(Regex("\\s+"), "") }
    }

    fun newApproval(
        taskId: String,
        sessionId: String,
        call: ToolCall,
        risk: Risk,
        now: Long,
        remainingCalls: List<ToolCall> = emptyList(),
    ) = PendingApproval(
        taskId = taskId,
        sessionId = sessionId,
        call = call,
        risk = risk.name,
        fingerprint = fingerprint(taskId, call),
        createdAt = now,
        expiresAt = now + APPROVAL_TTL_MS,
        remainingCallsJson = CallCodec.encode(remainingCalls),
    )
}
