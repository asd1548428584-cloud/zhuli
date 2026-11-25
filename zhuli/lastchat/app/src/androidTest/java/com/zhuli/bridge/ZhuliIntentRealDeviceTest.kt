package com.zhuli.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantSearchMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/**
 * 真机验收：意图分流两跳确认（B8 反馈修复的核心链路）。
 *
 * 走真实的 GenerationHandler（与 UI 发送消息同一条路），但**故意不配任何 provider**：
 * 只要模板回答能返回，就证明这条路一次模型推理都没发生 —— 这正是"秒回、不发热"的证据。
 * 若分流未命中，代码会走到 provider 解析并抛 Provider not found，用例即失败。
 */
@RunWith(AndroidJUnit4::class)
class ZhuliIntentRealDeviceTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun zhuliAssistant() = Assistant(
        name = ZhuliPersonaBootstrap.ZHULI_ASSISTANT_NAME,
        systemPrompt = "（测试用，不会真的走到模型）",
        streamOutput = false,
        enableMemory = false,
        searchMode = AssistantSearchMode.Off,
    )

    private fun plainAssistant() = Assistant(
        name = "普通助手",
        systemPrompt = "（测试用）",
        streamOutput = false,
        enableMemory = false,
        searchMode = AssistantSearchMode.Off,
    )

    private data class Result(val text: String, val elapsedMs: Long)

    /** 发一句话，收集最终消息文本；返回耗时（毫秒）。 */
    private suspend fun send(
        question: String,
        history: List<UIMessage> = emptyList(),
        assistant: Assistant = zhuliAssistant(),
    ): Result {
        val handler: GenerationHandler = GlobalContext.get().get()
        val modelId = Uuid.random()
        val model = Model(modelId = "no-model", displayName = "no-model", id = modelId)
        // providers 留空：任何真实模型调用都会抛 "Provider not found"
        val settings = me.rerere.rikkahub.data.datastore.Settings(
            init = true,
            setupCompleted = true,
            chatModelId = modelId,
        )
        var finalMessages: List<UIMessage> = emptyList()
        val start = System.currentTimeMillis()
        withTimeout(20_000) {
            handler.generateText(
                settings = settings,
                model = model,
                messages = history + UIMessage.user(question),
                assistant = assistant,
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) finalMessages = chunk.messages
            }
        }
        val elapsed = System.currentTimeMillis() - start
        val text = finalMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts?.filterIsInstance<UIMessagePart.Text>()?.joinToString("") { it.text }
            .orEmpty()
        return Result(text, elapsed)
    }

    private fun resetPending() {
        ctx.getSharedPreferences("zhuli_intent", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun twoHop_courier_confirm_thenTemplate() = runBlocking {
        resetPending()
        // 第一跳：只回确认问句，不查
        val first = send("我快递到哪了")
        assertTrue("第一跳应回确认问句，实际: ${first.text}", first.text.contains("要我查一下") && first.text.contains("确认"))
        assertTrue("第一跳不该有模型推理，耗时 ${first.elapsedMs}ms", first.elapsedMs < 1500)

        // 第二跳：回「确认」→ 模板直答（零推理）
        val second = send("确认")
        assertFalse("第二跳不该再问一遍，实际: ${second.text}", second.text.contains("要我查一下"))
        assertTrue("第二跳应给模板回答，实际: ${second.text}", second.text.contains("快递"))
        assertTrue("模板回答必须秒回，耗时 ${second.elapsedMs}ms", second.elapsedMs < 1500)
        println("[zhuli] courier 第一跳 ${first.elapsedMs}ms / 第二跳 ${second.elapsedMs}ms")
    }

    @Test
    fun twoHop_reject_doesNotQuery() = runBlocking {
        resetPending()
        val first = send("我有哪些待办")
        assertTrue("应回确认问句，实际: ${first.text}", first.text.contains("要我查一下"))
        val second = send("算了")
        assertTrue("拒绝应回「不查了」，实际: ${second.text}", second.text.contains("不查了"))
        // 拒绝后再问一次待办，不应该记住上次的意图
        val pending = ctx.getSharedPreferences("zhuli_intent", android.content.Context.MODE_PRIVATE)
            .getString("pending_intent", null)
        assertNull("拒绝后不应残留待确认意图，实际 $pending", pending)
    }

    @Test
    fun nonZhuliAssistant_isNotIntercepted() = runBlocking {
        resetPending()
        // 普通助手不参与分流：会走到 provider 解析并抛错（说明没有被"吃掉"）
        val failed = runCatching { send("我快递到哪了", assistant = plainAssistant()) }
        assertTrue(
            "普通助手应照常走模型（本次无 provider 必然失败），实际: ${failed.isSuccess}",
            failed.isFailure,
        )
        val msg = failed.exceptionOrNull()?.message.orEmpty()
        assertTrue("失败原因应是找不到 provider，实际: $msg", msg.contains("Provider", ignoreCase = true))
        // 失败也不该留下待确认状态
        val pending = ctx.getSharedPreferences("zhuli_intent", android.content.Context.MODE_PRIVATE)
            .getString("pending_intent", null)
        assertNull("普通助手不应写入 pending 意图，实际 $pending", pending)
    }

    @Test
    fun diagnostics_activeAssistant() = runBlocking {
        val store: SettingsStore = GlobalContext.get().get()
        val settings = store.settingsFlow.first()
        val names = settings.assistants.map { "${it.name}(${it.id})" }
        println("[zhuli] assistants = $names")
        println("[zhuli] assistantId(默认) = ${settings.assistantId}")
        val current = settings.getCurrentAssistant()
        println("[zhuli] 当前生效助手 = ${current.name} (${current.id})")
        assertNotNull("应有可用助手", settings.assistants.firstOrNull())
    }

    @Test
    fun diagnostics_shizuku() {
        val installed = ZhuliShizukuRunner.isShizukuInstalled(ctx)
        val available = ZhuliShizukuRunner.isShizukuAvailable()
        println("[zhuli] shizuku installed=$installed available=$available")
    }
}
