package com.zhuli.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * **C1-A 快速预检**：与 `MemoryFtsInstrumentedTest` 同样的七项，但在 JVM 上的 Robolectric 里跑。
 *
 * 定位（别误读）：
 * - 这个文件的结果**不能代替设备结论**。C0.2 §14.1 和 §12.2 A-2 都写明了这一点：
 *   Robolectric 用的是它自己打包的 SQLite（native 模式下是 AOSP 的构建产物，legacy 模式下是
 *   很老的 sqlite4java），与目标 ROM 的 `libsqlite.so` 不是同一个二进制。
 * - 它的价值有两个：① 在等设备跑之前几秒内暴露"触发器根本写不了虚拟表"这类结构性问题；
 *   ② 把两种 SQLite 实现的差异变成可复现的数字，而不是"据说一致"。
 *
 * 所以这里**故意跑两遍**：`NATIVE`（AOSP 新版本，最接近设备）与 `LEGACY`（老 3.8.x）。
 * 两份结果分开报告，混在一起统计就会得出错误的兼容性结论。
 *
 * 与设备版一样：不碰生产代码、不碰应用数据库、每个测试自建临时库并自带全部前置步骤。
 */
abstract class MemoryFtsRobolectricProbe {

    private lateinit var ctx: Context
    private val created = mutableListOf<File>()

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
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
        val f = File(ctx.cacheDir, "c1a_rb_${name}_${System.nanoTime()}.db")
        created += f
        println("  临时库: ${f.path}")
        return SQLiteDatabase.openOrCreateDatabase(f, null)
    }

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

    private fun describe(t: Throwable): String = "${t.javaClass.name}: ${t.message}"

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

    private fun matchRowIds(db: SQLiteDatabase, expr: String): List<Long> =
        db.rawQuery("SELECT rowid FROM zhuli_memory_fts WHERE zhuli_memory_fts MATCH ?", arrayOf(expr))
            .use { c ->
                val out = mutableListOf<Long>()
                while (c.moveToNext()) out += c.getLong(0)
                out
            }

    private fun ftsRows(db: SQLiteDatabase): Long = count(db, "SELECT COUNT(*) FROM zhuli_memory_fts")

    private fun ftsContentRows(db: SQLiteDatabase): Long =
        count(db, "SELECT COUNT(*) FROM zhuli_memory_fts_content")

    private fun ftsOrphans(db: SQLiteDatabase): Long =
        count(
            db,
            "SELECT COUNT(*) FROM zhuli_memory_fts WHERE rowid NOT IN (SELECT id FROM zhuli_memory_item)",
        )

    private fun ftsDuplicatedRowIds(db: SQLiteDatabase): Long =
        count(db, "SELECT COUNT(*) FROM (SELECT rowid FROM zhuli_memory_fts GROUP BY rowid HAVING COUNT(*) > 1)")

    private fun banner(step: String) {
        println("\n===== [robj] $step =====")
        println("  运行模式: ${if (Build.FINGERPRINT.contains("robolectric")) "Robolectric" else Build.FINGERPRINT} | SDK ${Build.VERSION.SDK_INT}")
    }

    // ---------------------------------------------------------------- 0. 环境诊断

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
            println("  trusted_schema: " + q1OrNull(db, "PRAGMA trusted_schema"))
            println("  defensive(不可读时为 null): " + q1OrNull(db, "PRAGMA defensive"))
        } finally {
            db.close()
        }
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
            assertTrue("INSERT 后 FTS 应命中该行；触发器失败记录=$trigFails", hits == listOf(id))
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

            val hits = matchRowIds(db, "\"快递\"")
            println("  软删后命中=$hits, fts 行数=${ftsRows(db)}, 原表 status=${q1(db, "SELECT status FROM zhuli_memory_item WHERE id=$id")}")
            assertEquals("软删后 MATCH 必须 0 命中；触发器失败记录=$trigFails", emptyList<Long>(), hits)
            assertEquals("软删后 FTS 行数应为 0", 0L, ftsRows(db))
            assertEquals("软删后 content 影子表也应为 0", 0L, ftsContentRows(db))
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
     * 与设备版 step8 同义：测量"更严格的 schema 信任策略"下触发器怎么坏。
     * LEGACY（3.8.7）根本没有 `trusted_schema` 这个 pragma，执行它等于空操作 ——
     * 这本身就说明"用老 SQLite 的测试结果推断新设备行为"是不成立的。
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

/**
 * NATIVE 模式：Robolectric 加载 AOSP 的 SQLite 原生库，行为最接近真机。
 * 这是快速预检的主口径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.zhuli.testutil.PlainTestApp::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class MemoryFtsRobolectricTest : MemoryFtsRobolectricProbe()

/**
 * LEGACY 模式：老的 sqlite4java（SQLite 3.8.x）。
 *
 * 之所以也跑：项目里既有的 `MemorySqliteTest` 一直跑在 Robolectric 默认模式下，
 * 如果两种模式的 FTS4 行为不同，那么"Robolectric 过了"这件事本身就不构成证据。
 * 这一类的存在就是为了把这个差异量化，而不是靠猜。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.zhuli.testutil.PlainTestApp::class)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class MemoryFtsRobolectricLegacyTest : MemoryFtsRobolectricProbe()
