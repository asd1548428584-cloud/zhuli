package com.zhuli.core

import com.zhuli.core.ZhuliLog as Log
import java.util.regex.Pattern

/**
 * 第 7 步 · 记账模块（需求 §2.4-②）。
 *
 * 双通道共用一条入账口：
 * - 文字通道：消息/通知 → 提取流水线算出金额后，[Ledger.ingest] 判是不是一笔交易；
 * - 截图通道：第 6 步 OCR 出金额/商户 → 同一入口入账。
 *
 * 规则口径：
 * - 分类首发固定十类（餐饮/交通/购物/日用/人情/通讯/医疗/数码/收入/其他），**只加不减**；
 * - 方向二分「收/支」，收入词表优先于支出词表（报销进账）；
 * - 不像交易的（「17:30」「218 千米」「房租每月 3000」）**不硬入账**，防噪声污染账本；
 * - 金额全程本地，统计不出手机（D2）。
 */
object Ledger {

    const val TAG_ID = ""
    const val DIR_INCOME = "收"
    const val DIR_EXPENSE = "支"

    const val CAT_DINING = "餐饮"
    const val CAT_TRANSPORT = "交通"
    const val CAT_SHOPPING = "购物"
    const val CAT_DAILY = "日用"
    const val CAT_FAVOUR = "人情"
    const val CAT_TELECOM = "通讯"
    const val CAT_MEDICAL = "医疗"
    const val CAT_DIGITAL = "数码"
    const val CAT_INCOME = "收入"
    const val CAT_MISC = "其他"

    // ==================== 分类词表（窄词排前、宽词垫后，避免误吞） ====================

    private val P_DINING = Pattern.compile(
        "午饭|晚饭|早饭|早餐|午餐|晚餐|夜宵|宵夜|吃饭|点餐|餐厅|外卖|奶茶|咖啡|瑞幸|星巴克|麦当劳|肯德基|火锅|烧烤|米线|饺子|包子|粥|汉堡|披萨|面条|粉店|水果|饮料|下馆子"
    )
    private val P_TRANSPORT = Pattern.compile(
        "地铁|公交|打车|滴滴|加油|停车|高铁|火车|机票|航班|出租车|出租|顺风车|单车|骑行|过路费|车票|代驾"
    )
    private val P_MEDICAL = Pattern.compile("药|药店|医院|看病|挂号|体检|门诊|拍片|理疗")
    private val P_DIGITAL = Pattern.compile(
        "手机|电脑|平板|耳机|充电器|内存卡|显卡|键盘|鼠标|主机|显示器|Kindle"
    )
    private val P_DAILY = Pattern.compile(
        "水费|电费|燃气费|物业费|宽带费|卫生纸|洗衣液|牙膏|牙刷|毛巾|洗洁精|抽纸|垃圾袋"
    )
    private val P_TELECOM = Pattern.compile("话费|流量|视频会员|音乐会员|大会员|宽带续费")
    private val P_FAVOUR = Pattern.compile("红包|份子|随礼|随份子|请客|礼金|送礼")
    private val P_SHOPPING = Pattern.compile(
        "超市|便利店|京东|淘宝|拼多多|天猫|苏宁|购物|网购|商城|商场|零食|日用品|衣服|鞋|书|文具"
    )
    private val P_INCOME = Pattern.compile(
        "工资|到账|收入|报销|分红|返利|退款|返现|佣金|稿酬|收款|进账|工资到账|报销款"
    )

    /** 无明确类别、但有强烈收支信号时的类别兜底（先判收入，剩下的算支出） */
    private val P_INCOME_SIGNAL = P_INCOME
    private val P_EXPENSE_SIGNAL = Pattern.compile(
        "花|买|付|充|还|交|支出|消费|到付|订阅|续费|扣款|还款|抵扣|缴纳|吃掉|喝掉"
    )

    private val CATS: List<Pair<String, Pattern>> = listOf(
        CAT_DINING to P_DINING,
        CAT_TRANSPORT to P_TRANSPORT,
        CAT_MEDICAL to P_MEDICAL,
        CAT_DIGITAL to P_DIGITAL,
        CAT_DAILY to P_DAILY,
        CAT_TELECOM to P_TELECOM,
        CAT_FAVOUR to P_FAVOUR,
        CAT_SHOPPING to P_SHOPPING,
        CAT_INCOME to P_INCOME
    )

    /** 原文 → [方向, 类别]；不像一笔交易返回 null（不硬入账）。 */
    fun classify(text: String): Pair<String, String>? {
        val t = text.replace(" ", "")
        if (t.isEmpty()) return null
        val hit = CATS.firstOrNull { it.second.matcher(t).find() }
        if (hit != null) {
            return if (hit.first == CAT_INCOME) Pair(DIR_INCOME, CAT_INCOME) else Pair(DIR_EXPENSE, hit.first)
        }
        return when {
            P_INCOME_SIGNAL.matcher(t).find() -> Pair(DIR_INCOME, CAT_INCOME)
            P_EXPENSE_SIGNAL.matcher(t).find() -> Pair(DIR_EXPENSE, CAT_MISC)
            else -> null
        }
    }

    /**
     * 入账（文字/截图同入口）。amount<=0 或不像交易跳过；UNIQUE(inbox_id) 保证一行只入一次。
     */
    fun ingest(db: InboxDatabase, inboxId: Long, text: String, amount: Double, occurredAt: Long) {
        if (amount <= 0.0) return
        val pair = classify(text) ?: return
        try {
            db.insertLedger(
                inboxId, pair.first, pair.second, amount,
                occurredAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                noteOf(pair.second, text)
            )
            Log.i("zhuli.ledger", "ingest dir=${pair.first} cat=${pair.second} amt=$amount")
        } catch (t: Throwable) {
            Log.e("zhuli.ledger", "ingest failed inbox=$inboxId", t)
        }
    }

    /** 备注 = 类别 + 原文短摘（对话小结一眼认出这笔）。 */
    private fun noteOf(cat: String, text: String): String {
        val s = text.replace(Regex("\\s+"), " ").trim()
        return "$cat：${s.take(24)}"
    }
}