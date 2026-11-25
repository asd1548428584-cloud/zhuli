package com.zhuli.core

import android.content.Context
import com.zhuli.core.ZhuliLog as Log
import java.util.Calendar
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * 提取流水线（第 4 步·规则版）：把收件箱里 pending=1 的条目做
 *   分类 → 时间挖掘 → 金额抽取 → 待办备注
 * 结果原地写回收件箱行（[InboxDatabase.applyExtract]），供对话查询 / 记账小结 / 第 5 步提醒引擎消费。
 *
 * 为什么先做规则版：
 * - D2 隐私底线要求「敏感内容全本地」，规则版天然不出网、零 Key、零模型加载开销；
 * - 第 9 步接本地小模型 / 云 API 时，只换本文件的实现，[ExtractFields] 字段契约不动，上层零改动。
 *
 * 已知边界（诚实标注）：
 * - 人名不做硬猜（中文人名歧义太大，猜错比不猜更糟），先用 note 原文承载，第 15 步实体归一时再上；
 * - 周期型（每周/每月/每天）deadline 归 0，交给第 5 步提醒引擎专项处理；
 * - 时间只认「单点时刻」，不认「区间」「工作日」这类复杂表述。
 */
object ExtractPipeline {

    private const val TAG = "zhuli.extract"
    private val io = Executors.newSingleThreadExecutor()

    // ==================== 分类词表 ====================

    private val CAT_EXPRESS = Pattern.compile("快递|物流|取件|驿站|运单|签收|派送|件到|到件|丰巢|菜鸟")
    private val CAT_FINANCE = Pattern.compile("元|块|¥|￥|\\$|费|报销|还款|充值|工资|账单|付款|收入|存款|房租|押金|转账|红包")
    private val CAT_WORK = Pattern.compile(
        "会议|开会|周会|例会|汇报|报告|提交|交材料|甲方|方案|项目|加班|任务|评审|复盘|总结|周报|日报|ppt|deadline",
        Pattern.CASE_INSENSITIVE
    )

    // ==================== 时间词 ====================

    private val P_DATE_MD = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?")
    /** 只写「25号」「31日」这种不带月份的，默认归本月（已过就顺延下月） */
    private val P_DAY_ONLY = Pattern.compile("(\\d{1,2})\\s*[日号]")
    private val P_RELATIVE = Pattern.compile("大后天|后天|明晚|明天|今晚|今天|昨晚")
    private val P_WEEKDAY = Pattern.compile("(下+\\s*)?(?:周|星期|礼拜)\\s*([一二三四五六日天1-7])")
    private val P_RECURRING = Pattern.compile("每月|每周|每天|每年|每季度")
    /** 第 14 步：周期事项。第 4 步时这里是「直接放弃」，现在解析成规则交给提醒引擎 */
    private val P_DAILY = Pattern.compile("每天|每日|天天")
    private val P_WEEKLY = Pattern.compile("每\\s*周\\s*([一二三四五六日天1-7])")
    private val P_MONTHLY = Pattern.compile("每\\s*月\\s*(\\d{1,2}|[一二三四五六七八九十]{1,3})\\s*[日号]")

    /** 「N分钟后 / N小时后」—— 手边说边记的场景最常用 */
    private val P_AFTER = Pattern.compile(
        "(\\d{1,3}|[一二两三四五六七八九十]{1,3})\\s*(分钟|分|小时|钟头)\\s*(?:后|以后|之后)"
    )
    /** 时刻：数字或中文数字（三点半、十点、十二点），前缀决定上下午 */
    private val P_CLOCK = Pattern.compile(
        "(凌晨|今早|明早|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜间|今晚|明晚|昨晚)?" +
            "\\s*(\\d{1,2}|[一二两三四五六七八九十]{1,3})\\s*[:：点时]\\s*" +
            "(半|\\d{1,2}|[一二三四五六七八九十]{1,2})?"
    )
    /** 只有时段没有具体点（「明天晚上吃饭」）时的兜底时刻 */
    private val P_PERIOD = Pattern.compile("凌晨|今早|明早|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|夜间|今晚|明晚|昨晚")

    /** 备注清洗用：句读处断开（ASCII 的 . 和 , 不切，否则「46.5 元」会被切成「46」） */
    private val P_PUNCT = Pattern.compile("[。！？!?，；;：\\n\\r]+")

    // ==================== 金额 ====================

    private val P_MONEY = Pattern.compile("(?:¥|￥|\\$)?\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(元|块钱|块|RMB|rmb)")
    private val P_PLAIN_NUM = Pattern.compile("(\\d+(?:\\.\\d{1,2})?)")
    private val AMOUNT_CTX = listOf(
        "元", "块", "费", "报销", "充", "还", "工资", "收入", "房租", "押金",
        "价", "账", "转账", "红包", "花", "付", "入账",
        // 口语记账最常见形态：「午饭 32」「咖啡 28」——不带单位的裸数字，靠饭钱类词兜住，
        // 不加「买」这类宽词（「买了房 300 万」这种误伤比漏记更糟）
        "饭", "餐", "外卖", "咖啡", "奶茶", "饮料"
    )

    // ==================== 对外入口 ====================

    /**
     * 处理一批待提取条目，返回实际写入条数。
     * 调用方负责在后台线程执行（[trigger] 已代劳）。
     */
    fun run(context: Context): Int {
        val app = context.applicationContext
        val db = InboxDatabase(app)
        var done = 0
        try {
            val rows = db.pendingRows()
            if (rows.isEmpty()) return 0
            for (rec in rows) {
                try {
                    val f = extract(rec)
                    db.applyExtract(rec.id, f)
                    // v15：同一条消息过完提取流水线后抽结构化事实。放在 applyExtract 之后，
                    // 这样金额/时间用的是流水线已解析的结果，不在两处各解析一遍。
                    // 事实写入失败不能让消息卡在 pending（记忆是增强，不是入库前提）。
                    runCatching {
                        val enriched = rec.copy(
                            category = f.category, deadlineAt = f.deadlineTs,
                            amount = f.amount, note = f.note,
                        )
                        val drafts = com.zhuli.memory.MemoryExtractor.extract(enriched)
                        if (drafts.isNotEmpty()) {
                            com.zhuli.memory.MemoryRepo(db).ingest(
                                drafts, com.zhuli.memory.SourceType.INBOX, rec.id, System.currentTimeMillis()
                            )
                        }
                    }.onFailure { Log.w(TAG, "memory ingest failed id=${rec.id}", it) }
                    // 第 7 步：像交易的带金额行同时入账（UNIQUE(inbox_id) 幂等，重复扫描不会重复记）
                    if (f.amount > 0) {
                        try {
                            Ledger.ingest(db, rec.id, buildString {
                                if (rec.title.isNotBlank()) append(rec.title).append(' ')
                                append(rec.text)
                            }, f.amount, rec.receivedAt)
                        } catch (t2: Throwable) { Log.e(TAG, "ledger ingest failed id=${rec.id}", t2) }
                    }
                    done++
                } catch (t: Throwable) {
                    // 单条失败不能拖垮整批：打日志留在 pending，下轮再试
                    Log.e(TAG, "extract failed id=${rec.id}", t)
                }
            }
        } finally {
            try { db.close() } catch (_: Throwable) {}
        }
        Log.i(TAG, "extracted $done rows")
        return done
    }

    /** 写入侧统一调用：异步跑一轮，绝不阻塞通知/分享主链路。 */
    fun trigger(context: Context, onDone: ((Int) -> Unit)? = null) {
        val app = context.applicationContext
        io.execute {
            val n = try { run(app) } catch (t: Throwable) { Log.e(TAG, "run failed", t); 0 }
            // 提取完立即排提醒（第 5 步）：否则新消息要等下次开 App 才会建闹钟
            if (n > 0) {
                try { ReminderEngine.scan(app) } catch (t: Throwable) { Log.e(TAG, "reminder scan failed", t) }
            }
            if (n > 0) onDone?.invoke(n)
        }
    }

    /** 单条记录 → 结构化字段（纯函数，方便单测/回放）。 */
    fun extract(rec: InboxRecord): ExtractFields {
        // 链接条目在第 3 步就定了性质，不再按正文关键词重判，否则会被正文里的「元」拽去金融
        if (rec.source == InboxRecord.SOURCE_LINK) {
            return ExtractFields(
                category = InboxRecord.CAT_LINK,
                deadlineTs = 0L,
                amount = 0.0,
                note = rec.summary.take(60).ifBlank { "链接" }
            )
        }
        val text = buildString {
            if (rec.title.isNotBlank()) append(rec.title).append(' ')
            append(rec.text)
        }
        return extract(text)
    }

    /** 原文 → 结构化字段（纯函数）。 */
    fun extract(text: String): ExtractFields {
        val now = Calendar.getInstance()
        val repeat = parseRepeat(text)
        // 周期事项：deadline 取「下一次该响的时刻」，规则另存一列，响完由引擎自动顺延
        val deadline = if (repeat.isBlank()) {
            // V3.1 T-D7：正则先算（已被真机样本验过），算不出来才交给 xk-time 兜底
            val byRule = parseDeadline(text, now)
            if (byRule != 0L) byRule else deadlineViaTimeParser(text, now)
        } else {
            val clock = parseClock(text) ?: periodDefault(text) ?: (9 * 60)
            val first = applyClock(startOfToday(now).timeInMillis, clock)
            Repeat.firstAfter(repeat, first)
        }
        val category = classify(text, deadline != 0L)
        val amount = parseAmount(text)
        val note = cleanNote(text)
        return ExtractFields(category, deadline, amount, note, repeat)
    }

    /**
     * V3.1 T-D7：正则没认出时间时，交给 [TimeParser]（xk-time）兜底。
     *
     * 只接受"未来"的时刻；只给日期不给点的（"下周三"），按当天 9:00 处理——
     * 与本文件 `periodDefault` 的兜底一致，不擅自排到凌晨 0 点。
     * 兜出来的时间同样要过"不能是过去"这道关，否则不如不建。
     */
    private fun deadlineViaTimeParser(text: String, now: Calendar): Long {
        val hit = TimeParser.firstFuture(text, now.timeInMillis) ?: return 0L
        val target = if (hit.allDay) {
            val c = Calendar.getInstance().apply { timeInMillis = hit.at }
            applyClock(startOfToday(c).timeInMillis, 9 * 60)
        } else hit.at
        return if (target > now.timeInMillis) target else 0L
    }

    /** 「每天 / 每周三 / 每月 15 号」→ RFC5545 规则串；认不出返回空串。 */    fun parseRepeat(text: String): String {
        if (P_DAILY.matcher(text).find()) return Repeat.daily()
        P_WEEKLY.matcher(text).let { m ->
            if (m.find()) {
                val d = when (val raw = m.group(1)) {
                    "一", "1" -> Calendar.MONDAY
                    "二", "2" -> Calendar.TUESDAY
                    "三", "3" -> Calendar.WEDNESDAY
                    "四", "4" -> Calendar.THURSDAY
                    "五", "5" -> Calendar.FRIDAY
                    "六", "6" -> Calendar.SATURDAY
                    "日", "天", "7" -> Calendar.SUNDAY
                    else -> null
                }
                if (d != null) return Repeat.weekly(d)
            }
        }
        P_MONTHLY.matcher(text).let { m ->
            if (m.find()) {
                val day = cnNum(m.group(1)) ?: 0
                if (day in 1..31) return Repeat.monthly(day)
            }
        }
        return ""
    }

    // ==================== 分类 ====================

    private fun classify(text: String, hasDeadline: Boolean): String {
        if (CAT_EXPRESS.matcher(text).find()) return InboxRecord.CAT_EXPRESS
        if (CAT_FINANCE.matcher(text).find()) return InboxRecord.CAT_FINANCE
        if (CAT_WORK.matcher(text).find()) return InboxRecord.CAT_WORK
        // 有明确时间又没提钱的，是生活安排（约饭、体检、接人）
        if (hasDeadline) return InboxRecord.CAT_LIFE
        return InboxRecord.CAT_OTHER
    }

    // ==================== 时间 ====================

    /** 返回识别到的截止时间戳；0 = 没识别到或周期型事项。 */
    /** 供工具层调用（create_reminder 要把"明天下午三点"变成时间戳）。 */
    fun parseDeadline(text: String, now: Calendar): Long {
        if (P_RECURRING.matcher(text).find()) return 0L

        // 「30分钟后」「2小时后」是相对当下算的，优先级最高，也最不容易歧义
        P_AFTER.matcher(text).let { m ->
            if (m.find()) {
                val n = cnNum(m.group(1)) ?: 0
                val isHour = m.group(2) == "小时" || m.group(2) == "钟头"
                val delta = if (isHour) n * 3_600_000L else n * 60_000L
                if (delta > 0) {
                    // 只抹掉毫秒：说 2 分钟就 2 分钟，不为了「整分好看」去早 59 秒或晚 1 分钟
                    return (now.timeInMillis + delta) / 1_000L * 1_000L
                }
            }
        }

        // 文本里最靠左的「日期类」词优先：一句里同时出现「明天」和「10月1日」时，先说的算数
        var bestPos = Int.MAX_VALUE
        var bestValue: Long? = null

        P_DATE_MD.matcher(text).let { m ->
            if (m.find()) {
                val mon = m.group(1)!!.toIntOrNull() ?: 0
                val day = m.group(2)!!.toIntOrNull() ?: 0
                val ts = absoluteDate(mon, day, now)
                if (ts != null && m.start() < bestPos) { bestPos = m.start(); bestValue = ts }
            }
        }
        P_RELATIVE.matcher(text).let { m ->
            if (m.find()) {
                val ts = relativeDate(m.group(), now)
                if (m.start() < bestPos) { bestPos = m.start(); bestValue = ts }
            }
        }
        P_WEEKDAY.matcher(text).let { m ->
            if (m.find()) {
                val ts = weekdayDate(m.group(1) != null, m.group(2)!!, now)
                if (m.start() < bestPos) { bestPos = m.start(); bestValue = ts }
            }
        }
        // 没有「几月几号」时才认「25号」这种裸日号，避免把「10月1日」的 1日 当成独立规则
        if (bestValue == null) {
            P_DAY_ONLY.matcher(text).let { m ->
                if (m.find()) {
                    val day = m.group(1)!!.toIntOrNull() ?: 0
                    val ts = dayOfMonth(day, now)
                    if (ts != null) { bestPos = m.start(); bestValue = ts }
                }
            }
        }

        // 时刻：先找「下午三点半」这类明确时刻，退而求其次用时段默认值（晚上→20:00）
        val clock = parseClock(text) ?: periodDefault(text)
        if (bestValue != null) {
            return applyClock(bestValue!!, clock)
        }
        // 只有「下午 3 点」这种纯时刻：过了点就顺延到明天，别生成一个已经过去的提醒
        if (clock != null) {
            val today = atClock(startOfToday(now), clock)
            return if (today > now.timeInMillis) today else today + DAY_MS
        }
        return 0L
    }

    private fun startOfToday(now: Calendar): Calendar =
        (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

    private fun relativeDate(word: String, now: Calendar): Long {
        val days = when (word) {
            "今天", "今晚" -> 0
            "明天", "明晚" -> 1
            "后天" -> 2
            "大后天" -> 3
            "昨晚" -> -1
            else -> 0
        }
        return startOfToday(now).apply { add(Calendar.DAY_OF_YEAR, days) }.timeInMillis
    }

    /** 「25号」这种只带日号的：归本月，已过则顺延下月 */
    private fun dayOfMonth(day: Int, now: Calendar): Long? {
        if (day !in 1..31) return null
        val c = startOfToday(now).apply { set(Calendar.DAY_OF_MONTH, day) }
        if (c.timeInMillis + DAY_MS < startOfToday(now).timeInMillis) {
            c.add(Calendar.MONTH, 1)
            c.set(Calendar.DAY_OF_MONTH, day)
        }
        return c.timeInMillis
    }

    private fun weekdayDate(isNext: Boolean, dayChar: String, now: Calendar): Long {
        val target = when (dayChar) {
            "一", "1" -> Calendar.MONDAY
            "二", "2" -> Calendar.TUESDAY
            "三", "3" -> Calendar.WEDNESDAY
            "四", "4" -> Calendar.THURSDAY
            "五", "5" -> Calendar.FRIDAY
            "六", "6" -> Calendar.SATURDAY
            else -> Calendar.SUNDAY
        }
        val today = startOfToday(now)
        var delta = (target - today.get(Calendar.DAY_OF_WEEK) + 7) % 7
        if (isNext) delta += 7
        return today.apply { add(Calendar.DAY_OF_YEAR, delta) }.timeInMillis
    }

    private fun absoluteDate(mon: Int, day: Int, now: Calendar): Long? {
        if (mon !in 1..12 || day !in 1..31) return null
        val c = startOfToday(now).apply {
            set(Calendar.MONTH, mon - 1)
            set(Calendar.DAY_OF_MONTH, day)
        }
        // 日子已过（比如今天 11 月说「10 月 1 日」）→ 顺延到明年
        if (c.timeInMillis + DAY_MS < startOfToday(now).timeInMillis) {
            c.add(Calendar.YEAR, 1)
        }
        return c.timeInMillis
    }

    /** 时刻解析：「下午三点半」→ 15:30；「晚上 8 点」→ 20:00；「十点」→ 10:00 */
    private fun parseClock(text: String): Int? {
        val m = P_CLOCK.matcher(text)
        var hour = -1
        var minute = -1
        while (m.find()) {
            val prefix = m.group(1)
            val h = cnNum(m.group(2)) ?: continue
            val mm = when (val raw = m.group(3)) {
                null, "" -> 0
                "半" -> 30
                else -> cnNum(raw) ?: 0
            }
            var hh = h
            val pm = prefix == "下午" || prefix == "傍晚" || prefix == "晚上" ||
                prefix == "夜里" || prefix == "夜间" || prefix == "今晚" || prefix == "明晚" ||
                prefix == "昨晚"
            if (pm && hh in 1..11) hh += 12
            if (!pm && prefix == "中午" && hh in 1..3) hh += 12
            if (hh !in 0..23 || mm !in 0..59) continue
            if (hour < 0) { hour = hh; minute = mm }
        }
        if (hour < 0) return null
        return hour * 60 + minute
    }

    /** 只说了时段没说几点（「明天晚上吃饭」）时的兜底时刻，别一律按早上 9 点 */
    private fun periodDefault(text: String): Int? {
        val m = P_PERIOD.matcher(text)
        if (!m.find()) return null
        return when (m.group()) {
            "凌晨" -> 1 * 60
            "今早", "明早", "早上", "早晨" -> 8 * 60
            "上午" -> 10 * 60
            "中午" -> 12 * 60
            "下午" -> 15 * 60
            "傍晚" -> 18 * 60
            else -> 20 * 60   // 晚上 / 今晚 / 明晚 / 夜里 / 夜间 / 昨晚
        }
    }

    /** 中文数字 → Int（支持 一~十二、二十、两），阿拉伯数字直接转 */
    private fun cnNum(s: String?): Int? {
        if (s.isNullOrBlank()) return null
        s.trim().toIntOrNull()?.let { return it }
        val d = mapOf(
            '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9
        )
        return when {
            s == "十" -> 10
            s.length == 2 && s[0] == '十' -> d[s[1]]?.plus(10)
            s.length == 2 && s[1] == '十' -> d[s[0]]?.times(10)
            s.length == 3 && s[1] == '十' ->
                d[s[0]]?.times(10)?.plus(d[s[2]] ?: 0)
            s.length == 1 -> d[s[0]]
            else -> null
        }
    }

    private fun applyClock(baseDay: Long, clock: Int?): Long {
        val c = Calendar.getInstance().apply { timeInMillis = baseDay }
        val h = clock?.div(60) ?: 9          // 没给时刻默认早 9 点，别半夜炸醒人
        val mm = clock?.rem(60) ?: 0
        c.set(Calendar.HOUR_OF_DAY, h)
        c.set(Calendar.MINUTE, mm)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun atClock(dayStart: Calendar, clock: Int): Long =
        (dayStart.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, clock / 60)
            set(Calendar.MINUTE, clock % 60)
        }.timeInMillis

    // ==================== 金额 ====================

    private fun parseAmount(text: String): Double {
        // 先认带单位的（最可靠）：¥32 / 32 元 / 32 块
        P_MONEY.matcher(text).let { m ->
            if (m.find()) {
                val v = m.group(1)?.toDoubleOrNull() ?: return 0.0
                return if (v in 0.01..9_999_999.0) v else 0.0
            }
        }
        // 再认裸数字，但必须前后 10 字内有金额语境，否则「17:30」「218 千米」会被当钱
        val m = P_PLAIN_NUM.matcher(text)
        while (m.find()) {
            val v = m.group(1)?.toDoubleOrNull() ?: continue
            if (v <= 0.0 || v > 9_999_999.0) continue
            val lo = maxOf(0, m.start() - 10)
            val hi = minOf(text.length, m.end() + 10)
            val win = text.substring(lo, hi)
            if (AMOUNT_CTX.any { win.contains(it) }) return v
        }
        return 0.0
    }

    // ==================== 备注 ====================

    private fun cleanNote(text: String): String {
        val firstSentence = P_PUNCT.split(text.replace(Regex("\\s+"), " "))
            .firstOrNull { it.isNotBlank() } ?: text
        return firstSentence.trim().take(60)
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
