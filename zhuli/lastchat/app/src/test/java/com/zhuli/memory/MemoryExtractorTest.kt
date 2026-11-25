package com.zhuli.memory

import com.zhuli.core.InboxRecord
import org.junit.Assert.*
import org.junit.Test

/** 规则抽取的「宁少不错」回归测试：宁可漏，也不能把噪声写成用户的事实。 */
class MemoryExtractorTest {

    private fun rec(
        title: String,
        text: String = "",
        note: String = "",
        source: String = InboxRecord.SOURCE_NOTIFICATION,
        amount: Double = 0.0,
        deadlineAt: Long = 0L,
        label: String = "测试App",
    ) = InboxRecord(
        source = source, packageName = "test.app", appLabel = label, title = title,
        text = text, note = note, receivedAt = 1_700_000_000_000L,
        amount = amount, deadlineAt = deadlineAt,
    )

    @Test fun `取件码必须有标签 且认出驿站主体`() {
        val drafts = MemoryExtractor.extract(rec("菜鸟驿站", "您的取件码：1234，地址北京市朝阳区"))
        val pickup = drafts.firstOrNull { it.predicate == "取件码" }
        assertNotNull(pickup)
        assertEquals("1234", pickup!!.value)
        assertEquals("菜鸟驿站", pickup.subject)
        assertTrue(pickup.confidence >= 0.9)
    }

    @Test fun `纯数字串不会被误认成取件码`() {
        val drafts = MemoryExtractor.extract(rec("账单", "您的订单尾号 123456 已生成"))
        assertTrue(drafts.none { it.kind == FactKind.EXPRESS })
    }

    @Test fun `收入信号决定金额谓词`() {
        val d = MemoryExtractor.extract(rec("收款通知", "您已到账 ¥128.50")).firstOrNull { it.kind == FactKind.MONEY }
        assertNotNull(d)
        assertEquals("收入", d!!.predicate)
        assertEquals("¥128.50", d.value)
    }

    @Test fun `手机号被抽成高置信事实`() {
        val d = MemoryExtractor.extract(rec("联系人", "电话 13812345678"))
        assertTrue(d.any { it.kind == FactKind.PHONE && it.value == "13812345678" && it.confidence >= 0.9 })
    }

    @Test fun `地址是不确定推断 必须低置信`() {
        val d = MemoryExtractor.extract(rec("配送通知", "收货地址：北京市朝阳区建国路88号")).firstOrNull { it.kind == FactKind.ADDRESS }
        assertNotNull(d)
        assertTrue(d!!.confidence < 0.5)
    }

    @Test fun `待办只从用户自己说的话里抽`() {
        val fromChat = MemoryExtractor.extract(rec("提醒我交报表", source = InboxRecord.SOURCE_CHAT))
        assertTrue(fromChat.any { it.kind == FactKind.TODO })
        val fromAd = MemoryExtractor.extract(rec("记得领红包", source = InboxRecord.SOURCE_NOTIFICATION))
        assertTrue(fromAd.none { it.kind == FactKind.TODO })
    }

    @Test fun `标题噪声被清掉 不影响取件码`() {
        val d = MemoryExtractor.extract(rec("[2条] 菜鸟驿站(广告)", "取件码 9527"))
        assertEquals("9527", d.first { it.predicate == "取件码" }.value)
        assertTrue(d.first { it.predicate == "取件码" }.sourceText.contains("菜鸟驿站"))
    }

    @Test fun `归一化统一全角与大小写`() {
        assertEquals("abc123", MemoryExtractor.norm("ＡＢＣ １２３"))
    }

    @Test fun `没有可抽内容时返回空表 不造假事实`() {
        assertTrue(MemoryExtractor.extract(rec("系统通知", "应用更新完成")).isEmpty())
    }
}
