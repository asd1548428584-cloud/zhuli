package com.zhuli.core

/**
 * 极简 JSON —— 只为备份格式服务（T-E2）。
 *
 * ## 为什么不用 org.json
 * `core` 是纯 Kotlin，靠它才能在 JVM 单测里跑（见 build.gradle 注释）。但 `org.json` 是
 * android.jar 的一部分，在本地单测里是**空壳**：任何方法调用都抛 `Method ... not mocked`。
 * 结果就是"备份的写和读一行都测不了"——而备份这种东西，**能写不能读等于没有备份**。
 *
 * 自己实现一个够用的子集（对象 / 数组 / 字符串 / 数字 / 布尔 / null）:
 * - 没有依赖，单测能完整跑通"导 → 解析 → 行数核对"的闭环；
 * - 数字保留原始字面量（[Num.raw]），13 位毫秒时间戳不会经 Double 转手丢精度。
 *
 * 不追求 RFC 全文合规（不支持注释、单引号、尾随逗号），只保证**自己写出去的能自己读回来**，
 * 且遇到坏输入抛 [IllegalArgumentException] 而不是静默返回空——备份文件损坏要让人知道。
 */
sealed class Js {

    object Null : Js()
    data class Bool(val v: Boolean) : Js()

    /** 数字：存原始字面量，不做 Double 中转。 */
    data class Num(val raw: String) : Js()

    data class Str(val v: String) : Js()

    class Arr(val items: MutableList<Js> = mutableListOf()) : Js() {
        val size: Int get() = items.size
        fun add(v: Js): Arr { items.add(v); return this }
        fun strings(): List<String> = items.map { it.asStringOr() }
    }

    class Obj(val fields: LinkedHashMap<String, Js> = LinkedHashMap()) : Js() {
        fun put(key: String, v: Js): Obj { fields[key] = v; return this }
        fun get(key: String): Js? = fields[key]
        fun str(key: String, def: String = ""): String = fields[key]?.asStringOr(def) ?: def
        fun num(key: String, def: Long = 0L): Long = fields[key]?.asLongOr(def) ?: def
        fun dbl(key: String, def: Double = 0.0): Double = fields[key]?.asDoubleOr(def) ?: def
        fun obj(key: String): Obj? = fields[key] as? Obj
        fun arr(key: String): Arr? = fields[key] as? Arr
    }

    // ---------- 取值（永不抛异常，类型不对就给默认值） ----------

    fun asLongOr(def: Long = 0L): Long = when (this) {
        is Num -> raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: def
        is Bool -> if (v) 1L else 0L
        is Str -> v.toLongOrNull() ?: def
        else -> def
    }

    fun asDoubleOr(def: Double = 0.0): Double = when (this) {
        is Num -> raw.toDoubleOrNull() ?: def
        is Bool -> if (v) 1.0 else 0.0
        is Str -> v.toDoubleOrNull() ?: def
        else -> def
    }

    fun asStringOr(def: String = ""): String = when (this) {
        is Str -> v
        is Num -> raw
        is Bool -> v.toString()
        else -> def
    }

    fun asBoolOr(def: Boolean = false): Boolean = when (this) {
        is Bool -> v
        is Num -> raw.toDoubleOrNull()?.let { it != 0.0 } ?: def
        is Str -> v == "true" || v == "1"
        else -> def
    }

    /** 数字是否为整数值（决定还原时 bindLong 还是 bindDouble）。 */
    fun isIntegral(): Boolean = this is Num && raw.none { it == '.' || it == 'e' || it == 'E' }

    // ---------- 写 ----------

    fun write(): String = StringBuilder(1024).also { writeTo(it) }.toString()

    private fun writeTo(sb: StringBuilder) {
        when (this) {
            is Null -> sb.append("null")
            is Bool -> sb.append(if (v) "true" else "false")
            is Num -> sb.append(if (raw.isEmpty()) "0" else raw)
            is Str -> escape(v, sb)
            is Arr -> {
                sb.append('[')
                items.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    v.writeTo(sb)
                }
                sb.append(']')
            }
            is Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in fields) {
                    if (!first) sb.append(',')
                    first = false
                    escape(k, sb)
                    sb.append(':')
                    v.writeTo(sb)
                }
                sb.append('}')
            }
        }
    }

    private fun escape(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') {
                    sb.append("\\u")
                    val h = ch.code.toString(16)
                    repeat(4 - h.length) { sb.append('0') }
                    sb.append(h)
                } else sb.append(ch)
            }
        }
        sb.append('"')
    }

    // ---------- 便捷构造 ----------

    companion object {
        fun obj(vararg pairs: Pair<String, Js>): Obj =
            Obj(LinkedHashMap<String, Js>().apply { pairs.forEach { put(it.first, it.second) } })

        fun arr(items: List<Js>): Arr = Arr(items.toMutableList())

        fun of(v: String?): Js = if (v == null) Null else Str(v)
        fun of(v: Long): Js = Num(v.toString())
        fun of(v: Int): Js = Num(v.toString())
        fun of(v: Double): Js = if (v.isFinite()) Num(v.toString()) else Null
        fun of(v: Boolean): Js = Bool(v)

        /** 解析。坏输入一律抛 [IllegalArgumentException]（带位置），调用方决定怎么报错。 */
        fun parse(text: String): Js = Parser(text).run {
            val v = value()
            skipWs()
            if (!eof()) fail("末尾多余内容")
            v
        }
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun eof() = i >= s.length

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun fail(msg: String): Nothing = throw IllegalArgumentException("JSON 解析失败@$i：$msg")

        fun value(): Js {
            skipWs()
            if (eof()) fail("内容为空")
            return when (val c = s[i]) {
                '{' -> objects()
                '[' -> array()
                '"' -> Str(string())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> if (c == '-' || c in '0'..'9') number() else fail("非法字符 '$c'")
            }
        }

        private fun literal(word: String, v: Js): Js {
            if (!s.startsWith(word, i)) fail("期望 $word")
            i += word.length
            return v
        }

        private fun objects(): Obj {
            val o = Obj()
            i++ // {
            skipWs()
            if (!eof() && s[i] == '}') { i++; return o }
            while (true) {
                skipWs()
                if (eof() || s[i] != '"') fail("对象键必须是字符串")
                val k = string()
                skipWs()
                if (eof() || s[i] != ':') fail("缺少 ':'")
                i++
                o.fields[k] = value()
                skipWs()
                if (eof()) fail("对象未闭合")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return o }
                    else -> fail("对象里期望 ',' 或 '}'")
                }
            }
        }

        private fun array(): Arr {
            val a = Arr()
            i++ // [
            skipWs()
            if (!eof() && s[i] == ']') { i++; return a }
            while (true) {
                a.items.add(value())
                skipWs()
                if (eof()) fail("数组未闭合")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return a }
                    else -> fail("数组里期望 ',' 或 ']'")
                }
            }
        }

        private fun string(): String {
            i++ // 开引号
            val sb = StringBuilder()
            while (true) {
                if (eof()) fail("字符串未闭合")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (eof()) fail("转义未完成")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u 后面不足 4 位")
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: fail("\\u 不是十六进制：$hex")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> fail("未知转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Num {
            val start = i
            if (!eof() && s[i] == '-') i++
            while (!eof() && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                        ((s[i] == '+' || s[i] == '-') && (s[i - 1] == 'e' || s[i - 1] == 'E')))) i++
            val raw = s.substring(start, i)
            if (raw.isEmpty() || raw == "-") fail("数字为空")
            // 校验能被解析，避免把 "1.2.3" 这种垃圾当数字带回下游
            if (raw.toDoubleOrNull() == null) fail("非法数字 $raw")
            return Num(raw)
        }
    }
}
