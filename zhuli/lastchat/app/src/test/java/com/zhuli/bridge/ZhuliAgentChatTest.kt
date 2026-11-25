package com.zhuli.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主聊天 Agent 桥接的**纯函数**部分测试。
 *
 * 覆盖两个最容易出错的边界（用户点名 C + B）：
 * - 普通闲聊不能被误判成 Agent 任务（否则每次聊天都变多步循环）；
 * - 「允许 / 拒绝 [任务号]」的解析必须精确，多任务时才能对应到正确任务。
 *
 * handleApproval 的持久化恢复依赖 Android Context / Koin，放在真机与模拟器阶段验，
 * 这里只钉住纯逻辑，不假装测了不该测的东西。
 */
class ZhuliAgentChatTest {

    @Test
    fun `普通闲聊不进 Agent`() {
        val chats = listOf(
            "你好",
            "今天天气怎么样",
            "给我讲个笑话",
            "1+1等于几",
            "帮我翻译这句话",
        )
        for (c in chats) {
            assertFalse("闲聊被误判成 Agent 任务：$c", ZhuliAgentChat.needsAgent(c))
        }
    }

    @Test
    fun `明确的本地数据查询与写操作才进 Agent`() {
        assertTrue("提醒创建应进 Agent", ZhuliAgentChat.needsAgent("提醒我明天九点开会"))
        assertTrue("记住类写操作应进 Agent", ZhuliAgentChat.needsAgent("记住我的住址是朝阳区"))
        assertTrue("查本地数据应进 Agent", ZhuliAgentChat.needsAgent("帮我查一下我的快递到哪了"))
    }

    @Test
    fun `审批语句解析 带任务号也能精确识别`() {
        assertEquals(true, ZhuliAgentChat.parseApproval("允许"))
        assertEquals(true, ZhuliAgentChat.parseApproval("好"))
        assertEquals(false, ZhuliAgentChat.parseApproval("拒绝"))
        assertEquals(false, ZhuliAgentChat.parseApproval("不用"))
        assertEquals(true, ZhuliAgentChat.parseApproval("允许 a1b2c3d4"))
        assertEquals(false, ZhuliAgentChat.parseApproval("拒绝 a1b2c3d4"))
        // 长句子不是裁决
        assertNull(ZhuliAgentChat.parseApproval("我觉得这件事可以允许你去做"))
        // 普通问句不是裁决
        assertNull(ZhuliAgentChat.parseApproval("我们什么时候出发"))
    }
}
