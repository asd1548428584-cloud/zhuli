package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-B1：判定-脱敏一致性单测。
 *
 * 这一组测试的存在理由（D3 实证）：v0.15 曾经"验收通过"过脱敏，但验收只测了 [SensitiveGuard.reason]
 * 判不判得出，没测 [SensitiveGuard.redact] 到底替换没替换。结果地址和"公司+岗位"被判成敏感、
 * 标了 redacted=true，原文照样发到云端。
 *
 * 所以核心断言是**不变量**：reason(x) 命中 ⇒ reason(redact(x)) 必须为 null。
 * 换句话说：脱敏之后，它自己都不该再被认成敏感。
 */
class SensitiveGuardTest {

    /** 每一类都要有一条：类型 → 原始句子。 */
    private val samples = mapOf(
        "电话" to "我手机号 13800001111，回头联系",
        "证件号" to "身份证 110101199003071234 帮我记一下",
        "卡号" to "卡号 6222021234567890123 转过去",
        "金额" to "这周花了 545.60 元",
        "凭证" to "验证码是 8823，十分钟内有效",
        "地址" to "我家在朝阳区建国路 88 号 3 号楼 2 单元",
        "公司+岗位" to "我们公司市场部经理姓王",
        // 批次 A 新增：快递取件码与运单号此前完全在规则之外
        "取件码" to "取件码 8-1-2233，放门口就行",
        "运单号" to "运单号 SF1234567890123 帮我查一下",
        // 批次 A 新增：定位占位符退化成的裸坐标此前无规则覆盖
        "坐标" to "我在 39.9042,116.4074 附近"
    )

    @Test
    fun `每类敏感内容都能被判出来`() {
        samples.forEach { (type, text) ->
            assertEquals("应判定为 $type：$text", type, SensitiveGuard.reason(text))
        }
    }

    @Test
    fun `脱敏之后自身不再被判定为敏感（判定-脱敏一致性）`() {
        samples.forEach { (type, text) ->
            val redacted = SensitiveGuard.redact(text)
            assertNull("$type 脱敏后仍被判敏感：$redacted", SensitiveGuard.reason(redacted))
        }
    }

    @Test
    fun `脱敏后原始片段不再出现`() {
        val phone = SensitiveGuard.redact("我手机号 13800001111")
        assertFalse("手机号残留：$phone", phone.contains("13800001111"))

        val idCard = SensitiveGuard.redact("身份证 110101199003071234")
        assertFalse("证件号残留：$idCard", idCard.contains("110101199003071234"))

        // D3 的两类：地址与公司+岗位
        val addr = SensitiveGuard.redact("我家在朝阳区建国路 88 号 3 号楼 2 单元")
        assertFalse("地址残留：$addr", addr.contains("建国路"))
        assertTrue("地址应留占位符：$addr", addr.contains("[地址]"))

        val org = SensitiveGuard.redact("我们公司市场部经理姓王")
        assertTrue("单位应留占位符：$org", org.contains("[公司]"))
        assertTrue("岗位应留占位符：$org", org.contains("[岗位]"))
        // 注意不能断言「不含公司」——占位符 [公司] 本身就含这两个字。
        // 要断言的是原始上下文被替换掉了。
        assertFalse("原始单位上下文残留：$org", org.contains("我们公司"))
        assertFalse("岗位原文残留：$org", org.contains("经理"))
    }

    @Test
    fun `非敏感句子不做任何改写`() {
        val plain = listOf(
            "帮我把今天的事捋一捋",
            "明天下午三点开会别忘",
            "我知道这条路怎么走"   // 单字"路"不能触发地址规则
        )
        plain.forEach { text ->
            assertNull("不该判敏感：$text", SensitiveGuard.reason(text))
            assertEquals("不该改写：$text", text, SensitiveGuard.redact(text))
        }
    }

    @Test
    fun `公司或岗位单独出现时不打码（组合判定防误伤）`() {
        val onlyOrg = "我公司楼下的咖啡不错"
        val onlyRole = "他当经理好几年了"
        assertNull("单个「公司」不该判敏感", SensitiveGuard.reason(onlyOrg))
        assertEquals("单个「公司」不该打码", onlyOrg, SensitiveGuard.redact(onlyOrg))
        assertNull("单个「经理」不该判敏感", SensitiveGuard.reason(onlyRole))
        assertEquals("单个「经理」不该打码", onlyRole, SensitiveGuard.redact(onlyRole))
    }

    @Test
    fun `带空格的地址要连门牌一起吃掉`() {
        // T-B6 真机跑出来的：第一版正则不容忍空格，只匹配到「朝阳区建国路」，
        // 「88 号 3 号楼 2 单元」原样留在请求体里。
        val text = "另外我家在朝阳区建国路 88 号 3 号楼 2 单元"
        val r = SensitiveGuard.redact(text)
        assertFalse("街道残留：$r", r.contains("建国路"))
        assertFalse("门牌残留：$r", r.contains("88 号"))
        assertFalse("楼栋残留：$r", r.contains("3 号楼"))
        assertFalse("单元残留：$r", r.contains("2 单元"))
        assertNull("脱敏后仍判敏感：$r", SensitiveGuard.reason(r))
    }

    @Test
    fun `脱敏后原始命中片段逐条消失`() {
        // 比「不再判敏感」更强：拿 hits() 抽出的原始片段去比对，片段还在就不算过。
        samples.forEach { (type, text) ->
            val r = SensitiveGuard.redact(text)
            val leaked = SensitiveGuard.hits(text)
                .filter { it.isNotBlank() && r.contains(it) && !r.contains("[$it]") }
            assertTrue("$type 仍有片段残留：$leaked → $r", leaked.isEmpty())
        }
    }

    @Test
    fun `hits 对非敏感句子返回空`() {
        assertTrue(SensitiveGuard.hits("明天下午三点开会别忘").isEmpty())
        assertTrue(SensitiveGuard.hits("我知道这条路怎么走").isEmpty())
    }

    @Test
    fun `脱敏是幂等的`() {
        samples.values.forEach { text ->
            val once = SensitiveGuard.redact(text)
            assertEquals("二次脱敏不应再变：$once", once, SensitiveGuard.redact(once))
        }
    }

    @Test
    fun `红线类型必须能被识别（供 PrivacyRouter 拦截）`() {
        listOf("证件号", "卡号", "凭证").forEach { type ->
            val text = samples.getValue(type)
            assertNotNull("$type 应被识别", SensitiveGuard.reason(text))
        }
    }
}
