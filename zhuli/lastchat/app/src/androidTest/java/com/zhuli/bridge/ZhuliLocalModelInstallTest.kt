package com.zhuli.bridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.rerere.locallm.LocalModelDefaultConfig
import me.rerere.locallm.LocalModelKind
import me.rerere.locallm.LocalModelStore
import me.rerere.locallm.InstalledLocalModel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * T-G2/B1-方案A：把手工 push 到真机的本地模型登记进 LastChat 的 LocalModelStore。
 *
 * 背景：App 内置下载走 huggingface.co（本机不通），改走 hf-mirror 在 PC 下载后 adb push，
 * 本测试以 App 进程身份拷贝 + upsert 安装记录，让模型出现在设置页 / assistant 模型选择器。
 *
 * 当前模型：Gemma-4-E2B-it（原生 function calling；Qwen2.5 无 tool 模板不识别工具、SmolLM2 中文乱码均弃用）。
 */
@RunWith(AndroidJUnit4::class)
class ZhuliLocalModelInstallTest {

    private val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    private val fileName = "Qwen3-0.6B.litertlm"
    private val modelId = "litert-community/Qwen3-0.6B-litert-lm/$fileName"
    private val commitHash = "local-push"
    private val size = 613_722_264L

    @Test
    fun install_localModelRecord_pointingToPushedFile() = runBlocking {
        val destDir = File(ctx.filesDir, "local_models")
        destDir.mkdirs()
        val dest = File(destDir, fileName)

        // 若 push 到了 /data/local/tmp，测试进程内拷进去
        val pushed = File("/data/local/tmp/$fileName")
        if (!dest.exists() && pushed.exists()) {
            pushed.inputStream().use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
        }
        assertTrue("模型文件应已就位（先 adb push 到 /data/local/tmp）", dest.exists())
        assertEquals("模型大小应为 $size，实际 ${dest.length()}", size, dest.length())

        val store: LocalModelStore = GlobalContext.get().get()
        store.upsert(
            InstalledLocalModel(
                id = modelId,
                displayName = "Qwen3 0.6B (zhuli local)",
                kind = LocalModelKind.LLM,
                filePath = dest.absolutePath,
                commitHash = commitHash,
                sizeInBytes = dest.length(),
                minDeviceMemoryGb = 8,
                defaultConfig = LocalModelDefaultConfig(
                    topK = 64, topP = 0.95f, temperature = 1.0f,
                    maxContextLength = 4096, maxTokens = 1024,
                ),
                imported = true,
            )
        )
        val installed = store.get(modelId)
        assertNotNull("安装记录应写入", installed)
        assertEquals(dest.absolutePath, installed!!.filePath)
    }

    @Test
    fun verify_existingRecord_orFile() = runBlocking {
        val dest = File(ctx.filesDir, "local_models/$fileName")
        val store: LocalModelStore = GlobalContext.get().get()
        val rec = store.get(modelId)
        assertTrue("模型文件或记录应至少有一个就位（file=${dest.exists()} record=${rec != null}）",
            dest.exists() || rec != null)
    }
}
