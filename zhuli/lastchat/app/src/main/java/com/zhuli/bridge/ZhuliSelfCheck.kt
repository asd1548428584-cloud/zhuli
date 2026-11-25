package com.zhuli.bridge

import java.util.Locale

/**
 * 自检面板渲染口径（T-G2/B2 移植，来自 zhuli 旧壳 SelfCheck 纯逻辑部分 1-289 行）。
 *
 * 旧壳 SelfCheck = 纯渲染(本类) + SelfCheckAndroid(系统状态探测)。
 * 新壳的"取值层"改用 LastChat 的 PermissionChecker / 状态流，不照搬 Android 取值——
 * 这里只搬"展示口径"：三档判定、分组渲染、14 行 Item 的文案与 Level 规则。
 *
 * 三档没有 INFO：每一行都必须有结论。总评取最高档，一条断不能被九条正常平均掉。
 */
object ZhuliSelfCheck {

    enum class Level { OK, WARN, BAD }

    data class Item(val group: String, val label: String, val value: String, val level: Level)

    const val G_COLLECT = "① 采集"
    const val G_KNOW = "② 知道什么"
    const val G_REMIND = "③ 提醒"
    const val G_ALIVE = "④ 权限与保活"
    const val G_CLOUD = "⑤ 出网"
    const val G_FIX = "⑥ 怎么纠正"

    const val STALE_WARN_H = 24L
    const val STALE_BAD_H = 72L
    const val BACKLOG_WARN = 5

    fun mark(level: Level): String = when (level) {
        Level.OK -> "✅"
        Level.WARN -> "⚠️"
        Level.BAD -> "❌"
    }

    fun overall(items: List<Item>): Level = when {
        items.any { it.level == Level.BAD } -> Level.BAD
        items.any { it.level == Level.WARN } -> Level.WARN
        else -> Level.OK
    }

    fun headline(items: List<Item>): String {
        val bad = items.count { it.level == Level.BAD }
        val warn = items.count { it.level == Level.WARN }
        return when {
            bad > 0 -> "$bad 项断了，先修它"
            warn > 0 -> "$warn 项要注意"
            else -> "全部正常"
        }
    }

    fun render(items: List<Item>): String {
        val sb = StringBuilder()
        var cur: String? = null
        for (i in items) {
            if (i.group != cur) {
                if (cur != null) sb.append('\n')
                sb.append("【").append(i.group).append("】\n")
                cur = i.group
            }
            sb.append(mark(i.level)).append(' ').append(i.label).append("：").append(i.value).append('\n')
        }
        return sb.toString().trimEnd()
    }

    fun ago(ms: Long): String {
        if (ms <= 0) return "刚刚"
        val min = ms / 60_000L
        val hour = min / 60L
        val day = hour / 24L
        return when {
            min < 1 -> "刚刚"
            min < 60 -> "$min 分钟前"
            hour < 24 -> "$hour 小时前"
            day < 30 -> "$day 天前"
            else -> "${day / 30} 个月前"
        }
    }

    fun duration(ms: Long): String {
        if (ms <= 0) return "现在"
        val min = ms / 60_000L
        val hour = min / 60L
        val day = hour / 24L
        return when {
            min < 60 -> "$min 分钟"
            hour < 24 -> "$hour 小时"
            else -> "$day 天"
        }
    }

    // ==================== ① 采集 ====================

    fun ingest(lastAt: Long, now: Long, hasAccess: Boolean): Item = when {
        !hasAccess -> Item(G_COLLECT, "通知使用权", "没开 —— 现在收不到任何 App 的通知", Level.BAD)
        lastAt <= 0L -> Item(G_COLLECT, "最近入库", "还没有收到过任何通知（开权限后等一条试试）", Level.WARN)
        else -> {
            val age = now - lastAt
            val h = age / 3_600_000L
            when {
                h < STALE_WARN_H -> Item(G_COLLECT, "最近入库", ago(age) + "，链路是通的", Level.OK)
                h < STALE_BAD_H -> Item(G_COLLECT, "最近入库", "${h} 小时没有新消息 —— 可能被系统限制了后台（去看「权限与保活」）", Level.WARN)
                else -> Item(G_COLLECT, "最近入库", "${h / 24} 天没有新消息 —— 监听大概率已被系统解绑，去系统设置重开一次", Level.BAD)
            }
        }
    }

    fun todayCount(today: Int, total: Int): Item = Item(G_COLLECT, "今天 / 总共", "$today 条 / $total 条", Level.OK)

    /**
     * 监听服务是否真的连上。
     * 权限开了但服务被系统解绑，是"收不到消息"最常见的原因，必须单独一行说清楚。
     */
    fun listener(hasAccess: Boolean, connected: Boolean): Item = when {
        !hasAccess -> Item(G_COLLECT, "监听服务", "通知使用权没开，服务起不来", Level.BAD)
        connected -> Item(G_COLLECT, "监听服务", "已连接，正在盯着通知栏", Level.OK)
        else -> Item(G_COLLECT, "监听服务", "权限在、服务没连上 —— 去系统设置把通知使用权关开一次", Level.BAD)
    }

    fun sources(distinct: Int, muted: Int): Item {
        val tail = if (muted > 0) "（静音 $muted 个来源）" else ""
        return Item(G_COLLECT, "来源", "$distinct 个 App 在给它喂消息$tail", Level.OK)
    }

    // ==================== ② 知道什么 ====================

    fun knowledge(total: Int, pending: Int, sources: Int): Item {
        val level = if (total == 0) Level.WARN else Level.OK
        val value = if (total == 0) "还什么都没收到" else "$total 条记忆 · $sources 个来源 · $pending 条待提取"
        return Item(G_KNOW, "记忆", value, level)
    }

    fun ledger(count: Int, sum: Double): Item = Item(
        G_KNOW, "账本", if (count == 0) "近 30 天没有金额记录" else "近 30 天 $count 笔，合计 ¥" + money(sum), Level.OK
    )

    fun inferred(pendingReminders: Int): Item = when {
        pendingReminders <= 0 -> Item(G_KNOW, "推断出来的事", "没有（时间含糊的消息会先放待确认）", Level.OK)
        pendingReminders <= BACKLOG_WARN -> Item(G_KNOW, "推断出来的事", "$pendingReminders 条在「待确认」里，没排闹钟", Level.OK)
        else -> Item(G_KNOW, "推断出来的事", "$pendingReminders 条堆着 —— 大概率有误判，去「待确认」逐条看依据", Level.WARN)
    }

    // ==================== ③ 提醒 ====================

    fun alarms(count: Int, nextAt: Long, now: Long): Item = when {
        count <= 0 -> Item(G_REMIND, "已排闹钟", "0 个（当前没有要响的事）", Level.OK)
        nextAt > now -> Item(G_REMIND, "已排闹钟", "$count 个，最近一个在 ${duration(nextAt - now)}后", Level.OK)
        else -> Item(G_REMIND, "已排闹钟", "$count 个，但最近一个时间已过 —— 下拉或重开 App 让它补排", Level.WARN)
    }

    fun fence(homeSet: Boolean, workSet: Boolean, hasPermission: Boolean, radiusM: Int = 300): Item {
        val spots = listOfNotNull(if (homeSet) "家" else null, if (workSet) "公司" else null)
        return when {
            spots.isEmpty() -> Item(G_REMIND, "位置提醒", "没设点（可选：设了到家/到公司会提一句）", Level.OK)
            !hasPermission -> Item(G_REMIND, "位置提醒", "设了${spots.joinToString("、")}但没定位权限 —— 到了也不会响", Level.WARN)
            else -> Item(G_REMIND, "位置提醒", "${spots.joinToString("、")} 已设（$radiusM 米内触发）", Level.OK)
        }
    }

    // ==================== ④ 权限与保活 ====================

    fun battery(exempt: Boolean): Item = if (exempt)
        Item(G_ALIVE, "电池优化", "已豁免，息屏后不容易被掐", Level.OK)
    else
        Item(G_ALIVE, "电池优化", "仍受限 —— 息屏久了后台可能被停（点下面「保活引导」）", Level.WARN)

    fun selfNotify(enabled: Boolean): Item = if (enabled)
        Item(G_ALIVE, "本应用通知", "可以弹出来（提醒靠它）", Level.OK)
    else
        Item(G_ALIVE, "本应用通知", "被关了 —— 提醒排了也不会显示，去系统设置打开", Level.BAD)

    fun exactAlarm(can: Boolean): Item = if (can)
        Item(G_ALIVE, "精确闹钟", "可以用，能准点响", Level.OK)
    else
        Item(G_ALIVE, "精确闹钟", "被系统关了 —— 提醒会晚几分钟到十几分钟", Level.WARN)

    fun autoStart(): Item = Item(G_ALIVE, "开机自启", "需手动确认（系统不提供查询接口）", Level.OK)

    fun sms(granted: Boolean): Item = if (granted)
        Item(G_ALIVE, "短信（快递）", "已授权，能自动抓取件码", Level.OK)
    else
        Item(G_ALIVE, "短信（快递）", "没授权 —— 快递只能靠通知和分享进来", Level.WARN)

    fun accessibility(enabled: Boolean): Item = if (enabled)
        Item(G_ALIVE, "无障碍", "已开（可读当前界面文字）", Level.OK)
    else
        Item(G_ALIVE, "无障碍", "没开（可选，只影响读屏类功能）", Level.OK)

    // ==================== ⑤ 出网 ====================

    fun cloud(count: Int, lastAt: Long, now: Long, ready: Boolean): Item {
        if (count <= 0) {
            return Item(G_CLOUD, "数据外出", "0 次 —— 还没往外发过任何东西", Level.OK)
        }
        val tail = if (ready) "云通道已配置" else "有出网记录，但当前没识别到云 Key"
        val whenText = if (lastAt in 1_600_000_000_000L..now) ago(now - lastAt) else "时间未知"
        return Item(G_CLOUD, "数据外出", "$count 次，最近 $whenText（$tail · 点「出网记录」看每次发了什么）", Level.OK)
    }

    // ==================== ⑥ 怎么纠正 ====================

    fun fixHint(muted: Int): Item = Item(
        G_FIX, "记错 / 太吵",
        "嫌吵去「来源白名单」关掉那个应用；后台处理页能看到每条是收下还是拦下、为什么" +
            if (muted > 0) "（已静音 $muted 个来源）" else "",
        Level.OK
    )

    private fun money(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)
}
