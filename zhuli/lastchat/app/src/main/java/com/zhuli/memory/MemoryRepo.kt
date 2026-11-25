package com.zhuli.memory

import android.content.ContentValues
import android.database.Cursor
import com.zhuli.core.InboxDatabase as Db

/**
 * 记忆持久化（v15 起；v16 加向量索引队列）。
 *
 * 三条硬规则：
 * 1. **历史不物理删除**。纠错 = 旧事实标 SUPERSEDED + 新事实写 superseded_by 指回去；
 *    删除 = 标 DELETED。否则「你上次说的不是这个」永远查不到，用户也没法纠错。
 * 2. **写入按槽位收敛**。同一「主体+谓词」已有值不同时，只有新证据置信度不低于旧的才取代；
 *    低置信度的新消息不会把用户亲口说的事实冲掉。同值重复到达不新增行。
 * 3. **向量只是索引，不是事实**。事实一落库就进索引队列，由后台线程建向量；
 *    建索引失败、模型没装都不影响事实本身，也绝不阻塞通知采集线程。
 */
class MemoryRepo(private val db: Db) {

    /**
     * 写入完成后通知索引器（默认走进程级单例；单测可传空 lambda 关闭后台副作用）。
     * 只排队、不做推理 —— 调用方可能正跑在通知采集线程上。
     */
    @Volatile
    var onIndexRequested: (() -> Unit)? = { MemoryIndexer.requestIndex() }

    /**
     * 一条通知/短信过完提取流水线后调用。返回真正新写入的事实数。
     *
     * @param activateThreshold 置信度低于它的草稿写成 [FactStatus.UNCONFIRMED]（历史回填用）：
     *   存下来给用户看，但不参与检索、不覆盖现有记忆。
     */
    fun ingest(
        drafts: List<FactDraft>,
        source: SourceType,
        sourceId: Long,
        now: Long,
        activateThreshold: Double = 0.0,
    ): Int {
        if (drafts.isEmpty()) return 0
        val sql = db.writableDatabase
        var inserted = 0
        sql.beginTransaction()
        try {
            for (d in drafts) {
                if (d.subject.isBlank() || d.value.isBlank()) continue
                val subject = d.subject.trim()
                val predicate = d.predicate.trim()
                val value = d.value.trim()
                d.entityKind?.let { upsertEntity(it, subject, now) }
                val status = if (d.confidence < activateThreshold) FactStatus.UNCONFIRMED else FactStatus.ACTIVE

                val existing = activeOf(subject, predicate)
                // 1) 同值重复到达：不新增行，只在证据更强时抬一下置信度/观察时间
                val same = existing.firstOrNull { MemoryExtractor.norm(it.value) == MemoryExtractor.norm(value) }
                if (same != null) {
                    if (d.confidence > same.confidence + 0.05) {
                        reinforce(same.id, d.confidence.coerceIn(0.0, 1.0), source, sourceId, now)
                    }
                    continue
                }
                // 2) 同槽位不同值：低置信度不覆盖高置信度；待确认的草稿更不参与替代
                val conflict = existing.firstOrNull()
                if (status == FactStatus.ACTIVE) {
                    if (conflict != null && d.confidence + 0.05 < conflict.confidence) continue
                }

                val id = insert(
                    Fact(
                        subject = subject,
                        predicate = predicate,
                        value = value,
                        kind = d.kind,
                        confidence = d.confidence.coerceIn(0.0, 1.0),
                        sourceType = source,
                        sourceId = sourceId,
                        sourceText = d.sourceText.take(300),
                        observedAt = now,
                        validFrom = now,
                        status = status,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                if (id <= 0L) continue
                // 3) 冲突则把旧事实标记为「被这条取代」，superseded_by 必须是真实新 ID
                if (status == FactStatus.ACTIVE && conflict != null) supersede(conflict.id, id, now)
                if (status == FactStatus.ACTIVE) enqueueVector(id, now)
                inserted++
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        if (inserted > 0) onIndexRequested?.invoke()
        return inserted
    }

    /** 用户纠错：旧事实保留为历史，新事实以「用户明确说的」最高置信度落库。 */
    fun correct(factId: Long, newValue: String, now: Long): Long? {
        val old = byId(factId) ?: return null
        val sql = db.writableDatabase
        sql.beginTransaction()
        try {
            val id = insert(
                old.copy(
                    id = 0L,
                    value = newValue.trim(),
                    confidence = 1.0,
                    sourceType = SourceType.USER,
                    sourceId = 0L,
                    sourceText = "用户纠正：${old.display} → $newValue".take(300),
                    observedAt = now,
                    validFrom = now,
                    validTo = 0L,
                    status = FactStatus.ACTIVE,
                    supersededBy = 0L,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            if (id <= 0L) return null
            supersede(old.id, id, now)
            enqueueVector(id, now)
            sql.setTransactionSuccessful()
            return id
        } finally {
            sql.endTransaction()
        }
    }

    /** 软删除：查不到、但历史还在。真要物理清除走用户的「清除全部数据」。 */
    fun deleteFact(id: Long, now: Long): Int {
        val sql = db.writableDatabase
        val cv = ContentValues().apply {
            put(Db.COL_F_STATUS, FactStatus.DELETED.name)
            put(Db.COL_F_UPDATED, now)
        }
        val n = sql.update(Db.TABLE_FACT, cv, "${Db.COL_F_ID}=?", arrayOf(id.toString()))
        // 已删除的事实不能继续被向量检索命中：向量和排队任务一起清掉
        dropVector(id)
        return n
    }

    fun byId(id: Long): Fact? = queryOne("${Db.COL_F_ID}=?", arrayOf(id.toString()))

    /** 历史回填留下的待确认事实（不参与检索，也不覆盖任何现有记忆）。 */
    fun unconfirmed(limit: Int = 100): List<Fact> = query(
        "${Db.COL_F_STATUS}=? ORDER BY ${Db.COL_F_CREATED} DESC LIMIT $limit",
        arrayOf(FactStatus.UNCONFIRMED.name),
        limit,
    )

    /** 用户确认一条待确认事实 → 转 ACTIVE，并进入向量索引队列。 */
    fun confirmFact(id: Long, now: Long): Boolean {
        val f = byId(id) ?: return false
        if (f.status != FactStatus.UNCONFIRMED) return false
        val sql = db.writableDatabase
        sql.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put(Db.COL_F_STATUS, FactStatus.ACTIVE.name)
                put(Db.COL_F_UPDATED, now)
            }
            sql.update(Db.TABLE_FACT, cv, "${Db.COL_F_ID}=?", arrayOf(id.toString()))
            enqueueVector(id, now)
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        onIndexRequested?.invoke()
        return true
    }

    fun activeOf(subject: String, predicate: String): List<Fact> = query(
        "${Db.COL_F_STATUS}=? AND ${Db.COL_F_SUBJECT}=? AND ${Db.COL_F_PRED}=? ORDER BY ${Db.COL_F_CONF} DESC LIMIT 50",
        arrayOf(FactStatus.ACTIVE.name, subject, predicate),
        limit = 50,
    )

    /** 全部 ACTIVE 事实（UI 列表用），按观测时间倒序。 */
    fun active(limit: Int = 200): List<Fact> =
        query("${Db.COL_F_STATUS}=? ORDER BY ${Db.COL_F_OBSERVED} DESC LIMIT $limit", arrayOf(FactStatus.ACTIVE.name), limit)

    fun activeCount(): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM ${Db.TABLE_FACT} WHERE ${Db.COL_F_STATUS}=?", arrayOf(FactStatus.ACTIVE.name)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 某条事实的完整变更历史（含被纠错的版本）。 */
    fun history(subject: String, predicate: String): List<Fact> = query(
        "${Db.COL_F_SUBJECT}=? AND ${Db.COL_F_PRED}=? ORDER BY ${Db.COL_F_CREATED} LIMIT 100",
        arrayOf(subject, predicate),
        limit = 100,
    )

    /** 关键词 / 时效 / 置信度四路打分检索。 */
    fun search(queryText: String, now: Long, limit: Int = 8): List<FactHit> =
        MemoryRanker.search(active(300), queryText, now, limit)

    /** 当前库里相互矛盾的事实对（供 UI 列出来让用户裁决）。 */
    fun conflicts(): List<Pair<Fact, Fact>> = MemoryRanker.conflicts(active(300))

    fun resolveConflict(keepId: Long, dropId: Long, now: Long) {
        val sql = db.writableDatabase
        sql.beginTransaction()
        try {
            val cv = ContentValues().apply {
                put(Db.COL_F_STATUS, FactStatus.SUPERSEDED.name)
                put(Db.COL_F_SUPERSEDED, keepId)
                put(Db.COL_F_UPDATED, now)
            }
            sql.update(Db.TABLE_FACT, cv, "${Db.COL_F_ID}=?", arrayOf(dropId.toString()))
            dropVector(dropId)
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    // ---------- 向量：严格按 space 隔离 ----------

    fun putVector(factId: Long, space: EmbeddingSpace, vec: FloatArray) {
        val cv = ContentValues().apply {
            put(Db.COL_FV_FACT, factId)
            put(Db.COL_FV_SPACE, space.key)
            put(Db.COL_FV_VEC, vec.toBlob())
            put(Db.COL_FV_AT, System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(
            Db.TABLE_FACT_VEC, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    /** 建好索引后把排队任务删掉（同一条事实只允许一行向量，fact_id 是主键）。 */
    fun markVectorIndexed(factId: Long) {
        db.writableDatabase.delete(Db.TABLE_VEC_QUEUE, "${Db.COL_VQ_FACT}=?", arrayOf(factId.toString()))
    }

    /** 索引失败：累计次数 + 记最后一次错误；事实本身纹丝不动。 */
    fun markVectorFailed(factId: Long, reason: String, now: Long) {
        db.writableDatabase.execSQL(
            "UPDATE ${Db.TABLE_VEC_QUEUE} SET ${Db.COL_VQ_ATTEMPTS}=${Db.COL_VQ_ATTEMPTS}+1, " +
                "${Db.COL_VQ_ERROR}=?, ${Db.COL_VQ_AT}=? WHERE ${Db.COL_VQ_FACT}=?",
            arrayOf<Any>(reason.take(200), now, factId)
        )
    }

    fun dropVector(factId: Long) {
        val sql = db.writableDatabase
        sql.delete(Db.TABLE_FACT_VEC, "${Db.COL_FV_FACT}=?", arrayOf(factId.toString()))
        sql.delete(Db.TABLE_VEC_QUEUE, "${Db.COL_VQ_FACT}=?", arrayOf(factId.toString()))
    }

    private fun enqueueVector(factId: Long, now: Long) {
        val cv = ContentValues().apply {
            put(Db.COL_VQ_FACT, factId)
            put(Db.COL_VQ_AT, now)
        }
        db.writableDatabase.insertWithOnConflict(
            Db.TABLE_VEC_QUEUE, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    /** 待建索引的事实（只取 ACTIVE 的；被删/被替代的不再消耗算力）。 */
    fun pendingVectorFacts(limit: Int = 16): List<Long> {
        val out = ArrayList<Long>()
        db.readableDatabase.rawQuery(
            "SELECT q.${Db.COL_VQ_FACT} FROM ${Db.TABLE_VEC_QUEUE} q " +
                "JOIN ${Db.TABLE_FACT} f ON f.${Db.COL_F_ID}=q.${Db.COL_VQ_FACT} " +
                "WHERE f.${Db.COL_F_STATUS}=? AND q.${Db.COL_VQ_ATTEMPTS} < 3 " +
                "ORDER BY q.${Db.COL_VQ_AT} LIMIT $limit",
            arrayOf(FactStatus.ACTIVE.name)
        ).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    fun pendingVectorCount(): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM ${Db.TABLE_VEC_QUEUE}", null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * 可重试的待建索引数（attempts < 3）。UI 必须和「永久失败数」分开显示 ——
     * 把 attempts=3 的项算进「待处理」会让用户以为再等等就好，实际上它永远不会再被处理。
     */
    fun waitingVectorCount(): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM ${Db.TABLE_VEC_QUEUE} WHERE ${Db.COL_VQ_ATTEMPTS} < 3", null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 反复失败（attempts >= 3）被移出重试队列的项：只能靠手动重建救回。 */
    fun failedVectorCount(): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM ${Db.TABLE_VEC_QUEUE} WHERE ${Db.COL_VQ_ATTEMPTS} >= 3", null
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }


    /** 已建索引的 space 集合：用来判断「换了模型要不要重建」。 */
    fun indexedSpaces(): Set<String> {
        val out = HashSet<String>()
        db.readableDatabase.rawQuery(
            "SELECT ${Db.COL_FV_SPACE}, COUNT(*) FROM ${Db.TABLE_FACT_VEC} GROUP BY ${Db.COL_FV_SPACE}", null
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) ?: "")
        }
        return out
    }

    fun vectorCount(space: EmbeddingSpace): Int = db.readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM ${Db.TABLE_FACT_VEC} WHERE ${Db.COL_FV_SPACE}=?", arrayOf(space.key)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /**
     * 索引重建：清掉指定 space 的向量（不传则清全部），并把 ACTIVE 事实全部重新排队。
     *
     * A 修复：这里不能只靠 [enqueueVector] 的 CONFLICT_IGNORE —— 反复失败 3 次的队列行
     * 仍在表里（attempts=3），IGNORE 会把它原样留下，而 pendingVectorFacts() 又用
     * `attempts < 3` 把它排除，结果「手动重建」对这些永久失败项完全无效。
     * 所以重建时显式把 attempts / last_error 清零，让它们重新进入重试队列。
     */
    fun enqueueAllActive(now: Long, dropSpace: String? = null): Int {
        val sql = db.writableDatabase
        if (dropSpace == null) sql.delete(Db.TABLE_FACT_VEC, null, null)
        else sql.delete(Db.TABLE_FACT_VEC, "${Db.COL_FV_SPACE}=?", arrayOf(dropSpace))
        val ids = ArrayList<Long>()
        db.readableDatabase.rawQuery(
            "SELECT ${Db.COL_F_ID} FROM ${Db.TABLE_FACT} WHERE ${Db.COL_F_STATUS}=?", arrayOf(FactStatus.ACTIVE.name)
        ).use { c ->
            while (c.moveToNext()) ids.add(c.getLong(0))
        }
        sql.beginTransaction()
        try {
            for (id in ids) {
                enqueueVector(id, now)
                // 重置失败计数与错误：这是「手动重建能救回永久失败项」的关键。
                // 必须在 enqueueVector 之后执行，保证行已存在（否则 UPDATE 影响 0 行）。
                sql.execSQL(
                    "UPDATE ${Db.TABLE_VEC_QUEUE} SET ${Db.COL_VQ_ATTEMPTS}=0, ${Db.COL_VQ_ERROR}='', " +
                        "${Db.COL_VQ_AT}=? WHERE ${Db.COL_VQ_FACT}=?",
                    arrayOf<Any>(now, id),
                )
            }
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
        if (ids.isNotEmpty()) onIndexRequested?.invoke()
        return ids.size
    }

    /** 建索引用的文本：主体 + 谓词 + 值。与检索时的查询落在同一语义里。 */
    fun vectorText(factId: Long): String? {
        val f = byId(factId) ?: return null
        if (f.status != FactStatus.ACTIVE) return null
        return f.display
    }

    /**
     * 语义检索：把字面检索的前 40 条候选与同 space 的向量做余弦混合。
     * 只在 [MemoryRanker] 没有强字面命中时把向量分加进来 —— 真机基线证明
     * 用户打得出原词时字面命中永远该赢，向量只负责「只记得意思、忘了原词」。
     *
     * 标 attention：哈希向量只能救「词序/标点/片段」，救不了同义改写，
     * 所以它的命中理由写「向量相似（字面）」，不冒充语义。
     */
    suspend fun hybridSearch(text: String, provider: EmbeddingProvider, now: Long, limit: Int = 8): List<FactHit> {
        val lexical = search(text, now, 40)
        if (!provider.isAvailable()) return lexical.take(limit)
        val q = provider.embed(listOf(text)).firstOrNull() ?: return lexical.take(limit)
        val space = EmbeddingSpace.of(provider)
        val byId = active(300).associateBy { it.id }
        val semantic = ArrayList<Pair<Fact, Double>>()
        db.readableDatabase.rawQuery(
            "SELECT ${Db.COL_FV_FACT}, ${Db.COL_FV_VEC} FROM ${Db.TABLE_FACT_VEC} WHERE ${Db.COL_FV_SPACE}=?",
            arrayOf(space.key)
        ).use { c ->
            while (c.moveToNext()) {
                val fact = byId[c.getLong(0)] ?: continue
                val v = cosine(q, c.getBlob(1).toFloats()).coerceIn(0.0, 1.0)
                if (v >= 0.55) semantic.add(fact to v)
            }
        }
        val label = if (provider.isSemantic) "语义相似" else "向量相似（字面）"
        val merged = lexical.toMutableList()
        val seen = lexical.map { it.fact.id }.toHashSet()
        for ((fact, sim) in semantic.sortedByDescending { it.second }) {
            if (fact.id in seen) continue
            merged.add(FactHit(fact, sim * 0.55, listOf(label)))
        }
        return merged.sortedByDescending { it.score }.take(limit)
    }

    // ---------- 内部 ----------

    private fun insert(f: Fact): Long {
        val cv = ContentValues().apply {
            put(Db.COL_F_SUBJECT, f.subject)
            put(Db.COL_F_PRED, f.predicate)
            put(Db.COL_F_VALUE, f.value)
            put(Db.COL_F_KIND, f.kind.name)
            put(Db.COL_F_CONF, f.confidence)
            put(Db.COL_F_STYPE, f.sourceType.name)
            put(Db.COL_F_SID, f.sourceId)
            put(Db.COL_F_STEXT, f.sourceText)
            put(Db.COL_F_OBSERVED, f.observedAt)
            put(Db.COL_F_VALID_FROM, f.validFrom)
            put(Db.COL_F_VALID_TO, f.validTo)
            put(Db.COL_F_STATUS, f.status.name)
            put(Db.COL_F_SUPERSEDED, f.supersededBy)
            put(Db.COL_F_CREATED, f.createdAt)
            put(Db.COL_F_UPDATED, f.updatedAt)
        }
        return db.writableDatabase.insert(Db.TABLE_FACT, null, cv)
    }

    /** 同值重复到达但证据更强：只抬置信度并刷新观察时间/来源，不新增一行。 */
    private fun reinforce(id: Long, confidence: Double, source: SourceType, sourceId: Long, now: Long) {
        val cv = ContentValues().apply {
            put(Db.COL_F_CONF, confidence)
            put(Db.COL_F_OBSERVED, now)
            put(Db.COL_F_STYPE, source.name)
            put(Db.COL_F_SID, sourceId)
            put(Db.COL_F_UPDATED, now)
        }
        db.writableDatabase.update(Db.TABLE_FACT, cv, "${Db.COL_F_ID}=?", arrayOf(id.toString()))
    }

    private fun supersede(id: Long, by: Long, now: Long) {
        val cv = ContentValues().apply {
            put(Db.COL_F_STATUS, FactStatus.SUPERSEDED.name)
            put(Db.COL_F_SUPERSEDED, by)
            put(Db.COL_F_VALID_TO, now)
            put(Db.COL_F_UPDATED, now)
        }
        db.writableDatabase.update(Db.TABLE_FACT, cv, "${Db.COL_F_ID}=?", arrayOf(id.toString()))
        // 被替代的事实不能再被向量检索命中
        dropVector(id)
    }

    private fun upsertEntity(kind: EntityKind, name: String, now: Long) {
        val norm = MemoryExtractor.norm(name)
        if (norm.isBlank()) return
        val cv = ContentValues().apply {
            put(Db.COL_ENT_KIND, kind.name)
            put(Db.COL_ENT_NAME, name)
            put(Db.COL_ENT_NORM, norm)
            put(Db.COL_ENT_UPDATED, now)
        }
        db.writableDatabase.insertWithOnConflict(
            Db.TABLE_ENTITY, null, cv, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun queryOne(where: String, args: Array<String>): Fact? = query(where, args, 1).firstOrNull()

    private fun query(where: String, args: Array<String>, limit: Int): List<Fact> {
        val out = ArrayList<Fact>()
        db.readableDatabase.rawQuery(
            "SELECT * FROM ${Db.TABLE_FACT} WHERE $where", args
        ).use { c ->
            while (c.moveToNext()) out.add(read(c))
        }
        return out
    }

    private fun read(c: Cursor) = Fact(
        id = c.getLong(c.getColumnIndexOrThrow(Db.COL_F_ID)),
        subject = c.str(Db.COL_F_SUBJECT),
        predicate = c.str(Db.COL_F_PRED),
        value = c.str(Db.COL_F_VALUE),
        kind = FactKind.of(c.str(Db.COL_F_KIND)),
        confidence = c.getDouble(c.getColumnIndexOrThrow(Db.COL_F_CONF)),
        sourceType = runCatching { SourceType.valueOf(c.str(Db.COL_F_STYPE)) }.getOrDefault(SourceType.INBOX),
        sourceId = c.getLong(c.getColumnIndexOrThrow(Db.COL_F_SID)),
        sourceText = c.str(Db.COL_F_STEXT),
        observedAt = c.getLong(c.getColumnIndexOrThrow(Db.COL_F_OBSERVED)),
        validFrom = c.getLong(c.getColumnIndexOrThrow(Db.COL_F_VALID_FROM)),
        validTo = c.getLong(c.getColumnIndexOrThrow(Db.COL_F_VALID_TO)),
        status = FactStatus.of(c.str(Db.COL_F_STATUS)),
        supersededBy = c.getLong(c.getColumnIndexOrThrow(Db.COL_F_SUPERSEDED)),
        createdAt = c.getLong(c.getColumnIndexOrThrow(Db.COL_F_CREATED)),
        updatedAt = c.getLong(c.getColumnIndexOrThrow(Db.COL_F_UPDATED)),
    )

    private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
}
