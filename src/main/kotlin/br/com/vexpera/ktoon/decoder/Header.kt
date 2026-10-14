package br.com.vexpera.ktoon.decoder

/**
 * Valid delimiters to tabular arrays and inline (§9).
 */
internal enum class Delim(val ch: Char) {
    COMMA(','), TAB('\t'), PIPE('|');

    companion object {
        fun fromBracketSymbol(sym: Char?): Delim =
            when (sym) {
                null -> COMMA
                '\t' -> TAB
                '|' -> PIPE
                else -> error("Invalid delimiter symbol '$sym'")
            }
    }
}

/**
 * Array header (§6): `key[N<delim?>]{fields}: inline values`, the key being optional (root arrays and list items).
 */
internal data class Header(
    val key: String?,
    val length: Int,
    val delim: Delim,
    val fields: List<String>?,
    val hasLengthMarker: Boolean,
    val inlineValues: String?
) {
    companion object {
        /**
         * Parses [lineContent] as an array header, or returns null when the line is not shaped like one
         * (it is then a key-value line or a primitive). A quoted key may contain brackets (`"key[x]"[2]: …`).
         */
        fun tryParse(lineContent: String): Header? {
            val colon = lineContent.firstUnquotedColonIndex()
            if (colon < 0) return null

            val left = lineContent.substring(0, colon).trim()
            val right = lineContent.substring(colon + 1).trim()

            val key: String?
            val idxOpen: Int
            if (left.startsWith("\"")) {
                val closing = closingQuoteIndex(left)
                if (closing < 0 || closing + 1 >= left.length || left[closing + 1] != '[') return null
                key = ValueParsers.parseQuotedStringStrict(left.substring(0, closing + 1))
                idxOpen = closing + 1
            } else {
                idxOpen = left.indexOf('[')
                if (idxOpen < 0) return null
                val token = left.substring(0, idxOpen).trim()
                key = if (token.isEmpty()) null else token
            }

            val idxClose = left.indexOf(']', idxOpen)
            if (idxClose < 0) return null

            val bracket = left.substring(idxOpen + 1, idxClose)
            val hasMarker = bracket.startsWith("#")
            var delimSym: Char? = null
            var digitsEnd = bracket.length
            if (bracket.isNotEmpty() && (bracket.last() == '\t' || bracket.last() == '|')) {
                delimSym = bracket.last()
                digitsEnd -= 1
            }
            val numberPart = bracket.substring(if (hasMarker) 1 else 0, digitsEnd)
            if (numberPart.isEmpty() || !numberPart.all { it.isDigit() }) return null
            val n = numberPart.toIntOrNull() ?: return null
            val delim = Delim.fromBracketSymbol(delimSym)

            val afterBracket = left.substring(idxClose + 1).trim()
            val fields: List<String>? = when {
                afterBracket.isEmpty() -> null
                afterBracket.startsWith("{") && afterBracket.endsWith("}") ->
                    splitFields(afterBracket.substring(1, afterBracket.length - 1), delim)
                else -> return null
            }

            val inline = right.takeIf { it.isNotEmpty() }
            if (fields != null && inline != null)
                throw DecodeException("A tabular header cannot have inline values: ${lineContent.show()}")

            return Header(
                key = if (key == null || left.startsWith("\"")) key else Keys.decodeKeyToken(key),
                length = n,
                delim = delim,
                fields = fields,
                hasLengthMarker = hasMarker,
                inlineValues = inline
            )
        }

        private fun closingQuoteIndex(s: String): Int {
            var i = 1
            while (i < s.length) {
                when (s[i]) {
                    '\\' -> i += 2
                    '"' -> return i
                    else -> i++
                }
            }
            return -1
        }

        private fun splitFields(s: String, delim: Delim): List<String> {
            val raw = Splitters.splitRespectingQuotes(s, delim.ch)
            return raw.map { Keys.decodeKeyToken(it.trim()) }
        }
    }
}
