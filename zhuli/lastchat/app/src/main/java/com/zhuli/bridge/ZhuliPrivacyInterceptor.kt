package com.zhuli.bridge

import android.content.Context
import com.zhuli.core.AssistantPrefs
import com.zhuli.core.InboxDatabase
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.concurrent.Executors

/**
 * 隐私闸门 · 出网拦截器（T-G2/B1 接线，2026-11-24；批次 A 重写为 fail-closed）。
 *
 * 挂在全局 OkHttpClient 上，处理所有**会把用户原文送出去**的推理接口：
 * chat/completions、responses、claude messages、gemini generateContent、embeddings、
 * legacy completions。判定与改写全部委托给 [OutgoingBodyGate] → `PrivacyPipeline`，
 * 本类只负责 OkHttp 侧的读包/重建/留档。
 *
 * **批次 A 修掉的五个 fail-open**（原实现都是 `chain.proceed(request)` 放行）：
 * 1. 请求体是 one-shot / duplex → 读不出内容也照发；
 * 2. 请求体超过 2MB → 不扫，直接发（视觉请求会带 base64 图片，旧上限还会误伤正常功能）；
 * 3. 读请求体抛异常 → 照发；
 * 4. 请求体不是 JSON → 照发；
 * 5. 是 JSON 但没有 `messages`（Responses API 的 `input`、embeddings 的 `input`）→ 当成
 *    「无敏感项，原样出网」，等于整类请求从未被扫描。
 *
 * 现在这些分支一律返回 403 + 可读原因，并写一条 `cloud_log`。
 */
class ZhuliPrivacyInterceptor(private val context: Context) : Interceptor {

    companion object {
        private val logIo = Executors.newSingleThreadExecutor { r ->
            Thread(r, "zhuli-privacy-log").apply { isDaemon = true }
        }

        /**
         * 扫描上限。视觉请求会把 JPEG 转 base64（约 1.33 倍），3 帧 300KB 的图约 1.2MB，
         * 8MB 足够覆盖正常多模态；超过这个量级说明不是正常的对话请求。
         */
        private const val MAX_BODY_BYTES = 8L * 1024 * 1024

        /** 这些路径上的请求体一定含用户原文，必须扫描。 */
        private fun isScannedPath(path: String): Boolean {
            val p = path.lowercase()
            return p.contains("chat/completions") ||
                p.endsWith("/completions") ||
                p.contains("/responses") ||
                p.contains("/messages") ||
                p.contains("/embeddings") ||
                p.contains("generatecontent")
        }

        private fun isLoopback(host: String): Boolean =
            host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "10.0.2.2"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!AssistantPrefs.privacyGateEnabled(context)) {
            ZhuliPrivacyGate.deactivate()
            return chain.proceed(request)
        }
        ZhuliPrivacyGate.activate()

        if (!isScannedPath(request.url.encodedPath)) return chain.proceed(request)
        // 本机回环（本地模型/本地网关）不算"出网"，不脱敏也不记录
        if (isLoopback(request.url.host)) return chain.proceed(request)

        val model = request.url.encodedPath
        val body = request.body
        if (body == null) {
            log(request, model, ok = false, note = "拦截：请求没有可扫描的请求体")
            return blockedResponse(request, "请求没有可扫描的请求体，拒绝出网")
        }
        if (body.isOneShot() || body.isDuplex()) {
            log(request, model, ok = false, note = "拦截：请求体一次性/流式，无法预扫描")
            return blockedResponse(request, "请求体是一次性/流式上传，无法在出网前完成隐私扫描")
        }
        val declared = body.contentLength()
        if (declared > MAX_BODY_BYTES) {
            log(request, model, ok = false, note = "拦截：请求体 ${declared / 1024}KB 超过扫描上限")
            return blockedResponse(request, "请求体 ${declared / 1024}KB 超过隐私扫描上限")
        }

        val raw = try {
            Buffer().also { body.writeTo(it) }.readUtf8()
        } catch (t: Throwable) {
            log(request, model, ok = false, note = "拦截：请求体读取失败 ${t.javaClass.simpleName}")
            return blockedResponse(request, "请求体读取失败，无法在出网前完成隐私扫描")
        }

        val strict = AssistantPrefs.privacyMode(context) == "strict"
        return when (val decision = OutgoingBodyGate.decide(raw, strict)) {
            is OutgoingBodyGate.Decision.Block -> {
                log(request, model, ok = false, note = "拦截：${decision.reason}")
                blockedResponse(request, decision.reason)
            }

            is OutgoingBodyGate.Decision.Send -> {
                val s = decision.scanned
                val note = buildString {
                    append(s.shape)
                    append("｜扫描 ").append(s.scannedFields).append(" 字段")
                    if (s.redactions > 0) append("｜脱敏 ").append(s.redactions).append(" 处")
                }
                log(request, model, ok = true, note = note)
                chain.proceed(rebuild(request, s.body, body.contentType()))
            }
        }
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

    private fun log(request: okhttp3.Request, model: String, ok: Boolean, note: String) {
        logIo.execute {
            try {
                val db = InboxDatabase(context.applicationContext)
                try {
                    db.appendCloudLog(
                        task = "chat",
                        host = request.url.host,
                        model = model,
                        requestRedacted = "（内容不外存）",
                        ok = ok,
                        note = note,
                    )
                } finally {
                    runCatching { db.close() }
                }
            } catch (_: Throwable) {
                // 日志失败不影响出网（拦截已经发生，记录不是放行条件）
            }
        }
    }
}
