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
 * T-G1/T-G2 端到端验收（真机 + 免费 egg-alpha-003）：
 * 走 LastChat 真实 GenerationHandler，让模型在对话里【自主】决定是否调用
 * zhuli 注入的只读 Tool，并验证 Tool 真正执行、返回本地快照里的真值。
 *
 * 只用免费模型 egg-alpha-003；provider 明文 key 注入内存 Settings，不写盘、不污染真实配置。
 */
@RunWith(AndroidJUnit4::class)
class ZhuliE2ETest {

    private val key: String by lazy {
        val fromArg = androidx.test.platform.app.InstrumentationRegistry
            .getArguments().getString("zhuli.e2e.key", "").orEmpty().trim()
        if (fromArg.isNotBlank()) return@lazy fromArg
        runCatching {
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().context.assets
                .open("zhuli_e2e_key.txt").bufferedReader().readText().trim()
        }.getOrDefault("")
    }

    /**
     * [zhuli 2026-11-23] 密钥已从仓库移出（曾以明文入库，已作废）。
     * 现在只从 instrumentation 参数取：`-e zhuli.e2e.key <key>`；
     * 没给就跳过，而不是让整包测试报红——否则"没配 key"会被误读成"功能坏了"。
     */
    @org.junit.Before
    fun requireE2eKey() {
        org.junit.Assume.assumeTrue("未提供 zhuli.e2e.key，跳过联网 E2E", key.isNotBlank())
    }

    private fun buildSettings(): Pair<Settings, Model> {
        val modelId = Uuid.random()
        val model = Model(
            modelId = "egg-alpha-003",
            displayName = "Egg Alpha (free)",
            id = modelId,
            abilities = listOf(ModelAbility.TOOL),
        )
        val provider = ProviderSetting.OpenAI(
            id = Uuid.random(),
            name = "Zhuli E2E (free)",
            baseUrl = "https://tokenrhythm.studio/v1",
            chatCompletionsPath = "/chat/completions",
            apiKey = key,
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

    private fun buildAssistant(): Assistant = Assistant(
        systemPrompt = "你是用户的私人助理。当用户问到快递、待办、账本、记忆时，务必调用相应工具查询本地记录后再回答，不要编造。",
        streamOutput = false,
        enableMemory = false,
        searchMode = AssistantSearchMode.Off,
    )

    /** 发一个问题，收集最终消息（不在此断言，交给各用例）。 */
    private suspend fun ask(question: String): List<UIMessage> {
        assertTrue("E2E key 不能为空（-e zhuli.e2e.key）", key.isNotBlank())
        val context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext
        val handler: GenerationHandler = GlobalContext.get().get()
        val (settings, model) = buildSettings()
        val tools = buildList {
            add(ZhuliCourierTool(context).asTool())
            addAll(ZhuliQueryTools(context).allTools())
        }
        var finalMessages: List<UIMessage> = emptyList()
        withTimeout(180_000) {
            handler.generateText(
                settings = settings,
                model = model,
                messages = listOf(UIMessage.user(question)),
                assistant = buildAssistant(),
                tools = tools,
            ).collect { chunk ->
                if (chunk is GenerationChunk.Messages) finalMessages = chunk.messages
            }
        }
        return finalMessages
    }

    private fun toolCallsOf(msgs: List<UIMessage>) =
        msgs.flatMap { it.parts.filterIsInstance<UIMessagePart.ToolCall>() }.map { it.toolName }

    private fun toolResultText(msgs: List<UIMessage>) =
        msgs.flatMap { it.parts.filterIsInstance<UIMessagePart.ToolResult>() }
            .joinToString("\n") { runCatching { it.content.toString() }.getOrDefault("") }

    @Test
    fun e2e_courier_modelCallsTool_andReturnsRealPickupCode() = runBlocking {
        val msgs = ask("我快递到哪了？取件码是多少？")
        assertTrue("应收到模型消息，实际 ${msgs.size} 条", msgs.isNotEmpty())
        val calls = toolCallsOf(msgs)
        assertTrue("模型应自主调用 list_courier，实际 $calls", calls.contains("list_courier"))
        val result = toolResultText(msgs)
        assertTrue("ToolResult 应含真库取件码 6825，实际: ${result.take(400)}", result.contains("6825"))
    }

    @Test
    fun e2e_todo_modelCallsTool_andReturnsRealReminder() = runBlocking {
        val msgs = ask("我接下来有什么待办或提醒？")
        val calls = toolCallsOf(msgs)
        assertTrue("模型应自主调用 list_todo，实际 $calls", calls.contains("list_todo"))
        val result = toolResultText(msgs)
        assertTrue("待办结果应含取件提醒真值 6825，实际: ${result.take(400)}", result.contains("6825"))
    }

    @Test
    fun e2e_ledger_modelCallsTool_andReturnsRealExpense() = runBlocking {
        val msgs = ask("最近账本小结，我前后花了多少钱？")
        val calls = toolCallsOf(msgs)
        assertTrue("模型应自主调用 ledger_summary，实际 $calls", calls.contains("ledger_summary"))
        val result = toolResultText(msgs)
        assertTrue("账本结果应含真实分类（餐饮/通讯），实际: ${result.take(400)}",
            result.contains("餐饮") || result.contains("通讯"))
    }

    @Test
    fun e2e_memory_modelCallsTool_andReturnsRealSearch() = runBlocking {
        val msgs = ask("帮我查一下记忆里关于取件码的事情")
        val calls = toolCallsOf(msgs)
        assertTrue("模型应自主调用 search_memory，实际 $calls", calls.contains("search_memory"))
        val result = toolResultText(msgs)
        assertTrue("检索结果应含真库内容，实际: ${result.take(400)}",
            result.contains("取件") || result.contains("6825") || result.contains("驿站"))
    }
}
