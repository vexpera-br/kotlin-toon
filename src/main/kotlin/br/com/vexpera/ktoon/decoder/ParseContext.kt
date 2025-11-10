package br.com.vexpera.ktoon.decoder

/**
 * Shared context between parsing stages.
 * Stores decoder options and provides debugging utilities.
 */
internal class ParseContext(val options: DecoderOptions) {

    /** Standard document delimiter — influences quoting (§11). */
    val documentDelimiter: Char = ','

    /** Debug log, active only if [DecoderOptions.debug] is true.. */
    fun debug(line: Line?, message: String) {
        if (options.debug) {
            val at = line?.let { " [${it.number}]" } ?: ""
            println("[Decoder$at] $message")
        }
    }
}

/**
 * Standard exception for decoding errors.
 */
class DecodeException(message: String) : RuntimeException(message)

/**
 * Throws a [DecodeException] with contextual line information.
 */
internal fun parseError(ln: Line, msg: String): Nothing =
    throw DecodeException("Line ${ln.number}: $msg. Line: ${ln.raw.show()}")

/**
 * Returns a string that is safe to display in error messages.
 */
internal fun String.show(max: Int = 200): String =
    if (length <= max) this else take(max) + "…"
