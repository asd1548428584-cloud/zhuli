package com.zhuli.bridge

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * T-G2/B8 S2：Shizuku 授权向导执行器（IUserService 模式，ClipStack 同款）。
 *
 * 在 Shizuku server 进程内跑一个 shell（UID 2000，等同 adb）用户服务，
 * 通过 [ZhuliShellService] 执行白名单授权命令，免去用户逐项点系统设置。
 * 安全边界：只执行本文件白名单里的命令，不接受外部输入拼命令。
 */
object ZhuliShizukuRunner {

    const val PKG = "lastchat.rikkafork.cocolal.dev"
    const val LISTENER = "$PKG/me.rerere.rikkahub.service.AssistantNotificationListener"

    data class Command(val id: String, val label: String, val shell: String)

    val COMMANDS: List<Command> = listOf(
        Command("battery", "电池优化豁免（保活）", "dumpsys deviceidle whitelist +$PKG"),
        Command(
            "calendar",
            "日历读写授权（提醒写回系统日历）",
            "pm grant $PKG android.permission.READ_CALENDAR; pm grant $PKG android.permission.WRITE_CALENDAR",
        ),
        // 短信通道（B8 补回）：只用来解析取件码，不是通用短信读取
        Command(
            "sms",
            "短信权限（取件码入库）",
            "pm grant $PKG android.permission.READ_SMS; pm grant $PKG android.permission.RECEIVE_SMS",
        ),
        Command("exact_alarm", "精确闹钟特殊权限", "appops set $PKG SCHEDULE_EXACT_ALARM allow"),
        Command("listener", "通知使用权（重装后一键恢复）", "cmd notification allow_listener $LISTENER"),
    )

    fun isShizukuAvailable(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 真机实测缺口：只在界面上写「请打开 Shizuku 授权」是死路 —— 本 App 从没调用过
     * requestPermission，Shizuku 那边根本不会出现本应用的授权项。这里补上请求入口。
     */
    fun requestPermission(requestCode: Int = SHIZUKU_REQUEST_CODE) {
        runCatching {
            if (Shizuku.isPreV11()) return
            if (!Shizuku.pingBinder()) return
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return
            Shizuku.requestPermission(requestCode)
        }
    }

    const val SHIZUKU_REQUEST_CODE = 4201

    fun isShizukuInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) != null
    }.getOrDefault(false)

    @Volatile
    private var service: IZhuliShellService? = null

    /** 绑定用户服务（Shizuku 授权后调用）。 */
    fun ensureService() {
        if (service != null) return
        synchronized(this) {
            if (service != null) return
            // 真机崩溃修复（2026-11-22）：Shizuku 的 UserServiceArgs 必须给进程名后缀，
            // 不给会在 bindUserService 里抛 NPE「process name suffix must not be null」→ 直接闪退。
            val args = Shizuku.UserServiceArgs(ComponentName(PKG, ZhuliShellService::class.java.name))
                .processNameSuffix("zhuli")
                .version(1)
            // 绑定是异步的，但参数错误是同步抛的；兜住，别让一次点击把整个 App 带走
            runCatching {
                Shizuku.bindUserService(
                    args,
                    object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            service = IZhuliShellService.Stub.asInterface(binder)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            service = null
                        }
                    },
                )
            }
        }
    }

    /** 执行一条白名单命令。返回 Pair(成功, 输出摘要)。 */
    fun run(cmd: Command): Pair<Boolean, String> {
        ensureService()
        // 绑定是异步的：首次点击时 binder 还没回来，等最多 3 秒，别一上来就报"未绑定"
        var waited = 0
        while (service == null && waited < 3000) {
            Thread.sleep(100)
            waited += 100
        }
        val svc = service
            ?: return Pair(false, "用户服务未绑定（3 秒内没连上，重试或重启 Shizuku 服务）")
        return runCatching {
            val out = svc.exec(cmd.shell) ?: ""
            val exit = Regex("\\[exit=(-?\\d+)]").find(out)?.groupValues?.get(1)?.toIntOrNull()
            val body = out.replace(Regex("\\[exit=-?\\d+]"), "").trim()
            when (exit) {
                0 -> Pair(true, body.ifBlank { "OK" })
                null -> Pair(false, "没有拿到退出码：${body.take(160)}")
                else -> Pair(false, "退出码 $exit：${body.take(160)}")
            }
        }.getOrElse { Pair(false, "执行失败：${it.message}") }
    }

    /** 全部命令一键执行。返回 (id to 成功) 列表。 */
    fun runAll(): List<Pair<String, Boolean>> =
        COMMANDS.map { cmd -> cmd.id to run(cmd).first }
}
