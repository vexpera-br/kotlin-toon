@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package br.com.vexpera.ktoon

import java.lang.StringBuilder
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor

/**
 * TOON – Token-Oriented Object Notation (JVM core)
 * Conformante à TOON Spec v1.4 (Working Draft 2025-11-05)
 *
 * - Objetos por indentação (key: value / key:)
 * - Arrays tabulares: key[#?N{delim?}]{f1<delim>f2}: linhas com <delim>
 * - Arrays inline de primitivos: key[#?N{delim?}]: v1<delim>v2...
 * - Strict mode valida indentação, tabs, contagens, larguras e linhas em branco.
 * - Lenient mode relaxa indentação e linhas em branco internas.
 */
object Toon {
    fun encode(value: Any?, options: EncodeOptions = EncodeOptions()): String =
        Encoder(options).encode(value)

    fun decode(input: String, options: DecodeOptions = DecodeOptions()): Any? =
        Decoder(options).decode(input)
}

// ---------- Options ----------

data class EncodeOptions(
    /** Espaços por nível (spec sugere 2) */
    val indent: Int = 2,
    /** Delimitador "do documento" (impacta cotações fora do escopo de arrays) */
    val delimiter: Delimiter = Delimiter.COMMA,
    /** Emite [N] nos headers quando possível */
    val lengthMarker: Boolean = false,
)

enum class Delimiter(val symbol: String) { COMMA(","), TAB("\t"), PIPE("|") }

data class DecodeOptions(
    /** Espaços por nível (strict valida múltiplos exatos) */
    val indent: Int = 2,
    /** Strict = valida tudo; Lenient = perdoa indent irregular & blanks em tabelas */
    val strict: Boolean = true,
    /** Log de depuração em stdout */
    val debug: Boolean = false,
)

// ---------- Decoder ----------

private class Decoder(private val options: DecodeOptions) {

    private fun log(msg: String) { if (options.debug) println("[DBG] $msg") }

    fun decode(input: String): Any? {
        val normalized = input.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n')
        val ctx = ParseCtx(lines, options)
        // Root-form discovery: se 1ª linha for header (tabular ou simples), parse root array
        ctx.peekNonEmpty()?.let { (_, content) ->
            if (TableHeader.isTabularHeader(content) || SimpleHeader.isSimpleHeader(content)) {
                // root array
                return parseArrayAtRoot(ctx)
            }
        }
        val result = parseBlock(ctx, 0)
        if (options.strict && ctx.hasMoreNonEmpty()) {
            throw ToonParseException("Trailing content after root value at line ${ctx.index + 1}")
        }
        return result
    }

    /**
     * Parse de objeto por indentação a partir de baseIndent.
     */
    private fun parseBlock(ctx: ParseCtx, baseIndent: Int): Any? {
        val peek = ctx.peekNonEmpty() ?: return emptyMap<String, Any?>()
        val (indentLevel, content) = peek
        if (indentLevel < baseIndent) return emptyMap<String, Any?>()

        log("parseBlock(baseIndent=$baseIndent) start → '$content'")

        // Caso especial: bloco começa com um header tabular
        if (TableHeader.isTabularHeader(content)) {
            return parseTabularArray(ctx, baseIndent)
        }

        // Caso especial: bloco começa com um header simples (inline ou expandido)
        if (SimpleHeader.isSimpleHeader(content)) {
            val (lvl, line) = ctx.readNonEmpty()!!
            val sh = SimpleHeader.parse(line)
                ?: throw ToonParseException("Malformed array header at line ${ctx.index}")
            // Inline array (valores na mesma linha)
            if (sh.inlineValues != null) {
                val values = parseDelimitedValues(sh.inlineValues, sh.delimiter)
                if (options.strict && sh.length != null && values.size != sh.length)
                    throw ToonParseException("Expected ${sh.length} items in '${sh.name}', got ${values.size}")
                // Em objeto, precisa de uma chave: use o name
                return mapOf(sh.name to values)
            } else {
                // Expandido (itens em linhas seguintes) — não implementado
                throw ToonParseException("Expanded arrays (list items) not implemented")
            }
        }

        val map = linkedMapOf<String, Any?>()

        while (true) {
            val next = ctx.peekNonEmpty() ?: break
            val (lvl, txt) = next
            if (lvl < baseIndent) break

            if (lvl > baseIndent) {
                // Se chegamos aqui sem um "key:" abrindo bloco, é indentação inesperada
                if (options.strict) {
                    throw ToonParseException("Unexpected indentation at line ${ctx.index + 1}")
                } else {
                    ctx.readNonEmpty(); continue
                }
            }

            // --- lvl == baseIndent ---
            // Linhas "itens de lista" não são suportadas
            if (txt.trimStart().startsWith("- ")) {
                throw ToonParseException("Objects-as-list-items not implemented")
            }

            // Tabular header no mesmo nível
            if (TableHeader.isTabularHeader(txt)) {
                val arr = parseTabularArray(ctx, baseIndent)
                // arr precisa de um nome para entrar no map; pegue do header
                val header = TableHeader.parse(ctx.lastReadHeaderLine ?: txt)
                    ?: throw ToonParseException("Malformed table header at line ${ctx.index}")
                map[header.name] = arr
                continue
            }

            // Header simples no mesmo nível (inline)
            if (SimpleHeader.isSimpleHeader(txt)) {
                val (_, line) = ctx.readNonEmpty()!!
                val sh = SimpleHeader.parse(line)
                    ?: throw ToonParseException("Malformed array header at line ${ctx.index}")
                if (sh.inlineValues != null) {
                    val values = parseDelimitedValues(sh.inlineValues, sh.delimiter)
                    if (options.strict && sh.length != null && values.size != sh.length)
                        throw ToonParseException("Expected ${sh.length} items in '${sh.name}', got ${values.size}")
                    map[sh.name] = values
                    continue
                } else {
                    throw ToonParseException("Expanded arrays (list items) not implemented")
                }
            }

            // Linha normal: "key:" ou "key: value"
            ctx.readNonEmpty()
            val kv = txt.split(":", limit = 2)
            if (kv.size != 2) {
                if (options.strict) throw ToonParseException("Missing colon after key at line ${ctx.index}")
                continue
            }
            val key = kv[0].trim()
            val rhs = kv[1]
            if (rhs.isNotBlank()) {
                // "key: value"
                val value = parsePrimitiveToken(rhs.trim())
                map[key] = value
            } else {
                // "key:" → objeto aninhado
                val nextPeek = ctx.peekNonEmpty()
                if (nextPeek != null && nextPeek.first >= baseIndent + options.indent) {
                    val child = parseBlock(ctx, baseIndent + options.indent)
                    map[key] = child
                } else {
                    map[key] = emptyMap<String, Any?>()
                }
            }
        }

        return map
    }

    /**
     * Parse de array tabular (header + rows).
     */
    private fun parseTabularArray(ctx: ParseCtx, baseIndent: Int): List<Map<String, Any?>> {
        val (lvl, headerLine) = ctx.readNonEmpty()!!
        ctx.lastReadHeaderLine = headerLine
        val header = TableHeader.parse(headerLine)
            ?: throw ToonParseException("Malformed table header at line ${ctx.index}")
        val rows = mutableListOf<Map<String, Any?>>()
        var sawFirstRow = false

        while (true) {
            // Checagem explícita de blank line dentro do bloco
            val rawAhead = ctx.peekRaw()
            if (rawAhead != null && rawAhead.isNotEmpty() && rawAhead.isBlank()) {
                if (sawFirstRow) {
                    if (options.strict) throw ToonParseException("Blank line inside table at line ${ctx.index + 1}")
                    // lenient: apenas consome e segue
                    ctx.readNonEmpty(); continue
                } else {
                    // blank antes de qualquer row: simplesmente consome
                    ctx.readNonEmpty(); continue
                }
            }

            val peek = ctx.peekNonEmpty() ?: break
            val (lvl2, txt) = peek

            // As linhas de row devem estar em baseIndent + indent
            if (lvl2 < baseIndent + options.indent) break
            if (lvl2 > baseIndent + options.indent) {
                if (options.strict) throw ToonParseException("Over-indented row at line ${ctx.index + 1}")
                ctx.readNonEmpty(); continue
            }

            // Disambiguation: se houver ":" não-quoted antes do primeiro delimitador ativo → fim das rows
            val colonPos = firstUnquotedIndexOf(txt, ':')
            val delimPos = firstUnquotedIndexOf(txt, header.delimiter)
            if (colonPos != -1 && (delimPos == -1 || colonPos < delimPos)) break

            // Linha de row
            ctx.readNonEmpty()
            sawFirstRow = true

            val cells = splitDelimitedAware(txt, header.delimiter)
            if (options.strict && cells.size != header.columns.size) {
                throw ToonParseException("Expected ${header.columns.size} values in row, got ${cells.size}")
            }
            val row = linkedMapOf<String, Any?>()
            for (i in header.columns.indices) {
                val key = header.columns[i]
                val raw = cells.getOrNull(i)?.trim() ?: ""
                row[key] = parsePrimitiveToken(raw)
            }
            rows += row
        }

        if (options.strict && header.length != null && rows.size != header.length) {
            throw ToonParseException("Expected ${header.length} rows, got ${rows.size}")
        }

        return rows
    }

    /**
     * Parse de array no root (tabular ou simples).
     */
    private fun parseArrayAtRoot(ctx: ParseCtx): Any? {
        val (_, content) = ctx.peekNonEmpty()!!
        return when {
            TableHeader.isTabularHeader(content) -> parseTabularArray(ctx, 0)
            SimpleHeader.isSimpleHeader(content) -> {
                val (_, line) = ctx.readNonEmpty()!!
                val sh = SimpleHeader.parse(line)
                    ?: throw ToonParseException("Malformed array header at line ${ctx.index}")
                if (sh.inlineValues != null) {
                    val values = parseDelimitedValues(sh.inlineValues, sh.delimiter)
                    if (options.strict && sh.length != null && values.size != sh.length)
                        throw ToonParseException("Expected ${sh.length} items, got ${values.size}")
                    values
                } else {
                    throw ToonParseException("Expanded arrays (list items) not implemented")
                }
            }
            else -> emptyList<Any?>()
        }
    }

    // ----- token parsing -----

    private val numRegex = Regex("^-?\\d+(?:\\.\\d+)?(?:[eE][+\\-]?\\d+)?$")
    private val leadingZero = Regex("^0\\d+$")

    private fun parsePrimitiveToken(tokenIn: String): Any? {
        val token = tokenIn.trim()
        if (token.isEmpty()) return ""
        // quoted string?
        if (token.length >= 2 && token.first() == '"' && token.last() == '"') {
            return unescape(token.substring(1, token.lastIndex))
        }
        // booleans & null
        return when (token) {
            "true" -> true
            "false" -> false
            "null", "~" -> null
            else -> {
                // numbers: aceitar decimal/expoente; leading zeros proibidos são string
                val negative = token.startsWith('-')
                val digits = if (negative) token.drop(1) else token
                if (numRegex.matches(token) && !(leadingZero.matches(digits) && digits != "0")) {
                    // -0 → 0
                    val d = token.toDoubleOrNull()
                    if (d != null) {
                        if (d == -0.0) 0
                        else d.toLong().toDouble().let {
                            // preferir Int/Long quando exato
                            val asLong = token.toLongOrNull()
                            asLong ?: d
                        }
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

    // mantém última linha de header lida (para nome em map)
    var lastReadHeaderLine: String? = null

    fun hasMoreNonEmpty(): Boolean {
        var i = index
        while (i < lines.size) {
            if (lines[i].isNotBlank()) return true
            i++
        }
        return false
    }

    /** Linha bruta atual (pode ser em branco). */
    fun peekRaw(): String? = if (index < lines.size) lines[index] else null

    fun peekNonEmpty(): Pair<Int, String>? {
        var i = index
        while (i < lines.size) {
            val raw = lines[i]
            val trimmedRight = raw.trimEnd()
            if (trimmedRight.isBlank()) { i++; continue }
            val indent = computeIndent(trimmedRight, i + 1)
            val content = trimmedRight.substring(indent)
            return indent to content
        }
        return null
    }

    fun readNonEmpty(): Pair<Int, String>? {
        while (index < lines.size) {
            val raw = lines[index++]
            val trimmedRight = raw.trimEnd()
            if (trimmedRight.isBlank()) continue
            val indent = computeIndent(trimmedRight, index)
            val content = trimmedRight.substring(indent)
            return indent to content
        }
        return null
    }

    private fun computeIndent(raw: String, lineNum: Int): Int {
        // Tabs na indentação → erro em strict
        if (options.strict && raw.isNotEmpty() && raw[0] == '\t') {
            throw ToonParseException("Tabs are not allowed in indentation (line $lineNum)")
        }
        val leadingSpaces = raw.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) 0 else it }
        if (options.strict && leadingSpaces % options.indent != 0) {
            throw ToonParseException("Indentation must be multiple of ${options.indent} spaces (line $lineNum)")
        }
        // lenient: aceita qualquer indent e mapeia para níveis por floor
        return if (options.strict) leadingSpaces else (leadingSpaces / options.indent) * options.indent
    }
}

// ---------- Headers ----------

/** Header de array tabular: key[#?N<delim?>]{fields}: */
private data class TableHeader(
    val name: String,
    val length: Int?,
    val columns: List<String>,
    val delimiter: Char,
) {
    companion object {
        // name [#?N (tab/pipe)? ] { fields } :
        private val rx = Regex(
            pattern = """^([A-Za-z_][A-Za-z0-9_.\-]*)\s*\[\#?(\d+)([\t|])?]\s*\{(.*)}\s*:$"""
        )

        fun isTabularHeader(text: String): Boolean = rx.matches(text.trim())

        fun parse(text: String): TableHeader? {
            val t = text.trim()
            val m = rx.find(t) ?: return null
            val name = m.groupValues[1]
            val len = m.groupValues[2].toInt()
            val delimChar = when (m.groupValues[3]) {
                "\t" -> '\t'
                "|" -> '|'
                else -> ',' // ausência = vírgula
            }
            val fieldsRaw = m.groupValues[4]

            // Consistência: o separador nos campos deve ser o delimitador ativo (ou único campo)
            val cols = splitFields(fieldsRaw, delimChar)
            if (cols.isEmpty()) return null

            return TableHeader(name, len, cols, delimChar)
        }

        private fun splitFields(s: String, delim: Char): List<String> {
            if (s.isBlank()) return emptyList()
            // se não houver o delim, pode ser uma única coluna
            if (!s.contains(delim) && s.indexOf(',') != -1 && delim != ',') {
                // Mismatch: campos usam ',' mas bracket declarou outro delim
                return emptyList()
            }
            val parts = splitDelimitedAware(s, delim).map { it.trim() }
            return parts.filter { it.isNotEmpty() }
        }
    }
}

/** Header simples de array de primitivos: key[#?N<delim?>]: [ inline ] */
private data class SimpleHeader(
    val name: String,
    val length: Int?,
    val delimiter: Char,
    val inlineValues: String? // null = expandido; non-null = lista inline bruta
) {
    companion object {
        private val rx = Regex(
            pattern = """^([A-Za-z_][A-Za-z0-9_.\-]*)\s*\[\#?(\d+)([\t|])?]\s*:\s*(.*)$"""
        )
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
                        val lengthPart = if (options.lengthMarker) "[${v.size}]" else "[]".take(0)
                        val cols = hom.first.joinToString(",")
                        line(level, "$k$lengthPart{$cols}:")
                        val delim = options.delimiter.symbol
                        for (row in hom.second) {
                            line(level + 1, row.joinToString(delim) { renderCell(it) })
                        }
                        continue
                    }
                    // lista heterogênea → inline se primitiva, caso contrário não suportado (sem list items)
                    val prim = v.all { it == null || it is String || it is Number || it is Boolean }
                    if (prim) {
                        val lengthPart = if (options.lengthMarker) "[${v.size}]" else "[]".take(0)
                        line(level, "$k$lengthPart:")
                        val joined = (v as List<Any?>).joinToString(options.delimiter.symbol) { renderCell(it) }
                        if (joined.isNotEmpty()) {
                            // reescreve a mesma linha com valores
                            sb.setLength(sb.length - 1)
                            sb.append(' ').append(joined).append('\n')
                        }
                    } else {
                        // para manter a simplicidade (sem objetos como itens de lista)
                        line(level, "$k:")
                        for (item in v) line(level + 1, escape(renderCell(item)))
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
            val cols = hom.first.joinToString(",")
            val lengthPart = if (options.lengthMarker) "[${list.size}]" else "[]".take(0)
            line(level, "items$lengthPart{$cols}:")
            val delim = options.delimiter.symbol
            for (row in hom.second) line(level + 1, row.joinToString(delim) { renderCell(it) })
            return
        }
        // fallback: array inline se primitivo
        val prim = list.all { it == null || it is String || it is Number || it is Boolean }
        if (prim) {
            val lengthPart = if (options.lengthMarker) "[${list.size}]" else "[]".take(0)
            line(level, "items$lengthPart:")
            val joined = list.joinToString(options.delimiter.symbol) { renderCell(it) }
            if (joined.isNotEmpty()) {
                sb.setLength(sb.length - 1)
                sb.append(' ').append(joined).append('\n')
            }
        } else {
            // sem list items (- ), representamos como objeto com índices
            line(level, "items:")
            list.forEachIndexed { i, v -> line(level + 1, "$i: ${renderScalarOrInline(v)}") }
        }
    }

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
        repeat(level) { sb.append(" ".repeat(options.indent)) }
        sb.append(text).append('\n')
    }

    /** Normaliza números: sem expoente, sem zeros à direita, -0 → 0, NaN/Inf → null */
    private fun formatCanonicalNumber(n: Number): String {
        val d = n.toDouble()
        if (!d.isFinite()) return "null"
        val bd = try {
            java.math.BigDecimal(n.toString())
        } catch (_: Exception) {
            java.math.BigDecimal.valueOf(d)
        }
        val norm = bd.stripTrailingZeros()
        val plain = norm.toPlainString()
        return if (plain == "-0") "0" else plain
    }
}

// ---------- Helpers ----------

class ToonParseException(message: String) : RuntimeException(message)

/** Split ciente de aspas e escapes; só divide no delimitador quando fora de aspas. */
private fun splitDelimitedAware(text: String, delimiter: Char): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    var inQuotes = false
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            // mantém par literal; unescape será tratado depois
            cur.append(c).append(text[i + 1]); i += 2; continue
        }
        if (c == '"') { inQuotes = !inQuotes; cur.append(c); i++; continue }
        if (!inQuotes && c == delimiter) { out += cur.toString(); cur.setLength(0); i++; continue }
        cur.append(c); i++
    }
    out += cur.toString()
    return out
}

/** Índice do primeiro caractere 'ch' não-quoted; -1 se não encontrado. */
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

/**
 * Escapa caracteres especiais.
 * - quoted strings/keys usam as 5 escapes: \\, \", \n, \r, \t
 * - forCell=true evita aspas, só aplica escapes necessários e barra invertida no delimitador
 */
private fun escape(s: String, quoteIfNeeded: Boolean = true, forCell: Boolean = false): String {
    val hasCtrl = s.any { it == '\n' || it == '\r' || it == '\t' }
    val hasStruct = s.any { it == ':' || it == '"' || it == '\\' || it == '[' || it == ']' || it == '{' || it == '}' }
    val startsWithHyphen = s.startsWith("-")
    val looksBooleanNull = (s == "true" || s == "false" || s == "null")
    val looksNumeric = Regex("^-?\\d+(?:\\.\\d+)?(?:[eE][+\\-]?\\d+)?$").matches(s) || Regex("^0\\d+$").matches(s)
    val needsQuote = quoteIfNeeded && (s.isEmpty() || s.first().isWhitespace() || s.last().isWhitespace()
            || hasCtrl || hasStruct || startsWithHyphen || looksBooleanNull || looksNumeric)

    val body = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    return when {
        needsQuote -> "\"$body\""
        forCell -> body // células já serão split por delimitador ativo; se contiver, caller deve ter quotado
        else -> body
    }
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
                else -> throw ToonParseException("Invalid escape sequence: \\${s[i + 1]}")
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

// DSL helpers
fun toon(text: String): ToonNode = ToonNode(Toon.decode(text))
inline fun <reified T : Any> toon(text: String, block: ToonNode.() -> T): T {
    val root = ToonNode(Toon.decode(text))
    return root.block()
}
