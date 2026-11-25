package com.zhuli.core

import android.content.Context
import com.zhuli.core.ZhuliLog as Log

/**
 * 来源管理（阶段 F）。
 *
 * ## 为什么要它
 * 真实库摸底（375 条）：`org.ikuuu.vpn` 169 条 + `com.bilibili.app.in` 144 条 = **83%**。
 * 而 NoiseGuard 的黑名单只覆盖日历/时钟/计步这类「会自激」的包，一条都没拦住 ——
 * 结果是少量真消息被大量推送淹没，检索要翻十几页才找到取件码。
 *
 * 用户能一眼看出「哪个 App 在刷屏」，所以让用户按 App 关掉它，比我们猜阈值可靠。
 *
 * ## 为什么不学 Bundl 那样「拦下来存着」
 * 我们不是通知聚合器，收件箱是**长期记忆**。噪声收进来再藏，等于把它留在检索与提醒的候选集里，
 * 而且下次重建索引还得再筛一遍。用户说「别收」就是别收。
 *
 * 参考实现：
 * - Bundl 的 AppRule（按 App 建规则，用户可增删）<https://github.com/eliasfloreteng/bundl>
 * - Message Mirror 的 allowed packages（采集侧直接按白名单过滤）
 *   <https://github.com/Dragon-Born/message-mirror>
 */
object SourceRules {

    private const val TAG = "zhuli.source"

    /** 面板一行：包名 + 显示名 + 条数 + 最近一条时间 + 是否静音。 */
    data class Row(
        val pkg: String,
        val label: String,
        val count: Int,
        val lastAt: Long,
        val muted: Boolean
    )

    /**
     * 来源列表，按条数降序。
     * 静音的来源即使已经没有历史行也要列出来（否则用户没法取消静音）。
     */
    fun rows(ctx: Context, db: InboxDatabase, limit: Int = 30): List<Row> {
        val muted = db.mutedPackages()
        val out = ArrayList<Row>(limit)
        for (s in db.topSources(limit)) {
            out.add(Row(s.pkg, pickLabel(labelOf(ctx, s.pkg), s.label, s.pkg), s.count, s.lastAt, s.pkg in muted))
        }
        // 已静音但历史已清空的来源：补在末尾，保证能取消静音
        val shown = out.map { it.pkg }.toHashSet()
        for (pkg in muted) {
            if (pkg !in shown) out.add(Row(pkg, pickLabel(labelOf(ctx, pkg), "", pkg), 0, 0L, true))
        }
        return out
    }

    fun setMuted(db: InboxDatabase, pkg: String, muted: Boolean) {
        db.setSourceMuted(pkg, muted)
        Log.i(TAG, "mute pkg=$pkg -> $muted")
    }

    /**
     * 静音 + 清历史（可选）。先撤闹钟与通知，再删行 —— 反过来会留下没有主人的闹钟。
     *
     * @return 删掉的收件箱行数；-1 表示静音本身失败
     */
    fun muteAndPurge(ctx: Context, db: InboxDatabase, pkg: String): Int = try {
        db.setSourceMuted(pkg, true)
        val ids = db.inboxIdsOfPackage(pkg)
        if (ids.isEmpty()) 0 else {
            for (rem in db.remindersByInboxIds(ids)) {
                try { ReminderEngine.cancelAlarm(ctx, rem.id) } catch (_: Throwable) {}
                try { ReminderNotifier.cancel(ctx, rem.id) } catch (_: Throwable) {}
                if (rem.calendarEventId > 0) {
                    try { CalendarWriter.remove(ctx, rem) } catch (_: Throwable) {}
                }
            }
            db.deleteRowsOfPackage(pkg)
        }
    } catch (t: Throwable) {
        Log.e(TAG, "muteAndPurge failed pkg=$pkg", t)
        -1
    }

    private fun labelOf(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (t: Throwable) {
        ""
    }

    /**
     * 显示名的降级顺序：系统查得到就用系统名，否则用采集时记在库里的 app_label，
     * 最后才是包名尾巴。抽成纯函数是为了能在单测里覆盖三条分支（无 Context）。
     *
     * 为什么要这一层：Android 11+ 包可见性限制下，Activity 里查第三方包会抛
     * NameNotFoundException —— 真机上 B 站显示成 "in"、微信显示成 "mm"、QQ 显示成
     * "mobileqq"。而采集侧（通知监听）拿得到名字，库里 `app_label` 是准的。
     */
    fun pickLabel(pmLabel: String?, dbLabel: String?, pkg: String): String {
        val a = pmLabel?.trim().orEmpty()
        if (a.isNotEmpty()) return a
        val b = dbLabel?.trim().orEmpty()
        if (b.isNotEmpty()) return b
        return pkg.substringAfterLast('.').ifBlank { pkg }
    }
}
