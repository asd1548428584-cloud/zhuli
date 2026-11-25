package com.zhuli.bridge


/**
 * 跑在 Shizuku server 进程内的 shell 用户服务（UID 2000，等同 adb 权限）。
 * 豁免 Android 10+ 后台剪贴板等限制；只接受白名单命令的执行请求。
 */
class ZhuliShellService : IZhuliShellService.Stub() {

    override fun exec(command: String): String? = try {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        buildString {
            if (out.isNotBlank()) append(out)
            if (err.isNotBlank()) append("\n[err] ").append(err)
            // 退出码必须带回来：pm grant 失败时只往 stderr 写一行，光看"有没有输出"会把失败当成功
            append("\n[exit=").append(code).append("]")
        }.trim()
    } catch (t: Throwable) {
        "FAILED: ${t.message}\n[exit=-1]"
    }

    override fun destroy() {
        // Shizuku 回收服务时调用；无状态，无需清理
    }
}
