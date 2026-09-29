package app.vaulttasks.domain

/**
 * Lossless view of a file: BOM flag + lines each with their own terminator ("", "\n", "\r\n").
 * parse(s).toString() == s for every s (§4.2). Callers must decode bytes as UTF-8 WITHOUT
 * stripping U+FEFF.
 */
class VaultText private constructor(val bom: Boolean, val lines: List<Line>) {
    data class Line(val text: String, val eol: String)

    /** First terminator found in the file; "\n" if none. */
    val eolStyle: String get() = lines.firstOrNull { it.eol.isNotEmpty() }?.eol ?: "\n"

    fun replaceLine(index: Int, text: String) =
        VaultText(bom, lines.toMutableList().also { it[index] = it[index].copy(text = text) })

    fun removeLine(index: Int) =
        VaultText(bom, lines.toMutableList().also { it.removeAt(index) })

    /** Undo support: insert at [index]; past the end it behaves like [appendLine]. */
    fun insertLine(index: Int, text: String): VaultText {
        if (index >= lines.size) return appendLine(text)
        return VaultText(bom, lines.toMutableList().also { it.add(index, Line(text, eolStyle)) })
    }

    /** Ensures a preceding newline; keeps "no trailing newline" files that way. */
    fun appendLine(text: String): VaultText {
        val ls = lines.toMutableList()
        val last = ls.lastOrNull()
        val newEol = if (last != null && last.eol.isEmpty()) {
            ls[ls.size - 1] = last.copy(eol = eolStyle)
            ""
        } else eolStyle
        ls.add(Line(text, newEol))
        return VaultText(bom, ls)
    }

    override fun toString(): String = buildString {
        if (bom) append('\uFEFF')
        for (l in lines) append(l.text).append(l.eol)
    }

    companion object {
        fun parse(s: String): VaultText {
            val bom = s.startsWith('\uFEFF')
            val body = if (bom) s.substring(1) else s
            val lines = ArrayList<Line>()
            var start = 0
            while (start < body.length) {
                val nl = body.indexOf('\n', start)
                if (nl < 0) {
                    lines += Line(body.substring(start), "")
                    break
                }
                val crlf = nl > start && body[nl - 1] == '\r'
                lines += Line(body.substring(start, if (crlf) nl - 1 else nl), if (crlf) "\r\n" else "\n")
                start = nl + 1
            }
            return VaultText(bom, lines)
        }
    }
}
