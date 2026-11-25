package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Configuration for Android text selection integration.
 * Stores user preferences for the "Ask LastChat" popup.
 */
@Serializable
data class TextSelectionConfig(
    val assistantId: Uuid? = null, // null = use default assistant
    val actions: List<TextSelectionAction> = DEFAULT_TEXT_SELECTION_ACTIONS,
    val translateLanguage: String = "English"
)

/**
 * A configurable quick action button for text selection popup.
 */
@Serializable
data class TextSelectionAction(
    val id: String = Uuid.random().toString(),
    val name: String,
    val icon: String, // Material icon name (e.g., "Translate", "Lightbulb")
    val prompt: String,
    val modelId: Uuid? = null,
    val enabled: Boolean = true,
    val isCustomPrompt: Boolean = false // Special flag for "Ask anything" which shows input field
)

val DEFAULT_TEXT_SELECTION_ACTIONS = listOf(
    TextSelectionAction(
        id = "translate",
        name = "翻译",
        icon = "Translate",
        prompt = """
            把用户提供的文字翻译成 {{language}}。
            只输出译文，不要任何解释或备注。
        """.trimIndent()
    ),
    TextSelectionAction(
        id = "explain",
        name = "解释",
        icon = "Lightbulb",
        prompt = """
            你是一个耐心的助手。用简单、容易理解的话解释下面这段文字。
            要简洁，但该说清楚的别省；有必要时举例说明。
        """.trimIndent()
    ),
    TextSelectionAction(
        id = "summarize",
        name = "总结",
        icon = "Summarize",
        prompt = """
            你是一个摘要助手。为下面这段文字写一份清晰的摘要，抓住要点和主要观点。
            简短，但不要漏。
        """.trimIndent()
    ),
    TextSelectionAction(
        id = "custom",
        name = "随便问…",
        icon = "AutoAwesome",
        prompt = """
            你是一个助手。针对下面这段文字回答用户的问题。
            用户的问题：{{custom_prompt}}
        """.trimIndent(),
        isCustomPrompt = true
    )
)
