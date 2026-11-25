package com.zhuli.agent

import me.rerere.ai.provider.ProviderSetting

/**
 * 本地推理后端的准入检查（批次 A）。
 *
 * 背景：`LocalModelProvider` 对网关声明 `leavesDevice = false`，网关据此认为
 * 「选了本地后端 = 数据没出设备」。但它的 `generate` 回调实际走的是宿主注入的
 * `ZhuliAgentRuntime.generateLocal`，那里只按 `assistant.backgroundModelId` 取模型，
 * **没有校验该模型属于哪个 provider**。用户完全可以把后台模型配成 OpenAI / Claude /
 * 任意 OpenAI 兼容云服务 —— 于是「本地模式」把 prompt 原样发到云上，而路由层、
 * 自检、用户界面都显示「本地」。
 *
 * 这是典型的 fail-open：不是没有开关，而是开关没接到闸刀上。
 * 修法只有一个方向：**端侧链路只接受端侧 provider**，其余一律拒绝，
 * 并且拒绝信息要能被 [LocalModelProvider] 归类为「模型不可用」，
 * 而不是伪装成网络失败再去触发云端降级。
 */
object LocalBackendPolicy {

    fun isOnDevice(setting: ProviderSetting): Boolean = setting is ProviderSetting.LiteRtLocal

    /**
     * @throws IllegalStateException 消息以 `model_not_installed` 开头，
     *         由 [LocalModelProvider] 映射为 [ModelError.Kind.UNAVAILABLE]（不重试、不降级）。
     */
    fun requireOnDevice(setting: ProviderSetting): ProviderSetting.LiteRtLocal =
        setting as? ProviderSetting.LiteRtLocal
            ?: throw IllegalStateException(
                "model_not_installed:local_provider_required:${setting::class.simpleName}"
            )
}
