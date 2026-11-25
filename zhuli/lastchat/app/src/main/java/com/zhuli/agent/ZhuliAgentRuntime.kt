package com.zhuli.agent

import android.content.Context
import com.zhuli.bridge.ZhuliPersonaBootstrap
import com.zhuli.core.InboxDatabase
import com.zhuli.memory.GemmaEmbeddingProvider
import com.zhuli.memory.HashEmbeddingProvider
import com.zhuli.memory.MemoryRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Agent 运行时的宿主装配点。
 *
 * 这里才碰 Android / Koin / 上游 RikkaHub；核心的 AgentLoop / Gateway / Policy 全部保持纯 JVM。
 * 也正因如此，整套循环能在单测里用假模型跑出真实多步流程，而不是靠 mock 声明"已实现"。
 */
object ZhuliAgentRuntime : KoinComponent {

    private val settingsStore: me.rerere.rikkahub.data.datastore.SettingsStore by inject()
    private val providerManager: me.rerere.ai.provider.ProviderManager by inject()
    private val liteRtEmbedder: me.rerere.locallm.LiteRtEmbedder by inject()
    private val localModelStore: me.rerere.locallm.LocalModelStore by inject()

    @Volatile
    private var initialized = false
    private lateinit var appCtx: Context
    private lateinit var _store: AgentStore
    private lateinit var _memory: MemoryRepo
    private lateinit var _tools: ToolRegistry
    private lateinit var _router: ModelRouter

    val store: AgentStore get() = _store
    val memory: MemoryRepo get() = _memory

    /** 在 Application 或首次进入控制台时调用；重复调用无副作用。 */
    fun init(ctx: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = ctx.applicationContext
            val db = InboxDatabase(app)
            _store = SqlAgentStore(db)
            _memory = MemoryRepo(db)
            // 向量源：真语义（EmbeddingGemma）优先，不可用就退到字符哈希 —— 由 EmbeddingGate
            // 统一决定，不再像上一版那样包一层「永远只有 Gemma」的假 provider。
            val semantic = GemmaEmbeddingProvider(liteRtEmbedder, localModelStore)
            val gate = com.zhuli.memory.EmbeddingGate(semantic, com.zhuli.memory.HashEmbeddingProvider())
            com.zhuli.memory.MemoryIndexer.init({ MemoryRepo(db) }, gate)
            _tools = ZhuliAgentTools.build(app, db, _memory, _store) { gate.active() }
            _router = ModelRouter(
                listOf(
                    CloudModelProvider(app),
                    LocalModelProvider({ prompt -> generateLocal(prompt) }, modelId = "configured-local"),
                ),
                onEvent = { msg -> com.zhuli.core.ZhuliLog.i("zhuli.agent", msg) },
            )
            // 上次进程没跑完的任务不能留在 RUNNING —— 恢复时从 PAUSED 接着跑
            _store.markOrphansPaused(System.currentTimeMillis())
            // 执行到一半的工具记录一律判「结果未知」，避免恢复后重复产生副作用
            val unknown = _store.recoverRunningExecutions(System.currentTimeMillis())
            if (unknown > 0) com.zhuli.core.ZhuliLog.w("zhuli.agent", "恢复：$unknown 条工具执行结果未知，不自动重试")
            appCtx = app
            initialized = true
            // 存量事实补建向量索引（异步，绝不在初始化线程里跑模型）
            com.zhuli.memory.MemoryIndexer.requestIndex()
        }
    }

    fun loop(): AgentLoop = AgentLoop(
        router = _router,
        tools = _tools,
        store = _store,
        policy = PolicyEngine(),
        onEvent = { msg -> com.zhuli.core.ZhuliLog.i("zhuli.agent", msg) },
    )

    /**
     * 发起任务。
     *
     * [localOnly] 的来源有两条，都必须在进 Gateway 之前定下来（不能只留一个没人调用的参数）：
     * - 调用方明确要求（用户在聊天里说「别联网」之类）；
     * - 隐私策略：云端可用、但这条目标过不了闸门（严格模式 / 硬红线 / 非 HTTPS 端点）时，
     *   任务整体只在本机跑。没配云端 Key 不算「隐私要求」，那种情况交给路由器自然失败再降级，
     *   否则用户没配 Key 时会得到一句莫名其妙的「只允许本机推理」。
     */
    suspend fun start(goal: String, origin: String = "chat", localOnly: Boolean = false) = withContext(Dispatchers.IO) {
        check(initialized) { "ZhuliAgentRuntime 未初始化，请先 init(context)" }
        loop().start(goal, origin, localOnly = localOnly || privacyDemandsLocal(goal))
    }

    private fun privacyDemandsLocal(goal: String): Boolean = try {
        val cloudReady = com.zhuli.core.AssistantPrefs.cloudReady(appCtx)
        cloudReady && !com.zhuli.core.PrivacyRouter
            .decide(appCtx, com.zhuli.core.PrivacyRouter.Task.CHAT, goal).allow
    } catch (t: Throwable) {
        // 判定本身出问题时不放行云端：宁可这一轮只在本机跑（可能答不出来），
        // 也不能因为判定异常而默认走云 —— 那是 fail-open（批次 A 口径）。
        com.zhuli.core.ZhuliLog.w("zhuli.agent", "隐私判定异常，本轮按仅本机处理", t)
        true
    }

    suspend fun approve(taskId: String, allow: Boolean) = withContext(Dispatchers.IO) {
        check(initialized) { "ZhuliAgentRuntime 未初始化，请先 init(context)" }
        loop().approve(taskId, allow)
    }

    fun describeBackends(): String = if (initialized) _router.describe() else "（未初始化）"

    /**
     * 本地推理：复用上游已验证的 ProviderManager 链路（与晨间简报同一套），
     * 不另起一个 LiteRT 实例。找不到模型就如实抛 UNAVAILABLE，让网关降级而不是假装成功。
     *
     * 批次 A：**必须**校验 provider 是端侧实现。旧实现直接拿 `backgroundModelId` 去
     * `getProviderByType`，用户把后台模型配成云厂商时，这条「本地」链路会把 prompt 发到云上，
     * 而网关仍以为 `leavesDevice = false`。
     */
    private suspend fun generateLocal(prompt: String): String {
        val settings = settingsStore.settingsFlow.first { !it.init }
        val assistant = settings.assistants.firstOrNull { it.name == ZhuliPersonaBootstrap.ZHULI_ASSISTANT_NAME }
        val modelId = assistant?.backgroundModelId ?: assistant?.chatModelId ?: settings.chatModelId
        val model = settings.findModelById(modelId) ?: error("model_not_installed:no_model_configured")
        val providerSetting = model.findProvider(settings.providers) ?: error("model_not_installed:no_provider")
        // 端侧链路只接受 LiteRtLocal；云 provider 在这里就被拒绝（UNAVAILABLE，不会触发云端降级）
        LocalBackendPolicy.requireOnDevice(providerSetting)
        val handler = providerManager.getProviderByType(providerSetting)
        return handler.generateText(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user(prompt)),
            params = TextGenerationParams(model = model, temperature = 0.4f, thinkingBudget = 0),
        ).choices.firstOrNull()?.message?.toContentText().orEmpty().ifBlank { error("model_empty_response") }
    }
}
