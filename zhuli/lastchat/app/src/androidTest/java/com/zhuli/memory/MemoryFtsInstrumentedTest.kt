package com.zhuli.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * **C1-A 门槛测试**：FTS4 在真实 Android `SQLiteDatabase` 上能不能用、触发器能不能维护它。
 *
 * 这是 `docs/批次C0.2_记忆系统架构设计最终版.md` 第 14.1 节的七项验证的**权威版本**
 * （Robolectric 版本只作快速预检，不能替代这里的结果）。
 *
 * 三条刻意的约束：
 *
 * 1. **不碰任何生产代码**：没有 `MemoryStore`、没有 `MemorySchema`，全部是手写 SQL。
 *    这样"FTS 能力"与"存储层实现"完全解耦 —— 测试通过就能证明后续实现有路可走，
 *    测试失败也不会被存储层的 bug 混淆。
 * 2. **不碰用户数据库**：每个测试在 `cacheDir` 下自建一个临时库，测完连同 `-journal` 一起删。
 * 3. **每个测试自带全部前置步骤**（建表 → 建 FTS → 建触发器 → 造数据），
 *    所以能单独跑、不依赖执行顺序，失败时也能独立看出卡在哪一步。
 *
 * 探针表只有触发器真正读写的三列。这不是"简化设计"，而是**故意把变量减到一个**：
 * 40 列的真实 schema 要到 C1-B 才存在，而这里要回答的问题只有
 * "触发器 + 虚拟表在 `SQLiteDatabase` 上成不成立"。
 */
@RunWith(AndroidJUnit4::class)
class MemoryFtsInstrumentedTest {

    private lateinit var ctx: Context
    private val created = mutableListOf<File>()

    @Before
    fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        created.forEach { f ->
            runCatching { File(f.path + "-journal").delete() }
            runCatching { File(f.path + "-wal").delete() }
            runCatching { File(f.path + "-shm").delete() }
            if (f.exists() && !f.delete()) println("  [warn] 临时库未删除: ${f.path}")
        }
        created.clear()
    }

    // ---------------------------------------------------------------- 基础设施

    private fun openProbe(name: String): SQLiteDatabase {
        val f = File(ctx.cacheDir, "c1a_fts_${name}_${System.nanoTime()}.db")
        created += f
        println("  临时库: ${f.path}")
        return SQLiteDatabase.openOrCreateDatabase(f, null)
    }

    /** 探针表：只含触发器真正读写的三列（见类注释）。 */
    private fun createItemTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE zhuli_memory_item (
              id          INTEGER PRIMARY KEY AUTOINCREMENT,
              search_text TEXT    NOT NULL DEFAULT '',
              status      TEXT    NOT NULL DEFAULT 'ACTIVE'
            )
            """.trimIndent(),
        )
    }

    private fun createFts(db: SQLiteDatabase) {
        db.execSQL("CREATE VIRTUAL TABLE zhuli_memory_fts USING fts4(search_text, tokenize=simple)")
    }

    private val triggerAi = """
        CREATE TRIGGER zhuli_memory_fts_ai
        AFTER INSERT ON zhuli_memory_item BEGIN
          INSERT INTO zhuli_memory_fts(rowid, search_text) VALUES (new.id, new.search_text);
        END
    """.trimIndent()

    private val triggerAu = """
        CREATE TRIGGER zhuli_memory_fts_au
        AFTER UPDATE OF search_text, status ON zhuli_memory_item BEGIN
          DELETE FROM zhuli_memory_fts WHERE rowid = old.id;
          INSERT INTO zhuli_memory_fts(rowid, search_text)
            SELECT new.id, new.search_text WHERE new.status <> 'DELETED';
        END
    """.trimIndent()

    private val triggerAd = """
        CREATE TRIGGER zhuli_memory_fts_ad
        AFTER DELETE ON zhuli_memory_item BEGIN
          DELETE FROM zhuli_memory_fts WHERE rowid = old.id;
        END
    """.trimIndent()

    private fun describe(t: Throwable): String =
        "${t.javaClass.name}: ${t.message}"

    /**
     * **宽松**建触发器：不抛，把每条失败原因收集起来。
     *
     * 为什么宽松：如果这里直接断言失败，步骤 3–7 就都会停在 setup 上，
     * 报告里只剩一句"触发器建不出来"，看不出"触发器被拒"与"建得出来但写不进"的区别。
     * 宽松之后，步骤 3–7 会各自跑到自己的断言处失败，失败信息里带上这里的原始异常。
     */
    private fun createTriggersLenient(db: SQLiteDatabase): List<String> {
        val failures = mutableListOf<String>()
        listOf("ai" to triggerAi, "au" to triggerAu, "ad" to triggerAd).forEach { (name, ddl) ->
            try {
                db.execSQL(ddl)
                println("  [OK]   trigger $name 创建成功")
            } catch (t: Throwable) {
                val d = describe(t)
                failures += "trigger $name -> $d"
                println("  [FAIL] trigger $name -> $d")
            }
        }
        return failures
    }

    private fun createTriggersStrict(db: SQLiteDatabase) {
        val failures = createTriggersLenient(db)
        assertTrue(
            "三个触发器未能全部创建（这是 C1-A 的核心结论之一）：\n" +
                failures.joinToString("\n") { "  - $it" } +
                "\n提示：若消息含 'unsafe use of virtual table'，说明该环境禁止在触发器中写虚拟表，" +
                "应改走 APP_SYNC。",
            failures.isEmpty(),
        )
    }

    /** 建表 + FTS + 触发器（宽松），返回触发器失败列表供断言信息里带上根因。 */
    private fun prepare(db: SQLiteDatabase): List<String> {
        createItemTable(db)
        createFts(db)
        return createTriggersLenient(db)
    }

    private fun insertItem(db: SQLiteDatabase, searchText: String, status: String = "ACTIVE"): Long {
        db.execSQL(
            "INSERT INTO zhuli_memory_item(search_text, status) VALUES (?, ?)",
            arrayOf<Any>(searchText, status),
        )
        return count(db, "SELECT MAX(id) FROM zhuli_memory_item")
    }

    private fun count(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { c ->
            if (!c.moveToFirst()) error("查询无结果: $sql")
            c.getLong(0)
        }

    private fun matchRowIds(db: SQLiteDatabase, expr: String): List<Long> =
        db.rawQuery("SELECT rowid FROM zhuli_memory_fts WHERE zhuli_memory_fts MATCH ?", arrayOf(expr))
            .use { c ->
                val out = mutableListOf<Long>()
                while (c.moveToNext()) out += c.getLong(0)
                out
            }

    /** FTS 总行数（不经 MATCH，直接看索引里还有几条）。 */
    private fun ftsRows(db: SQLiteDatabase): Long =
        count(db, "SELECT COUNT(*) FROM zhuli_memory_fts")

    /**
     * `_content` 影子表行数。
     *
     * 为什么要和 [ftsRows] 对比：`SELECT COUNT(*) FROM <fts>` 走的是索引/段结构，
     * `_content` 是原文副本。两者不一致说明索引与内容脱节 —— 这类问题不会报错，
     * 只会让"搜得到但取不出"或"删了还能搜到"。
     */
    private fun ftsContentRows(db: SQLiteDatabase): Long =
        count(db, "SELECT COUNT(*) FROM zhuli_memory_fts_content")

    /** 孤儿索引行：FTS 有、item 没有。 */
    private fun ftsOrphans(db: SQLiteDatabase): Long =
        count(
            db,
            "SELECT COUNT(*) FROM zhuli_memory_fts WHERE rowid NOT IN (SELECT id FROM zhuli_memory_item)",
        )

    /** 同一 rowid 在 FTS 里出现多次（重复命中）。 */
    private fun ftsDuplicatedRowIds(db: SQLiteDatabase): Long =
        count(db, "SELECT COUNT(*) FROM (SELECT rowid FROM zhuli_memory_fts GROUP BY rowid HAVING COUNT(*) > 1)")

    private fun banner(step: String) {
        println("\n===== $step =====")
        println("  设备: ${Build.MANUFACTURER} ${Build.MODEL} | API ${Build.VERSION.SDK_INT} | ${Build.VERSION.RELEASE}")
    }

    // ---------------------------------------------------------------- 0. 环境诊断

    /**
     * 环境基线。这一条**不做业务断言**，只记录：拿不到版本号本身就是重要信息，
     * 不该让整轮测试因为它变红。
     */
    @Test
    fun step0_environmentDiagnostics() {
        banner("step0 环境诊断")
        val db = openProbe("diag")
        try {
            println("  SQLite 版本: " + q1(db, "SELECT sqlite_version()"))
            println("  ENABLE_FTS3 编译项: " + q1OrNull(db, "SELECT sqlite_compileoption_used('ENABLE_FTS3')"))
            println("  ENABLE_FTS4 编译项: " + q1OrNull(db, "SELECT sqlite_compileoption_used('ENABLE_FTS4')"))
            println("  ENABLE_FTS5 编译项: " + q1OrNull(db, "SELECT sqlite_compileoption_used('ENABLE_FTS5')"))
            println("  journal_mode: " + q1(db, "PRAGMA journal_mode"))
            println("  secure_delete: " + q1(db, "PRAGMA secure_delete"))
            // trusted_schema 是 3.31 才有的 pragma；老版本读它会报错，这本身就是证据
            println("  trusted_schema: " + q1OrNull(db, "PRAGMA trusted_schema"))
            println("  defensive(不可读时为 null): " + q1OrNull(db, "PRAGMA defensive"))
            println("  库文件: ${db.path}")
        } finally {
            db.close()
        }
    }

    private fun q1(db: SQLiteDatabase, sql: String): String =
        db.rawQuery(sql, null).use { c ->
            if (!c.moveToFirst()) error("查询无结果: $sql")
            c.getString(0) ?: "NULL"
        }

    private fun q1OrNull(db: SQLiteDatabase, sql: String): String =
        try {
            q1(db, sql)
        } catch (t: Throwable) {
            "读取失败(${describe(t)})"
        }

    // ---------------------------------------------------------------- ① 建表

    @Test
    fun step1_createVirtualTableAndShadowTables() {
        banner("step1 创建 FTS4 虚拟表 + 影子表")
        val db = openProbe("step1")
        try {
            createItemTable(db)
            createFts(db)

            val names = db.rawQuery(
                "SELECT name, type FROM sqlite_master WHERE name LIKE 'zhuli_memory_fts%' ORDER BY name",
                null,
            ).use { c ->
                val out = mutableListOf<Pair<String, String>>()
                while (c.moveToNext()) out += c.getString(0) to c.getString(1)
                out
            }
            println("  sqlite_master 命中: $names")

            val nameSet = names.map { it.first }.toSet()
            val expected = setOf(
                "zhuli_memory_fts",
                "zhuli_memory_fts_content",
                "zhuli_memory_fts_segments",
                "zhuli_memory_fts_segdir",
                "zhuli_memory_fts_docsize",
                "zhuli_memory_fts_stat",
            )
            assertEquals("FTS4 的影子表集合与预期不符（实际 $nameSet）", expected, nameSet)
            assertTrue(
                "zhuli_memory_fts 应是虚拟表（type=table）",
                names.any { it.first == "zhuli_memory_fts" && it.second == "table" },
            )

            // 能写能读，才算真的建起来了
            db.execSQL("INSERT INTO zhuli_memory_fts(rowid, search_text) VALUES (1, '会议 议提 提醒')")
            assertEquals("直接写入虚拟表后应能 MATCH 到", listOf(1L), matchRowIds(db, "\"会议\" \"提醒\""))
        } finally {
            db.close()
        }
    }

    // ---------------------------------------------------------------- ② 触发器

    @Test
    fun step2_createThreeTriggers() {
        banner("step2 创建三个触发器")
        val db = openProbe("step2")
        try {
            createItemTable(db)
            createFts(db)
            // 严格版：任一失败即失败，消息里带三条的完整原因
            createTriggersStrict(db)

            val triggers = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name",
                null,
            ).use { c ->
                val out = mutableListOf<String>()
                while (c.moveToNext()) out += c.getString(0)
                out
            }
            println("  sqlite_master 里的触发器: $triggers")
            assertEquals(
                "应有且只有三个触发器",
                listOf("zhuli_memory_fts_ad", "zhuli_memory_fts_ai", "zhuli_memory_fts_au"),
                triggers,
            )
        } finally {
            db.close()
        }
    }

    // ---------------------------------------------------------------- ③ 插入

    @Test
    fun step3_insertThenMatch() {
        banner("step3 INSERT 后能否 MATCH")
        val db = openProbe("step3")
        try {
            val trigFails = prepare(db)
            val id = insertItem(db, "会议 议提 提醒")

            val hits = matchRowIds(db, "\"会议\" \"提醒\"")
            println("  item id=$id, 命中=$hits, fts 行数=${ftsRows(db)}, 触发器失败=$trigFails")
            assertEquals("行必须写进原表", 1L, count(db, "SELECT COUNT(*) FROM zhuli_memory_item"))
            assertTrue(
                "INSERT 后 FTS 应命中该行；触发器失败记录=$trigFails",
                hits == listOf(id),
            )
            assertEquals("FTS 应恰好一行，不能多插", 1L, ftsRows(db))
            assertEquals("content 影子表应与索引行数一致", 1L, ftsContentRows(db))
        } finally {
            db.close()
        }
    }

    // ---------------------------------------------------------------- ④ 更新

    @Test
    fun step4_updateSearchTextResyncsIndex() {
        banner("step4 UPDATE search_text 后旧词消失、新词命中")
        val db = openProbe("step4")
        try {
            val trigFails = prepare(db)
            val id = insertItem(db, "会议 议提 提醒")
            assertEquals("前置：初始应命中", listOf(id), matchRowIds(db, "\"会议\" \"提醒\""))

            db.execSQL(
                "UPDATE zhuli_memory_item SET search_text = ? WHERE id = ?",
                arrayOf<Any>("快递 递到 到了", id),
            )

            val oldHits = matchRowIds(db, "\"会议\"")
            val newHits = matchRowIds(db, "\"快递\"")
            println("  旧词命中=$oldHits, 新词命中=$newHits, fts 行数=${ftsRows(db)}, 触发器失败=$trigFails")
            assertEquals("旧词必须不再命中（触发器没删旧行）", emptyList<Long>(), oldHits)
            assertEquals("新词必须命中对应行", listOf(id), newHits)
            assertEquals("FTS 行数应仍为 1（删旧 + 插新）", 1L, ftsRows(db))
            assertEquals("不得出现重复 rowid", 0L, ftsDuplicatedRowIds(db))
        } finally {
            db.close()
        }
    }

    // ---------------------------------------------------------------- ⑤ 软删

    @Test
    fun step5_softDeleteRemovesFromIndex() {
        banner("step5 status=DELETED 后不再命中")
        val db = openProbe("step5")
        try {
            val trigFails = prepare(db)
            val id = insertItem(db, "快递 递到 到了")
            assertEquals("前置：应命中", listOf(id), matchRowIds(db, "\"快递\""))

            db.execSQL("UPDATE zhuli_memory_item SET status = 'DELETED' WHERE id = ?", arrayOf<Any>(id))

            // 关键：验 MATCH 结果，不是只看原表 status
            val hits = matchRowIds(db, "\"快递\"")
            println("  软删后命中=$hits, fts 行数=${ftsRows(db)}, 原表 status=${q1(db, "SELECT status FROM zhuli_memory_item WHERE id=$id")}")
            assertEquals("软删后 MATCH 必须 0 命中；触发器失败记录=$trigFails", emptyList<Long>(), hits)
            assertEquals("软删后 FTS 行数应为 0", 0L, ftsRows(db))
            assertEquals("软删后 content 影子表也应为 0", 0L, ftsContentRows(db))
            // 原表数据仍在（软删不是物理删）
            assertEquals("原表行必须保留", 1L, count(db, "SELECT COUNT(*) FROM zhuli_memory_item"))
        } finally {
            db.close()
        }
    }

    // ---------------------------------------------------------------- ⑥ 恢复

    @Test
    fun step6_restoreReindexesWithoutDuplicates() {
        banner("step6 恢复 ACTIVE 后重新命中，且不重复")
        val db = openProbe("step6")
        try {
            val trigFails = prepare(db)
            val id = insertItem(db, "快递 递到 到了")

            // 连续两轮 软删 → 恢复，检查不会越恢复越多
            repeat(2) { round ->
                db.execSQL("UPDATE zhuli_memory_item SET status = 'DELETED' WHERE id = ?", arrayOf<Any>(id))
                assertEquals("第 ${round + 1} 轮软删后应 0 命中", emptyList<Long>(), matchRowIds(db, "\"快递\""))

                db.execSQL("UPDATE zhuli_memory_item SET status = 'ACTIVE' WHERE id = ?", arrayOf<Any>(id))
                val hits = matchRowIds(db, "\"快递\"")
                println("  第 ${round + 1} 轮恢复: 命中=$hits, fts 行数=${ftsRows(db)}, 触发器失败=$trigFails")
                assertEquals("第 ${round + 1} 轮恢复后应重新命中", listOf(id), hits)
                assertEquals("第 ${round + 1} 轮恢复后 FTS 行数应为 1", 1L, ftsRows(db))
                assertEquals("第 ${round + 1} 轮恢复后不得有重复 rowid", 0L, ftsDuplicatedRowIds(db))
            }
        } finally {
            db.close()
        }
    }

    // ---------------------------------------------------------------- ⑦ 物理删

    @Test
    fun step7_physicalDeleteRemovesIndexRow() {
        banner("step7 物理 DELETE 后索引消失、无孤儿")
        val db = openProbe("step7")
        try {
            val trigFails = prepare(db)
            val id = insertItem(db, "快递 递到 到了")
            assertEquals("前置：应命中", listOf(id), matchRowIds(db, "\"快递\""))

            db.execSQL("DELETE FROM zhuli_memory_item WHERE id = ?", arrayOf<Any>(id))

            val hits = matchRowIds(db, "\"快递\"")
            println("  物理删后命中=$hits, fts 行数=${ftsRows(db)}, 孤儿=${ftsOrphans(db)}, 触发器失败=$trigFails")
            assertEquals("物理删后 MATCH 必须 0 命中；触发器失败记录=$trigFails", emptyList<Long>(), hits)
            assertEquals("物理删后 FTS 行数应为 0", 0L, ftsRows(db))
            assertEquals("物理删后 content 影子表也应为 0", 0L, ftsContentRows(db))
            assertEquals("不得留下孤儿索引行", 0L, ftsOrphans(db))
            assertEquals("原表行应为 0", 0L, count(db, "SELECT COUNT(*) FROM zhuli_memory_item"))
        } finally {
            db.close()
        }
    }

    // ---------------------------------------------------------------- ⑧ 负向对照（诊断）

    /**
     * **负向对照，只记录不判定**：如果目标 ROM 比这台更严格，触发器会以哪种方式坏掉？
     *
     * 为什么必须问这个问题：C1-B 的 `detectFtsMode` 要靠"试一下"来决定走 TRIGGER 还是 APP_SYNC。
     * 如果坏环境下的表现是"`CREATE TRIGGER` 成功、写入时才报错"或者"写入不报错但索引没更新"，
     * 那么只判断建表返回值就会**误判成 TRIGGER**，然后带着一个永远搜不到东西的库上线。
     *
     * 这一条没有断言，因为它的作用是测量而不是把关；结论写进验证报告，并直接约束
     * `detectFtsMode` 的实现方式（必须做功能性探测，不能只看 DDL 是否抛错）。
     */
    @Test
    fun step8_negativeControlTrustedSchemaOff() {
        banner("step8 负向对照：trusted_schema=OFF")
        // 关键：pragma 必须在**建表之前**设置。触发器体是在首次执行时才编译的，
        // 若先建后改，可能只是命中已缓存的程序，测不出坏环境的真实表现。
        val db = openProbe("step8")

        class Attempt(val label: String, val triggerFails: List<String>, val threw: Throwable?, val ftsDelta: Long) {
            override fun toString() =
                "$label: 触发器失败=${triggerFails.size}, 写入=${if (threw == null) "未抛错" else "抛错(" + threw.javaClass.simpleName + ": " + threw.message + ")"}, FTS 增量=$ftsDelta"
        }

        fun attempt(label: String): Attempt {
            createItemTable(db)
            createFts(db)
            val fails = createTriggersLenient(db)
            val before = ftsRows(db)
            var thrown: Throwable? = null
            try {
                db.execSQL(
                    "INSERT INTO zhuli_memory_item(search_text, status) VALUES (?, ?)",
                    arrayOf<Any>("会议 议提 提醒", "ACTIVE"),
                )
            } catch (t: Throwable) {
                thrown = t
            }
            return Attempt(label, fails, thrown, ftsRows(db) - before)
        }

        try {
            val pragmaRead = try {
                db.execSQL("PRAGMA trusted_schema=OFF")
                "已设置 trusted_schema=OFF"
            } catch (t: Throwable) {
                "不支持(${describe(t)})"
            }
            println("  $pragmaRead")

            val a = attempt("A|trusted_schema=OFF 下建对象并写入")
            println("  A|$a")

            db.execSQL("PRAGMA trusted_schema=ON")
            // 换一张表名，避免与 A 的残留冲突；同时验证连接已恢复
            db.execSQL("DROP TRIGGER IF EXISTS zhuli_memory_fts_ai")
            db.execSQL("DROP TRIGGER IF EXISTS zhuli_memory_fts_au")
            db.execSQL("DROP TRIGGER IF EXISTS zhuli_memory_fts_ad")
            db.execSQL("DROP TABLE zhuli_memory_fts")
            db.execSQL("DROP TABLE zhuli_memory_item")
            val b = attempt("B|恢复 trusted_schema=ON 后重建")
            println("  B|$b")

            when {
                a.triggerFails.isNotEmpty() -> println("  结论：坏环境在**建触发器**阶段就失败（DDL 抛错，可直接探测）")
                a.threw != null -> println("  结论：坏环境在**写入**阶段失败（fail-loud，探测需写一行）")
                a.ftsDelta == 0L -> println("  结论：坏环境**静默不同步**（写入不报错但索引没更新，必须功能性探测）")
                else -> println("  结论：该 pragma 对本连接无效（索引仍同步）→ 本机无法制造坏环境，A-6 保留为未验证")
            }
            println("  对照 B（同一连接恢复后）：${if (b.threw == null && b.ftsDelta == 1L) "正常同步" else "异常"}")
        } finally {
            db.close()
        }
    }
}
