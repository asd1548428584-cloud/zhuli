package com.zhuli.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.zhuli.core.InboxDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 记忆核心的**真实 SQLite** 测试（P0 第一条 + P1 第五条）。
 *
 * 为什么不能只测纯函数：记忆的问题几乎全部出在"写进去"和"查出来"之间 ——
 * 重复插入、superseded_by 留 0、删了事实向量还在、换了模型空间串了。
 * 这些只有真库跑得出来，所以这里用 Robolectric + 真 SQLiteOpenHelper。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.zhuli.testutil.PlainTestApp::class)
class MemorySqliteTest {

    private lateinit var ctx: Context
    private lateinit var db: InboxDatabase
    private lateinit var repo: MemoryRepo
    private val now = 1_700_000_000_000L

    private fun draft(value: String, conf: Double = 0.9, subject: String = "我", pred: String = "住址") =
        FactDraft(subject, pred, value, FactKind.ADDRESS, conf, "来源原文：$value")

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase("zhuli_inbox.db")
        db = InboxDatabase(ctx)
        repo = MemoryRepo(db)
        // 单测里不让后台线程自己跑：索引由 drainBlocking() 显式驱动，结果才可断言
        repo.onIndexRequested = null
    }

    @After
    fun tearDown() {
        try { db.close() } catch (_: Throwable) {}
        ctx.deleteDatabase("zhuli_inbox.db")
    }

    // ---------------- P1：记忆数据正确性 ----------------

    @Test
    fun `同主体谓词值的重复通知不会重复插入`() {
        val n1 = repo.ingest(listOf(draft("朝阳区建国路 88 号")), SourceType.INBOX, 1L, now)
        val n2 = repo.ingest(listOf(draft("朝阳区建国路 88 号")), SourceType.INBOX, 2L, now + 1_000)
        assertEquals(1, n1)
        assertEquals("重复通知写成了两条事实", 0, n2)
        assertEquals(1, repo.activeOf("我", "住址").size)
    }

    @Test
    fun `同槽位冲突时旧事实被取代且 superseded_by 指向真实新事实`() {
        repo.ingest(listOf(draft("老地址 A", conf = 0.9)), SourceType.INBOX, 1L, now)
        val oldId = repo.activeOf("我", "住址").single().id
        repo.ingest(listOf(draft("新地址 B", conf = 0.95)), SourceType.INBOX, 2L, now + 1_000)

        val active = repo.activeOf("我", "住址")
        assertEquals("同一槽位应只剩一条当前有效事实", 1, active.size)
        assertEquals("新地址 B", active.single().value)
        val newId = active.single().id

        val old = repo.byId(oldId)!!
        assertEquals(FactStatus.SUPERSEDED, old.status)
        assertEquals("superseded_by 必须是真实新事实 ID，不能是 0", newId, old.supersededBy)
        assertTrue("被取代的事实应记录失效时间", old.validTo > 0)
        // 历史完整可查
        assertEquals(2, repo.history("我", "住址").size)
    }

    @Test
    fun `低置信度推断不能覆盖高置信度事实`() {
        repo.ingest(listOf(draft("用户亲口说的地址", conf = 0.95)), SourceType.USER, 1L, now)
        repo.ingest(listOf(draft("模型猜的地址", conf = 0.5)), SourceType.INFERRED, 2L, now + 1_000)
        val active = repo.activeOf("我", "住址")
        assertEquals(1, active.size)
        assertEquals("用户亲口说的地址", active.single().value)
    }

    @Test
    fun `用户纠错保留完整历史`() {
        repo.ingest(listOf(draft("错的地址", conf = 0.9)), SourceType.INBOX, 1L, now)
        val oldId = repo.activeOf("我", "住址").single().id
        val newId = repo.correct(oldId, "对的地址", now + 5_000)!!

        val h = repo.history("我", "住址")
        assertEquals(2, h.size)
        val old = h.first { it.id == oldId }
        assertEquals(FactStatus.SUPERSEDED, old.status)
        assertEquals(newId, old.supersededBy)
        assertEquals(SourceType.USER, h.first { it.id == newId }.sourceType)
        assertEquals(1.0, h.first { it.id == newId }.confidence, 0.001)
    }

    @Test
    fun `删除后不再作为当前事实参与检索`() {
        repo.ingest(listOf(draft("要被删掉的地址")), SourceType.INBOX, 1L, now)
        val id = repo.activeOf("我", "住址").single().id
        repo.deleteFact(id, now + 1_000)

        assertEquals(FactStatus.DELETED, repo.byId(id)!!.status)
        assertTrue(repo.search("地址", now + 2_000).none { it.fact.id == id })
        assertTrue(repo.activeOf("我", "住址").isEmpty())
    }

    @Test
    fun `历史回填的低置信度事实进待确认 既不检索也不覆盖现有记忆`() {
        repo.ingest(listOf(draft("用户说过的高置信地址", conf = 0.95)), SourceType.INBOX, 1L, now)
        // 回填：同槽位的新值置信度只有 0.4 → 只存成待确认
        val n = repo.ingest(
            listOf(FactDraft("我", "住址", "模型猜的地址", FactKind.ADDRESS, 0.4, "历史通知")),
            SourceType.INBOX, 2L, now + 1_000, activateThreshold = MemoryBackfill.ACTIVATE_THRESHOLD,
        )
        assertEquals(1, n)
        assertEquals(1, repo.unconfirmed().size)
        // 现有事实没有被覆盖，检索里也不会冒出待确认的那条
        assertEquals("用户说过的高置信地址", repo.activeOf("我", "住址").single().value)
        assertTrue(repo.search("模型猜的地址", now + 2_000).none { it.fact.value.contains("模型猜的") })
        // 待确认不建向量：队列里只有第一条 ACTIVE 事实那一条，待确认那条没有入队
        val beforeConfirm = repo.pendingVectorCount()
        assertEquals("只有高置信那条入队，待确认的不该入队", 1, beforeConfirm)

        // 用户确认后才生效，并进入索引队列
        val id = repo.unconfirmed().single().id
        assertTrue(repo.confirmFact(id, now + 3_000))
        assertEquals(2, repo.activeOf("我", "住址").size)
        assertEquals("确认后新增一条入队", beforeConfirm + 1, repo.pendingVectorCount())
    }

    // ---------------- P0：向量索引闭环 ----------------

    private fun gate(semanticReady: Boolean = false, dim: Int = 768): EmbeddingGate {
        val semantic = object : EmbeddingProvider {
            override val id = "gemma-embedding-v1"
            override val dim = dim
            override val isSemantic = true
            override suspend fun isAvailable() = semanticReady
            override suspend fun embed(texts: List<String>) = texts.map { FloatArray(dim) { 1f / dim } }
        }
        return EmbeddingGate(semantic)
    }

    @Test
    fun `事实写入后异步建立向量索引 并能用不同措辞找回`() = runBlocking {
        MemoryIndexer.init({ repo }, gate(semanticReady = false))
        MemoryIndexer.resetCounters()

        repo.ingest(
            listOf(
                FactDraft("顺丰", "取件码", "6825 丰巢柜 3 号", FactKind.EXPRESS, 0.95, "丰巢取件通知"),
                FactDraft("我", "住址", "北京市朝阳区建国路 88 号", FactKind.ADDRESS, 0.9, "地址短信"),
            ),
            SourceType.INBOX, 7L, now,
        )
        // 写入时只是排队，不许同步跑模型
        assertEquals("事实写入不应同步建索引", 2, repo.pendingVectorCount())
        assertEquals(0, repo.vectorCount(EmbeddingSpace("hash-2gram-v1", 256)))

        val indexed = MemoryIndexer.drainBlocking()
        assertEquals(2, indexed)
        assertEquals(0, repo.pendingVectorCount())
        assertEquals(2, repo.vectorCount(EmbeddingSpace("hash-2gram-v1", 256)))

        // 原词命中
        val byCode = repo.hybridSearch("取件码", HashEmbeddingProvider(), now + 1_000)
        assertTrue(byCode.any { it.fact.value.contains("6825") })
        // 不同措辞（词序不同、只记得片段）也要找得到 —— 这是向量的意义
        val byPhrase = repo.hybridSearch("丰巢柜子里的件", HashEmbeddingProvider(), now + 1_000)
        assertTrue("换措辞没找回事实：$byPhrase", byPhrase.any { it.fact.value.contains("6825") })
    }

    @Test
    fun `语义模型可用时 同义改写靠向量命中而不是字面`() = runBlocking {
        // 可解释的假语义模型：空调/制冷/维修 → 第 1 维；师傅/上门/来 → 第 2 维。
        // 目的不是测模型好坏，而是证明「字面 0 命中时向量这条路真的通」。
        val fake = object : EmbeddingProvider {
            override val id = "fake-sem-v1"
            override val dim = 4
            override val isSemantic = true
            override suspend fun isAvailable() = true
            override suspend fun embed(texts: List<String>) = texts.map { t ->
                var a = 0f
                var b = 0f
                if (t.contains("空调") || t.contains("制冷") || t.contains("维修")) a = 1f
                if (t.contains("师傅") || t.contains("上门") || t.contains("来")) b = 1f
                val n = kotlin.math.sqrt((a * a + b * b).toDouble()).toFloat().takeIf { it > 0f } ?: 1f
                floatArrayOf(a / n, b / n, 0f, 0f)
            }
        }
        MemoryIndexer.init({ repo }, EmbeddingGate(fake))
        repo.ingest(
            listOf(FactDraft("张师傅", "上门", "空调维修", FactKind.OTHER, 0.9, "物业通知")),
            SourceType.INBOX, 1L, now,
        )
        MemoryIndexer.drainBlocking()

        val hits = repo.hybridSearch("制冷的人什么时候来", fake, now + 1_000)
        assertTrue("同义改写没找回事实：$hits", hits.any { it.fact.value.contains("空调维修") })
        val hit = hits.first { it.fact.value.contains("空调维修") }
        assertTrue("这条命中应来自语义分支，实际：${hit.reasons}", hit.reasons.any { it.contains("语义") })
    }

    @Test
    fun `删除或纠错后旧向量不再参与检索`() = runBlocking {
        MemoryIndexer.init({ repo }, gate())
        repo.ingest(listOf(draft("旧地址 一号院")), SourceType.INBOX, 1L, now)
        MemoryIndexer.drainBlocking()
        val oldId = repo.activeOf("我", "住址").single().id
        assertEquals(1, repo.vectorCount(EmbeddingSpace("hash-2gram-v1", 256)))

        repo.correct(oldId, "新地址 二号院", now + 1_000)
        // 旧事实的向量必须被清掉；新事实排进队列等建索引
        assertEquals("纠错后旧向量还在", 0, repo.vectorCount(EmbeddingSpace("hash-2gram-v1", 256)))
        assertEquals(1, repo.pendingVectorCount())

        MemoryIndexer.drainBlocking()
        val hits = repo.hybridSearch("旧地址 一号院", HashEmbeddingProvider(), now + 2_000)
        assertTrue("被纠错的旧值仍被检索出来", hits.none { it.fact.value.contains("旧地址") })
    }

    @Test
    fun `语义模型不可用时明确降级为哈希 不虚报语义`() = runBlocking {
        val g = gate(semanticReady = false)
        val active = g.active()
        assertFalse(active.isSemantic)
        assertTrue("降级后必须是哈希实现", active.id.contains("hash"))

        MemoryIndexer.init({ repo }, g)
        repo.ingest(listOf(draft("会说话的鸟")), SourceType.INBOX, 1L, now)
        MemoryIndexer.drainBlocking()
        val hits = repo.hybridSearch("鸟", active, now + 1_000)
        assertTrue(hits.isNotEmpty())
        // 理由必须写「字面」，不能把哈希说成语义
        val reasonText = hits.flatMap { it.reasons }.joinToString(",")
        assertFalse("哈希向量冒充了语义：$reasonText", reasonText.contains("语义相似"))
    }

    @Test
    fun `换模型后旧空间的向量不会被拿来比较`() = runBlocking {
        MemoryIndexer.init({ repo }, gate(semanticReady = false))
        repo.ingest(listOf(draft("朝阳区某小区 3 号楼")), SourceType.INBOX, 1L, now)
        MemoryIndexer.drainBlocking()
        assertEquals(setOf("hash-2gram-v1|256"), repo.indexedSpaces())

        // 换成"语义模型可用"：新的 space 是 gemma|768，旧向量必须重建而不是混用
        MemoryIndexer.init({ repo }, gate(semanticReady = true, dim = 768))
        MemoryIndexer.drainBlocking()
        assertEquals("旧 space 的向量没被清掉", setOf("gemma-embedding-v1|768"), repo.indexedSpaces())
        // 维度不同不能比较：用哈希 provider 查时不应命中 gemma 空间
        assertEquals(0, repo.vectorCount(EmbeddingSpace("hash-2gram-v1", 256)))
    }

    @Test
    fun `索引失败不影响事实本身 并保留可重试记录`() = runBlocking {
        val broken = object : EmbeddingProvider {
            override val id = "broken-v1"
            override val dim = 8
            override suspend fun isAvailable() = true
            override suspend fun embed(texts: List<String>): List<FloatArray> = error("模型 OOM")
        }
        MemoryIndexer.init({ repo }, EmbeddingGate(broken))
        repo.ingest(listOf(draft("照样要能查到")), SourceType.INBOX, 1L, now)

        val indexed = MemoryIndexer.drainBlocking()
        assertEquals(0, indexed)
        // 事实还在，字面检索照样能用
        assertEquals(1, repo.activeCount())
        assertTrue(repo.search("照样", now + 1_000).isNotEmpty())
        // 队列保留（可重试），并记了失败原因
        assertEquals(1, repo.pendingVectorCount())
        assertTrue(MemoryIndexer.status().lastError.contains("OOM"))
    }

    @Test
    fun `反复失败三次的项能被手动重建重新排队`() = runBlocking {
        // 用可控 provider：第一次永远失败，切到好的 provider 后重建应能救回
        var shouldFail = true
        val flaky = object : EmbeddingProvider {
            override val id = "flaky-v1"; override val dim = 8
            override val isSemantic = true
            override suspend fun isAvailable() = true
            override suspend fun embed(texts: List<String>): List<FloatArray> =
                if (shouldFail) error("模型 OOM") else texts.map { FloatArray(8) { 0.5f } }
        }
        MemoryIndexer.init({ repo }, EmbeddingGate(flaky))
        repo.ingest(listOf(draft("我要记住的住处")), SourceType.INBOX, 1L, now)
        // 连跑三次把 attempts 打到 3
        repeat(3) { MemoryIndexer.drainBlocking() }
        assertEquals("失败三次后应移出重试队列", 0, repo.waitingVectorCount())
        assertEquals(1, repo.failedVectorCount())
        assertTrue("永久失败项不能再被 pendingVectorFacts 取出", repo.pendingVectorFacts(10).isEmpty())

        // 手动重建：必须把 attempts 清零，让这条重新进入队列并被好的模型建上
        shouldFail = false
        repo.enqueueAllActive(now + 1_000)
        assertEquals("重建后失败项应重新变为可等待", 1, repo.waitingVectorCount())
        assertEquals(0, repo.failedVectorCount())
        val indexed = MemoryIndexer.drainBlocking()
        assertEquals("重建后应成功写入向量", 1, indexed)
        assertEquals(1, repo.vectorCount(EmbeddingSpace("flaky-v1", 8)))
    }

    @Test
    fun `索引状态能区分等待 处理中 失败 需重建`() = runBlocking {
        MemoryIndexer.init({ repo }, gate(semanticReady = false))
        repo.ingest(listOf(draft("状态测试的事实")), SourceType.INBOX, 1L, now)
        val waiting = MemoryIndexer.status()
        assertEquals(1, waiting.waiting)
        assertEquals(0, waiting.failed)
        assertTrue("有待处理项时文案应写等待：${waiting.stateLabel}", waiting.stateLabel.contains("等待"))

        // 打到失败后文案必须提示「需要手动重建」，不能继续冒充普通待处理
        MemoryIndexer.init({ repo }, EmbeddingGate(object : EmbeddingProvider {
            override val id = "dead-v1"; override val dim = 8
            override suspend fun isAvailable() = true
            override suspend fun embed(texts: List<String>) = error("dead")
        }))
        repeat(3) { MemoryIndexer.drainBlocking() }
        val failed = MemoryIndexer.status()
        assertEquals(1, failed.failed)
        assertEquals(0, failed.waiting)
        assertTrue("永久失败时文案必须提示重建：${failed.stateLabel}", failed.stateLabel.contains("重建"))
    }

    // ---------------- 数据库迁移 ----------------

    @Test
    fun `v15 库升级到 v16 是纯增量 存量事实与任务不丢`() {
        // 造一个 v15 库：只建 v15 里存在的表，把 version 写回 15
        db.close()
        ctx.deleteDatabase("zhuli_inbox.db")
        val raw = ctx.openOrCreateDatabase("zhuli_inbox.db", Context.MODE_PRIVATE, null)
        raw.execSQL(
            """
            CREATE TABLE zhuli_fact (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                subject TEXT NOT NULL, predicate TEXT NOT NULL, value TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'OTHER', confidence REAL NOT NULL DEFAULT 0.6,
                source_type TEXT NOT NULL DEFAULT 'INBOX', source_id INTEGER NOT NULL DEFAULT 0,
                source_text TEXT NOT NULL DEFAULT '', observed_at INTEGER NOT NULL DEFAULT 0,
                valid_from INTEGER NOT NULL DEFAULT 0, valid_to INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'ACTIVE', superseded_by INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )
            """
        )
        raw.execSQL(
            """
            CREATE TABLE zhuli_agent_task (
                id TEXT PRIMARY KEY, goal TEXT NOT NULL, status TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                finished_at INTEGER NOT NULL DEFAULT 0, result TEXT NOT NULL DEFAULT '',
                error TEXT NOT NULL DEFAULT '', pending_call TEXT NOT NULL DEFAULT '',
                pending_risk TEXT NOT NULL DEFAULT '', dedupe_key TEXT NOT NULL DEFAULT '',
                origin TEXT NOT NULL DEFAULT 'manual', step_count INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        raw.execSQL(
            "INSERT INTO zhuli_fact(subject,predicate,value,created_at,updated_at) VALUES('我','住址','老库里的地址',1,1)"
        )
        raw.execSQL(
            "INSERT INTO zhuli_agent_task(id,goal,status,created_at,updated_at,dedupe_key) VALUES('t1','旧任务','DONE',1,1,'t1')"
        )
        raw.version = 15
        raw.close()

        // 用当前版本打开 → 触发 v15→v16
        val upgraded = InboxDatabase(ctx)
        val repo2 = MemoryRepo(upgraded)
        assertEquals("存量事实丢了", 1, repo2.activeCount())
        assertEquals("老库里的地址", repo2.active(10).single().value)

        val store = com.zhuli.agent.SqlAgentStore(upgraded)
        val task = store.get("t1")
        assertNotNull("存量任务丢了", task)
        assertEquals(com.zhuli.agent.TaskStatus.DONE, task!!.status)
        assertFalse("新加的 local_only 应默认 false", task.localOnly)

        // 新表必须存在（onUpgrade 建了、onOpen 也会补）
        assertTrue(tableExists(upgraded, "zhuli_vec_queue"))
        assertTrue(tableExists(upgraded, "zhuli_tool_exec"))
        // v15 的存量事实应能在索引器首次运行时补建向量（由 pending 队列承担）
        repo2.enqueueAllActive(now)
        assertTrue(repo2.pendingVectorCount() > 0)
        upgraded.close()
    }

    private fun tableExists(db: InboxDatabase, name: String): Boolean =
        db.readableDatabase.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)
        ).use { it.moveToFirst() }
}
