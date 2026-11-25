package com.zhuli.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import com.zhuli.core.ZhuliLog as Log
import java.util.concurrent.Executors

/**
 * 快递入库（第 10 步）：短信 / App 推送 / 分享文本，三条通道共用一个解析与入库口。
 *
 * 隐私口径（和整个项目一致）：
 * - 解析全在本机，运单号、取件码不上云、不进 `cloud_log`；
 * - 短信只在**命中快递特征**时才落库一条摘要，其余短信**读都不读**（在 [SmsScan] 里先过 [Express.parse]）；
 * - 落库的条目和普通通知一样进收件箱，用户可删；类别固定「快递」，备注就是给提醒用的一行摘要。
 */
object CourierIntake {

    private const val TAG = "zhuli.courier"

    /** 短信来源的包名占位（不是真实 App，方便在列表里区分）。 */
    const val SOURCE_PACKAGE = "com.android.providers.telephony"
    const val SOURCE_LABEL = "快递短信"

    /**
     * 把一段文本当快递信息处理：解析成功就入库并（必要时）建取件提醒。
     * @return true = 已按快递入库
     */
    fun intake(
        ctx: Context,
        db: InboxDatabase,
        text: String,
        receivedAt: Long,
        packageName: String,
        appLabel: String,
        source: String = InboxRecord.SOURCE_NOTIFICATION
    ): Boolean {
        val info = Express.parse(text, receivedAt) ?: return false

        // 去重：同一驿站同一取件码 / 同一天同状态，只记一条（短信和推送常常说的是同一件事）
        val since = receivedAt - 3 * 24 * 60 * 60 * 1000L
        if (db.hasSameNoteSince(info.summary, since)) return true

        // 只有「还没拿到手 + 刚发生」的才建提醒；陈年旧短信只留记录，不炸通知
        val fresh = System.currentTimeMillis() - receivedAt < 12 * 60 * 60 * 1000L
        val due = if (fresh && info.actionable) Express.pickupDue(receivedAt) else 0L

        val rec = InboxRecord(
            source = source,
            packageName = packageName,
            appLabel = appLabel,
            title = "快递",
            text = text.trim().take(300),
            receivedAt = receivedAt,
            pendingExtract = 0,                  // 字段已经自己填好，不再过提取流水线
            category = InboxRecord.CAT_EXPRESS,
            deadlineAt = due,
            note = info.summary
        )
        val id = db.insert(rec)
        if (id <= 0) return true
        // 有金额的快递短信（到付/代收货款）顺手入账
        runCatching {
            val amount = ExtractPipeline.extract(text).amount
            if (amount > 0) Ledger.ingest(db, id, text, amount, receivedAt)
        }
        // 有取件时刻就立刻排程：分享/通知通道都在秒级完成，不能等下次开 App
        if (due > 0) ReminderEngine.scanAsync(ctx)
        Log.i(TAG, "intake ${info.status} station=${info.station} code=<redacted> due=$due")
        return true
    }

    // ==================== 短信通道 ====================

    fun hasSmsPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * 扫一遍最近 [days] 天的短信，只处理命中快递特征的。
     * **默认只在用户点了「扫快递短信」时跑**，不做后台常驻扫描（省电，也少碰隐私）。
     */
    fun scanSms(ctx: Context, days: Int = 7, max: Int = 20): Result {
        if (!hasSmsPermission(ctx)) return Result(0, 0, "没给短信权限")
        val app = ctx.applicationContext
        val since = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000L
        val db = InboxDatabase(app)
        var seen = 0
        var added = 0
        try {
            val cursor = app.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.BODY),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(since.toString()),
                "${Telephony.Sms.DATE} DESC"
            ) ?: return Result(0, 0, "读不到短信库")
            cursor.use { c ->
                val iAddr = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val iDate = c.getColumnIndex(Telephony.Sms.DATE)
                val iBody = c.getColumnIndex(Telephony.Sms.BODY)
                while (c.moveToNext() && added < max) {
                    val body = c.getString(iBody) ?: continue
                    val date = c.getLong(iDate)
                    val addr = c.getString(iAddr).orEmpty()
                    val info = Express.parse(body, date) ?: continue
                    seen++
                    // 已签收且超过 1 天的不再补记（历史上签收的没意义）
                    if (info.status == Express.STATUS_SIGNED &&
                        System.currentTimeMillis() - date > 24 * 60 * 60 * 1000L
                    ) continue
                    val label = if (info.carrier.isNotBlank()) "${info.carrier}短信" else SOURCE_LABEL
                    if (intake(app, db, body, date, SOURCE_PACKAGE, label, InboxRecord.SOURCE_NOTIFICATION)) {
                        added++
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "scan sms failed", t)
            return Result(seen, added, "扫短信出错：${t.message?.take(40)}")
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
        if (added > 0) ReminderEngine.scanAsync(app)
        return Result(seen, added, "")
    }

    fun scanSmsAsync(ctx: Context, days: Int = 7, onDone: (Result) -> Unit) {
        val app = ctx.applicationContext
        Executors.newSingleThreadExecutor().execute {
            val r = scanSms(app, days)
            onDone(r)
        }
    }

    data class Result(val matched: Int, val added: Int, val error: String)
}
