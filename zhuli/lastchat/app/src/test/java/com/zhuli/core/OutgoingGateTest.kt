package com.zhuli.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * 出网闸门测试：**重点是嵌套字段**。
 *
 * 上一版的漏洞不是"没判"，而是"只判 content"：`assistant.tool_calls[].function.arguments`
 * 是一段字符串里的 JSON，会被后续每一轮请求重新发出去，而它完全没过闸。
 * 这里用三种方式钉住：
 * 1. 纯函数层（[OutgoingGate]）逐字段检查；
 * 2. 真策略（[SensitiveGuard] / [PrivacyRouter.hardRedLine]）下的脱敏与拦截；
 * 3. 起一个假 OpenAI 端点，抓**实际发出去的完整请求体**，确认原文不在里面。
 */
class OutgoingGateTest {

    private val json = Json { prettyPrint = false }

    /** 真实策略：硬红线（证件/卡号/凭证）拦下，其余敏感脱敏。 */
    private fun realGate(payload: String) = OutgoingGate.scrub(
        payloadJson = payload,
        hardRedLine = { PrivacyRouter.hardRedLine(it) },
        sensitiveReason = { if (SensitiveGuard.isSensitive(it)) SensitiveGuard.reason(it) ?: "敏感" else null },
        redact = { SensitiveGuard.redact(it) },
    )

    private fun payload(vararg messages: String) =
        """{"model":"gpt-test","temperature":0.3,"messages":[${messages.joinToString(",")}]}"""

    private fun assistantWithCall(id: String, name: String, args: String) =
        """{"role":"assistant","content":"","tool_calls":[{"id":"$id","type":"function","function":{"name":"$name","arguments":"${esc(args)}"}}]}"""

    /** 把参数当成"字符串里的 JSON"原样嵌进去（模拟 OpenAI 的 arguments 字段）。 */
    private fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(c)
        }
    }

    @Test
    fun `tool_calls 参数里的电话和地址会被打码`() {
        val args = """{"subject":"我","predicate":"住址","value":"北京市朝阳区建国路 88 号 3 号楼","phone":"13800001111"}"""
        val out = realGate(payload(assistantWithCall("c1", "save_fact", args)))

        assertFalse("原始电话仍在请求体里", out.payload.contains("13800001111"))
        assertFalse("原始地址仍在请求体里", out.payload.contains("建国路"))
        assertTrue(out.payload.contains("[手机号]"))
        assertTrue(out.payload.contains("[地址]"))
        // 结构必须保持：tool_calls 的 id / name / arguments 都要还在，否则端点直接 400
        val root = json.parseToJsonElement(out.payload).jsonObject
        val call = root["messages"]!!.jsonArray[0].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject
        assertEquals("c1", call["id"]!!.jsonPrimitive.content)
        assertEquals("save_fact", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val newArgs = call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
        assertTrue("arguments 仍是可解析 JSON", newArgs.startsWith("{"))
        assertTrue(json.parseToJsonElement(newArgs).jsonObject.containsKey("value"))
    }

    @Test
    fun `嵌套参数里出现证件号时整轮中止`() {
        val args = """{"subject":"我","predicate":"身份证","value":"110101199003071234"}"""
        val e = runCatching { realGate(payload(assistantWithCall("c2", "save_fact", args))) }.exceptionOrNull()
        assertTrue("应当被拦下，实际：$e", e is OutgoingGate.Blocked)
        assertTrue((e as OutgoingGate.Blocked).where.contains("tool_calls"))
        assertEquals("证件号", e.reason)
    }

    @Test
    fun `tool 结果里的金额也会被打码`() {
        val toolMsg = """{"role":"tool","tool_call_id":"c1","content":"本月支出 ¥1288.50，工资到账 8000 元"}"""
        val out = realGate(payload(toolMsg))
        assertFalse(out.payload.contains("1288.5"))
        assertFalse(out.payload.contains("8000"))
        assertTrue(out.changed)
    }

    @Test
    fun `多模态数组里的文本片段同样过闸`() {
        val msg = """{"role":"user","content":[{"type":"text","text":"我电话 13800001111"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AAAA"}}]}"""
        val out = realGate(payload(msg))
        assertFalse(out.payload.contains("13800001111"))
        assertTrue(out.payload.contains("data:image/jpeg;base64,AAAA"))
    }

    @Test
    fun `取件码这类业务敏感项也走同一条闸门（策略可插拔）`() {
        // 取件码不是证件类硬红线，但同样是"用户东西"。这里用自定义策略证明
        // 闸门对嵌套字段是全覆盖的 —— 机制不依赖具体哪条正则。
        val out = OutgoingGate.scrub(
            payloadJson = payload(assistantWithCall("c3", "list_courier", """{"code":"6-2-5823"}""")),
            hardRedLine = { null },
            sensitiveReason = { if (it.contains("6-2-5823")) "取件码" else null },
            redact = { it.replace("6-2-5823", "[取件码]") },
        )
        assertFalse(out.payload.contains("6-2-5823"))
        assertTrue(out.payload.contains("[取件码]"))
        assertEquals("取件码", out.redactions.first().reason)
    }

    @Test
    fun `不是 messages 结构的载荷原样返回`() {
        val raw = """{"audio":"AAAA"}"""
        assertEquals(raw, realGate(raw).payload)
    }

    @Test
    fun `聊天载荷结构异常时中止出网 不静默放行原文`() {
        // 聊天请求（requireMessages=true）遇到结构异常必须拒绝，不能原样发出去绕过闸门。
        // 注意不能用 `prompt` 当反例：legacy completions 是**已知结构**，
        // 批次 A 起也会被正常扫描（见 PrivacyPipelineTest）。这里用真正的未知结构。
        val bad = """{"model":"gpt","foo":{"secret":"身份证 110101199003071234"}}"""
        val e = runCatching {
            OutgoingGate.scrub(
                payloadJson = bad,
                hardRedLine = { PrivacyRouter.hardRedLine(it) },
                sensitiveReason = { null },
                redact = { it },
                requireMessages = true,
            )
        }.exceptionOrNull()
        assertTrue("结构异常必须拦下，实际：$e", e is OutgoingGate.Blocked)

        // 非聊天载荷（requireMessages=false）仍原样返回，不破坏语音等接口
        val raw = """{"audio":"AAAA"}"""
        assertEquals(raw, realGate(raw).payload)
    }

    @Test
    fun `假 OpenAI 端点捕获的请求体里没有原始敏感串`() {
        // 用 ServerSocket 手写一个极简 HTTP 端点：JBR 21 的测试编译默认不含
        // com.sun.net.httpserver（jdk.httpserver 模块），不额外加模块也能跑。
        val captured = AtomicReference<String>("")
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val thread = Thread {
            try {
                val sock = server.accept()
                try {
                    val input = sock.getInputStream()
                    // 读到空行 = HTTP 头结束，再按 Content-Length 读 body
                    val header = StringBuilder()
                    var b = input.read()
                    while (b != -1) {
                        header.append(b.toChar())
                        if (header.length >= 4 && header.substring(header.length - 4) == "\r\n\r\n") break
                        b = input.read()
                    }
                    val len = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(header.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val bodyBytes = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = input.read(bodyBytes, read, len - read)
                        if (n < 0) break
                        read += n
                    }
                    captured.set(String(bodyBytes, 0, read, Charsets.UTF_8))
                    val resp = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}""".toByteArray()
                    val out = sock.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${resp.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(resp)
                    out.flush()
                } finally {
                    sock.close()
                }
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true }
        thread.start()
        try {
            val args = """{"value":"北京市朝阳区建国路 88 号","phone":"13800001111","amount":"¥45"}"""
            val gated = realGate(payload(assistantWithCall("c4", "save_fact", args))).payload
            val conn = URL("http://127.0.0.1:${server.localPort}/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(gated.toByteArray(Charsets.UTF_8)) }
            assertEquals(200, conn.responseCode)
            conn.inputStream.close()

            val body = captured.get()
            assertTrue("请求体没被捕获", body.contains("messages"))
            assertFalse("电话出网了", body.contains("13800001111"))
            assertFalse("地址出网了", body.contains("建国路"))
            assertFalse("金额出网了", body.contains("¥45"))
            assertTrue(body.contains("[手机号]"))
        } finally {
            server.close()
            thread.interrupt()
        }
    }
}
