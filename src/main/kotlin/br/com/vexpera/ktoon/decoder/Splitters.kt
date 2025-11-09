package br.com.vexpera.ktoon.decoder

/**
 * Utilitários para dividir strings respeitando aspas e escapes.
 */
internal object Splitters {

    /**
     * Divide uma string respeitando aspas duplas e escapes.
     * Exemplo:
     *  splitRespectingQuotes("a,\"b,c\",d", ',') → ["a", "\"b,c\"", "d"]
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
 * Encontra o primeiro índice de um caractere fora de aspas.
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
 * Encontra o índice do primeiro ':' fora de aspas (usado para key-value parsing).
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
