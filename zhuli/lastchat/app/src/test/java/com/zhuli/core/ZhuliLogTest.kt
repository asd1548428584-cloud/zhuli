package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ZhuliLogTest {
    private fun tmpDir(): File {
        val d = File(System.getProperty("java.io.tmpdir"), "zhuli-log-${System.nanoTime()}")
        d.mkdirs()
        ZhuliLog.init(d, maxBytesPerFile = 600, keepFiles = 3)
        return d
    }

    @Test
    fun writesAndReads() {
        val d = tmpDir()
        try {
            ZhuliLog.i("Tag", "hello 中文")
            val text = ZhuliLog.readAll()
            assertTrue(text.contains("I/Tag: hello 中文"))
            assertTrue(text.contains("log started"))
        } finally {
            ZhuliLog.reset()
            d.deleteRecursively()
        }
    }

    @Test
    fun rotatesAndKeepsBounded() {
        val d = tmpDir()
        try {
            repeat(40) { ZhuliLog.i("T", "x".repeat(80)) }
            val files = File(d, "logs").listFiles { f -> f.name.startsWith("zhuli.log") }?.size ?: 0
            assertTrue("日志文件数应 ≤ 3，实际 $files", files in 1..3)
            assertTrue(ZhuliLog.readAll().contains("T:"))
        } finally {
            ZhuliLog.reset()
            d.deleteRecursively()
        }
    }

    @Test
    fun crashRecordHasFullStackTrace() {
        val d = tmpDir()
        try {
            ZhuliLog.i("T", "before crash")
            val f = ZhuliLog.recordCrash(RuntimeException("boom"), "unit-test")
            assertNotNull(f)
            val txt = f!!.readText()
            assertTrue(txt.contains("java.lang.RuntimeException: boom"))
            assertTrue(txt.contains("unit-test"))
            assertTrue(txt.contains("ZhuliLogTest"))
            assertTrue(txt.contains("before crash"))
            assertEquals(1, ZhuliLog.crashFiles().size)
        } finally {
            ZhuliLog.reset()
            d.deleteRecursively()
        }
    }

    @Test
    fun noopBeforeInit() {
        ZhuliLog.reset()
        ZhuliLog.e("T", "ignored")
        assertEquals("", ZhuliLog.readAll())
    }

    @Test
    fun clearRemovesMainLog() {
        val d = tmpDir()
        try {
            ZhuliLog.i("T", "clean me")
            ZhuliLog.clear()
            assertTrue(ZhuliLog.readAll().isEmpty())
        } finally {
            ZhuliLog.reset()
            d.deleteRecursively()
        }
    }
}
