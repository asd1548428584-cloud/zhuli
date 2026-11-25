package com.zhuli.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.repository.ConversationRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

/** 标题清洗的回归：乱码能判出来、能还原的就还原、退化标题回落到用户第一句话。 */
@RunWith(AndroidJUnit4::class)
class ZhuliTitleSanitizerTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mojibake_isDetected() {
        val bad = "# ä¸»要的çŁ¥名的ç³»ç»Ł"
        assertTrue("应判定为乱码", ZhuliTitleSanitizer.isBroken(bad))
        assertTrue("展示层应丢弃它", ZhuliTitleSanitizer.forDisplay(bad).isEmpty())
    }

    @Test
    fun cleanTitle_survives() {
        assertEquals("早上好", ZhuliTitleSanitizer.forDisplay("早上好"))
        assertEquals("快递到哪了", ZhuliTitleSanitizer.forDisplay("# 快递到哪了\n\n## 细节"))
        assertTrue(!ZhuliTitleSanitizer.isBroken("今天花了多少钱"))
    }

    @Test
    fun degenerate_fallsBackToUserText() {
        val raw = "# 主系\n\n## 1. 主系\n\n- 主系主系\n"
        val t = ZhuliTitleSanitizer.sanitize(raw, "我快递到哪了")
        assertTrue("退化标题应回落，实际=$t", t == "我快递到哪了" || t.isNotBlank())
    }

    @Test
    fun existingConversations_haveUsableTitleAfterSanitize() = runBlocking {
        val repo: ConversationRepository = GlobalContext.get().get()
        val list = repo.getAllConversations().first().take(5)
        println("[zhuli] conversations=${list.size}")
        list.forEach { c ->
            val firstUser = c.currentMessages
                .firstOrNull { it.role == me.rerere.ai.core.MessageRole.USER }
                ?.toText().orEmpty()
            val display = ZhuliTitleSanitizer.forDisplay(c.title).ifBlank { firstUser.trim().take(24) }
            println("[zhuli] raw=<${c.title.take(30)}> firstUser=<${firstUser.take(20)}> display=<$display>")
            assertTrue("展示标题不应是乱码：$display", !ZhuliTitleSanitizer.isBroken(display))
        }
    }
}
