package br.com.vexpera.ktoon.decoder

/**
 * One source line after indentation analysis. Blank lines keep their place (they matter inside arrays, §12);
 * comment lines (`#` after the indentation, a library extension) are dropped before parsing.
 */
internal data class Line(
    val number: Int,
    val raw: String,
    val depth: Int,
    val content: String,
) {
    val isBlank: Boolean get() = content.isEmpty()

    companion object {
        fun empty(): Line = Line(number = -1, raw = "", depth = 0, content = "")
    }
}

internal object Lines {

    /**
     * Splits [text] into lines (LF, CRLF or CR), computes the depth of each line and validates indentation (§12):
     * in strict mode, tabs in indentation and indentation that is not a multiple of the indent width are errors;
     * in non-strict mode the depth is floor(spaces / width), a tab counting as one level.
     * Whitespace-only lines are blank and never validated.
     */
    fun read(text: String, options: DecoderOptions): List<Line> {
        val raw = text.replace("\r\n", "\n").replace("\r", "\n").split('\n')
        val out = ArrayList<Line>(raw.size)
        raw.forEachIndexed { i, line ->
            val ws = line.takeWhile { it == ' ' || it == '\t' }
            val content = line.substring(ws.length).trim()
            if (content.isEmpty()) {
                out += Line(i + 1, line, 0, "")
                return@forEachIndexed
            }
            if (content.startsWith("#")) return@forEachIndexed

            val tabs = ws.count { it == '\t' }
            val spaces = ws.length - tabs
            if (options.strict) {
                if (tabs > 0) throw DecodeException("Line ${i + 1}: Tabs are not allowed in indentation. Line: ${line.show()}")
                if (spaces % options.indentWidth != 0)
                    throw DecodeException("Line ${i + 1}: Indentation must be a multiple of ${options.indentWidth}. Line: ${line.show()}")
            } else if (options.debug) {
                if (tabs > 0) println("[WARN line ${i + 1}] Tab found in indentation (lenient mode, counted as one level)")
                if (spaces % options.indentWidth != 0)
                    println("[WARN line ${i + 1}] Indentation not multiple of ${options.indentWidth} (lenient mode, rounding down)")
            }
            out += Line(i + 1, line, spaces / options.indentWidth + tabs, content)
        }
        return out
    }
}
