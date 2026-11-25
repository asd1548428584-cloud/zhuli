package com.zhuli.bridge

import android.content.Context
import com.zhuli.core.InboxDatabase
import java.io.File

/**
 * T-G2/B7 全量迁移：fork 自持的 zhuli 数据库（自采自存自查）。
 *
 * - 首次运行把 assets 快照（旧壳真库导出，v14）落到 filesDir 作为初始数据，
 *   旧壳删除后历史数据仍在；
 * - 之后由新壳自己的通知监听（AssistantNotificationListener→提取管道）持续写入；
 * - InboxDatabase 的 DB_NAME 就是 zhuli_inbox.db（v14），直接用 helper 读写。
 */
object ZhuliLocalStore {

    fun db(context: Context): InboxDatabase {
        seedIfNeed(context)
        return InboxDatabase(context)
    }

    /**
     * 把 WAL 里的改动并回主库文件（备份/迁移前调用）。
     * 不 checkpoint 直接拷 .db，会丢最近几秒的写入。
     */
    fun checkpoint(context: Context) {
        runCatching {
            db(context).writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
        }
    }

    /** 只读 rawQuery 快捷入口（Tool 查询用）。 */
    fun rawQuery(context: Context, sql: String, args: Array<Any>?, block: (android.database.Cursor) -> Unit) {
        val helper = db(context)
        try {
            helper.readableDatabase.rawQuery(
                sql,
                args?.map { it.toString() }?.toTypedArray(),
            ).use { c -> block(c) }
        } finally {
            runCatching { helper.close() }
        }
    }

    /**
     * 首次使用时的"预置数据"。
     *
     * ⚠️ 2026-11-22 清场时发现：这里原来会把 assets 里的 `zhuli_inbox_snapshot.db`
     * （B7 迁移时从旧壳导出的**真实**收件箱快照：快递记录、账本、待办）复制成活动库。
     * 结果是每装一次 App 都带着我个人的测试数据上线，卸载 + pm clear 都清不干净。
     * 该快照已删除，这里不再播种任何数据 —— 新用户必须是真正的空库。
     */
    private fun seedIfNeed(context: Context) {
        // 故意留空：不预置任何数据。
        // 若将来要做"演示数据"，请用虚构的 demo 库，且必须由用户在设置里显式开启。
    }
}
