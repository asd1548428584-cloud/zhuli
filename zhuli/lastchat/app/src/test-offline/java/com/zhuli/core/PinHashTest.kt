package com.zhuli.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T-E3 / D13：PIN 哈希从「固定盐 + 单轮 SHA-256」换成 PBKDF2。
 *
 * 旧格式的问题：4 位 PIN 只有 1 万种组合，固定盐意味着可以预先算表，离线瞬间还原。
 * 这里锁住三件事：新格式带随机盐、同一 PIN 两次结果不同、旧哈希仍能校验（升级兼容）。
 */
class PinHashTest {

    @Test
    fun `新格式是 pbkdf2 且随机盐`() {
        val a = Backup.hashPin("1234")
        val b = Backup.hashPin("1234")
        assertTrue("应以 pbkdf2: 开头：$a", a.startsWith("pbkdf2:"))
        assertFalse("同一 PIN 两次哈希不应相同（盐随机）", a == b)
        assertTrue("迭代次数应为 120000：$a", a.startsWith("pbkdf2:120000:"))
    }

    @Test
    fun `正确 PIN 通过 错误 PIN 不通过`() {
        val stored = Backup.hashPin("246810")
        assertTrue(Backup.verifyPin("246810", stored))
        assertFalse(Backup.verifyPin("246811", stored))
        assertFalse(Backup.verifyPin("", stored))
    }

    @Test
    fun `旧格式仍可校验且被标记为需升级`() {
        val legacy = Backup.hashPinLegacy("1234")
        assertTrue("旧哈希应能验通过", Backup.verifyPin("1234", legacy))
        assertFalse(Backup.verifyPin("1235", legacy))
        assertTrue("旧格式应提示需要升级", Backup.pinNeedsUpgrade(legacy))
        assertFalse("新格式不该再升级", Backup.pinNeedsUpgrade(Backup.hashPin("1234")))
    }

    @Test
    fun `损坏的哈希不崩且判失败`() {
        listOf("pbkdf2:", "pbkdf2:abc:x:y", "pbkdf2:120000:!!!:!!!").forEach {
            assertFalse("应判失败：$it", Backup.verifyPin("1234", it))
        }
    }
}
