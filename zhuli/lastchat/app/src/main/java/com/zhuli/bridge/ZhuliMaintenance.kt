package com.zhuli.bridge

import android.content.Context
import com.zhuli.core.CalendarWriter
import com.zhuli.core.CapturePrefs
import com.zhuli.core.InboxDatabase
import com.zhuli.core.ReminderEngine
import com.zhuli.core.ReminderNotifier

/**
 * 数据维护（2026-11-24）。
 *
 * 背景：上一版采集没有白名单，一上午就收进来 653 条，其中 iKuuu 397 + 酷狗 187 + B 站 39。
 * 加了白名单只拦"以后"，已经躺在库里的噪声仍然会污染检索、今日简报和"最近入库"。
 * 这里提供一次性的定向清理：只删**不在白名单里**的 App 的记录，分享/链接/账本不碰。
 *
 * 注意：这里**不静音**。用户以后把某个 App 重新加回白名单时，它应该立刻能进来，
 * 不能因为清理过一次就永久被 SourceRules 的静音标记拦住。
 */
object ZhuliMaintenance {

    data class PurgeResult(
        val packages: Int = 0,
        val rows: Int = 0,
        val failed: Int = 0,
    )

    /** 白名单外的历史条数（给首页/设置页显示，不删任何东西）。 */
    fun countOutsideWhitelist(context: Context): Int {
        if (!CapturePrefs.whitelistEnabled(context)) return 0
        return try {
            val allowed = CapturePrefs.allowedPackages(context)
            val db = InboxDatabase(context.applicationContext)
            try {
                db.topSources(200).filter { it.pkg !in allowed }.sumOf { it.count }
            } finally {
                runCatching { db.close() }
            }
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * 删掉白名单外来源的全部收件箱记录，并撤掉挂在它们上面的闹钟/日历事件。
     * 账本保留（和之前"静音并清理"的口径一致：财务记录不靠 inbox 存活）。
     */
    fun purgeOutsideWhitelist(context: Context): PurgeResult {
        if (!CapturePrefs.whitelistEnabled(context)) return PurgeResult()
        val allowed = CapturePrefs.allowedPackages(context)
        return try {
            val db = InboxDatabase(context.applicationContext)
            try {
                var pkgs = 0
                var rows = 0
                var failed = 0
                db.topSources(200)
                    .map { it.pkg }
                    .filter { it.isNotBlank() && it !in allowed }
                    .forEach { pkg ->
                        try {
                            val ids = db.inboxIdsOfPackage(pkg)
                            if (ids.isEmpty()) return@forEach
                            for (rem in db.remindersByInboxIds(ids)) {
                                runCatching { ReminderEngine.cancelAlarm(context, rem.id) }
                                runCatching { ReminderNotifier.cancel(context, rem.id) }
                                if (rem.calendarEventId > 0) {
                                    runCatching { CalendarWriter.remove(context, rem) }
                                }
                            }
                            val n = db.deleteRowsOfPackage(pkg)
                            if (n >= 0) {
                                pkgs++
                                rows += n
                            } else {
                                failed++
                            }
                        } catch (_: Throwable) {
                            failed++
                        }
                    }
                PurgeResult(pkgs, rows, failed)
            } finally {
                runCatching { db.close() }
            }
        } catch (_: Throwable) {
            PurgeResult(failed = 1)
        }
    }
}
