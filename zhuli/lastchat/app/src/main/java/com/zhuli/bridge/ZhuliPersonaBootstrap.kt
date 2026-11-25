package com.zhuli.bridge

import android.content.Context
import com.zhuli.core.Persona
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import kotlin.uuid.Uuid

/**
 * T-G2/B8 P0-2：zhuli 人格接线（复用上游 Assistant 预设机制，零新 UI）。
 *
 * - 启动时确保 assistants 列表里有一个 name=="zhuli" 的预设（无则插入首位）；
 * - systemPrompt 由 Persona（语气/称谓/开场白）+ 安全底线 + 本地工具说明组成；
 * - 人格只变语气不变安全界线（Persona 设计底线：不含可关闭 D2/A3 的开关）。
 */
object ZhuliPersonaBootstrap {

    const val ZHULI_ASSISTANT_NAME = "zhuli"

    /** 人格 + 底线 + 工具说明 → system prompt。 */
    fun buildSystemPrompt(persona: Persona): String = buildString {
        appendLine("你是 ${persona.name}，用户的私人助理（本机数据助手）。称呼用户用「${persona.callUser}」。")
        appendLine("语气风格：${persona.tone}")
        if (persona.greeting.isNotBlank()) appendLine("开场白参考：「${persona.greeting}」")
        appendLine()
        appendLine("## 安全底线（不可违背）")
        appendLine("- 只收不发：不代替用户给任何人回消息、不代办任何资金操作、不点任何付款按钮。")
        appendLine("- 用户数据（快递/账本/待办/记忆）只在本机查询，查询动作不出网。")
        // 注意措辞避开出网闸门的凭证触发词（密码/验证码/token 等）：
        // 系统提示词同样会被隐私闸门扫描，写触发词会让 zhuli 的每次请求都被硬红线拦死
        // （2026-11-24 真机实测：聊天静默失败、空气泡）。语义不变，只换词。
        appendLine("- 涉及支付、账户安全、身份核验、证件的请求一律拒绝并说明原因。")
        appendLine()
        appendLine("## 本地工具")
        appendLine("回答涉及用户私人数据的问题时，优先调用下列工具查真值，不要编造：")
        com.zhuli.bridge.ZHULI_LOCAL_TOOL_LINES.forEach { appendLine("- $it") }
    }

    /**
     * 一次性导入标记：只导一次（用户删除后不主动复活），否则"助手删不掉"。
     *
     * 但存在一个真实缺陷：备份恢复 / 旧版本迁移可能把 settings 里的 zhuli 助手弄丢，
     * 而本标记仍是 true —— 于是主聊天的 Agent 桥（只在 assistant.name=="zhuli" 时生效）
     * 永久失效，且不报任何错。为兼顾两种意图，引入"用户主动删除"标记：
     * - 用户从助手页删除 zhuli → [markUserRemoved] → 不再复活（尊重用户）；
     * - 标记 imported=true 但助手不在、且没有被主动删除 → 自愈补回（修恢复丢数据）。
     */
    private const val PREF_USER_REMOVED = "user_removed_zhuli"

    fun markUserRemoved(context: Context) {
        context.getSharedPreferences("zhuli_persona", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_USER_REMOVED, true).apply()
    }

    /** 用户重新添加/导入 zhuli 时清除，避免以后又不再补回。 */
    fun clearUserRemoved(context: Context) {
        context.getSharedPreferences("zhuli_persona", Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_USER_REMOVED, false).apply()
    }

    /** 纯决策：是否该检查并补回 zhuli 助手（可脱离 Android 测试）。 */
    internal fun shouldEnsure(imported: Boolean, userRemoved: Boolean): Boolean =
        !imported || !userRemoved

    /**
     * 确保存在 zhuli 人格预设。幂等；首次插入到列表首位（成为默认兜底）。
     * @return 是否发生了插入（用于日志）。
     */
    suspend fun ensure(context: Context, settingsStore: SettingsStore): Boolean {
        val flag = context.getSharedPreferences("zhuli_persona", Context.MODE_PRIVATE)
        val imported = flag.getBoolean("imported", false)
        val userRemoved = flag.getBoolean(PREF_USER_REMOVED, false)
        // 用户主动删除过：尊重其决定，永不复活。
        if (!shouldEnsure(imported, userRemoved)) return false
        // 关键：settingsFlow 初值是 Settings.dummy()（init=true），此时 update() 会拒绝写入
        // 并只打一条 "Cannot update dummy settings" —— 这正是"inserted=true 但助手没出现"的原因。
        // 与其他启动任务一样，先等真实设置加载完成再写。
        settingsStore.settingsFlow.first { settings -> !settings.init }
        val persona = com.zhuli.core.Personas.current(context)
        var inserted = false
        var refreshedPrompt = false
        var countInside = -1
        settingsStore.update { settings ->
            countInside = settings.assistants.size
            val existing = settings.assistants.firstOrNull { it.name == ZHULI_ASSISTANT_NAME }
            if (existing != null) {
                // 自愈：旧版提示词若含出网闸门触发词（如"密码/验证码/转账"），会让 zhuli 的
                // 每次请求被隐私闸门整包拦死（2026-11-24 真机实测）。只有命中敏感规则时
                // 才刷新，避免覆盖用户自己改过的提示词。
                if (com.zhuli.bridge.ZhuliSensitiveGuard.reason(existing.systemPrompt) != null) {
                    refreshedPrompt = true
                    settings.copy(
                        assistants = settings.assistants.map {
                            if (it.name == ZHULI_ASSISTANT_NAME) {
                                it.copy(systemPrompt = buildSystemPrompt(persona))
                            } else it
                        },
                    )
                } else {
                    settings
                }
            } else {
                inserted = true
                val zhuli = Assistant(
                    id = Uuid.random(),
                    name = ZHULI_ASSISTANT_NAME,
                    systemPrompt = buildSystemPrompt(persona),
                )
                settings.copy(assistants = listOf(zhuli) + settings.assistants)
            }
        }
        if (inserted) {
            flag.edit().putBoolean("imported", true).apply()
        }
        // 诊断：这类"助手莫名丢失导致 Agent 桥静默失效"的问题没有日志无法定位。
        com.zhuli.core.ZhuliLog.i(
            "zhuli.persona",
            "ensure imported=$imported userRemoved=$userRemoved inserted=$inserted " +
                "refreshedPrompt=$refreshedPrompt " +
                "countInside=$countInside assistantsAfter=${settingsStore.settingsFlow.value.assistants.size}",
        )
        return inserted
    }
}
