package com.zhuli.bridge

import android.content.Context
import com.zhuli.core.AssistantPrefs

/**
 * 隐私闸门接线状态（T-G2/B1，2026-11-24 真正接上出网路径）。
 *
 * 判定核心 [ZhuliPrivacy] 早就在，但之前没有任何出网代码调用它，自检只能如实写"未接线"。
 * 现在由 [ZhuliPrivacyInterceptor] 挂在全局 OkHttpClient 上，每个聊天请求都会过一遍：
 * 硬红线（证件/卡号/凭证）整条拦下，其它敏感项脱敏后放行。
 *
 * 这里的 isActive() 表示"拦截器已安装且开关打开"，由 [refresh] 在启动与开关变化时刷新。
 */
object ZhuliPrivacyGate {

    @Volatile
    private var active = false

    fun isActive(): Boolean = active

    /** 由出网拦截逻辑在确认生效后调用。 */
    fun activate() {
        active = true
    }

    fun deactivate() {
        active = false
    }

    /** 按用户开关刷新状态（启动时、设置里拨动开关时各调一次）。 */
    fun refresh(ctx: Context) {
        if (AssistantPrefs.privacyGateEnabled(ctx)) activate() else deactivate()
    }

    /**
     * 出网前的统一闸门：把待发内容过一遍 [ZhuliPrivacy.decide]。
     * 接线方把 LastChat provider 状态填进 config，返回 Decision 决定是否放行/脱敏。
     */
    fun decide(config: ZhuliPrivacy.Config, task: ZhuliPrivacy.Task, text: String): ZhuliPrivacy.Decision =
        ZhuliPrivacy.decide(config, task, text)
}
