package com.zhuli.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机手工验证辅助：把中文写进剪贴板。
 * 原因：`adb shell input text` 只支持 ASCII，星云OS 自带输入法的中文候选栏又抓不到坐标，
 * 用「测试进程写剪贴板 + 界面长按粘贴」把中文送进聊天框，验证的是真实 UI 路径。
 */
@RunWith(AndroidJUnit4::class)
class ZhuliClipboardHelperTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private fun put(text: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("zhuli", text))
        val back = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        // Android 13+ 后台进程读剪贴板会被拒（返回空），这里只记录，不当作失败
        println("[zhuli] clipboard <- $text / readback=<$back>")
        Thread.sleep(1500)
    }

    @Test
    fun putConfirm() = put("确认")

    @Test
    fun putCourierQuestion() = put("我快递到哪了")

    @Test
    fun putTodoQuestion() = put("我有哪些待办")
}
