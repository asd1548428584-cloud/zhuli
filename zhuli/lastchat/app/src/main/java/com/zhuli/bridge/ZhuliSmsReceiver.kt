package com.zhuli.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.zhuli.core.CourierIntake
import com.zhuli.core.InboxDatabase
import com.zhuli.core.ZhuliLog as Log
import java.util.concurrent.Executors

/**
 * 短信 → 快递入库（B8 补：B7 迁移后这条链路整段丢了）。
 *
 * 只为「取件码」存在：
 * - 收到短信不落库、不留原文，先过 [CourierIntake.intake] 里的 Express.parse；
 * - 不是快递短信 → 直接丢弃，读都不读第二遍（和通知通道同一套隐私口径）；
 * - 是快递短信 → 只落一行摘要 + 取件码（脱敏），并立刻排取件提醒。
 *
 * 依赖 READ_SMS/RECEIVE_SMS（RECEIVE 是普通权限，READ 是为了"扫历史"和自检显示真实状态）。
 */
class ZhuliSmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "zhuli.sms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }
            .getOrNull()
            ?.filter { !it.messageBody.isNullOrBlank() }
            ?: return
        if (messages.isEmpty()) return

        val app = context.applicationContext
        val pending = goAsync()
        Executors.newSingleThreadExecutor().execute {
            try {
                // 长短信会拆成多条，按发信人合并再解析，否则取件码会被截断
                messages.groupBy { it.originatingAddress.orEmpty() }.forEach { (_, parts) ->
                    val body = parts.joinToString("") { it.messageBody.orEmpty() }
                    val at = parts.maxOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()
                    val label = CourierIntake.SOURCE_LABEL
                    val db = InboxDatabase(app)
                    try {
                        CourierIntake.intake(
                            ctx = app,
                            db = db,
                            text = body,
                            receivedAt = at,
                            packageName = CourierIntake.SOURCE_PACKAGE,
                            appLabel = label,
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "sms intake failed", t)
                    } finally {
                        runCatching { db.close() }
                    }
                }
            } finally {
                runCatching { pending.finish() }
            }
        }
    }
}
