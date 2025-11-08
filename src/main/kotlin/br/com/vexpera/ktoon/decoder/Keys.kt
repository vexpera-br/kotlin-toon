package br.com.vexpera.ktoon.decoder

/**
 * Utilitário responsável por decodificar chaves de objetos e campos.
 * Aceita tanto chaves simples quanto chaves entre aspas.
 */
internal object Keys {

    // Regex para validar chaves não-aspadas: letras, números, underscore e ponto.
    private val unquotedRegex = Regex("^[A-Za-z_][A-Za-z0-9_.]*$")

    /**
     * Decodifica uma chave, verificando se precisa de parsing de string.
     */
    fun decodeKeyToken(token: String): String {
        val t = token.trim()
        return if (t.startsWith("\"")) {
            // Chave entre aspas — usa o mesmo parser de strings de valor
            ValueParsers.parseQuotedStringStrict(t)
        } else {
            // Validação para nomes não-aspados
            if (!unquotedRegex.matches(t))
                throw DecodeException("Invalid unquoted key: ${t.show()}")
            t
        }
    }
}
