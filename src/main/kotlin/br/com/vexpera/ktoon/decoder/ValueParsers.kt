package br.com.vexpera.ktoon.decoder

import java.math.BigDecimal

/**
 * Parser de valores primitivos e numéricos (§4, §9.1, §11).
 */
internal object ValueParsers {

    /**
     * Interpreta um token como o valor mais apropriado (string, número, boolean, null, etc).
     */
    fun parsePrimitiveToken(token: String, ctx: ParseContext, documentDelimiter: Char): Any? {
        val t = token.trim()
        if (t.isEmpty()) return "" // Inline arrays: token vazio → string vazia (§9.1)

        // String entre aspas
        if (t.startsWith("\"")) return parseQuotedStringStrict(t)

        // Literais reservados
        if (t == "true") return true
        if (t == "false") return false
        if (t == "null") return null

        // Números
        if (looksNumeric(t)) {
            // "05" → inválido como número (mantém string)
            if (Regex("^0\\d+$").matches(t)) return t
            return parseNumber(t)
        }

        // Fallback: string literal não-aspada
        return t
    }

    /**
     * Detecta se a string parece um número válido.
     */
    private fun looksNumeric(s: String): Boolean =
        Regex("^-?\\d+(?:\\.\\d+)?(?:[eE][+\\-]?\\d+)?$").matches(s)

    /**
     * Faz o parsing seguro de números (Long, BigDecimal ou Double).
     */
    private fun parseNumber(s: String): Number {
        // Tenta como Long primeiro
        s.toLongOrNull()?.let { return it }

        // Depois tenta BigDecimal
        return try {
            val bd = BigDecimal(s)
            val stripped = bd.stripTrailingZerosIfPossible()

            if (stripped.scale() <= 0) {
                stripped.toLongExactOrNull() ?: stripped
            } else {
                stripped
            }
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("Valor inválido para número: '$s'", e)
        }
    }

    /**
     * Faz parsing rigoroso de uma string entre aspas.
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
 * Extensão: remove zeros à direita de um BigDecimal de forma segura.
 */
internal fun BigDecimal.stripTrailingZerosIfPossible(): BigDecimal =
    try { this.stripTrailingZeros() } catch (_: Throwable) { this }

/**
 * Extensão: tenta converter um BigDecimal exato para Long sem lançar exceção.
 */
internal fun BigDecimal.toLongExactOrNull(): Long? =
    try { this.longValueExact() } catch (_: ArithmeticException) { null }
