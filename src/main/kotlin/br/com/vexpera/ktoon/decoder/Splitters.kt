package br.com.vexpera.ktoon.decoder

/**
 * Utilities for splitting strings while respecting quotation marks and escapes.
 */
internal object Splitters {

    /**
     * Split a string respecting double quotes and escapes.
     *
     * * Example:
     *
     * * splitRespectingQuotes("a,\"b,c\",d", ',') → ["a", "\"b,c\"", "d"]
     */
    fun splitRespectingQuotes(s: String, delim: Char): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0

        println("[Splitters] splitRespectingQuotes input='$s' delim='$delim'")

        while (i < s.length) {
            val c = s[i]

            if (c == '"') {
                inQuotes = !inQuotes
                sb.append(c)
                i++
                continue
            }

            if (c == '\\' && inQuotes) {
                if (i + 1 >= s.length)
                    throw DecodeException("Unterminated escape in quoted string")
                sb.append(c).append(s[i + 1])
                i += 2
                continue
            }

            if (!inQuotes && c == delim) {
                out += sb.toString()
                sb.clear()
                i++
                continue
            }

            sb.append(c)
            i++
        }

        out += sb.toString()

        println("[Splitters] result=$out")
        return out
    }
}

/**
 * Finds the first index of a character outside of quotation marks.
 */
internal fun String.firstUnquotedIndexOf(ch: Char): Int {
    var inQuotes = false
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '"') {
            inQuotes = !inQuotes
            i++
            continue
        }
        if (c == '\\' && inQuotes) {
            i += 2
            continue
        }
        if (!inQuotes && c == ch) return i
        i++
    }
    return -1
}

/**
 * Finds the index of the first ':' outside of quotation marks (used for key-value parsing).
 */
internal fun String.firstUnquotedColonIndex(): Int {
    var inQuotes = false
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c == '"') {
            inQuotes = !inQuotes
            i++
            continue
        }
        if (c == '\\' && inQuotes) {
            i += 2
            continue
        }
        if (!inQuotes && c == ':') return i
        i++
    }
    return -1
}
