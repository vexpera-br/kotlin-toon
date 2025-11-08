package br.com.vexpera.ktoon.decoder

/**
 * Contexto compartilhado entre os estágios de parsing.
 * Guarda as opções do decoder e fornece utilitários de debug.
 */
internal class ParseContext(val options: DecoderOptions) {

    /** Delimitador padrão de documento — influencia quoting (§11). */
    val documentDelimiter: Char = ','

    /** Log de debug, ativo apenas se [DecoderOptions.debug] for true. */
    fun debug(line: Line?, message: String) {
        if (options.debug) {
            val at = line?.let { " [${it.number}]" } ?: ""
            println("[Decoder$at] $message")
        }
    }
}

/**
 * Exceção padrão para erros de decodificação.
 */
class DecodeException(message: String) : RuntimeException(message)

/**
 * Lança um [DecodeException] com informação contextual da linha.
 */
internal fun parseError(ln: Line, msg: String): Nothing =
    throw DecodeException("Line ${ln.number}: $msg. Line: ${ln.raw.show()}")

/**
 * Retorna uma string segura para exibição em mensagens de erro.
 */
internal fun String.show(max: Int = 200): String =
    if (length <= max) this else take(max) + "…"
