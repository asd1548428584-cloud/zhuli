package com.zhuli.core

import android.content.Context
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getCurrentChatModel

/**
 * 助理级小配置（API Key、人格档等），落 App 私有 SharedPreferences。
 * 规则（D2）：Key 只存此处，永不入代码库、永不入日志。
 */
object AssistantPrefs {

    private const val SP_NAME = "zhuli_prefs"
    private const val K_LINK_BASE = "link_base_url"
    private const val K_LINK_KEY = "link_api_key"
    private const val K_LINK_MODEL = "link_model"
    private const val K_PERSONA = "persona_id"
    private const val K_CUSTOM_NAME = "persona_custom_name"
    private const val K_CUSTOM_CALL = "persona_custom_call"
    private const val K_CUSTOM_TONE = "persona_custom_tone"
    private const val K_REMINDER_ON = "reminder_enabled"
    private const val K_REMINDER_CAL = "reminder_calendar_sync"
    /** 第 8 步：允许语音上传云端转写（默认关——语音内容可能敏感，必须用户显式子打开） */
    private const val K_VOICE_CLOUD = "voice_cloud_enabled"
    private const val K_VOICE_ARMED = "voice_cloud_armed_once"
    /** 第 13 步：勿扰时段（23:00–07:00 提醒静音） */
    private const val K_QUIET = "quiet_hours_enabled"
    /** 第 14 步：晨间简报开关 */
    private const val K_BRIEF = "daily_brief_enabled"
    /** 第 16 步：本地模型推理后端偏好（auto/npu/gpu/cpu） */
    private const val K_BACKEND = "model_backend"
    /** 隐私模式：redact=敏感脱敏后上云（默认，用户 11-20 定的策略）；strict=敏感一步不出机 */
    private const val K_PRIVACY = "privacy_mode"
    /** 隐私闸门总开关：关掉后所有请求原样出网（自检会如实显示"已关闭"） */
    private const val K_PRIVACY_GATE = "privacy_gate_enabled"

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    private fun get(ctx: Context, key: String, def: String = ""): String =
        sp(ctx).getString(key, def) ?: def

    private fun put(ctx: Context, key: String, value: String) {
        sp(ctx).edit().putString(key, value.trim()).apply()
    }

    // ==================== 云端（OpenAI 兼容） ====================

    /**
     * 新壳接线（2026-11-24）：旧壳的 link_* 配置在新壳里没有任何 UI 写入口，
     * 用户的云端 API 一直配在「设置 → 提供商」（主聊天在用）。
     * 旧配置为空时回落到当前聊天模型的 OpenAI 兼容提供商，让 zhuli-cloud 通道
     * 与主聊天共用同一份用户配置 —— 否则 Agent 永远报「云通道未配置」（真机实测）。
     */
    private data class ProviderFallback(val base: String, val key: String, val model: String)

    private fun providerFallback(): ProviderFallback? = try {
        val store = org.koin.java.KoinJavaComponent.get<me.rerere.rikkahub.data.datastore.SettingsStore>(
            me.rerere.rikkahub.data.datastore.SettingsStore::class.java,
        )
        val settings = store.settingsFlow.value
        if (settings.init) return null
        val model = settings.getCurrentChatModel() ?: return null
        val provider = model.findProvider(settings.providers) ?: return null
        val filled = try {
            val skm = org.koin.java.KoinJavaComponent.get<me.rerere.rikkahub.data.datastore.SecretKeyManager>(
                me.rerere.rikkahub.data.datastore.SecretKeyManager::class.java,
            )
            skm.populateProviderSecrets(provider)
        } catch (t: Throwable) {
            provider
        }
        val openai = filled as? me.rerere.ai.provider.ProviderSetting.OpenAI ?: return null
        if (openai.baseUrl.isBlank() || openai.apiKey.isBlank()) return null
        ProviderFallback(
            base = openai.baseUrl.trimEnd('/'),
            key = openai.apiKey,
            model = model.modelId.ifBlank { "auto" },
        )
    } catch (t: Throwable) {
        null
    }

    fun linkBaseUrl(ctx: Context): String =
        get(ctx, K_LINK_BASE).ifBlank { providerFallback()?.base ?: "" }
    fun setLinkBaseUrl(ctx: Context, v: String) = put(ctx, K_LINK_BASE, v)

    /**
     * API Key 走 [SecureKeyStore]（Keystore 加密存储，第 9 步）。
     * 老的明文键会被自动迁移并抹掉，对调用方完全透明。
     * 旧存储为空时回落到「设置 → 提供商」里配置的密钥。
     */
    fun linkApiKey(ctx: Context): String =
        SecureKeyStore.get(ctx).ifBlank { providerFallback()?.key ?: "" }
    fun setLinkApiKey(ctx: Context, v: String) = SecureKeyStore.put(ctx, v)

    /** 给设置面板看的 Key 状态（不吐 Key 本体）。 */
    fun linkApiKeyStatus(ctx: Context): String = SecureKeyStore.status(ctx)

    fun linkModel(ctx: Context): String =
        get(ctx, K_LINK_MODEL, "auto").ifBlank { "auto" }.let { configured ->
            if (configured == "auto") providerFallback()?.model ?: configured else configured
        }
    fun setLinkModel(ctx: Context, v: String) = put(ctx, K_LINK_MODEL, v)

    /** 云通道是否可用：BaseURL + Key 都配了才算（含提供商回落）。 */
    fun cloudReady(ctx: Context): Boolean =
        linkBaseUrl(ctx).isNotBlank() && linkApiKey(ctx).isNotBlank()

    // ==================== 提醒（第 5 步） ====================

    /** 提醒总开关，默认开（助理的核心承诺就是「叫得醒」）。 */
    fun reminderEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_REMINDER_ON, true)

    fun setReminderEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(K_REMINDER_ON, on).apply()

    /** 双写系统日历，默认开（需求 C4 已拍板）。 */
    fun reminderCalendarSync(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_REMINDER_CAL, true)

    fun setReminderCalendarSync(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(K_REMINDER_CAL, on).apply()

    // ==================== 语音上云许可（第 8 步） ====================

    /**
     * 是否允许本地识别失败后把语音上传云端转写。
     * **默认关**：语音内容无法预判敏感性，守「敏感不出机」红线，必须用户自己打开。
     */
    fun voiceCloudEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_VOICE_CLOUD, false)

    fun setVoiceCloudEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(K_VOICE_CLOUD, on).apply()

    /**
     * T-B4 / §11-3（D-009）：语音上云改成**一次性许可**。
     *
     * 原因：语音内容无法在上传前判定敏感性（不像文本能先过 [SensitiveGuard]），
     * 而分享链路是无界面的后台流程，弹不出"这次要不要发"。所以把确认前置到用户点开关的那一刻：
     * 打开 = 只放行**下一段**语音，用完自动关回。想再发就再点一次。
     */
    fun armVoiceCloudOnce(ctx: Context) =
        sp(ctx).edit().putBoolean(K_VOICE_CLOUD, true).putBoolean(K_VOICE_ARMED, true).apply()

    fun voiceCloudArmed(ctx: Context): Boolean =
        voiceCloudEnabled(ctx) && sp(ctx).getBoolean(K_VOICE_ARMED, false)

    /** 消费一次性许可：成功返回 true 并把开关关回去；未授权返回 false。 */
    fun consumeVoiceCloudConfirm(ctx: Context): Boolean {
        val s = sp(ctx)
        if (!s.getBoolean(K_VOICE_CLOUD, false) || !s.getBoolean(K_VOICE_ARMED, false)) return false
        s.edit().putBoolean(K_VOICE_ARMED, false).putBoolean(K_VOICE_CLOUD, false).apply()
        return true
    }

    // ==================== 勿扰时段（第 13 步） ====================

    /** 夜里有到点提醒照样弹，但不出声。默认开（半夜被吵醒的代价太大）。 */
    fun quietHoursEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_QUIET, true)

    fun setQuietHoursEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(K_QUIET, on).apply()

    /** 晨间简报开关（默认开）。 */
    fun briefEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_BRIEF, true)
    fun setBriefEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(K_BRIEF, on).apply()

    // ==================== 隐私模式 ====================

    fun privacyMode(ctx: Context): String = get(ctx, K_PRIVACY, "redact")
    fun setPrivacyMode(ctx: Context, v: String) = put(ctx, K_PRIVACY, v)
    fun privacyModeLabel(ctx: Context): String = when (privacyMode(ctx)) {
        "strict" -> "严格：命中敏感就只在本地说"
        else -> "脱敏上云：敏感项打码后再发（证件/卡号/验证码除外）"
    }

    /** 隐私闸门是否启用（默认开）。关闭 = 明知风险仍要原样出网，自检会标红。 */
    fun privacyGateEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(K_PRIVACY_GATE, true)
    fun setPrivacyGateEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(K_PRIVACY_GATE, on).apply()

    // ==================== 本地模型后端（第 16 步） ====================

    /**
     * 本地模型跑在哪：`auto` / `npu` / `gpu` / `cpu`。
     *
     * 为什么做成偏好而不是写死：同芯片上 NPU（LiteRT/MediaPipe 的 NPU delegate）功耗和首字延迟更优，
     * 但**驱动与算子覆盖**参差；GPU（OpenCL/Vulkan）算子覆盖最好、兼容性最稳，功耗高。
     * 现在还没有内置本地大模型，这个值先存着 —— 等接 LiteRT/MLC 时按它选 delegate，不用改上层。
     */
    fun modelBackend(ctx: Context): String = get(ctx, K_BACKEND, "auto")
    fun setModelBackend(ctx: Context, v: String) = put(ctx, K_BACKEND, v)

    fun modelBackendLabel(ctx: Context): String = when (modelBackend(ctx)) {
        "npu" -> "NPU 优先（省电，算子兼容看芯片）"
        "gpu" -> "GPU 优先（兼容最好，功耗高）"
        "cpu" -> "CPU（最慢但最稳）"
        else -> "自动（NPU→GPU→CPU 依次尝试）"
    }

    // ==================== 人格档 ====================

    fun personaId(ctx: Context): String = get(ctx, K_PERSONA, Persona.DEFAULT_ID)
    fun setPersonaId(ctx: Context, id: String) = put(ctx, K_PERSONA, id)

    fun customPersonaName(ctx: Context): String = get(ctx, K_CUSTOM_NAME)
    fun setCustomPersonaName(ctx: Context, v: String) = put(ctx, K_CUSTOM_NAME, v)

    fun customPersonaCall(ctx: Context): String = get(ctx, K_CUSTOM_CALL)
    fun setCustomPersonaCall(ctx: Context, v: String) = put(ctx, K_CUSTOM_CALL, v)

    fun customPersonaTone(ctx: Context): String = get(ctx, K_CUSTOM_TONE)
    fun setCustomPersonaTone(ctx: Context, v: String) = put(ctx, K_CUSTOM_TONE, v)
}
