package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频理解：由 AI 出计划，App 只执行（2026-11-21 用户定的架构）。
 *
 * 这里测的是「模型返回的计划怎么落地」——解析容错、越界夹取、兜底计划与提示词内容。
 */
class VideoAgentPlanTest {

    private val info = VideoNote.Info(durMs = 31_000L, width = 1080, height = 1920, hasAudio = true)

    @Test
    fun `解析标准计划`() {
        val raw = """{"frames":[0.1,0.35,0.6,0.9],"cropBand":true,"needAudio":true,"ask":"这段短剧讲了什么"}"""
        val p = VideoAgent.parsePlan(raw, info.durMs)!!
        assertEquals(listOf(3100L, 10850L, 18600L, 27900L), p.frames)
        assertTrue(p.cropBand)
        assertEquals("这段短剧讲了什么", p.ask)
    }

    @Test
    fun `模型给 10 50 90 这种写法也能夹回片内`() {
        val raw = """{"frames":[10,50,90],"cropBand":false,"needAudio":false,"ask":"x"}"""
        val p = VideoAgent.parsePlan(raw, 100_000L)!!
        // 10→1.0 越界被丢，50→0.5，90→0.9
        assertEquals(listOf(50_000L, 90_000L), p.frames)
    }

    @Test
    fun `包了代码块或解释也能取出 JSON`() {
        val raw = "好的，计划如下：\n```json\n{\"frames\":[0.5],\"cropBand\":true,\"needAudio\":true,\"ask\":\"讲了啥\"}\n```\n以上。"
        val p = VideoAgent.parsePlan(raw, 20_000L)!!
        assertEquals(listOf(10_000L), p.frames)
        assertEquals("讲了啥", p.ask)
    }

    @Test
    fun `帧太多时截到 9 个且去重排序`() {
        val nums = (1..15).joinToString(",") { (it / 20.0).toString() }
        val raw = """{"frames":[$nums,0.5,0.5],"ask":"x"}"""
        val p = VideoAgent.parsePlan(raw, 10_000L)!!
        assertEquals(9, p.frames.size)
        assertEquals(p.frames.sorted(), p.frames)
        assertEquals(p.frames.distinct(), p.frames)
    }

    @Test
    fun `解析不出来就返回 null，交给兜底计划`() {
        assertNull(VideoAgent.parsePlan("我不太确定", info.durMs))
        assertNull(VideoAgent.parsePlan("""{"cropBand":true}""", info.durMs))
        assertNull(VideoAgent.parsePlan("""{"frames":[]}""", info.durMs))
    }

    @Test
    fun `兜底计划按均匀采样`() {
        val p = VideoAgent.defaultPlan(info)
        assertEquals(VideoNote.samplingStamps(info.durMs), p.frames)
        assertTrue(p.cropBand)
    }

    @Test
    fun `计划提示词把音轨状态说清楚`() {
        assertTrue(VideoAgent.planUserPrompt(info).contains("有音轨"))
        val noAudio = info.copy(hasAudio = false)
        assertTrue(VideoAgent.planUserPrompt(noAudio).contains("没有音轨"))
    }

    @Test
    fun `汇总提示词如实交代音频没转写，避免模型编台词`() {
        val p = VideoAgent.defaultPlan(info)
        val s = VideoAgent.summaryUserPrompt(info, p, "寺内。不准踏入半步", "音轨已抽出（120KB），未转写")
        assertTrue(s.contains("未转写"))
        assertTrue(s.contains("寺内。不准踏入半步"))
        assertTrue(s.contains(p.ask))
    }
}
