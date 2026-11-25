package com.zhuli.memory

import com.zhuli.core.ZhuliLog as Log
import kotlinx.coroutines.runBlocking
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 事实向量索引器（v16）。
 *
 * 为什么必须有它：v15 的 [MemoryRepo.putVector] 有定义、没有调用方，
 * `hybridSearch` 又只读 `zhuli_fact_vec` —— 结果是「语义检索」永远查不到东西，
 * 但代码看起来一切正常。这类"看起来实现了"比没实现更危险，所以这一版把
 * **写入 → 排队 → 建索引 → 检索** 串成一条真正跑得通的链路，并用测试钉住。
 *
 * 三条边界（都不许越）：
 * 1. **不在通知采集线程跑模型**：ingest 只往 `zhuli_vec_queue` 插一行，推理在
 *    本类自己的单线程上跑；主链路（通知入库）永不等待模型。
 * 2. **索引失败不影响事实**：embed 抛异常只写 attempts/last_error，事实照旧可检索（字面）。
 * 3. **模型不可用就老实降级**：[EmbeddingGate] 在 Gemma 不可用时返回哈希向量，
 *    它的 id 明确写着 `hash-2gram-v1`，检索理由也写「向量相似（字面）」——不冒充语义。
 */
class EmbeddingGate(
    private val semantic: EmbeddingProvider,
    private val lexical: HashEmbeddingProvider = HashEmbeddingProvider(),
) {
    /** 当前真正可用的向量源：真语义优先，否则字符哈希（永远可用）。 */
    suspend fun active(): EmbeddingProvider = if (semantic.isAvailable()) semantic else lexical

    /** 语义模型是否就绪（UI 与自检用）。 */
    suspend fun semanticReady(): Boolean = semantic.isAvailable()

    val semanticId: String get() = semantic.id
    val lexicalId: String get() = lexical.id
}

object MemoryIndexer {

    private const val TAG = "zhuli.memindex"
    private const val BATCH = 8
    private const val MAX_ATTEMPTS = 3

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zhuli-mem-index").apply { isDaemon = true }
    }

    private val running = AtomicBoolean(false)
    private val queuedAgain = AtomicBoolean(false)
    private val indexedTotal = AtomicInteger(0)

    @Volatile private var repoProvider: (() -> MemoryRepo)? = null
    @Volatile private var gate: EmbeddingGate? = null
    @Volatile private var lastError: String = ""

    /** 进程启动装配：注入库与向量源。重复调用只更新这两个引用。 */
    fun init(repo: () -> MemoryRepo, gate: EmbeddingGate) {
        repoProvider = repo
        this.gate = gate
    }

    val initialized: Boolean get() = repoProvider != null && gate != null

    /**
     * ingest / 纠错 / 删除后调用：只排队，不做任何推理。
     * 名字不能叫 notify() —— 那会和 java.lang.Object.notify() 的 JVM 签名 (notify()V) 冲突，
     * Kotlin object 编译时报 "Accidental override"（补丁原始版本就是这么炸的）。
     */
    fun requestIndex() {
        if (!initialized) return
        scheduleDrain()
    }

    /**
     * 统一的任务调度入口。running 为真时只置 queuedAgain 标志，由正在跑的
     * [drainLoop] 收尾时接管；否则抢占 running 并提交一轮。
     */
    private fun scheduleDrain() {
        if (!running.compareAndSet(false, true)) {
            queuedAgain.set(true)
            return
        }
        exec.execute { drainLoop() }
    }

    /** 索引重建（换模型、用户手动修复、升级后补建）：清向量 + 全部重新排队。 */
    fun rebuild() {
        val repo = repoProvider?.invoke() ?: return
        val n = repo.enqueueAllActive(System.currentTimeMillis())
        Log.i(TAG, "重建索引：$n 条事实重新排队")
        requestIndex()
    }

    fun status(): Status {
        val repo = runCatching { repoProvider?.invoke() }.getOrNull()
        return Status(
            pending = repo?.pendingVectorCount() ?: 0,
            waiting = repo?.waitingVectorCount() ?: 0,
            failed = repo?.failedVectorCount() ?: 0,
            running = running.get(),
            facts = repo?.activeCount() ?: 0,
            spaces = repo?.indexedSpaces() ?: emptySet(),
            indexedThisRun = indexedTotal.get(),
            lastError = lastError,
        )
    }

    /**
     * C 修复：四种状态必须能区分 —— 等待处理 / 正在处理 / 永久失败 /
     * 需要手动重建。[stateLabel] 给 UI 直接显示，不让「永久失败」冒充「普通待处理」。
     */
    data class Status(
        val pending: Int,
        val waiting: Int,
        val failed: Int,
        val running: Boolean,
        val facts: Int,
        val spaces: Set<String>,
        val indexedThisRun: Int,
        val lastError: String,
    ) {
        val stateLabel: String get() = when {
            running -> "正在建立索引…"
            failed > 0 && waiting == 0 -> "$failed 条重试失败，需手动重建"
            failed > 0 -> "处理中；$failed 条重试失败需手动重建"
            waiting > 0 -> "$waiting 条等待索引"
            else -> "已同步"
        }
    }

    // ---------- 内部 ----------

    private fun drainLoop() {
        // 一次循环里最多重建一次空间：否则「模型一直失败 → 空间一直不匹配 → 又重建」会空转
        var rebuilt = false
        try {
            do {
                queuedAgain.set(false)
                rebuilt = drainOnce(rebuilt)
                // B 修复：循环体末尾再清一次标志。收尾窗口里到达的 notify() 只会置
                // queuedAgain=true（因为 running 还为 true）；这里把它一并消费掉，
                // 避免「队列还有任务、但循环判定为 false 退出」的丢唤醒竞态。
            } while (queuedAgain.getAndSet(false))
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "索引循环异常", t)
        } finally {
            // 先把 running 交还，再判断收尾窗口是否又来了新通知：
            // 先 release 再检查，才能与 scheduleDrain 的 CAS 形成正确的 happens-before。
            running.set(false)
            if (queuedAgain.getAndSet(false)) scheduleDrain()
        }
    }

    private fun drainOnce(alreadyRebuilt: Boolean): Boolean {
        var rebuilt = alreadyRebuilt
        val repo = repoProvider?.invoke() ?: return rebuilt
        val g = gate ?: return rebuilt
        val provider = runBlocking { g.active() }
        if (!runBlocking { provider.isAvailable() }) {
            lastError = "向量源不可用：${provider.id}"
            return rebuilt
        }
        val space = EmbeddingSpace.of(provider)

        // 换了模型/维度：旧 space 的向量不能拿来比较，整体重建（一轮只做一次，不是每次启动）
        val spaces = repo.indexedSpaces()
        if (!rebuilt && spaces.isNotEmpty() && space.key !in spaces) {
            Log.i(TAG, "向量空间变更 ${spaces.joinToString()} → ${space.key}，重建索引")
            repo.enqueueAllActive(System.currentTimeMillis())
            rebuilt = true
        } else if (spaces.isEmpty() && repo.pendingVectorCount() == 0 && repo.activeCount() > 0) {
            // 存量补建：v15 库里一条向量都没有（老版本从没建过），首次打开时补上
            repo.enqueueAllActive(System.currentTimeMillis())
        }

        var hadFailure = false
        while (true) {
            val ids = repo.pendingVectorFacts(BATCH)
            if (ids.isEmpty()) break
            val pairs = ids.mapNotNull { id -> repo.vectorText(id)?.let { id to it } }
            // 取不到文本（被删/被替代）：队列行已失效，直接清掉
            for (id in ids - pairs.map { it.first }.toSet()) repo.markVectorIndexed(id)
            if (pairs.isEmpty()) continue

            val vecs = try {
                runBlocking { provider.embed(pairs.map { it.second }) }
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                lastError = msg
                hadFailure = true
                Log.w(TAG, "embed 失败（${pairs.size} 条），保留待重试", t)
                for ((id, _) in pairs) repo.markVectorFailed(id, msg, System.currentTimeMillis())
                // 整批失败说明是模型侧问题（OOM/模型没装/磁盘），这一轮就停，别空转
                break
            }
            var wrote = 0
            for ((i, pair) in pairs.withIndex()) {
                val v = vecs.getOrNull(i)
                if (v == null || v.isEmpty()) {
                    repo.markVectorFailed(pair.first, "空向量", System.currentTimeMillis())
                    lastError = "空向量"
                    hadFailure = true
                    continue
                }
                repo.putVector(pair.first, space, v)
                repo.markVectorIndexed(pair.first)
                wrote++
            }
            indexedTotal.addAndGet(wrote)
            if (wrote == 0) break
        }
        // 只有整轮没有任何失败时才清 lastError；否则 UI 永远看不到「最近一次失败」。
        if (!hadFailure) lastError = ""
        return rebuilt
    }

    /** 单测用：把队列同步跑完（不启线程），返回本轮写入的向量数。 */
    fun drainBlocking(): Int {
        if (!initialized) return 0
        val before = indexedTotal.get()
        drainOnce(alreadyRebuilt = false)
        return indexedTotal.get() - before
    }

    /** 单测用：重置累计计数。 */
    fun resetCounters() {
        indexedTotal.set(0)
        lastError = ""
    }
}
