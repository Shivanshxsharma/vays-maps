package com.shivansh.raasta.core

/**
 * Tiny JSON reader (objects -> Map, arrays -> List, numbers -> Double).
 * Kept in-house so the core has no dependencies and behaves the same on Android and the JVM.
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw IllegalArgumentException("Trailing data in JSON at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] <= ' ') i++
        }

        fun fail(what: String): Nothing = throw IllegalArgumentException("Bad JSON ($what) at $i")

        fun value(): Any? {
            ws()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("literal")
            i += word.length
            return v
        }

        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws()
                if (s[i++] != ':') fail("expected ':'")
                m[k] = value()
                ws()
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return m
                    else -> fail("object")
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value())
                ws()
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return l
                    else -> fail("array")
                }
            }
        }

        fun str(): String {
            if (s[i] != '"') fail("expected string")
            i++
            val start = i
            // Fast path: no escapes.
            while (i < s.length && s[i] != '"' && s[i] != '\\') i++
            if (i < s.length && s[i] == '"') return s.substring(start, i++)
            val sb = StringBuilder().append(s, start, i)
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            i++
            while (i < s.length && s[i].let { it in '0'..'9' || it == '.' || it == 'e' || it == 'E' || it == '+' || it == '-' }) i++
            return s.substring(start, i).toDouble()
        }
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

@Suppress("UNCHECKED_CAST")
internal fun Any?.asList(): List<Any?> = this as? List<Any?> ?: emptyList()

internal fun Map<String, Any?>.d(key: String): Double = when (val v = this[key]) {
    is Double -> v
    is String -> v.toDouble()
    else -> Double.NaN
}

internal fun Map<String, Any?>.s(key: String): String? = this[key] as? String
