package com.buse.smartfreeze.ml.json

/**
 * Minimal, strict JSON parser used to read the exported model bundle.
 *
 * Why not org.json or kotlinx.serialization?
 *  - org.json is part of the Android framework but is stubbed out in local JVM unit tests,
 *    which would prevent the Python/Kotlin parity test from running on a laptop.
 *  - kotlinx.serialization adds a compiler plugin and reflection-free codegen we don't need.
 * This parser has no dependencies, rejects malformed input with [JsonException], and limits
 * nesting depth so a corrupted model file cannot exhaust the stack.
 *
 * Mapping: object -> Map<String, Any?>, array -> List<Any?>, number -> Double or Long,
 * string -> String, true/false -> Boolean, null -> null.
 */
class JsonException(message: String) : RuntimeException(message)

object MiniJson {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) throw JsonException("trailing characters at ${p.pos}")
        return v
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("nesting too deep")
            if (pos >= s.length) throw JsonException("unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("invalid literal at $pos")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++ // {
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw JsonException("expected key at $pos")
                val k = str()
                skipWs()
                expect(':')
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (pos >= s.length) throw JsonException("unterminated object")
                if (s[pos] == ',') { pos++; continue }
                if (s[pos] == '}') { pos++; return m }
                throw JsonException("expected ',' or '}' at $pos")
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++ // [
            val l = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) throw JsonException("unterminated array")
                if (s[pos] == ',') { pos++; continue }
                if (s[pos] == ']') { pos++; return l }
                throw JsonException("expected ',' or ']' at $pos")
            }
        }

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) throw JsonException("expected '$c' at $pos")
            pos++
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw JsonException("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toIntOrNull(16)?.toChar()
                                    ?: throw JsonException("bad unicode escape"))
                                pos += 4
                            }
                            else -> throw JsonException("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw JsonException("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') { isDouble = true; pos++; while (pos < s.length && s[pos] in '0'..'9') pos++ }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true; pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val t = s.substring(start, pos)
            if (t == "-" || t.isEmpty()) throw JsonException("bad number at $start")
            return if (isDouble) t.toDouble() else (t.toLongOrNull() ?: t.toDouble())
        }
    }
}

// Typed accessors that throw JsonException (instead of ClassCastException) on bad input.
@Suppress("UNCHECKED_CAST")
fun Any?.asObj(what: String): Map<String, Any?> = this as? Map<String, Any?> ?: throw JsonException("$what: expected object")
@Suppress("UNCHECKED_CAST")
fun Any?.asList(what: String): List<Any?> = this as? List<Any?> ?: throw JsonException("$what: expected array")
fun Any?.asDouble(what: String): Double = (this as? Number)?.toDouble() ?: throw JsonException("$what: expected number")
fun Any?.asDoubleOrNaN(): Double = (this as? Number)?.toDouble() ?: Double.NaN
fun Any?.asInt(what: String): Int {
    val n = this as? Number ?: throw JsonException("$what: expected integer")
    val d = n.toDouble()
    if (d != Math.floor(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) throw JsonException("$what: expected integer")
    return d.toInt()
}
fun Any?.asString(what: String): String = this as? String ?: throw JsonException("$what: expected string")
