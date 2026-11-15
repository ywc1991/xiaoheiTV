package com.xiaohei.tv.data

/**
 * 极简只读 JSON 解析器。
 *
 * 为什么不用 org.json：它是 Android 运行时类，JVM 单测里拿不到，
 * 一旦解析写错只能上电视才发现；也不想为了一份几 KB 的目录文件引一个序列化库。
 * 这里手写递归下降，只做「文本 → Map / List / String / Double / Boolean / null」，
 * 不提供序列化（目录文件是人在 GitHub 上手改的，App 只读不写）。
 *
 * 语法错误一律抛 [JsonError]：目录是不可信输入，宁可整份丢掉用内置清单，
 * 也不要猜半份出来把列表搞乱。
 */
object MiniJson {

    class JsonError(message: String) : Exception(message)

    /** 嵌套层数上限：防一份畸形文件把栈打穿（目录实际只有 2 层） */
    private const val MAX_DEPTH = 16

    fun parse(text: String): Any? {
        val p = P(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (!p.eof()) throw JsonError("JSON 结尾有多余内容")
        return v
    }

    /** 便捷取值：JSON 对象里按 key 拿字符串，缺省或类型不对返回 "" */
    fun str(map: Map<String, Any?>, key: String): String =
        (map[key] as? String)?.trim().orEmpty()

    private class P(private val s: String) {
        var i = 0

        fun eof() = i >= s.length

        fun skipWs() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonError("嵌套太深")
            if (eof()) throw JsonError("意外的结尾")
            return when (val c = s[i]) {
                '{' -> obj(depth + 1)
                '[' -> arr(depth + 1)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonError("非法字符 '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonError("无法识别的字面量")
            i += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // 吃掉 '{'
            skipWs()
            if (!eof() && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (eof() || s[i] != '"') throw JsonError("对象的键必须是字符串")
                val k = str()
                skipWs()
                if (eof() || s[i] != ':') throw JsonError("缺少 ':'")
                i++
                skipWs()
                m[k] = value(depth)
                skipWs()
                if (eof()) throw JsonError("对象没有闭合")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw JsonError("对象里出现 '" + s[i] + "'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            val list = ArrayList<Any?>()
            i++ // 吃掉 '['
            skipWs()
            if (!eof() && s[i] == ']') { i++; return list }
            while (true) {
                skipWs()
                list.add(value(depth))
                skipWs()
                if (eof()) throw JsonError("数组没有闭合")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> throw JsonError("数组里出现 '" + s[i] + "'")
                }
            }
        }

        /** 字符串：支持 \" \\ \/ \b \f \n \r \t 与 \uXXXX（含代理对） */
        private fun str(): String {
            i++ // 吃掉起始引号
            val sb = StringBuilder()
            while (true) {
                if (eof()) throw JsonError("字符串没有闭合")
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (eof()) throw JsonError("转义没有内容")
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
                                if (i + 4 > s.length) throw JsonError("\\u 后面不足 4 位")
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: throw JsonError("\\u 不是十六进制")
                                sb.append(code.toChar())
                                i += 4
                            }
                            else -> throw JsonError("不支持的转义 \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        /** 数字：统一转 Double。目录里只有 version，够用了 */
        private fun num(): Double {
            val start = i
            if (!eof() && s[i] == '-') i++
            while (!eof() && (s[i] in '0'..'9' || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val raw = s.substring(start, i)
            return raw.toDoubleOrNull() ?: throw JsonError("不是合法数字：$raw")
        }
    }
}
