package com.zhuli.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class MemoryEmbeddingTest {

    @Test fun `哈希向量已归一化`() = runBlocking {
        val p = HashEmbeddingProvider(256)
        val v = p.embedOne("顺丰取件码 6825")
        assertEquals(256, v.size)
        var norm = 0.0
        for (f in v) norm += f * f
        assertTrue("norm=$norm", abs(norm - 1.0) < 0.001)
    }

    @Test fun `相同文本得到相同向量`() = runBlocking {
        val p = HashEmbeddingProvider()
        assertArrayEquals(p.embedOne("北京朝阳区建国路"), p.embedOne("北京朝阳区建国路"), 0f)
    }

    @Test fun `空文本是零向量 不抛异常`() = runBlocking {
        assertTrue(HashEmbeddingProvider().embedOne("").all { it == 0f })
    }

    @Test fun `不同空间维度的向量拒绝比较`() {
        assertEquals(0.0, cosine(FloatArray(256), FloatArray(768)), 0.0)
    }

    @Test fun `blob 往返不丢精度`() {
        val v = FloatArray(8) { it * 0.125f - 0.5f }
        val back = v.toBlob().toFloats()
        assertArrayEquals(v, back, 0f)
    }

    @Test fun `文档明确声明哈希不是语义模型`() {
        // 这条测试是给未来改代码的人看的：不许把 trigram 哈希宣传成 semantic embedding。
        val p = HashEmbeddingProvider()
        assertTrue(p.id.contains("hash"))
        assertFalse(p.id.contains("semantic"))
    }
}
