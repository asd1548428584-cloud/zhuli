package com.zhuli.core

import com.zhuli.core.ZhuliLog as Log
import kotlin.math.sqrt

/**
 * 混合检索（P0）：向量召回 + 关键词兜底。
 *
 * 现状与取舍（必须写清楚，免得后人以为这是「真语义」）：
 * - 理想做法是 MediaPipe Semantic Retriever + EmbeddingGemma 2（256 维、端侧、NPU 加速）。
 *   但那需要 200MB+ 的模型文件，本次电脑上没有、也拉不下来，所以**没有引入该依赖**；
 * - 现在用的是 [HashEmbedder]：字符 1/2-gram 哈希到 256 维再归一化。它能解决 LIKE 的三个真实痛点
 *   —— 词序不同、被标点/空格断开、只记得片段；**但它不是语义模型**，同义改写（「修空调的师傅」vs「空调维修」）仍搜不到；
 * - 所以 [search] 永远是**混合**的：向量分 + 关键词命中加权，谁都不能单独决定结果。
 *   等 `filesDir/models/embeddinggemma.task` 就位，把 [embed] 换成 GemmaProvider 即可，上层不用动。
 */
object VectorSearch {

    private const val TAG = "zhuli.vec"
    const val DIM = 256

    /** 可插拔的嵌入器：将来换真模型只改这一处。 */
    fun embed(text: String): FloatArray = HashEmbedder.embed(text)

    /** 给还没向量化的行补上（每次最多 60 条，避免主线程/电池压力）。 */
    fun indexMissing(db: InboxDatabase): Int {
        var n = 0
        try {
            for (rec in db.missingEmbeddingRows(60)) {
                val text = (rec.title + " " + rec.text + " " + rec.note).trim()
                if (text.isBlank()) continue
                db.putEmbedding(rec.id, embed(text))
                n++
            }
        } catch (t: Throwable) {
            Log.w(TAG, "index failed", t)
        }
        return n
    }

    /**
     * 混合检索：先按时间窗取候选，再用「向量余弦 + 关键词命中」重排。
     * 关键词命中权重更高（0.55 vs 0.45）——用户能打出原词时，精确命中永远该赢。
     */
    fun search(
        db: InboxDatabase,
        query: String,
        from: Long,
        to: Long,
        limit: Int = 8
    ): List<InboxRecord> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        indexMissing(db)

        val qv = embed(q)
        val scored = ArrayList<Triple<InboxRecord, Double, Double>>()  // rec, vec, kw
        val vecMap = db.embeddingsInWindow(from, to, 400).toMap()
        // 候选：时间窗内最近 400 条（含无向量的，靠关键词仍可命中）
        for (rec in db.between(from, to, 400)) {
            val kw = keywordScore(rec, q)
            val v = vecMap[rec.id]?.let { cosine(qv, it) } ?: 0.0
            if (kw <= 0.0 && v < 0.20) continue
            scored.add(Triple(rec, v, kw))
        }
        return scored
            .sortedByDescending { (_, v, kw) -> v * 0.45 + kw * 0.55 }
            .take(limit)
            .map { it.first }
    }

    /** 关键词得分：整串命中 > 分词后命中 > 单字重合（给 0~1）。 */
    private fun keywordScore(rec: InboxRecord, q: String): Double {
        val hay = (rec.title + " " + rec.text + " " + rec.note).lowercase()
        if (hay.contains(q.lowercase())) return 1.0
        val parts = q.split(Regex("[\\s\\p{Punct}，。！？、]+")).filter { it.length >= 2 }
        if (parts.isEmpty()) return 0.0
        val hit = parts.count { hay.contains(it.lowercase()) }
        return if (hit == 0) 0.0 else 0.5 + 0.5 * hit / parts.size
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return dot      // 两边都已归一化
    }

    /**
     * 字符 n-gram 哈希嵌入（零依赖、毫秒级）。
     * 为什么用 1-gram + 2-gram：中文没有空格，2-gram 近似"词"，1-gram 兜住单字；
     * 哈希到 256 维做过冲突（不完美，但足够做排序用的相似度）。
     */
    private object HashEmbedder {
        fun embed(text: String): FloatArray {
            val v = FloatArray(DIM)
            val s = text.lowercase().replace(Regex("\\s+"), " ")
            if (s.isBlank()) return v
            fun add(gram: String, w: Float) {
                if (gram.isBlank()) return
                var h = 2166136261.toInt()
                for (c in gram) {
                    h = h xor c.code
                    h *= 16777619
                }
                val idx = (h and 0x7fffffff) % DIM
                v[idx] += w
            }
            // 单字（弱） + 相邻两字（强）
            for (i in s.indices) add(s[i].toString(), 0.5f)
            for (i in 0 until s.length - 1) add(s.substring(i, i + 2), 1.0f)
            var norm = 0.0
            for (f in v) norm += f * f
            norm = sqrt(norm)
            if (norm > 0) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
            return v
        }
    }
}
