package br.com.vexpera.ktoon

data class EncodeOptions(
    val indent: Int = 2,
    val delimiter: Delimiter = Delimiter.COMMA,
    val lengthMarker: Boolean = false,
)

// ---------- Encoder ----------

class Encoder(private val options: EncodeOptions) {
    private val sb = StringBuilder()
    private val indentUnit = " ".repeat(options.indent)

    fun encode(value: Any?): String {
        writeValue(value, 0)
        return sb.toString().trimEnd()
    }

    private fun writeValue(v: Any?, level: Int) {
        when (v) {
            null -> sb.append("null\n")
            is Map<*, *> -> writeObject(v as Map<String, Any?>, level)
            is List<*> -> writeList(v as List<Any?>, level)
            is String -> sb.append(escape(v)).append('\n')
            is Number -> sb.append(formatCanonicalNumber(v)).append('\n')
            is Boolean -> sb.append(v.toString()).append('\n')
            else -> sb.append(escape(v.toString())).append('\n')
        }
    }

    private fun writeObject(map: Map<String, Any?>, level: Int) {
        for ((kAny, v) in map) {
            val k = kAny?.toString() ?: "-"  // ← trata chave null
            when (v) {
                is List<*> -> {
                    val hom = homogenize(v)
                    if (hom != null) {
                        val lengthPart = if (options.lengthMarker) "[#${v.size}]" else "[${v.size}]"
                        val cols = hom.first.joinToString(options.delimiter.symbol)
                        line(level, "$k$lengthPart{$cols}:")
                        val delim = options.delimiter.symbol
                        for (row in hom.second) {
                            line(level + 1, row.joinToString(delim) { renderCell(it) })
                        }
                        continue
                    }

                    val prim = v.all { it == null || it is String || it is Number || it is Boolean }
                    if (prim) {
                        val lengthPart = if (options.lengthMarker) "[#${v.size}]" else "[${v.size}]"
                        val joined = v.joinToString(options.delimiter.symbol) { renderCell(it) }
                        line(level, "$k$lengthPart: $joined")
                    } else {
                        val lengthPart = if (options.lengthMarker) "[#${v.size}]" else "[${v.size}]"
                        line(level, "$k$lengthPart:")
                        for (item in v) line(level + 1, "- ${renderScalarOrInline(item)}")
                    }
                }

                is Map<*, *> -> {
                    line(level, "$k:")
                    @Suppress("UNCHECKED_CAST")
                    writeObject(v as Map<String, Any?>, level + 1)
                }

                else -> line(level, "$k: ${renderScalarOrInline(v)}")
            }
        }
    }

    private fun writeList(list: List<Any?>, level: Int) {
        val hom = homogenize(list)
        if (hom != null) {
            val cols = hom.first.joinToString(options.delimiter.symbol)
            val lengthPart = if (options.lengthMarker) "[#${list.size}]" else "[${list.size}]"
            line(level, "items$lengthPart{$cols}:")
            val delim = options.delimiter.symbol
            for (row in hom.second) line(level + 1, row.joinToString(delim) { renderCell(it) })
            return
        }

        val prim = list.all { it == null || it is String || it is Number || it is Boolean }
        if (prim) {
            val lengthPart = if (options.lengthMarker) "[#${list.size}]" else "[${list.size}]"
            val joined = list.joinToString(options.delimiter.symbol) { renderCell(it) }
            line(level, "items$lengthPart: $joined")
        } else {
            line(level, "items:")
            for (item in list) line(level + 1, "- ${renderScalarOrInline(item)}")
        }
    }

    /**
     * Se a lista for homogênea de mapas com as mesmas chaves na mesma ordem,
     * retorna (colunas, linhas) para serializar como tabela.
     */
    private fun homogenize(list: List<Any?>): Pair<List<String>, List<List<Any?>>>? {
        if (list.isEmpty()) return null
        val maps = list.filterIsInstance<Map<*, *>>()
        if (maps.size != list.size) return null
        val firstKeys = maps.first().keys.map { it.toString() }
        if (firstKeys.isEmpty()) return null
        if (maps.any { it.keys.map { k -> k.toString() } != firstKeys }) return null
        val rows = maps.map { m -> firstKeys.map { k -> m[k] } }
        return firstKeys to rows
    }

    private fun renderScalarOrInline(v: Any?): String = when (v) {
        null -> "null"
        is String -> escape(v)
        is Number -> formatCanonicalNumber(v)
        is Boolean -> v.toString()
        else -> escape(v.toString())
    }

    private fun renderCell(v: Any?): String = when (v) {
        null -> "null"
        is String -> escape(v, quoteIfNeeded = false, forCell = true)
        is Number -> formatCanonicalNumber(v)
        is Boolean -> v.toString()
        else -> escape(v.toString(), quoteIfNeeded = false, forCell = true)
    }

    private fun line(level: Int, text: String) {
        repeat(level) { sb.append(indentUnit) }
        sb.append(text).append('\n')
    }

    /** Canonical number format per §2 of TOON Spec */
    private fun formatCanonicalNumber(n: Number): String {
        val d = n.toDouble()
        if (!d.isFinite()) return "null"
        val bd = try {
            java.math.BigDecimal(n.toString())
        } catch (_: Exception) {
            java.math.BigDecimal.valueOf(d)
        }
        val plain = bd.stripTrailingZeros().toPlainString()
        return if (plain == "-0") "0" else plain
    }
}

/**
 * Escapa caracteres especiais para uso seguro em TOON.
 *
 * @param s Texto original.
 * @param quoteIfNeeded Quando true, envolve em aspas se houver espaços, delimitadores, colons, tabs etc.
 * @param forCell Quando true, aplica escape leve para uso em células de tabelas (não adiciona aspas).
 */
private fun escape(s: String, quoteIfNeeded: Boolean = true, forCell: Boolean = false): String {
    if (s.isEmpty()) return "\"\"" // strings vazias sempre devem ser citadas

    val numericLike = s.matches(Regex("^-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$"))
    val leadingZero = s.matches(Regex("^0\\d+$"))

    // Regras de citação por §7.2
    val needsQuote = quoteIfNeeded && (
            s.first().isWhitespace() ||
                    s.last().isWhitespace() ||
                    s == "true" || s == "false" || s == "null" ||
                    numericLike || leadingZero ||
                    s.contains(':') ||
                    s.contains('"') ||
                    s.contains('\\') ||
                    s.contains('[') || s.contains(']') ||
                    s.contains('{') || s.contains('}') ||
                    s.contains('\t') ||
                    s.contains('|') ||
                    s.contains(',') ||
                    s == "-" || s.startsWith('-')
            )

    val body = buildString {
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"'  -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }

    return when {
        needsQuote -> "\"$body\""
        forCell -> body.replace(",", "\\,") // célula tabular escapa vírgula sem citar
        else -> body
    }
}