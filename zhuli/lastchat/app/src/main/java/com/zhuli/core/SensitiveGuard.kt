package com.zhuli.core

import java.util.regex.Pattern

/**
 * 敏感特征判定（需求 §3 落地细则）。
 *
 * 口径：**拿不准就按敏感处理**（宁可慢，不可泄）。
 * 命中即视为「只能本地处理」——[PrivacyRouter] 拿它当唯一闸门，任何出网调用都要先过。
 *
 * v0.9.2（第 9 步）两处调整：
 * 1. **收紧地址规则**：原来单字「道 / 街 / 路」就判敏感，等于「我知道」「这条路怎么走」全部锁死本地，
 *    云端永远用不上。改成必须成结构：省市组合、或「区/县/路/街 + 号/号院/号楼」，或强特征词；
 * 2. **补「公司+岗位」组合型敏感**（需求 §6 开放问题：公司名+职位要不要算敏感 → 定为算）。
 */
object SensitiveGuard {

    private val PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)")
    private val BANK_CARD = Pattern.compile("(?<!\\d)\\d{16,19}(?!\\d)")
    /**
     * 金额：三档都算敏感。
     * - **带币种符号就算**（¥45 / ￥1234.50）—— 真机验收时发现「¥545.60」这种没有「元」字的金额会漏判，
     *   而它恰恰是记账小结的常见形态，漏一次就把账本带出机了；
     * - 数字 + 单位（45 元 / 3.5 万 / 200 块）；
     * - 财务词（工资/余额/还款/转账/红包…）。
     */
    private val MONEY = Pattern.compile(
        "(?:¥|￥|\\$)\\s*\\d[\\d,]*(?:\\.\\d{1,2})?" +
            "|\\d+(?:\\.\\d{1,2})?\\s*(?:元|块钱|块|万元|万|RMB)" +
            "|工资|余额|还款|账单金额|转账|红包"
    )
    private val CREDENTIAL = Pattern.compile(
        "(?:密码|口令|验证码|短信码|token|api[_\\s-]?key|secret|授权码)",
        Pattern.CASE_INSENSITIVE
    )
    private val URL_WITH_SECRET = Pattern.compile(
        "https?://\\S*(?:token|key|sig|sign|auth|secret|session|password)=",
        Pattern.CASE_INSENSITIVE
    )

    /** 地址：必须成结构，单字不算（否则「知道」都会被当成地址） */
    private val ADDRESS = Pattern.compile(
        "(?:[\\u4e00-\\u9fa5]{2,8}(?:省|自治区)\\s*[\\u4e00-\\u9fa5]{2,8}(?:市|州|盟))" +   // 省 + 市
            // 区/县 + 路 + 号：数字两侧允许空格 —— 用户手打地址常写成「建国路 88 号」，
            // v0.18 实测就是因为不容忍空格，只吃掉了「朝阳区建国路」，门牌全留在请求体里（T-B6 抓到）
            "|(?:[\\u4e00-\\u9fa5]{2,8}(?:市|区|县)[\\u4e00-\\u9fa5]{2,10}(?:路|街|大道|巷|胡同)\\s*\\d{0,4}\\s*号?)" +
            "|(?:\\d{1,4}\\s*号楼|\\d{1,4}\\s*单元|\\d{3,4}\\s*室|门牌号|小区\\s*\\d|\\d+\\s*栋)"   // 强特征
    )

    /** 公司名 + 岗位的组合型敏感（需求 §6 那条开放问题，这里定成「算敏感」） */
    private val ORG = Pattern.compile("(?:公司|集团|股份|有限|科技|银行|医院|学校|大学|研究院|事务所)")
    private val ROLE = Pattern.compile(
        "(?:经理|总监|主管|总裁|董事长|总经理|工程师|设计师|会计|出纳|律师|医生|护士|老师|教授|处长|科长|局长|主任)"
    )

    /** 命中的敏感类型名，未命中返回 null（便于日志里写原因，不写内容）。 */
    fun reason(text: String): String? = when {
        text.isBlank() -> null
        PHONE.matcher(text).find() -> "电话"
        ID_CARD.matcher(text).find() -> "证件号"
        BANK_CARD.matcher(text).find() -> "卡号"
        CREDENTIAL.matcher(text).find() -> "凭证"
        URL_WITH_SECRET.matcher(text).find() -> "凭证链接"
        MONEY.matcher(text).find() -> "金额"
        ADDRESS.matcher(text).find() -> "地址"
        ORG.matcher(text).find() && ROLE.matcher(text).find() -> "公司+岗位"
        else -> null
    }

    fun isSensitive(text: String): Boolean = reason(text) != null

    /**
     * 命中的敏感片段原文（T-B7 用）。
     *
     * 只给"自检"看：拿原始命中片段去比请求体，比"再判一次"更能说明问题——
     * 判定-脱敏一致性查的是"还会不会判敏感"，这里查的是"原文那几段到底还在不在"。
     * 不返回给云、不写日志。
     */
    fun hits(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        for (p in listOf(PHONE, ID_CARD, BANK_CARD, MONEY, CREDENTIAL, URL_WITH_SECRET, ADDRESS)) {
            val m = p.matcher(text)
            while (m.find()) m.group().takeIf { it.isNotBlank() }?.let { out += it }
        }
        if (ORG.matcher(text).find() && ROLE.matcher(text).find()) {
            for (p in listOf(ORG, ROLE)) {
                val m = p.matcher(text)
                while (m.find()) m.group().takeIf { it.isNotBlank() }?.let { out += it }
            }
        }
        return out.distinct()
    }

    /**
     * 脱敏：把命中的敏感片段替换成占位符，用于「出网留档」。
     * 注意这不是「脱敏后上云」的许可 —— 判定为上云的东西本来就不敏感，
     * 这里是给留档和排障用的第二道保险：万一规则漏了什么，留档里也不会躺着原文。
     */
    fun redact(text: String): String {
        var s = text
        s = PHONE.matcher(s).replaceAll("[手机号]")
        s = ID_CARD.matcher(s).replaceAll("[证件号]")
        s = BANK_CARD.matcher(s).replaceAll("[卡号]")
        s = MONEY.matcher(s).replaceAll("[金额]")
        s = CREDENTIAL.matcher(s).replaceAll("[凭证]")
        s = URL_WITH_SECRET.matcher(s).replaceAll("[凭证链接]")
        // D3 修复（T-B1）：地址与「公司+岗位」此前是"判定认敏感、脱敏不替换"，
        // 结果原文照样上云还标着 redacted=true。这里补齐：
        // - 地址能命中就说明结构完整（省+市 / 区+路+号 / N号楼），误伤面小，直接替换；
        // - 单位与岗位用**组合判定**：两者同现才打码，避免把"我公司楼下"这种日常话一起替换掉。
        s = ADDRESS.matcher(s).replaceAll("[地址]")
        if (ORG.matcher(s).find() && ROLE.matcher(s).find()) {
            s = ORG.matcher(s).replaceAll("[公司]")
            s = ROLE.matcher(s).replaceAll("[岗位]")
            s = s.replace("[公司][公司]", "[公司]")   // "有限公司" 会被 ORG 命中两次
        }
        return s
    }
}
