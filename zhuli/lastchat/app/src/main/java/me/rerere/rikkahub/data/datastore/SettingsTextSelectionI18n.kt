package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.TextSelectionAction

/**
 * 把内置的四条快捷指令（翻译/解释/总结/随便问）从中文化。
 *
 * 背景：这四条预设是**数据**不是资源，首次运行就写进了 DataStore，所以改
 * `DEFAULT_TEXT_SELECTION_ACTIONS` 只影响新装用户，老用户界面上仍是英文。
 *
 * 判定条件刻意收紧：只有「id 命中 + 名字仍是英文默认值 + prompt 仍以英文默认开头」
 * 才改写。用户自己改过名字或 prompt 的一律不动。
 */
internal fun Settings.localizeTextSelectionPresets(): Settings {
    val actions = textSelectionConfig.actions
    if (actions.isEmpty()) return this

    var changed = false
    val mapped = actions.map { action ->
        val localized = action.localizedBuiltin()
        if (localized !== action) changed = true
        localized
    }
    if (!changed) return this
    return copy(textSelectionConfig = textSelectionConfig.copy(actions = mapped))
}

private fun TextSelectionAction.localizedBuiltin(): TextSelectionAction = when {
    id == "translate" && name == "Translate" && prompt.startsWith("You are a translator") ->
        copy(
            name = "翻译",
            prompt = "把用户提供的文字翻译成{{language}}。只输出译文，不要任何解释或备注。",
        )

    id == "explain" && name == "Explain" && prompt.startsWith("You are a helpful assistant. Explain") ->
        copy(
            name = "解释",
            prompt = "你是一个耐心的助手。用简单、容易理解的话解释下面这段文字。要简洁，但该说清楚的别省；有必要时举例说明。",
        )

    id == "summarize" && name == "Summarize" && prompt.startsWith("You are a summarization assistant") ->
        copy(
            name = "总结",
            prompt = "你是一个摘要助手。为下面这段文字写一份清晰的摘要，抓住要点和主要观点，简短但不要漏。",
        )

    id == "custom" && name == "Ask anything…" && prompt.startsWith("You are a helpful assistant. Answer") ->
        copy(
            name = "随便问…",
            prompt = "你是一个助手。针对下面这段文字回答用户的问题。\n用户的问题：{{custom_prompt}}",
        )

    else -> this
}
