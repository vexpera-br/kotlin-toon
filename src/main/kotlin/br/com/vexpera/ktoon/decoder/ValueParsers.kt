package br.com.vexpera.ktoon.decoder

import java.math.BigDecimal

/**
 * Parser for primitive and numeric values (§4, §9.1, §11).
 */
internal object ValueParsers {

    /**
     * Interprets a token as the most appropriate value (string, number, boolean, null, etc).
     */
    fun parsePrimitiveToken(token: String, ctx: ParseContext, documentDelimiter: Char): Any? {

        ctx.debug(null, "parsePrimitiveToken: token='$token' delim='$documentDelimiter'")

        val t = token.trim()
        if (t.isEmpty()) {
            ctx.debug(null, "→ Empty string literal")
            return ""
        } // Inline arrays: empty token → empty string (§9.1)

        // String inside quotes
        if (t.startsWith("\"")) {
            ctx.debug(null, "→ string = '$t'")
            return parseQuotedStringStrict(t)
        }

        // Reserved literals
        if (t == "true") {
            ctx.debug(null, "→ boolean literal = true")
            return true
        }
        if (t == "false"){
            ctx.debug(null, "→ boolean literal = false")
            return false
        }
        if (t == "null") {
            ctx.debug(null, "→ null literal")
            return null
        }

        // Numbers
        if (looksNumeric(t)) {
            // "05" → invalid as number (keeps string)
            if (Regex("^0\\d+$").matches(t)) return t
            ctx.debug(null, "→ numeric $t")
            return parseNumber(t)
        }

        // Fallback: unquoted string literal
        ctx.debug(null, "→ fallback string = $t")
        return t
    }

    /**
     * Detects whether the string looks like a valid number.
     */
    private fun looksNumeric(s: String): Boolean =
        Regex("^-?\\d+(?:\\.\\d+)?(?:[eE][+\\-]?\\d+)?$").matches(s)

    /**
     * Safely parses numbers (Long, BigDecimal or Double).
     */
    private fun parseNumber(s: String): Number {
        // Try as Long first
        s.toLongOrNull()?.let { return it }

        // Then try BigDecimal
        return try {
            val bd = BigDecimal(s)
            val stripped = bd.stripTrailingZerosIfPossible()

            if (stripped.scale() <= 0) {
                stripped.toLongExactOrNull() ?: stripped
            } else {
                stripped
            }
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Invalid value for number: '$s'", e)
        }
    }

    /**
     * Strictly parses a quoted string.
     */
    fun parseQuotedStringStrict(tok: String): String {
        if (tok.length < 2 || !tok.startsWith("\"") || !tok.endsWith("\""))
            throw DecodeException("Unterminated string: ${tok.show()}")

        val inner = tok.substring(1, tok.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0

        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\') {
                if (i + 1 >= inner.length)
                    throw DecodeException("Unterminated escape at end of string")

                val n = inner[i + 1]
                when (n) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> throw DecodeException("Invalid escape sequence: \\$n")
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}

/**
 * Extension: safely removes trailing zeros from a BigDecimal.
 */
internal fun BigDecimal.stripTrailingZerosIfPossible(): BigDecimal =
    try { this.stripTrailingZeros() } catch (_: Throwable) { this }

/**
 * Extension: tries to convert an exact BigDecimal to Long without throwing an exception.
 */
internal fun BigDecimal.toLongExactOrNull(): Long? =
    try { this.longValueExact() } catch (_: ArithmeticException) { null }
