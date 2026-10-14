@file:Suppress("unused", "MemberVisibilityCanBePrivate")

package br.com.vexpera.ktoon.decoder

/**
 * Decoder options (§13).
 *
 * @property strict strict-mode validation (§14): counts, widths, indentation, blank lines inside arrays.
 * @property debug print parsing traces to stdout; off by default, the library prints nothing otherwise.
 * @property indentWidth spaces per indentation level.
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
        val ctx = ParseContext(options)
        val lines = Lines.read(text, options)
        ctx.debug(null, "Decoding ${lines.size} lines")
        return Parser(lines, ctx).parseDocument()
    }
}
