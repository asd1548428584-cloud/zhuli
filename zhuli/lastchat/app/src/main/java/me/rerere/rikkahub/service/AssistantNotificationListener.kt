package me.rerere.rikkahub.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.zhuli.core.CapturePrefs
import com.zhuli.core.NotificationCapture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

data class NotificationInfo(
    val id: Int,
    val packageName: String,
    val title: String,
    val content: String,
    val postTime: Long,
    val appLabel: String = ""
)

/**
 * 通知采集入口（v2，2026-11-24 重做）。
 *
 * 口径：
 * 1. 先过 [NotificationCapture] 的结构过滤（媒体/汇总/常驻/进度/通话）与正文解析；
 * 2. 再过 [CapturePrefs] 的白名单（默认只收微信/QQ/企业微信/钉钉/飞书/短信等）；
 * 3. 只有通过前两步的，才进"给模型看的通知流"和收件箱；
 * 4. 每一条的收/拦都写一条诊断，后台处理页可直接看到原因。
 *
 * 旧版的问题：媒体通知进了流被当成新消息；微信用 MessagingStyle/大文本时取不到正文被整条丢弃；
 * 只读 EXTRA_TEXT 一个字段，等于把"能不能收到"赌在对方 App 的实现细节上。
 */
class AssistantNotificationListener : NotificationListenerService() {

    companion object {
        private val _notifications = MutableStateFlow<List<NotificationInfo>>(emptyList())
        val notifications = _notifications.asStateFlow()

        /** 监听服务是否真的连上了（自检/后台处理页用；权限开了但服务没绑上是最常见的假绿勾）。 */
        private val _connected = MutableStateFlow(false)
        val connected = _connected.asStateFlow()

        private const val MAX_NOTIFICATIONS = 20

        /** 单线程写库：通知可能连发十几条，不能每条开一个线程抢数据库。 */
        private val io = Executors.newSingleThreadExecutor { r ->
            Thread(r, "zhuli-ingest").apply { isDaemon = true }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _connected.value = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        _connected.value = false
        // 系统会在内存紧张/省电策略下解绑监听服务，而权限仍然是"已授权"——
        // 用户看到的就是"权限开着但一条都收不到"。主动请求重绑，别等下次重启。
        runCatching {
            requestRebind(
                android.content.ComponentName(this, AssistantNotificationListener::class.java)
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val notification = sbn ?: return
        val ctx = applicationContext
        val pkg = notification.packageName
        if (pkg == packageName) return
        if (!CapturePrefs.enabled(ctx)) return

        val label = labelOf(pkg)
        val n = notification.notification
        val verdict = NotificationCapture.analyze(
            n = n,
            appLabel = label,
            allowMedia = CapturePrefs.allowMedia(ctx),
            allowOngoing = CapturePrefs.allowOngoing(ctx),
        )

        if (!verdict.ok) {
            val rawTitle = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val rawText = n.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
            CapturePrefs.recordBlocked(ctx, pkg, label, rawTitle, rawText, verdict.reason)
            return
        }

        if (!CapturePrefs.isAllowed(ctx, pkg)) {
            CapturePrefs.recordBlocked(ctx, pkg, label, verdict.title, verdict.text, "不在白名单")
            return
        }

        val info = NotificationInfo(
            id = notification.id,
            packageName = pkg,
            title = verdict.title,
            content = verdict.text,
            postTime = notification.postTime,
            appLabel = label,
        )
        _notifications.update { current ->
            (listOf(info) + current).take(MAX_NOTIFICATIONS)
        }

        ingestToZhuli(pkg, label, verdict.title, verdict.text, notification.postTime)
    }

    /** zhuli 提取管道入口（快递拦截 → 噪音闸门 → 落库 → 提取）。 */
    private fun ingestToZhuli(pkg: String, label: String, title: String, text: String, postTime: Long) {
        val app = applicationContext
        val rec = com.zhuli.core.InboxRecord(
            source = com.zhuli.core.InboxRecord.SOURCE_NOTIFICATION,
            packageName = pkg,
            appLabel = label,
            title = title.ifBlank { label },
            text = text,
            receivedAt = if (postTime > 0) postTime else System.currentTimeMillis(),
        )
        io.execute {
            try {
                val helper = com.zhuli.core.InboxDatabase(app)
                try {
                    if (com.zhuli.core.CourierIntake.intake(
                            ctx = app,
                            db = helper,
                            text = rec.text,
                            receivedAt = rec.receivedAt,
                            packageName = pkg,
                            appLabel = label,
                            source = com.zhuli.core.InboxRecord.SOURCE_NOTIFICATION,
                        )
                    ) {
                        CapturePrefs.recordCourier(app, pkg, label, rec.title, rec.text)
                        maybeExtract(app)
                        return@execute
                    }
                    val reason = com.zhuli.core.NoiseGuard.dropReason(helper, pkg, rec.title, rec.text)
                    if (reason != null) {
                        CapturePrefs.recordBlocked(app, pkg, label, rec.title, rec.text, reason)
                        return@execute
                    }
                    helper.insert(rec)
                    CapturePrefs.recordInserted(app, pkg, label, rec.title, rec.text)
                    maybeExtract(app)
                } finally {
                    runCatching { helper.close() }
                }
            } catch (t: Throwable) {
                android.util.Log.e("ZhuliIngest", "insert failed", t)
                CapturePrefs.recordBlocked(
                    app, pkg, label, title, text,
                    "入库失败：" + (t.message ?: t::class.java.simpleName),
                )
            }
        }
    }

    private fun maybeExtract(app: android.content.Context) {
        if (!CapturePrefs.autoExtract(app)) return
        com.zhuli.core.ExtractPipeline.trigger(app)
    }

    private fun labelOf(pkg: String): String = runCatching {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("")

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }
}
