package com.zhuli.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * 快递文本解析（第 10 步 · 纯本地规则）。
 *
 * 为什么走短信/通知而不是查询接口（真机结论 2026-11-20）：
 * - 系统「智慧建议」里的快递卡片由 `com.zte.mifavor.suggest` 提供，其 `SuggestOutProvider`
 *   受 `signature|privileged` 权限保护，第三方读不到（shell 也是 SecurityException）；
 * - 快递查询 API（快递100/快递鸟/各家开放平台）都要注册、实名、付费或商家资质；
 * - 但**原始信息本来就会发到手机上**：短信与 App 推送里已经写明「某驿站已到站，取件码 x-x-xxxx」。
 *   自己解析一遍，信息一样全，零 Key、零外网、运单号不出机。
 *
 * 只认「有取件码/到站/派送/签收」这类明确语义的文本，其余一律返回 null（宁漏不错）。
 */
object Express {

    /** 解析结果。waybill 可为空（很多取件短信不带运单号）。 */
    data class Info(
        val carrier: String,      // 承运商：顺丰/中通/圆通/韵达/极兔/京东/EMS/邮政…
        val station: String,      // 驿站/代收点名称（能认出来就填）
        val code: String,         // 取件码：8-4-2013 / 1234
        val status: String,       // 已到站 | 派送中 | 已签收 | 滞留
        val waybill: String,      // 运单号（可选）
        val summary: String       // 给收件箱/提醒用的一行摘要
    ) {
        val actionable: Boolean get() = status != STATUS_SIGNED
    }

    const val STATUS_ARRIVED = "已到站"
    const val STATUS_DELIVERING = "派送中"
    const val STATUS_SIGNED = "已签收"
    const val STATUS_STALLED = "滞留"

    // ==================== 词表 ====================

    /** 承运商：先看【】里的标签，再看正文关键词（顺序即优先级，别让「邮政」抢了「邮政EMS」） */
    private val CARRIERS = listOf(
        "顺丰" to listOf("顺丰", "SF"),
        "京东" to listOf("京东物流", "京东快递"),
        "中通" to listOf("中通"),
        "圆通" to listOf("圆通"),
        "韵达" to listOf("韵达"),
        "申通" to listOf("申通"),
        "极兔" to listOf("极兔", "J&T", "JT快递"),
        "EMS" to listOf("邮政EMS", "EMS"),
        "邮政" to listOf("中国邮政", "邮政"),
        "德邦" to listOf("德邦"),
        "丰网" to listOf("丰网")
    )

    /** 驿站/快递柜品牌：认出来就当作 station 的前缀 */
    private val STATIONS = listOf(
        "菜鸟驿站", "兔喜生活", "兔喜", "欢猫驿站", "丰巢", "速递易", "妈妈驿站",
        "邮乐购", "京东快递柜", "中邮驿站", "邮政驿站", "驿站", "快递柜", "代收点", "快递站"
    )

    /** 状态词（长词在前，避免「已到站」被「到」误判） */
    private val ST_SIGNED = Pattern.compile("已签收|本人签收|已代签|签收成功|已妥投|已取出|已取件")
    private val ST_STALLED = Pattern.compile("滞留|超期|逾期|即将退回|退回")
    private val ST_DELIVERING = Pattern.compile("派送中|正在派送|已揽收|运输中|派件中|即将送达|正在为您派送")
    private val ST_ARRIVED = Pattern.compile("已到站|已到达|已放入|已存入|待取件|请凭|凭取件码|取件码|取货|领取|已到[^，。]{0,12}(驿站|代收|门市|超市|车库|网点)")

    /** 取件码：8-4-2013 / 12-3-4567 / 纯 4~6 位（必须紧跟取件/取货/领取语境） */
    private val CODE_DASH = Pattern.compile("(\\d{1,3}-\\d{1,3}-\\d{3,5})")
    private val CODE_PLAIN = Pattern.compile("(?:取件码|取货码|取件号|提取码|密码)[为是:：\\s]*([0-9]{3,8})")
    private val CODE_AFTER_PING = Pattern.compile("凭[^0-9\\n]{0,6}([0-9][0-9\\-]{2,12})")

    /** 运单号：主流承运商前缀/长度规则（只做「看起来像」的判定，不当唯一依据） */
    private val WAYBILL = Pattern.compile(
        "(?:SF|ZTO|YT|YD|JD|JT|STO|EMS|DPK)\\s?\\d{10,16}|(?<!\\d)\\d{12,15}(?!\\d)"
    )

    /** 一句话里必须有这些词，否则不认为是快递信息（防误伤普通短信） */
    private val GATE = Pattern.compile("快递|包裹|取件|取货|驿站|快递柜|代收|签收|派送|派件|运单|物流")

    /** 营销/广告/验证码类一律不算 */
    private val JUNK = Pattern.compile("验证码|绑定|优惠|活动|退款|积分|下载APP|点击|http|www\\.|退订|客服热线")

    // ==================== 解析 ====================

    /** 从短信/通知正文里解析快递信息；不是快递、或信息太弱时返回 null。 */
    fun parse(text: String, receivedAt: Long = System.currentTimeMillis()): Info? {
        if (text.isBlank() || text.length > 600) return null
        if (!GATE.matcher(text).find()) return null
        // 「验证码/绑定」类先排除：ZTE 那条「验证码…快递功能绑定」会被 GATE 命中
        if (Pattern.compile("验证码|动态码|校验码").matcher(text).find()) return null
        if (JUNK.matcher(text).find() && !ST_ARRIVED.matcher(text).find()) return null

        val status = when {
            ST_SIGNED.matcher(text).find() -> STATUS_SIGNED
            ST_STALLED.matcher(text).find() -> STATUS_STALLED
            ST_DELIVERING.matcher(text).find() -> STATUS_DELIVERING
            ST_ARRIVED.matcher(text).find() -> STATUS_ARRIVED
            else -> return null
        }

        val code = pickCode(text)
        // 到站类信息至少要有个取件码或明确的驿站名，否则信息量太低不建提醒
        val station = pickStation(text)
        if (status == STATUS_ARRIVED && code == null && station == null) return null

        val carrier = pickCarrier(text)
        val waybill = pickWaybill(text) ?: ""
        val summary = buildSummary(carrier, station, code, status, text)

        return Info(
            carrier = carrier,
            station = station ?: "",
            code = code ?: "",
            status = status,
            waybill = waybill,
            summary = summary
        )
    }

    private fun pickCarrier(text: String): String {
        // 【XX】标签权重最高
        val tag = Regex("【([^】]{2,10})】").find(text)?.groupValues?.get(1).orEmpty()
        if (tag.isNotBlank()) {
            for ((name, keys) in CARRIERS) if (keys.any { tag.contains(it, true) }) return name
        }
        for ((name, keys) in CARRIERS) if (keys.any { text.contains(it, true) }) return name
        return ""
    }

    private fun pickStation(text: String): String? {
        // 1) 最准的形态：「到XX驿站 / 到XX超市 / 到XX门市 / 到XX车库」，直接把地名抓出来
        Regex("(?:到达|到了|到|至)([\\u4e00-\\u9fa5A-Za-z0-9]{2,16}?(?:驿站|超市|门市|车库|网点|代收点|快递站|快递柜|快递点|服务点))")
            .find(text)?.let { m ->
                return cleanStation(m.groupValues[1])
            }
        // 2) 只报了品牌（「【菜鸟驿站】您的包裹已到站…」）：品牌 + 紧跟的店名，遇到标点就停
        for (brand in STATIONS) {
            val i = text.indexOf(brand)
            if (i < 0) continue
            val tail = text.substring(i + brand.length)
                .takeWhile { c -> c !in "】】，,。；;！!？?（()【[] \n\u3000：:" }
                .take(12)
            return cleanStation(brand + tail)
        }
        return null
    }

    /** 去掉驿站名尾巴上的动词（「代收点领取」→「代收点」）。 */
    private fun cleanStation(raw: String): String {
        var s = raw.trim()
        for (w in listOf("领取", "取件", "取货", "自取", "签收", "通知")) {
            if (s.endsWith(w) && s.length > w.length + 1) s = s.removeSuffix(w)
        }
        return s.take(20)
    }

    private fun pickCode(text: String): String? {
        CODE_DASH.matcher(text).let { m -> if (m.find()) return m.group(1) }
        CODE_PLAIN.matcher(text).let { m -> if (m.find()) return m.group(1) }
        CODE_AFTER_PING.matcher(text).let { m ->
            if (m.find()) {
                val s = m.group(1)?.trim('-') ?: return@let
                if (s.length in 3..13) return s
            }
        }
        return null
    }

    private fun pickWaybill(text: String): String? {
        val m = WAYBILL.matcher(text)
        if (!m.find()) return null
        val s = m.group().replace(" ", "")
        // 取件码 8-4-2013 这种不会被 WAYBILL 命中（有横杠），这里只收纯数字/带前缀的
        return s.takeIf { it.length >= 10 }
    }

    private fun buildSummary(
        carrier: String,
        station: String?,
        code: String?,
        status: String,
        raw: String
    ): String {
        val who = carrier.ifBlank { "快递" }
        val where = station?.takeIf { it.isNotBlank() }
        return when {
            status == STATUS_SIGNED -> "$who 已签收" + (where?.let { "（$it）" } ?: "")
            code != null && where != null -> "$who·$where 取件码 $code"
            code != null -> "$who 取件码 $code（$status）"
            where != null -> "$who 已到 $where，去看看"
            else -> "$who${status}：" + raw.replace(Regex("\\s+"), " ").take(30)
        }
    }

    /** 展示用：把一堆信息压成一行（面板里列快递用）。 */
    fun line(info: Info, at: Long): String {
        val t = SimpleDateFormat("M-d HH:mm", Locale.getDefault()).format(Date(at))
        val code = if (info.code.isNotBlank()) "｜码 ${info.code}" else ""
        return "$t｜${info.status}$code｜${info.summary}"
    }

    /**
     * 取件提醒的时间：默认「到站后 2 小时」，但不超过当晚 20:30。
     * 为什么不是立刻响：取件短信常在上班时间到，立刻响等于打断；放到 2 小时后最贴近"顺路去拿"。
     */
    fun pickupDue(arrivedAt: Long): Long {
        val twoHours = arrivedAt + 2 * 60 * 60 * 1000L
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = arrivedAt
            set(java.util.Calendar.HOUR_OF_DAY, 20)
            set(java.util.Calendar.MINUTE, 30)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val eightThirty = cal.timeInMillis
        val now = System.currentTimeMillis()
        // 已经过点（比如夜里才收到）→ 顺延到明天上午 9 点，别半夜炸人
        if (twoHours < now) return now + 60 * 60 * 1000L
        return minOf(twoHours, maxOf(eightThirty, now + 30 * 60 * 1000L))
    }
}
