@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package br.com.vexpera.ktoon.decoder

import java.util.LinkedHashMap

/**
 * Decoder options
 */
data class DecoderOptions(
    val strict: Boolean = true,
    val debug: Boolean = false,
    val indentWidth: Int = 2
)

/**
 * Main Decoder class
 */
class Decoder(private val options: DecoderOptions = DecoderOptions()) {

    fun decode(text: String): Any? {
        val lines = Lexer.lines(text)
        val scanner = LineScanner(lines, options)
        val ctx = ParseContext(options)

        ctx.debug(null, "Iniciando decodificação")
        ctx.debug(null, "Texto de entrada normalizado:")
        lines.forEachIndexed { i, l -> ctx.debug(null, "  ${i + 1}: $l") }

        // Detecta o tipo de estrutura raiz
        val root = detectRootForm(scanner)
        ctx.debug(null, "Forma raiz detectada: $root")

        return when (root) {
            RootForm.EMPTY_OBJECT -> {
                ctx.debug(null, "Documento vazio → retornando objeto vazio")
                LinkedHashMap<String, Any?>()
            }
            RootForm.PRIMITIVE -> {
                ctx.debug(null, "Documento com valor primitivo raiz")
                parseSinglePrimitive(scanner, ctx)
            }
            RootForm.ROOT_ARRAY -> {
                ctx.debug(null, "Documento com array raiz")
                parseRootArray(scanner, ctx)
            }
            RootForm.ROOT_OBJECT -> {
                ctx.debug(null, "Documento com objeto raiz")
                parseRootObject(scanner, ctx)
            }
        }
    }

    private fun detectRootForm(scanner: LineScanner): RootForm {
        val nonEmptyDepth0 = mutableListOf<Line>()
        for (ln in scanner.remaining()) {
            if (ln.isBlankOutOfArrayScope && ln.depth == 0) continue
            if (ln.depth == 0 && ln.content.isNotEmpty()) nonEmptyDepth0 += ln
        }

        if (nonEmptyDepth0.isEmpty()) return RootForm.EMPTY_OBJECT

        val first = nonEmptyDepth0.first()
        val content = first.content

        // Root array
        if (Header.tryParse(content)?.let { it.key == null } == true)
            return RootForm.ROOT_ARRAY

        // Primitive
        if (nonEmptyDepth0.size == 1) {
            val isKeyValue = content.firstUnquotedColonIndex() >= 0
            val isHeader = Header.tryParse(content) != null
            if (!isKeyValue && !isHeader) return RootForm.PRIMITIVE
        }

        return RootForm.ROOT_OBJECT
    }

    private fun parseSinglePrimitive(scanner: LineScanner, ctx: ParseContext): Any? {
        val ln = scanner.nextNonBlankDepth0OrThrow()
        ctx.debug(ln, "Raiz primitiva detectada: ${ln.content}")
        val v = ValueParsers.parsePrimitiveToken(ln.content, ctx, documentDelimiter = ',')
        scanner.consumeUntilEnd()
        return v
    }

    private fun parseRootArray(scanner: LineScanner, ctx: ParseContext): Any? {
        val ln = scanner.nextNonBlankDepth0OrThrow()
        ctx.debug(ln, "Cabeçalho de array raiz: ${ln.content}")
        val hdr = Header.parseOrThrow(ln.content, ctx)
        return when {
            hdr.fields != null -> {
                ctx.debug(ln, "Tipo: TabularArrayNode")
                Nodes.TabularArrayNode(null, hdr).consume(scanner, ln.depth, ctx)
            }
            hdr.inlineValues != null -> {
                ctx.debug(ln, "Tipo: InlineArrayNode")
                Nodes.InlineArrayNode(null, hdr).consume(scanner, ln.depth, ctx)
            }
            else -> {
                ctx.debug(ln, "Tipo: ListArrayNode")
                Nodes.ListArrayNode(null, hdr).consume(scanner, ln.depth, ctx)
            }
        }
    }

    private fun parseRootObject(scanner: LineScanner, ctx: ParseContext): Any? {
        ctx.debug(null, "Iniciando parsing do objeto raiz")
        return Nodes.ObjectNode(parentKey = null, baseIndent = 0).consume(scanner, 0, ctx)
    }
}

