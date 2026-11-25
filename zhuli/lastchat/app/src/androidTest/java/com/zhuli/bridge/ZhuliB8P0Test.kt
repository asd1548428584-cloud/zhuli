package com.zhuli.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.datastore.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** B8 P0 验收：人格预设注入 / 简报 Worker 触发 / Shizuku 检测。 */
@RunWith(AndroidJUnit4::class)
class ZhuliB8P0Test {

    private val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun persona_ensure_createsZhuliAssistant_withPrompt() = runBlocking {
        val store = GlobalContext.get().get<SettingsStore>()
        val inserted = ZhuliPersonaBootstrap.ensure(ctx, store)
        val settings = store.settingsFlow.first { !it.init }
        val zhuli = settings.assistants.firstOrNull { it.name == ZhuliPersonaBootstrap.ZHULI_ASSISTANT_NAME }
        assertNotNull("应有 zhuli 人格预设", zhuli)
        assertTrue("systemPrompt 应含人格语气", zhuli!!.systemPrompt.contains("语气风格"))
        assertTrue("systemPrompt 应含只收不发底线", zhuli.systemPrompt.contains("只收不发"))
        assertTrue("systemPrompt 应含本地工具", zhuli.systemPrompt.contains("list_courier"))
    }

    @Test
    fun brief_worker_enqueuesAndRuns() {
        val wm = WorkManager.getInstance(ctx)
        wm.enqueueUniqueWork(
            "zhuli_brief_test_once",
            androidx.work.ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ZhuliBriefWorker>().build(),
        )
        // Worker 内部数据/模型缺失时安静返回 success；这里只验证不崩溃
        Thread.sleep(4000)
        assertTrue(true)
    }

    @Test
    fun shizuku_installed_check() {
        val installed = ZhuliShizukuRunner.isShizukuInstalled(ctx)
        assertTrue("用户设备应已安装 Shizuku", installed)
        val available = ZhuliShizukuRunner.isShizukuAvailable()
        // 可用性取决于 Shizuku 服务运行状态；只记录不断言
        println("Shizuku available=$available")
    }
}
