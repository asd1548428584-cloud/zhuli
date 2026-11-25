package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 批次 A：Android 安全配置的回归测试。
 *
 * 这些配置一旦被改回去（`usesCleartextTraffic=true`、`allowBackup=true`、
 * 备份规则放行数据库），应用层写得再严也等于白做 —— 明文流量在系统层就放行了。
 * 所以用测试把「系统层也必须严格」钉住。
 *
 * 读文件而不是反射：这些是**构建期常量**，单元测试里没有可用的 Context；
 * 直接读源码树反而更接近"提交前检查"的语义。
 */
class ManifestSecurityTest {

    private val moduleDir = sequenceOf(File("."), File("app"), File("zhuli/lastchat/app"))
        .map { it.absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
        ?: error("找不到 app 模块目录（工作目录=${File(".").absolutePath}）")

    private fun resXml(name: String): File =
        File(moduleDir, "src/main/res/xml/$name").also {
            assertTrue("缺少 $name：${it.absolutePath}", it.isFile)
        }

    private fun manifest(): Element {
        val f = File(moduleDir, "src/main/AndroidManifest.xml")
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        return doc.getElementsByTagName("application").item(0) as Element
    }

    private fun Element.child(tag: String, androidName: String): Element? {
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.getAttribute("android:name") == androidName) return e
        }
        return null
    }

    @Test
    fun `应用不允许明文流量且指向网络安全配置`() {
        val app = manifest()
        assertEquals("usesCleartextTraffic 必须是 false", "false", app.getAttribute("android:usesCleartextTraffic"))
        assertEquals(
            "必须引用 networkSecurityConfig",
            "@xml/network_security_config",
            app.getAttribute("android:networkSecurityConfig"),
        )
    }

    @Test
    fun `网络安全配置默认禁止明文只放行本机回环`() {
        val text = resXml("network_security_config.xml").readText()
        assertTrue("base-config 必须禁止明文", text.contains("""<base-config cleartextTrafficPermitted="false">"""))
        assertTrue("必须放行 127.0.0.1", text.contains("<domain includeSubdomains=\"false\">127.0.0.1</domain>"))
        assertTrue("必须放行 localhost", text.contains("<domain includeSubdomains=\"false\">localhost</domain>"))
    }

    @Test
    fun `应用数据不参与云备份与换机迁移`() {
        val app = manifest()
        assertEquals("allowBackup 必须是 false", "false", app.getAttribute("android:allowBackup"))

        // 即使将来有人把 allowBackup 打开，规则也必须排除数据库与偏好设置
        val extract = resXml("data_extraction_rules.xml").readText()
        assertTrue("data_extraction_rules 必须排除 database", extract.contains("""<exclude domain="database" path="." />"""))
        assertTrue("data_extraction_rules 必须排除 sharedpref", extract.contains("""<exclude domain="sharedpref" path="." />"""))

        val legacy = resXml("backup_rules.xml").readText()
        assertTrue("backup_rules 必须排除 database", legacy.contains("""<exclude domain="database" path="." />"""))
        assertTrue("backup_rules 必须排除 sharedpref", legacy.contains("""<exclude domain="sharedpref" path="." />"""))
    }

    @Test
    fun `短信接收器必须带系统级权限保护`() {
        val app = manifest()
        val receiver = app.child("receiver", "com.zhuli.bridge.ZhuliSmsReceiver")
        assertNotNull("找不到 ZhuliSmsReceiver", receiver)
        assertEquals(
            "短信接收器必须要求 BROADCAST_SMS 权限",
            "android.permission.BROADCAST_SMS",
            receiver!!.getAttribute("android:permission"),
        )
    }

    @Test
    fun `内部广播接收器不得对外导出`() {
        val app = manifest()
        for (name in listOf(
            "com.zhuli.bridge.ZhuliAlarmReceiver",
            "com.zhuli.bridge.ZhuliReminderActionReceiver",
            "com.zhuli.core.AlarmReceiver",
            "com.zhuli.core.ReminderActionReceiver",
        )) {
            val r = app.child("receiver", name) ?: error("找不到 $name")
            assertEquals("$name 不应导出", "false", r.getAttribute("android:exported"))
        }
    }
}
