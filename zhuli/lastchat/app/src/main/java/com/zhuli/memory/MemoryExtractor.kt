package com.zhuli.memory

import com.zhuli.core.InboxRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 规则式事实抽取（记忆 v1）。
 *
 * 为什么先规则、不先上模型：
 * 1. 抽取跑在通知到达的热路径上，模型推理要几百毫秒到几秒，还会发热 —— 真机已经因为发热被投诉过一次；
 * 2. 规则错在哪一行代码可以改，模型错在哪个 token 改不了；
 * 3. 规则抽出来的东西**置信度高且可解释**，正好用来给将来的模型抽取做验收基线。
 *
 * 口径：宁少不错。抽不准的（比如「地址」这种高噪声项）给低置信度，UI 会单独标出来；
 * 绝不用「猜」填满用户的记忆库 —— 那比没有记忆更糟。
 */
object MemoryExtractor {

    /** 同一 App 的推送标题里常有「[2条]」「(广告)」这类噪声，进事实前清掉。 */
    private val TITLE_NOISE = Regex("""[\[\(（【]\s*(?:\d+\s*条|广告|推广|置顶|免打扰)\s*[\]\)）】]""")

    /** 取件码：原文里明确写了「取件码」三个字才算，避免把订单号当取件码。 */
    private val PICKUP_CODE = Regex("""取件码[：:\s]*([0-9A-Za-z][0-9A-Za-z-]{2,12})""")

    /** 运单号/快递单号：必须有标签词在前，纯数字串一律不认。 */
    private val WAYBILL = Regex("""(?:运单号|快递单号|单号)[：:\s]*([A-Za-z0-9-]{8,24})""")

    /** 订单号：电商/外卖场景高频，且格式稳定。 */
    private val ORDER_NO = Regex("""订单号[：:\s]*([A-Za-z0-9-]{6,32})""")

    /** 金额：¥12.30 / 12.30元 / 人民币 12 元。 */
    private val MONEY_SIGN = Regex("""(?:[¥￥]\s*(\d{1,7}(?:\.\d{1,2})?))""")
    private val MONEY_YUAN = Regex("""(\d{1,7}(?:\.\d{1,2})?)\s*元""")

    /** 手机号 / 座机。 */
    private val PHONE_MOBILE = Regex("""(?<!\d)(1[3-9]\d{9})(?!\d)""")
    private val PHONE_LANDLINE = Regex("""(?<!\d)(0\d{2,3}-?\d{7,8})(?!\d)""")

    /** 地址：必须带地址特征字，且长度够，否则不认（这是噪声最高的一类）。 */
    private val ADDRESS = Regex(
        """(?:地址|地点|送至|送到|收货地址)[：:\s]*([\u4e00-\u9fa5A-Za-z0-9#\-]{6,40}?(?:路|街|道|巷|号|栋|幢|楼|室|单元|小区|花园|大厦|广场|中心|园区|镇|村|县|区))"""
    )

    /** 常见快递/驿站品牌。命中只用来确定「主体是谁」，不单独成为事实。 */
    private val EXPRESS_BRANDS = listOf(
        "菜鸟驿站", "丰巢", "妈妈驿站", "京东快递", "顺丰", "圆通", "中通", "韵达", "申通", "极兔", "邮政", "EMS",
        "德邦", "丹鸟", "兔喜", "快宝驿站"
    )

    /** 会议类关键词（配合已解析出的时间，才成为日程事实）。 */
    private val MEETING_WORDS = listOf("会议", "开会", "评审", "面试", "约见", "路演", "答辩", "培训", "例会")

    /** 用户自己说要记住/要做的事。 */
    private val TODO_WORDS = listOf("记得", "别忘", "提醒我", "要交", "截止", "ddl", "DDL")

    private fun clean(s: String): String = s.replace(TITLE_NOISE, "").trim()

    private fun fmtTime(ms: Long): String =
        SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(ms))

    /** 归一化：全角转半角、去空白与标点、小写。用于查重与实体匹配。 */
    fun norm(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val c = when {
                ch.code == 0x3000 -> ' '
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }

    private fun brandOf(text: String): String? = EXPRESS_BRANDS.firstOrNull { text.contains(it) }

    /**
     * 从一条收件箱记录里抽事实。
     *
     * @param rec 已过提取流水线的记录（category/deadlineAt/amount 可能已有值，规则会复用）
     * @return 去重后的草稿；没有可抽的内容返回空表（空表是正常结果，不要造假事实）
     */
    fun extract(rec: InboxRecord): List<FactDraft> {
        val title = clean(rec.title)
        val body = rec.text.ifBlank { rec.summary }
        val hay = "$title\n$body\n${rec.note}"
        val out = LinkedHashMap<String, FactDraft>()
        val brand = brandOf(hay)
        val appLabel = rec.appLabel.ifBlank { "未知来源" }

        fun put(d: FactDraft) {
            val key = "${norm(d.subject)}|${d.predicate}|${norm(d.value)}"
            // 同一记录里重复命中同一事实：留置信度更高的那条
            val old = out[key]
            if (old == null || old.confidence < d.confidence) out[key] = d
        }

        // 1) 取件码 / 运单号 —— 快递场景的两根主心骨
        PICKUP_CODE.find(hay)?.groupValues?.getOrNull(1)?.let { code ->
            put(
                FactDraft(
                    subject = brand ?: "快递",
                    predicate = "取件码",
                    value = code,
                    kind = FactKind.EXPRESS,
                    confidence = 0.92,
                    sourceText = hay.take(120),
                    entityKind = EntityKind.ORG,
                )
            )
        }
        WAYBILL.find(hay)?.groupValues?.getOrNull(1)?.let { no ->
            put(
                FactDraft(
                    subject = brand ?: "快递",
                    predicate = "运单号",
                    value = no,
                    kind = FactKind.EXPRESS,
                    confidence = 0.9,
                    sourceText = hay.take(120),
                    entityKind = EntityKind.ORG,
                )
            )
        }
        ORDER_NO.find(hay)?.groupValues?.getOrNull(1)?.let { no ->
            put(
                FactDraft(
                    subject = appLabel,
                    predicate = "订单号",
                    value = no,
                    kind = FactKind.ORDER,
                    confidence = 0.88,
                    sourceText = hay.take(120),
                )
            )
        }

        // 2) 金额：优先用流水线已解析的 amount（它是按整句语义算的），否则从原文抓
        val amount = rec.amount.takeIf { it > 0.0 }
            ?: MONEY_SIGN.find(hay)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
            ?: MONEY_YUAN.find(hay)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (amount != null && amount > 0.0) {
            val income = incomeSignals(hay)
            put(
                FactDraft(
                    subject = merchantOf(title, appLabel),
                    predicate = if (income) "收入" else "金额",
                    value = money(amount),
                    kind = FactKind.MONEY,
                    confidence = if (rec.amount > 0.0) 0.9 else 0.82,
                    sourceText = hay.take(120),
                    entityKind = EntityKind.ORG,
                )
            )
        }

        // 3) 时间：只有流水线给出了明确时刻才记。TODO：这里不自己解析「明天下午三点」——
        //    同一句话在 TimeParser 与这里各解析一次，迟早两套口径打架。
        if (rec.deadlineAt > 0L) {
            val isMeeting = MEETING_WORDS.any { hay.contains(it) }
            put(
                FactDraft(
                    subject = if (isMeeting) meetingSubject(title, appLabel) else "我",
                    predicate = if (isMeeting) "会议时间" else "时间",
                    value = fmtTime(rec.deadlineAt),
                    kind = FactKind.SCHEDULE,
                    confidence = 0.7,
                    sourceText = hay.take(120),
                    entityKind = if (isMeeting) EntityKind.PROJECT else null,
                )
            )
        }

        // 4) 电话 / 地址
        (PHONE_MOBILE.find(hay) ?: PHONE_LANDLINE.find(hay))?.groupValues?.getOrNull(1)?.let { p ->
            put(
                FactDraft(
                    subject = brand ?: appLabel,
                    predicate = "电话",
                    value = p,
                    kind = FactKind.PHONE,
                    confidence = 0.9,
                    sourceText = hay.take(120),
                )
            )
        }
        ADDRESS.find(hay)?.groupValues?.getOrNull(1)?.let { addr ->
            // 地址是推断出来的（可能是寄件人地址，也可能是收件人），置信度必须低
            put(
                FactDraft(
                    subject = brand ?: appLabel,
                    predicate = "地址",
                    value = addr,
                    kind = FactKind.ADDRESS,
                    confidence = 0.45,
                    sourceText = hay.take(120),
                    entityKind = EntityKind.PLACE,
                )
            )
        }

        // 5) 用户明说要办的事
        if (rec.source == InboxRecord.SOURCE_CHAT && TODO_WORDS.any { hay.contains(it) }) {
            val t = title.ifBlank { body }.take(40)
            if (t.isNotBlank()) {
                put(
                    FactDraft(
                        subject = "我",
                        predicate = "待办",
                        value = t,
                        kind = FactKind.TODO,
                        confidence = 0.75,
                        sourceText = hay.take(120),
                    )
                )
            }
        }

        return out.values.toList()
    }

    private fun incomeSignals(text: String): Boolean =
        listOf("收入", "到账", "收款", "退款", "入账", "红包").any { text.contains(it) } &&
            listOf("支出", "付款", "消费", "扣款").none { text.contains(it) }

    /** 商户名：优先标题冒号前的短句，其次 App 名。太长的不认，宁可退回 App 名。 */
    private fun merchantOf(title: String, appLabel: String): String {
        val head = title.substringBefore("：").substringBefore(":").trim()
        return if (head.length in 2..12) head else appLabel
    }

    private fun meetingSubject(title: String, appLabel: String): String {
        val t = title.trim().ifBlank { appLabel }
        return t.take(20)
    }

    fun money(v: Double): String =
        if (v == v.toLong().toDouble()) "¥${v.toLong()}" else String.format(Locale.US, "¥%.2f", v)
}
