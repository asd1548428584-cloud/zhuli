package com.zhuli.core

import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A7 文件日志。
 *
 * 为什么自写而不引 Timber/ACRA：本 ROM 的 logcat 恒空，崩溃后唯一的线索只能是落盘文件；
 * 而 ACRA 自带网络上报，和「隐私不出机」的项目硬约束冲突。这里只做三件事：
 *   1. 追加写 files/logs/zhuli.log（按大小滚动，目录是 App 私有，不给外部存储）
 *   2. 崩溃时单独落一份 crash-*.txt（含完整堆栈 + 末尾日志）
 *   3. 提供 readAll / crashFiles 给 App 内面板与系统分享
 *
 * **未 init 时全部是 no-op** —— JVM 单测不会因为它产生磁盘副作用。
 * 本文件不 import 任何 android.*，可直接单测。
 */
object ZhuliLog {
    private const val MAIN = "zhuli.log"
    private val MAX_DEFAULT = 512L * 1024L
    private const val KEEP_DEFAULT = 3

    @Volatile
    private var dir: File? = null
    private var maxBytes: Long = MAX_DEFAULT
    private var keep: Int = KEEP_DEFAULT
    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /** 在 Application.onCreate 调一次。filesDir 是 App 私有目录，不会进公共存储。 */
    fun init(
        filesDir: File,
        maxBytesPerFile: Long = MAX_DEFAULT,
        keepFiles: Int = KEEP_DEFAULT
    ) {
        synchronized(lock) {
            dir = File(filesDir, "logs").apply { mkdirs() }
            maxBytes = maxBytesPerFile.coerceAtLeast(16L * 1024L)
            keep = keepFiles.coerceIn(1, 10)
            write("I", "ZhuliLog", "=== log started ===")
        }
    }

    /** 仅测试用：卸掉目录，之后所有写都是 no-op。 */
    fun reset() {
        synchronized(lock) { dir = null }
    }

    fun logDir(): File? = dir

    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String) = write("W", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable?) = write("W", tag, msg, t)
    fun e(tag: String, msg: String) = write("E", tag, msg, null)
    fun e(tag: String, msg: String, t: Throwable?) = write("E", tag, msg, t)

    fun write(level: String, tag: String, msg: String, t: Throwable? = null) {
        synchronized(lock) {
            if (dir == null) return
            val line = "${fmt.format(Date())} $level/$tag: $msg\n"
            val sb = StringBuilder(line)
            if (t != null) {
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                sb.append(sw).append('\n')
            }
            appendLocked(sb.toString())
        }
    }

    private fun appendLocked(text: String) {
        val d = dir ?: return
        val main = File(d, MAIN)
        // 估算用 UTF-8 字节数，避免中文行明明超了却不滚
        val bytes = try {
            text.toByteArray(Charsets.UTF_8).size.toLong()
        } catch (_: Throwable) {
            text.length.toLong()
        }
        if (main.exists() && main.length() + bytes > maxBytes) rotateLocked()
        try {
            FileWriter(main, true).use { it.write(text) }
        } catch (_: Throwable) {
            // 日志失败绝不能反过来弄崩业务
        }
    }

    private fun rotateLocked() {
        val d = dir ?: return
        val main = File(d, MAIN)
        if (keep <= 1) {
            main.delete()
            return
        }
        File(d, "$MAIN.${keep - 1}").delete()
        for (i in keep - 2 downTo 1) {
            val from = File(d, "$MAIN.$i")
            if (from.exists()) from.renameTo(File(d, "$MAIN.${i + 1}"))
        }
        main.renameTo(File(d, "$MAIN.1"))
    }

    /** 由旧到新拼接所有日志；超长时只留最后 limitChars 字。 */
    fun readAll(limitChars: Int = Int.MAX_VALUE): String {
        synchronized(lock) {
            val d = dir ?: return ""
            val sb = StringBuilder()
            for (i in keep - 1 downTo 1) {
                val f = File(d, "$MAIN.$i")
                if (f.exists()) sb.append(readSafe(f))
            }
            val main = File(d, MAIN)
            if (main.exists()) sb.append(readSafe(main))
            val s = sb.toString()
            return if (s.length > limitChars) s.takeLast(limitChars) else s
        }
    }

    fun readTail(limitChars: Int = 20_000): String = readAll(limitChars)

    /** 崩溃落盘：单独文件，含完整堆栈 + 最近主日志（排障时往往尾部才有用）。 */
    fun recordCrash(t: Throwable, where: String = ""): File? {
        synchronized(lock) {
            val d = dir ?: return null
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
            val f = File(d, "crash-$stamp.txt")
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            pw.println("time: ${fmt.format(Date())}")
            pw.println("thread: ${Thread.currentThread().name}")
            pw.println("where: $where")
            pw.println("exception: ${t.javaClass.name}: ${t.message}")
            pw.println("--- stacktrace ---")
            t.printStackTrace(pw)
            pw.println("--- recent log (tail 4000 chars) ---")
            val main = File(d, MAIN)
            if (main.exists()) pw.println(readSafe(main).takeLast(4000))
            pw.flush()
            try {
                FileWriter(f, false).use { it.write(sw.toString()) }
                return f
            } catch (_: Throwable) {
                return null
            }
        }
    }

    fun crashFiles(): List<File> {
        val d = dir ?: return emptyList()
        return (d.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".txt") }
            ?: emptyArray()).sortedByDescending { it.lastModified() }
    }

    fun clear() {
        synchronized(lock) {
            val d = dir ?: return
            File(d, MAIN).delete()
            for (i in 1 until keep) File(d, "$MAIN.$i").delete()
        }
    }

    private fun readSafe(f: File): String = try {
        f.readText()
    } catch (_: Throwable) {
        ""
    }
}
