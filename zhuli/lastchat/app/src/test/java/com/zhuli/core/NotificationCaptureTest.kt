package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知正文解析的纯文本规则（2026-11-24）。
 *
 * 这些规则直接决定"哪条进收件箱"：
 * - 媒体播放条被当成消息 → 对话里会莫名多出一条"某视频"；
 * - 汇总条被收下 → 微信只剩"3 条新消息"，看不到内容；
 * - 说话人拆不出来 → 群聊不知道是谁说的。
 * 装到真机上才发现代价太高，先把规则钉死。
 */
class NotificationCaptureTest {

    // ==================== 播放控制 ====================

    @Test
    fun `播放进度条算媒体文案`() {
        assertTrue(NotificationCapture.isPlaybackText("03:21 / 04:58"))
        assertTrue(NotificationCapture.isPlaybackText("03:21"))
        assertTrue(NotificationCapture.isPlaybackText("正在播放"))
        assertTrue(NotificationCapture.isPlaybackText("暂停"))
        assertTrue(NotificationCapture.isPlaybackText("下一首"))
    }

    @Test
    fun `普通聊天内容不算媒体文案`() {
        assertFalse(NotificationCapture.isPlaybackText("你好，晚上一起吃饭吗"))
        assertFalse(NotificationCapture.isPlaybackText("这个视频的 03:21 处很好看")) // 长句不按纯进度条判
        assertFalse(NotificationCapture.isPlaybackText("会议推迟到 3 点"))
    }

    // ==================== 汇总条 ====================

    @Test
    fun `聚合文案算汇总`() {
        assertTrue(NotificationCapture.isSummaryText("3 条新消息"))
        assertTrue(NotificationCapture.isSummaryText("你有 2 条未读"))
        assertTrue(NotificationCapture.isSummaryText("您收到一条新消息"))
        assertTrue(NotificationCapture.isSummaryText("还有 5 条消息"))
    }

    @Test
    fun `正常消息不算汇总`() {
        assertFalse(NotificationCapture.isSummaryText("晚上 3 条鱼够不够"))
        assertFalse(NotificationCapture.isSummaryText("张三：3 条新消息"))
    }

    // ==================== 说话人拆分 ====================

    @Test
    fun `群聊拆出说话人`() {
        val p = NotificationCapture.splitSpeaker("张三：你好，这是一条测试消息")
        assertEquals("张三", p?.first)
        assertEquals("你好，这是一条测试消息", p?.second)
    }

    @Test
    fun `英文冒号也能拆`() {
        val p = NotificationCapture.splitSpeaker("Alice: hi there")
        assertEquals("Alice", p?.first)
        assertEquals("hi there", p?.second)
    }

    @Test
    fun `银行短信不拆成说话人`() {
        // 「【中国银行】」带括号，不应当作人名
        assertNull(NotificationCapture.splitSpeaker("【中国银行】您尾号 1234 的账户入账 500 元"))
        // 左侧过长（>12 字）也不拆
        assertNull(NotificationCapture.splitSpeaker("这是一段很长很长的通知标题：正文"))
        // 冒号后没内容不拆
        assertNull(NotificationCapture.splitSpeaker("张三："))
    }

    // ==================== 清洗 ====================

    @Test
    fun `换行与连续空白压平`() {
        assertEquals("张三 你好 在吗", NotificationCapture.normalize("  张三\n\n你好\t\t在吗  "))
        assertEquals("a b", NotificationCapture.normalize("a\u00A0b"))
    }

    @Test
    fun `拦截原因有人话说明`() {
        assertEquals("视频/音乐播放", NotificationCapture.describe("媒体播放通知"))
        assertEquals("群聊汇总", NotificationCapture.describe("群聊汇总条"))
        assertEquals("不在白名单", NotificationCapture.describe("不在白名单"))
    }
}
