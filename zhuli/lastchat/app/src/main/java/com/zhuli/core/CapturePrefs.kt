package com.zhuli.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 采集策略（v2，2026-11-24）。
 *
 * 为什么重做：旧实现"谁弹通知都收"，结果是视频播放的媒体通知被当成新消息读进对话，
 * 而微信这类真正要收的 App 因为正文不在 EXTRA_TEXT 里被整条丢掉 —— 收错的收了一堆，
 * 该收的一条没有。
 *
 * 新口径：
 * 1. **白名单**（默认开）：只收用户勾选的 App，其余一律不进库、也不进给模型看的通知流；
 * 2. 白名单之外还有"结构性过滤"（媒体播放 / 群聊汇总 / 常驻通知 / 进度条），
 *    即使把某个视频 App 加进白名单，正在播放的那条也不会被当成消息；
 * 3. 每一条"收下 / 拦下"都留一条诊断记录，后台处理页能直接看到原因，
 *    不再出现"到底收没收到"只能靠猜的情况。
 */
object CapturePrefs {

    private const val SP = "zhuli_capture"

    private const val K_INIT = "init_v2"
    private const val K_ENABLED = "capture_enabled"
    private const val K_WHITELIST_ON = "whitelist_on"
    private const val K_ALLOWED = "allowed_packages"
    private const val K_MEDIA = "allow_media"
    private const val K_ONGOING = "allow_ongoing"
    private const val K_AUTO_EXTRACT = "auto_extract"
    private const val K_EVENTS = "events_v1"

    /** 诊断记录上限：只留最近这些条，不占空间。 */
    const val MAX_EVENTS = 80

    /**
     * 默认白名单：只放"人发给人的消息"。
     * 视频、音乐、商店、新闻推送都不在这里 —— 想收哪个由用户自己打开。
     */
    val DEFAULT_ALLOWED: Set<String> = linkedSetOf(
        "com.tencent.mm",                    // 微信
        "com.tencent.mobileqq",              // QQ
        "com.tencent.wework",                // 企业微信
        "com.alibaba.android.rimet",         // 钉钉
        "com.ss.android.lark",               // 飞书
        "org.telegram.messenger",            // Telegram
        "com.android.mms",                   // 系统短信（AOSP）
        "com.android.messaging",
        "com.google.android.apps.messaging", // Google 信息
        "com.samsung.android.messaging",
        "com.miui.smsextra",
        "com.zte.mms",
        "com.huawei.message",
        "com.coloros.sms",
        "com.oppo.mms",
        "com.vivo.mms",
    )

    /** 默认候选里给用户看的顺序（没装的会跳过）。 */
    val SUGGESTED: List<Pair<String, String>> = listOf(
        "com.tencent.mm" to "微信",
        "com.tencent.mobileqq" to "QQ",
        "com.tencent.wework" to "企业微信",
        "com.alibaba.android.rimet" to "钉钉",
        "com.ss.android.lark" to "飞书",
        "org.telegram.messenger" to "Telegram",
        "com.android.mms" to "短信",
        "com.google.android.apps.messaging" to "信息",
    )

    /** 一条采集诊断：收下了，还是被什么规则拦下了。 */
    @Serializable
    data class Event(
        val at: Long,
        val pkg: String,
        val label: String,
        val title: String,
        val text: String,
        /** inserted / courier / blocked */
        val action: String,
        val reason: String = "",
    ) {
        val blocked: Boolean get() = action == "blocked"
    }

    private const val A_INSERTED = "inserted"
    private const val A_COURIER = "courier"
    private const val A_BLOCKED = "blocked"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun ensureInit(ctx: Context) {
        val p = sp(ctx)
        if (p.getBoolean(K_INIT, false)) return
        p.edit()
            .putBoolean(K_INIT, true)
            .putBoolean(K_ENABLED, true)
            .putBoolean(K_WHITELIST_ON, true)
            .putBoolean(K_MEDIA, false)
            .putBoolean(K_ONGOING, false)
            .putBoolean(K_AUTO_EXTRACT, true)
            .putStringSet(K_ALLOWED, DEFAULT_ALLOWED)
            .apply()
    }

    // ==================== 总开关 ====================

    fun enabled(ctx: Context): Boolean {
        ensureInit(ctx)
        return sp(ctx).getBoolean(K_ENABLED, true)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ensureInit(ctx)
        sp(ctx).edit().putBoolean(K_ENABLED, on).apply()
    }

    // ==================== 白名单 ====================

    fun whitelistEnabled(ctx: Context): Boolean {
        ensureInit(ctx)
        return sp(ctx).getBoolean(K_WHITELIST_ON, true)
    }

    fun setWhitelistEnabled(ctx: Context, on: Boolean) {
        ensureInit(ctx)
        sp(ctx).edit().putBoolean(K_WHITELIST_ON, on).apply()
    }

    fun allowedPackages(ctx: Context): Set<String> {
        ensureInit(ctx)
        return sp(ctx).getStringSet(K_ALLOWED, DEFAULT_ALLOWED)?.toSet() ?: DEFAULT_ALLOWED
    }

    fun isAllowed(ctx: Context, pkg: String): Boolean {
        if (!whitelistEnabled(ctx)) return true
        return pkg in allowedPackages(ctx)
    }

    fun setAllowed(ctx: Context, pkg: String, allowed: Boolean) {
        ensureInit(ctx)
        val next = allowedPackages(ctx).toMutableSet()
        if (allowed) next.add(pkg) else next.remove(pkg)
        sp(ctx).edit().putStringSet(K_ALLOWED, next).apply()
    }

    fun allowAll(ctx: Context, pkgs: Collection<String>) {
        ensureInit(ctx)
        val next = allowedPackages(ctx).toMutableSet().apply { addAll(pkgs) }
        sp(ctx).edit().putStringSet(K_ALLOWED, next).apply()
    }

    fun resetWhitelist(ctx: Context) {
        ensureInit(ctx)
        sp(ctx).edit().putStringSet(K_ALLOWED, DEFAULT_ALLOWED).apply()
    }

    /** 这个包在当前手机上装没装（默认白名单里预置了各厂商短信包，不是都装）。 */
    fun isInstalled(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(pkg, 0)
        true
    }.getOrDefault(false)

    /** 已允许且确实装了应用的数量：首页那句"正在看着 N 个应用"要用真实数字。 */
    fun installedAllowedCount(ctx: Context): Int =
        allowedPackages(ctx).count { isInstalled(ctx, it) }

    // ==================== 结构过滤开关 ====================

    /** 媒体播放通知（视频/音乐），默认关。 */
    fun allowMedia(ctx: Context): Boolean {
        ensureInit(ctx)
        return sp(ctx).getBoolean(K_MEDIA, false)
    }

    fun setAllowMedia(ctx: Context, on: Boolean) {
        ensureInit(ctx)
        sp(ctx).edit().putBoolean(K_MEDIA, on).apply()
    }

    /** 常驻通知（前台服务、下载中），默认关。 */
    fun allowOngoing(ctx: Context): Boolean {
        ensureInit(ctx)
        return sp(ctx).getBoolean(K_ONGOING, false)
    }

    fun setAllowOngoing(ctx: Context, on: Boolean) {
        ensureInit(ctx)
        sp(ctx).edit().putBoolean(K_ONGOING, on).apply()
    }

    /** 入库后是否自动跑提取（拆时间/金额/分类）。 */
    fun autoExtract(ctx: Context): Boolean {
        ensureInit(ctx)
        return sp(ctx).getBoolean(K_AUTO_EXTRACT, true)
    }

    fun setAutoExtract(ctx: Context, on: Boolean) {
        ensureInit(ctx)
        sp(ctx).edit().putBoolean(K_AUTO_EXTRACT, on).apply()
    }

    // ==================== 诊断记录 ====================

    fun record(
        ctx: Context,
        pkg: String,
        label: String,
        title: String,
        text: String,
        action: String,
        reason: String = "",
    ) {
        try {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val list = ArrayList(readEvents(ctx))
                // 刷屏类被拦（媒体播放/不在白名单）一分钟内只记一次，别把有用的记录挤掉
                if (action == A_BLOCKED) {
                    val head = list.firstOrNull()
                    if (head != null && head.action == A_BLOCKED && head.pkg == pkg &&
                        head.reason == reason && now - head.at < 60_000L
                    ) {
                        return
                    }
                }
                list.add(
                    0,
                    Event(
                        at = now,
                        pkg = pkg,
                        label = label.ifBlank { pkg.substringAfterLast('.') },
                        title = title.take(80),
                        text = text.take(120),
                        action = action,
                        reason = reason.take(80),
                    )
                )
                while (list.size > MAX_EVENTS) list.removeAt(list.size - 1)
                writeEvents(ctx, list)
            }
        } catch (_: Throwable) {
            // 诊断记录失败绝不影响采集主链路
        }
    }

    fun recordInserted(ctx: Context, pkg: String, label: String, title: String, text: String) =
        record(ctx, pkg, label, title, text, A_INSERTED)

    fun recordCourier(ctx: Context, pkg: String, label: String, title: String, text: String) =
        record(ctx, pkg, label, title, text, A_COURIER, "识别为快递/取件码")

    fun recordBlocked(ctx: Context, pkg: String, label: String, title: String, text: String, reason: String) =
        record(ctx, pkg, label, title, text, A_BLOCKED, reason)

    fun recentEvents(ctx: Context, limit: Int = 30): List<Event> =
        readEvents(ctx).take(limit)

    fun clearEvents(ctx: Context) {
        synchronized(lock) { sp(ctx).edit().remove(K_EVENTS).apply() }
    }

    private fun readEvents(ctx: Context): List<Event> {
        val raw = sp(ctx).getString(K_EVENTS, null) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(Event.serializer()), raw)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeEvents(ctx: Context, list: List<Event>) {
        val raw = json.encodeToString(ListSerializer(Event.serializer()), list)
        sp(ctx).edit().putString(K_EVENTS, raw).apply()
    }
}
