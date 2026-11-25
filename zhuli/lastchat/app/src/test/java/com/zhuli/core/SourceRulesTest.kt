package com.zhuli.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 来源面板的显示名降级（2026-11-21 真机抓到的 bug）。
 *
 * 真机 Z70 Ultra / Android 16 上，来源管理面板把第三方 App 显示成包名尾巴：
 * bilibili → "in"、微信 → "mm"、QQ → "mobileqq"。原因是 Android 11+ 的包可见性限制
 * 让 `PackageManager.getApplicationInfo(第三方包, 0)` 抛异常，而库里 `app_label`
 * 从采集侧（通知监听）拿到的名字是准的，之前没用上。
 */
class SourceRulesTest {

    @Test
    fun `系统能查到名字时优先用系统名`() {
        // 系统 App / 自家 App 查得到 —— 电话短信存储这种名字库里的 app_label 反而不对
        assertEquals("电话和短信存储", SourceRules.pickLabel("电话和短信存储", "韵达短信", "com.android.providers.telephony"))
    }

    @Test
    fun `系统查不到时回落到库里记的名字`() {
        // 真机现场：PM 抛 NameNotFoundException，库里 app_label = "bilibili"
        assertEquals("bilibili", SourceRules.pickLabel(null, "bilibili", "com.bilibili.app.in"))
        assertEquals("微信", SourceRules.pickLabel("", "微信", "com.tencent.mm"))
    }

    @Test
    fun `两边都没有才用包名尾巴`() {
        assertEquals("vpn", SourceRules.pickLabel(null, null, "org.ikuuu.vpn"))
        assertEquals("lite", SourceRules.pickLabel("  ", "", "com.kugou.android.lite"))
    }

    @Test
    fun `没有点的包名原样返回`() {
        assertEquals("zhuli", SourceRules.pickLabel(null, null, "zhuli"))
    }
}
