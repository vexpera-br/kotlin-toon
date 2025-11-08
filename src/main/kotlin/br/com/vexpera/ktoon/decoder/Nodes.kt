package br.com.vexpera.ktoon.decoder

import br.com.vexpera.ktoon.decoder.DecodeException
import br.com.vexpera.ktoon.decoder.DecoderOptions
import br.com.vexpera.ktoon.decoder.Header
import br.com.vexpera.ktoon.decoder.Keys
import br.com.vexpera.ktoon.decoder.Line
import br.com.vexpera.ktoon.decoder.LineScanner
import br.com.vexpera.ktoon.decoder.ParseContext
import br.com.vexpera.ktoon.decoder.Splitters
import br.com.vexpera.ktoon.decoder.ValueParsers
import br.com.vexpera.ktoon.decoder.firstUnquotedColonIndex
import br.com.vexpera.ktoon.decoder.firstUnquotedIndexOf
import br.com.vexpera.ktoon.decoder.parseError
import java.util.LinkedHashMap

/**
 * Estruturas de parsing hierárquico (objetos, listas e arrays).
 *
 * Implementa um modelo baseado em "nós" com chain-of-responsibility:
 * cada nó decide se pode consumir uma linha e processar um bloco.
 */
internal object Nodes {

    // ------------------------------------------------------------
    // Interface base para manipuladores de linhas dentro de um nó
    // ------------------------------------------------------------
    internal interface Handler {
        fun canHandle(ln: Line, ctx: ParseContext): Boolean
        fun handle(node: Node, ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean
    }

    // ------------------------------------------------------------
    // Nó base abstrato
    // ------------------------------------------------------------
    internal abstract class Node(
        val parentKey: String?,
        val baseIndent: Int
    ) {
        protected val handlers: MutableList<Handler> = mutableListOf()

        open fun consume(scanner: LineScanner, expectedIndent: Int, ctx: ParseContext): Any? {
            var result: Any? = null
            while (true) {
                val ln = scanner.peek() ?: break
                if (ln.depth < expectedIndent) break // dedent → encerra

                if (!tryHandle(ln, scanner, ctx)) break
                result = currentValue()
            }
            return result ?: currentValue()
        }

        protected fun tryHandle(ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean {
            if (ln.content.isEmpty()) {
                scanner.next() // consome linha em branco
                return true
            }
            for (h in handlers) {
                if (h.canHandle(ln, ctx)) {
                    return h.handle(this, ln, scanner, ctx)
                }
            }
            return false
        }

        protected abstract fun currentValue(): Any?
    }

    // ------------------------------------------------------------
    // ObjectNode (§8) — chave/valor e blocos aninhados
    // ------------------------------------------------------------
    class ObjectNode(parentKey: String?, baseIndent: Int) : Node(parentKey, baseIndent) {
        private val map = LinkedHashMap<String, Any?>()

        init {
            handlers += KeyValueHeaderHandler()
            handlers += KeyValuePrimitiveHandler()
            handlers += KeyNestedObjectHandler()
        }

        override fun currentValue(): Any? = map

        // ----------------- Handlers -----------------

        private inner class KeyValueHeaderHandler : Handler {
            override fun canHandle(ln: Line, ctx: ParseContext): Boolean {
                if (ln.depth != baseIndent) return false
                val colon = ln.content.firstUnquotedColonIndex()
                if (colon < 0) return false
                return Header.tryParse(ln.content) != null
            }

            override fun handle(node: Node, ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean {
                scanner.next()
                val hdr = Header.parseOrThrow(ln.content, ctx)
                val key = hdr.key ?: parseError(ln, "Header at object level must have a key")

                val value: Any? = when {
                    hdr.fields != null -> TabularArrayNode(key, hdr).consume(scanner, ln.depth, ctx)
                    hdr.inlineValues != null -> InlineArrayNode(key, hdr).consume(scanner, ln.depth, ctx)
                    else -> ListArrayNode(key, hdr).consume(scanner, ln.depth, ctx)
                }

                map[key] = value
                return true
            }
        }

        private inner class KeyValuePrimitiveHandler : Handler {
            override fun canHandle(ln: Line, ctx: ParseContext): Boolean {
                if (ln.depth != baseIndent) return false
                val colon = ln.content.firstUnquotedColonIndex()
                if (colon < 0) return false
                return Header.tryParse(ln.content) == null
            }

            override fun handle(node: Node, ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean {
                scanner.next()
                val colon = ln.content.firstUnquotedColonIndex()
                val kTok = ln.content.substring(0, colon).trim()
                val vTok = ln.content.substring(colon + 1).trimStart()
                val key = Keys.decodeKeyToken(kTok)

                if (vTok.isEmpty()) {
                    val child = ObjectNode(key, baseIndent + 1)
                    val v = child.consume(scanner, baseIndent + 1, ctx)
                    map[key] = v
                } else {
                    val v = ValueParsers.parsePrimitiveToken(vTok, ctx, ctx.documentDelimiter)
                    map[key] = v
                }
                return true
            }
        }

        private inner class KeyNestedObjectHandler : Handler {
            override fun canHandle(ln: Line, ctx: ParseContext): Boolean {
                if (ln.depth != baseIndent) return false
                val colon = ln.content.firstUnquotedColonIndex()
                if (colon < 0) return false
                if (Header.tryParse(ln.content) != null) return false
                val vTok = ln.content.substring(colon + 1).trim()
                return vTok.isEmpty()
            }

            override fun handle(node: Node, ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean {
                scanner.next()
                val key = Keys.decodeKeyToken(
                    ln.content.substring(0, ln.content.firstUnquotedColonIndex()).trim()
                )
                val child = ObjectNode(key, baseIndent + 1)
                val v = child.consume(scanner, baseIndent + 1, ctx)
                map[key] = v
                return true
            }
        }
    }

    // ------------------------------------------------------------
    // InlineArrayNode (§9.1)
    // ------------------------------------------------------------
    class InlineArrayNode(parentKey: String?, private val hdr: Header) : Node(parentKey, 0) {
        private val list = mutableListOf<Any?>()

        init { parseInline() }

        override fun currentValue(): Any? = list

        private fun parseInline() {
            val raw = hdr.inlineValues ?: ""
            val parts = Splitters.splitRespectingQuotes(raw, hdr.delim.ch)

            for (p in parts) {
                val v = ValueParsers.parsePrimitiveToken(
                    p.trim(),
                    ParseContext(DecoderOptions()), // contexto leve
                    documentDelimiter = ','
                )
                list += v
            }

            if (hdr.length != list.size) {
                throw DecodeException("Inline array length mismatch: expected ${hdr.length}, got ${list.size}")
            }
        }
    }

    // ------------------------------------------------------------
    // TabularArrayNode (§9.3)
    // ------------------------------------------------------------
    class TabularArrayNode(parentKey: String?, private val hdr: Header) : Node(parentKey, 0) {
        private val rows = mutableListOf<LinkedHashMap<String, Any?>>()
        override fun currentValue(): Any? = rows

        override fun consume(scanner: LineScanner, headerIndent: Int, ctx: ParseContext): Any? {
            val rowIndent = headerIndent + 1
            while (true) {
                val ln = scanner.peek() ?: break
                if (ln.depth < rowIndent) break

                if (ln.depth > rowIndent && ctx.options.strict)
                    parseError(ln, "Unexpected indentation inside tabular rows")

                if (ln.content.isEmpty()) {
                    if (ctx.options.strict)
                        parseError(ln, "Blank line inside tabular rows is not allowed")
                    scanner.next()
                    continue
                }

                val firstDelim = ln.content.firstUnquotedIndexOf(hdr.delim.ch)
                val firstColon = ln.content.firstUnquotedColonIndex()
                if (firstColon >= 0 && (firstDelim < 0 || firstColon < firstDelim)) break

                scanner.next()
                val parts = Splitters.splitRespectingQuotes(ln.content, hdr.delim.ch)
                if (hdr.fields == null)
                    parseError(ln, "Missing fields in tabular header")

                if (ctx.options.strict && parts.size != hdr.fields.size)
                    parseError(ln, "Tabular row width mismatch: expected ${hdr.fields.size}, got ${parts.size}")

                val obj = LinkedHashMap<String, Any?>()
                hdr.fields!!.forEachIndexed { idx, f ->
                    val v = ValueParsers.parsePrimitiveToken(parts.getOrElse(idx) { "" }, ctx, ',')
                    obj[f] = v
                }
                rows += obj
            }

            if (ctx.options.strict && rows.size != hdr.length)
                throw DecodeException("Tabular row count mismatch: expected ${hdr.length}, got ${rows.size}")

            return rows
        }
    }

    // ------------------------------------------------------------
    // ListArrayNode (§9.2, §9.4, §10)
    // ------------------------------------------------------------
    class ListArrayNode(parentKey: String?, private val hdr: Header) : Node(parentKey, 0) {
        private val items = mutableListOf<Any?>()
        override fun currentValue(): Any? = items

        override fun consume(scanner: LineScanner, headerIndent: Int, ctx: ParseContext): Any? {
            val itemIndent = headerIndent + 1
            while (true) {
                val ln = scanner.peek() ?: break
                if (ln.depth < itemIndent) break
                if (ln.content.isEmpty()) {
                    if (ctx.options.strict)
                        parseError(ln, "Blank line inside list array")
                    scanner.next()
                    continue
                }

                if (ln.depth == itemIndent && ln.content.startsWith("- ")) {
                    scanner.next()
                    val afterHyphen = ln.content.substring(2).trimStart()
                    handleListItem(afterHyphen, ln, scanner, ctx)
                    continue
                } else break
            }

            if (ctx.options.strict && items.size != hdr.length)
                throw DecodeException("List array item count mismatch: expected ${hdr.length}, got ${items.size}")

            return items
        }

        private fun handleListItem(afterHyphen: String, ln: Line, scanner: LineScanner, ctx: ParseContext) {
            val firstColon = afterHyphen.firstUnquotedColonIndex()
            val headerTry = Header.tryParse(afterHyphen)

            when {
                // - [M<delim?>]: ...
                headerTry != null && headerTry.key == null -> {
                    val childHdr = Header.parseOrThrow(afterHyphen, ctx)
                    val value = when {
                        childHdr.fields != null -> TabularArrayNode(null, childHdr).consume(scanner, ln.depth, ctx)
                        childHdr.inlineValues != null -> InlineArrayNode(null, childHdr).consume(scanner, ln.depth, ctx)
                        else -> ListArrayNode(null, childHdr).consume(scanner, ln.depth, ctx)
                    }
                    items += value
                }

                // - key[M...]: ...
                headerTry != null && headerTry.key != null -> {
                    val itemObj = LinkedHashMap<String, Any?>()
                    val childHdr = Header.parseOrThrow(afterHyphen, ctx)
                    val key = childHdr.key!!
                    val value = when {
                        childHdr.fields != null -> TabularArrayNode(key, childHdr).consume(scanner, ln.depth, ctx)
                        childHdr.inlineValues != null -> InlineArrayNode(key, childHdr).consume(scanner, ln.depth, ctx)
                        else -> ListArrayNode(key, childHdr).consume(scanner, ln.depth, ctx)
                    }
                    itemObj[key] = value
                    parseSiblingFields(scanner, ctx, ln.depth + 1, itemObj)
                    items += itemObj
                }

                // - key: value  |  - key:
                firstColon >= 0 -> {
                    val kTok = afterHyphen.substring(0, firstColon).trim()
                    val vTok = afterHyphen.substring(firstColon + 1).trimStart()
                    val key = Keys.decodeKeyToken(kTok)
                    val itemObj = LinkedHashMap<String, Any?>()

                    if (vTok.isEmpty()) {
                        val nested = ObjectNode(key, ln.depth + 2) // +2 (§10)
                        val value = nested.consume(scanner, ln.depth + 2, ctx)
                        itemObj[key] = value
                    } else {
                        val value = ValueParsers.parsePrimitiveToken(vTok, ctx, ctx.documentDelimiter)
                        itemObj[key] = value
                    }

                    parseSiblingFields(scanner, ctx, ln.depth + 1, itemObj)
                    items += itemObj
                }

                // - primitive
                else -> {
                    val v = ValueParsers.parsePrimitiveToken(afterHyphen, ctx, ',')
                    items += v
                }
            }
        }

        private fun parseSiblingFields(
            scanner: LineScanner,
            ctx: ParseContext,
            expectedIndent: Int,
            acc: LinkedHashMap<String, Any?>
        ) {
            while (true) {
                val ln = scanner.peek() ?: break
                if (ln.depth < expectedIndent) break
                if (ln.content.isEmpty()) {
                    if (ctx.options.strict)
                        parseError(ln, "Blank line inside list-item fields")
                    scanner.next()
                    continue
                }

                val colon = ln.content.firstUnquotedColonIndex()
                val headerTry = Header.tryParse(ln.content)
                when {
                    // header key[...] (precisa de key obrigatoriamente aqui)
                    headerTry != null -> {
                        if (ln.depth != expectedIndent) break
                        scanner.next()
                        val h = Header.parseOrThrow(ln.content, ctx)
                        val key = h.key ?: parseError(ln, "Array header inside list-item must have a key")
                        val value = when {
                            h.fields != null -> TabularArrayNode(key, h).consume(scanner, ln.depth, ctx)
                            h.inlineValues != null -> InlineArrayNode(key, h).consume(scanner, ln.depth, ctx)
                            else -> ListArrayNode(key, h).consume(scanner, ln.depth, ctx)
                        }
                        acc[key] = value
                    }

                    colon >= 0 -> {
                        if (ln.depth != expectedIndent) break
                        scanner.next()
                        val kTok = ln.content.substring(0, colon).trim()
                        val vTok = ln.content.substring(colon + 1).trimStart()
                        val key = Keys.decodeKeyToken(kTok)
                        if (vTok.isEmpty()) {
                            val nested = ObjectNode(key, expectedIndent + 1)
                            val v = nested.consume(scanner, expectedIndent + 1, ctx)
                            acc[key] = v
                        } else {
                            val v = ValueParsers.parsePrimitiveToken(vTok, ctx, ctx.documentDelimiter)
                            acc[key] = v
                        }
                    }

                    else -> break
                }
            }
        }
    }
}
