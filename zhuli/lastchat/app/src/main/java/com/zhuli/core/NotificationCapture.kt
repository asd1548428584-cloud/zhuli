package com.zhuli.core

import android.app.Notification

/**
 * 通知正文解析（v2，2026-11-24）。
 *
 * 旧实现只读 `EXTRA_TITLE` / `EXTRA_TEXT` 两个字段，两个后果都在真机上出现了：
 * - 视频/音乐播放器把「歌名 + 歌手」放在这两个字段里 → 被当成一条新消息读进对话；
 * - 微信/QQ 的通知正文常常在 `MessagingStyle`、`EXTRA_BIG_TEXT` 或 `EXTRA_TEXT_LINES` 里，
 *   `EXTRA_TEXT` 是空的 → 整条被 `text.isBlank()` 丢掉，于是「微信一条都收不到」。
 *
 * 这里做两件事：**先把不是消息的拦住**（媒体/汇总/常驻/进度/通话），
 * **再把真正的消息从几个可能的位置挖出来**。纯函数，便于单测与回放。
 */
object NotificationCapture {

    /** 解析结果。[reason] 非空表示"不该收"，[title]/[text] 只在 reason 为空时有意义。 */
    data class Result(
        val title: String,
        val text: String,
        val reason: String = "",
    ) {
        val ok: Boolean get() = reason.isBlank() && text.isNotBlank()
    }

    private fun skip(reason: String) = Result("", "", reason)

    /**
     * @param appLabel 该包的应用名（用于识别"标题只是应用名"与"xx: 正文"的拆分）
     */
    fun analyze(
        n: Notification,
        appLabel: String,
        allowMedia: Boolean = false,
        allowOngoing: Boolean = false,
    ): Result {
        val ex = n.extras

        // ① 媒体播放：媒体会话、传输类别、系统媒体控件
        if (!allowMedia) {
            if (ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) return skip("媒体播放通知")
            if (n.category == Notification.CATEGORY_TRANSPORT) return skip("媒体播放通知")
        }

        // ② 群聊汇总条（"3 条新消息"），真正的消息会各自单独上报
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return skip("群聊汇总条")

        // ③ 进度条 / 常驻服务 / 通话 / 系统提示
        when (n.category) {
            Notification.CATEGORY_PROGRESS -> return skip("进度条通知")
            Notification.CATEGORY_SERVICE -> return skip("常驻服务通知")
            Notification.CATEGORY_CALL -> return skip("通话通知")
            Notification.CATEGORY_SYSTEM -> return skip("系统提示")
            else -> Unit
        }
        if (!allowOngoing && (n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return skip("常驻通知")

        // ④ 正文：按可靠性从高到低找
        // MessagingStyle 的消息在 EXTRA_MESSAGES 里（Parcelable[] of Bundle）；
        // 公开 SDK 没有 extractMessagingStyleFromNotification()，只能自己拆。
        var title = ""
        var text = ""

        val messages = runCatching {
            ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        }.getOrNull()
        val last = messages?.lastOrNull() as? android.os.Bundle
        if (last != null) {
            text = last.getCharSequence("text")?.toString().orEmpty()
            val conversation = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?.toString().orEmpty()
            val senderPerson = runCatching {
                @Suppress("DEPRECATION")
                last.getParcelable("sender_person") as? android.app.Person
            }.getOrNull()
            val sender = senderPerson?.name?.toString()
                ?: last.getCharSequence("sender")?.toString().orEmpty()
            title = conversation.ifBlank { sender }
            // 群聊：标题是群名时，把说话人补回正文，别丢"谁说的"
            if (conversation.isNotBlank() && sender.isNotBlank() && sender != conversation) {
                text = "$sender：$text"
            }
        }

        if (text.isBlank()) {
            text = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        }
        if (text.isBlank()) {
            // 群聊常把每条消息放在 EXTRA_TEXT_LINES，而 EXTRA_TEXT 只有「N 条新消息」；
            // 所以 TEXT_LINES 要排在 TEXT 前面，否则会先拿到汇总文案再被整条丢掉。
            val lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (lines != null && lines.isNotEmpty()) {
                text = lines.takeLast(2).joinToString("\n") { it?.toString().orEmpty() }
            }
        }
        if (text.isBlank()) {
            text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        }
        if (text.isBlank()) {
            text = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        }
        if (text.isBlank()) {
            // 兜底：有些 App 只给 EXTRA_INFO_TEXT（"1 条新消息"），没有正文就没必要收
            return skip("没有正文")
        }

        if (title.isBlank()) {
            title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        }
        if (title.isBlank()) {
            title = ex.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        }

        // ⑤ 清洗
        var cleanTitle = normalize(title)
        var cleanText = normalize(text)

        // 标题只剩应用名时，从「张三: 你好」里把说话人拆出来
        val labelNorm = normalize(appLabel)
        if (labelNorm.isNotBlank() && (cleanTitle.isBlank() || cleanTitle == labelNorm)) {
            val split = splitSpeaker(cleanText)
            if (split != null) {
                cleanTitle = split.first
                cleanText = split.second
            } else if (cleanTitle.isBlank()) {
                cleanTitle = labelNorm
            }
        }

        // 正文里带「微信：」前缀的去掉，避免标题和正文重复
        if (labelNorm.isNotBlank()) {
            cleanText = cleanText.removePrefix("$labelNorm：").removePrefix("$labelNorm:")
            cleanText = cleanText.trim()
        }

        if (cleanText.isBlank()) return skip("没有正文")
        if (isPlaybackText(cleanText)) return skip("播放控制文案")
        if (isSummaryText(cleanText)) return skip("消息汇总文案")
        if (cleanText.length < 1) return skip("没有正文")

        if (cleanTitle.isBlank()) cleanTitle = labelNorm.ifBlank { "通知" }

        return Result(cleanTitle, cleanText.take(500))
    }

    // ==================== 纯文本规则（可单测） ====================

    /** 换行压成空格、连续空白压成一个、去掉首尾。 */
    fun normalize(s: String): String =
        s.replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    /**
     * 媒体播放进度/控制文案：
     * 「03:21 / 04:58」「正在播放」「暂停」「下一首」「轻触播放」这类不是人发来的消息。
     */
    fun isPlaybackText(text: String): Boolean {
        val t = text.trim()
        if (Regex("^\\d{1,2}:\\d{2}\\s*(/|／)?\\s*(\\d{1,2}:\\d{2})?$").matches(t)) return true
        if (t.length > 40) return false
        val words = listOf(
            "正在播放", "播放中", "暂停", "继续播放", "下一首", "上一首",
            "随机播放", "单曲循环", "列表循环", "轻触播放", "点击播放", "缓冲中",
        )
        return words.any { t == it || t.startsWith("$it ") || t.endsWith(" $it") }
    }

    /** 汇总条文案：「3 条新消息」「你有 2 条未读」——各条消息会单独上报。 */
    fun isSummaryText(text: String): Boolean {
        val t = text.trim()
        if (Regex("^\\d+\\s*条(新)?(消息|未读|信息)$").matches(t)) return true
        if (Regex("^(你|您)?(有|收到)?\\s*\\d+\\s*条(新)?(消息|未读|信息)$").matches(t)) return true
        if (Regex("^还有\\s*\\d+\\s*条.*$").matches(t) && t.length <= 16) return true
        return t == "以下是新消息" || t == "你收到一条新消息" || t == "您收到一条新消息"
    }

    /**
     * 「张三：你好」→ (张三, 你好)。只在左侧像人名（≤12 字、无空格/标点）时拆，
     * 避免把「【中国银行】您尾号…」这种正文拆坏。
     */
    fun splitSpeaker(text: String): Pair<String, String>? {
        val t = text.trim()
        val idx = t.indexOfFirst { it == ':' || it == '：' }
        if (idx <= 0 || idx > 12) return null
        val speaker = t.substring(0, idx).trim()
        val body = t.substring(idx + 1).trim()
        if (body.isEmpty()) return null
        if (speaker.any { it.isWhitespace() }) return null
        if (speaker.any { it in "【】[]，。！？!?、,.;；" }) return null
        return speaker to body
    }

    /** 给后台处理页/自检页用的人类可读说明。 */
    fun describe(reason: String): String = when (reason) {
        "媒体播放通知" -> "视频/音乐播放"
        "群聊汇总条" -> "群聊汇总"
        "进度条通知" -> "下载/进度"
        "常驻服务通知" -> "前台服务"
        "通话通知" -> "来电"
        "系统提示" -> "系统提示"
        "常驻通知" -> "常驻通知"
        "没有正文" -> "取不到正文"
        "播放控制文案" -> "播放控制"
        "消息汇总文案" -> "汇总文案"
        else -> reason
    }
}
