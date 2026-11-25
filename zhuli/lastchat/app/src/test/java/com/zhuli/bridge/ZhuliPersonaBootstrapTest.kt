package com.zhuli.bridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * zhuli 助手自愈逻辑的纯决策测试。
 *
 * 背景（真机实测缺陷）：某设备 zhuli_persona.imported=true，但 settings 里助手列表
 * 只剩 "Generical"，zhuli 助手丢失 → 主聊天 Agent 桥（只在 name=="zhuli" 生效）永久失效。
 * 根因是一次性导入标记无脑短路。修法：加"用户主动删除"标记区分两种意图。
 */
class ZhuliPersonaBootstrapTest {

    @Test
    fun `首次启动 未导入过 需要确保`() {
        assertTrue(ZhuliPersonaBootstrap.shouldEnsure(imported = false, userRemoved = false))
    }

    @Test
    fun `已导入且助手在 标记不影响 幂等`() {
        // ensure() 内部还会再查助手名；这里只验证决策层不会因为 imported=true 就永不检查
        assertTrue(ZhuliPersonaBootstrap.shouldEnsure(imported = true, userRemoved = false))
    }

    @Test
    fun `导入过但助手丢失 没有被主动删除 需要自愈补回`() {
        // 这正是真机缺陷那一例：imported=true / userRemoved=false → 必须补回
        assertTrue(ZhuliPersonaBootstrap.shouldEnsure(imported = true, userRemoved = false))
    }

    @Test
    fun `用户主动删除过 不再复活`() {
        // 保留"助手删不掉"的原意图：用户明确删除 → 永不自愈
        assertFalse(ZhuliPersonaBootstrap.shouldEnsure(imported = true, userRemoved = true))
    }

    @Test
    fun `系统提示词不得触发出网闸门的敏感规则`() {
        // 真机缺陷（2026-11-24）：人格底线里写了"密码/验证码"，被隐私闸门 CREDENTIAL
        // 硬红线命中 → zhuli 的每次聊天请求整包被拦 → 静默失败空气泡。
        // 系统提示词是开发者文案，必须零命中；触发词应写在安全界线的语义里而不是字面里。
        val prompt = ZhuliPersonaBootstrap.buildSystemPrompt(
            com.zhuli.core.Persona(
                id = "test",
                name = "助理",
                callUser = "你",
                tone = "温和直接",
                greeting = "在的，说吧。",
            )
        )
        val hit = ZhuliSensitiveGuard.reason(prompt)
        assertNull("系统提示词命中敏感规则：$hit，会导致 zhuli 所有请求被闸门拦截", hit)
    }
}
