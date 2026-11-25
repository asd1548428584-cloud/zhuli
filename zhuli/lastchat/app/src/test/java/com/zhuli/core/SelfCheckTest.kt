package com.zhuli.core

// [zhuli 2026-11-23] 新壳把自检面板的纯渲染逻辑搬到了 bridge/ZhuliSelfCheck；
// 旧测试仍按 SelfCheck 命名调用，这里用别名对齐，测试内容一字未改。
import com.zhuli.bridge.ZhuliSelfCheck as SelfCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 G：自检面板的判定规则。
 *
 * 这里只测"什么算正常、什么算断了"——阈值与文案改错时先在这里红，
 * 不用装到模拟器上点开面板才发现"3 天没数据竟然显示绿灯"。
 */
class SelfCheckTest {

    private val H = 3_600_000L
    private val now = 1_700_000_000_000L

    // ==================== 总评 ====================

    @Test
    fun `空列表是正常`() {
        assertEquals(SelfCheck.Level.OK, SelfCheck.overall(emptyList()))
    }

    @Test
    fun `一条断了就整体算断（不被平均掉）`() {
        val items = listOf(
            SelfCheck.ingest(now - H, now, hasAccess = true),
            SelfCheck.battery(exempt = true),
            SelfCheck.ingest(0L, now, hasAccess = false)
        )
        assertEquals(SelfCheck.Level.BAD, SelfCheck.overall(items))
    }

    @Test
    fun `没有断但有注意时总评是注意`() {
        val items = listOf(
            SelfCheck.ingest(now - H, now, hasAccess = true),
            SelfCheck.battery(exempt = false)
        )
        assertEquals(SelfCheck.Level.WARN, SelfCheck.overall(items))
    }

    @Test
    fun `headline 数出各有几项`() {
        val items = listOf(
            SelfCheck.ingest(0L, now, hasAccess = false),
            SelfCheck.battery(exempt = false),
            SelfCheck.exactAlarm(can = false),
            SelfCheck.battery(exempt = true)
        )
        assertEquals("1 项断了，先修它", SelfCheck.headline(items))
        assertEquals("全部正常", SelfCheck.headline(listOf(SelfCheck.battery(exempt = true))))
    }

    // ==================== 渲染 ====================

    @Test
    fun `同一组只出一个标题，且保持传入顺序`() {
        val items = listOf(
            SelfCheck.ingest(now - H, now, true),
            SelfCheck.todayCount(3, 100),
            SelfCheck.battery(true)
        )
        val text = SelfCheck.render(items)
        assertEquals(1, Regex("【" + Regex.escape(SelfCheck.G_COLLECT) + "】").findAll(text).count())
        assertTrue(text.indexOf(SelfCheck.G_COLLECT) < text.indexOf(SelfCheck.G_ALIVE))
    }

    @Test
    fun `断了用叉，注意用叹号，正常用对勾`() {
        assertEquals("❌", SelfCheck.mark(SelfCheck.Level.BAD))
        assertEquals("⚠️", SelfCheck.mark(SelfCheck.Level.WARN))
        assertEquals("✅", SelfCheck.mark(SelfCheck.Level.OK))
    }

    @Test
    fun `相对时间分档，负数按刚刚`() {
        assertEquals("刚刚", SelfCheck.ago(-5000))
        assertEquals("刚刚", SelfCheck.ago(30_000))
        assertEquals("5 分钟前", SelfCheck.ago(5 * 60_000L))
        assertEquals("3 小时前", SelfCheck.ago(3 * H))
        assertEquals("2 天前", SelfCheck.ago(48 * H))
    }

    // ==================== 采集 ====================

    @Test
    fun `没通知使用权直接算断`() {
        val it = SelfCheck.ingest(now - H, now, hasAccess = false)
        assertEquals(SelfCheck.Level.BAD, it.level)
        assertTrue(it.value.contains("收不到"))
    }

    @Test
    fun `有权限但从来没收到过是注意`() {
        assertEquals(SelfCheck.Level.WARN, SelfCheck.ingest(0L, now, true).level)
    }

    @Test
    fun `24 小时内算通，超过 24 小时提醒可能被掐，超过 72 小时算断`() {
        assertEquals(SelfCheck.Level.OK, SelfCheck.ingest(now - 2 * H, now, true).level)
        assertEquals(SelfCheck.Level.WARN, SelfCheck.ingest(now - 30 * H, now, true).level)
        assertEquals(SelfCheck.Level.BAD, SelfCheck.ingest(now - 80 * H, now, true).level)
    }

    // ==================== 提醒 ====================

    @Test
    fun `没有待响提醒是正常，不是问题`() {
        assertEquals(SelfCheck.Level.OK, SelfCheck.alarms(0, 0L, now).level)
    }

    @Test
    fun `有待响但最近的已过点要提醒补排`() {
        val it = SelfCheck.alarms(2, now - 10 * 60_000L, now)
        assertEquals(SelfCheck.Level.WARN, it.level)
        assertTrue(it.value.contains("补排"))
    }

    @Test
    fun `设了围栏却没定位权限，属于静默失效要标注意`() {
        val it = SelfCheck.fence(homeSet = true, workSet = false, hasPermission = false)
        assertEquals(SelfCheck.Level.WARN, it.level)
        assertTrue(it.value.contains("不会响"))
    }

    @Test
    fun `没设围栏是正常，设了且有权限也是正常`() {
        assertEquals(SelfCheck.Level.OK, SelfCheck.fence(false, false, false).level)
        assertEquals(SelfCheck.Level.OK, SelfCheck.fence(true, true, true).level)
    }

    // ==================== 推断 / 出网 ====================

    @Test
    fun `待确认堆过阈值才算误判风险`() {
        assertEquals(SelfCheck.Level.OK, SelfCheck.inferred(3).level)
        assertEquals(SelfCheck.Level.WARN, SelfCheck.inferred(SelfCheck.BACKLOG_WARN + 1).level)
    }

    @Test
    fun `从没出过网要明说 0 次`() {
        val it = SelfCheck.cloud(0, 0L, now, ready = false)
        assertEquals(SelfCheck.Level.OK, it.level)
        assertTrue(it.value.contains("0 次"))
        assertTrue(it.value.contains("还没往外发过"))
        // 2026-11-24：没出网时不再补一句"云 Key 未配置"——那是 AssistantPrefs 的口径，
        // 与实际 provider 配置无关，属于假信息。
        assertFalse(it.value.contains("未配置"))
    }

    @Test
    fun `出网时间要算相对差，不能把时间戳当间隔（否则会出现 692 个月前）`() {
        val it = SelfCheck.cloud(12, now - 3 * H, now, ready = false)
        assertTrue(it.value.contains("12 次"))
        assertTrue(it.value.contains("3 小时前"))
        // 脏时间戳不许编数字
        assertTrue(SelfCheck.cloud(3, 123L, now, false).value.contains("时间未知"))
    }

    @Test
    fun `查不到的权限必须标需手动确认，不能假绿勾`() {
        val it = SelfCheck.autoStart()
        assertTrue(it.value.contains("需手动确认"))
    }

    @Test
    fun `本应用通知被关算断了（排了闹钟也不会显示）`() {
        val it = SelfCheck.selfNotify(enabled = false)
        assertEquals(SelfCheck.Level.BAD, it.level)
        assertTrue(it.value.contains("不会显示"))
        assertEquals(SelfCheck.Level.OK, SelfCheck.selfNotify(true).level)
    }
}
