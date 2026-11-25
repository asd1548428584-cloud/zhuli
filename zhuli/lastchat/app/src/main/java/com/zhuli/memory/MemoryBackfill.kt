package com.zhuli.memory

import android.content.Context
import com.zhuli.core.ExtractPipeline
import com.zhuli.core.InboxDatabase
import com.zhuli.core.ZhuliLog as Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 可选的历史 inbox 回填（P1 第五条的后半段）。
 *
 * 为什么默认**不**自动跑：历史里混着三年前的取件码、别人的地址、已经作废的会议时间。
 * 一次性全量灌进记忆库，等于用陈旧数据污染"当前事实"——用户问"我住哪"，
 * 系统可能答出上一处住址。所以这里的口径是：
 *
 * - **用户选时间范围**才跑，绝不后台偷偷全量回填；
 * - **可暂停、可继续、失败可重试**（游标存在本对象里，暂停不丢进度）；
 * - **保留来源**：每条事实都带 inbox 行号，能在记忆页追到原文；
 * - **低置信度进待确认**（[FactStatus.UNCONFIRMED]），不参与检索、不覆盖现有记忆；
 * - **不跑模型**：只用规则版 [ExtractPipeline] + [MemoryExtractor]，所以不占推理、不出网。
 *
 * 注意：进度不跨进程持久化（App 被杀后需要重新选范围）。这是有意的取舍 ——
 * 一个后台静默续跑的回填任务，比"用户重新点一次"危险得多。
 */
object MemoryBackfill {

    enum class State { IDLE, RUNNING, PAUSED, DONE, FAILED }

    data class Progress(
        val state: State = State.IDLE,
        val from: Long = 0L,
        val to: Long = 0L,
        val scanned: Int = 0,
        val facts: Int = 0,
        val unconfirmed: Int = 0,
        val failed: Int = 0,
        val lastError: String = "",
    ) {
        val running: Boolean get() = state == State.RUNNING
    }

    /** 低于这个置信度的事实只进"待确认"，不直接生效。 */
    const val ACTIVATE_THRESHOLD = 0.6

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zhuli-backfill").apply { isDaemon = true }
    }

    @Volatile private var state = State.IDLE
    @Volatile private var fromTs = 0L
    @Volatile private var toTs = 0L
    @Volatile private var cursor = 0L
    @Volatile private var scanned = 0
    @Volatile private var facts = 0
    @Volatile private var unconfirmed = 0
    @Volatile private var failed = 0
    @Volatile private var lastError = ""
    private val stopRequested = AtomicBoolean(false)
    private val pauseRequested = AtomicBoolean(false)

    fun progress() = Progress(state, fromTs, toTs, scanned, facts, unconfirmed, failed, lastError)

    /** 用户选定范围后开始。已在进行中时不重复启动。 */
    fun start(ctx: Context, from: Long, to: Long) {
        if (state == State.RUNNING) return
        val app = ctx.applicationContext
        fromTs = from
        toTs = to
        cursor = 0L
        scanned = 0
        facts = 0
        unconfirmed = 0
        failed = 0
        lastError = ""
        stopRequested.set(false)
        pauseRequested.set(false)
        state = State.RUNNING
        io.execute { runLoop(app) }
    }

    fun pause() {
        if (state == State.RUNNING) pauseRequested.set(true)
    }

    /** 从暂停处继续（游标不重置）。 */
    fun resume(ctx: Context) {
        if (state != State.PAUSED) return
        val app = ctx.applicationContext
        pauseRequested.set(false)
        state = State.RUNNING
        io.execute { runLoop(app) }
    }

    fun cancel() {
        stopRequested.set(true)
    }

    /** 失败后从头重试这一段（已写入的事实会被 ingest 去重，不会重复）。 */
    fun retry(ctx: Context) {
        if (state != State.RUNNING) start(ctx, fromTs, toTs)
    }

    private fun runLoop(app: Context) {
        val db = InboxDatabase(app)
        val repo = MemoryRepo(db)
        repo.onIndexRequested = null // 回填不触发即时索引，结束后统一排队（避免和用户操作抢线程）
        try {
            while (!stopRequested.get() && !pauseRequested.get()) {
                val rows = db.rowsInRangeAfter(fromTs, toTs, cursor, 50)
                if (rows.isEmpty()) {
                    state = State.DONE
                    break
                }
                for (rec in rows) {
                    cursor = rec.id
                    scanned++
                    try {
                        val f = ExtractPipeline.extract(rec)
                        val drafts = MemoryExtractor.extract(
                            rec.copy(category = f.category, deadlineAt = f.deadlineTs, amount = f.amount, note = f.note)
                        )
                        if (drafts.isEmpty()) continue
                        val n = repo.ingest(
                            drafts, SourceType.INBOX, rec.id, System.currentTimeMillis(),
                            activateThreshold = ACTIVATE_THRESHOLD,
                        )
                        facts += n
                        unconfirmed += drafts.count { it.confidence < ACTIVATE_THRESHOLD }
                    } catch (t: Throwable) {
                        failed++
                        lastError = "#${rec.id}: ${t.message ?: t.javaClass.simpleName}"
                        Log.w("zhuli.backfill", "回填失败 ${rec.id}", t)
                    }
                }
            }
            if (pauseRequested.get() && !stopRequested.get()) state = State.PAUSED
            if (stopRequested.get()) state = State.IDLE
        } catch (t: Throwable) {
            state = State.FAILED
            lastError = t.message ?: t.javaClass.simpleName
            Log.w("zhuli.backfill", "回填中断", t)
        } finally {
            try { db.close() } catch (_: Throwable) {}
            MemoryIndexer.requestIndex()
        }
    }
}
