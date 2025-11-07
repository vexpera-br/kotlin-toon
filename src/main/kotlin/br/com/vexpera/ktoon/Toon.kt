@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package br.com.vexpera.ktoon

import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * TOON – Token-Oriented Object Notation (JVM core)
 * Conformante à TOON Spec v1.4 (Johann Schopplich)
 *
 * - Objetos por indentação (key: value / key:)
 * - Arrays tabulares: key[#?N<delim?>]{f1<delim>f2}: linhas com <delim>
 * - Arrays inline de primitivos: key[#?N<delim?>]: v1<delim>v2...
 * - Strict mode valida indentação, tabs, contagens, larguras e linhas em branco
 * - Lenient mode relaxa indentação e ignora blanks internos em tabelas
 */
object Toon {
    fun encode(value: Any?, options: EncodeOptions = EncodeOptions()): String =
        Encoder(options).encode(value)

    fun decode(input: String, options: DecodeOptions = DecodeOptions()): Any? =
        Decoder(options).decode(input)
}

// ---------- Options ----------

data class EncodeOptions(
    val indent: Int = 2,
    val delimiter: Delimiter = Delimiter.COMMA,
    val lengthMarker: Boolean = false,
)

enum class Delimiter(val symbol: String) { COMMA(","), TAB("\t"), PIPE("|") }

data class DecodeOptions(
    val indent: Int = 2,
    val strict: Boolean = true,
    val debug: Boolean = false,
)

// ---------- Decoder ----------

private class Decoder(private val options: DecodeOptions) {

    private fun log(msg: String) { if (options.debug) println("[DBG] $msg") }

    fun decode(input: String): Any? {
        val normalized = input.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n')
        val ctx = ParseCtx(lines, options)

        ctx.peekNonEmpty()?.let { (_, content) ->
            if (TableHeader.isTabularHeader(content)) {
                val header = TableHeader.parse(content)
                    ?: throw ToonParseException("Malformed root table header at line 1")
                val rows = parseTabularArray(ctx, 0)
                return mapOf(header.name to rows)
            }
            if (SimpleHeader.isSimpleHeader(content)) {
                val header = SimpleHeader.parse(content)
                    ?: throw ToonParseException("Malformed root array header at line 1")
                ctx.readNonEmpty()!!
                val values = parseDelimitedValues(header.inlineValues ?: "", header.delimiter)
                return mapOf(header.name to values)
            }
        }

        val result = parseBlock(ctx, 0)
        if (options.strict && ctx.hasMoreNonEmpty())
            throw ToonParseException("Trailing content after root value at line ${ctx.index + 1}")
        return result
    }

    // ---------- Object parsing ----------

    private fun parseBlock(ctx: ParseCtx, baseIndent: Int): Any? {
        val peek = ctx.peekNonEmpty() ?: return emptyMap<String, Any?>()
        val (indentLevel, content) = peek
        if (indentLevel < baseIndent) return emptyMap<String, Any?>()

        if (TableHeader.isTabularHeader(content)) return parseTabularArray(ctx, baseIndent)

        val map = linkedMapOf<String, Any?>()

        while (true) {
            val next = ctx.peekNonEmpty() ?: break
            val (lvl, txt) = next
            if (lvl < baseIndent) break

            // [FIX v4.3] — detectar hífen em qualquer indentação relativa
            val stripped = txt.trimStart()
            if (options.strict &&
                stripped.startsWith("-") &&
                (stripped.length == 1 || stripped[1].isWhitespace())) {
                throw ToonParseException("Unsupported list items (-) not implemented")
            }

            if (lvl > baseIndent) {
                if (options.strict)
                    throw ToonParseException("Unexpected indentation at line ${ctx.index + 1}")
                ctx.readNonEmpty(); continue
            }

            if (TableHeader.isTabularHeader(txt)) {
                val arr = parseTabularArray(ctx, baseIndent)
                val header = TableHeader.parse(ctx.lastReadHeaderLine ?: txt)
                    ?: throw ToonParseException("Malformed table header at line ${ctx.index}")
                map[header.name] = arr
                continue
            }

            ctx.readNonEmpty()
            val kv = txt.split(":", limit = 2)
            if (kv.size != 2) {
                if (options.strict)
                    throw ToonParseException("Missing colon after key at line ${ctx.index}")
                continue
            }

            val key = kv[0].trim()
            val rhs = kv[1]
            if (rhs.isNotBlank()) {
                val value = parsePrimitiveToken(rhs.trim())
                map[key] = value
            } else {
                val nextPeek = ctx.peekNonEmpty()
                if (nextPeek != null && (
                            if (options.strict)
                                nextPeek.first >= baseIndent + options.indent
                            else
                                nextPeek.first > baseIndent
                            )) {
                    val child = parseBlock(ctx, baseIndent + options.indent)
                    map[key] = child
                } else {
                    map[key] = emptyMap<String, Any?>()
                }
            }
        }
        return map
    }

    // ---------- Table parsing ----------

    private fun parseTabularArray(ctx: ParseCtx, baseIndent: Int): List<Map<String, Any?>> {
        ctx.readNonEmpty()!!.also { (_, headerLine) -> ctx.lastReadHeaderLine = headerLine }
        val header = TableHeader.parse(ctx.lastReadHeaderLine!!)
            ?: throw ToonParseException("Malformed table header at line ${ctx.index}")

        val rows = mutableListOf<Map<String, Any?>>()
        var sawRow = false

        while (true) {
            val raw = ctx.peekRaw() ?: break

            // [FIX v4.3] — tratamento de linhas em branco dentro e depois da tabela
            if (raw.isBlank()) {
                if (options.strict) {
                    if (sawRow && ctx.hasMoreNonEmptyAfterBlankAtSameIndent(baseIndent + options.indent))
                        throw ToonParseException("Blank line inside table at line ${ctx.index + 1}")
                    ctx.readBlank()
                    continue
                } else {
                    ctx.readBlank()
                    continue
                }
            }

            val (lvl2, txt) = ctx.peekNonEmpty()!!
            if (lvl2 < baseIndent + options.indent) break
            if (lvl2 > baseIndent + options.indent) {
                if (options.strict)
                    throw ToonParseException("Over-indented row at line ${ctx.index + 1}")
                ctx.readNonEmpty(); continue
            }

            val colonPos = firstUnquotedIndexOf(txt, ':')
            val delimPos = firstUnquotedIndexOf(txt, header.delimiter)
            if (colonPos != -1 && (delimPos == -1 || colonPos < delimPos)) break

            ctx.readNonEmpty()
            sawRow = true

            val cells = splitDelimitedAware(txt, header.delimiter)
            if (options.strict && cells.size != header.columns.size)
                throw ToonParseException("Expected ${header.columns.size} values in row, got ${cells.size}")

            val row = linkedMapOf<String, Any?>()
            for (i in header.columns.indices) {
                val key = header.columns[i]
                val rawCell = cells.getOrNull(i)?.trim() ?: ""
                row[key] = parsePrimitiveToken(rawCell)
            }
            rows += row

            if (header.length != null && rows.size > header.length && options.strict)
                throw ToonParseException("Too many rows in '${header.name}' (expected ${header.length})")
        }

        // [FIX v4.3] — valida contagem ignorando blanks finais
        if (options.strict && header.length != null && rows.size < header.length) {
            if (!ctx.remainingAreBlank())
                throw ToonParseException("Expected ${header.length} rows, got ${rows.size}")
        }

        return rows
    }

    // ---------- Primitive parsing ----------

    private val numRegex = Regex("^-?\\d+(?:\\.\\d+)?(?:[eE][+\\-]?\\d+)?$")
    private val leadingZero = Regex("^0\\d+$")

    private fun parsePrimitiveToken(tokenIn: String): Any? {
        val token = tokenIn.trim()
        if (token.isEmpty()) return ""
        if (token.length >= 2 && token.first() == '"' && token.last() == '"')
            return unescape(token.substring(1, token.lastIndex))
        return when (token) {
            "true" -> true
            "false" -> false
            "null", "~" -> null
            else -> {
                val negative = token.startsWith('-')
                val digits = if (negative) token.drop(1) else token
                if (numRegex.matches(token) && !(leadingZero.matches(digits) && digits != "0")) {
                    val d = token.toDoubleOrNull()
                    if (d != null) {
                        if (d == -0.0) 0 else token.toLongOrNull() ?: d
                    } else token
                } else token
            }
        }
    }

    private fun parseDelimitedValues(text: String, delimiter: Char): List<Any?> =
        splitDelimitedAware(text, delimiter).map { parsePrimitiveToken(it.trim()) }
}

// ---------- ParseCtx ----------

private class ParseCtx(private val lines: List<String>, private val options: DecodeOptions) {
    var index: Int = 0
        private set

    var lastReadHeaderLine: String? = null

    fun hasMoreNonEmpty(): Boolean {
        var i = index
        while (i < lines.size) if (lines[i].isNotBlank()) return true else i++
        return false
    }

    fun remainingAreBlank(): Boolean {
        for (j in index until lines.size)
            if (lines[j].isNotBlank()) return false
        return true
    }

    // [FIX v4.3] — detecção de blanks internos em tabelas
    fun hasMoreNonEmptyAfterBlankAtSameIndent(expectedIndent: Int): Boolean {
        var i = index
        var sawBlank = false
        while (i < lines.size) {
            val raw = lines[i]
            if (raw.isBlank()) { sawBlank = true; i++; continue }
            if (!sawBlank) return false
            val trimmedRight = raw.trimEnd()
            val indent = computeIndent(trimmedRight, i + 1)
            return indent == expectedIndent && trimmedRight.substring(indent).isNotEmpty()
        }
        return false
    }

    fun peekRaw(): String? = if (index < lines.size) lines[index] else null
    fun readBlank() { if (index < lines.size && lines[index].isBlank()) index++ }

    fun peekNonEmpty(): Pair<Int, String>? {
        var i = index
        while (i < lines.size) {
            val raw = lines[i]
            val trimmedRight = raw.trimEnd()
            if (trimmedRight.isBlank()) { i++; continue }
            val indent = computeIndent(trimmedRight, i + 1)
            return indent to trimmedRight.substring(indent)
        }
        return null
    }

    fun readNonEmpty(): Pair<Int, String>? {
        while (index < lines.size) {
            val raw = lines[index++]
            val trimmedRight = raw.trimEnd()
            if (trimmedRight.isBlank()) continue
            val indent = computeIndent(trimmedRight, index)
            return indent to trimmedRight.substring(indent)
        }
        return null
    }

    private fun computeIndent(raw: String, lineNum: Int): Int {
        if (options.strict && raw.isNotEmpty() && raw[0] == '\t')
            throw ToonParseException("Tabs are not allowed in indentation (line $lineNum)")
        val leadingSpaces = raw.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) 0 else it }
        if (options.strict && leadingSpaces % options.indent != 0)
            throw ToonParseException("Indentation must be multiple of ${options.indent} spaces (line $lineNum)")
        return leadingSpaces
    }
}

// ---------- Headers ----------

private data class TableHeader(
    val name: String,
    val length: Int?,
    val columns: List<String>,
    val delimiter: Char,
) {
    companion object {
        // [FIX v4.3] aceita [#N] e [N]
        private val rx =
            Regex("""^([A-Za-z_][A-Za-z0-9_.\-]*)\s*\[\#?(\d+)([\t|])?]\s*\{(.*)}\s*:$""")

        fun isTabularHeader(text: String): Boolean = rx.matches(text.trim())

        fun parse(text: String): TableHeader? {
            val t = text.trim()
            val m = rx.find(t) ?: return null
            val name = m.groupValues[1]
            val len = m.groupValues[2].toInt()
            val delimChar = when (m.groupValues[3]) {
                "\t" -> '\t'
                "|" -> '|'
                else -> ','
            }
            val fieldsRaw = m.groupValues[4]
            val cols = splitDelimitedAware(fieldsRaw, delimChar).map { it.trim() }.filter { it.isNotEmpty() }
            if (cols.isEmpty()) return null
            return TableHeader(name, len, cols, delimChar)
        }
    }
}

private data class SimpleHeader(
    val name: String,
    val length: Int?,
    val delimiter: Char,
    val inlineValues: String?
) {
    companion object {
        private val rx =
            Regex("""^([A-Za-z_][A-Za-z0-9_.\-]*)\s*\[\#?(\d+)([\t|])?]\s*:\s*(.*)$""")

        fun isSimpleHeader(text: String): Boolean = rx.matches(text.trim())

        fun parse(text: String): SimpleHeader? {
            val t = text.trim()
            val m = rx.find(t) ?: return null
            val name = m.groupValues[1]
            val len = m.groupValues[2].toIntOrNull()
            val delimChar = when (m.groupValues[3]) {
                "\t" -> '\t'
                "|" -> '|'
                else -> ','
            }
            val trailing = m.groupValues[4]
            val inline = trailing.ifBlank { null }
            return SimpleHeader(name, len, delimChar, inline)
        }
    }
}

// ---------- Utility ----------

class ToonParseException(message: String) : RuntimeException(message)

private fun splitDelimitedAware(text: String, delimiter: Char): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            cur.append(c).append(text[i + 1]); i += 2; continue
        }
        if (c == '"') { inQuotes = !inQuotes; cur.append(c); i++; continue }
        if (!inQuotes && c == delimiter) { out += cur.toString(); cur.setLength(0); i++; continue }
        cur.append(c); i++
    }
    out += cur.toString()
    return out
}

private fun firstUnquotedIndexOf(text: String, ch: Char): Int {
    var inQuotes = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) { i += 2; continue }
        if (c == '"') { inQuotes = !inQuotes; i++; continue }
        if (!inQuotes && c == ch) return i
        i++
    }
    return -1
}

private fun unescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                '\\' -> out.append('\\')
                '"' -> out.append('"')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                else -> out.append(s[i + 1])
            }
            i += 2
        } else {
            out.append(c); i++
        }
    }
    return out.toString()
}

// ---------- Kotlin idiomatic layer ----------

class ToonNode(val value: Any?) {
    fun asMap(): Map<String, ToonNode> = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to ToonNode(v) }
        else -> emptyMap()
    }

    fun asList(): List<ToonNode> = when (value) {
        is List<*> -> value.map { ToonNode(it) }
        else -> emptyList()
    }

    fun asString(): String? = value as? String ?: value?.toString()
    fun asInt(): Int? = (value as? Number)?.toInt()
    fun asLong(): Long? = (value as? Number)?.toLong()
    fun asDouble(): Double? = (value as? Number)?.toDouble()
    fun asBoolean(): Boolean? = value as? Boolean

    inline fun <reified T : Any> asObject(): T {
        val map = value as? Map<*, *> ?: throw ToonParseException("Expected map for ${T::class.simpleName}")
        return construct(T::class, map)
    }

    inline fun <reified T : Any> asListOf(): List<T> = asList().map { it.asObject<T>() }

    operator fun get(key: String): ToonNode? =
        (value as? Map<*, *>)?.get(key)?.let { ToonNode(it) }

    fun <T : Any> construct(clazz: KClass<T>, map: Map<*, *>): T {
        val ctor = clazz.primaryConstructor
            ?: throw ToonParseException("No primary constructor for ${clazz.simpleName}")
        val args = ctor.parameters.associateWith { param ->
            val raw = map[param.name]
            when (param.type.classifier) {
                String::class -> raw?.toString()
                Int::class -> (raw as? Number)?.toInt()
                Long::class -> (raw as? Number)?.toLong()
                Double::class -> (raw as? Number)?.toDouble()
                Boolean::class -> raw as? Boolean
                else -> raw
            }
        }
        return ctor.callBy(args)
    }
}

// ---------- Encoder ----------

private class Encoder(private val options: EncodeOptions) {
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
            val k = kAny.toString()
            when (v) {
                is List<*> -> {
                    val hom = homogenize(v)
                    if (hom != null) {
                        // tabular array
                        val lengthPart = if (options.lengthMarker) "[#${v.size}]" else "[${v.size}]"
                        val cols = hom.first.joinToString(options.delimiter.symbol)
                        line(level, "$k$lengthPart{$cols}:")
                        val delim = options.delimiter.symbol
                        for (row in hom.second) {
                            line(level + 1, row.joinToString(delim) { renderCell(it) })
                        }
                        continue
                    }

                    // inline primitive array
                    val prim = v.all { it == null || it is String || it is Number || it is Boolean }
                    if (prim) {
                        val lengthPart = if (options.lengthMarker) "[#${v.size}]" else "[${v.size}]"
                        val joined = v.joinToString(options.delimiter.symbol) { renderCell(it) }
                        line(level, "$k$lengthPart: $joined")
                    } else {
                        // expanded list (not tabular)
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

fun toon(text: String): ToonNode = ToonNode(Toon.decode(text))
inline fun <reified T : Any> toon(text: String, block: ToonNode.() -> T): T {
    val root = ToonNode(Toon.decode(text))
    return root.block()
}
