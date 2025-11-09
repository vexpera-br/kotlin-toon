package br.com.vexpera.ktoon.decoder

import br.com.vexpera.ktoon.*

internal object Nodes {

    internal interface Handler {
        fun canHandle(ln: Line, ctx: ParseContext): Boolean
        fun handle(node: Node, ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean
    }

    internal abstract class Node(
        val parentKey: String?,
        val baseIndent: Int
    ) {
        protected val handlers: MutableList<Handler> = mutableListOf()

        open fun consume(scanner: LineScanner, expectedIndent: Int, ctx: ParseContext): Any? {
            ctx.debug(scanner.peek() ?: Line.empty(), "Entrando em ${this::class.simpleName} (indent=$expectedIndent, strict=${ctx.options.strict})")
            var result: Any? = null
            while (true) {
                val ln = scanner.peek() ?: break
                ctx.debug(ln, "→ Processando linha '${ln.raw}' (depth=${ln.depth})")

                // Ajuste: indentação leniente aceita níveis mais profundos
                if (ctx.options.strict && ln.depth != expectedIndent) break
                if (!ctx.options.strict && ln.depth < expectedIndent) break

                if (ln.content.trimStart().startsWith("#")) {
                    ctx.debug(ln, "Ignorando comentário")
                    scanner.next()
                    continue
                }
                if (ln.content.isEmpty()) {
                    ctx.debug(ln, "Linha em branco ignorada")
                    scanner.next()
                    continue
                }
                if (!tryHandle(ln, scanner, ctx)) {
                    ctx.debug(ln, "→ Nenhum handler aceitou a linha")
                    break
                }
                result = currentValue()
            }
            ctx.debug(scanner.peek() ?: Line.empty(), "Saindo de ${this::class.simpleName} com valor = $result")
            return result ?: currentValue()
        }

        protected fun tryHandle(ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean {
            for (h in handlers) {
                if (h.canHandle(ln, ctx)) {
                    ctx.debug(ln, "Handler ${h::class.simpleName} aceitou a linha")
                    return h.handle(this, ln, scanner, ctx)
                }
            }
            return false
        }

        protected abstract fun currentValue(): Any?
    }

    class ObjectNode(parentKey: String?, baseIndent: Int) : Node(parentKey, baseIndent) {
        private val map = LinkedHashMap<String?, Any?>()

        init {
            handlers += AnonymousListEntryHandler()
            handlers += KeyValueHeaderHandler()
            handlers += KeyValuePrimitiveHandler()
            handlers += KeyNestedObjectHandler()
        }

        override fun currentValue(): Any? = map

        private inner class AnonymousListEntryHandler : Handler {
            override fun canHandle(ln: Line, ctx: ParseContext): Boolean =
                ln.depth == baseIndent && ln.content.trimStart().startsWith("-:")

            override fun handle(node: Node, ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean {
                scanner.next()
                val after = ln.content.trimStart().substringAfter("-:").trimStart()
                val value = ValueParsers.parsePrimitiveToken(after, ctx, ctx.documentDelimiter)
                ctx.debug(ln, "Tratando -: como chave nula → $value")
                map[null] = value
                return true
            }
        }

        private inner class KeyValueHeaderHandler : Handler {
            override fun canHandle(ln: Line, ctx: ParseContext): Boolean {
                if (ln.depth != baseIndent) return false
                val colon = ln.content.firstUnquotedColonIndex()
                if (colon < 0) return false
                return Header.tryParse(ln.content) != null
            }

            override fun handle(node: Node, ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean {
                scanner.next()
                ctx.debug(ln, "Processando Header")
                val hdr = Header.parseOrThrow(ln.content, ctx)
                val key = hdr.key ?: parseError(ln, "Header at object level must have a key")

                val value = when {
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
                if (ctx.options.strict) {
                    if (ln.depth != baseIndent) return false
                } else {
                    if (ln.depth < baseIndent) return false
                }
                val colon = ln.content.firstUnquotedColonIndex()
                if (colon < 0) return false
                return Header.tryParse(ln.content) == null
            }

            override fun handle(node: Node, ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean {
                scanner.next()
                ctx.debug(ln, "Processando chave:valor primitivo")
                val colon = ln.content.firstUnquotedColonIndex()
                val kTok = ln.content.substring(0, colon).trim()
                val vTok = ln.content.substring(colon + 1).trimStart()
                val key = Keys.decodeKeyToken(kTok)

                if (vTok.isEmpty()) {
                    ctx.debug(ln, "Valor está vazio → criando objeto filho")
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
                if (ctx.options.strict) {
                    if (ln.depth != baseIndent) return false
                } else {
                    if (ln.depth < baseIndent) return false
                }
                val colon = ln.content.firstUnquotedColonIndex()
                if (colon < 0) return false
                if (Header.tryParse(ln.content) != null) return false
                val vTok = ln.content.substring(colon + 1).trim()
                return vTok.isEmpty()
            }

            override fun handle(node: Node, ln: Line, scanner: LineScanner, ctx: ParseContext): Boolean {
                scanner.next()
                ctx.debug(ln, "Processando objeto aninhado")
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

    class InlineArrayNode(parentKey: String?, private val hdr: Header) : Node(parentKey, 0) {
        private val list = mutableListOf<Any?>()
        init { parseInline() }
        override fun currentValue(): Any? = list

        private fun parseInline() {
            val raw = hdr.inlineValues ?: ""
            val parts = Splitters.splitRespectingQuotes(raw, hdr.delim.ch)
            for (p in parts) {
                val v = ValueParsers.parsePrimitiveToken(p.trim(), ParseContext(DecoderOptions()), ',')
                list += v
            }
            if (hdr.length != list.size) {
                throw DecodeException("Inline array length mismatch: expected ${hdr.length}, got ${list.size}")
            }
        }
    }

    class TabularArrayNode(parentKey: String?, private val hdr: Header) : Node(parentKey, 0) {
        private val rows = mutableListOf<LinkedHashMap<String, Any?>>()
        override fun currentValue(): Any? = rows

        override fun consume(scanner: LineScanner, headerIndent: Int, ctx: ParseContext): Any? {
            val rowIndent = headerIndent + 1
            var rowCount = 0
            ctx.debug(scanner.peek() ?: Line.empty(), "Iniciando parsing de tabela com indent=$rowIndent")

            while (true) {
                val ln = scanner.peek() ?: break

                // Ignora comentários
                if (ln.content.startsWith("#")) {
                    scanner.next()
                    continue
                }

                // Trata linhas em branco
                if (ln.content.isEmpty()) {
                    ctx.debug(ln, "Linha em branco detectada")
                    if (ctx.options.strict && !hdr.hasLengthMarker) {
                        ctx.debug(ln, "Falha: linha em branco em tabela sem marcador de tamanho")
                        parseError(ln, "Blank line inside tabular rows is not allowed")
                    } else {
                        scanner.next()
                        continue
                    }
                }

                // Detecção de fim de tabela
                if (ln.depth < rowIndent) {
                    if (ctx.options.strict && rowCount < hdr.length) {
                        ctx.debug(ln, "Fim prematuro: esperava ${hdr.length}, tinha $rowCount")
                        parseError(ln, "Premature end of tabular rows: expected ${hdr.length}, got $rowCount")
                    }
                    break
                }

                if (ctx.options.strict && ln.depth > rowIndent) {
                    ctx.debug(ln, "Indentação inesperada dentro da tabela")
                    parseError(ln, "Unexpected indentation inside tabular rows")
                }

                if (ctx.options.strict && rowCount >= hdr.length) {
                    ctx.debug(ln, "Excesso de linhas na tabela")
                    parseError(ln, "Too many tabular rows: expected ${hdr.length}")
                }

                // Consome linha válida
                val line = scanner.next() ?: break
                val parts = Splitters.splitRespectingQuotes(line.content, hdr.delim.ch)

                if (hdr.fields == null)
                    parseError(line, "Missing fields in tabular header")

                if (ctx.options.strict && parts.size != hdr.fields.size)
                    parseError(line, "Tabular row width mismatch: expected ${hdr.fields.size}, got ${parts.size}")

                val obj = LinkedHashMap<String, Any?>()
                hdr.fields!!.forEachIndexed { idx: Int, f: String ->
                    val token = parts.getOrNull(idx) ?: ""
                    val v = ValueParsers.parsePrimitiveToken(token, ctx, hdr.delim.ch)
                    obj[f] = v
                }

                ctx.debug(line, "Linha tabular parseada → $obj")
                rows += obj
                rowCount++
            }

            if (ctx.options.strict && rowCount != hdr.length)
                parseError(scanner.peek() ?: Line.empty(), "Tabular array row count mismatch: expected ${hdr.length}, got $rowCount")

            return rows
        }
    }

    class ListArrayNode(parentKey: String?, private val hdr: Header) : Node(parentKey, 0) {
        private val items = mutableListOf<Any?>()
        override fun currentValue(): Any? = items

        override fun consume(scanner: LineScanner, headerIndent: Int, ctx: ParseContext): Any? {
            val entryIndent = headerIndent + 1
            var itemCount = 0
            ctx.debug(scanner.peek() ?: Line.empty(), "Iniciando parsing de lista indent=$entryIndent")

            while (true) {
                val ln = scanner.peek() ?: break

                if (ln.content.isEmpty()) {
                    ctx.debug(ln, "Linha em branco ignorada")
                    scanner.next()
                    continue
                }

                if (ln.depth < entryIndent) break
                if (ctx.options.strict && itemCount >= hdr.length)
                    parseError(ln, "Too many list items: expected ${hdr.length}")

                val line = scanner.next()
                    ?: parseError(ln, "Unexpected EOF while reading list item")

                if (!line.content.trimStart().startsWith("-"))
                    parseError(ln, "Expected list item starting with '-'")

                val rest = line.content.trimStart().drop(1).trimStart()

                val colon = rest.firstUnquotedColonIndex()

                // Correção: rejeita mapas em listas no modo strict
                if (ctx.options.strict && (rest.startsWith("[") || rest.startsWith("{") || colon >= 0))
                    parseError(ln, "List item maps are not supported in strict mode")

                val item = if (colon < 0) {
                    ValueParsers.parsePrimitiveToken(rest, ctx, ctx.documentDelimiter)
                } else {
                    val child = ObjectNode(null, entryIndent + 1)
                    val kTok = rest.substring(0, colon).trim()
                    val vTok = rest.substring(colon + 1).trim()
                    val key = Keys.decodeKeyToken(kTok)
                    if (vTok.isEmpty()) {
                        val v = child.consume(scanner, entryIndent + 1, ctx)
                        mapOf(key to v)
                    } else {
                        mapOf(key to ValueParsers.parsePrimitiveToken(vTok, ctx, ctx.documentDelimiter))
                    }
                }

                ctx.debug(ln, "Item de lista parseado → $item")
                items += item
                itemCount++
            }

            if (ctx.options.strict && itemCount != hdr.length)
                parseError(scanner.peek() ?: Line.empty(), "List array item count mismatch: expected ${hdr.length}, got $itemCount")

            return items
        }
    }
}
