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
 * Header to array, ex:
 * - key[3]{a,b,c}:
 * - [#2|]:
 * - key[4]:
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
        fun tryParse(lineContent: String): Header? {
            val colon = lineContent.firstUnquotedColonIndex()
            if (colon < 0) return null

            val left = lineContent.substring(0, colon).trimEnd()
            val right = lineContent.substring(colon + 1).trimStart()

            val idxOpen = left.indexOf('[')
            val idxClose = left.lastIndexOf(']')
            if (idxOpen < 0 || idxClose < 0 || idxClose < idxOpen) return null

            val maybeKey = left.substring(0, idxOpen).takeIf { it.isNotEmpty() }?.trim()
            val bracket = left.substring(idxOpen + 1, idxClose)
            val hasMarker = bracket.startsWith("#")
            val digitsStart = if (hasMarker) 1 else 0

            var delimSym: Char? = null
            var nDigitsEnd = bracket.length
            if (bracket.isNotEmpty()) {
                val last = bracket.last()
                if (last == '\t' || last == '|') {
                    delimSym = last
                    nDigitsEnd -= 1
                }
            }

            val numberPart = bracket.substring(digitsStart, nDigitsEnd)
            val n = numberPart.toIntOrNull() ?: return null
            val delim = Delim.fromBracketSymbol(delimSym)

            val afterBracket = left.substring(idxClose + 1).trim()
            var fields: List<String>? = null
            if (afterBracket.startsWith("{") && afterBracket.endsWith("}")) {
                fields = splitFields(
                    afterBracket.substring(1, afterBracket.length - 1),
                    delim
                )
            } else if (afterBracket.isNotEmpty()) {
                return null
            }

            return Header(
                key = maybeKey?.let { Keys.decodeKeyToken(it) },
                length = n,
                delim = delim,
                fields = fields,
                hasLengthMarker = hasMarker,
                inlineValues = right.takeIf { it.isNotEmpty() }
            )
        }

        fun parseOrThrow(lineContent: String, ctx: ParseContext): Header {
            val colon = lineContent.firstUnquotedColonIndex()
                .takeIf { it >= 0 }
                ?: throw DecodeException("Missing colon in header: ${lineContent.show()}")

            val left = lineContent.substring(0, colon).trimEnd()
            val right = lineContent.substring(colon + 1).trimStart()

            val idxOpen = left.indexOf('[')
            val idxClose = left.lastIndexOf(']')
            if (idxOpen < 0 || idxClose < 0 || idxClose < idxOpen)
                throw DecodeException("Invalid array header (missing [...]): ${lineContent.show()}")

            val maybeKey = left.substring(0, idxOpen).takeIf { it.isNotEmpty() }?.trim()
            val keyDecoded = maybeKey?.let { Keys.decodeKeyToken(it) }
            val bracket = left.substring(idxOpen + 1, idxClose)
            val hasMarker = bracket.startsWith("#")
            val digitsStart = if (hasMarker) 1 else 0

            var delimSym: Char? = null
            var nDigitsEnd = bracket.length
            if (bracket.isNotEmpty()) {
                val last = bracket.last()
                if (last == '\t' || last == '|') {
                    delimSym = last
                    nDigitsEnd -= 1
                }
            }

            val numberPart = bracket.substring(digitsStart, nDigitsEnd)
            val n = numberPart.toIntOrNull()
                ?: throw DecodeException("Invalid array length in header: '$numberPart'")

            val delim = Delim.fromBracketSymbol(delimSym)
            val afterBracket = left.substring(idxClose + 1).trim()

            var fields: List<String>? = null
            if (afterBracket.isNotEmpty()) {
                if (afterBracket.startsWith("{") && afterBracket.endsWith("}")) {
                    fields = splitFields(
                        afterBracket.substring(1, afterBracket.length - 1),
                        delim
                    )
                } else {
                    throw DecodeException("Invalid header fields segment: ${afterBracket.show()}")
                }
            }

            val hdr = Header(
                key = keyDecoded,
                length = n,
                delim = delim,
                fields = fields,
                hasLengthMarker = hasMarker,
                inlineValues = right.takeIf { it.isNotEmpty() }
            )

            ctx.debug(null, "Header.parseOrThrow → $hdr")
            return hdr
        }

        private fun splitFields(s: String, delim: Delim): List<String> {
            val raw = Splitters.splitRespectingQuotes(s, delim.ch)
            return raw.map { Keys.decodeKeyToken(it.trim()) }
        }
    }
}
