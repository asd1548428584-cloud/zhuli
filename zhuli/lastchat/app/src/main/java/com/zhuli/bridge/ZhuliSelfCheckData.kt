package com.zhuli.bridge

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import com.zhuli.core.CapturePrefs
import com.zhuli.core.InboxDatabase
import me.rerere.rikkahub.service.AssistantNotificationListener
import me.rerere.rikkahub.utils.PermissionChecker
import java.util.Calendar

/**
 * 自检面板取值层（新壳专用，2026-11-24 改为读真实数据）。
 *
 * 上一版的致命问题：`lastAt = 0L` 是写死的，注释还写着"新壳不发通知到收件箱"——
 * 但采集代码早就会入库了。于是不管收了多少条，自检永远显示"还没有收到过任何通知"。
 * 这种"假红"和假绿勾一样有害：用户会以为链路断了，实际只是这一行没接库。
 *
 * 现在每一行都从真实来源取：InboxDatabase 的统计、监听服务的连接状态、隐私闸门开关。
 */
object ZhuliSelfCheckData {

    /** 生命状态标签。 */
    data class Health(
        val privacyGateActive: Boolean = false,
        val privacyGateEnabled: Boolean = true,
        val cloudReady: Boolean = false,
        val listenerConnected: Boolean = false,
    )

    /** 一次性读齐自检需要的计数（只开一次库）。 */
    data class Snapshot(
        val lastIngestAt: Long = 0L,
        val today: Int = 0,
        val total: Int = 0,
        val sources: Int = 0,
        val muted: Int = 0,
        val pending: Int = 0,
        val scheduled: Int = 0,
        val nextAlarmAt: Long = 0L,
        val cloudCount: Int = 0,
        val lastCloudAt: Long = 0L,
    )

    fun snapshot(context: Context): Snapshot {
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return try {
            val db = InboxDatabase(context.applicationContext)
            try {
                val reminders = runCatching { db.scheduledReminders(200) }.getOrDefault(emptyList())
                val cloud = runCatching { db.recentCloudLog(1) }.getOrDefault(emptyList())
                Snapshot(
                    lastIngestAt = db.lastIngestAt(),
                    today = db.countSince(startOfToday),
                    total = db.totalCount(),
                    sources = db.distinctSourceCount(),
                    muted = db.mutedSourceCount(),
                    pending = db.pendingCount(),
                    scheduled = reminders.size,
                    nextAlarmAt = reminders.minOfOrNull { it.dueAt } ?: 0L,
                    cloudCount = runCatching { db.cloudLogCount() }.getOrDefault(0),
                    lastCloudAt = cloud.firstOrNull()?.firstOrNull()?.toLongOrNull() ?: 0L,
                )
            } finally {
                runCatching { db.close() }
            }
        } catch (t: Throwable) {
            Snapshot()
        }
    }

    fun health(context: Context): Health = Health(
        privacyGateActive = ZhuliPrivacyGate.isActive(),
        privacyGateEnabled = com.zhuli.core.AssistantPrefs.privacyGateEnabled(context),
        cloudReady = com.zhuli.core.AssistantPrefs.cloudReady(context),
        listenerConnected = AssistantNotificationListener.connected.value,
    )

    /**
     * 生成自检 Item 列表。
     */
    fun collect(context: Context, health: Health, snap: Snapshot = snapshot(context)): List<ZhuliSelfCheck.Item> {
        val now = System.currentTimeMillis()
        val items = mutableListOf<ZhuliSelfCheck.Item>()

        // ① 采集
        val notifAccess = PermissionChecker.hasNotificationListenerAccess(context)
        items += ZhuliSelfCheck.ingest(lastAt = snap.lastIngestAt, now = now, hasAccess = notifAccess)
        items += ZhuliSelfCheck.listener(hasAccess = notifAccess, connected = health.listenerConnected)
        items += ZhuliSelfCheck.todayCount(snap.today, snap.total)
        items += ZhuliSelfCheck.sources(snap.sources, snap.muted)

        // ② 知道什么
        items += ZhuliSelfCheck.knowledge(snap.total, snap.pending, snap.sources)

        // ③ 提醒
        items += ZhuliSelfCheck.alarms(snap.scheduled, snap.nextAlarmAt, now)

        // ④ 权限与保活
        val notifEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        items += ZhuliSelfCheck.selfNotify(notifEnabled)
        val smsGranted = context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED
        items += ZhuliSelfCheck.sms(smsGranted)

        // ⑤ 出网 + 隐私闸门
        val cloudReady = health.cloudReady || snap.cloudCount > 0
        items += ZhuliSelfCheck.cloud(snap.cloudCount, snap.lastCloudAt, now, cloudReady)
        val privacyRows = privacyRows()
        val allPrivacyOk = privacyRows.all { it.allOk }
        items += ZhuliSelfCheck.Item(
            group = ZhuliSelfCheck.G_CLOUD,
            label = "隐私闸门",
            value = when {
                !health.privacyGateEnabled -> "已关闭 —— 所有内容原样出网（点下面开关可恢复）"
                health.privacyGateActive && allPrivacyOk ->
                    "已接线（chat/responses/embeddings + 工具参数），${privacyRows.size} 条规则自检全过"

                health.privacyGateActive -> "已接线，但有 ${privacyRows.count { !it.allOk }} 条规则没过"
                else -> "拦截器未生效 —— 开关是开的，但请求没走到闸门（重启一次 App）"
            },
            level = when {
                !health.privacyGateEnabled -> ZhuliSelfCheck.Level.BAD
                health.privacyGateActive && allPrivacyOk -> ZhuliSelfCheck.Level.OK
                else -> ZhuliSelfCheck.Level.WARN
            },
        )

        // ⑥ 怎么纠正
        items += ZhuliSelfCheck.fixHint(snap.muted)

        return items
    }

    /**
     * 隐私三层自检明细（给面板展开看每一条）。
     *
     * 这里固定 `cloudReady = true`：测的是「规则能不能拦住内容」，
     * 不是「用户配没配 Key」；用真实的 Key 状态会让没配 Key 的用户看到一片
     * 「留本地」而误以为规则都通过了。端点用示例域名，不会产生任何网络请求。
     */
    fun privacyRows(): List<ZhuliPrivacy.SelfCheck> = ZhuliPrivacy.selfTest3(
        ZhuliPrivacy.Config(cloudReady = true, baseUrl = "https://example.com/v1")
    )

    /** 采集策略摘要（首页/设置页抬头用）。 */
    fun captureSummary(ctx: Context): String {
        if (!CapturePrefs.enabled(ctx)) return "采集已暂停"
        if (!CapturePrefs.whitelistEnabled(ctx)) return "收全部应用（白名单已关）"
        val n = CapturePrefs.allowedPackages(ctx).size
        return "白名单 $n 个应用"
    }
}
