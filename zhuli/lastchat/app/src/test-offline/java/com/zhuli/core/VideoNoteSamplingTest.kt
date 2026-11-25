package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视频抽帧点（2026-11-21 真机复验后调整）。
 *
 * 31 秒竖屏短剧：只取 10%/50%/90% 三帧 → 只覆盖三句字幕，且「此山石有灵」被读成「石有员」。
 * 现在按 10%~90% 均匀取 9 点，极短片仍取 3 点。
 */
class VideoNoteSamplingTest {

    @Test
    fun `三十一秒取九帧且均匀分布`() {
        val s = VideoNote.samplingStamps(31_000L)
        assertEquals(9, s.size)
        // 第一帧在 ~3.1s，最后一帧在 ~27.9s，都在片内
        // 2026-11-21 修正：实现按**毫秒**返回（grabFrames 内部再 ×1000 转微秒），
        // 旧断言按微秒写，属于测试口径过期，不是代码 bug。
        assertEquals(3_100L, s.first())
        assertEquals(27_900L, s.last())
        assertTrue(s.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `极短片仍取三帧`() {
        assertEquals(3, VideoNote.samplingStamps(4_000L).size)
    }

    @Test
    fun `时长未知时退化到第零帧`() {
        assertEquals(listOf(0L), VideoNote.samplingStamps(0L))
        assertEquals(listOf(0L), VideoNote.samplingStamps(-1L))
    }

    @Test
    fun `采样点都落在片内`() {
        for (dur in listOf(6_000L, 61_000L, 600_000L)) {
            VideoNote.samplingStamps(dur).forEach { ms ->
                assertTrue("$ms 超出时长 $dur", ms > 0 && ms < dur)
            }
        }
    }
}

/**
 * 字幕近似去重（2026-11-21 真机复验后加）。
 *
 * 同一句字幕在相邻两帧上 OCR 结果不一致（「切這第一声」vs「切远第声」），
 * 逐字完全相同才去重会让摘要里出现两行半对半错的同一句话。
 */
class VideoNoteDedupeTest {

    @Test
    fun `同一句的两种识别结果只留一行`() {
        val lines = mutableListOf<String>()
        assertTrue(VideoNote.dedupeNear(lines, "寺内。不准踏入半步"))
        // 形近换字、错位，但字符高度重合
        assertTrue(!VideoNote.dedupeNear(lines, "寺内。不准踏人半步"))
        assertEquals(1, lines.size)
    }

    @Test
    fun `不同字幕都留下`() {
        val lines = mutableListOf<String>()
        assertTrue(VideoNote.dedupeNear(lines, "我要在此凿一座佛窟"))
        assertTrue(VideoNote.dedupeNear(lines, "点火做饭"))
        assertEquals(2, lines.size)
    }

    @Test
    fun `太短的行不进摘要`() {
        val lines = mutableListOf<String>()
        assertTrue(!VideoNote.dedupeNear(lines, "一"))
        assertEquals(0, lines.size)
    }

    @Test
    fun `空白不影响比较`() {
        val lines = mutableListOf<String>()
        assertTrue(VideoNote.dedupeNear(lines, "点火 做饭"))
        assertTrue(!VideoNote.dedupeNear(lines, "点火做饭"))
        assertEquals(1, lines.size)
    }
}
