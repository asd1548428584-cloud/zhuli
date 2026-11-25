package com.zhuli.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.service.SpontaneousWorker
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Duration

/**
 * T-G2/B8 P0-3：晨间简报（C3）——复用上游 Worker + 通知模式。
 *
 * 每天 07:30 前后（PeriodicWork 24h，initialDelay 对齐下个 07:30）运行：
 * 直接查 zhuli 本地库（待办/账本/快递）拼进 prompt → 云模型生成简报 → 通知栏推送。
 * 简报数据本地取、生成走配置模型；不做会话写入（通知即简报，点开进 App 对话追问）。
 * 开关：com.zhuli.core.AssistantPrefs.briefEnabled（默认开）。
 */
class ZhuliBriefWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    companion object {
        const val UNIQUE_WORK_NAME = "zhuli_daily_brief"
        const val CHANNEL_ID = "zhuli_brief"
        const val BRIEF_HOUR = 7
        const val BRIEF_MINUTE = 30

        /** 距下一个 07:30 的延迟（用于 PeriodicWork initialDelay 对齐）。 */
        fun initialDelayToNextBrief(now: LocalDateTime = LocalDateTime.now()): Duration {
            var next = now.toLocalDate().atTime(LocalTime.of(BRIEF_HOUR, BRIEF_MINUTE))
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next)
        }
    }

    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore by inject()
    private val providerManager: me.rerere.ai.provider.ProviderManager by inject()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!com.zhuli.core.AssistantPrefs.briefEnabled(applicationContext)) {
            return@withContext Result.success()
        }
        val settings = try {
            settingsStore.settingsFlow.first { !it.init }
        } catch (e: Exception) {
            return@withContext Result.retry()
        }
        runCatching {
            process(settings)
            Result.success()
        }.getOrElse {
            ZhuliBriefStore.saveNote(applicationContext, "生成失败：${it.message ?: it::class.java.simpleName}")
            Result.success()
        }
    }

    private suspend fun process(settings: Settings) {
        if (!canPostNotifications()) {
            ZhuliBriefStore.saveNote(applicationContext, "本应用通知被关了，简报发不出去")
            return
        }

        val assistant = settings.assistants.firstOrNull { it.name == ZhuliPersonaBootstrap.ZHULI_ASSISTANT_NAME }
        if (assistant == null) {
            ZhuliBriefStore.saveNote(applicationContext, "没找到 zhuli 助理（人格预设可能没注入）")
            return
        }
        val modelId = assistant.backgroundModelId ?: assistant.chatModelId ?: settings.chatModelId
        val model = settings.findModelById(modelId)
        if (model == null) {
            ZhuliBriefStore.saveNote(applicationContext, "助理还没配模型")
            return
        }
        val provider = model.findProvider(settings.providers)
        if (provider == null) {
            ZhuliBriefStore.saveNote(applicationContext, "模型对应的服务商没配")
            return
        }
        val providerHandler = providerManager.getProviderByType(provider)

        val briefData = collectBriefData(applicationContext)
        val prompt = buildString {
            appendLine("现在是早上，请给用户一份简短的晨间简报（中文，直接输出正文，不要寒暄开场）。")
            appendLine("结构：1) 今日待办 2) 昨日/近期账目小结 3) 待取快递提醒。没有内容的段落写「无」。控制在 200 字内。")
            appendLine()
            appendLine("【待办数据】")
            appendLine(briefData.todo)
            appendLine("【账目数据】")
            appendLine(briefData.ledger)
            appendLine("【快递数据】")
            appendLine(briefData.courier)
        }

        val brief = runCatching {
            providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(UIMessage.user(prompt)),
                params = TextGenerationParams(
                    model = model,
                    temperature = 0.5f,
                    thinkingBudget = 0,
                ),
            ).choices.firstOrNull()?.message?.toContentText()
        }.getOrNull()?.trim().orEmpty()
        if (brief.isBlank()) {
            ZhuliBriefStore.saveNote(applicationContext, "模型没返回内容（Key / 网络 / 额度）")
            return
        }

        // 存一份给"今日简报"卡片随时查看（通知划过就没了，用户经常想回头看）
        runCatching { ZhuliBriefStore.saveText(applicationContext, brief) }
        runCatching {
            ZhuliBriefStore.saveNote(
                applicationContext,
                "上次生成成功：" + java.text.SimpleDateFormat("M-d HH:mm", java.util.Locale.CHINA)
                    .format(java.util.Date()),
            )
        }

        sendNotification(assistant, brief)
    }

    /** 本地库简报数据（直接查 fork 自持 zhuli_inbox.db，不出网）。 */
    private fun collectBriefData(context: Context): BriefData {
        val tools = ZhuliQueryTools(context)
        val now = System.currentTimeMillis()

        val todoRows = tools.queryTodo(10).take(5)
        val todo = if (todoRows.isEmpty()) "无"
        else ZhuliQueryAnswer.todo(todoRows, System.currentTimeMillis())
        val ledger = tools.queryLedger(1, now).take(8)
            .joinToString("\n") { "· ${it.dir} ${it.category} ¥${"%.2f".format(it.amount)} (${it.count}笔)" }
            .ifBlank { "无" }
        val courierRows = ZhuliCourierTool(context).queryCourier(now).take(3)
        val courier = if (courierRows.isEmpty()) "无"
        else courierRows.joinToString("\n") { "· ${it.note.ifBlank { it.text.take(40) }}" }
        return BriefData(todo, ledger, courier)
    }

    private fun canPostNotifications(): Boolean {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            nm.areNotificationsEnabled()
        } else true
    }

    private fun sendNotification(assistant: Assistant, content: String) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "zhuli 晨间简报", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val intent = Intent(applicationContext, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            applicationContext, "zhuli_brief".hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${assistant.name} · 晨间简报")
            .setContentText(content.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify("zhuli_brief".hashCode(), notification)
    }

    private data class BriefData(val todo: String, val ledger: String, val courier: String)
}
