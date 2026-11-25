package me.rerere.locallm

import android.content.Context
import android.os.Build
import java.io.File

/**
 * NPU（高通 HTP）的粗略可用性探测。
 *
 * 能上 NPU 需要同时满足三件事，这里只能判断前两件：
 * 1. 设备是高通平台（否则没有 HTP）；
 * 2. 系统或 App 的 native 目录里存在 QNN dispatch 库（libQnnHtp / libQnn*）；
 * 3. 模型文件里带 HTP 编译段 —— 这一点本地判断不了，只能"试一次"。
 *
 * 所以这里返回 true 只代表"值得一试"；真正失败会在 [LiteRtRuntime] 里被捕获，
 * 标记 `npuCrashed` 并回落到 CPU，不会让 App 卡死。
 */
object NpuSupport {

    fun isQualcomm(): Boolean {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER.orEmpty()
        } else {
            ""
        }.lowercase()
        val hardware = Build.HARDWARE.orEmpty().lowercase()
        return soc.contains("qualcomm") || hardware.contains("qcom") || hardware.startsWith("sm8")
    }

    fun hasQnnLibs(context: Context): Boolean {
        val dirs = listOfNotNull(
            context.applicationInfo?.nativeLibraryDir,
            "/vendor/lib64",
            "/vendor/lib",
            "/system/lib64",
            "/system/lib",
        )
        return dirs.any { dir ->
            runCatching {
                File(dir).list()?.any { name ->
                    name.contains("QnnHtp", ignoreCase = true) || name.contains("libQnn", ignoreCase = true)
                } == true
            }.getOrDefault(false)
        }
    }

    fun available(context: Context): Boolean = isQualcomm() && hasQnnLibs(context)
}
