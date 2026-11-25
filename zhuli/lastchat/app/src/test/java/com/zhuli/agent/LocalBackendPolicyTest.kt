package com.zhuli.agent

import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 A：本地模式不得外溢到云端。
 *
 * 这组测试守的是一个真实漏洞：`LocalModelProvider` 对网关声明 `leavesDevice = false`，
 * 但宿主注入的生成函数只按 `backgroundModelId` 找模型，不校验 provider 类型。
 * 用户把后台模型配成云厂商后，「本地模式」会把 prompt 发到云上，而所有界面都显示"本地"。
 *
 * 修法是把准入判断抽成 [LocalBackendPolicy]，让「声明」与「实际执行」对齐。
 */
class LocalBackendPolicyTest {

    @Test
    fun `端侧 provider 允许通过`() {
        val local = ProviderSetting.LiteRtLocal()
        assertTrue(LocalBackendPolicy.isOnDevice(local))
        assertEquals(local, LocalBackendPolicy.requireOnDevice(local))
    }

    @Test
    fun `云 provider 被拒绝且错误可被识别为模型不可用`() {
        val cloud = ProviderSetting.OpenAI(name = "我的云厂商")
        assertFalse(LocalBackendPolicy.isOnDevice(cloud))

        val e = runCatching { LocalBackendPolicy.requireOnDevice(cloud) }.exceptionOrNull()
        assertTrue("应抛异常，实际 $e", e is IllegalStateException)
        // LocalModelProvider 靠这个前缀把错误归类为 UNAVAILABLE（不重试、不降级）
        assertTrue("错误消息要带 model_not_installed 前缀：${e!!.message}", e.message!!.startsWith("model_not_installed"))
    }
}
