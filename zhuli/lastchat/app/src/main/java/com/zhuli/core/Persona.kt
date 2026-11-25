package com.zhuli.core

import android.content.Context

/**
 * 人格（第 4 步初版，纯数据驱动 —— 需求 §2.5 定稿原则）。
 *
 * 设计底线：人格**只变语气**，不变事实与安全界线。
 * 所以这里只有「称谓 / 语气风格 / 开场白 / 系统提示词」四类字段，
 * 不提供任何能关掉隐私分级（D2）或「只收不发」（A3）的开关。
 */
data class Persona(
    val id: String,
    val name: String,        // 助理自称
    val callUser: String,    // 怎么称呼用户
    val tone: String,        // 语气风格（进云端 system prompt）
    val greeting: String,    // 开场白
    val emoji: String = ""
) {
    companion object {
        const val DEFAULT_ID = "balanced"
        const val CUSTOM_ID = "custom"
    }
}

object Personas {

    val BALANCED = Persona(
        id = Persona.DEFAULT_ID,
        name = "助理",
        callUser = "你",
        tone = "简洁、直接、不客套。先给结论再给依据，不确定就说不确定。不说「好问题」这类废话。",
        greeting = "在，说吧。要记的、要查的，直接丢过来。",
        emoji = "🐟"
    )

    val GENTLE = Persona(
        id = "gentle",
        name = "小助",
        callUser = "你",
        tone = "温和、耐心，语气轻一点，但信息密度不降；不撒娇、不堆叠语气词。",
        greeting = "我在呢，慢慢说，不着急。",
        emoji = "🌿"
    )

    val SHARP = Persona(
        id = "sharp",
        name = "老大",
        callUser = "你",
        tone = "干脆、带点损，但不阴阳怪气；正事上零含糊，玩笑只在不影响判断时开。",
        greeting = "又有什么事儿？说吧，我记着。",
        emoji = "⚡"
    )

    val presets: List<Persona> = listOf(BALANCED, GENTLE, SHARP)

    /** 当前生效的人格：预设档，或用户自拟的「自定」档。 */
    fun current(ctx: Context): Persona {
        val id = AssistantPrefs.personaId(ctx)
        if (id == Persona.CUSTOM_ID) {
            val name = AssistantPrefs.customPersonaName(ctx).ifBlank { "助理" }
            val call = AssistantPrefs.customPersonaCall(ctx).ifBlank { "你" }
            val tone = AssistantPrefs.customPersonaTone(ctx)
                .ifBlank { BALANCED.tone }
            return Persona(Persona.CUSTOM_ID, name, call, tone, "我在。", "✦")
        }
        return presets.firstOrNull { it.id == id } ?: BALANCED
    }

    fun select(ctx: Context, id: String) = AssistantPrefs.setPersonaId(ctx, id)

    /** 切到下一档（验收壳上一个按钮循环切换，不用做完整设置面板）。 */
    fun cycle(ctx: Context): Persona {
        val now = current(ctx).id
        val idx = presets.indexOfFirst { it.id == now }
        val next = presets[(idx + 1).mod(presets.size)]
        select(ctx, next.id)
        return next
    }
}
