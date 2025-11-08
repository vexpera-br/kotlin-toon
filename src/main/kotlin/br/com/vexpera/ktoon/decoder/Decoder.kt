@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package br.com.vexpera.ktoon.decoder

import java.util.LinkedHashMap

/**
 * Opções de decodificação.
 */
data class DecoderOptions(
    val strict: Boolean = true,
    val debug: Boolean = false,
    val indentWidth: Int = 2
)

/**
 * Classe principal de decodificação do formato KTOON.
 */
class Decoder(private val options: DecoderOptions = DecoderOptions()) {

    fun decode(text: String): Any? {
        val lines = Lexer.lines(text)
        val scanner = LineScanner(lines, options)
        val ctx = ParseContext(options)

        // Detecta o tipo de estrutura raiz
        return when (val root = detectRootForm(scanner)) {
            RootForm.EMPTY_OBJECT -> LinkedHashMap<String, Any?>()
            RootForm.PRIMITIVE -> parseSinglePrimitive(scanner, ctx)
            RootForm.ROOT_ARRAY -> parseRootArray(scanner, ctx)
            RootForm.ROOT_OBJECT -> parseRootObject(scanner, ctx)
        }
    }

    /**
     * Determina a forma raiz do documento (§5 da spec)
     */
    private fun detectRootForm(scanner: LineScanner): RootForm {
        val nonEmptyDepth0 = mutableListOf<Line>()
        for (ln in scanner.remaining()) {
            if (ln.isBlankOutOfArrayScope && ln.depth == 0) continue
            if (ln.depth == 0 && ln.content.isNotEmpty()) nonEmptyDepth0 += ln
        }

        if (nonEmptyDepth0.isEmpty()) return RootForm.EMPTY_OBJECT

        val first = nonEmptyDepth0.first()

        // Root array
        if (Header.tryParse(first.content)?.let { it.key == null } == true)
            return RootForm.ROOT_ARRAY

        // Primitive
        if (nonEmptyDepth0.size == 1) {
            val c = first.content
            val isKeyValue = c.firstUnquotedColonIndex() >= 0
            val isHeader = Header.tryParse(c) != null
            if (!isKeyValue && !isHeader) return RootForm.PRIMITIVE
        }

        return RootForm.ROOT_OBJECT
    }

    private fun parseSinglePrimitive(scanner: LineScanner, ctx: ParseContext): Any? {
        val ln = scanner.nextNonBlankDepth0OrThrow()
        val v = ValueParsers.parsePrimitiveToken(ln.content, ctx, documentDelimiter = ',')
        scanner.consumeUntilEnd()
        return v
    }

    private fun parseRootArray(scanner: LineScanner, ctx: ParseContext): Any? {
        val ln = scanner.nextNonBlankDepth0OrThrow()
        val hdr = Header.parseOrThrow(ln.content, ctx)
        return when {
            hdr.fields != null -> Nodes.TabularArrayNode(null, hdr).consume(scanner, ln.depth, ctx)
            hdr.inlineValues != null -> Nodes.InlineArrayNode(null, hdr).consume(scanner, ln.depth, ctx)
            else -> Nodes.ListArrayNode(null, hdr).consume(scanner, ln.depth, ctx)
        }
    }

    private fun parseRootObject(scanner: LineScanner, ctx: ParseContext): Any? =
        Nodes.ObjectNode(parentKey = null, baseIndent = 0).consume(scanner, 0, ctx)
}
