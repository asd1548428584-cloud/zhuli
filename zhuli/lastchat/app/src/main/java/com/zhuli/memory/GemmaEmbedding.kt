package com.zhuli.memory

import me.rerere.locallm.LiteRtEmbedder
import me.rerere.locallm.LocalModelKind
import me.rerere.locallm.LocalModelStore
import java.io.File
import kotlin.math.sqrt

/**
 * 真·语义 Embedding：端侧 EmbeddingGemma（LiteRT，arm64）。
 *
 * 方案选择依据（复审结论，写在这里防止后人再走回头路）：
 * - 中文检索效果明显优于字符 n-gram；180MB 级模型只在**用户已下载**时才启用，
 *   不下载就老实退回 [HashEmbeddingProvider]，不往包体里塞模型；
 * - LiteRT 已在工程里（本地 LLM 在用），不新增 ONNX Runtime 这类额外 native 依赖；
 * - x86_64 模拟器跑不了 RAG native 库，`isAvailable()` 直接 false ——
 *   这不是故障，是设计上的降级边界，模拟器上必须走哈希检索。
 *
 * 向量统一归一化，且与哈希向量用不同 space id；跨 space  comparisons 被 [cosine] 的维度检查拦住。
 */
class GemmaEmbeddingProvider(
    private val embedder: LiteRtEmbedder,
    private val store: LocalModelStore,
    private val modelId: String? = null,
    override val dim: Int = 768,
) : EmbeddingProvider {

    override val id: String = "gemma-embedding-v1"

    /** 真语义模型：EmbeddingGemma 的输出可以承受「同义改写」检索。 */
    override val isSemantic: Boolean = true

    private suspend fun installed() = (if (modelId.isNullOrBlank()) {
        store.current().firstOrNull { it.kind == LocalModelKind.EMBEDDING }
    } else {
        store.get(modelId)?.takeIf { it.kind == LocalModelKind.EMBEDDING }
    })

    override suspend fun isAvailable(): Boolean {
        if (!embedder.isSupported) return false
        val m = installed() ?: return false
        return File(m.filePath).exists() && !m.tokenizerPath.isNullOrBlank() && File(m.tokenizerPath).exists()
    }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val m = installed() ?: error("embedding_model_not_installed")
        val raw = embedder.embed(m, texts)
        return raw.map { list ->
            val v = FloatArray(dim) { i -> list.getOrNull(i) ?: 0f }
            var norm = 0.0
            for (f in v) norm += f * f
            norm = sqrt(norm)
            if (norm > 0) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
            v
        }
    }
}
