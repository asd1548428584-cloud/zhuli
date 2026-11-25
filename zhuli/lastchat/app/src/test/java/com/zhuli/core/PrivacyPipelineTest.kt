package com.zhuli.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 A：统一隐私管线的结构覆盖测试。
 *
 * 为什么需要这么多形态：历史缺陷 D1/D2/D3 的共同点是「判定层通过、请求体里还在」，
 * 而漏点全部出在**非 content 字段**上。这里逐种结构钉住，避免下次换一家 provider
 * 就把整类请求变成扫描盲区。
 *
 * 断言口径统一为两条：
 * 1. 原始敏感串**不出现在**输出里；
 * 2. 结构不被破坏（仍是合法 JSON，关键字段还在）。
 */
class PrivacyPipelineTest {

    private val json = Json { ignoreUnknownKeys = true }
    private fun parse(s: String) = json.parseToJsonElement(s).jsonObject

    private fun raw(payload: String) = PrivacyPipeline.scrub(payload, PrivacyPipeline.Rules()).payload

    // ==================== chat/completions ====================

    @Test
    fun `chat content 里的手机号被打码`() {
        val out = raw(
            """{"model":"m","messages":[{"role":"user","content":"我手机号 13800001111"}]}"""
        )
        assertFalse(out.contains("13800001111"))
        assertTrue(out.contains("[手机号]"))
        assertEquals("user", parse(out)["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool_calls 参数里的地址与金额被打码且仍是合法 JSON`() {
        val args = """{"value":"北京市朝阳区建国路 88 号 3 号楼","amount":"¥45"}"""
        val out = raw(
            """{"model":"m","messages":[{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"save_fact","arguments":"${esc(args)}"}}]}]}"""
        )
        assertFalse("地址残留", out.contains("建国路"))
        assertFalse("金额残留", out.contains("¥45"))
        val newArgs = parse(out)["messages"]!!.jsonArray[0].jsonObject["tool_calls"]!!
            .jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        assertTrue("参数应仍是合法 JSON", json.parseToJsonElement(newArgs).jsonObject.containsKey("value"))
    }

    @Test
    fun `tool_calls 参数里的证件号整轮中止`() {
        val args = """{"value":"110101199003071234"}"""
        val payload =
            """{"model":"m","messages":[{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"save_fact","arguments":"${esc(args)}"}}]}]}"""
        val e = runCatching { raw(payload) }.exceptionOrNull()
        assertTrue("应被拦下，实际：$e", e is PrivacyPipeline.Blocked)
        assertTrue((e as PrivacyPipeline.Blocked).where.contains("tool_calls"))
        assertEquals("证件号", e.reason)
    }

    @Test
    fun `旧式 function_call 参数同样过闸`() {
        val out = raw(
            """{"model":"m","messages":[{"role":"assistant","content":"","function_call":{"name":"save","arguments":"{\"phone\":\"13800001111\"}"}}]}"""
        )
        assertFalse(out.contains("13800001111"))
        assertTrue(out.contains("[手机号]"))
    }

    @Test
    fun `多模态文本片段过闸而图片不动`() {
        val inc = """{"model":"m","messages":[{"role":"user","content":[{"type":"text","text":"我电话 13800001111"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA"}}]}]}"""
        val out = raw(inc)
        assertFalse(out.contains("13800001111"))
        assertTrue(out.contains("data:image/jpeg;base64,AAAA"))
    }

    // ==================== Responses API ====================

    @Test
    fun `responses 的 input 字符串会被扫描`() {
        val out = raw("""{"model":"m","input":"我电话 13800001111"}""")
        assertFalse(out.contains("13800001111"))
        assertTrue(out.contains("[手机号]"))
    }

    @Test
    fun `responses 的 input 分段数组会被扫描`() {
        val out = raw(
            """{"model":"m","input":[{"role":"user","content":[{"type":"input_text","text":"我住朝阳区建国路 88 号 3 号楼"}]}]}"""
        )
        assertFalse(out.contains("建国路"))
        assertTrue(out.contains("[地址]"))
    }

    @Test
    fun `responses 的 function_call 参数与 instructions 都会被扫描`() {
        val out = raw(
            """{"model":"m","instructions":"用户手机号 13800001111","input":[{"type":"function_call","name":"save","arguments":"{\"code\":\"8-1-2233\"}"}]}"""
        )
        assertFalse("instructions 里的手机号残留", out.contains("13800001111"))
        assertFalse("工具参数里的取件码残留", out.contains("8-1-2233"))
        assertTrue(out.contains("[手机号]"))
        assertTrue(out.contains("[取件码]"))
    }

    @Test
    fun `responses 的 function_call_output 会被扫描`() {
        val out = raw("""{"model":"m","input":[{"type":"function_call_output","call_id":"c1","output":"取件码 1234"}]}""")
        assertFalse(out.contains("1234"))
        assertTrue(out.contains("[取件码]"))
    }

    // ==================== 其他 provider 结构 ====================

    @Test
    fun `claude 的 tool_use input 会被扫描`() {
        val out = raw(
            """{"model":"claude","system":"你是助理","messages":[{"role":"user","content":[{"type":"tool_use","name":"save","input":{"phone":"13800001111"}}]}]}"""
        )
        assertFalse(out.contains("13800001111"))
        assertTrue(out.contains("[手机号]"))
    }

    @Test
    fun `gemini 的 contents 与 functionCall args 会被扫描`() {
        val out = raw(
            """{"contents":[{"role":"user","parts":[{"text":"我电话 13800001111"},{"functionCall":{"name":"save","args":{"code":"8-1-2233"}}}]}]}"""
        )
        assertFalse(out.contains("13800001111"))
        assertFalse(out.contains("8-1-2233"))
        assertTrue(out.contains("[手机号]"))
        assertTrue(out.contains("[取件码]"))
    }

    @Test
    fun `embeddings 的 input 数组会被扫描`() {
        val out = raw("""{"model":"emb","input":["我电话 13800001111","明天开会"]}""")
        assertFalse(out.contains("13800001111"))
        assertTrue(out.contains("明天开会"))
    }

    @Test
    fun `legacy completions 的 prompt 会被扫描`() {
        val out = raw("""{"model":"davinci","prompt":"我电话 13800001111"}""")
        assertFalse(out.contains("13800001111"))
    }

    // ==================== fail-closed ====================

    @Test
    fun `未知结构在 requireKnownShape 下必须中止`() {
        val e = runCatching { raw("""{"model":"m","foo":"身份证 110101199003071234"}""") }.exceptionOrNull()
        assertTrue("应被拦下，实际：$e", e is PrivacyPipeline.Blocked)
    }

    @Test
    fun `未知结构在明确允许时可以原样返回`() {
        val inc = """{"audio":"AAAA"}"""
        val out = PrivacyPipeline.scrub(inc, PrivacyPipeline.Rules(), requireKnownShape = false)
        assertEquals(inc, out.payload)
        assertEquals(PrivacyPipeline.Shape.UNKNOWN, out.shape)
    }

    @Test
    fun `非法 JSON 必须中止而不是原样放行`() {
        val e = runCatching { raw("{not json") }.exceptionOrNull()
        assertTrue("应被拦下，实际：$e", e is PrivacyPipeline.Blocked)
    }

    @Test
    fun `strict 模式下任何敏感项都中止`() {
        val payload = """{"model":"m","messages":[{"role":"user","content":"我手机号 13800001111"}]}"""
        val e = runCatching {
            PrivacyPipeline.scrub(payload, PrivacyPipeline.Rules(strict = true))
        }.exceptionOrNull()
        assertTrue("strict 应拦下，实际：$e", e is PrivacyPipeline.Blocked)
        assertEquals("电话", (e as PrivacyPipeline.Blocked).reason)
    }

    @Test
    fun `没有敏感项时原样返回且不重新序列化`() {
        val payload = """{"model":"m","messages":[{"role":"user","content":"明天下午三点开会"}]}"""
        val out = PrivacyPipeline.scrub(payload, PrivacyPipeline.Rules())
        assertEquals(payload, out.payload)
        assertTrue(out.redactions.isEmpty())
        assertEquals(1, out.scannedFields)
        assertEquals(PrivacyPipeline.Shape.CHAT_COMPLETIONS, out.shape)
    }

    @Test
    fun `脱敏计数与字段计数可用于留档`() {
        val out = PrivacyPipeline.scrub(
            """{"model":"m","messages":[{"role":"user","content":"电话 13800001111，地址北京市朝阳区建国路 88 号"}]}""",
            PrivacyPipeline.Rules(),
        )
        // 计数口径是「被改写的字段数」，不是「替换了几处」：
        // 同一句里手机号和地址一起被打码，仍然只算一个字段。
        assertEquals(1, out.scannedFields)
        assertEquals(1, out.redactions.size)
        assertEquals("电话", out.redactions.first().reason)
    }

    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(c)
        }
    }
}
