package br.com.vexpera.ktoon

data class EncodeOptions(
    val indent: Int = 2,
    val delimiter: Delimiter = Delimiter.COMMA,
    val lengthMarker: Boolean = false,
)

// ---------- Encoder ----------

/**
 * Encoder for TOON spec v1.4 (§2–§12): objects by indentation, inline primitive arrays, tabular arrays of uniform
 * primitive objects (§9.3), and the expanded list form for everything else (§9.2, §9.4), with objects as list items
 * placing their first field on the hyphen line (§10). Keys are quoted when they are not identifiers (§7.3).
 */
class Encoder(private val options: EncodeOptions) {
    private val sb = StringBuilder()
    private val indentUnit = " ".repeat(options.indent)

    fun encode(value: Any?): String {
        when (value) {
            is Map<*, *> -> writeObject(value, 0)
            is List<*> -> writeArray(prefix = "", key = null, list = value, level = 0, bodyLevel = 1)
            else -> line(0, renderPrimitive(value))
        }
        return sb.toString().trimEnd('\n')
    }

    // ---------- Objects ----------

    private fun writeObject(map: Map<*, *>, level: Int) {
        for ((k, v) in map) writeField(prefix = "", key = k, value = v, level = level, childLevel = level + 1)
    }

    /**
     * Writes one `key: value` field on a line at [level], after [prefix] ("- " for the first field of a list item).
     * Nested object fields go to [childLevel]; array rows and items go to [level] + 1.
     */
    private fun writeField(prefix: String, key: Any?, value: Any?, level: Int, childLevel: Int) {
        val k = renderKey(key)
        when (value) {
            is Map<*, *> -> {
                line(level, "$prefix$k:")
                writeObject(value, childLevel)
            }
            is List<*> -> writeArray(prefix, k, value, level, bodyLevel = level + 1)
            else -> line(level, "$prefix$k: ${renderPrimitive(value)}")
        }
    }

    // ---------- Arrays (§9) ----------

    private fun writeArray(prefix: String, key: String?, list: List<*>, level: Int, bodyLevel: Int) {
        val k = key ?: ""
        val delim = options.delimiter.symbol
        val length = lengthPart(list.size)
        when {
            list.isEmpty() -> line(level, "$prefix$k$length:")
            list.all { isPrimitive(it) } ->
                line(level, "$prefix$k$length: " + list.joinToString(delim) { renderPrimitive(it) })
            else -> {
                val columns = tabularColumns(list)
                if (columns != null) {
                    line(level, "$prefix$k$length{" + columns.joinToString(delim) { renderKey(it) } + "}:")
                    for (item in list) {
                        val row = item as Map<*, *>
                        line(bodyLevel, columns.joinToString(delim) { renderPrimitive(row[it]) })
                    }
                } else {
                    line(level, "$prefix$k$length:")
                    for (item in list) writeListItem(item, bodyLevel)
                }
            }
        }
    }

    /** One element of the expanded list form (§9.4, §10). */
    private fun writeListItem(item: Any?, level: Int) {
        when (item) {
            is List<*> -> writeArray(prefix = "- ", key = null, list = item, level = level, bodyLevel = level + 1)
            is Map<*, *> -> {
                if (item.isEmpty()) {
                    line(level, "-")
                    return
                }
                val entries = item.entries.toList()
                val first = entries.first()
                // First field on the hyphen line: its nested object fields at +2, its array rows/items at +1;
                // the remaining fields at +1.
                writeField(prefix = "- ", key = first.key, value = first.value, level = level, childLevel = level + 2)
                for (e in entries.drop(1)) writeField(prefix = "", key = e.key, value = e.value, level = level + 1, childLevel = level + 2)
            }
            else -> line(level, "- ${renderPrimitive(item)}")
        }
    }

    /**
     * Tabular detection (§9.3): every element is a non-empty object, all with the same set of keys (order may vary)
     * and only primitive values. Returns the columns in the first object's key order, or null.
     */
    private fun tabularColumns(list: List<*>): List<Any?>? {
        if (list.isEmpty() || list.any { it !is Map<*, *> || it.isEmpty() }) return null
        val maps = list.map { it as Map<*, *> }
        val columns = maps.first().keys.toList()
        val keySet = columns.toSet()
        if (maps.any { it.keys != keySet }) return null
        if (maps.any { m -> m.values.any { !isPrimitive(it) } }) return null
        return columns
    }

    private fun isPrimitive(v: Any?): Boolean = v !is Map<*, *> && v !is List<*>

    // ---------- Tokens ----------

    private fun renderPrimitive(v: Any?): String = when (v) {
        null -> "null"
        is String -> escape(v, options.delimiter)
        is Number -> formatCanonicalNumber(v)
        is Boolean -> v.toString()
        else -> escape(v.toString(), options.delimiter)
    }

    /**
     * Key encoding (§7.3): identifiers stay unquoted, anything else is quoted and escaped.
     * A null key is written as the legacy "-" entry, which the decoder reads back as the null key.
     */
    private fun renderKey(key: Any?): String {
        if (key == null) return "-"
        val k = key.toString()
        return if (UNQUOTED_KEY.matches(k)) k else "\"" + escapeBody(k) + "\""
    }

    // A non-comma delimiter is declared as the last symbol inside the brackets ("[N\t]", "[#N|]"), as the decoder expects.
    private fun lengthPart(size: Int): String {
        val marker = if (options.lengthMarker) "#" else ""
        val delim = if (options.delimiter == Delimiter.COMMA) "" else options.delimiter.symbol
        return "[$marker$size$delim]"
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

    private companion object {
        val UNQUOTED_KEY = Regex("^[A-Za-z_][A-Za-z0-9_.]*$")
    }
}

/**
 * Escapes special characters for safe use in TOON.
 *
 * Wraps the text in quotes when it would otherwise decode to a different value (§7.2): leading/trailing
 * whitespace, literals, numbers, structural characters, control characters or the delimiter in use.
 * The encoder has a single delimiter, so the document delimiter (object values) and the active delimiter
 * (table cells, inline arrays) are the same; other delimiters do not require quotes (§11).
 *
 * @param s Original text.
 * @param delimiter The delimiter in use.
 */
private fun escape(s: String, delimiter: Delimiter): String {
    if (s.isEmpty()) return "\"\"" // empty strings must always be quoted

    val numericLike = s.matches(Regex("^-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?$"))
    val leadingZero = s.matches(Regex("^0\\d+$"))

    // Quoting rules per §7.2
    val needsQuote = (
            s.first().isWhitespace() ||
                    s.last().isWhitespace() ||
                    s == "true" || s == "false" || s == "null" ||
                    numericLike || leadingZero ||
                    s.contains(':') ||
                    s.contains('"') ||
                    s.contains('\\') ||
                    s.contains('[') || s.contains(']') ||
                    s.contains('{') || s.contains('}') ||
                    s.any { it < ' ' } ||
                    s.contains(delimiter.symbol) ||
                    s == "-" || s.startsWith('-') ||
                    // The decoder skips lines starting with "#" as comments (as TOON spec 4.x does), so a
                    // table cell or list value starting with "#" at the start of a line would be lost.
                    s.startsWith('#')
            )

    val body = escapeBody(s)
    return if (needsQuote) "\"$body\"" else body
}

/** Escapes \\, ", LF, CR and tab for use inside a quoted string or key (§7.1). */
private fun escapeBody(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}
