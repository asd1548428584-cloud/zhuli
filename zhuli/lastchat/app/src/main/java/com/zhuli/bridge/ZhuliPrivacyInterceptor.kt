package com.zhuli.bridge

import android.content.Context
import com.zhuli.core.AssistantPrefs
import com.zhuli.core.InboxDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.concurrent.Executors

/**
 * 隐私闸门 · 出网拦截器（T-G2/B1 接线，2026-11-24）。
 *
 * 挂在全局 OkHttpClient 上，只处理"聊天类"请求（chat/completions、responses、messages）：
 * - 逐条取出 messages / system 的文本，交给 [ZhuliPrivacy.decide]；
 * - 命中硬红线（证件号 / 卡号 / 凭证）→ 整条请求拦下，返回 403 + 可读原因，内容不出机；
 * - 普通敏感（手机号 / 地址 / 金额）→ 按脱敏后的文本重建请求体再发；
 * - 每次出网写一条 cloud_log（只记宿主、模型、脱敏处数，不记内容）。
 *
 * 不做的事（诚实标注）：
 * - 不拦图片/音频的 base64（体积大且规则判定会误伤），也不拦非聊天接口（如 embedding）；
 * - 请求体超过 2MB 直接放行，避免为了检查把内存打爆。
 */
class ZhuliPrivacyInterceptor(private val context: Context) : Interceptor {

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val logIo = Executors.newSingleThreadExecutor { r ->
            Thread(r, "zhuli-privacy-log").apply { isDaemon = true }
        }
        private const val MAX_BODY_BYTES = 2L * 1024 * 1024

        private fun isChatPath(path: String): Boolean {
            val p = path.lowercase()
            return p.contains("chat/completions") ||
                p.endsWith("/completions") ||
                p.contains("/responses") ||
                p.contains("/messages")
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!AssistantPrefs.privacyGateEnabled(context)) {
            ZhuliPrivacyGate.deactivate()
            return chain.proceed(request)
        }
        ZhuliPrivacyGate.activate()

        if (!isChatPath(request.url.encodedPath)) return chain.proceed(request)
        // 本机回环（本地模型/本地网关）不算"出网"，不脱敏也不记录
        val host = request.url.host
        if (host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "10.0.2.2") {
            return chain.proceed(request)
        }
        val body = request.body ?: return chain.proceed(request)
        if (body.isOneShot() || body.isDuplex()) return chain.proceed(request)
        val declared = body.contentLength()
        if (declared > MAX_BODY_BYTES) return chain.proceed(request)

        val raw = try {
            Buffer().also { body.writeTo(it) }.readUtf8()
        } catch (t: Throwable) {
            return chain.proceed(request)
        }
        if (raw.isBlank()) return chain.proceed(request)

        val root = try {
            json.parseToJsonElement(raw) as? JsonObject
        } catch (t: Throwable) {
            null
        } ?: return chain.proceed(rebuild(request, raw, body.contentType()))

        val config = ZhuliPrivacy.Config(
            cloudReady = true, // 请求本身能发出来就是已配好；这里只判敏感度与地址安全性
            baseUrl = request.url.toString(),
            strict = AssistantPrefs.privacyMode(context) == "strict",
        )

        val outcome = rewrite(root, config)
        val model = root["model"]?.jsonPrimitive?.contentOrNull.orEmpty()

        if (outcome.blocked != null) {
            val reason = outcome.blocked
            log(
                host = request.url.host,
                model = model,
                ok = false,
                note = "隐私闸门拦截：$reason",
            )
            return blockedResponse(request, reason)
        }

        if (outcome.redacted == 0) {
            log(
                host = request.url.host,
                model = model,
                ok = true,
                note = "无敏感项，原样出网",
            )
            return chain.proceed(rebuild(request, raw, body.contentType()))
        }

        val newRaw = json.encodeToString(JsonObject.serializer(), outcome.body)
        log(
            host = request.url.host,
            model = model,
            ok = true,
            note = "已脱敏 ${outcome.redacted} 处后出网",
        )
        return chain.proceed(rebuild(request, newRaw, body.contentType()))
    }

    // ==================== JSON 改写 ====================

    private class Outcome(
        val body: JsonObject,
        val redacted: Int,
        val blocked: String?,
    )

    private fun rewrite(root: JsonObject, config: ZhuliPrivacy.Config): Outcome {
        var redacted = 0
        var blocked: String? = null

        fun handle(text: String): String? {
            if (blocked != null || text.isBlank()) return null
            val d = ZhuliPrivacy.decide(config, ZhuliPrivacy.Task.CHAT, text)
            if (!d.allow) {
                blocked = d.reason
                return null
            }
            if (d.redacted) {
                redacted++
                return d.text.ifBlank { text }
            }
            return null
        }

        fun rewriteContent(el: JsonElement?): JsonElement? {
            if (el == null) return null
            return when (el) {
                is JsonPrimitive -> {
                    if (!el.isString) return null
                    handle(el.content)?.let { JsonPrimitive(it) }
                }

                is JsonArray -> {
                    var changed = false
                    val parts = el.map { part ->
                        val obj = part as? JsonObject ?: return@map part
                        val t = obj["text"] ?: return@map part
                        if (!t.jsonPrimitive.isString) return@map part
                        val replaced = handle(t.jsonPrimitive.content) ?: return@map part
                        changed = true
                        JsonObject(obj + ("text" to JsonPrimitive(replaced)))
                    }
                    if (changed) JsonArray(parts) else null
                }

                else -> null
            }
        }

        // messages[*].content
        val messages = root["messages"] as? JsonArray
        val newMessages: JsonArray? = messages?.let { arr ->
            var changed = false
            val list = arr.map { item ->
                val obj = item as? JsonObject ?: return@map item
                val replaced = rewriteContent(obj["content"]) ?: return@map obj
                changed = true
                JsonObject(obj + ("content" to replaced))
            }
            if (changed) JsonArray(list) else null
        }

        // system（字符串或分段数组）
        val systemReplaced = rewriteContent(root["system"])

        if (blocked != null) return Outcome(root, redacted, blocked)

        if (newMessages == null && systemReplaced == null) return Outcome(root, 0, null)

        var out: JsonObject = root
        if (newMessages != null) out = JsonObject(out + ("messages" to newMessages))
        if (systemReplaced != null) out = JsonObject(out + ("system" to systemReplaced))
        return Outcome(out, redacted, null)
    }

    // ==================== 请求/响应 ====================

    private fun rebuild(source: okhttp3.Request, raw: String, contentType: okhttp3.MediaType?): okhttp3.Request {
        if (source.method == "GET" || source.method == "HEAD") return source
        val type = contentType ?: "application/json; charset=utf-8".toMediaType()
        return okhttp3.Request.Builder().apply {
            url(source.url)
            method(source.method, raw.toRequestBody(type))
            for (i in 0 until source.headers.size) {
                addHeader(source.headers.name(i), source.headers.value(i))
            }
            // 长度变了，旧的长度/编码头必须去掉，否则服务端会按旧长度读
            removeHeader("Content-Length")
            removeHeader("Content-Encoding")
        }.build()
    }

    private fun blockedResponse(request: okhttp3.Request, reason: String): Response {
        val payload = buildString {
            append("{\"error\":{\"message\":\"zhuli 隐私闸门已拦下这条请求：")
            append(reason.replace("\\", "\\\\").replace("\"", "\\\""))
            append("。内容没有离开这台手机。\",\"type\":\"zhuli_privacy_blocked\"}}")
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Blocked by zhuli privacy gate")
            .body(payload.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    private fun log(host: String, model: String, ok: Boolean, note: String) {
        logIo.execute {
            try {
                val db = InboxDatabase(context.applicationContext)
                try {
                    db.appendCloudLog(
                        task = "chat",
                        host = host,
                        model = model,
                        requestRedacted = "（内容不外存）",
                        ok = ok,
                        note = note,
                    )
                } finally {
                    runCatching { db.close() }
                }
            } catch (_: Throwable) {
                // 日志失败不影响出网
            }
        }
    }
}
