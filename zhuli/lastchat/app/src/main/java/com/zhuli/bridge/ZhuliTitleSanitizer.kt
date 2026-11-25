package com.zhuli.bridge

/**
 * 会话标题的清洗与修复。
 *
 * 真机现场：侧边栏出现 `# ä¸»要的çŁ¥名的ç³»ç»Ł…` 这种标题，还带着一整篇 markdown。
 * 成因有两层：
 * 1. 标题是让**当前对话模型**生成的，小模型（Qwen2.5 1.5B / Qwen3 0.6B）在"总结成标题"这种
 *    任务上会跑飞，吐出 `# 主系\n\n## 1. 主系…` 加几十个空 bullet 的退化文本；
 * 2. 这段文本又经历过一次单字节/UTF-8 错配的解码（`主` 的字节 E4B8BB 被读成 `ä¸»`），
 *    于是中文变成鬼画符。
 *
 * 处理原则：能干净还原的就还原；还原不了或明显是退化文本的，**不要硬凑**，交给调用方回落
 * 到用户第一句话（这比"ä¸»要的…"有用得多）。
 */
object ZhuliTitleSanitizer {

    private const val MAX_LEN = 24

    /** 乱码指纹：单字节表的字母区 + 常见错配字母。 */
    private val MOJIBAKE_MARKS = "Ł¥³§©®°±²µ·º¼½¾¿"

    /** 看着像被解码错的乱码吗（不判断能不能还原）。 */
    fun isBroken(s: String): Boolean {
        if (s.isBlank()) return false
        var marks = 0
        var cjk = 0
        for (ch in s) {
            val o = ch.code
            if (o in 0x80..0xFF || MOJIBAKE_MARKS.indexOf(ch) >= 0) marks++
            if (o in 0x4E00..0x9FFF) cjk++
        }
        return marks >= 3 && marks > cjk
    }

    /**
     * 把 UTF-8 被按单字节表读出来的文本还原。
     * 只在"全是 0x00–0xFF"时尝试（严格 Latin-1 往返）；混进了别的字母表说明不是干净的错配，
     * 强行还原只会更乱，这时原样返回。
     */
    fun repairMojibake(raw: String): String {
        if (raw.isEmpty() || !isBroken(raw)) return raw
        val bytes = ByteArray(raw.length)
        for (i in raw.indices) {
            val c = raw[i].code
            if (c > 0xFF) return raw
            bytes[i] = c.toByte()
        }
        val fixed = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return raw
        val fixedCjk = fixed.count { it in '\u4e00'..'\u9fff' }
        return if (!fixed.contains('\uFFFD') && fixedCjk > 0) fixed else raw
    }

    /** 取第一行像标题的内容，去掉 markdown 标记。 */
    private fun firstMeaningfulLine(s: String): String =
        s.lineSequence()
            .map { it.trim() }
            .map { it.replace(Regex("^[\\s\\-#*>·•>]+"), "") }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
            .replace(Regex("^对话标题[:：]?\\s*"), "")
            .trim()

    /**
     * 把模型吐出来的东西压成一条能看的标题。
     * @param fallback 清洗后仍不可用时用这个（一般是用户第一句话）
     */
    fun sanitize(raw: String?, fallback: String = ""): String {
        val repaired = repairMojibake(raw.orEmpty())
        val line = firstMeaningfulLine(repaired)
        val bad = line.isBlank() || isBroken(line) || isDegenerate(line)
        val chosen = if (bad) firstMeaningfulLine(repairMojibake(fallback)) else line
        return chosen.take(MAX_LEN).trim()
    }

    /** 退化判定：太短、只有一个字反复、或者整行没有文字/数字。 */
    private fun isDegenerate(s: String): Boolean {
        if (s.length < 2) return true
        if (s.toSet().size <= 2 && s.length > 3) return true
        return s.none { it.isLetterOrDigit() || it in '\u4e00'..'\u9fff' }
    }

    /**
     * 侧边栏展示用：修乱码 + 压一行，不改库里的原值。
     * 修不好（或本来就是垃圾）时返回空串，由调用方回落到"用户第一句话 / 新消息"。
     */
    fun forDisplay(raw: String?): String {
        val repaired = repairMojibake(raw.orEmpty())
        val line = firstMeaningfulLine(repaired)
        return if (line.isBlank() || isBroken(line) || isDegenerate(line)) "" else line.take(MAX_LEN + 8)
    }
}
