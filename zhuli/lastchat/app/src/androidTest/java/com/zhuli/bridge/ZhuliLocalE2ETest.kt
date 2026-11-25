package com.zhuli.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantSearchMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import kotlin.uuid.Uuid

/**
 * T-G2/B1-方案A 端到端（真机、离线）：
 * 端侧 LiteRT 本地模型（SmolLM2-360M）驱动 zhuli 只读 Tool，Tool 结果只在设备内回传，
 * 数据（取件码/账本/待办）不出机。
 *
 * 前置：ZhuliLocalModelInstallTest 已登记本地模型（files/local_models + LocalModelStore）。
 */
@RunWith(AndroidJUnit4::class)
class ZhuliLocalE2ETest {

    private val localModelFileId = "litert-community/gemma-4-E2B-it-litert-lm/gemma-4-E2B-it.litertlm"

    private fun buildSettings(): Pair<Settings, Model> {
        val modelId = Uuid.random()
        val model = Model(
            modelId = localModelFileId,
            displayName = "Gemma 4 E2B (zhuli local)",
            id = modelId,
            abilities = listOf(ModelAbility.TOOL),
        )
        val provider = ProviderSetting.LiteRtLocal(
            models = listOf(model),
        )
        val settings = Settings(
            init = true,
            setupCompleted = true,
            chatModelId = modelId,
            providers = listOf(provider),
        )
        return settings to model
    }

    private suspend fun ask(question: String): List<UIMessage> {
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val handler: GenerationHandler = GlobalContext.get().get()
        val (settings, model) = buildSettings()
        val assistant = Assistant(
            systemPrompt = "你是用户的私人助理。用户问到快递、待办、账本、记忆时，调用对应工具查询本地记录后回答。",
            streamOutput = false,
            enableMemory = false,
            searchMode = AssistantSearchMode.Off,
        )
        val tools = buildList {
            add(ZhuliCourierTool(context).asTool())
            addAll(ZhuliQueryTools(context).allTools())
        }
        var finalMessages: List<UIMessage> = emptyList()
        withTimeout(300_000) {
            handler.generateText(
                settings = settings,
                model = model,
                messages = listOf(UIMessage.user(question)),
                assistant = assistant,
                tools = tools,
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) finalMessages = chunk.messages
            }
        }
        return finalMessages
    }

    @Test
    fun localModel_callsZhuliTool_andReturnsRealPickupCode_offline() = runBlocking {
        val msgs = ask("我快递到哪了？取件码是多少？")
        assertTrue("应收到模型消息，实际 ${msgs.size} 条", msgs.isNotEmpty())
        val calls = msgs.flatMap { it.parts.filterIsInstance<UIMessagePart.ToolCall>() }.map { it.toolName }
        val assistantText = msgs.filter { it.role == MessageRole.ASSISTANT }
            .flatMap { it.parts.filterIsInstance<UIMessagePart.Text>() }
            .joinToString("\n") { it.text }
        assertTrue(
            "本地模型应自主调用 list_courier，实际 $calls；模型回复: ${assistantText.take(500)}",
            calls.contains("list_courier"),
        )
        val result = msgs.flatMap { it.parts.filterIsInstance<UIMessagePart.ToolResult>() }
            .joinToString("\n") { runCatching { it.content.toString() }.getOrDefault("") }
        assertTrue("Tool 结果应含真库取件码 6825，实际: ${result.take(400)}", result.contains("6825"))
    }
}
