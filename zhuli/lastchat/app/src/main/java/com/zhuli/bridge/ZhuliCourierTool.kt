package com.zhuli.bridge

//
// T-G1 数据桥（方案 A）的 LastChat 侧完整实现。
//
// 设计要点：不把 zhuli 的 InboxDatabase（SQLiteOpenHelper + Context + 私有库路径）搬进 LastChat，
// 而是把真机导出的 inbox 库快照放进 LastChat 的 assets，用一个自包含的只读 Tool 查询它。
// 这样绕开「两个 App 私有库互读不到」和「裁剪 InboxDatabase」两个难题，是 Demo 最小可跑通路径。
//
// 依赖：LastChat 的 kotlinx.serialization + android.database.sqlite（app 模块已具备）。
// 单测覆盖：查询口径 = ZhuliToolSpecs/CourierAnswer（纯 JVM，已验证真库取件码）。
//

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

/**
 * 从 assets 里的 zhuli 库快照只读查询快递。
 *
 * 接线（LastChat app/src/main/java/me/rerere/rikkahub/data/ai/tools/LocalTools.kt:getTools()）：
 *   val zhuliCourier = ZhuliCourierTool(context = appContext, assetName = "zhuli_inbox_snapshot.db")
 *   tools.add(zhuliCourier.asTool())
 *
 * assets 放置：把真机导出的 zhuli_inbox_pre_v14_20261121.db 复制为
 *   app/src/main/assets/zhuli_inbox_snapshot.db（首次运行由 [openOrCopy] 落到 filesDir 只读打开）。
 */
class ZhuliCourierTool(
    private val context: Context,
    private val assetName: String = "zhuli_inbox_snapshot.db",
) {
    /** 把 assets 快照复制到 filesDir（仅首次），返回可只读打开的路径。 */
    private fun openOrCopy(): SQLiteDatabase? = try {
        val dest = java.io.File(context.filesDir, assetName)
        if (!dest.exists()) {
            context.assets.open(assetName).use { input ->
                java.io.FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
        }
        SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
    } catch (t: Throwable) {
        null
    }

    /**
     * 查询近 14 天快递记录。口径与主项目 [com.zhuli.core.Tools.pickCourierRows] 一致：
     * category='快递'、received_at >= now-14d、含"签收"单列、有 deadline 的排前、按到站倒序。
     */
    fun queryCourier(now: Long, windowDays: Int = 14): List<CourierAnswer.Row> {
        val since = now - windowDays * 86_400_000L
        // B7 全量迁移：直接查 fork 本地 zhuli_inbox.db
        val sql = "SELECT note, text, received_at, deadline_at FROM inbox " +
            "WHERE category = ? AND received_at >= ? ORDER BY received_at DESC"
        val rows = mutableListOf<CourierAnswer.Row>()
        runCatching {
            ZhuliLocalStore.rawQuery(context, sql, arrayOf("快递", since)) { c ->
                while (c.moveToNext()) {
                    rows += CourierAnswer.Row(
                        note = c.getString(c.getColumnIndexOrThrow("note")) ?: "",
                        text = c.getString(c.getColumnIndexOrThrow("text")) ?: "",
                        receivedAt = c.getLong(c.getColumnIndexOrThrow("received_at")),
                        deadlineAt = c.getLong(c.getColumnIndexOrThrow("deadline_at")),
                    )
                }
            }
        }
        return rows
    }

    /** 装配成 LastChat 的 list_courier Tool。execute 复用已验证的 CourierAnswer 纯函数。 */
    fun asTool(): Tool = Tool(
        name = "list_courier",
        description = "查用户手机上近 14 天到站的快递（驿站、取件码、到站时间）。问快递到了没、要取什么、我快递到哪了时用。数据来自本地记录，不联网。",
        systemPrompt = { model, _ ->
            if (com.zhuli.bridge.ZhuliLocalToolBridge.isLocalModel(model.modelId)) com.zhuli.bridge.zhuliLocalToolsPrompt() else ""
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject { },
                required = null,
            )
        },
        execute = {
            val rows = queryCourier(System.currentTimeMillis())
            val result = CourierAnswer.answer(rows, System.currentTimeMillis())
            buildJsonObject { put("result", JsonPrimitive(result)) }
        }
    )
}
