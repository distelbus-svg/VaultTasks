package app.vaulttasks.domain

/** Tiny JSON reader for test fixtures (no dependency). Objects → Map, arrays → List, numbers → Double. */
object MiniJson {
    fun parse(s: String): Any? = Reader(s).run { val v = value(); ws(); check(i == s.length) { "trailing data at $i" }; v }

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> { check(c == '-' || c.isDigit()) { "bad char '$c' at $i" }; num() }
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); check(s[i++] == ':'); m[k] = value(); ws()
                if (s[i] == ',') i++ else { check(s[i++] == '}'); return m }
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                if (s[i] == ',') i++ else { check(s[i++] == ']'); return l }
            }
        }
        fun str(): String {
            check(s[i++] == '"'); val sb = StringBuilder()
            while (true) {
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Double { val st = i; while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++; return s.substring(st, i).toDouble() }
    }
}
