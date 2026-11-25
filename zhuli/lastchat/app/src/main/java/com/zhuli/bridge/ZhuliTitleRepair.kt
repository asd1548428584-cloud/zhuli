package com.zhuli.bridge

import android.content.Context
import kotlinx.coroutines.flow.first
import me.rerere.rikkahub.data.repository.ConversationRepository
import java.time.Instant

/**
 * 一次性清洗历史脏标题。
 *
 * 背景：早期会话的标题是让本地小模型生成的，跑飞之后写进库里的是整篇 markdown，
 * 还有一段被单字节解码搞成 `ä¸»要的…` 的鬼画符。只在展示层兜底不够 ——
 * 导出、编辑标题对话框、搜索结果都会看到它。这里直接把库里的值修掉：
 * 能干净还原就还原，还原不了就用用户第一句话。
 */
object ZhuliTitleRepair {

    private const val PREFS = "zhuli_title_repair"
    private const val K_DONE = "v1_done"

    suspend fun runOnce(ctx: Context, repo: ConversationRepository) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sp.getBoolean(K_DONE, false)) return

        val conversations = runCatching { repo.getAllConversations().first() }.getOrElse { return }
        var fixed = 0
        conversations.forEach { c ->
            val broken = ZhuliTitleSanitizer.isBroken(c.title)
            if (!broken) return@forEach
            val firstUser = c.currentMessages
                .firstOrNull { it.role == me.rerere.ai.core.MessageRole.USER }
                ?.toText().orEmpty()
            val newTitle = ZhuliTitleSanitizer.sanitize(c.title, firstUser)
            if (newTitle.isBlank()) return@forEach
            runCatching {
                repo.updateTitle(c.id, newTitle, Instant.ofEpochMilli(c.updateAt.toEpochMilli()))
                fixed++
            }
        }
        sp.edit().putBoolean(K_DONE, true).apply()
        if (fixed > 0) {
            com.zhuli.core.ZhuliLog.i("zhuli.title", "repaired $fixed broken conversation titles")
        }
    }
}
