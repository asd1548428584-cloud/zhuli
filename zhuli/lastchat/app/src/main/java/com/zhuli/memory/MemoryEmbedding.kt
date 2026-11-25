package com.zhuli.memory

import kotlin.math.sqrt

/**
 * 嵌入（Embedding）接入层。
 *
 * 事实先说清楚（这是上一版最容易被误读的地方）：
 * - [HashEmbeddingProvider] **不是语义模型**。它是字符 2-gram 哈希，只解决「词序不同 / 被标点断开 /
 *   只记得片段」这三类字面问题；同义改写（「修空调的师傅」vs「空调维修」）它一样搜不到。
 * - 真正的语义向量走 [GemmaEmbeddingProvider]（EmbeddingGemma 300M / 768 维 / 端侧），
 *   它由 `local-llm` 的 LiteRtEmbedder 驱动，只在 arm64 真机 + 用户已下载模型时可用。
 * - 两种向量的维度与空间都不同，**绝不能互相比较**。所以每条向量都带 `provider|dim`，
 *   检索时只在自己那一组里算余弦 —— 这是硬约束，不是优化。
 *
 * 上层（[com.zhuli.core.VectorSearch]）不关心用的是哪种，只要求同一时刻用同一套。
 */
interface EmbeddingProvider {
    /** 空间标识，落库用。换模型必须换 id，否则历史向量会被当成同一空间。 */
    val id: String
    val dim: Int

    /**
     * 是不是**真语义**模型。
     * 默认 false：哈希向量只解决「词序不同 / 被标点断开 / 只记得片段」，
     * 检索理由和 UI 文案必须照实写「向量相似（字面）」，不许冒充语义检索。
     */
    val isSemantic: Boolean get() = false

    /** 是否真的可用（比如 Gemma 需要 arm64 + 模型文件）。 */
    suspend fun isAvailable(): Boolean

    suspend fun embed(texts: List<String>): List<FloatArray>
}

/** 向量空间标识：id + 维度。任何比较前先比这个。 */
data class EmbeddingSpace(val id: String, val dim: Int) {
    /** 落库键：`providerId|dim`，换模型或换维度都会变，历史向量自然不会被混用。 */
    val key: String get() = "$id|$dim"

    companion object {
        fun of(provider: EmbeddingProvider) = EmbeddingSpace(provider.id, provider.dim)
    }
}

/**
 * 字符 2-gram 哈希向量（零依赖、毫秒级）。
 * 明确标注：字面相似度，不是语义相似度。保留它的唯一理由是「没装模型时也不能没有检索」。
 */
class HashEmbeddingProvider(override val dim: Int = 256) : EmbeddingProvider {

    override val id: String = "hash-2gram-v1"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { embedOne(it) }

    fun embedOne(text: String): FloatArray {
        val v = FloatArray(dim)
        val s = text.lowercase().replace(Regex("\\s+"), " ")
        if (s.isBlank()) return v
        fun add(gram: String, w: Float) {
            if (gram.isBlank()) return
            var h = 2166136261.toInt()
            for (c in gram) {
                h = h xor c.code
                h *= 16777619
            }
            val idx = (h and 0x7fffffff) % dim
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

/** 余弦相似度：入参必须已归一化（两个 provider 都归一化后再落库）。 */
fun cosine(a: FloatArray, b: FloatArray): Double {
    if (a.size != b.size) return 0.0
    var dot = 0.0
    for (i in a.indices) dot += a[i] * b[i]
    return dot
}

/** float32 → 小端字节；落 BLOB 用。 */
fun FloatArray.toBlob(): ByteArray {
    val out = ByteArray(size * 4)
    for (i in indices) {
        val bits = this[i].toRawBits()
        out[i * 4] = (bits and 0xFF).toByte()
        out[i * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
        out[i * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
        out[i * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
    }
    return out
}

fun ByteArray.toFloats(): FloatArray {
    val n = size / 4
    val out = FloatArray(n)
    for (i in 0 until n) {
        val bits = (this[i * 4].toInt() and 0xFF) or
            ((this[i * 4 + 1].toInt() and 0xFF) shl 8) or
            ((this[i * 4 + 2].toInt() and 0xFF) shl 16) or
            ((this[i * 4 + 3].toInt() and 0xFF) shl 24)
        out[i] = Float.fromBits(bits)
    }
    return out
}
