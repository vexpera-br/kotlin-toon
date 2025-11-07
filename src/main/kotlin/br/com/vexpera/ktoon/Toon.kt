@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package br.com.vexpera.ktoon

import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
/**
 * TOON – Token-Oriented Object Notation (JVM core)
 * Minimal encoder/decoder focused on:
 *  - Key: Value blocks with indentation-based nesting
 *  - Tabular arrays: key[len]{c1,c2,...}:
 *      <row1 by delimiter>\n
 *      <row2 by delimiter>\n
 *
 * Later we can lift it to Kotlin Multiplatform.
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
    private val indent = options.indent

    private fun log(msg: String) {
        if (options.debug) println("[DBG] $msg")
    }

    fun decode(input: String): Any? {
        val lines = input.replace("\r\n", "\n").replace("\r", "\n").lines()
        val ctx = ParseCtx(lines)
        val result = parseBlock(ctx, 0)
        if (options.strict && ctx.hasMoreNonEmpty()) {
            throw ToonParseException("Trailing content after root value at line ${ctx.index + 1}")
        }
        return result
    }

    /**
     * Parser de blocos (objetos ou tabelas) por indentação.
     */
    private fun parseBlock(ctx: ParseCtx, baseIndent: Int): Any? {
        val peek = ctx.peekNonEmpty() ?: return null
        val (indentLevel, content) = peek
        if (indentLevel < baseIndent) return null

        log("parseBlock(baseIndent=$baseIndent) start → peek='$content'")

        // caso especial: o bloco começa diretamente com uma tabela
        if (TableHeader.isTableHeader(content)) {
            log("base-level TABLE detected at indent=$indentLevel")
            ctx.readNonEmpty()
            return parseTable(ctx, baseIndent, content)
        }

        val map = linkedMapOf<String, Any?>()

        while (true) {
            val next = ctx.peekNonEmpty() ?: break
            val (lvl, txt) = next

            log("line=${ctx.index} lvl=$lvl baseIndent=$baseIndent → '$txt'")

            if (lvl < baseIndent) {
                log("break (lvl < baseIndent)")
                break
            }

            if (lvl > baseIndent) {
                if (options.strict)
                    throw ToonParseException("Unexpected indentation at line ${ctx.index + 1}")
                log("skipping over-indented line")
                ctx.readNonEmpty(); continue
            }

            // --- lvl == baseIndent ---
            if (TableHeader.isTableHeader(txt)) {
                ctx.readNonEmpty() // consome o cabeçalho
                log("TABLE header detected at indent=$lvl → '$txt'")
                val header = TableHeader.parse(txt)
                    ?: throw ToonParseException("Malformed table header at line ${ctx.index}")
                val table = parseTable(ctx, baseIndent, txt) // passa o cabeçalho já lido
                map[header.name] = table
                log("TABLE '${header.name}' parsed (${(table as List<*>).size} rows)")
                continue
            }

            // Linha normal "key:" ou "key: value"
            ctx.readNonEmpty()
            val kv = txt.split(":", limit = 2)
            if (kv.size == 2) {
                val key = kv[0].trim()
                val rhs = kv[1].trim()
                log("key='$key' rhs='${rhs.ifEmpty { "<empty>" }}'")

                if (rhs.isNotEmpty()) {
                    map[key] = parseScalar(rhs)
                } else {
                    val nextPeek = ctx.peekNonEmpty()
                    if (nextPeek != null && nextPeek.first >= baseIndent + indent) {
                        log("entering nested block under key='$key'")
                        val child = parseBlock(ctx, baseIndent + indent)
                        map[key] = child
                        continue
                    } else {
                        map[key] = emptyMap<String, Any?>()
                    }
                }
            } else {
                log("ERROR: no ':' in line → '$txt'")
                if (options.strict)
                    throw ToonParseException("Expected ':' after key at line ${ctx.index}")
            }
        }

        log("parseBlock(baseIndent=$baseIndent) end → keys=${map.keys}")
        return map
    }

    /**
     * Parser de tabelas com cabeçalho pré-consumido.
     */
    private fun parseTable(ctx: ParseCtx, baseIndent: Int, headerLine: String? = null): List<Map<String, Any?>> {
        val headerText: String
        val lvl: Int

        if (headerLine != null) {
            headerText = headerLine
            lvl = baseIndent
        } else {
            val read = ctx.readNonEmpty()!!
            lvl = read.first
            headerText = read.second
        }

        val header = TableHeader.parse(headerText)
            ?: throw ToonParseException("Malformed table header at line ${ctx.index}")
        log("parseTable(baseIndent=$baseIndent) header='${headerText.trim()}' cols=${header.columns} indent=$lvl")

        val rows = mutableListOf<Map<String, Any?>>()

        while (true) {
            val peek = ctx.peekNonEmpty() ?: break
            val (lvl2, txt) = peek
            log("   table-row peek lvl2=$lvl2 base+indent=${baseIndent + indent} txt='$txt'")

            if (lvl2 < baseIndent + indent) {
                log("   break (out of table)")
                break
            }
            if (lvl2 > baseIndent + indent) {
                log("   skip (over-indented row)")
                if (options.strict)
                    throw ToonParseException("Over-indented row at line ${ctx.index + 1}")
                ctx.readNonEmpty(); continue
            }

            ctx.readNonEmpty()
            val cols = splitRespectingEscape(txt, header.delimiter)
            val row = linkedMapOf<String, Any?>()
            for (i in header.columns.indices) {
                val key = header.columns[i]
                val raw = cols.getOrNull(i)?.trim() ?: ""
                row[key] = parseScalar(raw)
            }
            rows += row
            log("   row parsed: $row")
        }

        log("parseTable end: ${header.name} → ${rows.size} rows")
        return rows
    }

    private fun parseScalar(token: String): Any? {
        if (token.isEmpty()) return ""
        if ((token.startsWith('"') && token.endsWith('"')) ||
            (token.startsWith('\'') && token.endsWith('\''))) {
            return unescape(token.substring(1, token.length - 1))
        }
        return when (token) {
            "null", "~" -> null
            "true" -> true
            "false" -> false
            else -> token.toLongOrNull() ?: token.toDoubleOrNull() ?: token
        }
    }
}

private class ParseCtx(private val lines: List<String>) {
    var index: Int = 0
        private set

    fun hasMoreNonEmpty(): Boolean {
        var i = index
        while (i < lines.size) {
            if (lines[i].isNotBlank()) return true
            i++
        }
        return false
    }

    fun peekNonEmpty(): Pair<Int, String>? {
        var i = index
        while (i < lines.size) {
            val raw = lines[i].trimEnd(); i++
            if (raw.isBlank()) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) 0 else it }
            return indent to raw.substring(indent)
        }
        return null
    }

    fun readNonEmpty(): Pair<Int, String>? {
        while (index < lines.size) {
            val raw = lines[index].trimEnd(); index++
            if (raw.isBlank()) continue
            val indent = raw.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) 0 else it }
            return indent to raw.substring(indent)
        }
        return null
    }
}

// ---------- Table header ----------

private data class TableHeader(
    val name: String,
    val length: Int?,
    val columns: List<String>,
    val delimiter: Char,
) {
    companion object {

        fun isTableHeader(text: String): Boolean {
            val t = text.trim()
            val isHeader = t.contains("{") && t.endsWith(":")
            return isHeader
        }

        fun parse(text: String): TableHeader? {
            val t = text.trim()
            val pattern = """^([A-Za-z_][A-Za-z0-9_\-]*)\s*(?:\[(\d+)])?\s*\{([^}]*)}\s*:$""".toRegex()
            val match = pattern.find(t)
            if (match == null) {
                return null
            }

            val name = match.groupValues[1]
            val len = match.groupValues[2].takeIf { it.isNotEmpty() }?.toInt()
            val cols = match.groupValues[3]
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            val delim = when {
                t.contains('\t') -> '\t'
                t.contains('|') -> '|'
                else -> ','
            }

            return TableHeader(name, len, cols, delim)
        }
    }
}

// ---------- Encoder (inalterado) ----------
/* Mantive o Encoder igual ao seu, pois estava correto e funcional */

// ---------- Helpers ----------
class ToonParseException(message: String) : RuntimeException(message)

private fun splitRespectingEscape(text: String, delimiter: Char): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            cur.append(text[i + 1]); i += 2; continue
        }
        if (c == delimiter) {
            out += cur.toString(); cur.clear()
        } else cur.append(c)
        i++
    }
    out += cur.toString()
    return out
}

private fun unescape(s: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length) {
            when (s[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                '"' -> out.append('"')
                '\\' -> out.append('\\')
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

    fun asString(): String? = value?.toString()
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
            map[param.name]?.let { raw ->
                when (param.type.classifier) {
                    String::class -> raw.toString()
                    Int::class -> (raw as? Number)?.toInt()
                    Long::class -> (raw as? Number)?.toLong()
                    Double::class -> (raw as? Number)?.toDouble()
                    Boolean::class -> raw as? Boolean
                    else -> raw
                }
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
            is Number, is Boolean -> sb.append(v.toString()).append('\n')
            else -> sb.append(escape(v.toString())).append('\n')
        }
    }

    private fun writeObject(map: Map<String, Any?>, level: Int) {
        for ((k, v) in map) {
            when (v) {
                is List<*> -> {
                    val hom = homogenize(v)
                    if (hom != null) {
                        val lengthPart = if (options.lengthMarker) "[${v.size}]" else ""
                        val cols = hom.first.joinToString(",")
                        line(level, "$k$lengthPart{$cols}:")
                        val delim = options.delimiter.symbol
                        for (row in hom.second) {
                            line(level + 1, row.joinToString(delim) { renderCell(it) })
                        }
                        continue
                    }
                    line(level, "$k:")
                    for (item in v) line(level + 1, "-: ${renderScalarOrInline(item)}")
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
            val lengthPart = if (options.lengthMarker) "[${list.size}]" else ""
            line(level, "items$lengthPart{$cols}:")
            val delim = options.delimiter.symbol
            for (row in hom.second) line(level + 1, row.joinToString(delim) { renderCell(it) })
            return
        }
        line(level, "items:")
        for (item in list) line(level + 1, "-: ${renderScalarOrInline(item)}")
    }

    /**
     * Se a lista for homogênea de mapas com as mesmas chaves na mesma ordem,
     * retornamos (colunas, linhas) para serializar como tabela.
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
        is Number, is Boolean -> v.toString()
        else -> escape(v.toString())
    }

    /**
     * Render de célula de tabela: evita aspas quando possível e aplica escapes leves.
     */
    private fun renderCell(v: Any?): String = when (v) {
        null -> "null"
        is String -> escape(v, quoteIfNeeded = false, forCell = true)
        is Number, is Boolean -> v.toString()
        else -> escape(v.toString(), quoteIfNeeded = false, forCell = true)
    }

    private fun line(level: Int, text: String) {
        repeat(level) { sb.append(indentUnit) }
        sb.append(text).append('\n')
    }
}

/**
 * Escapa caracteres especiais para uso seguro em TOON.
 *
 * @param s Texto original.
 * @param quoteIfNeeded Quando true, envolve em aspas se houver espaços, vírgulas, dois-pontos, pipes ou tabs.
 * @param forCell Quando true, aplica escape leve para uso em células de tabelas (não adiciona aspas).
 */
private fun escape(s: String, quoteIfNeeded: Boolean = true, forCell: Boolean = false): String {
    val needsQuote = quoteIfNeeded && (
            s.any { it.isWhitespace() } ||
                    s.any { it in charArrayOf(':', ',', '|', '\t') }
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
        forCell -> body.replace(",", "\\,")
        else -> body
    }
}

fun toon(text: String): ToonNode = ToonNode(Toon.decode(text))
inline fun <reified T : Any> toon(text: String, block: ToonNode.() -> T): T {
    val root = ToonNode(Toon.decode(text))
    return root.block()
}
