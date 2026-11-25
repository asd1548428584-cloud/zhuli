package com.zhuli.agent

import android.content.ContentValues
import android.database.Cursor
import com.zhuli.core.InboxDatabase as Db

/**
 * Agent 任务的 SQLite 实现。
 *
 * 为什么必须落库而不是留在内存：用户点「允许」可能发生在 10 分钟后，
 * 中间 App 可能被系统杀掉；WAITING_APPROVAL + pendingCall 落库后才能原样恢复。
 * 进程重启时 [markOrphansPaused] 把 RUNNING 标成 PAUSED —— 留着上下文，等用户点继续。
 */
class SqlAgentStore(private val db: Db) : AgentStore {

    override fun create(task: AgentTask): AgentTask {
        val cv = task.cv().apply { put(Db.COL_T_STEPS, 0) }
        db.writableDatabase.insertWithOnConflict(
            Db.TABLE_TASK, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
        return task.copy(stepCount = 0)
    }

    override fun get(id: String): AgentTask? = first("${Db.COL_T_ID}=?", arrayOf(id))

    override fun update(task: AgentTask) {
        val cv = task.cv()
        db.writableDatabase.update(Db.TABLE_TASK, cv, "${Db.COL_T_ID}=?", arrayOf(task.id))
    }

    override fun transition(id: String, from: TaskStatus, to: TaskStatus, now: Long): Boolean {
        if (!from.canMoveTo(to)) return false
        val cv = ContentValues().apply {
            put(Db.COL_T_STATUS, to.name)
            put(Db.COL_T_UPDATED, now)
        }
        val n = db.writableDatabase.update(
            Db.TABLE_TASK, cv,
            "${Db.COL_T_ID}=? AND ${Db.COL_T_STATUS}=?",
            arrayOf(id, from.name),
        )
        return n > 0
    }

    override fun appendStep(step: AgentStep): AgentStep {
        val sql = db.writableDatabase
        sql.beginTransaction()
        try {
            val seq = if (step.seq > 0) step.seq else sql.rawQuery(
                "SELECT COALESCE(MAX(${Db.COL_AS_SEQ}), -1) + 1 FROM ${Db.TABLE_STEP} WHERE ${Db.COL_AS_TASK}=?",
                arrayOf(step.taskId)
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            val cv = ContentValues().apply {
                put(Db.COL_AS_TASK, step.taskId)
                put(Db.COL_AS_SEQ, seq)
                put(Db.COL_AS_KIND, step.kind.name)
                put(Db.COL_AS_CONTENT, step.content)
                put(Db.COL_AS_TOOL, step.toolName)
                put(Db.COL_AS_ARGS, step.toolArgs)
                put(Db.COL_AS_CALLID, step.toolCallId)
                put(Db.COL_AS_CALLS, step.callsJson)
                put(Db.COL_AS_OK, if (step.ok) 1 else 0)
                put(Db.COL_AS_AT, step.at)
                put(Db.COL_AS_LATENCY, step.latencyMs)
                put(Db.COL_AS_PROVIDER, step.providerId)
            }
            val id = sql.insertWithOnConflict(
                Db.TABLE_STEP, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
            )
            sql.execSQL(
                "UPDATE ${Db.TABLE_TASK} SET ${Db.COL_T_STEPS}=(SELECT COUNT(*) FROM ${Db.TABLE_STEP} WHERE ${Db.COL_AS_TASK}=?), " +
                    "${Db.COL_T_UPDATED}=? WHERE ${Db.COL_T_ID}=?",
                arrayOf<Any>(step.taskId, step.at, step.taskId)
            )
            sql.setTransactionSuccessful()
            return step.copy(id = id, seq = seq)
        } finally {
            sql.endTransaction()
        }
    }

    override fun steps(taskId: String): List<AgentStep> {
        val out = ArrayList<AgentStep>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM ${Db.TABLE_STEP} WHERE ${Db.COL_AS_TASK}=? ORDER BY ${Db.COL_AS_SEQ}",
            arrayOf(taskId)
        ).use { c ->
            while (c.moveToNext()) out.add(readStep(c))
        }
        return out
    }

    override fun stepsCount(taskId: String): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM ${Db.TABLE_STEP} WHERE ${Db.COL_AS_TASK}=?", arrayOf(taskId)
    ).use { c -> if (c.moveToNext()) c.getInt(0) else 0 }

    override fun recent(limit: Int): List<AgentTask> = list(
        "ORDER BY ${Db.COL_T_CREATED} DESC LIMIT $limit", emptyArray()
    )

    override fun active(): List<AgentTask> = list(
        "WHERE ${Db.COL_T_STATUS} IN ('PENDING','RUNNING','WAITING_APPROVAL','PAUSED','CANCELLING') ORDER BY ${Db.COL_T_CREATED} DESC",
        emptyArray(),
    )

    override fun findActiveByDedupeKey(key: String, since: Long): AgentTask? = first(
        "${Db.COL_T_DEDUPE}=? AND ${Db.COL_T_CREATED}>=? AND ${Db.COL_T_STATUS} IN ('PENDING','RUNNING','WAITING_APPROVAL','PAUSED','CANCELLING') ORDER BY ${Db.COL_T_CREATED} DESC LIMIT 1",
        arrayOf(key, since.toString()),
    )

    override fun markOrphansPaused(now: Long): Int {
        val sql = db.writableDatabase
        // RUNNING 的孤儿留上下文等用户继续；CANCELLING 的孤儿说明进程在取消中途死了，
        // 不能复活成 PAUSED —— 用户已经明确取消过，直接落 CANCELLED。
        val cvPaused = ContentValues().apply {
            put(Db.COL_T_STATUS, TaskStatus.PAUSED.name)
            put(Db.COL_T_UPDATED, now)
        }
        val nPaused = sql.update(Db.TABLE_TASK, cvPaused, "${Db.COL_T_STATUS}='RUNNING'", null)
        val cvCancelled = ContentValues().apply {
            put(Db.COL_T_STATUS, TaskStatus.CANCELLED.name)
            put(Db.COL_T_UPDATED, now)
            put(Db.COL_T_FINISHED, now)
        }
        sql.update(Db.TABLE_TASK, cvCancelled, "${Db.COL_T_STATUS}='CANCELLING'", null)
        return nPaused
    }

    // ---------- v16：工具执行记录 ----------

    override fun beginToolExecution(exec: ToolExecution): ToolExecution {
        val sql = db.writableDatabase
        val cv = ContentValues().apply {
            put(Db.COL_TX_ID, exec.executionId)
            put(Db.COL_TX_TASK, exec.taskId)
            put(Db.COL_TX_TOOL, exec.tool)
            put(Db.COL_TX_ARGS, exec.args)
            put(Db.COL_TX_KEY, exec.idemKey)
            put(Db.COL_TX_STATE, exec.state.name)
            put(Db.COL_TX_RESULT, exec.result)
            put(Db.COL_TX_ERROR, exec.error)
            put(Db.COL_TX_CREATED, exec.createdAt)
            put(Db.COL_TX_UPDATED, exec.updatedAt)
            put(Db.COL_TX_ATTEMPTS, exec.attempts)
        }
        // CONFLICT_IGNORE：同 idemKey 已存在时保留原记录（幂等键唯一索引兜住并发重复）
        sql.insertWithOnConflict(
            Db.TABLE_TOOL_EXEC, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
        return findToolExecution(exec.idemKey) ?: exec
    }

    override fun findToolExecution(idemKey: String): ToolExecution? {
        if (idemKey.isBlank()) return null
        return db.readableDatabase.rawQuery(
            "SELECT * FROM ${Db.TABLE_TOOL_EXEC} WHERE ${Db.COL_TX_KEY}=? LIMIT 1", arrayOf(idemKey)
        ).use { c -> if (c.moveToNext()) readExec(c) else null }
    }

    override fun updateToolExecution(exec: ToolExecution) {
        val cv = ContentValues().apply {
            put(Db.COL_TX_STATE, exec.state.name)
            put(Db.COL_TX_RESULT, exec.result)
            put(Db.COL_TX_ERROR, exec.error)
            put(Db.COL_TX_UPDATED, exec.updatedAt)
            put(Db.COL_TX_ATTEMPTS, exec.attempts)
        }
        db.writableDatabase.update(
            Db.TABLE_TOOL_EXEC, cv, "${Db.COL_TX_KEY}=?", arrayOf(exec.idemKey)
        )
    }

    override fun toolExecutions(taskId: String): List<ToolExecution> {
        val out = ArrayList<ToolExecution>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM ${Db.TABLE_TOOL_EXEC} WHERE ${Db.COL_TX_TASK}=? ORDER BY ${Db.COL_TX_CREATED}",
            arrayOf(taskId)
        ).use { c -> while (c.moveToNext()) out.add(readExec(c)) }
        return out
    }

    override fun recoverRunningExecutions(now: Long, taskId: String?): Int {
        val sql = db.writableDatabase
        val cv = ContentValues().apply {
            put(Db.COL_TX_STATE, ToolExecState.UNKNOWN.name)
            put(Db.COL_TX_UPDATED, now)
        }
        val where = if (taskId == null) {
            "${Db.COL_TX_STATE} IN (?,?)"
        } else {
            "${Db.COL_TX_STATE} IN (?,?) AND ${Db.COL_TX_TASK}=?"
        }
        val args = if (taskId == null) {
            arrayOf(ToolExecState.RUNNING.name, ToolExecState.PENDING.name)
        } else {
            arrayOf(ToolExecState.RUNNING.name, ToolExecState.PENDING.name, taskId)
        }
        return sql.update(Db.TABLE_TOOL_EXEC, cv, where, args)
    }

    // ---------- 内部 ----------

    // 不再追加 LIMIT：findActiveByDedupeKey 传入的 where 自带 LIMIT 1，
    // 再拼一层会变成 "... LIMIT 1 LIMIT 1" 触发 SQLite 语法错误。
    // get() 传入的纯条件即使匹配多行，firstOrNull() 也只取第一条，语义不变。
    private fun first(where: String, args: Array<String>) = list("WHERE $where", args).firstOrNull()

    private fun list(tail: String, args: Array<String>): List<AgentTask> {
        val out = ArrayList<AgentTask>()
        db.readableDatabase.rawQuery("SELECT * FROM ${Db.TABLE_TASK} $tail", args).use { c ->
            while (c.moveToNext()) out.add(readTask(c))
        }
        return out
    }

    private fun AgentTask.cv() = ContentValues().apply {
        put(Db.COL_T_ID, id)
        put(Db.COL_T_GOAL, goal)
        put(Db.COL_T_STATUS, status.name)
        put(Db.COL_T_CREATED, createdAt)
        put(Db.COL_T_UPDATED, updatedAt)
        put(Db.COL_T_FINISHED, finishedAt)
        put(Db.COL_T_RESULT, result)
        put(Db.COL_T_ERROR, error)
        // v16 的 pending_call 列现在放 TaskContext 信封；旧行是 ToolCall 数组，Codec 两种都认。
        // 向后兼容：只设置了 pendingCall（没设置信封）的旧调用方，仍要能原样读回。
        val effectiveApproval = pendingApproval ?: pendingCall?.let { call ->
            TaskContextCodec.newApproval(
                id, sessionId, call, Risk.of(pendingRisk.ifBlank { Risk.WRITE_LOCAL.name }),
                createdAt,
            )
        }
        put(
            Db.COL_T_PENDING,
            TaskContextCodec.encode(
                TaskContext(
                    sessionId = sessionId,
                    phase = phase,
                    approval = effectiveApproval,
                    cancelReason = cancelReason,
                )
            )
        )
        put(Db.COL_T_RISK, pendingRisk)
        put(Db.COL_T_DEDUPE, dedupeKey)
        put(Db.COL_T_ORIGIN, origin)
        put(Db.COL_T_LOCAL, if (localOnly) 1 else 0)
    }

    private fun readTask(c: Cursor): AgentTask {
        val ctx = TaskContextCodec.decode(c.str(Db.COL_T_PENDING), c.str(Db.COL_T_ID))
        return AgentTask(
            id = c.str(Db.COL_T_ID),
            goal = c.str(Db.COL_T_GOAL),
            status = runCatching { TaskStatus.valueOf(c.str(Db.COL_T_STATUS)) }.getOrDefault(TaskStatus.PAUSED),
            createdAt = c.getLong(c.getColumnIndexOrThrow(Db.COL_T_CREATED)),
            updatedAt = c.getLong(c.getColumnIndexOrThrow(Db.COL_T_UPDATED)),
            finishedAt = c.getLong(c.getColumnIndexOrThrow(Db.COL_T_FINISHED)),
            result = c.str(Db.COL_T_RESULT),
            error = c.str(Db.COL_T_ERROR),
            pendingCall = ctx.approval?.call ?: CallCodec.decode(c.str(Db.COL_T_PENDING)).firstOrNull(),
            pendingRisk = c.str(Db.COL_T_RISK),
            pendingApproval = ctx.approval,
            sessionId = ctx.sessionId,
            phase = ctx.phase,
            cancelReason = ctx.cancelReason,
            dedupeKey = c.str(Db.COL_T_DEDUPE),
            origin = c.str(Db.COL_T_ORIGIN),
            stepCount = c.getInt(c.getColumnIndexOrThrow(Db.COL_T_STEPS)),
            localOnly = c.getInt(c.getColumnIndexOrThrow(Db.COL_T_LOCAL)) != 0,
        )
    }

    private fun readExec(c: Cursor) = ToolExecution(
        executionId = c.str(Db.COL_TX_ID),
        taskId = c.str(Db.COL_TX_TASK),
        tool = c.str(Db.COL_TX_TOOL),
        args = c.str(Db.COL_TX_ARGS),
        idemKey = c.str(Db.COL_TX_KEY),
        state = ToolExecState.of(c.str(Db.COL_TX_STATE)),
        result = c.str(Db.COL_TX_RESULT),
        error = c.str(Db.COL_TX_ERROR),
        attempts = c.getInt(c.getColumnIndexOrThrow(Db.COL_TX_ATTEMPTS)),
        createdAt = c.getLong(c.getColumnIndexOrThrow(Db.COL_TX_CREATED)),
        updatedAt = c.getLong(c.getColumnIndexOrThrow(Db.COL_TX_UPDATED)),
    )

    private fun readStep(c: Cursor) = AgentStep(
        id = c.getLong(c.getColumnIndexOrThrow(Db.COL_AS_ID)),
        taskId = c.str(Db.COL_AS_TASK),
        seq = c.getInt(c.getColumnIndexOrThrow(Db.COL_AS_SEQ)),
        kind = runCatching { StepKind.valueOf(c.str(Db.COL_AS_KIND)) }.getOrDefault(StepKind.NOTE),
        content = c.str(Db.COL_AS_CONTENT),
        toolName = c.str(Db.COL_AS_TOOL),
        toolArgs = c.str(Db.COL_AS_ARGS),
        toolCallId = c.str(Db.COL_AS_CALLID),
        callsJson = c.str(Db.COL_AS_CALLS),
        ok = c.getInt(c.getColumnIndexOrThrow(Db.COL_AS_OK)) != 0,
        at = c.getLong(c.getColumnIndexOrThrow(Db.COL_AS_AT)),
        latencyMs = c.getLong(c.getColumnIndexOrThrow(Db.COL_AS_LATENCY)),
        providerId = c.str(Db.COL_AS_PROVIDER),
    )

    private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
}
