package com.zhuli.core

/**
 * 收件箱条目 = 一条被助理记住的信息（通知 / 分享 / 链接）。
 *
 * 第 4 步起，[category] / [deadlineAt] / [amount] / [note] 由提取流水线原地写回，
 * 上层（对话查询、记账小结、第 5 步提醒引擎）直接读这四个字段，不再自己解析原文。
 */
data class InboxRecord(
    val id: Long = 0L,
    val source: String,
    val packageName: String,     // 来源 App 包名（链接条目为空）
    val appLabel: String,        // 来源 App 显示名（链接条目为空）
    val title: String,           // 通知标题 / 分享默认标题 / 链接固定「链接」
    val text: String,            // 正文（已清洗）
    val receivedAt: Long,        // 接收时间戳（毫秒）
    val pendingExtract: Int = 1, // 1=待提取流水线处理（第4步消费后置0）
    val url: String = "",        // 第3步：链接条目存 URL 原文（多条用 | 拼接）
    val summary: String = "",    // 第3步：链接正文摘要；敏感条目存占位说明
    val category: String = "",   // 第4步：快递|金融|工作|生活|链接|其他（''=未提取）
    val deadlineAt: Long = 0L,   // 第4步：识别出的截止时间戳；0=未识别/周期型
    val amount: Double = 0.0,    // 第4步：识别出的金额；0=未识别
    val note: String = "",       // 第4步：待办/备注简述（原文截断）
    val repeatRule: String = ""  // 第14步：周期规则 daily / weekly:3 / monthly:15；''=一次性
) {
    /** 展示用一行摘要：链接条目优先用抓取到的正文摘要。 */
    val display: String
        get() = when {
            url.isNotBlank() && summary.isNotBlank() -> summary
            else -> text
        }

    companion object {
        const val SOURCE_SHARE = "share"
        const val SOURCE_NOTIFICATION = "notification"
        const val SOURCE_LINK = "link"
        /** 用户在对话里直接下的提醒（2026-11-21 补：原来这类话被当成「查待办」丢掉） */
        const val SOURCE_CHAT = "chat"

        // 分类口径（首发固定，第 7 步记账扩展时只加不减）
        const val CAT_EXPRESS = "快递"
        const val CAT_FINANCE = "金融"
        const val CAT_WORK = "工作"
        const val CAT_LIFE = "生活"
        const val CAT_LINK = "链接"
        const val CAT_OTHER = "其他"
    }
}
