package br.com.vexpera.ktoon.decoder

/**
 * Recursive-descent parser over the analysed [Line]s (TOON spec v1.4 §5, §6, §8–§12, §14).
 *
 * Library extensions kept for compatibility: comment lines starting with `#` (dropped by [Lines.read]) and the
 * legacy `-: value` entry inside an object, stored under the `null` key.
 */
internal class Parser(private val lines: List<Line>, private val ctx: ParseContext) {

    private var pos = 0
    private val strict get() = ctx.options.strict

    fun parseDocument(): Any? {
        val nonBlank = lines.filter { !it.isBlank }
        if (nonBlank.isEmpty()) return LinkedHashMap<String, Any?>()

        val first = nonBlank.first()
        if (strict && first.depth != 0) fail(first, "Unexpected indentation at root")

        val header = Header.tryParse(first.content)
        if (header != null && header.key == null) {
            ctx.debug(first, "Root array: $header")
            pos = lines.indexOf(first) + 1
            val value = parseArrayBody(header, first, bodyDepth = first.depth + 1)
            skipBlanks()
            lines.getOrNull(pos)?.let { fail(it, "Unexpected content after the root array") }
            return value
        }

        if (nonBlank.size == 1 && !isFieldLine(first.content)) {
            ctx.debug(first, "Root primitive")
            return primitive(first.content)
        }

        ctx.debug(first, "Root object")
        val root = parseObject(depth = 0)
        skipBlanks()
        lines.getOrNull(pos)?.let { fail(it, "Unexpected indentation") }
        return root
    }

    // ---------- Objects (§8, §10) ----------

    private fun parseObject(depth: Int): LinkedHashMap<String?, Any?> {
        val map = LinkedHashMap<String?, Any?>()
        while (true) {
            skipBlanks()
            val ln = lines.getOrNull(pos) ?: break
            if (ln.depth < depth) break
            if (ln.depth > depth && strict) fail(ln, "Unexpected indentation")
            // Non-strict recovery: a deeper line is read as a field of this object, its children relative to it.
            val fieldDepth = maxOf(ln.depth, depth)
            pos++
            parseField(ln.content, ln, fieldDepth, onHyphenLine = false, into = map)
        }
        return map
    }

    /**
     * Parses one field line into [into]. [fieldDepth] is the depth of the field's siblings. For the first field of an
     * object list item ([onHyphenLine]), arrays have their rows/items at [fieldDepth] (one level below the hyphen,
     * §10); nested objects always have their fields at fieldDepth + 1.
     */
    private fun parseField(content: String, ln: Line, fieldDepth: Int, onHyphenLine: Boolean, into: MutableMap<String?, Any?>) {
        if (content.startsWith("-:")) {
            into[null] = primitive(content.substring(2))
            return
        }

        val header = Header.tryParse(content)
        if (header != null) {
            val key = header.key ?: fail(ln, "An array header inside an object needs a key")
            into[key] = parseArrayBody(header, ln, bodyDepth = if (onHyphenLine) fieldDepth else fieldDepth + 1)
            return
        }

        val colon = content.firstUnquotedColonIndex()
        if (colon < 0) fail(ln, "Missing colon after key")
        val key = Keys.decodeKeyToken(content.substring(0, colon))
        val valueToken = content.substring(colon + 1).trim()
        into[key] = if (valueToken.isEmpty()) parseObject(fieldDepth + 1) else primitive(valueToken)
    }

    // ---------- Arrays (§9) ----------

    private fun parseArrayBody(header: Header, ln: Line, bodyDepth: Int): List<Any?> = when {
        header.fields != null -> parseTabular(header, bodyDepth)
        header.inlineValues != null -> parseInline(header, ln)
        else -> parseListItems(header, bodyDepth)
    }

    private fun parseInline(header: Header, ln: Line): List<Any?> {
        val values = Splitters.splitRespectingQuotes(header.inlineValues!!, header.delim.ch).map { primitive(it) }
        if (strict && values.size != header.length)
            fail(ln, "Inline array has ${values.size} values but its header declares ${header.length}")
        return values
    }

    private fun parseTabular(header: Header, bodyDepth: Int): List<Any?> {
        val fields = header.fields!!
        val rows = mutableListOf<Any?>()
        while (true) {
            if (!continueAfterBlanks(bodyDepth, "tabular rows") { isRow(it.content, header.delim) }) break
            val ln = lines.getOrNull(pos) ?: break
            if (ln.depth < bodyDepth) break
            if (ln.depth > bodyDepth && strict) fail(ln, "Unexpected indentation inside tabular rows")
            if (!isRow(ln.content, header.delim)) break
            pos++
            val parts = Splitters.splitRespectingQuotes(ln.content, header.delim.ch)
            if (strict && parts.size != fields.size)
                fail(ln, "Tabular row has ${parts.size} values but the header has ${fields.size} fields")
            val row = LinkedHashMap<String, Any?>()
            fields.forEachIndexed { i, f -> row[f] = primitive(parts.getOrElse(i) { "" }) }
            rows += row
        }
        if (strict && rows.size != header.length)
            fail(lines.getOrNull(pos) ?: Line.empty(), "Tabular array has ${rows.size} rows but its header declares ${header.length}")
        return rows
    }

    private fun parseListItems(header: Header, bodyDepth: Int): List<Any?> {
        val items = mutableListOf<Any?>()
        while (true) {
            if (!continueAfterBlanks(bodyDepth, "list array") { isListItem(it.content) }) break
            val ln = lines.getOrNull(pos) ?: break
            if (ln.depth < bodyDepth) break
            if (ln.depth > bodyDepth && strict) fail(ln, "Unexpected indentation inside list array")
            if (!isListItem(ln.content)) break
            pos++
            items += parseListItem(ln, itemDepth = ln.depth)
        }
        if (strict && items.size != header.length)
            fail(lines.getOrNull(pos) ?: Line.empty(), "List array has ${items.size} items but its header declares ${header.length}")
        return items
    }

    private fun parseListItem(ln: Line, itemDepth: Int): Any? {
        if (ln.content == "-") return LinkedHashMap<String, Any?>()
        val rest = ln.content.substring(2).trim()

        val header = Header.tryParse(rest)
        if (header != null && header.key == null) return parseArrayBody(header, ln, bodyDepth = itemDepth + 1)

        if (header != null || isFieldLine(rest)) {
            val obj = LinkedHashMap<String?, Any?>()
            parseField(rest, ln, fieldDepth = itemDepth + 1, onHyphenLine = true, into = obj)
            obj.putAll(parseObject(itemDepth + 1))
            return obj
        }
        return primitive(rest)
    }

    // ---------- Helpers ----------

    /**
     * Handles blank lines inside an array block (§12): when the next non-blank line still belongs to the block,
     * strict mode fails and non-strict mode skips the blanks. Returns false when the block ends at the blanks.
     */
    private fun continueAfterBlanks(bodyDepth: Int, what: String, belongs: (Line) -> Boolean): Boolean {
        if (lines.getOrNull(pos)?.isBlank != true) return true
        var next = pos
        while (lines.getOrNull(next)?.isBlank == true) next++
        val following = lines.getOrNull(next) ?: return false
        if (following.depth != bodyDepth || !belongs(following)) return false
        if (strict) fail(lines[pos], "Blank line inside $what is not allowed in strict mode")
        pos = next
        return true
    }

    private fun skipBlanks() {
        while (lines.getOrNull(pos)?.isBlank == true) pos++
    }

    /** Row or key-value line at row depth (§9.3 disambiguation). */
    private fun isRow(content: String, delim: Delim): Boolean {
        val colon = content.firstUnquotedColonIndex()
        if (colon < 0) return true
        val d = content.firstUnquotedIndexOf(delim.ch)
        return d in 0 until colon
    }

    private fun isListItem(content: String) = content == "-" || content.startsWith("- ")

    private fun isFieldLine(content: String) = content.firstUnquotedColonIndex() >= 0

    private fun primitive(token: String): Any? = ValueParsers.parsePrimitiveToken(token, ctx, ctx.documentDelimiter)

    private fun fail(ln: Line, msg: String): Nothing = parseError(ln, msg)
}
