package com.zhuli.bridge

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * 只读快照库的共享打开器（T-G2）。
 *
 * 与 T-G1 的 ZhuliCourierTool 同策略：assets 里的真库快照首次运行复制到 filesDir，
 * 之后用 OPEN_READONLY 打开。三个新 Tool 共用这一个文件，避免各复制一份。
 * 注意：ZhuliCourierTool 自带一份等价实现（已真机验证），本类供新 Tool 使用；
 * 两者读的是同一个 dest 文件，互不干扰（各自 open/close）。
 */
internal class ZhuliSnapshotDb(
    private val context: Context,
    private val assetName: String = "zhuli_inbox_snapshot.db",
) {
    fun open(): SQLiteDatabase? = try {
        val dest = java.io.File(context.filesDir, assetName)
        if (!dest.exists()) copyAsset(dest)
        var db = SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        // T-G2 修正：真机上 T-G1 残留的旧库可能缺 reminders 等表（user_version=0、空表），
        // 大小却与 assets 相同，靠文件大小判不出来。这里校验必需表，缺表就删除重拷。
        if (!hasRequiredTables(db)) {
            db.close()
            dest.delete()
            copyAsset(dest)
            db = SQLiteDatabase.openDatabase(dest.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }
        db
    } catch (t: Throwable) {
        null
    }

    private fun copyAsset(dest: java.io.File) {
        context.assets.open(assetName).use { input ->
            java.io.FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
    }

    private fun hasRequiredTables(db: SQLiteDatabase): Boolean = try {
        db.rawQuery(
            "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('inbox','reminders','ledger','embeddings')",
            null
        ).use { c -> c.moveToFirst() && c.getInt(0) == 4 }
    } catch (_: Throwable) { false }
}

/** 从 BLOB 读回 256 维 float 向量（little-endian），与 InboxDatabase.putEmbedding 对称。 */
internal fun blobToVec(blob: ByteArray): FloatArray {
    val bb = java.nio.ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val arr = FloatArray(blob.size / 4)
    for (i in arr.indices) arr[i] = bb.float
    return arr
}
