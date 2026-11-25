package com.zhuli.bridge

//
// T-G2：把 zhuli 的三个只读查询能力接进 LastChat 对话层。
// 与 T-G1 的 list_courier 同形态：自包含 Tool，只读 assets 快照，不联网、不写库。
// 回答口径全部在 ZhuliQueryAnswer（纯 JVM，已用真库 fixture 单测），本文件只做"取数 + 装配"。
//

import android.content.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

class ZhuliQueryTools(private val context: Context) {

    /**
     * 统一查询执行（B7 全量迁移）：直接查 fork 本地 zhuli_inbox.db（ZhuliLocalStore 自持）。
     * 旧壳已退役，快照/live 桥不再使用。
     */
    private fun <T> execQuery(sql: String, args: Array<Any>?, block: (android.database.Cursor) -> T): T? {
        return runCatching {
            var result: T? = null
            ZhuliLocalStore.rawQuery(context, sql, args) { c -> result = block(c) }
            result
        }.getOrNull()
    }

    /** 工具参数里取整数，钳制到 [lo, hi]，缺省 default。 */
    private fun JsonElement?.intArg(default: Int, lo: Int, hi: Int): Int {
        if (this == null) return default
        return try {
            val n = (this as? JsonPrimitive)?.content?.toIntOrNull()
            (n ?: default).coerceIn(lo, hi)
        } catch (_: Throwable) { default }
    }

    private fun JsonElement?.strArg(): String = try {
        (this as? JsonPrimitive)?.contentOrNull ?: ""
    } catch (_: Throwable) { "" }

    // ---------------- list_todo ----------------

    fun queryTodo(limit: Int = 20): List<ZhuliQueryAnswer.TodoRow> {
        // 真库事实：待办在 reminders 表（pending/scheduled），主项目查 inbox.deadline_at>now 在真库上为空。
        val rows = execQuery<List<ZhuliQueryAnswer.TodoRow>>(
            "SELECT due_at, title, status, confidence, IFNULL(basis,'') AS basis FROM reminders " +
                "WHERE status IN ('pending','scheduled') AND due_at > 0 " +
                "ORDER BY due_at ASC LIMIT ?",
            arrayOf(limit),
        ) { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ZhuliQueryAnswer.TodoRow(
                            dueAt = c.getLong(c.getColumnIndexOrThrow("due_at")),
                            title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
                            status = c.getString(c.getColumnIndexOrThrow("status")) ?: "",
                            confidence = c.getString(c.getColumnIndexOrThrow("confidence")) ?: "",
                            basis = c.getString(c.getColumnIndexOrThrow("basis")) ?: "",
                        )
                    )
                }
            }
        }
        return rows ?: emptyList()
    }

    fun listTodoTool(): Tool = Tool(
        name = "list_todo",
        systemPrompt = { model, _ ->
            if (com.zhuli.bridge.ZhuliLocalToolBridge.isLocalModel(model.modelId)) com.zhuli.bridge.zhuliLocalToolsPrompt() else ""
        },
        description = "列出用户还没完成的待办/提醒（含时间来源不确定的）。问'接下来有什么事''有什么安排''我定的提醒'时用。数据来自本地记录，不联网。",
        parameters = { InputSchema.Obj(properties = buildJsonObject {}, required = null) },
        execute = {
            val now = System.currentTimeMillis()
            val rows = queryTodo()
            buildJsonObject { put("result", JsonPrimitive(ZhuliQueryAnswer.todo(rows, now))) }
        }
    )

    // ---------------- ledger_summary ----------------

    fun queryLedger(days: Int, now: Long): List<ZhuliQueryAnswer.LedgerGroup> {
        val from = now - days * 86_400_000L
        val rows = execQuery<List<ZhuliQueryAnswer.LedgerGroup>>(
            "SELECT dir, category, COUNT(*), IFNULL(SUM(amount),0) FROM ledger " +
                "WHERE occurred_at BETWEEN ? AND ? GROUP BY dir, category ORDER BY SUM(amount) DESC",
            arrayOf(from, now),
        ) { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ZhuliQueryAnswer.LedgerGroup(
                            dir = c.getString(0) ?: "",
                            category = c.getString(1) ?: "",
                            count = c.getInt(2),
                            amount = c.getDouble(3),
                        )
                    )
                }
            }
        }
        return rows ?: emptyList()
    }

    fun ledgerTool(): Tool = Tool(
        name = "ledger_summary",
        systemPrompt = { model, _ ->
            if (com.zhuli.bridge.ZhuliLocalToolBridge.isLocalModel(model.modelId)) com.zhuli.bridge.zhuliLocalToolsPrompt() else ""
        },
        description = "查账本：支出/收入合计与分类明细。问花了多少钱、这个月开销时用。数据来自本地记录，不联网。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("days", buildJsonObject {
                        put("type", "integer")
                        put("description", "统计最近多少天，默认 7")
                    })
                },
                required = null,
            )
        },
        execute = { args ->
            val days = args.intArg(7, 1, 365)
            val groups = queryLedger(days, System.currentTimeMillis())
            buildJsonObject { put("result", JsonPrimitive(ZhuliQueryAnswer.ledger(groups, days))) }
        }
    )

    // ---------------- search_memory ----------------

    fun querySearch(query: String, days: Int, now: Long, limit: Int = 6): List<ZhuliQueryAnswer.MemRow> {
        val from = now - days * 86_400_000L
        // 候选：时间窗内最近 400 条（与 VectorSearch.search 一致）
        val candidates = execQuery<List<ZhuliQueryAnswer.MemRow>>(
            "SELECT id, title, text, note, app_label, source, received_at FROM inbox " +
                "WHERE received_at BETWEEN ? AND ? ORDER BY received_at DESC LIMIT 400",
            arrayOf(from, now),
        ) { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ZhuliQueryAnswer.MemRow(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
                            text = c.getString(c.getColumnIndexOrThrow("text")) ?: "",
                            note = c.getString(c.getColumnIndexOrThrow("note")) ?: "",
                            appLabel = c.getString(c.getColumnIndexOrThrow("app_label")) ?: "",
                            source = c.getString(c.getColumnIndexOrThrow("source")) ?: "",
                            receivedAt = c.getLong(c.getColumnIndexOrThrow("received_at")),
                        )
                    )
                }
            }
        } ?: return emptyList()
        // 向量：时间窗内已索引的 (inboxId, vec)
        val vecMap = HashMap<Long, FloatArray>()
        execQuery<Unit>(
            "SELECT e.inbox_id, e.vec FROM embeddings e JOIN inbox i ON i.id = e.inbox_id " +
                "WHERE i.received_at BETWEEN ? AND ? ORDER BY i.received_at DESC LIMIT 400",
            arrayOf(from, now),
        ) { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(1) ?: continue
                vecMap[c.getLong(0)] = blobToVec(blob)
            }
        }
        return ZhuliQueryAnswer.searchMemory(candidates, vecMap, query, limit)
    }

    fun searchTool(): Tool = Tool(
        name = "search_memory",
        systemPrompt = { model, _ ->
            if (com.zhuli.bridge.ZhuliLocalToolBridge.isLocalModel(model.modelId)) com.zhuli.bridge.zhuliLocalToolsPrompt() else ""
        },
        description = "在用户手机的记忆库里检索（消息、通知、链接摘要、账单）。用户问过去发生过什么、谁说过什么时用。数据来自本地记录，不联网。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "关键词，2-6 个字最有效")
                    })
                    put("days", buildJsonObject {
                        put("type", "integer")
                        put("description", "回看天数，默认 7")
                    })
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val query = args.strArg()
            if (query.isBlank()) {
                buildJsonObject { put("result", JsonPrimitive("（没给关键词）")) }
            } else {
                val days = args.intArg(7, 1, 365)
                val hits = querySearch(query, days, System.currentTimeMillis())
                buildJsonObject { put("result", JsonPrimitive(ZhuliQueryAnswer.searchAnswer(hits, query, days))) }
            }
        }
    )

    /** 一次性拿到全部三个 Tool。 */
    fun allTools(): List<Tool> = listOf(searchTool(), listTodoTool(), ledgerTool(), createReminderTool())

    /**
     * T-G2/B4：写操作 Tool（LastChat 缺系统闹钟，此为最小闭环）。
     * 双保险：Tool 层 RequiresApproval（LastChat 原生审批 UI）+
     * execute 内过 [ZhuliActionConfirm]（三档确认 + 硬禁止名单，B3 移植）。
     */
    fun createReminderTool(): Tool = Tool(
        name = "create_reminder",
        description = "给用户设一条系统提醒（到点弹通知）。用户说'提醒我…'、'X点叫我…'时用。需要确认。",
        systemPrompt = { model, _ ->
            if (com.zhuli.bridge.ZhuliLocalToolBridge.isLocalModel(model.modelId))
                com.zhuli.bridge.zhuliLocalToolsPrompt() + "\ncreate_reminder：设系统提醒（弹通知）。用户说提醒我做某事时用，须给未来时刻。"
            else ""
        },
        approvalMode = me.rerere.ai.core.ToolApprovalMode.RequiresApproval,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("title", buildJsonObject {
                        put("type", "string")
                        put("description", "提醒内容，如：取快递")
                    })
                    put("due_at_epoch_ms", buildJsonObject {
                        put("type", "integer")
                        put("description", "提醒时刻的毫秒时间戳（UTC epoch），必须是未来时刻")
                    })
                },
                required = listOf("title", "due_at_epoch_ms"),
            )
        },
        execute = { args ->
            val obj = args as? kotlinx.serialization.json.JsonObject
            val title = obj?.get("title").strArg().ifBlank { "提醒" }
            // epoch 毫秒远超 Int 范围，直接按 long 解析
            val dueMs = obj?.get("due_at_epoch_ms")?.let {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
            } ?: 0L
            if (dueMs <= 0) {
                buildJsonObject { put("result", JsonPrimitive("缺参数：due_at_epoch_ms（毫秒时间戳）")) }
            } else {
                // B3 三档确认：硬禁止名单 + 本轮允许口径（无 UI 时安全拒绝）
                val allowed = ZhuliActionConfirm.ask(
                    op = ZhuliActionGate.OP_WRITE_REMINDER,
                    label = "设系统提醒：$title",
                )
                if (!allowed) {
                    buildJsonObject { put("result", JsonPrimitive("提醒未设置：需用户在设置里确认授权（或命中禁止名单）")) }
                } else {
                    val reqCode = (title.hashCode() and 0x7FFFFFFF) % 100000 + 1
                    val r = ZhuliReminderScheduler.schedule(context, reqCode, title, dueMs)
                    buildJsonObject { put("result", JsonPrimitive(if (r.ok) r.message else "失败：${r.message}")) }
                }
            }
        }
    )
}
