package br.com.vexpera.ktoon.decoder

import kotlin.math.floor

/**
 * Representa uma linha do documento após o pré-processamento léxico.
 */
internal data class Line(
    val number: Int,
    val raw: String,
    val depth: Int,
    val content: String,
    val isBlankOutOfArrayScope: Boolean
)

/**
 * Normaliza e quebra o texto de entrada em linhas.
 */
internal object Lexer {
    fun lines(text: String): List<String> {
        // Normaliza terminadores de linha (aceita \r\n, \r e \n)
        val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
        return normalized.split('\n')
    }
}

/**
 * Leitor sequencial de linhas, responsável por rastrear profundidade, indentação e erros.
 */
internal class LineScanner(
    private val rawLines: List<String>,
    private val options: DecoderOptions
) {
    private var index = 0

    fun remaining(): Sequence<Line> = sequence {
        for (i in rawLines.indices) {
            yield(peekLineAt(i))
        }
    }

    fun peek(): Line? =
        rawLines.getOrNull(index)?.let { peekLineAt(index) }

    fun next(): Line? =
        rawLines.getOrNull(index++)?.let { peekLineAt(index - 1) }

    fun nextNonBlankDepth0OrThrow(): Line {
        while (true) {
            val ln = next() ?: error("Unexpected EOF while expecting a root token")
            if (ln.depth == 0 && ln.content.isNotEmpty()) return ln
            if (ln.depth == 0 && ln.isBlankOutOfArrayScope) continue
            if (options.strict && ln.depth > 0 && ln.content.isNotEmpty())
                parseError(ln, "Unexpected indentation at root")
        }
    }

    fun consumeUntilEnd() {
        index = rawLines.size
    }

    private fun peekLineAt(i: Int): Line {
        val raw = rawLines[i]
        val indent = countLeadingSpaces(raw)
        val content = raw.substring(indent).trimEnd()
        val isBlank = content.isEmpty()
        val depth = computeDepth(indent)

        if (options.strict) {
            if (indent % options.indentWidth != 0)
                parseError(i, raw, "Indentation must be a multiple of ${options.indentWidth}")
            if (raw.take(indent).any { it == '\t' })
                parseError(i, raw, "Tabs are not allowed in indentation")
            if (raw.endsWith(" "))
                parseError(i, raw, "Trailing spaces are not allowed")
        }

        return Line(
            number = i + 1,
            raw = raw,
            depth = depth,
            content = content,
            isBlankOutOfArrayScope = isBlank
        )
    }

    private fun countLeadingSpaces(s: String): Int {
        var n = 0
        while (n < s.length && s[n] == ' ') n++
        return n
    }

    private fun computeDepth(indentSpaces: Int): Int =
        if (options.strict)
            indentSpaces / options.indentWidth
        else
            floor(indentSpaces.toDouble() / options.indentWidth).toInt()

    private fun parseError(i: Int, raw: String, msg: String): Nothing =
        throw DecodeException("Line ${i + 1}: $msg. Raw: ${raw.show()}")
}
