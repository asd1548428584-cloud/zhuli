package com.zhuli.bridge

import kotlinx.serialization.builtins.ListSerializer
import me.rerere.ai.ui.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真机缺陷复现（2026-11-24，Z70 Ultra）：
 * 冷启动时 ChatService.initializeConversation 从 DB 加载会话，
 * conversationEntityToConversation 用 JsonInstant 解码 nodes；
 * 解码一旦失败会静默得到 emptyList()（只写 logcat，本 ROM logcat 恒空），
 * 于是「会话消息为空」→ 新对话欢迎语（晚上好/建议 chips）叠加在
 * 已有对话的消息列表上，输入框双层。消息列表走 DB 分页另一条路径，所以有消息显示。
 *
 * 本测试用真机导出的真实 nodes JSON 验证解码必须成功且非空。
 * JSON 文件：/issue_coldstart_nodes.json（对话 "hello"→assistant 回复）。
 */
class ConversationNodesDecodeTest {

    private fun loadNodesJson(): String {
        val stream = javaClass.getResourceAsStream("/issue_coldstart_nodes.json")
            ?: error("测试资源 issue_coldstart_nodes.json 不存在")
        return stream.bufferedReader(Charsets.UTF_8).readText()
    }

    @Test
    fun `真机导出的nodes必须完整解码且消息非空`() {
        val json = loadNodesJson()
        val nodes = JsonInstant.decodeFromString(ListSerializer(MessageNode.serializer()), json)
        assertTrue("解码后应得到 2 个消息节点，实际 ${nodes.size}", nodes.size == 2)
        val totalMessages = nodes.sumOf { it.messages.size }
        assertTrue(
            "解码后消息总数应为 2（user hello + assistant 回复），实际 $totalMessages",
            totalMessages == 2,
        )
        // 助手消息必须保留 Reasoning part（此前字段类型不匹配会在静默 catch 中丢失一切）
        val assistantMsg = nodes[1].messages.first()
        assertTrue(
            "assistant 消息应包含 Reasoning part，实际 parts=${assistantMsg.parts.map { it::class.simpleName }}",
            assistantMsg.parts.any { it is me.rerere.ai.ui.UIMessagePart.Reasoning },
        )
    }
}
