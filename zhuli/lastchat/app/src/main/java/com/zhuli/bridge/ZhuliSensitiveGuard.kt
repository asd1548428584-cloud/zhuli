package com.zhuli.bridge

import com.zhuli.core.SensitiveGuard

/**
 * 新壳的敏感判定入口 —— **批次 A 起只做转发，不再自带一份正则**。
 *
 * 历史包袱：这个对象原先与 `com.zhuli.core.SensitiveGuard` 逐字重复（连注释都一样），
 * 两份规则各自演进的结果就是「旧壳修了地址空格、新壳还在漏」这类分叉缺陷。
 * 现在唯一实现是 [SensitiveGuard]，本对象只为保持既有调用点（`ZhuliPrivacy`、
 * `ZhuliPersonaBootstrap`）与测试的稳定而保留。
 */
object ZhuliSensitiveGuard {

    fun reason(text: String): String? = SensitiveGuard.reason(text)

    fun isSensitive(text: String): Boolean = SensitiveGuard.isSensitive(text)

    fun hits(text: String): List<String> = SensitiveGuard.hits(text)

    fun redact(text: String): String = SensitiveGuard.redact(text)
}
