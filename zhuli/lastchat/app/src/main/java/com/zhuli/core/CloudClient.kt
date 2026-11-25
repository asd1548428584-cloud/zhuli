package com.zhuli.core

import android.content.Context
import com.zhuli.core.ZhuliLog as Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.regex.Pattern

/**
 * 出网统一出口（第 9 步）。
 *
 * 为什么要把散落的 `openConnection()` 收进来：
 * 第 4/3/8 步各写了一份 HTTP 调用（闲聊、链接摘要、语音转写），三份超时/错误处理/留档各不一样，
 * 而且「有没有过敏感闸门」全靠调用方自觉。收到这里之后：
 * - 每次出网都先经 [PrivacyRouter] 判定（不敏感、配了 Key、地址是 HTTPS）；
 * - 成功/失败都往 `cloud_log` 写一条**脱敏摘要**（需求 §3.3），壳里能查；
 * - 传输安全：非回环地址必须是 HTTPS（`res/xml/network_security_config.xml` 也从系统层面兜一层）；
 * - Key 只从 [SecureKeyStore] 取，不落明文、不进日志。
 *
 * 不做什么：不内置任何服务商、不做重试风暴（失败就老实降级本地，让用户看到「本地兜底」）。
 */
object CloudClient {

    private const val TAG = "zhuli.cloud"

    /** 一次调用用的端点信息。 */
    data class Endpoint(val base: String, val key: String, val model: String) {
        val host: String get() = try { URL(base).host } catch (t: Throwable) { base }
    }

    fun endpoint(ctx: Context): Endpoint? {
        val base = AssistantPrefs.linkBaseUrl(ctx).trimEnd('/')
        val key = SecureKeyStore.get(ctx)
        if (base.isBlank() || key.isBlank()) return null
        if (!isEndpointSafe(base)) return null
        return Endpoint(base, key, AssistantPrefs.linkModel(ctx).ifBlank { "auto" })
    }

    /** 地址安全：必须是 https，或本机回环（adb reverse 自测用）。 */
    fun isEndpointSafe(base: String): Boolean {
        val b = base.trim().lowercase()
        if (b.startsWith("https://")) return true
        if (b.startsWith("http://")) {
            val host = b.removePrefix("http://").substringBefore('/').substringBefore(':')
            return host == "127.0.0.1" || host == "localhost" || host == "::1"
        }
        return false
    }

    /**
     * 闲聊/摘要类调用：OpenAI 兼容 `/chat/completions`。
     * 调用方必须先过 [PrivacyRouter.decide]；这里再兜一次（纵深防御：万一忘了，宁可不发）。
     */
    fun chat(
        ctx: Context,
        task: PrivacyRouter.Task,
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        temperature: Double = 0.7
    ): String {
        val ep = endpoint(ctx) ?: throw IllegalStateException("云通道未配置")
        val verdict = PrivacyRouter.decide(ctx, task, user)
        if (!verdict.allow) {
            PrivacyRouter.audit(ctx, task, ep.host, ep.model, user, ok = false, note = "闸门拦截：${verdict.reason}")
            throw IllegalStateException(verdict.reason)
        }
        // 实际发出去的内容 = 闸门给出的（可能已脱敏）
        val outgoing = verdict.text.ifBlank { user }

        val payload = buildString {
            append("{\"model\":\"").append(json(ep.model))
            append("\",\"temperature\":").append(temperature)
            append(",\"messages\":[{\"role\":\"system\",\"content\":\"").append(json(gateText(ctx, task, "system", system))).append("\"}")
            for ((role, content) in history) {
                if (role != "user" && role != "assistant") continue
                append(",{\"role\":\"").append(role).append("\",\"content\":\"").append(json(gateText(ctx, task, "历史", content))).append("\"}")
            }
            // T-B2（D2）：这里原来用 json(user) —— 判定层已经把敏感项脱敏成 verdict.text，
            // 但组包时又拿回原文，等于脱敏白做。改用 outgoing。
            append(",{\"role\":\"user\",\"content\":\"").append(json(outgoing)).append("\"}]}")
        }

        return post(ctx, task, ep, "/chat/completions", payload) { resp ->
            val m = Pattern.compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(resp)
            if (!m.find()) throw IllegalStateException("返回解析失败")
            unescape(m.group(1)!!).trim().ifBlank { throw IllegalStateException("空回复") }
        }
    }

    /**
     * 带工具调用的对话（本次重构的核心）。
     *
     * 一轮最多两次往返：模型要工具 → 我们本地执行 → 结果回灌 → 模型给最终答复。
     * 为什么只给两轮：再多容易在手机上卡住几十秒，而且真要连查三次的事，用户会更希望直接说清。
     */
    fun chatWithTools(
        ctx: Context,
        task: PrivacyRouter.Task,
        system: String,
        history: List<Pair<String, String>>,
        user: String,
        db: InboxDatabase,
        temperature: Double = 0.6
    ): String {
        val ep = endpoint(ctx) ?: throw IllegalStateException("云通道未配置")
        val verdict = PrivacyRouter.decide(ctx, task, user)
        if (!verdict.allow) {
            PrivacyRouter.audit(ctx, task, ep.host, ep.model, user, ok = false, note = "闸门拦截：${verdict.reason}")
            throw IllegalStateException(verdict.reason)
        }
        val outgoing = verdict.text.ifBlank { user }

        // T-B3（D1）：闸门只判用户这一句是不够的 —— "问句不敏感，但查出来的记忆里有地址/取件码/金额"
        // 这条路径会把原文送出去。system、历史、工具返回值一样要逐条过闸：
        // 可脱敏的脱敏后照发（§11-8 的 a），命中证件/卡号/凭证这类红线就整轮中止、回落本地（§11-8 的 b）。
        val msgs = org.json.JSONArray()
        msgs.put(org.json.JSONObject().put("role", "system").put("content", gateText(ctx, task, "system", system)))
        for ((r, c) in history) {
            if (r != "user" && r != "assistant") continue
            msgs.put(org.json.JSONObject().put("role", r).put("content", gateText(ctx, task, "历史", c)))
        }
        msgs.put(org.json.JSONObject().put("role", "user").put("content", outgoing))

        var rounds = 0
        var answer = ""
        try {
        while (rounds < 3) {
            rounds++
            val payload = org.json.JSONObject()
                .put("model", ep.model)
                .put("temperature", temperature)
                .put("messages", msgs)
                .put("tools", org.json.JSONArray(Tools.spec()))
                .put("tool_choice", "auto")
                .toString()
            val body = postJson(ctx, task, ep, payload)
            val msg = org.json.JSONObject(body)
                .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw IllegalStateException("返回解析失败")
            val calls = msg.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                answer = msg.optString("content").trim()
                if (answer.isBlank()) throw IllegalStateException("空回复")
                return answer
            }
            // 把 assistant 的 tool_calls 原样回灌，再逐条执行
            msgs.put(msg)
            for (i in 0 until calls.length()) {
                val c = calls.getJSONObject(i)
                val id = c.optString("id")
                val fn = c.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val args = fn.optString("arguments", "{}")
                val result = Tools.run(ctx, db, name, args)
                msgs.put(
                    org.json.JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        // 工具返回的是本地数据（账本金额、取件码、记忆正文），必须过同一道闸
                        .put("content", gateText(ctx, task, "工具返回($name)", result))
                )
            }
        }
        } catch (t: Throwable) {
            val msg = t.message ?: ""
            // 模型不支持 tools（400/404）或平台临时不可用：退回纯聊天，别把报错甩给用户
            if (msg.startsWith("HTTP 4")) {
                Log.w(TAG, "tools unsupported, fallback plain chat (content omitted)")
                return chat(ctx, task, system, history, outgoing, temperature)
            }
            throw t
        }
        // 三轮还没给答案：把最后一轮的工具结果整理成一句人话兜底
        return if (answer.isBlank()) "我查到了，但没能组织成一句话，你可以换个问法再问一次。" else answer
    }

    /**
     * Agent 网关专用：**单轮**原始补全，不在这里执行任何工具。
     *
     * 与 [chatWithTools] 的区别很关键：旧方法自己本地执行工具，模型绕过了新的 ToolRegistry /
     * PolicyEngine；新方法只把模型的 tool_calls 原样交回 AgentLoop，由 AgentLoop 统一判定
     * 风险、弹确认、记步骤。老聊天链路继续用旧方法，不影响已验证功能。
     *
     * @param messagesJson OpenAI 风格 messages 数组（含 role / content / tool_call_id / tool_calls）
     * @param toolsJson OpenAI 风格 tools 数组；空串表示这一步不允许调工具
     * @return choices[0].message 的原始 JSON（调用方决定怎么解析）
     */
    fun completeOnce(
        ctx: Context,
        task: PrivacyRouter.Task,
        messagesJson: String,
        toolsJson: String = "",
        temperature: Double = 0.3,
    ): String {
        val ep = endpoint(ctx) ?: throw IllegalStateException("云通道未配置")
        val msgs = org.json.JSONArray(messagesJson)
        // 纵深防御：即便调用方已经脱敏过，这里再逐条过闸。
        // v16 修复：`assistant.tool_calls[].function.arguments` 也是要出网的内容 ——
        // 工具参数里常带手机号/地址/取件码/金额，且会在后续每一轮请求里被重新发送。
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: continue
            val role = m.optString("role")
            val content = m.optString("content")
            if (content.isNotBlank()) {
                m.put("content", gateText(ctx, task, "消息[$role]", content))
            }
            val calls = m.optJSONArray("tool_calls") ?: continue
            for (j in 0 until calls.length()) {
                val fn = calls.optJSONObject(j)?.optJSONObject("function") ?: continue
                val args = fn.optString("arguments", "")
                if (args.isBlank()) continue
                val name = fn.optString("name").ifBlank { "?" }
                // 拦下就整轮中止（gateText 会抛 PrivacyBlockedException），不做"换个字段继续发"
                fn.put("arguments", gateText(ctx, task, "工具参数[$name]", args))
            }
        }
        val payload = org.json.JSONObject()
            .put("model", ep.model)
            .put("temperature", temperature)
            .put("messages", msgs)
        if (toolsJson.isNotBlank()) {
            payload.put("tools", org.json.JSONArray(toolsJson))
            payload.put("tool_choice", "auto")
        }
        val body = postJson(ctx, task, ep, payload.toString())
        val msg = org.json.JSONObject(body)
            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("返回解析失败")
        return msg.toString()
    }

    /**
     * 多模态：把若干本地图片连同文字问题发给视觉模型。
     *
     * 闸门口径与文本不同，必须在调用处守住：
     * - **文字**照常过 [PrivacyRouter.decide]（红线不发、敏感脱敏）；
     * - **图片没法打码**，所以只在用户明确同意（一次性许可）后才调这里，
     *   并按「几张、多大」写进出网留档 —— 用户能在「出网记录」里看到这次发了什么。
     *
     * 实测（2026-11-21，tokenrhythm）：`qwen3.8-max` / `seed-2.1-pro` 支持 vision，
     * `glm-5.3` 返回 `MODEL_CAPABILITY_NOT_SUPPORTED`。所以这里不做模型回退，
     * 让失败如实暴露给上层（上层再决定要不要退回本地链路）。
     */
    fun chatVision(
        ctx: Context,
        task: PrivacyRouter.Task,
        system: String,
        user: String,
        images: List<java.io.File>,
        maxTokens: Int = 900
    ): String {
        val ep = endpoint(ctx) ?: throw IllegalStateException("云通道未配置")
        val verdict = PrivacyRouter.decide(ctx, task, user)
        if (!verdict.allow) {
            PrivacyRouter.audit(ctx, task, ep.host, ep.model, user, ok = false, note = "闸门拦截：${verdict.reason}")
            throw IllegalStateException(verdict.reason)
        }
        val outgoing = verdict.text.ifBlank { user }
        val kb = images.sumOf { it.length() } / 1024

        val content = org.json.JSONArray()
        content.put(org.json.JSONObject().put("type", "text").put("text", outgoing))
        for (f in images) {
            val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            content.put(
                org.json.JSONObject()
                    .put("type", "image_url")
                    .put("image_url", org.json.JSONObject().put("url", "data:image/jpeg;base64,$b64"))
            )
        }
        val msgs = org.json.JSONArray()
            .put(org.json.JSONObject().put("role", "system").put("content", gateText(ctx, task, "system", system)))
            .put(org.json.JSONObject().put("role", "user").put("content", content))

        val payload = org.json.JSONObject()
            .put("model", ep.model)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
            .put("messages", msgs)
            .toString()

        PrivacyRouter.audit(
            ctx, task, ep.host, ep.model, "$system ｜ $outgoing",
            ok = true, note = "图片 ${images.size} 张 ${kb}KB"
        )
        val body = postJson(ctx, task, ep, payload)
        val msg = org.json.JSONObject(body)
            .optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw IllegalStateException("返回解析失败")
        val answer = msg.optString("content").trim()
        if (answer.isBlank()) throw IllegalStateException("空回复")
        return answer
    }

    /** 发一次 JSON 请求，返回响应体（带留档）。 */
    private fun postJson(ctx: Context,
        task: PrivacyRouter.Task,
        ep: Endpoint,
        rawPayload: String
    ): String {
        // T-B3：最后一道统一闸门。不管调用方怎么组包（现在或将来新加的），
        // 出网前整体再扫一遍请求体，避免"某条路径忘了过闸"这类历史问题复发。
        val gate = lastGate(ctx, task, rawPayload)
        val payload = gate.payload
        val gateNote = gate.note
        var conn: HttpURLConnection? = null
        try {
            val c = open("${ep.base}/chat/completions", ep.key)
            conn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 8_000
            c.readTimeout = 40_000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                val err = try { c.errorStream?.bufferedReader()?.readText()?.take(120) } catch (_: Throwable) { "" }
                PrivacyRouter.audit(ctx, task, ep.host, ep.model, payload, ok = false, note = joinNote(gateNote, "HTTP $code"))
                throw IllegalStateException("HTTP $code ${err.orEmpty().take(60)}")
            }
            val resp = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            PrivacyRouter.audit(ctx, task, ep.host, ep.model, payload, ok = true, note = joinNote(gateNote, "tools"))
            return resp
        } catch (t: Throwable) {
            if (t.message?.startsWith("HTTP ") != true) {
                PrivacyRouter.audit(ctx, task, ep.host, ep.model, payload, ok = false,
                    note = joinNote(gateNote, "失败：${t.javaClass.simpleName}"))
            }
            throw t
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    /**
     * 语音转写：OpenAI 兼容 `/audio/transcriptions`（multipart，model=whisper-1）。
     * 语音本身不做文本判定（还没有文本），由用户在设置里显式勾「允许语音上云」把关；
     * 这里只保证走同一个端点、同一条留档。
     */
    fun transcribe(ctx: Context, task: PrivacyRouter.Task, file: File): String {
        val ep = endpoint(ctx) ?: throw IllegalStateException("云通道未配置")
        // D4 / §11-3（D-009）：语音上云必须一次一确认。没有确认券就不发。
        if (!AssistantPrefs.consumeVoiceCloudConfirm(ctx)) {
            PrivacyRouter.audit(ctx, task, ep.host, "whisper-1",
                "（音频 ${file.length() / 1024}KB）", ok = false, note = "语音上云未确认，未发送")
            throw IllegalStateException("语音上云未确认")
        }
        val boundary = "----zhuli" + System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        val brief = "（音频 ${file.length() / 1024}KB）"
        try {
            val c = open("${ep.base}/audio/transcriptions", ep.key)
            conn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 60_000
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val nl = "\r\n"
            c.outputStream.use { out ->
                out.write(("--$boundary$nl").toByteArray())
                out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"$nl").toByteArray())
                out.write(("Content-Type: application/octet-stream$nl$nl").toByteArray())
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
                out.write(nl.toByteArray())
                out.write(("--$boundary$nl").toByteArray())
                out.write(("Content-Disposition: form-data; name=\"model\"$nl$nl").toByteArray())
                out.write("whisper-1".toByteArray())
                out.write(nl.toByteArray())
                out.write(("--$boundary--$nl").toByteArray())
                out.flush()
            }
            val code = c.responseCode
            if (code != 200) {
                val err = try { c.errorStream?.bufferedReader()?.readText()?.take(80) } catch (_: Throwable) { "" }
                PrivacyRouter.audit(ctx, task, ep.host, "whisper-1", brief, ok = false,
                    note = "HTTP $code ${err.orEmpty()}".trim())
                throw IllegalStateException("HTTP $code ${err.orEmpty()}".trim())
            }
            val resp = c.inputStream.bufferedReader().use { it.readText() }
            val m = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(resp)
            val text = if (m.find()) unescape(m.group(1)!!).trim() else ""
            PrivacyRouter.audit(ctx, task, ep.host, "whisper-1",
                "$brief → ${SensitiveGuard.redact(text).take(80)}",
                ok = text.isNotBlank(), note = if (text.isBlank()) "没有返回文字" else "")
            if (text.isBlank()) throw IllegalStateException("云端没有返回文字")
            return text
        } catch (t: Throwable) {
            if (t.message?.startsWith("HTTP ") != true && t.message != "云端没有返回文字") {
                PrivacyRouter.audit(ctx, task, ep.host, "whisper-1", brief, ok = false,
                    note = "失败：${t.javaClass.simpleName}:${t.message?.take(40) ?: ""}")
            }
            throw t
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    // ==================== 内部 ====================

    private fun post(
        ctx: Context,
        task: PrivacyRouter.Task,
        ep: Endpoint,
        path: String,
        rawPayload: String,
        parse: (String) -> String
    ): String {
        val gate = lastGate(ctx, task, rawPayload)
        val payload = gate.payload
        val gateNote = gate.note
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            val c = open("${ep.base}$path", ep.key)
            conn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 8_000
            c.readTimeout = 25_000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                val err = try { c.errorStream?.bufferedReader()?.readText()?.take(80) } catch (_: Throwable) { "" }
                PrivacyRouter.audit(ctx, task, ep.host, ep.model, payload,
                    ok = false, note = joinNote(gateNote, "HTTP $code ${err.orEmpty()}".trim()))
                throw IllegalStateException("HTTP $code")
            }
            val resp = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val out = parse(resp)
            PrivacyRouter.audit(ctx, task, ep.host, ep.model, payload,
                ok = true, note = joinNote(gateNote, "${System.currentTimeMillis() - started}ms"))
            return out
        } catch (t: Throwable) {
            // 连不上/超时/解析炸了也要留痕：出网这件事「发生过」，不然排障时全是空白。
            // HTTP 非 200 的分支上面已经写过留档，这里跳过免得重复记两条。
            if (t.message?.startsWith("HTTP ") != true) {
                PrivacyRouter.audit(ctx, task, ep.host, ep.model, payload, ok = false,
                    note = joinNote(gateNote, "失败：${t.javaClass.simpleName}:${t.message?.take(40) ?: ""}"))
            }
            throw t
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun open(url: String, key: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("User-Agent", "ZhuliAssistant/0.9.2 (Android)")
        return conn
    }

    private fun json(s: String): String = buildString {
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append(' ') else append(c)
        }
    }

    private fun unescape(s: String): String = s
        .replace("\\\"", "\"").replace("\\\\", "\\")
        .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")

    // ==================== 闸门（T-B2 / T-B3） ====================

    /** 闸门判为「整轮不该发」时抛这个，调用方据此回落本地回答。 */
    class PrivacyBlockedException(message: String) : IllegalStateException(message)

    /**
     * 单条文本过闸：放行返回（可能已脱敏的）内容；命中硬红线则抛异常中止整轮。
     * 用于 system / 历史 / 工具返回值 —— 这些都是「用户没直接说出口、但会被带出去」的内容。
     */
    private fun gateText(ctx: Context, task: PrivacyRouter.Task, label: String, text: String): String {
        if (text.isBlank()) return text
        val v = PrivacyRouter.decide(ctx, task, text)
        if (!v.allow) {
            PrivacyRouter.audit(ctx, task, "-", "-", text, ok = false, note = "闸门拦截($label)：${v.reason}")
            throw PrivacyBlockedException("$label 含${v.reason}，这一轮不上云")
        }
        return v.text.ifBlank { text }
    }

    /**
     * 请求体整体复核：逐条查硬红线（含 tool_calls 参数与多模态文本片段），
     * 命中即中止，其余敏感项就地脱敏。
     * 这是最后一道，不做业务判断，只保证发出去的内容里没有红线内容。
     *
     * 实现委托给 [OutgoingGate]：那份逻辑是纯 JVM 的，能被单测直接钉住
     * （嵌套 tool_calls 参数、多模态数组、cloud_log 与实发内容一致）。
     */
    private fun lastGate(ctx: Context, task: PrivacyRouter.Task, payload: String): GateResult {
        return try {
            val out = PrivacyPipeline.scrub(
                payloadJson = payload,
                rules = PrivacyPipeline.Rules(
                    hardRedLine = { PrivacyDecision.hardRedLine(it) },
                    sensitiveReason = { SensitiveGuard.reason(it) },
                    redact = { SensitiveGuard.redact(it) },
                ),
                // 这里只处理 JSON 聊天体：结构异常必须中止，
                // 绝不能因为「解析不出来就原样发」让聊天请求绕过闸门。
                requireKnownShape = true,
            )
            GateResult(
                payload = out.payload,
                note = if (out.redactions.isEmpty()) "" else
                    "出网前脱敏 ${out.redactions.size} 处（${out.shape.label}，扫描 ${out.scannedFields} 字段）",
            )
        } catch (e: PrivacyPipeline.Blocked) {
            PrivacyRouter.audit(ctx, task, "-", "-", e.where, ok = false, note = "最后闸门拦截：${e.reason}")
            throw PrivacyBlockedException("${e.where} 含${e.reason}，已中止")
        }
    }

    /** 闸门留档说明 + 本次调用说明，拼成一条 note（空项自动省略）。 */
    private fun joinNote(vararg parts: String): String =
        parts.filter { it.isNotBlank() }.joinToString(" · ")

    private data class GateResult(val payload: String, val note: String)
}
