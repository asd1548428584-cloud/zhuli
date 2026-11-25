package com.zhuli.core

import android.content.ContentValues
import android.content.Context
import com.zhuli.core.ZhuliLog as Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * 链接链路（第 3 步）：分享内容里的链接 → 提取正文 → 摘要入库。
 *
 * 路由策略（对应需求 §2.4-①，用户已拍板）：
 * 1. URL 命中敏感特征（订单号/token/密钥类参数）→ 只存不抓，正文写占位说明
 * 2. 未命中且云端已配置（OpenAI 兼容）→ 云摘要
 * 3. 未命中但无云配置 / 云失败 → 本地抓取正文兜底（仍不出隐私边界：抓取只用脱敏无关的纯读）
 *
 * 本模块只做「读」——不代付、不代发，深合 §2.6-A3 只收不发底线。
 */
object LinkExtractor {

    private const val TAG = "zhuli.link"
    private val io = Executors.newSingleThreadExecutor()

    /** 分享文本里有没有链接（供入口路由用）。 */
    fun hasUrl(text: String): Boolean = URL_RE.matcher(text).find()

    private val URL_RE = Pattern.compile("https?://[^\\s\"<>\\\\]+", Pattern.CASE_INSENSITIVE)
    // 敏感特征：query 中的凭证类参数、长订单号
    private val SENSITIVE_RES = listOf(
        Pattern.compile("(token|key|sig|sign|auth|secret|ticket|session|password)=[^&\\s]{6,}", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(orderno|waybillno|trackno|expressno)=\\w{6,}", Pattern.CASE_INSENSITIVE)
    )
    private val TITLE_RE = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val SCRIPT_RE = Pattern.compile("<script.*?</script>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val STYLE_RE = Pattern.compile("<style.*?</style>", Pattern.CASE_INSENSITIVE or Pattern.DOTALL)
    private val COMMENT_RE = Pattern.compile("<!--.*?-->", Pattern.DOTALL)

    fun handle(context: Context, rawText: String, onToast: (String) -> Unit) {
        val m = URL_RE.matcher(rawText)
        // 注意：不要在循环外先调 find()——会把游标推过第一条，循环里就再也收不到它了
        val urls = LinkedHashSet<String>()
        while (m.find()) urls.add(stripTrailing(m.group()))
        if (urls.isEmpty()) { onToast("没读到链接"); return }
        val first = urls.first()

        val db = InboxDatabase(context.applicationContext)
        val sensitive = looksSensitive(first)
        val rec = InboxRecord(
            source = InboxRecord.SOURCE_LINK,
            packageName = "",
            appLabel = "",
            title = "链接",
            text = rawText.trim(),
            receivedAt = System.currentTimeMillis(),
            pendingExtract = 1,
            url = urls.joinToString(" | "),
            summary = if (sensitive) "[命中敏感特征：仅存不抓]" else "[读取中……]"
        )
        val id = db.insert(rec)
        onToast(if (sensitive) "敏感链接：只存不抓" else "正在读取：${first.take(24)}…")

        if (sensitive) {
            db.close()
            // 只存不抓的条目同样要归档：分类固定「链接」，备注沿用占位说明
            ExtractPipeline.trigger(context)
            return
        }

        io.execute {
            try {
                // 第 9 步：走统一闸门——敏感链接/未配 Key/非 HTTPS 一律本地抓取，不上云
                val verdict = PrivacyRouter.decide(context, PrivacyRouter.Task.LINK, first)
                val result = if (verdict.allow) {
                    try {
                        cloudSummary(context, first)
                    } catch (t: Throwable) {
                        Log.w(TAG, "cloud summary failed, fallback local", t)
                        localRead(first)
                    }
                } else {
                    localRead(first)
                }
                updateSummary(db, id, result)
            } catch (t: Throwable) {
                Log.e(TAG, "read failed", t)
                updateSummary(db, id, "[读取失败：${t.message?.take(40) ?: "未知错误"}]")
            } finally {
                try { db.close() } catch (_: Throwable) {}
                // 第 4 步：链接条目也要过流水线（分类固定「链接」，备注取摘要前 60 字）
                ExtractPipeline.trigger(context)
            }
        }
    }

    private fun looksSensitive(url: String): Boolean {
        val q = url.substringAfter('?', url)
        if (q == url) return false
        return SENSITIVE_RES.any { r -> r.matcher(q).find() }
    }

    /** 去尾标点括号，别把句号带进 URL */
    private fun stripTrailing(s: String): String {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_/?=&%.:#~"
        var end = s.length
        while (end > 8 && s[end - 1] !in allowed) end--
        return s.substring(0, end)
    }

    private fun updateSummary(db: InboxDatabase, id: Long, summary: String) {
        val cv = ContentValues().apply {
            put(InboxDatabase.COL_SUMMARY, summary)
            put(InboxDatabase.COL_PENDING, 0)
        }
        db.writableDatabase.update(InboxDatabase.TABLE, cv,
            "${InboxDatabase.COL_ID} = ?", arrayOf(id.toString()))
    }

    // —— 本地抓取：纯读，不登录、不提交任何表单 ——
    private fun localRead(urlStr: String): String {
        val conn = (java.net.URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent",
                "Mozilla/5.0 (Linux; Android 15; NX733J) AppleWebKit/537.36 ZhuliAssistant/1.0")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val bytes = readAll(conn.inputStream, 350_000)
            val html = decodeBytes(bytes, conn.contentType ?: "")
            val text = stripHtml(html)
            if (text.isBlank()) throw IllegalStateException("页面正文为空（可能是 JS 重渲染页）")
            return composeSummary(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun readAll(stream: java.io.InputStream, capBytes: Int): ByteArray {
        val buf = ByteArray(32 * 1024)
        val bos = ByteArrayOutputStream()
        var total = 0
        while (true) {
            val n = stream.read(buf, 0, minOf(buf.size, capBytes - total))
            if (n <= 0) break
            bos.write(buf, 0, n)
            total += n
            if (total >= capBytes) break
        }
        return bos.toByteArray()
    }

    private fun decodeBytes(bytes: ByteArray, contentType: String): String {
        val headEnc = contentType.substringAfter("charset=", "")
        val bomUtf8 = bytes.size >= 3 &&
            bytes[0].toInt() == 0xEF && bytes[1].toInt() == 0xBB && bytes[2].toInt() == 0xBF
        val metaEnc = if (!bomUtf8) {
            val headLen = minOf(bytes.size, 2048)
            val headLatin = String(bytes, 0, headLen, Charsets.ISO_8859_1)
            val mm = Pattern.compile("charset=[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE)
                .matcher(headLatin)
            if (mm.find()) mm.group(1) else ""
        } else ""
        val chosen = when {
            headEnc.isNotBlank() && headEnc != "null" -> headEnc
            bomUtf8 -> "UTF-8"
            metaEnc.isNotBlank() -> metaEnc
            isValidUtf8(bytes) -> "UTF-8"
            else -> "GBK"
        }
        return try { String(bytes, java.nio.charset.Charset.forName(chosen)) }
            catch (t: Throwable) { String(bytes, Charsets.UTF_8) }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        // 回环判定：按 UTF-8 解出字符串再编回去，逐字节一致才算合法
        return try {
            val s = String(bytes, Charsets.UTF_8)
            s.toByteArray(Charsets.UTF_8).contentEquals(bytes)
        } catch (t: Throwable) { false }
    }

    private fun stripHtml(html: String): String {
        val s = html.replace("\r", " ")
        val clean = COMMENT_RE.matcher(s).replaceAll(" ")
        val noJs = SCRIPT_RE.matcher(clean).replaceAll(" ")
        val noStyle = STYLE_RE.matcher(noJs).replaceAll(" ")
        val noTag = Pattern.compile("<[^>]+>").matcher(noStyle).replaceAll(" ")
        val unescaped = noTag
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        return unescaped.replace(Regex("\\s+"), " ").trim()
    }

    private fun composeSummary(text: String): String {
        val titleM = TITLE_RE.matcher(text)
        val title = if (titleM.find()) titleM.group(1).trim() else ""
        val bodyStart = if (title.isNotBlank()) text.indexOf(title) + title.length else 0
        val body = text.substring(bodyStart).trim().let {
            if (it.length > 800) it.substring(0, 800) + " …(截断)" else it
        }
        return buildString {
            if (title.isNotBlank()) appendLine("[标题] $title")
            append(body)
        }.trim()
    }

    // ==================== 断网补跑（第 13 步） ====================

    /**
     * 上次读取失败的链接，开 App 时补跑一遍。
     *
     * 为什么要有：地铁里分享一条链接，正好断网 → 摘要写成「[读取失败：…]」，
     * 用户以为助理废物。其实只要联网后重试一次就好。这里只捞 24 小时内、失败过、还没重试过的，
     * 每条最多补 2 次（失败次数记在摘要里，靠它判断），不无限重试。
     */
    fun retryFailedAsync(context: Context) {
        val app = context.applicationContext
        kotlin.concurrent.thread {
            val db = InboxDatabase(app)
            try {
                val since = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                val rows = db.readableDatabase.rawQuery(
                    "SELECT ${InboxDatabase.COL_ID}, ${InboxDatabase.COL_URL}, ${InboxDatabase.COL_SUMMARY} " +
                        "FROM ${InboxDatabase.TABLE} WHERE ${InboxDatabase.COL_RECEIVED} >= ? " +
                        "AND ${InboxDatabase.COL_URL} != '' AND ${InboxDatabase.COL_SUMMARY} LIKE '[读取失败%' " +
                        "ORDER BY ${InboxDatabase.COL_RECEIVED} DESC LIMIT 5",
                    arrayOf(since.toString())
                ).use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            add(Triple(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
                        }
                    }
                }
                for ((id, url, summary) in rows) {
                    val tries = Regex("重试 (\\d+) 次").find(summary)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (tries >= 2) continue
                    val first = url.substringBefore(" | ").trim()
                    if (first.isBlank()) continue
                    val result = try {
                        if (PrivacyRouter.decide(app, PrivacyRouter.Task.LINK, first).allow) {
                            try { cloudSummary(app, first) } catch (t: Throwable) { localRead(first) }
                        } else localRead(first)
                    } catch (t: Throwable) {
                        "[读取失败：${t.message?.take(30) ?: "未知"}（重试 ${tries + 1} 次）]"
                    }
                    updateSummary(db, id, result)
                    if (!result.startsWith("[读取失败")) {
                        Log.i(TAG, "retry ok id=$id")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "retry failed", t)
            } finally {
                try { db.close() } catch (_: Throwable) {}
            }
        }
    }

    // —— 云摘要（OpenAI 兼容）：只给「过闸门」的链接用，失败自动回落本地抓取 ——
    private fun cloudSummary(context: Context, urlStr: String): String {
        val text = CloudClient.chat(
            ctx = context,
            task = PrivacyRouter.Task.LINK,
            system = "你是摘要助手。根据用户给的链接地址，用三行以内的中文要点说明这条链接大概讲什么；不确定就说看不清。",
            history = emptyList(),
            user = urlStr,
            temperature = 0.2
        )
        return "[云端摘要] $text"
    }
}

