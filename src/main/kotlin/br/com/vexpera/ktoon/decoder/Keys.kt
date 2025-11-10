package br.com.vexpera.ktoon.decoder

/**
 *
 * Utility responsible to decode keys of objects and field
 * Accept simple keys as quoted keys as well
 */
internal object Keys {

    // Regular expression to validate non-spade keys: letters, numbers, underscores, and periods.
    private val unquotedRegex = Regex("^[A-Za-z_][A-Za-z0-9_.]*$")

    /**
     * Decodes a key, checking if string parsing is needed.
     */
    fun decodeKeyToken(token: String): String {
        val t = token.trim()
        return if (t.startsWith("\"")) {
            // Key in quotes — uses the same string parser as value strings.
            ValueParsers.parseQuotedStringStrict(t)
        } else {
            // Validation for non-quoted names
            if (!unquotedRegex.matches(t))
                throw DecodeException("Invalid unquoted key: ${t.show()}")
            t
        }
    }
}
