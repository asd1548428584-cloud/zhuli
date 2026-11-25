package com.zhuli.bridge

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.WebDavConfig
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.sync.WebDavClientFactory
import me.rerere.rikkahub.data.sync.WebdavSync
import me.rerere.rikkahub.data.datastore.SecretKeyManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File
import java.util.zip.ZipFile

/**
 * P1-2 / P1-3 真机验收：
 * 1. 提醒到点通知必须带「完成 / 稍后 10 分钟 / 撤销」三个动作（否则用户只能点进 App）；
 * 2. 备份包必须包含 zhuli 自持库 zhuli_inbox.db（否则恢复后快递/待办/账本全丢）。
 *
 * 提醒用 65 秒延迟：星云OS 对短延迟 alarm 会做 policy 对齐（8s 请求实测晚 ~30s），
 * 太短的窗口必失败，这是上一轮踩过的坑。
 */
@RunWith(AndroidJUnit4::class)
class ZhuliReminderBackupTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun reminderNotification_hasThreeActions() {
        val id = 88_001
        val title = "测试：下午三点开会"
        val due = System.currentTimeMillis() + 65_000L
        val scheduled = ZhuliReminderScheduler.schedule(ctx, id, title, due)
        assertTrue("排程应成功：${scheduled.message}", scheduled.ok)

        // 等到点
        Thread.sleep(75_000)

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var found: android.app.Notification? = null
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            found = nm.activeNotifications
                .firstOrNull { it.tag == ZhuliAlarmReceiver.NOTIFICATION_TAG && it.id == id }
                ?.notification
            if (found != null) break
            Thread.sleep(500)
        }
        assertNotNull("到点应弹出提醒通知", found)
        val actions = found!!.actions.orEmpty()
        val labels = actions.map { it.title?.toString().orEmpty() }
        assertEquals("通知应带 3 个动作，实际 $labels", 3, actions.size)
        assertTrue("应有「完成」，实际 $labels", labels.any { it.contains("完成") })
        assertTrue("应有「稍后」，实际 $labels", labels.any { it.contains("稍后") })
        assertTrue("应有「撤销」，实际 $labels", labels.any { it.contains("撤销") })
        nm.cancel(ZhuliAlarmReceiver.NOTIFICATION_TAG, id)
    }

    @Test
    fun backupArchive_containsZhuliInboxDb(): Unit = runBlocking {
        val koin = GlobalContext.get()
        val sync = WebdavSync(
            settingsStore = koin.get<SettingsStore>(),
            json = koin.get(),
            context = ctx,
            secretKeyManager = koin.get(),
            appDatabase = koin.get<AppDatabase>(),
            webDavClientFactory = koin.get<WebDavClientFactory>(),
        )
        val file: File = sync.prepareBackupFile(WebDavConfig())
        assertTrue("应产出备份文件", file.exists() && file.length() > 0)
        val entries = ZipFile(file).use { zip -> zip.entries().toList().map { it.name } }
        println("[zhuli] backup entries=${entries.take(12)}")
        assertTrue("备份应含主库 rikka_hub.db，实际 $entries", entries.contains("rikka_hub.db"))
        assertTrue("备份应含 zhuli_inbox.db，实际 $entries", entries.contains("zhuli_inbox.db"))
        file.delete()
        Unit
    }
}
